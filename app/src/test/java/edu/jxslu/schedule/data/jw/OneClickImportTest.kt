package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.domain.mergeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一键导入的合成口径（DESIGN §4.4）。
 *
 * 这一层要回答的核心问题只有一个：两张课表连着抽，**哪一次是"这张表没课"、哪一次是
 * "拿到的不是这张表"**。实验课表在前期学期本来就空，判错就会把用户挡在导入之外；
 * 反过来把结构变化当成空课表，会静默地只导一半数据。
 */
class OneClickImportTest {

    // ---- 页面形态：识别成功与否 -------------------------------------------------

    @Test
    fun theorySourceRecognizesGridEvenWithoutCourses() {
        val source = OneClickImport.theorySource(theoryJson(items = "", cells = 41))
        assertEquals(OneClickImport.THEORY_LABEL, source.label)
        assertEquals(0, source.courses.size)
        assertEquals("2026-2027-1", source.term)
        assertTrue("扫到网格但本学期没课，属于识别成功", source.recognized)
    }

    @Test
    fun theorySourceWithoutGridIsNotRecognized() {
        assertFalse(OneClickImport.theorySource(theoryJson(items = "", cells = 0)).recognized)
        assertFalse(
            "抽取脚本自己报错（ok:false）时不可能识别成功",
            OneClickImport.theorySource("""{"ok":false,"error":"boom"}""").recognized,
        )
        assertFalse(
            "旧脚本不带 cells 字段：拿不到形态信息，宁可让弹窗多提示一句",
            OneClickImport.theorySource("""{"ok":true,"items":[]}""").recognized,
        )
    }

    @Test
    fun labSourceNeedsContainerNotJustEmptyItems() {
        assertTrue(OneClickImport.labSource(labJson(items = "", container = true)).recognized)
        assertFalse(OneClickImport.labSource(labJson(items = "", container = false)).recognized)
        assertFalse(OneClickImport.labSource("""{"ok":false,"error":"boom"}""").recognized)
    }

    @Test
    fun labSourceKeepsItsOwnTerm() {
        val source = OneClickImport.labSource(
            labJson(items = labItem("机电传动控制B", day = 6, week = 1, sections = "3-4"), term = "2025-2026-2"),
        )
        assertEquals("2025-2026-2", source.term)
        assertEquals(1, source.courses.size)
        assertEquals(CourseKind.Lab, source.courses[0].kind)
    }

    // ---- 合成：条数、学期、顺序 -------------------------------------------------

    @Test
    fun combineMergesBothSourcesWithTheoryFirst() {
        val theory = OneClickImport.theorySource(
            theoryJson(items = theoryItem("高等数学", day = 1, detail = "老师:张;时间:1-8周[1-2节];地点:A101"), cells = 41),
        )
        val lab = OneClickImport.labSource(
            labJson(items = labItem("机电传动控制B", day = 6, week = 1, sections = "3-4")),
        )

        val result = OneClickImport.combine(theory, lab)

        assertNull(result.blocked)
        assertEquals(2, result.courses.size)
        assertEquals("理论在前、实验在后", "高等数学", result.courses[0].name)
        assertEquals("机电传动控制B", result.courses[1].name)
        assertEquals("2026-2027-1", result.term)
        assertEquals(listOf("理论课表" to 1, "实验课表" to 1), result.breakdown)
        assertNull("两张表都认出来了，不该有警示", result.note)
    }

    /** 同名课在两张表里都要留：`mergeKey` 含 kind，理论课不该把实验课吞掉。 */
    @Test
    fun sameCourseInBothSourcesSurvivesAsTwoRows() {
        val detail = "老师:张;时间:1-8周[1-2节];地点:A101"
        val theory = OneClickImport.theorySource(
            theoryJson(items = theoryItem("机械制造基础A", day = 6, detail = detail), cells = 41),
        )
        val lab = OneClickImport.labSource(
            labJson(items = labItem("机械制造基础A", day = 6, week = 1, sections = "1-2")),
        )

        val result = OneClickImport.combine(theory, lab)

        assertEquals(2, result.courses.size)
        assertEquals(
            "mergeKey 必须让两条都活下来",
            2,
            result.courses.map { it.mergeKey() }.toSet().size,
        )
    }

    // ---- 合成：警示文案 ---------------------------------------------------------

    @Test
    fun emptyLabIsANoteNotABlock() {
        val theory = OneClickImport.theorySource(
            theoryJson(items = theoryItem("高等数学", day = 1), cells = 41),
        )
        val result = OneClickImport.combine(theory, OneClickImport.labSource(labJson(items = "")))

        assertNull("空实验课不能挡住理论课导入", result.blocked)
        assertEquals(1, result.courses.size)
        assertEquals(listOf("理论课表" to 1, "实验课表" to 0), result.breakdown)
        assertTrue("弹窗必须写明实验课 0 条", result.note!!.contains("暂无实验课安排"))
    }

    @Test
    fun missingLabContainerSaysSoOutLoud() {
        val theory = OneClickImport.theorySource(
            theoryJson(items = theoryItem("高等数学", day = 1), cells = 41),
        )
        val result = OneClickImport.combine(
            theory,
            OneClickImport.labSource(labJson(items = "", container = false)),
        )

        assertNull(result.blocked)
        assertTrue(result.note!!.contains("未识别到课表"))
    }

    @Test
    fun emptyTheoryWarnsButStillImportsLab() {
        val theory = OneClickImport.theorySource(theoryJson(items = "", cells = 41))
        val lab = OneClickImport.labSource(
            labJson(items = labItem("机电传动控制B", day = 6, week = 1, sections = "3-4")),
        )
        val result = OneClickImport.combine(theory, lab)

        assertNull(result.blocked)
        assertEquals(1, result.courses.size)
        assertTrue("理论课表为空要给显著警示", result.note!!.contains("理论课表 0 条"))
    }

    @Test
    fun termMismatchIsReported() {
        val theory = OneClickImport.theorySource(
            theoryJson(items = theoryItem("高等数学", day = 1), cells = 41, term = "2026-2027-1"),
        )
        val lab = OneClickImport.labSource(
            labJson(items = labItem("机电传动控制B", day = 6, week = 1, sections = "3-4"), term = "2025-2026-2"),
        )
        val result = OneClickImport.combine(theory, lab)

        assertTrue(result.note!!.contains("学期不一致"))
        assertEquals("展示口径以理论课表为准", "2026-2027-1", result.term)
    }

    @Test
    fun labTermFillsInWhenTheoryTermIsMissing() {
        val theory = OneClickImport.theorySource(
            theoryJson(items = theoryItem("高等数学", day = 1), cells = 41, term = ""),
        )
        val lab = OneClickImport.labSource(
            labJson(items = labItem("机电传动控制B", day = 6, week = 1, sections = "3-4"), term = "2025-2026-2"),
        )
        val result = OneClickImport.combine(theory, lab)

        assertNull("读不到学期不该自己去比，比分也就无从谈起", result.note)
        assertEquals("2025-2026-2", result.term)
    }

    @Test
    fun bothEmptyIsBlockedBeforeTheDialog() {
        val result = OneClickImport.combine(
            OneClickImport.theorySource(theoryJson(items = "", cells = 41)),
            OneClickImport.labSource(labJson(items = "", container = true)),
        )

        assertNotNull(result.blocked)
        assertTrue(result.courses.isEmpty())
        assertNull(result.note)
    }

    // ---- 形态读取本身 -----------------------------------------------------------

    @Test
    fun readExtractMetaToleratesMissingAndDirtyFields() {
        assertEquals(ExtractMeta(ok = true, cells = 41, container = true), readExtractMeta(
            """{"ok":true,"cells":41,"container":true}""",
        ))
        assertEquals(
            "字段缺失一律按 0/false，不抛异常",
            ExtractMeta(ok = true, cells = 0, container = false),
            readExtractMeta("""{"items":[]}"""),
        )
        assertEquals(
            "连 JSON 都不是时照样给个安全的默认值",
            ExtractMeta(ok = false, cells = 0, container = false),
            readExtractMeta("<html>不是 JSON</html>"),
        )
    }

    // ---- fixture ---------------------------------------------------------------

    private fun theoryItem(name: String, day: Int, detail: String = "老师:张;时间:1-8周[1-2节];地点:A101") =
        """{"name":"$name","detail":"$detail","day":$day}"""

    private fun labItem(name: String, day: Int, week: Int, sections: String) =
        """{"name":"$name","position":"工程训练中心207","day":$day,"week":$week,"sections":"$sections"}"""

    private fun theoryJson(items: String, cells: Int, term: String = "2026-2027-1") =
        """{"ok":true,"items":[$items],"term":"$term","cells":$cells}"""

    private fun labJson(items: String, container: Boolean = true, term: String = "2026-2027-1") =
        """{"ok":true,"items":[$items],"term":"$term","container":$container}"""
}
