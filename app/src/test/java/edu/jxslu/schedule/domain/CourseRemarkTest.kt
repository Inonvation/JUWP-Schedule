package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 课程备注的搬运（DESIGN §4.3「备注的存活口径」）。
 *
 * 备注是用户手写的内容，而课程行会被覆盖导入/调课检测应用**重建**——搬丢的表现是
 * 「导入一次备注全没了」，且用户很难意识到是自己没写；这里把口径钉死。
 */
class CourseRemarkTest {

    private fun course(
        name: String = "高等数学",
        day: Int = 1,
        start: Int = 1,
        end: Int = 2,
        teacher: String = "张老师",
        kind: CourseKind = CourseKind.Theory,
        remark: String = "",
        id: Long = 0,
    ) = Course(
        id = id,
        name = name,
        teacher = teacher,
        position = "",
        day = day,
        startSection = start,
        endSection = end,
        weeks = setOf(1, 2, 3),
        kind = kind,
        remark = remark,
    )

    @Test
    fun carriedOver_whenKeyMatches() {
        val old = course(remark = "带计算器")
        val incoming = course(id = 99, remark = "")
        // mergeKey 不含 id / weeks / colorIndex：重建后 key 不变，备注应搬回来
        val result = courseRemarksCarriedOver(listOf(old), listOf(incoming))
        assertEquals("带计算器", result.single().remark)
    }

    @Test
    fun incomingRemarkWins() {
        // 合并导入/本地编辑带了自己的备注 → 不覆盖
        val old = course(remark = "旧备注")
        val incoming = course(remark = "新备注")
        assertEquals("新备注", courseRemarksCarriedOver(listOf(old), listOf(incoming)).single().remark)
    }

    @Test
    fun newCourseHasNoRemark() {
        val old = course(remark = "带计算器")
        val brandNew = course(name = "大学物理", remark = "")
        assertEquals("", courseRemarksCarriedOver(listOf(old), listOf(brandNew)).single().remark)
    }

    @Test
    fun kindIsPartOfTheKey() {
        // 理论课与实验课可能同名同节次：备注各归各的，不串门
        val theory = course(kind = CourseKind.Theory, remark = "理论课备注")
        val lab = course(kind = CourseKind.Lab, remark = "")
        val result = courseRemarksCarriedOver(listOf(theory), listOf(lab))
        assertEquals("", result.single().remark)
    }

    @Test
    fun duplicateKeysAllGetTheRemark() {
        // 同一门课按周拆成多行（实验课常见）：每行都拿到同一条备注
        val a = course(day = 2, remark = "分组：第 3 组")
        val incoming = listOf(course(day = 2, id = 1), course(day = 2, id = 2))
        val result = courseRemarksCarriedOver(listOf(a), incoming)
        assertEquals(listOf("分组：第 3 组", "分组：第 3 组"), result.map { it.remark })
    }

    @Test
    fun emptyInputsAreNoOps() {
        val old = course(remark = "带计算器")
        assertEquals(emptyList<Course>(), courseRemarksCarriedOver(emptyList(), emptyList()))
        assertEquals(listOf(old), courseRemarksCarriedOver(listOf(old), listOf(old)))
        // 老表里没有任何备注 → 原样返回（不产生多余 copy）
        val plain = course(remark = "")
        assertEquals(listOf(plain), courseRemarksCarriedOver(listOf(plain), listOf(plain)))
    }
}
