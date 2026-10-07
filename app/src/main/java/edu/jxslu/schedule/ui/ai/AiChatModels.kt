package edu.jxslu.schedule.ui.ai

import edu.jxslu.schedule.domain.AiScheduleTool
import edu.jxslu.schedule.domain.AppliedEntry
import edu.jxslu.schedule.domain.ScheduleDiff
import edu.jxslu.schedule.domain.ScheduleOverride
import edu.jxslu.schedule.ui.common.NoticeTone

/**
 * AI 调课 · 单页工作单的数据模型（DESIGN §3.25，2026-10-07 v2 重构）。
 *
 * 形态：上面通知输入卡、中间一份提案、下面一条贴底动作条。不做对话；
 * 不满意就改通知再点「重新分析」。红线不变：模型的 tool_call 只是**提议**，
 * 落库必须过用户逐条勾选 + 显式应用；无后台调用（只有点「分析」才发请求）；
 * 会话与图片**不落盘**。
 *
 * v2 与上一版的差异（都是交互修复）：
 * - **结果就绪不再自动折叠**（折叠只在应用成功后发生）——勾选是主战场，不许藏；
 * - **应用回执跨轮保留**（[AiWorkState.receipt] 不再随新一轮分析销毁）——
 *   重新分析后撤销入口仍在（页面渲染成「上次应用」条）；
 * - 底栏动作收成唯一映射 [aiBarSpec]（纯函数），撤销只跟回执行走、不进底栏。
 */

/** 一次性提示（应用 / 撤销的结果），由 UI 消费后清。 */
data class AiNotice(val text: String, val tone: NoticeTone)

/** 工作单所处阶段（驱动贴底动作条与结果区的呈现）。 */
enum class AiPhase {
    /** 待输入：通知为空（或只有没发过的草稿）。 */
    IDLE,

    /** 正在流式接收。 */
    ANALYZING,

    /** 调用失败，[AiWorkState.failure] 是用户可读文案。 */
    FAILED,

    /** 结果就绪待确认（含空结果 / 纯文本兜底）。 */
    READY,

    /** 应用事务进行中（防重入闸）。 */
    APPLYING,

    /** 已应用且未撤销。 */
    APPLIED,

    /** 已撤销（可改勾选后重新应用 = 走 [AiPhase.APPLYING]）。 */
    UNDONE,
}

/** 贴底动作条的动作（标签与行为在 UI 层映射，这里是纯枚举）。 */
enum class AiBarAction { ANALYZE, STOP, RETRY, REANALYZE, APPLY, APPLYING, NEW_NOTICE }

/**
 * 动作条规格（纯映射，单测钉住）：主按钮必给，次按钮可空。
 *
 * 语义是「本轮工作单的下一步」：未分析 → 分析；接收中 → 停止；就绪 → 应用所选
 * （草稿改过可次按钮「重新分析」）；已应用 → 分析新通知（本轮结束，回执落「上次应用」条）；
 * 已撤销 → 可重新应用。**撤销不在底栏**——它跟着回执行走（摘要行 / 展开后的回执行）。
 */
data class AiBarSpec(
    val primary: AiBarAction,
    val primaryEnabled: Boolean,
    val secondary: AiBarAction? = null,
    val secondaryEnabled: Boolean = true,
)

/**
 * 贴底动作条的规格来源。[hasInput] = 通知输入区当前有内容（文字或图片）。
 */
fun aiBarSpec(work: AiWorkState, hasInput: Boolean): AiBarSpec = when (work.phase) {
    AiPhase.IDLE -> AiBarSpec(AiBarAction.ANALYZE, hasInput)
    AiPhase.ANALYZING -> AiBarSpec(AiBarAction.STOP, true)
    // 重试 = 原样重发；输入改过时给次按钮「重新分析」（用当前输入），
    // 否则唯一按钮发的是旧文本，与卡头「内容有改动」互相矛盾
    AiPhase.FAILED -> AiBarSpec(AiBarAction.RETRY, true, AiBarAction.REANALYZE, hasInput)
    // 空结果不给死路的禁用「应用所选（0）」——改改通知重新分析才是出路
    AiPhase.READY, AiPhase.UNDONE ->
        if (work.isEmptyResult) {
            AiBarSpec(AiBarAction.REANALYZE, hasInput)
        } else {
            AiBarSpec(AiBarAction.APPLY, work.selectedCount > 0, AiBarAction.REANALYZE, hasInput)
        }

    AiPhase.APPLYING -> AiBarSpec(AiBarAction.APPLYING, false)
    AiPhase.APPLIED -> AiBarSpec(AiBarAction.NEW_NOTICE, true)
}

/**
 * 应用回执：**撤销所需的全部信息**。
 *
 * [entries] 是真正落库的课表改动；[overrideIds] 是那次写入的日期覆盖行 id。
 * 计数分开存：调休类通知全是日期覆盖、课表改动为 0，只报合计会显示「已应用 0 处」。
 * [proposalSeq] 记下应用的是哪一轮提案——防重入与「摘要归属」都靠它。
 */
data class AiAppliedReceipt(
    val appliedCourses: Int,
    val appliedOverrides: Int,
    val skipped: Int,
    val entries: List<AppliedEntry>,
    val overrideIds: List<Long>,
    /** 落库时的课表 id：撤销要按它落，不能按「撤销那一刻的当前课表」。 */
    val timetableId: Long,
    val at: String,
    val proposalSeq: Int,
    /** 已被撤销（回执留档展示，不再可撤）。 */
    val undone: Boolean = false,
    /**
     * 这次应用在 [edu.jxslu.schedule.data.repo.AiApplyHistoryStore] 里的留痕 id
     * （撤销要同步标记它，跨会话的「调课记录」页据此判断还能不能撤）。
     */
    val historyId: Long = 0L,
) {
    /** 合计处数（课表改动 + 日期覆盖）。 */
    val applied: Int get() = appliedCourses + appliedOverrides
}

/**
 * 「应用事务跑完但一处都没落库」——不算失败，也不该改状态：提案留着让用户重来
 * （通常是对不上指纹：分析期间课表被改过）。带条数给 UI 说清楚。
 */
class AiNothingApplied(val skipped: Int) :
    IllegalStateException(if (skipped > 0) "没有改动被应用：$skipped 条与当前课表对不上" else "没有改动被应用")

/**
 * 工作单的完整状态。**一份**（不是消息列表）。
 *
 * 勾选用 `Set<Int>?`：`null` = 用户还没动过，按**默认全勾**算（见 [effectiveSelection]）。
 * 新一轮分析 = 整个 [result] 换新对象，选择自然重置；但 [receipt] 跨轮保留。
 */
data class AiWorkState(
    val phase: AiPhase = AiPhase.IDLE,

    /** 本轮提案序号：每次发起请求（分析 / 重试 / 重新分析）+1。 */
    val proposalSeq: Int = 0,

    /** 本轮「分析」实际发出的通知原文（重试原样复用）。 */
    val noticeText: String = "",

    /** 本轮「分析」实际发出的图片（`AiImageStore` 文件名）。 */
    val noticeImages: List<String> = emptyList(),

    /** 分析完成时刻（显示用，形如 `9:41`）。 */
    val analyzedAt: String = "",

    /** 思考过程全文（流式累积；展示开关在配置页）。 */
    val reasoning: String = "",

    /** 正文流（兜底展示用；完成时并进 message 并清空）。 */
    val content: String = "",

    val result: AiScheduleTool.Result? = null,

    /** 失败文案。 */
    val failure: String = "",

    /** 失败是不是「上游不吃图片」——决定给不给「去掉图片重发」。 */
    val failureIsImage: Boolean = false,

    val selected: Set<Int>? = null,
    val selectedOverrides: Set<Int>? = null,

    /** 最近一次应用的回执；**跨轮保留**（新一轮分析不清它，撤销入口不丢）。 */
    val receipt: AiAppliedReceipt? = null,

    /** 撤销时因「行已被改动 / id 被占用」而放弃的条数（UI 要如实提示）。 */
    val undoSkipped: Int = 0,

    /** 正在落库——防重入闸。 */
    val applying: Boolean = false,

    /** 应用成功后提案卡自动折叠成摘要卡（用户可点开）。 */
    val collapsed: Boolean = false,
) {

    val changes: List<ScheduleDiff.Change> get() = result?.changes.orEmpty()

    val overrides: List<ScheduleOverride> get() = result?.overrides.orEmpty()

    /** 实际勾选（`null` = 默认全勾）。 */
    val effectiveSelection: Set<Int> get() = selected ?: changes.indices.toSet()

    val effectiveOverrideSelection: Set<Int> get() = selectedOverrides ?: overrides.indices.toSet()

    val selectedCount: Int get() = effectiveSelection.size + effectiveOverrideSelection.size

    val totalCount: Int get() = changes.size + overrides.size

    /** 结果里没有任何可应用的条目。 */
    val isEmptyResult: Boolean get() = changes.isEmpty() && overrides.isEmpty()

    /** 已应用且未撤销（摘要卡 / 回执行的状态依据）。 */
    val isApplied: Boolean get() = receipt?.undone == false && phase == AiPhase.APPLIED

    /** 结论文本：模型写的 message；空则由 UI 按 [isEmptyResult] 给兜底文案。 */
    val headline: String get() = result?.message.orEmpty()

    /**
     * 回执是否属于**当前这份提案**（摘要卡用它判定「这份提案已应用」）。
     *
     * 新一轮分析后 [proposalSeq] 前移，旧回执不再归属当前提案——它落成页面的
     * 「上次应用」条（撤销入口仍在）。
     */
    val receiptForThisProposal: AiAppliedReceipt? get() = receipt?.takeIf { it.proposalSeq == proposalSeq }

    /** 有可撤销的回执（撤销按钮的可用判据；与阶段无关）。 */
    val undoAvailable: Boolean get() = receipt?.undone == false
}

/**
 * 通知输入区与「本轮已发送内容」是否不一致（驱动状态词与提示，纯函数）。
 *
 * [AiPhase.IDLE]（还没有发送过任何内容）永远算一致——草稿是新写的，不是改动。
 */
fun noticeDirty(work: AiWorkState, draft: String, attachments: List<String>): Boolean {
    if (work.phase == AiPhase.IDLE) return false
    return draft.trim() != work.noticeText || attachments != work.noticeImages
}

/**
 * 「调课记录」里一行摘要（纯函数）：从**真正落库的**条目取课程名——
 * 不用勾选集合，勾了但被指纹挡掉的条目不该出现在记录里。最多列 3 门，多了报总数。
 */
fun aiHistorySummary(entries: List<AppliedEntry>, overrideCount: Int): String {
    val names = entries.mapNotNull { (it.after ?: it.before)?.name?.takeIf(String::isNotBlank) }.distinct()
    val head = when {
        names.isEmpty() -> ""
        names.size <= 3 -> names.joinToString("、")
        else -> names.take(3).joinToString("、") + " 等 ${names.size} 门"
    }
    return buildString {
        append(head)
        if (overrideCount > 0) {
            if (isNotEmpty()) append("；")
            append("日期覆盖 $overrideCount 项")
        }
    }
}
