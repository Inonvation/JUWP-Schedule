package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.TeachingCalendar
import edu.jxslu.schedule.domain.TeachingCalendarRules

/**
 * 教学日历解析（DESIGN §4.42）：`/jsxsd/jxzl/jxzl_query?xnxq01id=<学期>` 的服务端渲染
 * 整页 HTML，只取「第 1 周周一」。
 *
 * 页面结构与坑（2026-10-07 实测，快照在 `scripts/out/jxzl.html`）：
 * - 表 = 表头 + 第 1..25 周 + 页脚（`周历编制 | <学期>`）；周行 9 格：
 *   周次标签 + 周一..周五（**裸日号**，如 `31`）+ 周六/周日（`MM月DD日`）+ 备注；
 * - **全表没有年份**——年份从学期号推导（[TeachingCalendarRules.week1Monday]）；
 *   页面上唯一能自证学期的两处：`select#xnxq01id` 的选中项与页脚的学期串，
 *   两处都对不上请求学期就放弃（同 `ExamSync.termFromShell` 的纪律：不猜学期）；
 * - 数据行的属性是**单引号**（`<tr class='qz-weeklyTable-tr'>`，模板产物），
 *   表头行是双引号——正则只按 `<tr[^>]*>` 收窄，不假设引号形态。
 *
 * 自检链（任一不过返回 null，宁可不动开学日）：
 * 周一 = 周六 − 5 天 → 与周一/周二格的裸日号逐一对上 → 周六格确实是周六日期。
 */
object TeachingCalendarParser {

    fun parse(html: String, requestedTerm: String): TeachingCalendar? {
        if ("用户没有登录" in html || "请先登录系统" in html) return null
        if (!JwUrls.TERM_PATTERN.matches(requestedTerm)) return null
        // 响应学期必须自证：选中项优先、页脚兜底；都对不上（或都没有）不猜
        val pageTerm = selectedOptionTerm(html) ?: footerTerm(html) ?: return null
        if (pageTerm != requestedTerm) return null

        val row = ROW_RE.findAll(html)
            .map { it.value }
            .firstOrNull { "第1周" in it } ?: return null
        val cells = CELL_RE.findAll(row).map { clean(it.groupValues[1]) }.toList()
        // 周行至少：标签 + 7 天（备注格可有可无）
        if (cells.size < 8) return null

        val sat = DATE_RE.find(cells[6]) ?: return null
        val monday = TeachingCalendarRules.week1Monday(
            requestedTerm,
            sat.groupValues[1].toInt(),
            sat.groupValues[2].toInt(),
        ) ?: return null
        // 交叉校验：周一/周二格必须与推导出的日号一致（页面结构变了就不会齐）
        if (cells[1].toIntOrNull() != monday.dayOfMonth) return null
        if (cells[2].toIntOrNull() != monday.plusDays(1).dayOfMonth) return null
        return TeachingCalendar(startDate = monday, term = requestedTerm)
    }

    /**
     * `select#xnxq01id` 的选中项（`<option value="…" selected=…>`，value 优先、文本兜底）。
     * 按 id/name 收窄定位；选择器整块缺失时退化为全页扫 `<option selected>`（本页只有这一个
     * select，风险可控，等值校验在后面兜底）。抽不到返回 null。
     */
    private fun selectedOptionTerm(html: String): String? =
        JwHtml.selectedTerm(html, selectId = "xnxq01id", requireTermPattern = false)
            ?: JwHtml.selectedTerm(html, selectId = null, requireTermPattern = false)

    /** 页脚「周历编制 | <学期>」的学期串（选中项缺失时的兜底）。 */
    private fun footerTerm(html: String): String? =
        Regex("""周历编制[\s\S]{0,400}?(\d{4}-\d{4}-\d)""").find(html)?.groupValues?.get(1)

    private fun clean(raw: String): String =
        TAG_RE.replace(raw, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()

    private val ROW_RE = Regex("""<tr[^>]*>[\s\S]*?</tr>""")
    private val CELL_RE = Regex("""<td[^>]*>([\s\S]*?)</td>""")
    private val DATE_RE = Regex("""(\d{1,2})月(\d{1,2})日""")
    private val TAG_RE = Regex("""<[^>]+>""")
}
