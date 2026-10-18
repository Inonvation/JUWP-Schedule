package edu.jxslu.schedule.data.qzxy

import edu.jxslu.schedule.domain.QzxyClData
import edu.jxslu.schedule.domain.QzxyFrame
import edu.jxslu.schedule.domain.QzxyProtocol

/**
 * 用水流程的协议状态机（DESIGN §4.30）。
 *
 * 开阀、结束用水、清除候选的**唯一实现**：`QzxyViewModel` 只负责联网下单、流程文案、
 * 登录态与界面状态，蓝牙时序与成败判定全在这里。不含 Android API 与协程原语，
 * 注入假的 [Link] 与 [Gateway] 即可用单测钉住（状态 3 跳过停阀、停阀后先查再等轮询、
 * 清除候选命中即停、连续无回包提前收手、**上报失败不清设备记录**）。
 * 等待由调用方完成（真机 `delay`，单测直接跳过）。
 */
class QzxyWaterFlow(
    private val link: Link,
    private val gateway: Gateway,
) {

    /**
     * 蓝牙链路的最小接口。
     *
     * **建链由实现方负责**：`request` 内部要先保证链路可用（真机 = `ensureLink`，
     * 断过就重连一次），本类不做「有没有连着」的前置判断——第一帧查询就要顺带建链。
     * 返回 `null` = 设备没有回包（超时、发送失败或功能码不符，由实现方判定）。
     */
    interface Link {
        /** [functionCode] 用于回包的功能码校验：对不上的帧按「没有回包」处理。 */
        suspend fun request(functionCode: Int, payload: ByteArray, timeoutMillis: Int): ByteArray?
    }

    /**
     * 服务端侧的最小接口。真机实现包住 [QzxyRepository]。
     *
     * 上报**失败不抛异常**，把结果装进 [Upload]：失败原因要原样带给用户，
     * 要不要清设备记录由本类决定（结算失败时**不清**，见 [stop]）。
     */
    interface Gateway {
        suspend fun uploadConsume(xfData: String, randomNumber: String, protocolType: String): Upload
    }

    /**
     * 一次消费数据上报的结果。
     *
     * [error] 非空即失败；成功时 [clData] 是服务端签发的清除凭据（密文原文），
     * [consumeMoney] 单位是**厘**。
     */
    data class Upload(
        val clData: String? = null,
        val consumeMoney: String? = null,
        val error: String? = null,
    ) {
        val succeed: Boolean get() = error == null
    }

    /** 流程被挡的原因分类：调用方按它决定文案与出口（钱的事只有调用方知道）。 */
    enum class BlockedKind {
        Other,

        /** 设备没回包（连不上、超时、解不出）。 */
        NoState,

        /** 设备空闲：没有在放水、也没有待结算记录。 */
        DeviceIdle,

        /** 开阀被挡：设备空闲之外的状态（常见是状态 3 压着旧记录）。 */
        DeviceBusy,

        /** 状态到位了但消费数据拿不到。 */
        NoRecord,
    }

    /** 一条流程的最终结果。 */
    sealed interface Outcome {
        /** 开阀完成，`downData` 已被设备接受。 */
        data class Opened(val downData: ByteArray) : Outcome

        /**
         * 结算流程走到头。[uploaded] = 上报是否成功。
         *
         * **上报失败时 [cleared] 恒为 false 且设备记录保留**——记录是重试结算的唯一
         * 凭证；旧实现在失败时也清记录，等于把还能救回来的钱一次毁掉（设备随后空闲，
         * 再点「结束用水」只能得到「没有待结算的记录」）。放弃结算的出口是界面上的
         * 「清除设备记录」，那是一个显式选择，不是默认行为。
         */
        data class Settled(
            val uploaded: Boolean,
            val uploadError: String?,
            val consumeMoney: String?,
            val clDataRaw: String?,
            val cleared: Boolean,
            val clearLabel: String?,
            val clearDetail: String,
            /** 清除命中的候选 key（试通哪条记进 `QzxyClearStore`，下次排最前）。 */
            val clearKey: String? = null,
        ) : Outcome

        /** 流程被挡。 [reason] 是短标题，[detail] 是给用户看的排查提示。 */
        data class Blocked(
            val reason: String,
            val detail: String,
            val kind: BlockedKind = BlockedKind.Other,
        ) : Outcome
    }

    /** 清除环节的结果。 */
    data class ClearResult(val cleared: Boolean, val label: String?, val detail: String, val key: String? = null)

    /** 流程过程中的阶段回调，真机接到界面文案与诊断耗时，单测可忽略。 */
    var onStep: ((String) -> Unit)? = null

    /** 每条清除候选试完后的回调（标签、结果、是否通过），供界面列试错表。 */
    var onClearTrial: ((String, String, Boolean) -> Unit)? = null

    /** 阶段耗时回调（标签、毫秒），接诊断文本里的「连接 1200ms」那行。 */
    var onTiming: ((String, Long) -> Unit)? = null

    private suspend fun <T> timed(label: String, block: suspend () -> T): T {
        val startedAt = System.nanoTime()
        try {
            return block()
        } finally {
            onTiming?.invoke(label, (System.nanoTime() - startedAt) / 1_000_000)
        }
    }

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
        val state = timed("读状态") { readState(address) } ?: return Outcome.Blocked(
            reason = "设备没有回应可解析的数据",
            detail = "等待回包超时。重试一次；还不行就把诊断发出来",
            kind = BlockedKind.NoState,
        )
        if (state.deviceState != QzxyProtocol.STATE_IDLE) {
            return Outcome.Blocked(
                reason = "设备当前不可开",
                detail = blockedHint(state.deviceState),
                kind = BlockedKind.DeviceBusy,
            )
        }

        step("向服务端下单并下发")
        val downData = order(state)
        val reply = timed("下发开阀数据") {
            link.request(
                functionCode = QzxyProtocol.DOWN_RATE,
                payload = downData,
                timeoutMillis = RESPONSE_TIMEOUT_MILLIS,
            )
        } ?: return Outcome.Blocked(
            reason = "设备没有回应开阀数据",
            detail = PRE_DEDUCT_DETAIL + "（本次现象：等待回包超时）",
            kind = BlockedKind.NoState,
        )
        val response = decode(reply)
        if (response == null || !response.success) {
            return Outcome.Blocked(
                reason = "设备拒绝了开阀数据",
                detail = PRE_DEDUCT_DETAIL + "（${response?.summary ?: "回包解不出帧结构"}）",
                kind = BlockedKind.NoState,
            )
        }
        return Outcome.Opened(downData)
    }

    /**
     * 结束用水的蓝牙侧与服务端上报。
     *
     * 时序与真机验证过的口径一致：状态 3 直接采集（此时发 0x22 会被回 `81 01`）；
     * 否则停阀 → 「先查再等」轮询到状态 3（最多 [stopPollTimeoutMillis]，连续
     * [stopPollMaxFailures] 次读不到状态提前收手）→ 采集 → 上报 → **上报成功才**按
     * 候选表清记录（失败保留，见 [Outcome.Settled] 的说明）。
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
        var ready = timed("读状态") { readState(address) } ?: return Outcome.Blocked(
            reason = "设备没有回应可解析的数据",
            detail = "等待回包超时。重试一次；还不行就把诊断发出来",
            kind = BlockedKind.NoState,
        )
        var pollFailures = 0

        if (ready.deviceState == QzxyProtocol.STATE_IDLE) {
            return Outcome.Blocked(
                reason = "设备当前空闲",
                detail = "没有在放水，也没有待结算的记录。要开热水点「开阀」",
                kind = BlockedKind.DeviceIdle,
            )
        }

        if (ready.deviceState != QzxyProtocol.STATE_FINISHED_UNCOLLECTED) {
            step("向设备发送停止指令")
            val stopReply = timed("停阀") {
                link.request(
                    functionCode = QzxyProtocol.END_RATE,
                    payload = ByteArray(1),
                    timeoutMillis = RESPONSE_TIMEOUT_MILLIS,
                )
            }?.let { decode(it) }
            if (stopReply != null && !stopReply.success) {
                // 被拒不当场退出：设备可能已经结算完，0x22 自然不被接受。
                step("停止指令被拒：${stopReply.summary}")
            }

            step("等待设备结算")
            var lastState = ready.deviceState
            val deadline = nowMillis() + stopPollTimeoutMillis
            timed("等结算") {
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
        }

        if (ready.deviceState != QzxyProtocol.STATE_FINISHED_UNCOLLECTED) {
            if (pollFailures >= stopPollMaxFailures) {
                return Outcome.Blocked(
                    reason = "读不到设备状态",
                    detail = "蓝牙连着但设备连续 $pollFailures 次不回包，可能被官方 App 抢占或已断开。" +
                        "等几秒再点一次「结束用水」",
                    kind = BlockedKind.NoState,
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
        val collectReply = timed("读记录") {
            link.request(
                functionCode = QzxyProtocol.COLLECT_CONSUME,
                payload = ByteArray(1),
                timeoutMillis = RESPONSE_TIMEOUT_MILLIS,
            )
        }?.let { decode(it) }
        if (collectReply == null || !collectReply.success) {
            return Outcome.Blocked(
                reason = "设备没有返回可解析的消费数据",
                detail = collectReply?.summary ?: "等待回包超时",
                kind = BlockedKind.NoRecord,
            )
        }
        val record = collectReply.payload

        step("上报消费数据结算")
        val upload = timed("上报") {
            gateway.uploadConsume(
                xfData = QzxyFrame.bytesToHex(record),
                randomNumber = ready.randomNumber,
                protocolType = ready.protocolType.orEmpty(),
            )
        }
        if (!upload.succeed) {
            // 上报失败**不清设备记录**：记录是重试结算的唯一凭证。留着它，用户重试
            // 「结束用水」还能走通；清掉之后设备就空了，哪怕平台还能处理这笔单，
            // 客户端也再无凭证可报。放弃结算的显式出口是「清除设备记录」。
            return Outcome.Settled(
                uploaded = false,
                uploadError = upload.error,
                consumeMoney = null,
                clDataRaw = null,
                cleared = false,
                clearLabel = null,
                clearDetail = "设备记录已保留，可重试结算",
            )
        }

        // 设备端记录要清掉，否则下次开阀会说「消费数据未采集」。
        // 参数按候选表依次试，试到哪条由 clearRecord 自己改流程文案。
        val clDataPlain = upload.clData?.let { QzxyClData.decrypt(it) }
        val cleared = timed("清除") {
            clearRecord(
                address = address,
                record = record,
                clDataPlain = clDataPlain,
                verifyDelayMillis = verifyDelayMillis,
                clearTimeoutMillis = clearTimeoutMillis,
                maxAttempts = maxClearAttempts,
                preferredKey = preferredClearKey,
                delayMillis = delayMillis,
            )
        }
        return Outcome.Settled(
            uploaded = true,
            uploadError = null,
            consumeMoney = upload.consumeMoney,
            clDataRaw = upload.clData,
            cleared = cleared.cleared,
            clearLabel = cleared.label,
            clearDetail = cleared.detail,
            clearKey = cleared.key,
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
                if (clearedNow) return ClearResult(true, candidate.label, last, candidate.key)
                continue
            }
            onClearTrial?.invoke(candidate.label, last, false)
        }
        return ClearResult(false, null, last)
    }

    /** 读一次设备状态。无回包、被拒、解不出都返回 null（建链由 [Link] 实现完成）。 */
    private suspend fun readState(address: String): QzxyProtocol.DeviceState? {
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
        /**
         * 查询设备状态的等待上限。
         *
         * 1.5 秒在刚建链、系统正在写 CCCD 或设备刚从休眠醒来时偏紧；
         * 超时又会让 [Link] 直接 close()，用户看到的是「设备没有回应」。
         * 3 秒仍比人工重试便宜，先给足一帧往返的时间。
         */
        const val QUERY_TIMEOUT_MILLIS = 3_000

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
