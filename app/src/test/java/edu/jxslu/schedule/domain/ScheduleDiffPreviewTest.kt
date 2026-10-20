package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览用：把勾选的改动套到课程列表上（DESIGN §3.25 方案 B）。
 *
 * 契约：**预览必须与真应用同口径**——按指纹匹配、匹配不上就跳过，
 * 否则预览会比真应用更乐观，用户看到「会变」而实际什么都没变。
 */
class ScheduleDiffPreviewTest {

    private fun course(
        id: Long = 1,
        name: String = "高等数学",
        teacher: String = "张三",
        position: String = "教一101",
        day: Int = 3,
        startSection: Int = 3,
        endSection: Int = 4,
        weeks: Set<Int> = (1..16).toSet(),
        colorIndex: Int = 0,
        remark: String = "",
    ) = Course(
        id = id,
        name = name,
        teacher = teacher,
        position = position,
        day = day,
        startSection = startSection,
        endSection = endSection,
        weeks = weeks,
        kind = CourseKind.Theory,
        colorIndex = colorIndex,
        remark = remark,
    )

    @Test
    fun `调整把目标行换成新值并保留本机字段`() {
        val old = course(colorIndex = 5, remark = "带计算器")
        val new = old.copy(day = 5)
        val preview = ScheduleDiff.preview(
            listOf(old),
            listOf(ScheduleDiff.Change(ScheduleDiff.ChangeKind.MODIFIED, old = old, new = new)),
            setOf(0),
        )
        assertEquals(1, preview.size)
        assertEquals(5, preview.single().day)
        // 本机字段照旧保留（与写库同口径）
        assertEquals(5, preview.single().colorIndex)
        assertEquals("带计算器", preview.single().remark)
    }

    @Test
    fun `删除把条目摘掉`() {
        val old = course()
        val preview = ScheduleDiff.preview(
            listOf(old, course(id = 2, name = "大学物理")),
            listOf(ScheduleDiff.Change(ScheduleDiff.ChangeKind.REMOVED, old = old)),
            setOf(0),
        )
        assertEquals(listOf("大学物理"), preview.map { it.name })
    }

    @Test
    fun `新增加进列表且不重复`() {
        val added = course(name = "体育", day = 2)
        val preview = ScheduleDiff.preview(
            listOf(course()),
            listOf(ScheduleDiff.Change(ScheduleDiff.ChangeKind.ADDED, new = added)),
            setOf(0),
        )
        assertEquals(2, preview.size)
        // 已存在同 mergeKey 的课不再插一份
        val again = ScheduleDiff.preview(
            listOf(course(), added.copy(id = 9)),
            listOf(ScheduleDiff.Change(ScheduleDiff.ChangeKind.ADDED, new = added)),
            setOf(0),
        )
        assertEquals(2, again.size)
    }

    @Test
    fun `新增同槽位不同周次时另插一行`() {
        // 补课 / 回课时换教室：同 mergeKey、周次不相交，是同一课块的另一段，不是重复
        val existing = course(weeks = (1..16).toSet())
        val extra = course(weeks = (17..18).toSet())
        val preview = ScheduleDiff.preview(
            listOf(existing),
            listOf(ScheduleDiff.Change(ScheduleDiff.ChangeKind.ADDED, new = extra)),
            setOf(0),
        )
        assertEquals(2, preview.size)
        assertEquals(setOf(17, 18), preview.last().weeks)
    }

    @Test
    fun `新增同槽位且周次有交集时视为重复`() {
        val existing = course(weeks = (1..16).toSet())
        val overlapping = course(weeks = setOf(9, 17))
        val preview = ScheduleDiff.preview(
            listOf(existing),
            listOf(ScheduleDiff.Change(ScheduleDiff.ChangeKind.ADDED, new = overlapping)),
            setOf(0),
        )
        assertEquals(1, preview.size)
    }

    @Test
    fun `没勾的条目不参与预览`() {
        val old = course()
        val preview = ScheduleDiff.preview(
            listOf(old),
            listOf(ScheduleDiff.Change(ScheduleDiff.ChangeKind.MODIFIED, old = old, new = old.copy(day = 5))),
            emptySet(),
        )
        assertEquals(3, preview.single().day)
    }

    @Test
    fun `指纹匹配不上的条目跳过而不是硬套`() {
        val stored = course(day = 3)
        val stale = course(day = 1) // 与当前课表对不上的 old
        val preview = ScheduleDiff.preview(
            listOf(stored),
            listOf(ScheduleDiff.Change(ScheduleDiff.ChangeKind.MODIFIED, old = stale, new = stale.copy(day = 5))),
            setOf(0),
        )
        assertEquals(listOf(3), preview.map { it.day })
    }

    @Test
    fun `空勾选原样返回`() {
        val list = listOf(course(), course(name = "大学物理"))
        val preview = ScheduleDiff.preview(list, emptyList(), emptySet())
        assertEquals(list.map { it.id }, preview.map { it.id })
        assertTrue(preview.isNotEmpty())
    }
}
