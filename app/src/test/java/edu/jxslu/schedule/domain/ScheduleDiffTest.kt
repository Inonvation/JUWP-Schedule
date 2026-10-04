package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调课检测（DESIGN §4.17 重立）：钉住差异配对、冲突判定与已阅指纹的口径。
 *
 * 核心契约：
 * - 指纹只含教务字段，本机字段（配色/备注/自定义时间）不参与——否则每次检查都报假差异；
 * - 同名同 kind 的一删一增合并成「调整」，多删多增宁可拆开也不错误配对；
 * - 「与你的修改冲突」判据是当前课表里指纹条目是否原样还在。
 */
class ScheduleDiffTest {

    private fun course(
        name: String = "高等数学",
        teacher: String = "张三",
        position: String = "教一101",
        day: Int = 3,
        startSection: Int = 3,
        endSection: Int = 4,
        weeks: Set<Int> = (1..16).toSet(),
        kind: CourseKind = CourseKind.Theory,
        colorIndex: Int = 0,
        remark: String = "",
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
        colorIndex = colorIndex,
        remark = remark,
    )

    @Test
    fun `相同集合不同顺序不算差异`() {
        val base = listOf(course(name = "A"), course(name = "B"), course(name = "C"))
        val remote = base.reversed()
        assertTrue(ScheduleDiff.detect(base, remote).isEmpty())
    }

    @Test
    fun `教务新加的课报新增`() {
        val changes = ScheduleDiff.detect(
            baseline = listOf(course()),
            remote = listOf(course(), course(name = "大学物理", day = 5)),
        )
        assertEquals(1, changes.size)
        assertEquals(ScheduleDiff.ChangeKind.ADDED, changes[0].kind)
        assertEquals("大学物理", changes[0].new?.name)
    }

    @Test
    fun `教务删掉的课报删除`() {
        val changes = ScheduleDiff.detect(
            baseline = listOf(course(), course(name = "大学英语")),
            remote = listOf(course()),
        )
        assertEquals(1, changes.size)
        assertEquals(ScheduleDiff.ChangeKind.REMOVED, changes[0].kind)
        assertEquals("大学英语", changes[0].old?.name)
    }

    @Test
    fun `时间挪动配成一条调整而不是一删一增`() {
        val changes = ScheduleDiff.detect(
            baseline = listOf(course(day = 3)),
            remote = listOf(course(day = 5)),
        )
        assertEquals(1, changes.size)
        assertEquals(ScheduleDiff.ChangeKind.MODIFIED, changes[0].kind)
        assertEquals(3, changes[0].old?.day)
        assertEquals(5, changes[0].new?.day)
    }

    @Test
    fun `周次或教师或地点变化都算调整`() {
        listOf(
            course(weeks = (1..8).toSet()),
            course(teacher = "李四"),
            course(position = "教二202"),
        ).forEach { remote ->
            val changes = ScheduleDiff.detect(baseline = listOf(course()), remote = listOf(remote))
            assertEquals(1, changes.size)
            assertEquals(ScheduleDiff.ChangeKind.MODIFIED, changes[0].kind)
        }
    }

    @Test
    fun `同名多块课部分变动时不做错误配对`() {
        // 基线两块：周三与周五；教务两块：周四与周六（一删一增 ×2）。数量相等但
        // 无法确定对应关系 → 拆成 2 删 2 增，绝不冒充「两条调整」
        val base = listOf(course(day = 3), course(name = "高等数学", day = 5))
        val remote = listOf(course(day = 4), course(name = "高等数学", day = 6))
        val changes = ScheduleDiff.detect(base, remote)
        assertEquals(4, changes.size)
        assertEquals(2, changes.count { it.kind == ScheduleDiff.ChangeKind.REMOVED })
        assertEquals(2, changes.count { it.kind == ScheduleDiff.ChangeKind.ADDED })
    }

    @Test
    fun `本机字段变化不产生差异`() {
        // 基线里的条目被本机重排过颜色、加了备注：指纹不含这些字段，照常无差异
        val base = listOf(course(colorIndex = 7, remark = "带计算器"))
        val remote = listOf(course(colorIndex = 3, remark = ""))
        assertTrue(ScheduleDiff.detect(base, remote).isEmpty())
    }

    @Test
    fun `考试条目两侧都不参与比对`() {
        val base = listOf(course(), course(name = "期末考试", kind = CourseKind.Exam, day = 1))
        val remote = listOf(course())
        // 教务课表页抓不到考试：基线里的考试条目不许报成「教务删除」
        assertTrue(ScheduleDiff.detect(base, remote).isEmpty())
    }

    @Test
    fun `已阅指纹与条目顺序无关且内容变化会变`() {
        val a = ScheduleDiff.Change(ScheduleDiff.ChangeKind.ADDED, new = course())
        val b = ScheduleDiff.Change(ScheduleDiff.ChangeKind.REMOVED, old = course(name = "大学英语"))
        assertEquals(
            ScheduleDiff.fingerprintOf(listOf(a, b)),
            ScheduleDiff.fingerprintOf(listOf(b, a)),
        )
        val c = ScheduleDiff.Change(ScheduleDiff.ChangeKind.ADDED, new = course(name = "大学物理"))
        assertNotEquals(
            ScheduleDiff.fingerprintOf(listOf(a, b)),
            ScheduleDiff.fingerprintOf(listOf(a, c)),
        )
    }

    @Test
    fun `当前课表里条目不在了删除和调整都算冲突`() {
        val base = listOf(course(day = 3), course(name = "大学英语", day = 4))
        // 用户把高等数学手动删了、大学英语手动改了时间
        val current = listOf(course(name = "大学英语", day = 6))
        assertTrue(
            ScheduleDiff.isConflicted(
                ScheduleDiff.Change(ScheduleDiff.ChangeKind.REMOVED, old = course(day = 3)),
                current,
            ),
        )
        assertTrue(
            ScheduleDiff.isConflicted(
                ScheduleDiff.Change(
                    ScheduleDiff.ChangeKind.MODIFIED,
                    old = course(name = "大学英语", day = 4),
                    new = course(name = "大学英语", day = 5),
                ),
                current,
            ),
        )
        // 未被用户动过的条目不冲突
        assertFalse(
            ScheduleDiff.isConflicted(
                ScheduleDiff.Change(ScheduleDiff.ChangeKind.REMOVED, old = course(name = "大学英语", day = 4)),
                listOf(course(name = "大学英语", day = 4)),
            ),
        )
    }

    @Test
    fun `教务新增但用户已手动加过同门课算冲突`() {
        val current = listOf(course(name = "大学物理", day = 5, teacher = "王五"))
        assertTrue(
            ScheduleDiff.isConflicted(
                ScheduleDiff.Change(ScheduleDiff.ChangeKind.ADDED, new = course(name = "大学物理", day = 2)),
                current,
            ),
        )
        assertFalse(
            ScheduleDiff.isConflicted(
                ScheduleDiff.Change(ScheduleDiff.ChangeKind.ADDED, new = course(name = "数据结构", day = 2)),
                current,
            ),
        )
    }
}
