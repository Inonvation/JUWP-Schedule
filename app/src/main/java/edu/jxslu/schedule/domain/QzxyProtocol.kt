package edu.jxslu.schedule.domain

/**
 * 趣智校园蓝牙水控的命令码与设备响应解析（DESIGN §4.30）。
 *
 * 数据来源同样只有公开逆向资料（看雪《趣智校园 app 分析》，2025-11-29），
 * 字段偏移在下面逐个标注。这套偏移量对应官方 App 的 `AnalyTools.analyWaterDatas`，
 * 单测钉的是「按该资料给的结构能解析」，真机是否一致要抓包核对。
 */
object QzxyProtocol {

    // ── 命令码（帧下标 4）──

    /** 查询设备信息。实测样本 `60000480230000a316`，响应带项目号、设备号、随机数、设备状态。 */
    const val QUERY_DEVICE: Int = 0x23

    /** 采集消费数据（官方类名 caijishuju）。 */
    const val COLLECT_CONSUME: Int = 0x85

    /** 下发费率数据，即开阀：数据体就是服务端返回的 downData。 */
    const val DOWN_RATE: Int = 0x21

    /** 结束费率。 */
    const val END_RATE: Int = 0x22

    /** 开始交易。 */
    const val START_DEAL: Int = 0x31

    /** 结束交易。 */
    const val STOP_DEAL: Int = 0x32

    /** 清除已采集的消费数据（官方类名 fanhuicunchu）。 */
    const val CLEAR_CONSUME: Int = 0x86

    /** 设置密钥（官方类名 settingKey）。 */
    const val SETTING_KEY: Int = 0x88

    // ── 设备主类型（mayDeviceType）──

    const val TYPE_WATER_METER: Int = 0
    const val TYPE_DRINKING: Int = 1
    const val TYPE_WASHER: Int = 2
    const val TYPE_HAIR_DRYER: Int = 3
    const val TYPE_CHARGER: Int = 4
    const val TYPE_AIR_CONDITIONER: Int = 5

    /** 热水器（水表）类型名，UI 与诊断文案共用。 */
    fun typeName(mainType: Int?): String = when (mainType) {
        TYPE_WATER_METER -> "热水器"
        TYPE_DRINKING -> "饮水机"
        TYPE_WASHER -> "洗衣机"
        TYPE_HAIR_DRYER -> "吹风机"
        TYPE_CHARGER -> "充电器"
        TYPE_AIR_CONDITIONER -> "空调"
        else -> "未知设备"
    }

    // ── 设备状态（payload[19]）──

    const val STATE_IDLE: Int = 0
    const val STATE_IN_ORDER: Int = 1
    const val STATE_CARD_CONSUMING: Int = 2
    const val STATE_FINISHED_UNCOLLECTED: Int = 3
    const val STATE_REMOTE_CONTROL: Int = 5

    /**
     * 6：**官方头文件里没有这个状态**。实测出现在停阀之后、状态 3 之前，
     * 持续五秒以上（2026-09-27，停阀后轮询 5.1 秒仍是 6，隔几秒再读才变 3），
     * 所以按「结算中/记录写入中」理解：这时候发 0x85 也拿不到记录。
     */
    const val STATE_SETTLING: Int = 6

    /** 设备状态文案；未知值直接给数字，方便抓包时对表。 */
    fun stateText(state: Int): String = when (state) {
        STATE_IDLE -> "空闲"
        STATE_IN_ORDER -> "有进行中的订单"
        STATE_CARD_CONSUMING -> "刷卡消费中"
        STATE_FINISHED_UNCOLLECTED -> "消费完成，数据待采集"
        STATE_REMOTE_CONTROL -> "远程控制模式"
        STATE_SETTLING -> "结算中（记录写入中）"
        else -> "未知状态（$state）"
    }

    /** 查询响应数据体的首字节，固定为 0x80。 */
    private const val RESPONSE_MARK: Int = 0x80

    /**
     * 成功回包的数据体首字节。请求帧里 `[3]` 是 `0x80`，回包里 `[3]` 变成 `0x81`，
     * 成败只能看数据体首字节——官方 iOS SDK 的 `KRWMUtils.isSuccessResponseWithData:`
     * 就是这一个判断。
     */
    const val RESPONSE_OK_MARK: Int = 0x80

    /** 官方 `KRErrorCodeUnknownError`。数据体不足两字节时取它。 */
    const val ERROR_UNKNOWN: Int = 0xFF

    /** 通用前缀长度：标记(1) + 项目号(4) + 设备号(4) + 账号号(4) + 序列号(6) + 状态(1)。 */
    private const val PREFIX_END: Int = 20

    /**
     * 设备查询响应的解析结果。
     *
     * [randomNumber] 是设备本次会话生成的交易校验随机数（十六进制小写），
     * 下单签名要拿它参与计算，也是「必须蓝牙连上设备才能开阀」的根本原因。
     * [mainType]/[subType] 在短包（数据体 23 字节那种）里不返回，为 null。
     */
    data class DeviceState(
        val projectId: Int,
        val deviceId: Int,
        val accountId: Int,
        val snCode: String,
        val deviceState: Int,
        val randomNumber: String,
        val mainType: Int?,
        val subType: Int?,
        /** 协议版本号，长包里才有；短包为 null。下单时原样回传。 */
        val protocolType: String? = null,
        /** 原始数据体十六进制。偏移量对不上时全靠它对着抓包结果比。 */
        val rawHex: String = "",
    ) {
        val isWaterMeter: Boolean
            get() = mainType == null || mainType == TYPE_WATER_METER
    }

    /**
     * 解析设备查询响应。数据体结构按长度分四种（官方 `analyWaterDatas` 的 case 23/28/48/49+）：
     *
     * ```
     * [0]        0x80 标记
     * [1..4]     projectId
     * [5..8]     deviceId
     * [9..12]    accountId
     * [13..18]   snCode（6 字节）
     * [19]       设备状态
     * 短包        [19] 状态，[21..22] 2 字节随机数，25 字节起 [23] 主类型、[24] 子类型
     * 长包        [19] 协议版本，[24..27] 4 字节随机数，[28] 状态，[29] 主类型，[30] 子类型
     * ```
     *
     * 结构对不上（长度不足、标记不符）返回 null。长度分支按「够不够读到该字段」判定，
     * 不照搬官方那几个精确 case：服务端多返回一个字节不该让整包作废。
     *
     * **长包那段偏移是读公开反编译代码推出来的，把握最低**：同一份代码里短包拿 [19]
     * 当状态、长包拿 [19] 当协议版本，两个口径对不上。所以长包解析结果同时附 [DeviceState.rawHex]，
     * 真机对不上时先看它。
     */
    fun parseDeviceState(payload: ByteArray): DeviceState? {
        if (payload.size < PREFIX_END) return null
        if ((payload[0].toInt() and 0xFF) != RESPONSE_MARK) return null

        val projectId = readInt(payload, 1)
        val deviceId = readInt(payload, 5)
        val accountId = readInt(payload, 9)
        val snCode = QzxyFrame.bytesToHex(payload.copyOfRange(13, 19))
        val deviceState = payload[19].toInt() and 0xFF

        return when {
            payload.size >= 30 -> DeviceState(
                projectId = projectId,
                deviceId = deviceId,
                accountId = accountId,
                snCode = snCode,
                deviceState = payload[28].toInt() and 0xFF,
                randomNumber = QzxyFrame.bytesToHex(payload.copyOfRange(24, 28)),
                mainType = payload[29].toInt() and 0xFF,
                subType = payload[30].toInt() and 0xFF,
                protocolType = QzxyFrame.bytesToHex(byteArrayOf(payload[19])),
                rawHex = QzxyFrame.bytesToHex(payload),
            )

            payload.size >= 25 -> DeviceState(
                projectId = projectId,
                deviceId = deviceId,
                accountId = accountId,
                snCode = snCode,
                deviceState = deviceState,
                randomNumber = QzxyFrame.bytesToHex(payload.copyOfRange(21, 23)),
                mainType = payload[23].toInt() and 0xFF,
                subType = payload[24].toInt() and 0xFF,
                rawHex = QzxyFrame.bytesToHex(payload),
            )

            else -> DeviceState(
                projectId = projectId,
                deviceId = deviceId,
                accountId = accountId,
                snCode = snCode,
                deviceState = deviceState,
                randomNumber = QzxyFrame.bytesToHex(payload.copyOfRange(21, 23)),
                mainType = null,
                subType = null,
                rawHex = QzxyFrame.bytesToHex(payload),
            )
        }
    }

    // ── 设备回包 ──

    /**
     * 一条回包的解析结果（DESIGN §4.30）。
     *
     * 之前只看「回读到的设备状态」判断命令是否生效，设备拒收时看不到任何理由，
     * 清除命令卡了三轮就卡在这里。官方 SDK 把成败与错误码都放在数据体里：
     * 首字节 `0x80` 是成功，否则**第 2 字节**是 [errorCodeText] 里的错误码。
     */
    data class DeviceResponse(
        val functionCode: Int,
        val payload: ByteArray,
        val success: Boolean,
        val errorCode: Int?,
    ) {
        val functionCodeHex: String get() = "%02x".format(functionCode)

        /** 错误码中文名；成功时给 `-`。 */
        val errorText: String get() = errorCode?.let(::errorCodeText) ?: "-"

        val payloadHex: String get() = QzxyFrame.bytesToHex(payload)

        /** 一句话摘要，直接进调试日志与诊断文本。 */
        val summary: String
            get() = buildString {
                append("功能码 ").append(functionCodeHex)
                if (success) {
                    append(" 成功")
                } else {
                    append(" 被拒：").append(errorCode).append(" ").append(errorText)
                }
                append("，数据体 ").append(payloadHex)
            }
    }

    /** 解析一条回包帧。帧结构对不上返回 null。 */
    fun decodeResponse(frame: ByteArray): DeviceResponse? {
        val parsed = QzxyFrame.parseFrame(frame) ?: return null
        val payload = parsed.payload
        val success = payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == RESPONSE_OK_MARK
        val errorCode = when {
            success -> null
            payload.size >= 2 -> payload[1].toInt() and 0xFF
            else -> ERROR_UNKNOWN
        }
        return DeviceResponse(parsed.functionCode, payload, success, errorCode)
    }

    /** 错误码中文名。取值来自官方头文件 `KRWMDefine.h` 的 `KRErrorCode`。 */
    fun errorCodeText(code: Int): String = when (code) {
        0x01 -> "包头错误"
        0x02 -> "包长度错误"
        0x04 -> "功能码错误（设备不认这条命令）"
        0x05 -> "数据格式错误（参数不对）"
        0x06 -> "校验和错误"
        0x07 -> "结束码错误"
        0x08 -> "来源错误"
        ERROR_UNKNOWN -> "未知错误"
        else -> "未登记错误码"
    }

    // ── 消费记录 ──

    /**
     * 0x85 回包的数据体（消费记录）。
     *
     * 字段偏移有两份独立来源互相印证，不再是猜的：
     *
     * - 官方 iOS SDK 的 `-[KRConsumptionDetailsObject setConsumptionDetailsFromData:]`
     *   反汇编（2026-09-27，`SDKQZNetworkOffline` 1.2.1）；
     * - 看雪分析里 `AnalyTools.analyWaterDatas` 的反编译片段。
     *
     * 两者对 `timeId` / `projectId` / `deviceId` / `accountId` / `accountType` /
     * `consumeMoney` 的偏移完全一致。`[0]` 是成功标记，不属于记录本身。
     *
     * [tac] 只在 63 字节的长记录里出现（`[58..62]`）。官方注释写着
     * 「通过当前数据即时计算得到的，每次需验证通过后才能清除数据」——本校设备回的是
     * 42 字节短记录，所以拿不到它，清除要另找凭据。
     */
    data class ConsumptionRecord(
        /** 时间序号 `yyMMddHHmmss`，一条记录的唯一标识。 */
        val timeId: String,
        val projectId: Int,
        val deviceId: Int,
        val accountId: Int,
        val accountType: Int,
        val useCount: Int,
        /** 预扣金额，单位厘。 */
        val preDeductMoney: Int,
        /** 本次消费，单位厘。 */
        val consumeMoney: Int,
        val rate: Int,
        val macAddress: String,
        /** 校验码，长记录才有。 */
        val tac: String? = null,
        val rawHex: String,
    )

    /** 短记录长度：官方 `length >= 42` 就走短记录分支。 */
    private const val CONSUME_MIN_SIZE: Int = 42

    /** 带 [ConsumptionRecord.tac] 的长记录长度，官方按 `== 63` 判定。 */
    private const val CONSUME_TAC_SIZE: Int = 63

    fun parseConsumption(payload: ByteArray): ConsumptionRecord? {
        if (payload.size < CONSUME_MIN_SIZE) return null
        if ((payload[0].toInt() and 0xFF) != RESPONSE_OK_MARK) return null
        return ConsumptionRecord(
            timeId = QzxyFrame.bytesToHex(payload.copyOfRange(1, 7)),
            projectId = readInt(payload, 7),
            deviceId = readInt(payload, 11),
            accountId = readInt(payload, 15),
            accountType = payload[19].toInt() and 0xFF,
            useCount = readInt(payload, 20),
            preDeductMoney = readInt(payload, 24),
            consumeMoney = readInt(payload, 28),
            rate = readInt(payload, 32),
            macAddress = QzxyFrame.bytesToHex(payload.copyOfRange(36, 42)),
            tac = if (payload.size >= CONSUME_TAC_SIZE) {
                QzxyFrame.bytesToHex(payload.copyOfRange(58, 63))
            } else {
                null
            },
            rawHex = QzxyFrame.bytesToHex(payload),
        )
    }

    // ── 清除命令的参数候选 ──

    /**
     * 一条待试的清除命令。
     *
     * [key] 是这条候选的**稳定标识**，不含设备信息：试通哪条之后把它记在本机，
     * 下次直接排到最前面。不能用 [payload] 当标识——参数是从当次记录与当次凭据算出来的，
     * 换一次用水就全变了。
     */
    data class ClearCandidate(
        val key: String,
        val label: String,
        val functionCode: Int,
        val payload: ByteArray,
    ) {
        val payloadHex: String get() = QzxyFrame.bytesToHex(payload)
    }

    /** 清除候选的稳定标识。 */
    object ClearKey {
        const val CL_DATA_RAW = "clData.raw"
        const val CL_DATA_HEX = "clData.hex"
        const val CL_DATA_PART = "clData.part"
        const val CL_DATA_JOINED = "clData.joined"
        const val RECORD_FULL = "record.full"
        const val RECORD_NO_MARK = "record.noMarker"
        const val RECORD_DIGEST = "record.digest"
        const val RECORD_DIGEST_TYPE = "record.digestType"
        const val RECORD_TIME_ID = "record.timeId"
        const val RECORD_TIME_ID_MAC = "record.timeIdMac"
        const val RECORD_MAC = "record.mac"
        const val EMPTY = "empty"
    }

    /**
     * 设备清除命令要的「记录摘要」：时间序号 + 项目号 + 设备号 + 账号号 + 使用次数，
     * 共 22 字节。
     *
     * **不是猜的**：2026-09-27 服务端凭据 `clData` 解密后的第二段就是这个布局，
     * 把它发出去设备立刻回到空闲（见 DESIGN §4.30）。所以清除不必依赖服务端凭据——
     * 从刚读回的记录里切片就能得到，记录早就结算过、上传被拒时照样能清。
     *
     * 末尾四字节取记录 `[20..23]`（使用次数）。那次实测里账户类别也是 2，与使用次数撞值，
     * 另一种读法见 [recordDigestWithAccountType]，两个都进候选表。
     */
    fun recordDigest(record: ByteArray): ByteArray? {
        if (record.size < CONSUME_MIN_SIZE) return null
        return record.copyOfRange(1, 19) + record.copyOfRange(20, 24)
    }

    /** [recordDigest] 的另一种读法：末四字节是账户类别（大端补成四字节）。 */
    fun recordDigestWithAccountType(record: ByteArray): ByteArray? {
        if (record.size < CONSUME_MIN_SIZE) return null
        val accountType = record[19].toInt() and 0xFF
        return record.copyOfRange(1, 19) + byteArrayOf(0, 0, 0, accountType.toByte())
    }

    /**
     * 清除命令（0x86）的参数候选，**实测确认过的那条排第一**。
     *
     * 功能码与参数布局现在都有实证：0x86 由官方 SDK 的 `krClearConsumptionDetails:`
     * 与看雪反编译的 `CmdBtUtils.fanhuicunchu` 双向确认；参数就是 [recordDigest] 那
     * 22 字节——服务端凭据 `clData` 解密后的第二段与它逐字节相同（见 [QzxyClData]）。
     *
     * 其余候选留着当保险：服务端哪天改凭据格式、或设备换成别的记录布局，
     * 还能靠它们试出来，不至于整个功能卡死。
     *
     * [preferredKey] 是上次试通的那条（[ClearKey]），给了就排到最前面。
     */
    fun clearCandidates(
        record: ByteArray,
        clDataPlain: ByteArray? = null,
        preferredKey: String? = null,
    ): List<ClearCandidate> {
        val out = mutableListOf<ClearCandidate>()

        // 记录摘要排最前：实测确认过布局，而且从记录切片就够，不依赖上传成功。
        // 记录早就结算过、上传被服务端拒的场景（就是卡住用户的那种）靠它解套。
        if (record.size >= CONSUME_MIN_SIZE) {
            recordDigest(record)?.let {
                out += ClearCandidate(
                    ClearKey.RECORD_DIGEST,
                    "记录摘要（时间序号+项目+设备+账号+次数）",
                    CLEAR_CONSUME,
                    it,
                )
            }
            recordDigestWithAccountType(record)?.let {
                out += ClearCandidate(
                    ClearKey.RECORD_DIGEST_TYPE,
                    "记录摘要（末四字节按账户类别）",
                    CLEAR_CONSUME,
                    it,
                )
            }
        }

        // 服务端凭据：内容与记录摘要一致，但它是服务端签发的，格式变了它能兜住
        if (clDataPlain != null && clDataPlain.isNotEmpty()) {
            val text = clDataPlain.toString(Charsets.US_ASCII)
            out += ClearCandidate(ClearKey.CL_DATA_RAW, "服务端凭据原文", CLEAR_CONSUME, clDataPlain)
            hexOrNull(text)?.let {
                out += ClearCandidate(ClearKey.CL_DATA_HEX, "服务端凭据按十六进制解", CLEAR_CONSUME, it)
            }
            val parts = text.split('-').filter { it.isNotEmpty() }
            parts.forEachIndexed { index, part ->
                hexOrNull(part)?.let {
                    out += ClearCandidate(
                        ClearKey.CL_DATA_PART + index,
                        "服务端凭据第 ${index + 1} 段",
                        CLEAR_CONSUME,
                        it,
                    )
                }
            }
            if (parts.size > 1) {
                hexOrNull(parts.joinToString(""))?.let {
                    out += ClearCandidate(ClearKey.CL_DATA_JOINED, "服务端凭据两段拼起来", CLEAR_CONSUME, it)
                }
            }
        }

        if (record.size >= CONSUME_MIN_SIZE) {
            out += ClearCandidate(ClearKey.RECORD_FULL, "整条消费记录", CLEAR_CONSUME, record)
            out += ClearCandidate(
                ClearKey.RECORD_NO_MARK,
                "记录去掉标记字节",
                CLEAR_CONSUME,
                record.copyOfRange(1, record.size),
            )
            val timeId = record.copyOfRange(1, 7)
            val mac = record.copyOfRange(36, 42)
            out += ClearCandidate(ClearKey.RECORD_TIME_ID, "记录的时间序号（6 字节）", CLEAR_CONSUME, timeId)
            out += ClearCandidate(
                ClearKey.RECORD_TIME_ID_MAC,
                "时间序号 + 设备 MAC",
                CLEAR_CONSUME,
                timeId + mac,
            )
            out += ClearCandidate(ClearKey.RECORD_MAC, "设备 MAC（6 字节）", CLEAR_CONSUME, mac)
        }

        out += ClearCandidate(ClearKey.EMPTY, "空参数", CLEAR_CONSUME, ByteArray(1))

        val distinct = out.distinctBy { it.functionCode.toString() + QzxyFrame.bytesToHex(it.payload) }
        val preferred = preferredKey?.let { key -> distinct.firstOrNull { it.key == key } }
        return if (preferred == null) distinct else listOf(preferred) + distinct.filter { it !== preferred }
    }

    /** 十六进制文本 → 字节；不是合法十六进制返回 null。 */
    private fun hexOrNull(text: String): ByteArray? =
        QzxyFrame.hexToBytes(text)?.takeIf { it.isNotEmpty() }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until 4) {
            value = (value shl 8) or (bytes[offset + i].toInt() and 0xFF)
        }
        return value
    }
}
