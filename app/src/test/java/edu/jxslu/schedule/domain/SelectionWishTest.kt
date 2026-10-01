package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 预选清单：匹配口径、优先级排序、校验与 JSON 往返（DESIGN §3.20）。 */
class SelectionWishTest {

    private fun wish(
        name: String,
        teacher: String = "",
        priority: Int = SelectionWishes.PRIORITY_MEDIUM,
    ) = SelectionWish(nameKeyword = name, teacherKeyword = teacher, priority = priority)

    @Test
    fun matchesByNameKeyword() {
        val w = wish("机械设计")
        assertTrue(SelectionWishes.matches(w, "机械设计基础A", "张三"))
        // 去空白 + 忽略大小写
        assertTrue(SelectionWishes.matches(wish("plc"), "PLC原理及应用B", "李四"))
        assertFalse(SelectionWishes.matches(w, "液压与气压传动A", "张三"))
    }

    @Test
    fun matchesWithTeacherKeyword() {
        val w = wish("机械", teacher = "曾")
        assertTrue(SelectionWishes.matches(w, "机械制造基础A", "曾刚,李四"))
        assertFalse(SelectionWishes.matches(w, "机械制造基础A", "张三"))
        // 教师关键词为空时只看课程名
        assertTrue(SelectionWishes.matches(wish("机械"), "机械制造基础A", "张三"))
    }

    @Test
    fun blankInputsNeverMatch() {
        assertFalse(SelectionWishes.matches(wish("机械"), "", "张三"))
        assertFalse(SelectionWishes.matches(wish("   "), "机械设计", "张三"))
    }

    @Test
    fun sortedByPriorityKeepsInsertOrderWithinSameLevel() {
        val low = wish("A", priority = SelectionWishes.PRIORITY_LOW)
        val high1 = wish("B", priority = SelectionWishes.PRIORITY_HIGH)
        val high2 = wish("C", priority = SelectionWishes.PRIORITY_HIGH)
        val medium = wish("D")
        assertEquals(
            listOf("B", "C", "D", "A"),
            SelectionWishes.sorted(listOf(low, high1, high2, medium)).map { it.nameKeyword },
        )
    }

    @Test
    fun validateRequiresNameKeyword() {
        assertNull(SelectionWishes.validate(wish("机械", teacher = "曾")))
        assertEquals("请填写课程名关键词", SelectionWishes.validate(wish("   ")))
    }

    @Test
    fun priorityLabelFallsBackToMedium() {
        assertEquals("高", SelectionWishes.priorityLabel(SelectionWishes.PRIORITY_HIGH))
        assertEquals("低", SelectionWishes.priorityLabel(SelectionWishes.PRIORITY_LOW))
        assertEquals("中", SelectionWishes.priorityLabel(7))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val wishes = listOf(wish("机械设计", teacher = "曾", priority = SelectionWishes.PRIORITY_HIGH))
        assertEquals(wishes, SelectionWishes.decode(SelectionWishes.encode(wishes)))
    }

    @Test
    fun decodeBadJsonReturnsEmpty() {
        assertTrue(SelectionWishes.decode("{不是列表}").isEmpty())
        assertTrue(SelectionWishes.decode("").isEmpty())
    }
}
