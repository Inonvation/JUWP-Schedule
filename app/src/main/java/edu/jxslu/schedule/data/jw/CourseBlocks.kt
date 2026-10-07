package edu.jxslu.schedule.data.jw

/**
 * 课表单元格里的「课块」切分（理论页与实验页**共用**这一份）。
 *
 * 强智把同一格、不同周次/地点的安排渲染成**多个** `li.courselists-item`
 * （实测同一格最多 3 个：单双周拆块、回课时换教室都会长成这样）。
 * 两个 WebView 注入脚本都用 `querySelectorAll('li.courselists-item')` 全取，
 * 所以 OkHttp 直取路径（[QiangzhiScheduleParser.parseFromHtml] /
 * [SyjxScheduleParser.parseFromHtml]）也必须逐块全取——只取第一个会静默丢课，
 * 而调课检测拿直取结果与基线比对，会把丢掉的课误报成「教务删除」，
 * 且**重新导入也修不好**（每次检查都重爬重解析）。
 *
 * 2026-10-07 实测：真实理论课表页 35 个数据格里有 9 个是多课块格，
 * 只取第一个时 34 个课块只解析出 24 个。
 */
internal object CourseBlocks {

    /**
     * 一段单元格 HTML → 全部课块。每块从 `<li` 开标签到（不含）下一个课块 /
     * 单元格 / 行 / 表体边界或串尾。
     *
     * 用前瞻收口而不是等 `</li>`：页面个别位置缺闭合标签时浏览器 DOM 会自动闭合，
     * 正则切分必须给出同样的结果（与注入脚本的 DOM 语义对齐）。
     */
    private val BLOCK = Regex(
        "(?is)<li\\b[^>]*courselists-item[\\s\\S]*?(?=<li\\b|</td|</tr|</tbody|\\z)",
    )

    fun inCell(cellHtml: String): List<String> = BLOCK.findAll(cellHtml).map { it.value }.toList()
}
