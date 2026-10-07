package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 实习基地分组规则（DESIGN §4.43）：组间顺序与组内排序是 UI 契约，逐条钉住。 */
class InternshipBaseRulesTest {

    private fun base(id: String, no: String, category: String) = InternshipBase(
        id = id,
        no = no,
        name = "基地$id",
        category = category,
        unit = "",
        leader = "",
        address = "",
        phone = "",
        builtDate = "",
        listedYears = "",
        capacity = "",
        campusContact = "",
        campusPhone = "",
        college = "",
        intro = "",
    )

    @Test
    fun `按类别分组且组间保序组内按编号`() {
        val list = listOf(
            base("1", "03", "其他"),
            base("2", "02", "校外基地"),
            base("3", "05", "校内基地"),
            base("4", "01", "校内基地"),
            base("5", "", "校外基地"),
            base("6", "09", ""),
        )
        val groups = InternshipBaseRules.byCategory(list)
        assertEquals(
            listOf("校内基地", "校外基地", "其他", "未标注"),
            groups.map { it.first },
        )
        assertEquals(listOf("01", "05"), groups[0].second.map { it.no })
        // 编号缺失的排在组内最后
        assertEquals(listOf("02", ""), groups[1].second.map { it.no })
    }

    @Test
    fun `空列表给空分组`() {
        assertEquals(
            emptyList<Pair<String, List<InternshipBase>>>(),
            InternshipBaseRules.byCategory(emptyList()),
        )
    }
}
