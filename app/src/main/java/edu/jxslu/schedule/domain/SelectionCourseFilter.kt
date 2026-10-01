package edu.jxslu.schedule.domain

/** 课程列表的筛选条件（DESIGN §3.22，纯数据，UI 只负责收集）。 */
data class SelectionFilter(
    /** 关键词：课程名 / 教师 / 时间地点 任一包含（去空白、忽略大小写）；空 = 不筛。 */
    val query: String = "",
    /** 只看还有余量的（余量未知的按「不排除」处理——不猜）。 */
    val onlyAvailable: Boolean = false,
    /** 只看我已选中的。 */
    val onlySelected: Boolean = false,
    /** 课程属性（必修 / 任选…）；null = 全部。 */
    val attribute: String? = null,
)

/** 课程列表的排序口径。 */
enum class SelectionSort {
    /** 教务列表原顺序（不重排）。 */
    Default,

    /** 余量多的在前；余量未知的排最后。 */
    Remaining,

    /** 课程名。 */
    Name,

    /** 教师名；无教师的排最后。 */
    Teacher,
}

/**
 * 课程列表的筛选与排序（DESIGN §3.22，纯 JVM 可测）。
 *
 * 排空/空字符串一律当「不筛」；排序是稳定排序，同键保持教务原顺序
 * （`sortedBy` 系列是稳定排序，别改成 `sortedWith(compareBy...)` 之外的比较器）。
 */
object SelectionCourses {

    fun filter(courses: List<SelectionCourse>, filter: SelectionFilter): List<SelectionCourse> {
        val query = filter.query.trim()
        return courses.filter { course ->
            if (query.isNotEmpty() && !course.matchesQuery(query)) return@filter false
            if (filter.onlyAvailable) {
                // remaining 是计算属性（无 backing field），取出来再判，别指望 smart cast
                val remaining = course.remaining
                if (remaining != null && remaining <= 0) return@filter false
            }
            if (filter.onlySelected && !course.selected) return@filter false
            val attr = filter.attribute
            if (attr != null && attr.isNotEmpty() && course.attribute != attr) return@filter false
            true
        }
    }

    private fun SelectionCourse.matchesQuery(query: String): Boolean =
        name.contains(query, ignoreCase = true) ||
            teacher.contains(query, ignoreCase = true) ||
            timeText.contains(query, ignoreCase = true) ||
            placeText.contains(query, ignoreCase = true)

    fun sort(courses: List<SelectionCourse>, sort: SelectionSort): List<SelectionCourse> =
        when (sort) {
            SelectionSort.Default -> courses
            // 余量未知排最后：用「有空余」优先、再按余量降序——null 不能当 0 用
            SelectionSort.Remaining -> courses.sortedWith(
                compareByDescending<SelectionCourse> { it.remaining?.let { r -> r > 0 } == true }
                    .thenByDescending { it.remaining ?: Int.MIN_VALUE },
            )
            SelectionSort.Name -> courses.sortedBy { it.name }
            SelectionSort.Teacher -> courses.sortedBy { it.teacher.ifBlank { "\uFFFF" } }
        }

    /** 筛选项来源：列表里出现过的课程属性（保序去重，空串不列）。 */
    fun attributes(courses: List<SelectionCourse>): List<String> =
        courses.map { it.attribute.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 列表汇总（给状态条用）：总数 / 已选数 / 可选的（未选且未被证明已满）。 */
    data class Summary(
        val total: Int,
        val selected: Int,
        /** 未选、且没有「余量为 0」的条目；余量未知的也算可选（不猜「已满」，与筛选项同口径）。 */
        val selectable: Int,
    )

    fun summarize(courses: List<SelectionCourse>): Summary = Summary(
        total = courses.size,
        selected = courses.count { it.selected },
        selectable = courses.count { !it.selected && (it.remaining?.let { r -> r > 0 } ?: true) },
    )
}
