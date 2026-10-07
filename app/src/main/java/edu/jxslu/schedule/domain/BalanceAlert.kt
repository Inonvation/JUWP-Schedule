package edu.jxslu.schedule.domain

/**
 * 余额提醒的两个来源（DESIGN §3.10 / §3.13）：各自独立开关、阈值与检查间隔。
 * 存储层的「上次成功检查时刻」「偏低已提醒标记」按它分键，设置页与后台核对用它指定要评估哪一个。
 */
enum class BalanceAlertSource {
    /** 寝室电费（缴费平台剩余电量折算成元）。 */
    Power,

    /** 一卡通正式卡余额。 */
    Ykt,
}

/**
 * 余额提醒的固定口径（DESIGN §3.10 / §3.13）。
 *
 * 两个来源各自独立：**寝室电费**（缴费平台剩余电量折算成元）与**一卡通余额**
 * （正式卡余额）。它们共用同一份凭证（`YktCredentialStore` 的学号 + 查询密码），
 * 因此设置项同页、开关同源，但阈值候选表、检查间隔与判定互不相干。
 *
 * 单一来源：DataStore 键默认值、设置页轮选器、后台核对 Worker 的判定
 * 全部从这里取，不各自写字面量。模式同 [ScoreAlertDefaults]。
 */
object BalanceAlert {

    // ------------------------------------------------------------------
    // 档位口径（用户拍板：电费 10–80 元、一卡通 10–50 元，步长均 5 元）
    // ------------------------------------------------------------------

    /** 电费提醒阈值候选（元）：10, 15, …, 80。 */
    val POWER_CHOICES: List<Int> = (10..80 step 5).toList()

    /** 一卡通余额提醒阈值候选（元）：10, 15, …, 50。 */
    val YKT_CHOICES: List<Int> = (10..50 step 5).toList()

    /** 电费提醒默认阈值（元）。 */
    const val DEFAULT_POWER_YUAN = 20

    /** 一卡通余额提醒默认阈值（元）。 */
    const val DEFAULT_YKT_YUAN = 20

    /** 存储值夹取到合法值域（手改数据 / 旧数据防线）。 */
    fun coercePowerYuan(value: Int): Int = value.coerceIn(POWER_CHOICES.first(), POWER_CHOICES.last())

    fun coerceYktYuan(value: Int): Int = value.coerceIn(YKT_CHOICES.first(), YKT_CHOICES.last())

    /** 存储值 → 选项下标；不在选项表内的值向下取档（与 `CalendarSyncDefaults` 同口径）。 */
    fun powerChoiceIndex(yuan: Int): Int = choiceIndexOf(POWER_CHOICES, yuan)

    fun yktChoiceIndex(yuan: Int): Int = choiceIndexOf(YKT_CHOICES, yuan)

    /** 存储值 → 设置页右侧展示文案。 */
    fun powerLabel(yuan: Int): String = "低于 ¥${coercePowerYuan(yuan)} 时提醒"

    fun yktLabel(yuan: Int): String = "低于 ¥${coerceYktYuan(yuan)} 时提醒"

    private fun choiceIndexOf(choices: List<Int>, yuan: Int): Int {
        val coerced = yuan.coerceIn(choices.first(), choices.last())
        // 取不超过该值的最大档（非 5 倍数向下取档）
        return choices.indexOfLast { it <= coerced }.coerceAtLeast(0)
    }

    // ------------------------------------------------------------------
    // 检查间隔口径（用户拍板 2026-10-07：默认 6 小时，档位 3–48 小时）
    // ------------------------------------------------------------------

    /** 默认检查间隔（小时）。 */
    const val INTERVAL_DEFAULT_HOURS = 6

    /** 检查间隔档位（小时）。两个来源共用同一张档位表，但**各自独立设置**。 */
    val INTERVAL_CHOICES_HOURS: List<Int> = listOf(3, 6, 8, 10, 12, 24, 48)

    /** 存储值兜底：不在档位表里归到默认档（旧数据/手改数据防线）。 */
    fun coerceIntervalHours(value: Int): Int =
        if (value in INTERVAL_CHOICES_HOURS) value else INTERVAL_DEFAULT_HOURS

    /** 存储值 → 选项下标；不在档位表内的值归到默认档。 */
    fun intervalChoiceIndex(value: Int): Int =
        INTERVAL_CHOICES_HOURS.indexOf(coerceIntervalHours(value)).coerceAtLeast(0)

    /** 存储值 → 设置页展示文案。 */
    fun intervalLabel(value: Int): String = "每 ${coerceIntervalHours(value)} 小时"

    // ------------------------------------------------------------------
    // 判定（纯函数，可 JVM 测）
    // ------------------------------------------------------------------

    /**
     * 电费剩余**金额**（元）= 剩余电量（度）× 单价（元/度）。
     *
     * 这是全 App 唯一的换算口径（生活页电费卡与提醒 Worker 共用）。
     * 电量或单价任一缺失返回 null —— **不猜**：宁可这次不提醒，也不拿
     * 「按 0 元」或「按默认单价」编出来的数字去报警。
     */
    fun remainingYuan(remain: Double?, priceYuan: Double?): Double? {
        if (remain == null || priceYuan == null) return null
        return remain * priceYuan
    }

    /**
     * 是否低于阈值。口径是**严格小于**：「低于 ¥20 时提醒」在余额正好 20.00 元
     * 时不提醒（边界只在一处定义，免得两边各理解一半）。
     */
    fun isLow(yuan: Double, thresholdYuan: Int): Boolean = yuan < thresholdYuan
}
