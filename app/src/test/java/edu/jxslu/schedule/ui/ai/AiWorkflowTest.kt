package edu.jxslu.schedule.ui.ai

import edu.jxslu.schedule.domain.AiScheduleTool
import edu.jxslu.schedule.domain.AppliedEntry
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.domain.OverrideKind
import edu.jxslu.schedule.domain.ScheduleDiff
import edu.jxslu.schedule.domain.ScheduleOverride
import edu.jxslu.schedule.domain.SemesterConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单页工作单 v2 的纯逻辑（DESIGN §3.25 / §4.39，2026-10-07 重构）：
 * 底栏动作映射、输入区 dirty 判定、预览周次选择、回执跨轮保留语义。
 *
 * 这些是本次交互修复的核心契约，任何一处回退都会让「怪」重新长回来：
 * - 结果就绪不折叠（READY 底栏必须给「应用所选」+ 次按钮「重新分析」）；
 * - 空结果不给禁用的「应用所选（0）」，给「重新分析」；
 * - 重新分析不清回执（撤销入口不丢）；
 * - 预览周次覆盖改动实际涉及的周，不是恒第 1 周。
 */
class AiWorkflowTest {

    /** 2026-2027-1：2026-09-07（周一）开学，20 周。 */
    private val semester = SemesterConfig(startDate = "2026-09-07", totalWeeks = 20)

    private fun course(
        weeks: Set<Int> = setOf(1),
        day: Int = 1,
    ) = Course(
        id = 0,
        name = "高等数学",
        teacher = "张三",
        position = "教一101",
        day = day,
        startSection = 3,
        endSection = 4,
        weeks = weeks,
        kind = CourseKind.Theory,
    )

    private fun result(
        changes: List<ScheduleDiff.Change> = emptyList(),
        overrides: List<ScheduleOverride> = emptyList(),
    ) = AiScheduleTool.Result(changes = changes, overrides = overrides)

    private fun receipt(seq: Int, undone: Boolean = false) = AiAppliedReceipt(
        appliedCourses = 1,
        appliedOverrides = 1,
        skipped = 0,
        entries = emptyList(),
        overrideIds = emptyList(),
        timetableId = 1L,
        at = "9:41",
        proposalSeq = seq,
        undone = undone,
    )

    // ---------------- 底栏动作映射 ----------------

    @Test
    fun `底栏动作表与阶段一一对应`() {
        val idle = AiWorkState()
        assertEquals(AiBarAction.ANALYZE, aiBarSpec(idle, hasInput = true).primary)
        assertFalse(aiBarSpec(idle, hasInput = false).primaryEnabled)
        assertTrue(aiBarSpec(idle, hasInput = true).primaryEnabled)

        assertEquals(AiBarAction.STOP, aiBarSpec(idle.copy(phase = AiPhase.ANALYZING), true).primary)

        // 失败：重试 = 原样重发；输入改过时次按钮「重新分析」用当前输入（卡头会标「内容有改动」）
        val failed = aiBarSpec(idle.copy(phase = AiPhase.FAILED), hasInput = true)
        assertEquals(AiBarAction.RETRY, failed.primary)
        assertEquals(AiBarAction.REANALYZE, failed.secondary)
        assertTrue(failed.secondaryEnabled)
        assertFalse(aiBarSpec(idle.copy(phase = AiPhase.FAILED), hasInput = false).secondaryEnabled)

        val ready = AiWorkState(
            phase = AiPhase.READY,
            result = result(changes = listOf(ScheduleDiff.Change(ScheduleDiff.ChangeKind.ADDED, new = course()))),
        )
        val readySpec = aiBarSpec(ready, hasInput = true)
        assertEquals(AiBarAction.APPLY, readySpec.primary)
        assertTrue(readySpec.primaryEnabled)
        // 改完通知还要能重来——次按钮常显「重新分析」
        assertEquals(AiBarAction.REANALYZE, readySpec.secondary)

        // 全部不勾：应用禁用，但仍可重新分析
        val nonePicked = ready.copy(selected = emptySet(), selectedOverrides = emptySet())
        assertFalse(aiBarSpec(nonePicked, true).primaryEnabled)
        assertEquals(AiBarAction.REANALYZE, aiBarSpec(nonePicked, true).secondary)

        assertEquals(AiBarAction.APPLYING, aiBarSpec(ready.copy(phase = AiPhase.APPLYING), true).primary)
        assertFalse(aiBarSpec(ready.copy(phase = AiPhase.APPLYING), true).primaryEnabled)
        assertEquals(AiBarAction.NEW_NOTICE, aiBarSpec(ready.copy(phase = AiPhase.APPLIED), true).primary)
        assertEquals(AiBarAction.APPLY, aiBarSpec(ready.copy(phase = AiPhase.UNDONE), true).primary)
    }

    @Test
    fun `空结果时底栏给重新分析而不是禁用的应用`() {
        val empty = AiWorkState(
            phase = AiPhase.READY,
            result = AiScheduleTool.Result(message = "没有需要改动的课程"),
        )
        val spec = aiBarSpec(empty, hasInput = true)
        assertEquals(AiBarAction.REANALYZE, spec.primary)
        assertTrue(spec.primaryEnabled)
        assertNull(spec.secondary)
        assertFalse(aiBarSpec(empty, hasInput = false).primaryEnabled)
    }

    // ---------------- 输入区 dirty ----------------

    @Test
    fun `草稿与已发送内容不一致才算有改动`() {
        val sent = AiWorkState(
            phase = AiPhase.READY,
            noticeText = "9月20日按第5周周一上课",
            noticeImages = listOf("a.jpg"),
        )
        assertFalse(noticeDirty(sent, "9月20日按第5周周一上课", listOf("a.jpg")))
        // 首尾空白不算改动（发送前 trim 过）
        assertFalse(noticeDirty(sent, "  9月20日按第5周周一上课 ", listOf("a.jpg")))
        assertTrue(noticeDirty(sent, "9月20日按第5周周一上课，停课", listOf("a.jpg")))
        assertTrue(noticeDirty(sent, "9月20日按第5周周一上课", emptyList()))
        // 还没发送过：草稿本来就是新写的，不算改动
        assertFalse(noticeDirty(AiWorkState(), "随手写点什么", listOf("a.jpg")))
    }

    // ---------------- 预览周次 ----------------

    @Test
    fun `预览周次取改动第一周与覆盖周`() {
        val changes = listOf(
            ScheduleDiff.Change(ScheduleDiff.ChangeKind.ADDED, new = course(weeks = setOf(5, 6, 7))),
            ScheduleDiff.Change(ScheduleDiff.ChangeKind.MODIFIED, old = course(weeks = setOf(2)), new = course(weeks = setOf(2))),
        )
        val overrides = listOf(
            ScheduleOverride(
                fromDate = "2026-09-20",
                toDate = "2026-09-20",
                kind = OverrideKind.FOLLOW,
                sourceWeek = 5,
                sourceWeekday = 1,
            ),
        )
        // 2026-09-20 是第 2 周周日 → 覆盖周 = 2；改动周 = 5 与 2 → 去重升序 [2, 5]
        assertEquals(listOf(2, 5), previewWeeksOf(changes, setOf(0, 1), overrides, semester))
    }

    @Test
    fun `未勾选的改动不进预览周次`() {
        val changes = listOf(
            ScheduleDiff.Change(ScheduleDiff.ChangeKind.ADDED, new = course(weeks = setOf(5))),
            ScheduleDiff.Change(ScheduleDiff.ChangeKind.ADDED, new = course(weeks = setOf(9))),
        )
        assertEquals(listOf(5), previewWeeksOf(changes, setOf(0), emptyList(), semester))
        assertTrue(previewWeeksOf(changes, emptySet(), emptyList(), semester).isEmpty())
        // 学期缺失时覆盖不参与，但改动周照给
        assertEquals(listOf(5, 9), previewWeeksOf(changes, setOf(0, 1), emptyList(), null))
    }

    // ---------------- 回执跨轮 ----------------

    @Test
    fun `回执跨轮保留且只按本轮提案判归属`() {
        val receipt = receipt(seq = 1)
        // 新一轮分析（proposalSeq=2）：旧回执不再归属本轮，但撤销仍可用（落「上次应用」条）
        val next = AiWorkState(phase = AiPhase.READY, proposalSeq = 2, receipt = receipt)
        assertNull(next.receiptForThisProposal)
        assertTrue(next.undoAvailable)
        assertFalse(next.isApplied)

        // 同一轮提案已应用
        val applied = AiWorkState(phase = AiPhase.APPLIED, proposalSeq = 1, receipt = receipt)
        assertEquals(receipt, applied.receiptForThisProposal)
        assertTrue(applied.isApplied)

        // 撤销后：留档展示，不再可撤
        val undone = applied.copy(phase = AiPhase.UNDONE, receipt = receipt.copy(undone = true))
        assertEquals(receipt.copy(undone = true), undone.receiptForThisProposal)
        assertFalse(undone.undoAvailable)
        assertFalse(undone.isApplied)
    }

    // ---------------- 调课记录摘要 ----------------

    @Test
    fun `记录摘要取落库条目而非勾选集合`() {
        val entries = listOf(
            AppliedEntry(kind = ScheduleDiff.ChangeKind.MODIFIED, before = course(), after = course()),
            AppliedEntry(kind = ScheduleDiff.ChangeKind.REMOVED, before = course()),
        )
        assertEquals("高等数学", aiHistorySummary(entries, 0))
        val many = (1..5).map {
            AppliedEntry(kind = ScheduleDiff.ChangeKind.ADDED, after = course().copy(name = "课$it"))
        }
        assertEquals("课1、课2、课3 等 5 门", aiHistorySummary(many, 0))
        assertEquals("高等数学；日期覆盖 2 项", aiHistorySummary(entries, 2))
        assertEquals("日期覆盖 1 项", aiHistorySummary(emptyList(), 1))
    }
}
