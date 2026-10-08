package edu.jxslu.schedule.domain

/**
 * 选课中心列表的服务端查询条件（DESIGN §4.36，2026-10-08 窗口期实测）。
 *
 * 字段与教务列表页 `queryKxkcList` 的 URL 参数一一对应（映射在
 * `SelectionCenterParser.listQuery`）；三个开关是**服务端**语义（同为 AND 叠加）：
 *
 * - [hideConflict]（教务 `sfct`）：实测**连「该课堂已选」的行也会一起隐藏**（48→15）——
 *   教务页面默认勾选，但 App **默认必须关**：否则已选课程从列表消失、退课入口断链；
 * - [hideFull]（教务 `sfym`）：隐藏满员课程（实测 48→46）；
 * - [hideRestricted]（教务 `sfxx`）：过滤限选条件未达标的课程（教务默认勾选，
 *   本数据无差异样本，保持 App 既有的 true 口径）。
 *
 * **空条件不是「查全部」**：教务要求 关键词 / 教师 / 星期 至少一个非空（全空回 0 行，
 * 页面原话「选课课程默认不加载」）——三者为空时客户端会换成浏览专用的
 * `SelectionCenterParser.BROWSE_KEYWORD`（`data/jw/SelectionCenterParser.kt`）。
 */
data class SelectionQuery(
    /** 课程编号 / 名称关键词（教务 `kch`，服务端做子串匹配）。 */
    val keyword: String = "",
    /** 上课教师（教务 `jg0101id`，传姓名文本即可，实测）。 */
    val teacher: String = "",
    /** 星期筛选（教务 `skxq`，1-7；null = 不限）。可单独成条件。 */
    val weekday: Int? = null,
    /** 节次起（教务 `skjc`，1-11；必须与星期组合，见 [validationError]）。 */
    val startSection: Int? = null,
    /** 节次止（教务 `endJc`，1-11）。 */
    val endSection: Int? = null,
    /** 课程类别代码（教务 `kctype`，01-09，见 [SelectionCourseTypes]；空 = 全部）。 */
    val courseType: String = "",
    val hideFull: Boolean = false,
    val hideConflict: Boolean = false,
    val hideRestricted: Boolean = true,
) {
    /** 表单校验（文案对齐教务页面 `queryKxkcList` 的三条提示原话）；null = 可以通过。 */
    fun validationError(): String? = when {
        startSection != null && weekday == null -> "选课「节次」查询时，必须选择「星期」组合一起查询"
        (startSection == null) != (endSection == null) -> "开始节次和结束节次必须组合一起查询"
        startSection != null && endSection != null && startSection > endSection -> "开始节次必须小于结束节次"
        else -> null
    }
}

/**
 * 教务列表页「课程类别」下拉的固定选项（代码 ↔ 名称，2026-10-08 页面快照原样）。
 *
 * 注意两个口径别混：**查询参数**传代码（`kctype=01`…），**行内展示**用教务返回的
 * `kctype` 字段——它给的就是中文名（如「理论课（含实践）」），不需要客户端再映射。
 */
object SelectionCourseTypes {

    val OPTIONS: List<Pair<String, String>> = listOf(
        "01" to "理论课（不含实践）",
        "02" to "理论课（含实践）",
        "03" to "实验课",
        "04" to "集中性实践环节",
        "09" to "其它",
    )

    /** 代码 → 名称；未知代码原样回显（不编）。 */
    fun label(code: String): String = OPTIONS.firstOrNull { it.first == code }?.second ?: code
}
