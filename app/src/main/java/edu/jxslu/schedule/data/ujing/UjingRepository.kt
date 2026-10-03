package edu.jxslu.schedule.data.ujing

import edu.jxslu.schedule.data.qiekj.QiekjJson
import edu.jxslu.schedule.domain.UjingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * U净 仓库（DESIGN §4.37）：登录态、只读链路（附近洗衣房 / 空闲看板 / 扫码识别 / 套餐）
 * 与订单链（P2：下单 / 支付参数 / 详情 / 取消 / 云端启动）。
 *
 * 编排边界：指纹头与端点关 [UjingApi]，会话持久化关 [UjingSessionStore]，订单快照
 * 关 [UjingOrderStore]（跨进程恢复），这里只做「拿会话 → 调接口 → 解 data 层 → 落快照」。
 *
 * 红线（DESIGN §4.37）：token 持久化、只在用户主动时发码（60 秒冷却由 UI 控）、
 * 会话过期**不做任何自动重登**——[markExpired] 之后由用户重新登录；
 * **写操作零自动重试**（重发与否由调用方按「先查状态」判定，本类只发一次）。
 */
class UjingRepository(
    private val sessionStore: UjingSessionStore,
    private val orderStore: UjingOrderStore,
    /** 历史账单（本 App 发起的每一笔都留痕，见 [UjingHistoryStore]）。 */
    private val historyStore: UjingHistoryStore,
    /**
     * debug 专用：`program/info` 原始响应的落盘目录（release 恒 null）。
     * 每台设备覆盖一份，供补「投放加购声明字段」等协议样本，见 [dumpProgramInfo]。
     */
    private val debugDumpDir: File? = null,
) {
    private val api = UjingApi.create()

    /** 登录态流（对齐趣智 / 快趣的 `loggedIn`）：任何窗口里的登录 / 退出都即时反映。 */
    private val _loggedIn = MutableStateFlow(sessionStore.read() != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    fun localSession(): UjingSession? = sessionStore.read()

    /** 登录表单预填手机号（上次登录用过的）。 */
    fun lastMobile(): String = sessionStore.lastMobile()

    fun logout() {
        sessionStore.clear()
        _loggedIn.value = false
    }

    /** 会话过期（服务端判定）：清 token 留手机号，UI 引导重新登录。 */
    fun markExpired() {
        sessionStore.clearToken()
        _loggedIn.value = false
    }

    // ── 登录 ──

    suspend fun requestCaptcha(mobile: String) = withContext(Dispatchers.IO) {
        api.requestCaptcha(mobile).requireSuccess()
    }

    suspend fun login(mobile: String, captcha: String): UjingSession = withContext(Dispatchers.IO) {
        val envelope = api.login(mobile, captcha)
        envelope.requireSuccess()
        val data = envelope.decodeData<UjingLoginData>()
        val token = data?.token?.takeIf { it.isNotBlank() }
            ?: throw UjingApiException("登录响应缺少令牌")
        val session = UjingSession(
            mobile = mobile.trim(),
            token = token,
            userId = data.userId.orEmpty(),
        )
        sessionStore.save(session)
        _loggedIn.value = true
        session
    }

    // ── 只读链路 ──

    /**
     * 扫码识别设备（`qrCode` = 二维码原文，直接透传）。
     * debug 包把原文与响应都落盘（[dumpScanWasher]）：成功的是机身码样本，失败的是
     * 「这个码 U净 认不认」的样本，两样都是收紧本地判据的依据。
     */
    suspend fun scanWasher(qrCode: String): UjingScanResult = withContext(Dispatchers.IO) {
        val content = qrCode.trim()
        val envelope = try {
            api.scanWasher(requireSession().token, content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dumpScanWasher(content, envelope = null, error = e.message)
            throw e
        }
        dumpScanWasher(content, envelope, error = null)
        envelope.requireSuccess()
        envelope.decodeData<UjingScanData>()?.result
            ?: throw UjingApiException("未识别到设备信息")
    }

    /** 设备套餐（模式 / 价格 / 时长）。 */
    suspend fun programInfo(deviceId: String): UjingProgramData = withContext(Dispatchers.IO) {
        val envelope = api.programInfo(requireSession().token, deviceId)
        envelope.requireSuccess()
        dumpProgramInfo(deviceId, envelope)
        envelope.decodeData<UjingProgramData>()
            ?: throw UjingApiException("套餐信息为空")
    }

    /**
     * debug 专用：把 `program/info` 的原始响应整包落盘（含 `code` / `message` / `data`），
     * 一台设备覆盖一份。响应体不含凭证（token 在请求头），可放心留档；
     * 落盘失败只吞掉，绝不打断扫码主流程。release 不配目录，什么都不写。
     */
    private fun dumpProgramInfo(deviceId: String, envelope: UjingEnvelope) {
        val dir = debugDumpDir ?: return
        runCatching {
            dir.mkdirs()
            val safe = deviceId.ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
            File(dir, "program_info_$safe.json").writeText(QiekjJson.json.encodeToString(envelope))
        }
    }

    /**
     * debug 专用：把 `devices/scanWasherCode` 的**二维码原文与响应**整包落盘，一个码型一份。
     *
     * 补的样本有两处用处：
     * 1. 机身码的真实格式 —— `domain/QuickScan.kt` 现在只能本地认出车身码与几个别家码型，
     *    剩下的一律交给本接口（服务端才认机身码）；拿到样本后本地判据就能反过来做成白名单；
     * 2. 服务端对陌生码的应答 —— 顺手记下失败时的 `code` / `message`。
     *
     * 响应体不含凭证（token 在请求头，不落盘）。文件名用原文的 SHA-256 前 6 字节，
     * **原文只写在文件内容里**，不进文件名（文件名要能安全落在任何文件系统上）。
     * 失败（磁盘满 / 无权限）静默：留档是副业，不该影响扫码这条路。release 恒不落盘。
     */
    private fun dumpScanWasher(qrCode: String, envelope: UjingEnvelope?, error: String?) {
        val dir = debugDumpDir ?: return
        runCatching {
            dir.mkdirs()
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(qrCode.toByteArray())
                .take(6)
                .joinToString("") { byte -> "%02x".format(byte) }
            val payload = buildJsonObject {
                put("qrCode", qrCode)
                put("code", envelope?.code)
                put("message", envelope?.message)
                envelope?.data?.let { data -> put("data", data) }
                put("error", error)
            }
            File(dir, "scan_washer_$hash.json").writeText(payload.toString())
        }
    }

    /** 附近洗衣房（一次拉全，配置收藏用）。 */
    suspend fun nearbyStores(lat: Double, lng: Double): List<UjingStore> =
        withContext(Dispatchers.IO) {
            val envelope = api.storesNear(requireSession().token, lat, lng)
            envelope.requireSuccess()
            envelope.decodeData<UjingStoreListData>()?.storeList.orEmpty().filter { it.isComplete }
        }

    /** 一家洗衣房的设备统计（看板一行一次调用，并发聚合由调用方编排）。 */
    suspend fun devicesReserve(storeId: String): List<UjingDeviceGroup> =
        withContext(Dispatchers.IO) {
            val envelope = api.devicesReserve(requireSession().token, storeId)
            envelope.requireSuccess()
            envelope.decodeData<UjingReserveData>()?.devices.orEmpty().mapNotNull { it.device }
        }

    /** 设备统计 → 看板行（domain 纯逻辑）；没有洗衣机类型时返回 null。 */
    fun boardLine(groups: List<UjingDeviceGroup>): UjingState.BoardLine? =
        UjingState.boardLine(
            groups.map {
                UjingState.BoardGroup(
                    typeName = it.deviceTypeName,
                    free = it.free,
                    total = it.total,
                    waitMinutes = it.waitTime,
                )
            },
        )

    // ── 订单链（P2） ──

    /** 在案订单（跨进程恢复的快照）。 */
    fun currentOrder(): UjingOrderSnapshot? = orderStore.current()

    /** 在案订单流（订单卡订阅；终结后由 UI 的「知道了」清掉）。 */
    val orderState: StateFlow<UjingOrderSnapshot?> = orderStore.state

    /**
     * 下单。返回订单快照并存档（进程重启可恢复）。**机器必须用户亲手扫**——
     * `deviceId` 来自扫码结果，不做"不传 deviceId 由服务端自动匹配"的路径。
     *
     * 可选参数的发送条件在 [UjingApi.createOrder]：[washTemperatureId] 只在
     * 机型开放水温（`isWashTemperatureEnable`）且非烘干时由调用方传入；
     * 投放加购按用户勾选带标准档（[addDetergent] / [addDisinfectant]，默认值
     * 由机型强制投放声明给出）；烘干 `dryTime` 仍由本类按模式时长自动补。
     */
    suspend fun createOrder(
        scan: UjingScanResult,
        program: UjingProgramData,
        model: UjingWashModel,
        washTemperatureId: Int? = null,
        addDetergent: Boolean = false,
        addDisinfectant: Boolean = false,
    ): UjingOrderSnapshot = withContext(Dispatchers.IO) {
        val storeId = program.storeId?.takeIf { it.isNotBlank() }
            ?: throw UjingApiException("设备未绑定门店，无法下单")
        val envelope = api.createOrder(
            token = requireSession().token,
            deviceId = scan.deviceId.orEmpty(),
            deviceTypeId = scan.deviceTypeId,
            storeId = storeId,
            washModelId = model.workModelId,
            type = program.type,
            washTemperatureId = washTemperatureId,
            // 投放加购：用户勾选才带标准档（枚举单一来源在 UjingState，UI 不选档位）
            detergentGearId = if (addDetergent) UjingState.DETERGENT_STANDARD_GEAR else null,
            disinfectantGearId = if (addDisinfectant) UjingState.DISINFECTANT_STANDARD_GEAR else null,
            // 烘干机协议事实：dryTime = 模式时长 / 10
            dryTime = if (program.type == 2) model.time / 10 else null,
        )
        envelope.requireSuccess()
        val data = envelope.decodeData<UjingOrderCreateData>()
        val orderId = data?.orderId?.takeIf { it.isNotBlank() && it != "0" }
            ?: throw UjingApiException("下单响应缺少订单号")
        val now = System.currentTimeMillis()
        val snapshot = UjingOrderSnapshot(
            orderId = orderId,
            orderNo = data?.orderNo.orEmpty(),
            deviceId = scan.deviceId.orEmpty(),
            deviceName = listOfNotNull(program.storeName, program.deviceNo)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { program.deviceTypeName },
            modelId = model.workModelId,
            modelName = model.workModelName,
            priceFen = model.basePrice,
            status = "0",
            snapshotAt = now,
            createdAt = now,
            durationSeconds = model.time * 60,
        )
        orderStore.save(snapshot)
        // 账单留痕：下单即入账（状态随刷新更新，取消标注见 cancelOrder）
        historyStore.upsert(UjingHistoryEntry.fromSnapshot(snapshot))
        snapshot
    }

    /**
     * 支付参数：`payInfo.orderInfo` 直接给支付宝 SDK（服务端签发，App 不经手资金）。
     * 金额查询串（couponId 等）按官方默认形态传死——不代用户用券、不碰红包。
     */
    suspend fun paymentOrderInfo(orderId: String): String = withContext(Dispatchers.IO) {
        val envelope = api.paymentArguments(requireSession().token, orderId)
        envelope.requireSuccess()
        envelope.decodeData<UjingPayArgsData>()?.payInfo?.orderInfo?.takeIf { it.isNotBlank() }
            ?: throw UjingApiException("支付参数为空，请稍后重试")
    }

    /**
     * 订单详情 → 刷新在案快照（状态 / 剩余时间 / 支付标记）。
     * 订单到达终结态时保留快照（卡片要展示结果），由 UI 的「知道了」清掉。
     */
    suspend fun refreshOrder(orderId: String): UjingOrderSnapshot = withContext(Dispatchers.IO) {
        val envelope = api.orderDetail(requireSession().token, orderId)
        envelope.requireSuccess()
        val data = envelope.decodeData<UjingOrderDetailData>()
            ?: throw UjingApiException("订单详情为空")
        val current = orderStore.current()
        val snapshot = UjingOrderSnapshot(
            orderId = orderId,
            orderNo = data.orderNo.orEmpty().ifBlank { current?.orderNo.orEmpty() },
            deviceId = data.deviceId.orEmpty().ifBlank { current?.deviceId.orEmpty() },
            deviceName = current?.deviceName.orEmpty(),
            modelId = current?.modelId ?: 0,
            modelName = current?.modelName.orEmpty(),
            priceFen = current?.priceFen
                // round 而非截断：4.35 元是 435 分，截断会显示成 434
                ?: data.payPrice?.toDoubleOrNull()?.let { Math.round(it * 100).toInt() }
                ?: 0,
            status = data.status.orEmpty(),
            remainSeconds = data.remainTime,
            snapshotAt = System.currentTimeMillis(),
            paid = data.payFlag == 1,
            // 下单时刻与模式总时长只在下单时产生，刷新原样保留（旧快照没有就是 0）
            createdAt = current?.createdAt ?: 0L,
            durationSeconds = current?.durationSeconds ?: 0,
        )
        orderStore.save(snapshot)
        // 同一笔订单状态变化更新原条目（同 orderId 去重），历史页看到的就是最新状态
        historyStore.upsert(UjingHistoryEntry.fromSnapshot(snapshot))
        snapshot
    }

    /** 支付回跳后的确认：lastPayStatus + detail 双查（单查有滞后，社区口径）。 */
    suspend fun confirmPayment(orderId: String): UjingOrderSnapshot = withContext(Dispatchers.IO) {
        runCatching { api.lastPayStatus(requireSession().token, orderId).requireSuccess() }
        refreshOrder(orderId)
    }

    /** 取消订单（未支付 / 支付中才可调）并清在案快照。取消也算一笔账，留痕后清场。 */
    suspend fun cancelOrder(orderId: String) = withContext(Dispatchers.IO) {
        val envelope = api.cancelOrder(requireSession().token, orderId)
        envelope.requireSuccess()
        orderStore.current()?.let {
            historyStore.upsert(UjingHistoryEntry.fromSnapshot(it, statusOverride = "60"))
        }
        orderStore.clear()
    }

    /** 用户在终结态卡片上点「知道了」：清掉快照。 */
    fun dismissOrder() {
        orderStore.clear()
    }

    /**
     * 云端启动（`control/start`）。受理判定：`code == 0` 或 `1703 + errorCode == 0`。
     * **启动前必须先查订单状态**（服务端对已运行订单返回静默拒绝 `{}`），由 ViewModel 把关。
     */
    suspend fun startOrder(orderId: String) = withContext(Dispatchers.IO) {
        val envelope = api.control(requireSession().token, orderId, "start")
        envelope.requireSuccess()
        val data = envelope.decodeData<UjingControlData>()
        if (!UjingState.commandAccepted(envelope.code, data?.errorCode)) {
            throw UjingApiException(
                data?.errorMessage?.takeIf { it.isNotBlank() }
                    ?: "启动指令未被受理，请刷新订单状态后重试",
                envelope.code,
            )
        }
    }

    private fun requireSession(): UjingSession =
        sessionStore.read()?.takeIf { it.isComplete }
            ?: throw UjingSessionExpiredException()
}
