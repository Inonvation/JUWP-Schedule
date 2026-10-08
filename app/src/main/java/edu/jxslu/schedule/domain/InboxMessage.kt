package edu.jxslu.schedule.domain

/**
 * 收件箱消息（DESIGN §3.24 / §4.38）：应用内重要事实的留痕层。
 *
 * 收的是「可回查的事实」（考试安排、成绩发布、调课差异、选课轮次、余额预警），
 * 不是「时刻型提醒」（上课/作业/洗衣这类过期即无用的通知，见 DESIGN §4.38 的范围边界）。
 * 投递只发生在各业务链「数据已到手并完成对比」的结果点上，收件箱自己不发任何网络请求。
 */
enum class InboxKind {
    /** 考试安排新增 / 时间考场调整（`ExamSync`）。 */
    EXAM,
    /** 成绩发布 / 改分（`ScoreSync`）。 */
    SCORE,
    /** 调课检测发现新差异（`ScheduleSync.check`）。 */
    SCHEDULE_CHANGE,
    /** 选课轮次开始前 / 截止前的提醒落点（`SelectionAlertReminder`）。 */
    SELECTION_ROUND,
    /** 寝室电费低于提醒线（状态型：回升即消解）。 */
    POWER_LOW,
    /** 校园卡正式卡余额低于提醒线（状态型：回升即消解）。 */
    YKT_LOW,
    /** 问渠平台上出现新的可报名志愿活动（`WenquActivityAlertReminder`，2026-10-08）。 */
    WENQU_ACTIVITY,
}

/** 收件箱消息的领域模型（`InboxMessageEntity` 的镜像，纯逻辑层可测）。 */
data class InboxMessage(
    val id: Long = 0,
    val kind: InboxKind,
    /** 去重键：同一事件重复投递一律落更新，不产生第二条（见 [InboxRules.onPost]）。 */
    val dedupeKey: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    /** null = 未读。 */
    val readAt: Long? = null,
    /** 非 null = 用户已删（软删：同键重投不复活，30 天后物理清除）。 */
    val deletedAt: Long? = null,
    /** 点击跳转的二级页（[edu.jxslu.schedule.SubpageScreen] 枚举名），解析不到就不跳。 */
    val route: String? = null,
    /** 二级页的 `focusItemId` 定位参数（可空）。 */
    val focusItemId: String? = null,
)

/** 各业务线的去重键口径。前缀按来源分段，同一段内的事件键各自拼接。 */
object InboxKeys {
    /** 考试：`exam|` + [edu.jxslu.schedule.domain.ExamChangeDetector.keyOf]（courseNo|date）。 */
    const val EXAM_PREFIX = "exam|"

    /**
     * 成绩：`score|term|name|新分数`。分数参与键——改分（85→90）是新事实；
     * 键不含 courseNo（`ScoreChangeDetector.Change` 不带它），同名课同学期极罕见，
     * 撞了也只是合并成一条消息。
     */
    const val SCORE_PREFIX = "score|"

    /** 调课：`schedule|` + [edu.jxslu.schedule.domain.ScheduleDiff.fingerprintOf]。 */
    const val SCHEDULE_PREFIX = "schedule|"

    /** 选课轮次：`selection|` + 提醒键（`轮次id|start` / `轮次id|end`，与已发键同源）。 */
    const val SELECTION_PREFIX = "selection|"

    /** 电费偏低：每来源固定一条状态键，随状态消长（回升即删）。 */
    const val POWER_LOW = "balance|power|low"

    /** 一卡通偏低：同上。 */
    const val YKT_LOW = "balance|ykt|low"

    /**
     * 问渠新活动：`wenqu|activity|` + 本批新活动里**最大的那个 id**。
     * 用「本批最大 id」而不是每个活动一条：一次核对发现 10 个新活动只该留一条痕，
     * 之后再出现更新的活动（id 更大）才算新事实。
     */
    const val WENQU_ACTIVITY_PREFIX = "wenqu|activity|"
}

/**
 * 收件箱的投递与清理决策（纯逻辑，单测在 [edu.jxslu.schedule.InboxRulesTest]）。
 *
 * 投递语义（`dedupeKey` 唯一索引兜底并发）：
 * - 无同键行 → 新插入（未读）；
 * - 同键行已软删 → 只刷新内容，**不复活**（用户删掉就是不想再看这条；事件真变了键就不同，
 *   会以新消息出现）；
 * - 同键行未删 → 刷新内容并**重新置未读**。事件型消息同键重投基本不发生（变更检测以
 *   基线为准）；状态型（余额）靠它实现「已读后次日仍低 → 重新未读」，频率由各链自己的
 *   每日闸门限制，最多一天一次。
 */
object InboxRules {

    sealed interface PostDecision {
        /** 库里没有同键消息：插入一条新的（未读）。 */
        data object Insert : PostDecision

        /** 同键消息还在（未删）：刷新内容并重新置未读。 */
        data class Refresh(val id: Long) : PostDecision

        /** 同键消息已被用户删除：只刷新内容，保持已删状态不复活。 */
        data class RefreshDeleted(val id: Long) : PostDecision
    }

    fun onPost(existing: InboxMessage?, now: Long): PostDecision = when {
        existing == null -> PostDecision.Insert
        existing.deletedAt != null -> PostDecision.RefreshDeleted(existing.id)
        else -> PostDecision.Refresh(existing.id)
    }

    /** 保留策略的裁剪计划：返回要物理删除的消息 id 集合。 */
    data class PurgePlan(val ids: List<Long>)

    /**
     * 三条保留规则（超出即物理删除）：
     * 1. 软删超过 [DELETED_RETENTION_MS]（30 天）——删除意图已充分兑现；
     * 2. 已读且创建超过 [READ_RETENTION_MS]（90 天）——已读留痕的价值随时间归零；
     * 3. 未删消息总量超过 [MAX_MESSAGES]（200 条）——按创建时间保留最新的一份。
     */
    fun planPurge(messages: List<InboxMessage>, now: Long): PurgePlan {
        val ids = mutableSetOf<Long>()
        for (msg in messages) {
            val deleted = msg.deletedAt
            if (deleted != null && now - deleted > DELETED_RETENTION_MS) ids += msg.id
        }
        val alive = messages.filter { it.id !in ids }
        for (msg in alive) {
            if (msg.deletedAt == null && msg.readAt != null && now - msg.createdAt > READ_RETENTION_MS) {
                ids += msg.id
            }
        }
        val kept = alive.filter { it.id !in ids && it.deletedAt == null }
        if (kept.size > MAX_MESSAGES) {
            kept.sortedWith(compareByDescending<InboxMessage> { it.createdAt }.thenByDescending { it.id })
                .drop(MAX_MESSAGES)
                .forEach { ids += it.id }
        }
        return PurgePlan(ids.toList())
    }

    /** 软删后的物理清除窗口：30 天。 */
    const val DELETED_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** 已读消息的保留窗口：90 天（从创建时刻算）。 */
    const val READ_RETENTION_MS: Long = 90L * 24 * 60 * 60 * 1000

    /** 未删消息总量上限（列表查询同样 LIMIT 这个数）。 */
    const val MAX_MESSAGES = 200

    // ------------------------------------------------------------------
    // 时间分组（收件箱页的「今天 / 昨天 / 本周 / 更早」分段头）
    // ------------------------------------------------------------------

    /** 分组标签按时间从新到旧排列。 */
    enum class TimeGroup(val label: String) {
        TODAY("今天"),
        YESTERDAY("昨天"),
        THIS_WEEK("本周"),
        OLDER("更早"),
    }

    /**
     * 消息的分组判定（收件箱页按此分段）。输入是本地时区日期，页面层把
     * `createdAt` 与 [today] 各转一次；归进 [TimeGroup.OLDER] 的「跨年带年份」
     * 由 UI 格式化负责，这里只定桶。
     *
     * 边界口径：本周 = [today] 所在周的周一（ISO 周）起的七天；昨天到七天前之间
     * 落 [TimeGroup.THIS_WEEK]——「上周六」不在本周但距今 5 天，归更早比归本周诚实。
     */
    fun timeGroupOf(
        createdAt: java.time.Instant,
        zone: java.time.ZoneId,
        today: java.time.LocalDate,
    ): TimeGroup {
        val date = createdAt.atZone(zone).toLocalDate()
        return when {
            date == today -> TimeGroup.TODAY
            date == today.minusDays(1) -> TimeGroup.YESTERDAY
            !date.isBefore(today.with(java.time.DayOfWeek.MONDAY)) -> TimeGroup.THIS_WEEK
            else -> TimeGroup.OLDER
        }
    }
}
