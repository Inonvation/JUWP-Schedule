package edu.jxslu.schedule.domain

/**
 * 选课中心里的一门候选课程（DESIGN §4.36，2026-10-01 规划）。
 *
 * **字段以窗口期实测为准**：教务课程列表页面的真实列名与主键还没样本（非选课期进不去），
 * 这里先定「最小集 + 可空扩展」——[id] 只当不透明字符串（提交选课用的主键），
 * 容量类字段一律可空（教务没给就不显示，不猜）。
 */
data class SelectionCourse(
    /** 提交选课用的主键（教务原始 id，不解析、只透传）。 */
    val id: String,
    val name: String,
    val teacher: String = "",
    /** 上课时间原文（多行以 `\n` 分隔）。 */
    val timeText: String = "",
    /** 上课地点原文。 */
    val placeText: String = "",
    val credit: Double? = null,
    /** 课程属性：必修 / 任选… */
    val attribute: String = "",
    /** 课程性质：通识必修课 / 专业任选课… */
    val category: String = "",
    /** 容量（总名额）；null = 教务未给。 */
    val capacity: Int? = null,
    /** 已选人数；null = 教务未给。 */
    val enrolled: Int? = null,
    /** 我是否已选中这门课（教务列表的状态列）。 */
    val selected: Boolean = false,
) {
    /** 余量；任一端为空就是 null（不猜）。 */
    val remaining: Int? get() = if (capacity != null && enrolled != null) capacity - enrolled else null
}

/**
 * 抢课会话的停止原因（DESIGN §4.36）。
 *
 * 前四个是**正常收场**（用户会看到「完成/结束」类文案），[Fatal] 是需要人工处理的
 * （会话失效、验证码、接口未接入）——文案必须给出下一步动作。
 */
sealed interface GrabStop {
    /** 清单全部命中。 */
    data object AllMatched : GrabStop

    /** 轮次已到截止时刻。 */
    data object RoundEnded : GrabStop

    /** 用户手动停止。 */
    data object UserStopped : GrabStop

    /** 连续失败到阈值（网络/教务侧异常），自动收手避免空转打教务。 */
    data object TooManyFailures : GrabStop

    /** 达到单场会话的时长上限。 */
    data object TimeLimit : GrabStop

    /** 需要人工：会话失效 / 验证码 / 接口未接入等。 */
    data class Fatal(val reason: String) : GrabStop
}

/** 一次抢课会话的状态快照（纯数据，供 [SelectionGrabPolicy.shouldStop] 判定）。 */
data class GrabState(
    val startedAt: Long,
    val consecutiveFailures: Int = 0,
    /** 还没抢到的清单条目 id。 */
    val pendingWishIds: Set<String> = emptySet(),
    /** 轮次截止时刻（null = 未知，只用时长上限兜底）。 */
    val roundEndAt: Long? = null,
)

/**
 * 抢课节流与停止条件（DESIGN §4.36，纯 JVM 可测）。
 *
 * 三条节制纪律（红线，见 §4.36）：固定间隔起步、失败线性退避封顶、**连续失败到阈值
 * 自动停止**——不做并发、不做重试风暴、不绕验证。时长上限是兜底（轮次截止时刻未知时
 * 也总有个头）。
 */
object SelectionGrabPolicy {

    /** 单轮检查间隔：默认 10 秒（用户可调档，范围 [INTERVAL_MIN_MS]–[INTERVAL_MAX_MS]）。 */
    const val INTERVAL_DEFAULT_MS = 10_000L
    const val INTERVAL_MIN_MS = 5_000L
    const val INTERVAL_MAX_MS = 60_000L

    /** 设置页可选的间隔档位（秒 → 毫秒），与 [coerceInterval] 的夹取范围一致。 */
    val INTERVAL_CHOICES_MS: List<Long> = listOf(5_000L, 10_000L, 20_000L, 30_000L, 60_000L)

    /** 档位展示名（「10 秒」；不在档位表内也照实显示秒数，不编）。 */
    fun intervalLabel(value: Long): String = "${coerceInterval(value) / 1000} 秒"

    /** 存储值 → 选项下标；脏数据（不在档位表内）回默认档下标。 */
    fun intervalChoiceIndex(value: Long): Int {
        val index = INTERVAL_CHOICES_MS.indexOf(coerceInterval(value))
        return if (index >= 0) index else INTERVAL_CHOICES_MS.indexOf(INTERVAL_DEFAULT_MS)
    }

    /** 连续失败阈值：到这个数就停（网络抖动/教务侧异常都不该无限空转）。 */
    const val MAX_CONSECUTIVE_FAILURES = 5

    /** 单场会话时长上限（2 小时）——即使轮次截止时刻解析不出来也总有个头。 */
    const val MAX_SESSION_MS = 2L * 60 * 60 * 1000

    fun coerceInterval(value: Long): Long = value.coerceIn(INTERVAL_MIN_MS, INTERVAL_MAX_MS)

    /**
     * 下一次检查的等待时长：连续失败 1 次起线性加长（10s → 20s → 30s…），
     * 封顶 [INTERVAL_MAX_MS]。成功一次（调用方把计数清零）就回到基础间隔。
     */
    fun nextDelay(baseMs: Long, consecutiveFailures: Int): Long =
        coerceInterval(baseMs * (1 + consecutiveFailures.coerceAtLeast(0)))

    /**
     * 该不该收场。判定顺序有讲究：
     * 「全部命中」先于「轮次截止」——最后一门刚好在截止瞬间抢到，应报完成而不是截止。
     */
    fun shouldStop(state: GrabState, now: Long): GrabStop? = when {
        state.pendingWishIds.isEmpty() -> GrabStop.AllMatched
        state.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES -> GrabStop.TooManyFailures
        state.roundEndAt != null && now >= state.roundEndAt -> GrabStop.RoundEnded
        now - state.startedAt >= MAX_SESSION_MS -> GrabStop.TimeLimit
        else -> null
    }
}
