package edu.jxslu.schedule.domain

/**
 * 选课中心里的一门候选课程（DESIGN §4.36）。
 *
 * 字段按 **2026-10-08 窗口期实测**（「本学期计划选课」列表 `xsxkBxqjhxk`）落地：
 * [id] 仍是「不透明主键」——由 `jx0404id|jx02id|cfbs` 拼成（[SelectionCourse] 不感知分段，
 * 拆分在 `SelectionCenterParser.splitCourseKey`）；容量三列教务都直接给
 * （`xkrs` 选课人数 / `pkrs` 配额 / `syrs` 剩余容量），`syrs` 是权威余量（与 `pkrs-xkrs` 不恒等）。
 *
 * 2026-10-08 v3 起本模型独立成文件（原与已下线的抢课策略同文件）。
 */
data class SelectionCourse(
    /** 提交选课用的主键（教务原始 id，不解析、只透传）。 */
    val id: String,
    val name: String,
    val teacher: String = "",
    /** 上课时间原文（多行以 `\n` 分隔）。 */
    val timeText: String = "",
    /** 上课地点原文。 */
    val placeText: String = "",
    val credit: Double? = null,
    /** 课程属性：必修 / 任选… */
    val attribute: String = "",
    /** 课程性质：通识必修课 / 专业任选课… */
    val category: String = "",
    /** 容量（总名额，教务 `pkrs`）；null = 教务未给。 */
    val capacity: Int? = null,
    /** 已选人数（教务 `xkrs`）；null = 教务未给。 */
    val enrolled: Int? = null,
    /** 我是否已选中这门课（教务状态列 `xkzt == "9"`）。 */
    val selected: Boolean = false,
    /** 课程编号（教务 `kch`）。 */
    val courseNo: String = "",
    /** 剩余容量（教务直接给的 `syrs`，权威值）。 */
    val remainingSeats: Int? = null,
    /** 时间冲突 / 已选说明（教务 `ctsm`，可能为空；如「与已选课程 …冲突」「该课堂已选」）。 */
    val conflictNote: String = "",
    /** 课程类别显示名（教务行内 `kctype` 给的就是中文，如「理论课（含实践）」）。 */
    val courseType: String = "",
    /** 分组名（教务 `fzmc`，可空）。 */
    val groupName: String = "",
    /** 上课校区（教务 `xqmc`，如「瑶湖校区」）。 */
    val campus: String = "",
    /** 授课方式（教务 `skfsmc`，如「其它」）。 */
    val teachMode: String = "",
) {
    /** 余量：优先教务直接给的 `syrs`；没有才由容量差推。任一端为空就是 null（不猜）。 */
    val remaining: Int? get() = remainingSeats ?: if (capacity != null && enrolled != null) capacity - enrolled else null
}
