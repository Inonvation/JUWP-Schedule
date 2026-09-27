package edu.jxslu.schedule.ui.widget

import edu.jxslu.schedule.domain.BalanceAlert
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 生活小组件的纯逻辑（DESIGN §3.6 二条目改版，2026-09-27）。
 *
 * 校园卡余额与寝室电费合并在一条卡片里（余额大字 + 电费副行），快照 [LifeCardSnapshot]
 * 同时带两份数据。只放可 JVM 单测的东西：快照模型、JSON 编解码、取数闸门、文案格式化。
 * 同步与刷新的编排在 [LifeWidgetSync]；渲染在 `CampusCardWidget`。
 */

/**
 * 校园卡数据（合并卡的**主区**，渲染成余额大字）。
 *
 * - [cardFen] = 正式卡余额（分），口径与余额提醒一致（`BalanceAlertReminder.checkYkt`：
 *   食堂 / 门禁实际扣款的那个钱包，不含电子账户）。**-1 = 从未取到**（显示「余额待更新」）；
 * - [fetchedAtMs] = 最近一次**成功**取数时刻。取数失败不写它（与余额提醒「失败不落日期」
 *   同口径），它同时是 2 小时闸门的时间基点；
 * - [hasCredentials] = 计算快照那一刻凭证是否存在（决定渲染态与点击落点）；
 * - [hideBalance] = 「在小组件中隐藏余额」开关（设置页，默认关）。
 */
@Serializable
data class CampusCardSnapshot(
    val cardFen: Long = -1L,
    val fetchedAtMs: Long = 0L,
    val hasCredentials: Boolean = false,
    val hideBalance: Boolean = false,
)

/**
 * 电费数据（合并卡的**副行**，渲染成一行小字，独立可点区域）。
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

/**
 * 合并的生活卡片快照（DESIGN §3.6 二条目改版，2026-09-27）：[campus] 是主区（余额大字），
 * [power] 是副行（电费小字 + 独立点击落点）。
 *
 * 合成一个快照而不是两条目各存一份：一次后台刷新（写状态 + `update()`）就能把两份数据
 * 一起推上桌面。点击分区只是渲染层的事，不影响数据来源与取数口径（校园卡 2 小时闸门、
 * 电费零网络，各自不变）。
 */
@Serializable
data class LifeCardSnapshot(
    val campus: CampusCardSnapshot = CampusCardSnapshot(),
    val power: PowerWidgetSnapshot = PowerWidgetSnapshot(),
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

    /**
     * 电费副行文案（合并卡主区下面那一行，DESIGN §3.6）。
     *
     * 窄档（2 格宽，真机实测 150dp）放不下「寝室」「折合」和单价括号，压成
     * `电费 23.7 度 · ¥14.69`；宽档（4×2）给全口径。房号不进文案——卡片是自己看的，
     * 房号在缴费页才有用。
     *
     * 折合只走 [BalanceAlert.remainingYuan]（全 App 唯一换算处）：单价缺失就只报度数，
     * 不按默认单价编（DESIGN §3.13）。
     */
    fun powerLineText(power: PowerWidgetSnapshot, narrow: Boolean): String {
        val prefix = if (narrow) "电费" else "寝室电费"
        val remain = power.remainKwh ?: return "$prefix · 暂无读数"
        val kwh = "$prefix ${kwh(remain)} 度"
        val unitPrice = power.priceYuan
        val yuan = BalanceAlert.remainingYuan(remain, unitPrice)
        return when {
            yuan == null || unitPrice == null -> kwh
            narrow -> "$kwh · ¥${yuanAmount(yuan)}"
            else -> "$kwh · 折合 ¥${yuanAmount(yuan)}（${price(unitPrice)} 元/度）"
        }
    }
}

/** 合并卡快照 JSON 编解码；解码失败返回 null（组合退回首帧快照，不崩）。 */
internal object LifeCardSnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(snapshot: LifeCardSnapshot): String = json.encodeToString(LifeCardSnapshot.serializer(), snapshot)

    fun decode(raw: String?): LifeCardSnapshot? =
        raw?.let { runCatching { json.decodeFromString(LifeCardSnapshot.serializer(), it) }.getOrNull() }
}
