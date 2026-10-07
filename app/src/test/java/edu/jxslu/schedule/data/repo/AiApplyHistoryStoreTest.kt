package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.domain.AppliedEntry
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.domain.ScheduleDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 调课留痕（DESIGN §4.39，2026-10-07）的纯逻辑：列表维护、栈式撤销判据、
 * 以及 **JSON 往返的保真度**——留痕存的是撤销所需的全部信息，行 id 与自定义时间
 * 丢了，跨会话的「撤销」就会撤错行或把用户的自定义时间清掉。
 *
 * 落盘路径（filesDir）依赖 Android 运行时，不在 JVM 测（同 UjingHistoryStore 口径）；
 * encode/decode 是纯函数，直接测 round-trip。
 */
class AiApplyHistoryStoreTest {

    private fun course(
        id: Long,
        name: String = "高等数学",
        custom: Boolean = false,
    ) = Course(
        id = id,
        name = name,
        teacher = "张三",
        position = "教一101",
        day = 1,
        startSection = 3,
        endSection = 4,
        weeks = setOf(1, 2, 3),
        isCustomTime = custom,
        customStartTime = if (custom) "08:00" else null,
        customEndTime = if (custom) "09:35" else null,
        kind = CourseKind.Theory,
    )

    private fun record(id: Long, undone: Boolean = false, timetable: Long = 1L) = AiApplyRecord(
        id = id,
        appliedCourses = 1,
        appliedOverrides = 0,
        skipped = 0,
        timetableId = timetable,
        summary = "高等数学",
        entries = emptyList(),
        overrideIds = emptyList(),
        undone = undone,
    )

    // ---------------- 列表维护 ----------------

    @Test
    fun `新记录在顶且同 id 去重`() {
        val list = AiApplyHistoryStore.upsertInto(listOf(record(1L)), record(2L))
        assertEquals(listOf(2L, 1L), list.map { it.id })
        // 同 id 重放（不该发生，但别把旧条目吃掉成两份）
        val again = AiApplyHistoryStore.upsertInto(list, record(2L))
        assertEquals(listOf(2L, 1L), again.map { it.id })
    }

    @Test
    fun `超量截断到上限`() {
        val old = List(AiApplyHistoryStore.CAP) { i -> record(100L + i) }
        val list = AiApplyHistoryStore.upsertInto(old, record(999L))
        assertEquals(AiApplyHistoryStore.CAP, list.size)
        assertEquals(999L, list.first().id)
        // 最老的一条被截掉
        assertTrue(list.none { it.id == 100L + AiApplyHistoryStore.CAP - 1 })
    }

    @Test
    fun `只允许撤销最新一条未撤销`() {
        // 栈式：撤掉顶部之后，上一条才轮到可撤
        assertEquals(3L, AiApplyHistoryStore.latestUndoable(listOf(record(3L), record(2L), record(1L)))!!.id)
        assertEquals(2L, AiApplyHistoryStore.latestUndoable(listOf(record(3L, undone = true), record(2L)))!!.id)
        assertNull(AiApplyHistoryStore.latestUndoable(listOf(record(2L, undone = true), record(1L, undone = true))))
        assertNull(AiApplyHistoryStore.latestUndoable(emptyList()))
    }

    @Test
    fun `已删课表的记录不挡栈也不可撤`() {
        // 顶部是已删课表(B)的记录：它永远撤不动（撤销写会向不存在的课表塞孤儿行），
        // 但不该把栈堵死——用户还得能撤更早的 A 记录
        val list = listOf(
            record(3L, timetable = 2L),
            record(2L, timetable = 1L),
            record(1L, timetable = 1L),
        )
        assertEquals(2L, AiApplyHistoryStore.latestUndoable(list, setOf(1L))!!.id)
        // 两张课表都在时维持原语义（最新的先撤）
        assertEquals(3L, AiApplyHistoryStore.latestUndoable(list, setOf(1L, 2L))!!.id)
        // null = 存活集合未知（首帧）：不跳过，兜底旧行为
        assertEquals(3L, AiApplyHistoryStore.latestUndoable(list, null)!!.id)
    }

    @Test
    fun `标记撤销幂等`() {
        val list = listOf(record(2L), record(1L))
        val once = AiApplyHistoryStore.markUndoneIn(list, 2L)
        assertTrue(once.first().undone)
        assertFalse(once.last().undone)
        assertEquals(once, AiApplyHistoryStore.markUndoneIn(once, 2L))
        // 不存在的 id 是空操作
        assertEquals(once, AiApplyHistoryStore.markUndoneIn(once, 42L))
    }

    // ---------------- 序列化往返 ----------------

    @Test
    fun `留痕 JSON 往返保留行 id 与自定义时间`() {
        val entries = listOf(
            AppliedEntry(
                kind = ScheduleDiff.ChangeKind.MODIFIED,
                before = course(7L),
                after = course(7L, custom = true),
            ),
            AppliedEntry(kind = ScheduleDiff.ChangeKind.ADDED, after = course(9L)),
        )
        val original = AiApplyRecord(
            id = 1_728_000_000_000L,
            appliedCourses = 2,
            appliedOverrides = 1,
            skipped = 1,
            timetableId = 5L,
            summary = "高等数学",
            entries = entries.map { it.toRow() },
            overrideIds = listOf(3L, 4L),
        )
        val decoded = AiApplyHistoryStore.decode(AiApplyHistoryStore.encode(listOf(original)))
        assertEquals(listOf(original), decoded)

        // 还原回域对象：撤销按 id 删 / 覆盖，自定义时间必须原样带回
        val restored = decoded.first().entries.mapNotNull { it.toEntry() }
        assertEquals(2, restored.size)
        assertEquals(7L, restored[0].before!!.id)
        assertEquals(7L, restored[0].after!!.id)
        assertTrue(restored[0].after!!.isCustomTime)
        assertEquals("08:00", restored[0].after!!.customStartTime)
        assertEquals("09:35", restored[0].after!!.customEndTime)
        assertEquals(9L, restored[1].after!!.id)
        assertNull(restored[1].before)
    }

    @Test
    fun `kind 认不出的存档行还原成 null`() {
        assertNull(AiAppliedEntryRow(kind = "WHAT", after = null).toEntry())
    }

    @Test
    fun `空表往返`() {
        assertTrue(AiApplyHistoryStore.decode(AiApplyHistoryStore.encode(emptyList())).isEmpty())
    }
}
