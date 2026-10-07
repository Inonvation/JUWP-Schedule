package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 调课「撤销」的反向规划（DESIGN §4.39）：钉住倒序、逐类型反向与**状态闸**。
 *
 * 为什么要有这层纯函数：撤销是本功能唯一需要新写事务逻辑的地方，
 * 而仓储那侧要起 Room 才测得了。规划与执行分开后，最容易写错的
 * 「倒序」「各类型反着什么」「应用后被改过的行不能硬覆盖」可以在 JVM 上钉死。
 */
class ScheduleUndoTest {

    private fun course(id: Long, name: String = "高等数学", day: Int = 3) = Course(
        id = id,
        name = name,
        teacher = "张三",
        position = "教一101",
        day = day,
        startSection = 3,
        endSection = 4,
        weeks = setOf(1, 2, 3),
        kind = CourseKind.Theory,
    )

    /** 与写入同口径：`current` 就是「改后」的样子（状态闸要它才比得出指纹）。 */
    private fun currentOf(vararg entries: AppliedEntry): List<Course> =
        entries.mapNotNull { it.after }

    @Test
    fun `新增的反向是删掉写入的那一行`() {
        val after = course(id = 42)
        val entries = listOf(AppliedEntry(ScheduleDiff.ChangeKind.ADDED, after = after))
        val plan = ScheduleUndo.plan(entries, currentOf(entries[0]))
        assertEquals(listOf(ScheduleUndo.Action.Delete(42L)), plan.actions)
        assertEquals(0, plan.skipped)
    }

    @Test
    fun `调整的反向是用改前那一行覆盖回去`() {
        val before = course(id = 7, name = "高等数学")
        val after = course(id = 7, name = "高等数学", day = 4)
        val entries = listOf(AppliedEntry(ScheduleDiff.ChangeKind.MODIFIED, before = before, after = after))
        val plan = ScheduleUndo.plan(entries, currentOf(entries[0]))
        assertEquals(listOf(ScheduleUndo.Action.Upsert(before)), plan.actions)
    }

    @Test
    fun `删除的反向是把改前那一行插回去（带原 id）`() {
        val before = course(id = 9, name = "体育")
        val entries = listOf(AppliedEntry(ScheduleDiff.ChangeKind.REMOVED, before = before))
        // REMOVED 之后那行已经不在课表里了
        val plan = ScheduleUndo.plan(entries, emptyList())
        assertEquals(1, plan.actions.size)
        val upsert = plan.actions.first() as ScheduleUndo.Action.Upsert
        assertEquals(9L, upsert.course.id)
        assertEquals("体育", upsert.course.name)
    }

    @Test
    fun `多次改动倒序撤销`() {
        val a = course(id = 1, name = "A")
        val b = course(id = 2, name = "B")
        val c = course(id = 3, name = "C")
        val entries = listOf(
            AppliedEntry(ScheduleDiff.ChangeKind.ADDED, after = a),
            AppliedEntry(ScheduleDiff.ChangeKind.MODIFIED, before = b, after = b.copy(day = 5)),
            AppliedEntry(ScheduleDiff.ChangeKind.REMOVED, before = c),
        )
        // 后落库的先撤：REMOVED → MODIFIED → ADDED
        assertEquals(
            listOf(
                ScheduleUndo.Action.Upsert(c),
                ScheduleUndo.Action.Upsert(b),
                ScheduleUndo.Action.Delete(1L),
            ),
            ScheduleUndo.plan(entries, currentOf(entries[0], entries[1])).actions,
        )
    }

    @Test
    fun `缺反向信息的脏条目被跳过而不是整批失败`() {
        val good = course(id = 5, name = "大学英语")
        val entries = listOf(
            AppliedEntry(ScheduleDiff.ChangeKind.ADDED, after = null),
            AppliedEntry(ScheduleDiff.ChangeKind.REMOVED, before = good),
            AppliedEntry(ScheduleDiff.ChangeKind.MODIFIED, before = null, after = good),
        )
        val plan = ScheduleUndo.plan(entries, emptyList())
        assertEquals(listOf(ScheduleUndo.Action.Upsert(good)), plan.actions)
        // 缺反向信息是「我们没有记录」，不是「用户后来改过」——不计入 skipped，
        // 否则 UI 会报「1 条你后来改过，未动」，把责任推给用户
        assertEquals(0, plan.skipped)
    }

    @Test
    fun `空列表得到空计划`() {
        assertTrue(ScheduleUndo.plan(emptyList(), emptyList()).isEmpty)
    }

    // ---- 状态闸（2026-10-06 复核补）----

    @Test
    fun `应用后被用户手动改过的行不撤销`() {
        val before = course(id = 7, name = "高等数学")
        val after = course(id = 7, name = "高等数学", day = 4)
        val entries = listOf(AppliedEntry(ScheduleDiff.ChangeKind.MODIFIED, before = before, after = after))
        // 用户应用之后又自己改了一次（教室变了）→ 指纹与 after 对不上
        val userEdited = after.copy(position = "教二202")
        val plan = ScheduleUndo.plan(entries, listOf(userEdited))
        assertTrue("不该按快照硬覆盖用户的后续修改", plan.actions.isEmpty())
        assertEquals(1, plan.skipped)
    }

    @Test
    fun `应用后被改过的行不删`() {
        val after = course(id = 42, name = "形势与政策")
        val entries = listOf(AppliedEntry(ScheduleDiff.ChangeKind.ADDED, after = after))
        val plan = ScheduleUndo.plan(entries, listOf(after.copy(day = 6)))
        assertTrue(plan.actions.isEmpty())
        assertEquals(1, plan.skipped)
    }

    @Test
    fun `新增那行已经被删掉时无事可做也不算跳过`() {
        val after = course(id = 42)
        val entries = listOf(AppliedEntry(ScheduleDiff.ChangeKind.ADDED, after = after))
        val plan = ScheduleUndo.plan(entries, emptyList())
        assertTrue(plan.actions.isEmpty())
        assertEquals(0, plan.skipped)
    }

    @Test
    fun `原 id 被别的行占用时不覆盖它`() {
        val before = course(id = 9, name = "体育")
        val entries = listOf(AppliedEntry(ScheduleDiff.ChangeKind.REMOVED, before = before))
        // 带 id 的恢复导入可能把 9 号占掉
        val plan = ScheduleUndo.plan(entries, listOf(course(id = 9, name = "别人的课")))
        assertTrue("不能覆盖一条无关的课", plan.actions.isEmpty())
        assertEquals(1, plan.skipped)
    }

    @Test
    fun `用户把同门课加回来后删除的不再插回`() {
        // AI 删过一门课，用户后来自己把它加回来了（新 id）——
        // 再按快照插一遍会出现两行同课（与写路径 ADDED 的 mergeKey 防重同一把钥匙）
        val before = course(id = 9, name = "体育")
        val entries = listOf(AppliedEntry(ScheduleDiff.ChangeKind.REMOVED, before = before))
        val readded = before.copy(id = 77)
        val plan = ScheduleUndo.plan(entries, listOf(readded))
        assertTrue("不能把同门课插成两行", plan.actions.isEmpty())
        assertEquals(1, plan.skipped)
    }
}
