package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.domain.ScheduleCourseRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 调课检测抓取结果的两道可信度闸门（DESIGN §4.17，2026-10-07 复核补）：
 *
 * 1. **实验页学期校验**：实验页也带 `select#xnxq01id`（实测单引号 + 无值 selected），
 *    与理论页不一致（教务忽略了学期参数）时整轮放弃——混两个学期的课比对会刷一屏假差异；
 * 2. **整类课「有 → 零」闸门**：理论 / 实验一整类解析为 0 而基线里该类有货，大概率是
 *    解析器与页面脱节，硬比对会把整类课报成「教务删除」，勾选合并即批量删课。
 *    只守这一侧：基线没有、教务新有的课（0 → N）是正常新增，照报。
 */
class ScheduleSyncFetchGuardsTest {

    private fun lesson(name: String, kind: CourseKind = CourseKind.Theory) = Course(
        id = 0,
        name = name,
        teacher = "张三",
        position = "教一101",
        day = 3,
        startSection = 3,
        endSection = 4,
        weeks = setOf(1, 2, 3),
        kind = kind,
    )

    @Test
    fun `实验页学期与理论页一致才放行（单引号选中项形态）`() {
        val lab = """<select id="xnxq01id"><option value='2002-2003-1'>2002-2003-1</option>
            <option value='2026-2027-1' selected>2026-2027-1</option></select>"""
        assertNull(ScheduleSync.labTermError("2026-2027-1", lab))
    }

    @Test
    fun `实验页学期不一致或抽不到下拉都拦下`() {
        val mismatch = """<select id="xnxq01id"><option value='2025-2026-2' selected>2025-2026-2</option></select>"""
        assertNotNull(ScheduleSync.labTermError("2026-2027-1", mismatch))
        assertNotNull(ScheduleSync.labTermError("2026-2027-1", "<html><body>登录页</body></html>"))
    }

    @Test
    fun `整类课从有到零要拦下并报出规模`() {
        val baseline = listOf(
            ScheduleCourseRow.of(lesson("高等数学")),
            ScheduleCourseRow.of(lesson("大学英语")),
            ScheduleCourseRow.of(lesson("实验课", CourseKind.Lab)),
        )
        // 理论整类归零 → 拦
        val theoryGone = ScheduleSync.vanishingKindError(
            remote = listOf(lesson("实验课", CourseKind.Lab)),
            baselineRows = baseline,
        )
        assertNotNull(theoryGone)
        assertEquals(true, theoryGone!!.contains("理论课 2→0"))
        // 实验整类归零 → 拦
        val labGone = ScheduleSync.vanishingKindError(
            remote = listOf(lesson("高等数学"), lesson("大学英语")),
            baselineRows = baseline,
        )
        assertNotNull(labGone)
    }

    @Test
    fun `正常新增与正常删除不拦`() {
        // 基线没有、教务新有（0 → N）：正常新增，放行
        assertNull(
            ScheduleSync.vanishingKindError(
                remote = listOf(lesson("高等数学"), lesson("实验课", CourseKind.Lab)),
                baselineRows = listOf(ScheduleCourseRow.of(lesson("高等数学"))),
            ),
        )
        // 教务删掉部分课（N → N-1）：正常差异，放行
        assertNull(
            ScheduleSync.vanishingKindError(
                remote = listOf(lesson("高等数学")),
                baselineRows = listOf(
                    ScheduleCourseRow.of(lesson("高等数学")),
                    ScheduleCourseRow.of(lesson("大学英语")),
                ),
            ),
        )
    }
}
