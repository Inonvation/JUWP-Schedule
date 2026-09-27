package edu.jxslu.schedule.domain

/**
 * 学业完成情况（DESIGN §4.29）。
 *
 * 数据来自强智教务「学籍成绩 → 学业达成情况 → 学业完成情况」
 * （`/jsxsd/xxwcqk/xxwcqk_idxOntx.do`），页面按四个维度各给一张表：
 * 课程体系 / 课程性质 / 课程属性 / 公选课类别。四张表的行结构一致，都按
 * 「分组 → 课程明细」两级展开，所以领域模型不区分维度，用 [ScholarDimension.id] 打标即可。
 *
 * 与成绩（[ScoreRecord]）的关系：成绩只记「已经出了分」的课，这里是**培养方案的达成度**——
 * 要求多少学分、修了多少、还差多少、哪几门没过、哪几门还没修。两边课程编号对得上，
 * 但口径不同、不互相替代。
 */

/** 四个展示维度。id 是落库口径，改它等于迁移，别动。 */
enum class ScholarDimension(val id: String, val label: String) {
    /** 课程体系：通识必修课 / 通识限选课 / 专业课 / 学科基础课…（唯一带 5 列总表的维度）。 */
    System("system", "课程体系"),

    /** 课程性质：通识必修课 / 学科基础课 / 专业核心课…（分组最细，16 组左右）。 */
    Nature("nature", "课程性质"),

    /** 课程属性：必修 / 限选 / 任选 / 公选 / 其它。 */
    Attribute("attribute", "课程属性"),

    /** 公选课类别：工程技术 / 创新创业类 / 自然科学…（只有公选课，行数最少）。 */
    Elective("elective", "公选课类别"),
    ;

    companion object {
        fun fromId(id: String): ScholarDimension? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 一个分组块的头。
 *
 * 注意**顶层还有一张总账表**（`div.total-list`，kctx 是 5 列、其余维度 3 列），
 * 这里不存它的行：四个维度的总账列数不同，存下来 UI 得按维度分支处理；
 * 而它的数值就是分组块之和（实测 kctx：58.5+4.5+46+52 = 161.0 = 教务合计行），
 * 由 UI 现算反而四维度口径统一。代价是「计划外非通选课」这类没有学分要求的分组
 * 要跳过（它本来也不进合计）。
 *
 * [requiredCredit] 等字段可空：教务有些分组不给「学分要求」（课程性质维度里
 * 一堆 0.0 的分组、公选课类别维度全是 0.0）。
 */
data class ScholarGroup(
    val dimension: String,
    val name: String,
    val sortOrder: Int,
    val requiredCredit: Double? = null,
    val earnedCredit: Double? = null,
    /**
     * 正修读学分。**实测四个维度的分组块都不给这个数**（页面上只有「已修」「还需」
     * 两个 `b.study-number`），所以解析结果恒为 null，汇总卡的「在修」改由
     * [ScholarProgressRules.totals] 从明细现算。留着这个字段是防教务以后把它加回来——
     * 真加回来了，解析器已经在填，UI 换回来就行。
     */
    val ongoingCredit: Double? = null,
    val remainingCredit: Double? = null,
    /** 结论：true = 通过（`bg-green`），false = 未通过（`bg-red`），null = 该块没有结论行。 */
    val passed: Boolean? = null,
    /** 进度条原文（「98.3%」「∞%」或空串）。不解析成数字——教务给什么显示什么。 */
    val percent: String = "",
)

/** 一条培养方案课程明细（11 列）。 */
data class ScholarCourse(
    val dimension: String,
    /** 所属分组名（= 同一维度那个 [ScholarGroup.name]）。 */
    val groupName: String,
    val sortOrder: Int,
    /** 修读学期，如 `2025-2026-2`；未修的课教务可能给空串。 */
    val term: String = "",
    val courseNo: String = "",
    val name: String,
    val credit: Double = 0.0,
    /** 计划内 = true，计划外 = false，教务没标 = null。 */
    val planned: Boolean? = null,
    /** 课程类别（教务这里是代码或类别名，如「1」「理论课（含实践）」）。 */
    val category: String = "",
    /** 课程属性：必修 / 限选 / 任选 / 公选。 */
    val attribute: String = "",
    /** 课程性质：通识必修课 / 学科基础课… */
    val nature: String = "",
    /** 修读情况，已归一化（见 [ScholarCourseStatus]）。 */
    val status: String = ScholarCourseStatus.Pending.label,
    val scoreText: String = "",
    val remark: String = "",
    /** 是否学位课；教务可能不给这一列（空串）→ null。 */
    val degreeCourse: Boolean? = null,
)

/** 一次导入的完整结果。 */
data class ScholarProgress(
    /** 培养方案名，如「2024 xxx专业培养方案及教学计划」。 */
    val planName: String,
    val groups: List<ScholarGroup>,
    val courses: List<ScholarCourse>,
)

/**
 * 修读情况。
 *
 * 教务在「课程体系」维度写「已修读 / 修读中 / 未修读」，在「课程属性」维度写
 * 「已修读 / 修读中 / 待修读」——同一个意思两种写法，落库前必须归一，
 * 否则 UI 上同一个状态会渲染出两种标签。
 */
enum class ScholarCourseStatus(val label: String) {
    Earned("已修读"),
    Ongoing("修读中"),
    Pending("未修读"),
    ;

    companion object {
        fun fromRaw(raw: String): ScholarCourseStatus {
            val t = raw.trim()
            return when {
                t.contains("已修") || t.contains("通过") -> Earned
                t.contains("修读中") || t.contains("在修") || t.contains("正在") -> Ongoing
                else -> Pending
            }
        }
    }
}

/** 学分单元格的解析结果：数值 + 是否计划内（教务没标时 [planned] 为 null）。 */
data class CreditCell(val value: Double, val planned: Boolean?)

/** 一个维度的学分总账（UI 汇总卡）。四项都可能是 0（教务该维度不给这类数字）。 */
data class ScholarTotals(
    val required: Double = 0.0,
    val earned: Double = 0.0,
    val ongoing: Double = 0.0,
    val remaining: Double = 0.0,
)

object ScholarProgressRules {

    /**
     * 解析学分单元格，形如 `0.5 （计划内）` / `2` / `0.25（计划外）`。
     *
     * 解析不出数字返回 null——宁可让调用方拒绝这一行，也不要写一个 0 学分进去。
     * 全角括号与半角括号都认（教务两种都出现过）。
     */
    fun parseCredit(raw: String): CreditCell? {
        val text = raw.replace("\u00a0", " ").trim()
        if (text.isEmpty()) return null
        val m = CREDIT_RE.find(text) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val planned = when {
            text.contains("计划内") -> true
            text.contains("计划外") -> false
            else -> null
        }
        return CreditCell(value, planned)
    }

    /**
     * 「是否学位课」列：`是` / `否` / 空。
     * 空串不能当成「否」——教务有些维度根本不输出这一列，null 表示「不知道」。
     */
    fun parseDegreeCourse(raw: String): Boolean? = when (raw.trim()) {
        "是" -> true
        "否" -> false
        else -> null
    }

    /** 解析进度条原文里的百分比数字；「∞%」与空串返回 null（UI 不画进度条）。 */
    fun parsePercent(raw: String): Int? =
        PERCENT_RE.find(raw)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100)

    /**
     * 写库前的完整性校验。返回 null = 通过，否则是给用户看的原因。
     *
     * 存在的意义是**挡住半张表**：教务改版时正则可能只匹配到一小部分节点，
     * 那时如果照常「先删后插」，用户原来那 83 门课的记录就被换成了几条垃圾。
     * 宁可这次不更新，也不能拿解析残值覆盖好数据。
     *
     * 这里只放**四个维度都成立**的判据。「要求学分合计大于 0」看着像通用规则，
     * 实际不是——公选课类别维度所有分组的学分要求都是 0.0（教务口径：公选课按类别
     * 记门数不记学分），拿它当通用闸门会把整个维度永久判失败。那条检查单独放在
     * [validateCreditTotal]，只对课程体系维度用。
     */
    fun validate(progress: ScholarProgress): String? {
        if (progress.groups.isEmpty()) return "没解析到任何分组"
        if (progress.courses.isEmpty()) return "没解析到任何课程"
        // 分组名与明细行的 groupName 必须能对上；全对不上说明两块是分开匹配来的残值
        val groupsWithCourses = progress.courses.map { it.groupName }.toSet()
        if (progress.groups.none { it.name in groupsWithCourses }) {
            return "分组与课程对不上，页面结构可能已变"
        }
        return null
    }

    /** 课程体系维度专有的附加检查：那个维度的分组一定带学分要求（合计 161.0 这类）。 */
    fun validateCreditTotal(progress: ScholarProgress): String? {
        val total = progress.groups
            .mapNotNull { it.requiredCredit }
            .sum()
        return if (total <= 0.0) "分组的要求学分合计为 0，页面结构可能已变" else null
    }

    /**
     * 该维度的学分总账（UI 汇总卡用）：要求 / 已修 / 在修 / 还需。
     *
     * 逐项跳过 null 再求和：教务有一部分分组不给这些数字（公选课类别全是 0.0，
     * 「计划外非通选课」干脆没有学分要求），把它们当 0 处理是对的。
     *
     * **「在修」不能从分组头取**：教务的分组块只给「已修 / 还需」两个数（实测四个维度
     * 都只有两个 `b.study-number`），「正修读学分」只出现在**顶层汇总表**里，而那张表
     * 四个维度的列集合还不一致、没入库。所以这里从明细现算：`修读中` 课程的学分之和。
     * 不这么算的话，汇总卡那一格会永远是 0（与教务的 0.25 对不上）。
     * 按课程编号去重，防同一门课在同一维度下被列进两个分组时重复计数。
     */
    fun totals(
        groups: List<ScholarGroup>,
        courses: List<ScholarCourse> = emptyList(),
    ): ScholarTotals = ScholarTotals(
        required = groups.sumOf { it.requiredCredit ?: 0.0 },
        earned = groups.sumOf { it.earnedCredit ?: 0.0 },
        ongoing = courses
            .filter { it.status == ScholarCourseStatus.Ongoing.label }
            .distinctBy { it.courseNo.ifBlank { it.name } }
            .sumOf { it.credit },
        remaining = groups.sumOf { it.remainingCredit ?: 0.0 },
    )

    /** 搜索匹配：课程名或课程编号，忽略大小写（英文课名与编号混排时用得上）。 */
    fun matchesQuery(course: ScholarCourse, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return course.name.contains(q, ignoreCase = true) ||
            course.courseNo.contains(q, ignoreCase = true)
    }

    /**
     * 按关键字过滤分组 → 课程（UI 的搜索框）。
     *
     * 空关键字**原样返回**（含没课的分组）：搜索关掉时列表要恢复成教务给的全貌，
     * 顺手把空分组滤掉会让「课程性质」维度少几行、看起来像数据丢了。
     * 有关键字时反过来：空分组要滤掉，否则搜出来的是一片空分组。
     */
    fun filterForSearch(
        coursesByGroup: Map<String, List<ScholarCourse>>,
        query: String,
    ): Map<String, List<ScholarCourse>> {
        if (query.isBlank()) return coursesByGroup
        return coursesByGroup
            .mapValues { (_, list) -> list.filter { matchesQuery(it, query) } }
            .filterValues { it.isNotEmpty() }
    }

    private val CREDIT_RE = Regex("""(-?\d+(?:\.\d+)?)""")
    /** 带小数点的百分比也要认：教务给过「17200.0%」这种值。 */
    private val PERCENT_RE = Regex("""(\d+(?:\.\d+)?)\s*%""")
}
