package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调课检测的**展示分组**（2026-10-21）：教务把「某一周调课」写成两行（原块减一周 +
 * 新块只含目标周），[ScheduleDiff.detect] 输出行级差异，[ScheduleDiff.group] 再把
 * 这两行合回一次调课。
 *
 * 契约：
 * - 只有「纯周次收缩」的调整才能当调课锚点（换天 / 换教室 / 换教师不合并）；
 * - 目标块同课、周数相等；候选唯一才配，多个候选只有同教室唯一才配；
 * - 分组只是展示单位：组内下标仍是扁平 changes 的下标，写库逐条不变。
 */
class ScheduleDiffGroupTest {

    private fun course(
        name: String,
        teacher: String = "张三",
        position: String = "教一101",
        day: Int = 4,
        startSection: Int = 5,
        endSection: Int = 6,
        weeks: Set<Int> = (1..10).toSet(),
        kind: CourseKind = CourseKind.Theory,
    ) = Course(
        id = 0,
        name = name,
        teacher = teacher,
        position = position,
        day = day,
        startSection = startSection,
        endSection = endSection,
        weeks = weeks,
        kind = kind,
    )

    /** 用户实报场景：官方三条调课通知，旧口径 6 条差异 → 分组后 3 组。 */
    @Test
    fun `三条单周调课通知分成三组`() {
        val hci = course(
            name = "人机交互技术",
            teacher = "曾刚",
            position = "南B106",
            day = 4,
            startSection = 7,
            endSection = 8,
            weeks = setOf(1, 2, 4, 5, 6, 7, 8, 9, 10),
        )
        val mechThu = course(name = "机械制造基础A", teacher = "洪琦", position = "南B206", day = 4)
        val mechFri = course(
            name = "机械制造基础A",
            teacher = "洪琦",
            position = "北B111",
            day = 5,
            startSection = 1,
            endSection = 2,
        )
        val baseline = listOf(hci, mechThu, mechFri)
        val remote = listOf(
            hci.copy(weeks = hci.weeks - 8),
            hci.copy(day = 2, startSection = 3, endSection = 4, position = "北B302", weeks = setOf(9)),
            mechThu.copy(weeks = mechThu.weeks - 8),
            mechThu.copy(day = 6, weeks = setOf(12)),
            mechFri.copy(weeks = mechFri.weeks - 8),
            mechFri.copy(day = 7, weeks = setOf(12)),
        )

        val changes = ScheduleDiff.detect(baseline, remote)
        assertEquals(6, changes.size)
        val groups = ScheduleDiff.group(changes)
        assertEquals(3, groups.size)
        assertTrue(groups.all { it.move != null })
        // 下标仍是扁平 changes 的下标：全部勾选 = 全部应用
        assertEquals(changes.indices.toSet(), groups.flatMap { it.indexes }.toSet())

        val hciGroup = groups.first { it.anchor.old?.name == "人机交互技术" }
        assertEquals(setOf(8), hciGroup.move!!.from.weeks)
        assertEquals(4, hciGroup.move!!.from.day)
        assertEquals(setOf(9), hciGroup.move!!.to.weeks)
        assertEquals(2, hciGroup.move!!.to.day)

        // 同名两块按教室区分，与官方两条通知逐一对应
        val mechGroups = groups.filter { it.anchor.old?.name == "机械制造基础A" }
        val fromThu = mechGroups.first { it.move!!.from.day == 4 }.move!!
        assertEquals("南B206", fromThu.from.position)
        assertEquals(6, fromThu.to.day)
        assertEquals("南B206", fromThu.to.position)
        val fromFri = mechGroups.first { it.move!!.from.day == 5 }.move!!
        assertEquals("北B111", fromFri.from.position)
        assertEquals(7, fromFri.to.day)
        assertEquals("北B111", fromFri.to.position)
    }

    /** 候选不唯一、教室也分不开：宁可拆开，不猜对应关系。 */
    @Test
    fun `候选不唯一且教室分不开时不合并`() {
        val old = course(name = "高等数学", position = "教一101", weeks = (1..10).toSet())
        val kept = old.copy(weeks = old.weeks - 8)
        val remote = listOf(
            kept,
            old.copy(day = 6, weeks = setOf(12)),
            old.copy(day = 7, weeks = setOf(12)),
        )

        val groups = ScheduleDiff.group(ScheduleDiff.detect(listOf(old), remote))
        assertEquals(3, groups.size)
        assertTrue(groups.none { it.move != null })
    }

    /** 只少了周次、没有目标块：是一条普通的周次调整，不是调课。 */
    @Test
    fun `纯周次收缩没有目标块时不成组`() {
        val old = course(name = "高等数学", weeks = (1..10).toSet())
        val remote = listOf(old.copy(weeks = old.weeks - 8))

        val groups = ScheduleDiff.group(ScheduleDiff.detect(listOf(old), remote))
        assertEquals(1, groups.size)
        assertNull(groups.single().move)
    }

    /** 原行还换了教室：合并会把这门课其余周次的换教室藏掉，不配调课。 */
    @Test
    fun `换教室又缩周时不把新增块吞进调课`() {
        val old = course(name = "高等数学", position = "教一101", weeks = (1..10).toSet())
        val remote = listOf(
            old.copy(position = "教二202", weeks = old.weeks - 8),
            old.copy(day = 6, weeks = setOf(12)),
        )

        val changes = ScheduleDiff.detect(listOf(old), remote)
        assertEquals(2, changes.size)
        val groups = ScheduleDiff.group(changes)
        assertEquals(2, groups.size)
        assertTrue(groups.none { it.move != null })
    }

    /** 原行既让出一周又新增一周：不是「纯收缩」，合并会藏掉新增的那一周。 */
    @Test
    fun `周次有增有减时不把新增块吞进调课`() {
        val old = course(name = "高等数学", weeks = (1..10).toSet())
        val remote = listOf(
            old.copy(weeks = (1..7).toSet() + setOf(9, 10, 11)),
            old.copy(day = 6, weeks = setOf(12)),
        )

        val changes = ScheduleDiff.detect(listOf(old), remote)
        assertEquals(2, changes.size)
        val groups = ScheduleDiff.group(changes)
        assertEquals(2, groups.size)
        assertTrue(groups.none { it.move != null })
    }

    /**
     * 待处置缓存的展示条数（课表 hub 入口行「N处差异」，2026-10-21 用户报障）：
     * 2 次单周调课 + 1 门删除在缓存里是 5 行，但对用户是 3 处。入口行与处置页
     * 必须同一口径，否则卡片显示 5、点进去只有 3 张卡。
     */
    @Test
    fun `待处置缓存按组计数而不是按行计数`() {
        val a = course(name = "人机交互技术", day = 4, startSection = 7, endSection = 8)
        val b = course(name = "机械制造基础A", day = 4, startSection = 1, endSection = 2)
        val c = course(name = "大学物理", day = 5, startSection = 3, endSection = 4)
        val remote = listOf(
            a.copy(weeks = a.weeks - 8),
            a.copy(day = 2, startSection = 3, endSection = 4, weeks = setOf(9)),
            b.copy(weeks = b.weeks - 8),
            b.copy(day = 6, weeks = setOf(12)),
        )

        val changes = ScheduleDiff.detect(listOf(a, b, c), remote)
        assertEquals(5, changes.size)
        val pending = SchedulePendingChanges(
            timetableId = 1,
            term = "2026-2027-1",
            changes = changes.map(ScheduleChangeRow::of),
            fingerprint = ScheduleDiff.fingerprintOf(changes),
        )
        // 走一遍落盘 / 读盘（入口行读的就是 DataStore 里的 JSON）
        val restored = SchedulePendingChanges.decode(pending.encode())!!
        assertEquals(5, restored.changes.size)
        assertEquals(3, restored.groupCount())
    }
}
