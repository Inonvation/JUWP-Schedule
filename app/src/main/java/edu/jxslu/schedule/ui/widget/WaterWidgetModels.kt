package edu.jxslu.schedule.ui.widget

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 开水两卡小组件（胖乖开水 + 趣智开水，DESIGN §3.6「开水两卡」）的纯逻辑。
 *
 * 两张卡都是**固定 2×2**（provider XML `resizeMode="none"`，不给拖动），渲染只读
 * 各自的快照（Glance 状态），组合里零网络。可 JVM 单测的东西都在本文件：快照模型、
 * 数值解析、副行文案、JSON 编解码；编排（何时推送 / 何时取数闸门）在 [WaterWidgetSync]，
 * 渲染在 `QiekjWaterWidget` / `QzxyWaterWidget`。
 *
 * 取数闸门与校园卡合并卡共用 [CampusBalanceGate]（名字带 Campus 是历史沿革，
 * 语义 = 小组件余额类取数的「距上次成功 ≥2 小时」闸门，胖乖 / 趣智同口径）。
 */

/**
 * 胖乖开水卡的快照。
 *
 * - [loggedIn] = 构建快照那一刻的登录态（`QiekjRepository.loggedIn`，不落 DataStore，
 *   每次构建快照时现算）；
 * - [ticketYuan] = 小票余额（元）。**null = 从未取到**（渲染「余额待更新」）。
 *   胖乖账户里能付水钱的就是小票（`BalanceData.tokenCoin`，分），开水页同一口径；
 * - [points] = 积分原文（服务端给的字符串，不转数值）；
 * - [fetchedAtMs] = 最近一次**成功**取数时刻。失败不写它（与校园卡「失败不落时刻」
 *   同口径），它同时是 2 小时闸门的时间基点。
 */
@Serializable
data class QiekjWaterSnapshot(
    val loggedIn: Boolean = false,
    val ticketYuan: Double? = null,
    val points: String? = null,
    val fetchedAtMs: Long = 0L,
)

/**
 * 趣智开水卡的快照。
 *
 * - [balanceText] = 余额原文（服务端 `money` 字段，本身就是元，开水页原样展示）。
 *   **null = 从未取到**；
 * - [wateringStartedAtMs] = 非空 = 「用水中」（本地记账未过期，`QzxyWateringStore`）。
 *   过期（超 1 小时）与已结算都在快照构建时归为 null，渲染不区分这两种情况；
 * - [lastDeviceName] = 上次使用的设备名（本地 `QzxyDeviceStore.lastUsed`，零网络），
 *   空闲态副行用它交代「水从哪台设备出」。
 */
@Serializable
data class QzxyWaterSnapshot(
    val loggedIn: Boolean = false,
    val balanceText: String? = null,
    val fetchedAtMs: Long = 0L,
    val wateringStartedAtMs: Long? = null,
    val wateringDeviceName: String? = null,
    val lastDeviceName: String? = null,
)

/** 开水两卡的文案格式化（渲染层只调这里，不在组合里拼字符串）。 */
object WaterWidgetFormat {

    /**
     * 胖乖小票余额：分（服务端字符串）→ 元。解析失败 / 空 = null（显示「余额待更新」，
     * **不猜**，与 `BalanceData.ticketText` 的「-」口径一致但不再上卡）。
     */
    fun qiekjTicketYuan(tokenCoin: String?): Double? =
        tokenCoin?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.div(100.0)

    /**
     * 胖乖卡副行：主区大字是小票余额，这行交代币种 + 积分。
     * 没有积分数据就只留「小票」两个字。
     */
    fun qiekjSubLine(points: String?): String = when {
        points.isNullOrBlank() -> "小票"
        else -> "小票 · 积分 ${points.trim()}"
    }

    /**
     * 趣智余额原文 → 可展示值。服务端全空时 `QzxyBalance.text` 会退成 "-"，
     * 那等于没取到，归 null（「余额待更新」），别把 "¥-" 渲染上桌面。
     */
    fun qzxyBalanceText(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it != "-" }

    /**
     * 趣智用水中副行：`设备名 · 已 N 分钟`（没设备名就只留时长）。
     *
     * 分钟取整、不足 1 分钟算「刚开阀」。时长按**渲染时刻**算，两次刷新之间不跳
     * （与课表卡「还有 N 分钟」同一口径）。
     */
    fun qzxyWateringLine(deviceName: String?, startedAtMs: Long, nowMs: Long): String {
        val minutes = ((nowMs - startedAtMs).coerceAtLeast(0L) / 60_000L).toInt()
        val elapsed = if (minutes < 1) "刚开阀" else "已 $minutes 分钟"
        val name = deviceName?.trim().orEmpty()
        return if (name.isEmpty()) elapsed else "$name · $elapsed"
    }
}

/** 胖乖卡快照 JSON 编解码；解码失败返回 null（组合退回首帧快照，不崩）。 */
internal object QiekjWaterSnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(snapshot: QiekjWaterSnapshot): String =
        json.encodeToString(QiekjWaterSnapshot.serializer(), snapshot)

    fun decode(raw: String?): QiekjWaterSnapshot? =
        raw?.let { runCatching { json.decodeFromString(QiekjWaterSnapshot.serializer(), it) }.getOrNull() }
}

/** 趣智卡快照 JSON 编解码；口径同 [QiekjWaterSnapshotCodec]。 */
internal object QzxyWaterSnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(snapshot: QzxyWaterSnapshot): String =
        json.encodeToString(QzxyWaterSnapshot.serializer(), snapshot)

    fun decode(raw: String?): QzxyWaterSnapshot? =
        raw?.let { runCatching { json.decodeFromString(QzxyWaterSnapshot.serializer(), it) }.getOrNull() }
}
