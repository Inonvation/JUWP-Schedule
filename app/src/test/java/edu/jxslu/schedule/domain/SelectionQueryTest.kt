package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选课中心服务端查询条件（DESIGN §4.36）：表单校验文案对齐教务页面原话、
 * 默认开关取 App 口径（2026-10-08 窗口期实测）。
 */
class SelectionQueryTest {

    @Test
    fun defaultsMatchAppCaliber() {
        val query = SelectionQuery()
        assertFalse(query.hideFull)
        // 教务页面默认勾选「过滤冲突课程」，但实测它会把已选行（xkzt=9）一起滤掉——
        // App 默认必须关，否则退课入口从列表消失
        assertFalse(query.hideConflict)
        assertTrue(query.hideRestricted)
    }

    @Test
    fun sectionRequiresWeekday() {
        assertEquals(
            "选课「节次」查询时，必须选择「星期」组合一起查询",
            SelectionQuery(startSection = 1, endSection = 2).validationError(),
        )
    }

    @Test
    fun sectionPairMustBeComplete() {
        assertEquals(
            "开始节次和结束节次必须组合一起查询",
            SelectionQuery(weekday = 1, startSection = 1).validationError(),
        )
        assertEquals(
            "开始节次和结束节次必须组合一起查询",
            SelectionQuery(weekday = 1, endSection = 2).validationError(),
        )
    }

    @Test
    fun startSectionMustNotExceedEnd() {
        assertEquals(
            "开始节次必须小于结束节次",
            SelectionQuery(weekday = 1, startSection = 3, endSection = 2).validationError(),
        )
    }

    @Test
    fun validCombinationsPass() {
        assertNull(SelectionQuery().validationError())
        assertNull(SelectionQuery(weekday = 1).validationError())
        assertNull(SelectionQuery(weekday = 1, startSection = 2, endSection = 3).validationError())
        assertNull(SelectionQuery(weekday = 1, startSection = 2, endSection = 2).validationError())
        assertNull(SelectionQuery(keyword = "机械").validationError())
    }

    @Test
    fun teacherDoesNotExemptSectionRule() {
        // 规则只看 星期/节次 的组合，教师不是豁免项（与教务页面 JS 同口径）
        assertEquals(
            "选课「节次」查询时，必须选择「星期」组合一起查询",
            SelectionQuery(teacher = "教师甲", startSection = 1, endSection = 1).validationError(),
        )
    }

    @Test
    fun courseTypeLabels() {
        assertEquals("理论课（不含实践）", SelectionCourseTypes.label("01"))
        assertEquals("理论课（含实践）", SelectionCourseTypes.label("02"))
        assertEquals("集中性实践环节", SelectionCourseTypes.label("04"))
        // 未知代码原样回显（不编）
        assertEquals("99", SelectionCourseTypes.label("99"))
        assertEquals(5, SelectionCourseTypes.OPTIONS.size)
    }
}
