package edu.jxslu.schedule.domain

/**
 * U净（美的校园洗衣）纯逻辑（DESIGN §4.37）：扫码徽标、看板聚合、金额格式化。
 *
 * 无 Android 依赖，直接 JVM 测（`UjingStateTest`）。协议事实来自社区多份独立
 * 逆向成果交叉核对；判定规则与文案集中在这里，UI 不另写 if。
 */
object UjingState {

    // ── 金额 ──

    /** 分 → 元，恒两位小数（`278 → "2.78"`、`50 → "0.50"`）。 */
    fun fen2yuan(fen: Int): String {
        val sign = if (fen < 0) "-" else ""
        val abs = kotlin.math.abs(fen)
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }

    // ── 扫码徽标 ──

    /** 扫码结果对应的设备状态；文案即展示文本。 */
    enum class ScanBadge(val label: String) {
        Free("空闲"),
        InUse("使用中"),
        Fault("故障"),
        Offline("离线"),
    }

    /**
     * 扫码结果 → 徽标。
     *
     * 判据来自 `scanWasherCode` 的 `createOrderEnabled` 与 `status`（服务端枚举：
     * 1 = 运行中/被占用、2 = 故障、8 = 离线）；`reason` 是服务端文案，兜底按关键词判。
     * **顺序即优先级**：能下单就是空闲，其余按离线/故障/占用细分。
     */
    fun scanBadge(createOrderEnabled: Boolean, status: String?, reason: String?): ScanBadge = when {
        createOrderEnabled -> ScanBadge.Free
        status == "8" || reason?.contains("离线") == true -> ScanBadge.Offline
        status == "2" || reason?.contains("故障") == true -> ScanBadge.Fault
        else -> ScanBadge.InUse
    }

    // ── 通信模块 ──

    /**
     * 通信模块类型 → 短文案（取值查自社区协议报告）。**P3 启动通道的判据**：
     * 1 / 5 = 纯蓝牙机型（启动必须人在机器旁，走 BLE 透传）；其余走云端 `control/start`。
     * 服务端未返回（-1）或未知值不猜。
     */
    fun moduleTypeLabel(moduleType: Int): String = when (moduleType) {
        0 -> "2G"
        1 -> "蓝牙 Nordic"
        2 -> "NB"
        3 -> "2G + 蓝牙"
        4 -> "NB + 蓝牙"
        5 -> "蓝牙 Cypress"
        6 -> "WiFi Mesh"
        7 -> "4G"
        else -> "未知"
    }

    // ── 订单 ──

    /**
     * 订单状态 → 中文（依据社区逆向的 orderDetail 文案表；`status` 是字符串）。
     * 服务端 `statusRemark` 非空时优先展示服务端文案，本地表作兜底。
     */
    fun statusText(status: String): String = when (status) {
        "0" -> "已创建"
        "10" -> "待支付"
        "17" -> "支付处理中"
        "20" -> "已支付 · 待启动"
        "21" -> "启动中"
        "22" -> "自洁启动中"
        "24" -> "正在投放洗衣液"
        "29" -> "订单保护中"
        "30" -> "自洁中"
        "35" -> "自洁完成"
        "40" -> "洗涤中"
        "50" -> "已完成"
        "51" -> "支付超时"
        "52" -> "启动失败"
        "53", "60" -> "已取消"
        else -> "状态 $status"
    }

    /** 「启动」按钮可点窗口：待启动 20 / 自洁启动中 22 / 自洁完成 35（社区实测口径）。 */
    fun canStart(status: String): Boolean = status in setOf("20", "22", "35")

    /** 「取消订单」可用窗口：未支付 / 支付中（20 起已锁定机器，不可取消）。 */
    fun canCancel(status: String): Boolean = status in setOf("0", "10", "17")

    /** 订单是否已终结（50 完成 / 51 支付超时 / 52 启动失败 / 53、60 取消）。 */
    fun isTerminal(status: String): Boolean = status in setOf("50", "51", "52", "53", "60")

    /** 云端控制指令受理判定：`code == 0`，或受理码 1703 且内层 `errorCode == 0`。 */
    fun commandAccepted(code: Int, innerErrorCode: Int?): Boolean =
        code == 0 || (code == 1703 && innerErrorCode == 0)

    /** 剩余秒数 → 「X 分 Y 秒」（负数夹到 0）。 */
    fun remainText(remainSeconds: Int): String {
        val total = remainSeconds.coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return if (m > 0) "$m 分 $s 秒" else "$s 秒"
    }

    // ── 下单参数 ──

    /**
     * 水温档 id → 文案（协议固定枚举：1=常温 2=30℃ 3=40℃ 4=60℃）。
     * 未知 id 不猜，返回「水温 $id」让用户能看到原值。
     */
    fun temperatureLabel(id: Int): String = when (id) {
        1 -> "常温"
        2 -> "30℃"
        3 -> "40℃"
        4 -> "60℃"
        else -> "水温 $id"
    }

    /** 水温档全集（UI 的选择 chips 与默认值同源）。 */
    val temperatureOptions: List<Pair<Int, String>> =
        listOf(1, 2, 3, 4).map { it to temperatureLabel(it) }

    /** 未支付订单的机器独占期（秒）：下单后 2 分钟内要完成支付，超时自动取消。 */
    const val PAY_WINDOW_SECONDS = 120L

    /**
     * 支付窗口剩余秒数（[nowMillis] − [createdAtMillis] 对 2 分钟窗口取差）；
     * `createdAt` 未知（旧快照 0）返回 -1 表示「无法计算」。剩余到 0 以下夹 0——
     * 窗口已过但服务端还没关单，UI 提示「刷新确认」而不是假装还有时间。
     */
    fun payWindowRemainSeconds(createdAtMillis: Long, nowMillis: Long): Int {
        if (createdAtMillis <= 0L) return -1
        val elapsed = (nowMillis - createdAtMillis) / 1000L
        return (PAY_WINDOW_SECONDS - elapsed).toInt().coerceAtLeast(0)
    }

    /**
     * 烘干机下单 body 的 `dryTime`：协议事实 = 模式时长 / 10（仅烘干机业务发送）。
     */
    fun dryTimeFor(modelTimeMinutes: Int): Int = modelTimeMinutes / 10

    // ── 空闲看板 ──

    /** 某洗衣房按设备类型的聚合数据（来自 `devices/reserve` 的 `devices[].device`）。 */
    data class BoardGroup(
        val typeName: String,
        val free: Int,
        val total: Int,
        val waitMinutes: Int,
    )

    /**
     * 一家洗衣房的一行看板。文案语义集中在 [primaryText] / [secondaryText]，
     * UI 直接取用、不再拼接。
     */
    data class BoardLine(val free: Int, val total: Int, val waitMinutes: Int) {
        val primaryText: String get() = if (free > 0) "空闲 $free/$total" else "暂无空闲"
        val secondaryText: String?
            get() = if (free <= 0 && waitMinutes > 0) "约等 $waitMinutes 分钟" else null
    }

    /**
     * 聚合一家洗衣房：**只数洗衣机**（排除名含「烘干 / 干衣」的类型，与社区实现同一
     * 排除法口径），全满时取最短等待。没有任何洗衣机类型时返回 null——该行显示什么
     * 由调用方定，这里不编数据。
     */
    fun boardLine(groups: List<BoardGroup>): BoardLine? {
        val washers = groups.filterNot { it.typeName.contains("烘干") || it.typeName.contains("干衣") }
        if (washers.isEmpty()) return null
        val free = washers.sumOf { it.free }
        val total = washers.sumOf { it.total }
        val wait = washers.filter { it.free <= 0 }
            .map { it.waitMinutes }
            .filter { it > 0 }
            .minOrNull() ?: 0
        return BoardLine(free = free, total = total, waitMinutes = wait)
    }
}
