package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 选课中心的筛选/排序/汇总（DESIGN §3.22）。 */
class SelectionCoursesTest {

    private fun course(
        id: String,
        name: String,
        teacher: String = "张三",
        attribute: String = "必修",
        remaining: Int? = 10,
        selected: Boolean = false,
        timeText: String = "",
    ) = SelectionCourse(
        id = id,
        name = name,
        teacher = teacher,
        attribute = attribute,
        selected = selected,
        timeText = timeText,
        // remaining = null 时容量两端都为空（余量未知）；否则容量 = remaining + 5、已选 5
        capacity = remaining?.plus(5),
        enrolled = remaining?.let { 5 },
    )

    @Test
    fun queryMatchesNameTeacherPlace() {
        val courses = listOf(
            course("1", "机械设计基础A", teacher = "曾刚", timeText = "星期四 0708节"),
            course("2", "PLC原理及应用B", teacher = "李四"),
        )
        assertEquals(
            listOf("1"),
            SelectionCourses.filter(courses, SelectionFilter(query = "机械")).map { it.id },
        )
        assertEquals(
            listOf("1"),
            SelectionCourses.filter(courses, SelectionFilter(query = "曾")).map { it.id },
        )
        assertEquals(
            listOf("1"),
            SelectionCourses.filter(courses, SelectionFilter(query = "0708")).map { it.id },
        )
        assertEquals(
            listOf("2"),
            SelectionCourses.filter(courses, SelectionFilter(query = "plc")).map { it.id },
        )
    }

    @Test
    fun onlyAvailableExcludesKnownFullButKeepsUnknown() {
        val courses = listOf(
            course("1", "满的", remaining = 0),
            course("2", "有余额", remaining = 3),
            course("3", "余量未知", remaining = null),
        )
        assertEquals(
            listOf("2", "3"),
            SelectionCourses.filter(courses, SelectionFilter(onlyAvailable = true)).map { it.id },
        )
    }

    @Test
    fun onlySelectedAndAttribute() {
        val courses = listOf(
            course("1", "必修课A", attribute = "必修", selected = true),
            course("2", "任选课B", attribute = "任选", selected = false),
        )
        assertEquals(
            listOf("1"),
            SelectionCourses.filter(courses, SelectionFilter(onlySelected = true)).map { it.id },
        )
        assertEquals(
            listOf("2"),
            SelectionCourses.filter(courses, SelectionFilter(attribute = "任选")).map { it.id },
        )
        // attribute = null 与空串都当「全部」
        assertEquals(2, SelectionCourses.filter(courses, SelectionFilter(attribute = "")).size)
    }

    @Test
    fun sortRemainingPutsUnknownLast() {
        val courses = listOf(
            course("1", "未知", remaining = null),
            course("2", "两余量", remaining = 2),
            course("3", "满的", remaining = 0),
            course("4", "五余量", remaining = 5),
        )
        assertEquals(
            listOf("4", "2", "3", "1"),
            SelectionCourses.sort(courses, SelectionSort.Remaining).map { it.id },
        )
        // 稳定：同余量保持原顺序
        val same = listOf(course("a", "A", remaining = 1), course("b", "B", remaining = 1))
        assertEquals(listOf("a", "b"), SelectionCourses.sort(same, SelectionSort.Remaining).map { it.id })
    }

    @Test
    fun sortByNameAndTeacher() {
        val courses = listOf(
            course("1", "B课", teacher = "乙"),
            course("2", "A课", teacher = ""),
            course("3", "C课", teacher = "甲"),
        )
        assertEquals(listOf("2", "1", "3"), SelectionCourses.sort(courses, SelectionSort.Name).map { it.id })
        // 教师按**码点序**排（不是拼音序）：乙(U+4E59) < 甲(U+7532)，无教师排最后
        assertEquals(listOf("1", "3", "2"), SelectionCourses.sort(courses, SelectionSort.Teacher).map { it.id })
        // Default 不重排
        assertEquals(listOf("1", "2", "3"), SelectionCourses.sort(courses, SelectionSort.Default).map { it.id })
    }

    @Test
    fun attributesDedupesAndDropsBlank() {
        val courses = listOf(
            course("1", "A", attribute = "必修"),
            course("2", "B", attribute = "任选"),
            course("3", "C", attribute = "必修"),
            course("4", "D", attribute = ""),
        )
        assertEquals(listOf("必修", "任选"), SelectionCourses.attributes(courses))
    }

    @Test
    fun summarizeCounts() {
        val courses = listOf(
            course("1", "已选", selected = true, remaining = 0),
            course("2", "可选的", remaining = 3),
            course("3", "未知余量", remaining = null),
            course("4", "满的", remaining = 0),
        )
        val summary = SelectionCourses.summarize(courses)
        assertEquals(4, summary.total)
        assertEquals(1, summary.selected)
        // 未选且未被证明已满：2（有余额）与 3（未知）计入，4（满）不计
        assertEquals(2, summary.selectable)
        assertTrue(summary.selectable <= summary.total)
    }
}
