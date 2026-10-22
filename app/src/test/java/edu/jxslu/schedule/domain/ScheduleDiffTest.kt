package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调课检测（DESIGN §4.17 重立）：钉住差异配对、冲突判定与已阅指纹的口径。
 *
 * 核心契约：
 * - 指纹只含教务字段，本机字段（配色/备注/自定义时间）不参与——否则每次检查都报假差异；
 * - 同名同 kind 的一删一增合并成「调整」；多条时按同槽位（星期 + 节次）配对，
 *   同槽位候选多于一个时按周次重叠严格最大配对，平手宁可拆开也不错误配对；
 * - 「与你的修改冲突」判据是当前课表里指纹条目是否原样还在；ADDED 只看同一课块
 *   且周次有交集（合并会跳过），同门课的其他时段不算冲突。
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
    fun `单周调课拆成两块时配成一条调整加一条新增`() {
        // 教务表达「第 8 周周四 7-8 节 → 第 9 周周二 3-4 节」的写法：原块去掉第 8 周，
        // 新块只含第 9 周。基线行与两条远端行指纹都不相等，同槽位的那条才是同一块课——
        // 不能报成「删除整门课 + 两条新增」（2026-10-20 用户实报）
        val old = course(
            name = "人机交互技术",
            teacher = "王五",
            position = "南B106",
            day = 4,
            startSection = 7,
            endSection = 8,
            weeks = setOf(1, 2, 4, 5, 6, 7, 8, 9, 10),
        )
        val kept = old.copy(weeks = setOf(1, 2, 4, 5, 6, 7, 9, 10))
        val moved = old.copy(day = 2, startSection = 3, endSection = 4, position = "北B302", weeks = setOf(9))

        val changes = ScheduleDiff.detect(listOf(old), listOf(kept, moved))
        assertEquals(2, changes.size)
        val modified = changes.first { it.kind == ScheduleDiff.ChangeKind.MODIFIED }
        assertEquals(old, modified.old)
        assertEquals(kept, modified.new)
        assertEquals(moved, changes.first { it.kind == ScheduleDiff.ChangeKind.ADDED }.new)

        // 两条都采纳后基线 = 教务，下次检查不再报差异
        val advanced = ScheduleDiff.baselineAfter(
            listOf(old),
            listOf(
                AppliedEntry(ScheduleDiff.ChangeKind.MODIFIED, before = old, after = kept),
                AppliedEntry(ScheduleDiff.ChangeKind.ADDED, after = moved),
            ),
        )
        assertTrue(ScheduleDiff.detect(advanced, listOf(kept, moved)).isEmpty())
    }

    @Test
    fun `同槽位多条候选按周次重叠配对`() {
        // 教务把单双周拆成两行（同槽位、周次不相交）：各配各的，不拆成 2 删 2 增
        val odd = course(day = 3, weeks = (1..15 step 2).toSet())
        val even = course(day = 3, weeks = (2..16 step 2).toSet())
        val changes = ScheduleDiff.detect(
            listOf(odd, even),
            listOf(odd.copy(position = "教二202"), even.copy(position = "教二202")),
        )
        assertEquals(2, changes.size)
        assertTrue(changes.all { it.kind == ScheduleDiff.ChangeKind.MODIFIED })
    }

    @Test
    fun `教务新增的冲突判据是同一课块重复而不是同门课`() {
        val current = listOf(course(name = "大学物理", day = 5, teacher = "王五"))
        // 同门课的其他时段不是冲突（一门课本来就可能有多块）
        assertFalse(
            ScheduleDiff.isConflicted(
                ScheduleDiff.Change(ScheduleDiff.ChangeKind.ADDED, new = course(name = "大学物理", day = 2)),
                current,
            ),
        )
        // 同槽位但周次不相交：补课 / 回课时换教室，会另插一行，也不是冲突
        assertFalse(
            ScheduleDiff.isConflicted(
                ScheduleDiff.Change(
                    ScheduleDiff.ChangeKind.ADDED,
                    new = course(name = "大学物理", day = 5, teacher = "王五", weeks = (17..18).toSet()),
                ),
                current,
            ),
        )
        // 同一课块且周次有交集：合并会跳过，算冲突
        assertTrue(
            ScheduleDiff.isConflicted(
                ScheduleDiff.Change(
                    ScheduleDiff.ChangeKind.ADDED,
                    new = course(name = "大学物理", day = 5, teacher = "王五"),
                ),
                current,
            ),
        )
        // 不同的课不冲突
        assertFalse(
            ScheduleDiff.isConflicted(
                ScheduleDiff.Change(ScheduleDiff.ChangeKind.ADDED, new = course(name = "数据结构", day = 2)),
                current,
            ),
        )
    }

    /**
     * 合并路径的基线推进（2026-10-07 复核补）：**只在旧基线上套用已应用的改动**。
     *
     * 契约：基线只装教务口径的条目——本机条目（手动添加 / AI 调课）永远进不去。
     * 此前合并后用整张课表重建基线，本机条目会被下一次检查报成「教务删除」，
     * 勾选合并即把用户自己的课删掉。
     */
    @Test
    fun `基线推进只套用已应用的改动`() {
        val base = listOf(course(), course(name = "大学英语", day = 4))
        val entries = listOf(
            AppliedEntry(ScheduleDiff.ChangeKind.REMOVED, before = course(name = "大学英语", day = 4)),
            AppliedEntry(
                ScheduleDiff.ChangeKind.MODIFIED,
                before = course(day = 3),
                after = course(day = 5),
            ),
            AppliedEntry(ScheduleDiff.ChangeKind.ADDED, after = course(name = "大学物理", day = 2)),
        )
        val after = ScheduleDiff.baselineAfter(base, entries)
        assertEquals(setOf("高等数学", "大学物理"), after.map { it.name }.toSet())
        assertEquals(5, after.first { it.name == "高等数学" }.day)
    }

    @Test
    fun `基线推进匹配不上就跳过且不叠加重复新增`() {
        val base = listOf(course())
        // before 在基线里查无此课：跳过，不硬套
        val dirty = listOf(
            AppliedEntry(ScheduleDiff.ChangeKind.REMOVED, before = course(name = "查无此课")),
        )
        assertEquals(base, ScheduleDiff.baselineAfter(base, dirty))
        // ADDED 的指纹已在基线里（脏数据）：不叠加出第二行
        val dup = listOf(AppliedEntry(ScheduleDiff.ChangeKind.ADDED, after = course()))
        assertEquals(base, ScheduleDiff.baselineAfter(base, dup))
    }

    /** 合并完成后的新基线必须与教务抓取「无差异」——否则下一次检查会重复报刚合并的条目。 */
    @Test
    fun `合并完成后的新基线与教务比对无差异`() {
        val base = listOf(course(), course(name = "大学英语", day = 4))
        val remote = listOf(course(day = 5), course(name = "大学物理", day = 2))
        val changes = ScheduleDiff.detect(base, remote)
        // 用户全部勾选：逐条落库后由 AppliedEntry 构造新基线
        val entries = changes.map { change ->
            when (change.kind) {
                ScheduleDiff.ChangeKind.REMOVED ->
                    AppliedEntry(change.kind, before = change.old)
                ScheduleDiff.ChangeKind.MODIFIED ->
                    AppliedEntry(change.kind, before = change.old, after = change.new)
                ScheduleDiff.ChangeKind.ADDED ->
                    AppliedEntry(change.kind, after = change.new)
            }
        }
        val advanced = ScheduleDiff.baselineAfter(base, entries)
        assertTrue(ScheduleDiff.detect(advanced, remote).isEmpty())
    }

    /** 合并导入的判重口径：同课块但周次不相交是两块课，不能静默吞掉。 */
    @Test
    fun `同课块周次不相交不算重复`() {
        val existing = course(weeks = setOf(1, 2, 3))
        val nextWeeks = existing.copy(weeks = setOf(4, 5, 6))
        assertNull(ScheduleDiff.duplicateOf(nextWeeks, listOf(existing)))
        // 周次有交集才算重复：同一块课被重复导入时不能加第二行
        assertNotNull(ScheduleDiff.duplicateOf(existing.copy(weeks = setOf(3, 4)), listOf(existing)))
    }
}
