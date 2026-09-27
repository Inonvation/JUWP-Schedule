package edu.jxslu.schedule.data.qzxy

import edu.jxslu.schedule.domain.QzxyClData
import edu.jxslu.schedule.domain.QzxyFrame
import edu.jxslu.schedule.domain.QzxyProtocol

/**
 * 用水流程的协议状态机（DESIGN §4.30）。
 *
 * 从 `QzxyViewModel` 抽出来的理由：开阀、结束用水、清除候选的判定原先只能靠真机验证。
 * 抽成不含 Android API 与协程的纯 JVM 类，注入假的 [Link] 与 [Gateway]，这些分支
 * （状态 3 跳过停阀、轮询到状态 3、候选命中即停、连续不回包提前收手）就能用单测钉住。
 *
 * 与 ViewModel 的分工：本类只回答「下一步该发什么、看到什么回包算成功」；
 * 联网下单（要签名、要用服务端登记 MAC）、流程文案、登录态都留在 ViewModel。
 * 等待由调用方完成（真机 `delay`，单测直接跳过）。
 */
class QzxyWaterFlow(
    private val link: Link,
    private val gateway: Gateway,
) {

    /**
     * 蓝牙链路的最小接口。真机实现包住 [QzxyGattLink]（含建链与重连），单测给假实现。
     *
     * [request] 返回 `null` = 设备没有回包（超时或功能码不符，由实现方判定）。
     */
    interface Link {
        fun isConnectedTo(address: String): Boolean

        /** [functionCode] 用于回包的功能码校验：对不上的帧按「没有回包」处理。 */
        suspend fun request(functionCode: Int, payload: ByteArray, timeoutMillis: Int): ByteArray?
    }

    /**
     * 服务端侧的最小接口。真机实现包住 [QzxyRepository]。
     *
     * 上传**失败不抛异常**、返回 null：结算被拒也照样清记录（重复上报会被服务端拒，
     * 记录留着会挡住后续开阀），要不要继续由本类决定。
     */
    interface Gateway {
        /** 返回结算响应里的 `clData`（清除凭据密文）；失败或服务端没给就是 null。 */
        suspend fun uploadConsume(xfData: String, randomNumber: String, protocolType: String): String?
    }

    /** 一条流程的最终结果。 */
    sealed interface Outcome {
        /** 开阀完成，`downData` 已被设备接受。 */
        data class Opened(val downData: ByteArray) : Outcome

        /** 结算完成（结算请求已发出，成败由调用方另行核对）。 */
        data class Settled(val cleared: Boolean, val clearLabel: String?, val clearDetail: String) : Outcome

        /** 流程被挡。 [reason] 是短标题，[detail] 是给用户看的排查提示。 */
        data class Blocked(val reason: String, val detail: String) : Outcome
    }

    /** 清除环节的结果。 */
    data class ClearResult(val cleared: Boolean, val label: String?, val detail: String)

    /** 流程过程中的阶段回调，真机接到界面文案，单测可忽略。 */
    var onStep: ((String) -> Unit)? = null

    /** 每条清除候选试完后的回调（标签、结果、是否通过），供界面列试错表。 */
    var onClearTrial: ((String, String, Boolean) -> Unit)? = null

    /**
     * 解一条回包。设备回的是 `#<hex>\n` 文本包装（见 [QzxyFrame.unwrap]），
     * 解不开就按原字节试——两种形态都兼容。
     */
    private fun decode(raw: ByteArray): QzxyProtocol.DeviceResponse? =
        QzxyProtocol.decodeResponse(QzxyFrame.unwrap(raw) ?: raw)

    private fun step(text: String) {
        onStep?.invoke(text)
    }

    /**
     * 开阀的蓝牙侧：读状态 → 校验空闲 → 把调用方下单拿到的 `downData` 写进设备。
     *
     * [order] 由调用方提供（签名与登记 MAC 都在它那边），本类只管蓝牙前置校验与写入。
     * [order] 抛出的异常原样上抛，由调用方按「下单失败」处理。
     */
    suspend fun open(
        address: String,
        order: suspend (QzxyProtocol.DeviceState) -> ByteArray,
    ): Outcome {
        step("读取设备状态")
        val state = readState(address) ?: return Outcome.Blocked(
            reason = "设备没有回应可解析的数据",
            detail = "等待回包超时。重试一次；还不行就把诊断发出来",
        )
        if (state.deviceState != QzxyProtocol.STATE_IDLE) {
            return Outcome.Blocked(reason = "设备当前不可开", detail = blockedHint(state.deviceState))
        }

        step("向服务端下单并下发")
        val downData = order(state)
        val reply = link.request(
            functionCode = QzxyProtocol.DOWN_RATE,
            payload = downData,
            timeoutMillis = RESPONSE_TIMEOUT_MILLIS,
        ) ?: return Outcome.Blocked(
            reason = "设备没有回应开阀数据",
            detail = PRE_DEDUCT_DETAIL + "（本次现象：等待回包超时）",
        )
        val response = decode(reply)
        if (response == null || !response.success) {
            return Outcome.Blocked(
                reason = "设备拒绝了开阀数据",
                detail = PRE_DEDUCT_DETAIL + "（${response?.summary ?: "回包解不出帧结构"}）",
            )
        }
        return Outcome.Opened(downData)
    }

    /**
     * 结束用水的蓝牙侧与服务端上报。
     *
     * 时序与真机验证过的口径一致：状态 3 直接采集（此时发 0x22 会被回 `81 01`）；
     * 否则停阀 → 「先查再等」轮询到状态 3（最多 [stopPollTimeoutMillis]，连续
     * [stopPollMaxFailures] 次读不到状态提前收手）→ 采集 → 上报 → 按候选表清记录。
     */
    suspend fun stop(
        address: String,
        stopPollTimeoutMillis: Long,
        stopPollIntervalMillis: Long,
        stopPollMaxFailures: Int,
        verifyDelayMillis: Long,
        clearTimeoutMillis: Int,
        maxClearAttempts: Int,
        preferredClearKey: String?,
        nowMillis: () -> Long,
        delayMillis: suspend (Long) -> Unit,
    ): Outcome {
        step("读取设备状态")
        var ready = readState(address) ?: return Outcome.Blocked(
            reason = "设备没有回应可解析的数据",
            detail = "等待回包超时。重试一次；还不行就把诊断发出来",
        )
        var pollFailures = 0

        if (ready.deviceState == QzxyProtocol.STATE_IDLE) {
            return Outcome.Blocked(
                reason = "设备当前空闲",
                detail = "没有在放水，也没有待结算的记录。要开热水点「开阀」",
            )
        }

        if (ready.deviceState != QzxyProtocol.STATE_FINISHED_UNCOLLECTED) {
            step("向设备发送停止指令")
            val stopReply = link.request(
                functionCode = QzxyProtocol.END_RATE,
                payload = ByteArray(1),
                timeoutMillis = RESPONSE_TIMEOUT_MILLIS,
            )?.let { decode(it) }
            if (stopReply != null && !stopReply.success) {
                // 被拒不当场退出：设备可能已经结算完，0x22 自然不被接受。
                step("停止指令被拒：${stopReply.summary}")
            }

            step("等待设备结算")
            var lastState = ready.deviceState
            val deadline = nowMillis() + stopPollTimeoutMillis
            while (nowMillis() < deadline) {
                // 先问再等：设备往往在停阀回包到达时就已经结算完
                val polled = readState(address)
                if (polled == null) {
                    if (++pollFailures >= stopPollMaxFailures) break
                } else {
                    pollFailures = 0
                    ready = polled
                    if (polled.deviceState == QzxyProtocol.STATE_FINISHED_UNCOLLECTED) break
                    if (polled.deviceState != lastState) {
                        lastState = polled.deviceState
                        step("等待设备结算（${QzxyProtocol.stateText(polled.deviceState)}）")
                    }
                }
                delayMillis(stopPollIntervalMillis)
            }
        }

        if (ready.deviceState != QzxyProtocol.STATE_FINISHED_UNCOLLECTED) {
            if (pollFailures >= stopPollMaxFailures) {
                return Outcome.Blocked(
                    reason = "读不到设备状态",
                    detail = "蓝牙连着但设备连续 $pollFailures 次不回包，可能被官方 App 抢占或已断开。" +
                        "等几秒再点一次「结束用水」",
                )
            }
            val stillRunning = ready.deviceState == QzxyProtocol.STATE_IN_ORDER ||
                ready.deviceState == QzxyProtocol.STATE_CARD_CONSUMING
            return Outcome.Blocked(
                reason = if (stillRunning) "停阀指令没被接受" else "设备还没结算完",
                detail = if (stillRunning) {
                    "当前状态：${QzxyProtocol.stateText(ready.deviceState)}，水可能还在流。" +
                        "再点一次「结束用水」，还不行就把诊断发出来"
                } else {
                    "当前状态：${QzxyProtocol.stateText(ready.deviceState)}。" +
                        "水已停，设备通常几秒内写完记录，过几秒再点一次「结束用水」"
                },
            )
        }

        step("读取消费数据")
        val collectReply = link.request(
            functionCode = QzxyProtocol.COLLECT_CONSUME,
            payload = ByteArray(1),
            timeoutMillis = RESPONSE_TIMEOUT_MILLIS,
        )?.let { decode(it) }
        if (collectReply == null || !collectReply.success) {
            return Outcome.Blocked(
                reason = "设备没有返回可解析的消费数据",
                detail = collectReply?.summary ?: "等待回包超时",
            )
        }
        val record = collectReply.payload

        step("上报消费数据结算")
        // 结算被拒也照样往下清：记录多半已经结算过，留着会挡后续开阀
        val clDataRaw = gateway.uploadConsume(
            xfData = QzxyFrame.bytesToHex(record),
            randomNumber = ready.randomNumber,
            protocolType = ready.protocolType.orEmpty(),
        )
        val clDataPlain = clDataRaw?.let { QzxyClData.decrypt(it) }

        val cleared = clearRecord(
            address = address,
            record = record,
            clDataPlain = clDataPlain,
            verifyDelayMillis = verifyDelayMillis,
            clearTimeoutMillis = clearTimeoutMillis,
            maxAttempts = maxClearAttempts,
            preferredKey = preferredClearKey,
            delayMillis = delayMillis,
        )
        return Outcome.Settled(
            cleared = cleared.cleared,
            clearLabel = cleared.label,
            clearDetail = cleared.detail,
        )
    }

    /**
     * 按候选表依次发 0x86，两层判定：设备回包成功 + 回读状态离开「消费完成」。
     * [preferredKey]（上次试通的那条）排最前，命中即返回。
     */
    suspend fun clearRecord(
        address: String,
        record: ByteArray,
        clDataPlain: ByteArray?,
        verifyDelayMillis: Long,
        clearTimeoutMillis: Int,
        maxAttempts: Int,
        preferredKey: String?,
        delayMillis: suspend (Long) -> Unit,
    ): ClearResult {
        val candidates = QzxyProtocol.clearCandidates(record, clDataPlain, preferredKey).take(maxAttempts)
        var last = "没有可试的参数"
        for ((index, candidate) in candidates.withIndex()) {
            step("试清除 ${index + 1}/${candidates.size}：${candidate.label}")
            val reply = link.request(
                functionCode = candidate.functionCode,
                payload = candidate.payload,
                timeoutMillis = clearTimeoutMillis,
            )?.let { decode(it) }
            if (reply == null) {
                last = "${candidate.label}：设备没有回包"
            } else if (!reply.success) {
                last = "${candidate.label}：设备拒收，错误码 ${reply.errorCode}（${reply.errorText}）"
            } else {
                delayMillis(verifyDelayMillis)
                val after = readState(address)
                val clearedNow = after != null && after.deviceState != QzxyProtocol.STATE_FINISHED_UNCOLLECTED
                last = if (clearedNow) {
                    "${candidate.label}：设备已回到空闲"
                } else {
                    "${candidate.label}：设备回了成功，但记录还在（" +
                        (after?.let { QzxyProtocol.stateText(it.deviceState) } ?: "状态读不到") + "）"
                }
                onClearTrial?.invoke(candidate.label, last, clearedNow)
                if (clearedNow) return ClearResult(true, candidate.label, last)
                continue
            }
            onClearTrial?.invoke(candidate.label, last, false)
        }
        return ClearResult(false, null, last)
    }

    /** 读一次设备状态。连不上、无回包、被拒、解不出都返回 null。 */
    private suspend fun readState(address: String): QzxyProtocol.DeviceState? {
        if (!link.isConnectedTo(address)) return null
        val raw = link.request(
            functionCode = QzxyProtocol.QUERY_DEVICE,
            payload = ByteArray(1),
            timeoutMillis = QUERY_TIMEOUT_MILLIS,
        ) ?: return null
        val response = decode(raw) ?: return null
        if (!response.success) return null
        return QzxyProtocol.parseDeviceState(response.payload)
    }

    private fun blockedHint(deviceState: Int): String = when (deviceState) {
        QzxyProtocol.STATE_FINISHED_UNCOLLECTED ->
            "设备上有一条未结算的用水记录。点「结束用水」走一遍结算并清除，" +
                "或直接点「清除设备记录」"
        QzxyProtocol.STATE_SETTLING ->
            "上一条用水记录还在写入（状态 6），等几秒再点「开阀」"
        else -> QzxyProtocol.stateText(deviceState)
    }

    companion object {
        const val RESPONSE_TIMEOUT_MILLIS = 5_000
        const val QUERY_TIMEOUT_MILLIS = 1_500

        /**
         * 下单成功、`downData` 却没写进设备时的提示。这一刻服务端已经建单并预扣，
         * 用户重试会再下一单（服务端对「同一台设备还有进行中的订单」回 307），
         * 预扣却已经挂在账上。
         */
        const val PRE_DEDUCT_DETAIL =
            "服务端那边已经下单并预扣，设备却没出水。别连点重试——重试可能被服务端" +
                "以「设备正在使用中」拒绝，而预扣还挂着。先在「消费记录」里核对有没有这一单，" +
                "再用官方 App 处理"
    }
}
