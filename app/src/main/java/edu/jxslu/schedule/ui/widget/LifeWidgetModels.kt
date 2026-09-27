package edu.jxslu.schedule.ui.widget

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 校园卡 / 电费小组件的纯逻辑（DESIGN §3.6 三条目改版，2026-09-27）。
 *
 * 只放可 JVM 单测的东西：快照模型、JSON 编解码、取数闸门、数字/时刻格式化。
 * 同步与刷新的编排在 [LifeWidgetSync]；渲染在 `CampusCardWidget` / `PowerWidget`。
 */

/**
 * 校园卡小组件快照。
 *
 * - [cardFen] = 正式卡余额（分），口径与余额提醒一致（`BalanceAlertReminder.checkYkt`：
 *   食堂 / 门禁实际扣款的那个钱包，不含电子账户）。**-1 = 从未取到**（显示「余额待更新」）；
 * - [accountFen] = 电子账户余额（分），独立接口（`rechargeAccountDetail`），余额提醒的
 *   取数路径拿不到 → **null = 本次没取 / 历史未知**（渲染时保留旧值或整行省略，**不显示 ¥0.00**）；
 * - [fetchedAtMs] = 最近一次**成功**取数时刻。取数失败不写它（与余额提醒「失败不落日期」
 *   同口径），它同时是 2 小时闸门的时间基点；
 * - [hasCredentials] = 计算快照那一刻凭证是否存在（决定渲染态与点击落点）；
 * - [hideBalance] = 「在小组件中隐藏余额」开关（设置页，默认关）。
 */
@Serializable
data class CampusCardSnapshot(
    val cardFen: Long = -1L,
    val accountFen: Long? = null,
    val fetchedAtMs: Long = 0L,
    val hasCredentials: Boolean = false,
    val hideBalance: Boolean = false,
)

/**
 * 电费小组件快照（Room `power_readings` 最新一条的镜像，见 [LifeWidgetSync]）。
 *
 * [remainKwh] 为 null = 没有读数（没开凭证 / 从未刷新）；[priceYuan] 为 null =
 * 读数上单价未知（落库时 0），此时只显示度数、不算折合（**不猜**，与
 * `BalanceAlert.remainingYuan` 的「任一缺失返回 null」同口径）。
 */
@Serializable
data class PowerWidgetSnapshot(
    val roomId: String = "",
    val remainKwh: Double? = null,
    val priceYuan: Double? = null,
    val fetchedAtMs: Long = 0L,
)

/** 校园卡余额的后台取数闸门：距上次成功取数 ≥ [INTERVAL_MS] 才再取一次。 */
object CampusBalanceGate {

    /** 2 小时：与 App 内既有取数密度同量级（吃饭前后各一次），不加重校内系统压力。 */
    const val INTERVAL_MS: Long = 2L * 60 * 60 * 1000

    /**
     * [lastSuccessMs] = 上次成功取数时刻（从未成功为 null）。失败不落时刻，
     * 所以失败后下一次 tick 仍会尝试——但**没有重试风暴**：15 分钟 tick 一次，
     * 最多每小时 4 次轻量请求，且凭证不存在时根本不会走到取数。
     */
    fun shouldFetch(lastSuccessMs: Long?, nowMs: Long): Boolean = when {
        lastSuccessMs == null -> true
        // 时钟回拨 / 换机恢复备份：宁多取一次，不出现「卡在两小时外」
        nowMs < lastSuccessMs -> true
        else -> nowMs - lastSuccessMs >= INTERVAL_MS
    }
}

/** 数字与时刻格式化（小组件渲染与设置页共用；Locale.US 与 `PowerUsage.kwhText` 同口径）。 */
object LifeWidgetFormat {

    /** 分 → 「128.45」。负数不该出现（上游口径保证非负），夹成 0 兜底。 */
    fun yuan(fen: Long): String = String.format(Locale.US, "%.2f", fen.coerceAtLeast(0) / 100.0)

    /** 元（`BalanceAlert.remainingYuan` 的返回值）→ 「14.69」。 */
    fun yuanAmount(yuan: Double): String = String.format(Locale.US, "%.2f", yuan)

    /** 度数大字：一位小数（23.7）。 */
    fun kwh(remainKwh: Double): String = String.format(Locale.US, "%.1f", remainKwh)

    /** 单价（0.62 元/度）。 */
    fun price(priceYuan: Double): String = String.format(Locale.US, "%.2f", priceYuan)

    /** 「更新 12:30」的时刻部分；[fetchedAtMs] 无效（≤0）返回 null，调用方整段省略。 */
    fun timeLabel(fetchedAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (fetchedAtMs <= 0L) return null
        return DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(fetchedAtMs))
    }
}

/** 校园卡快照 JSON 编解码；解码失败返回 null（组合退回首帧快照，不崩）。 */
internal object CampusCardSnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(snapshot: CampusCardSnapshot): String = json.encodeToString(CampusCardSnapshot.serializer(), snapshot)

    fun decode(raw: String?): CampusCardSnapshot? =
        raw?.let { runCatching { json.decodeFromString(CampusCardSnapshot.serializer(), it) }.getOrNull() }
}

/** 电费快照 JSON 编解码；口径同 [CampusCardSnapshotCodec]。 */
internal object PowerWidgetSnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(snapshot: PowerWidgetSnapshot): String = json.encodeToString(PowerWidgetSnapshot.serializer(), snapshot)

    fun decode(raw: String?): PowerWidgetSnapshot? =
        raw?.let { runCatching { json.decodeFromString(PowerWidgetSnapshot.serializer(), it) }.getOrNull() }
}
