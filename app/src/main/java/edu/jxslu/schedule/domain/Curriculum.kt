package edu.jxslu.schedule.domain

/**
 * 培养方案「课程设置总表」（DESIGN §3.26 / §4.41）。
 *
 * 数据来自强智教务「培养方案」明细页（`/jsxsd/pyfa/topyfamx`，服务端渲染整页 HTML）——
 * 就是手机端 `jiaowuh5`「标准版」里那张「课程设置总表」的同源数据。全量 80+ 门课
 * 一次抓齐，每门带**开设学期 1–8**（建议修读学期 = 大一上…大四下）、计划学分与各类学时。
 *
 * 与学业完成情况（[ScholarProgress]）的关系：那份是**达成度**（要求多少 / 修了多少 /
 * 还差多少 / 哪几门没过），这份是**计划全量**（培养方案本身怎么排课）。两边课程编号
 * 对得上，但口径不同、不互相替代。
 *
 * 「开设学期」是**计划口径**：教务按培养方案建议的修读学期标注，不等于实际开课/选课
 * 安排（选修课可能有弹性、个别课隔年开）。
 */

/** 一条培养方案课程（「课程设置总表」的一行）。 */
data class CurriculumCourse(
    /** 开设学期 1–8；0 = 教务没标（页面变化时的兜底，UI 归入「未标注」）。 */
    val term: Int,
    /** 页面内的解析序号（全表递增），列表排序用。 */
    val sortOrder: Int,
    val courseNo: String,
    val name: String,
    /** 课程性质（通识必修课 / 学科基础课 / 专业课 / 集中实践教学环节…）。 */
    val kind: String,
    /** 课程属性（必修 / 限选 / 任选 / 公选）。 */
    val attr: String,
    /** 计划学分；解析不出为 null——宁可缺也不写一个 0 进去。 */
    val credit: Double? = null,
    /**
     * 理论 / 实践 / 讲座 / 实验 / 总学时。
     *
     * 教务原文是字符串：实践类课程会给「2(周)」这种值（实习、课程设计），
     * 不做数字解析；空串 = 教务没给这一格。
     */
    val hoursTheory: String = "",
    val hoursPractice: String = "",
    val hoursLecture: String = "",
    val hoursExperiment: String = "",
    val hoursTotal: String = "",
    /** 体系（通识限选课 / 通识必修课 / 学科基础课 / 专业课），页面的分组列。 */
    val tixi: String = "",
)

/** 一次抓取的完整结果。 */
data class Curriculum(
    /** 培养方案名，如「2022 xxx专业培养方案」。 */
    val planName: String,
    val courses: List<CurriculumCourse>,
)

/** 一个学年及其学期分组（[CurriculumRules.byYear] 的产物，UI 直接用）。 */
data class CurriculumYear(
    /** 大一 / 大二 / 大三 / 大四。 */
    val label: String,
    val terms: List<CurriculumTerm>,
    val courseCount: Int,
    val credits: Double,
)

/** 一个学期及其课程（[CurriculumRules.byYear] 的产物）。 */
data class CurriculumTerm(
    /** 开设学期 1–8。 */
    val term: Int,
    /** 中文标签，如「大一下」。 */
    val label: String,
    val courses: List<CurriculumCourse>,
)

object CurriculumRules {

    /** 开设学期上限（大一上…大四下 = 1–8）。 */
    const val TERM_MAX = 8

    private val YEAR_LABELS = listOf("大一", "大二", "大三", "大四")
    private val TERM_LABELS = mapOf(
        1 to "大一上", 2 to "大一下", 3 to "大二上", 4 to "大二下",
        5 to "大三上", 6 to "大三下", 7 to "大四上", 8 to "大四下",
    )

    /** 学年序号（0–3）→ 中文标签；越界给「其它」。 */
    fun yearLabel(yearIndex: Int): String = YEAR_LABELS.getOrElse(yearIndex) { "其它" }

    /** 开设学期 → 中文标签；0 或越界 =「未标注」。 */
    fun termLabel(term: Int): String = TERM_LABELS[term] ?: "未标注"

    /** 全表学分合计（跳过解析不出的 null，口径同 [ScholarProgressRules.totals]）。 */
    fun totalCredit(courses: List<CurriculumCourse>): Double =
        courses.sumOf { it.credit ?: 0.0 }

    /**
     * 按学年 → 学期分组（UI 的「大一 / 大二 / …」视图）。
     *
     * 只保留有课的学年与学期（空档不铺空壳——培养方案从大一上开始、课不连续时
     * 空白学期本身没有信息量）；term=0 的「未标注」行不进学年分组，UI 单独兜底。
     */
    fun byYear(courses: List<CurriculumCourse>): List<CurriculumYear> =
        (0 until YEAR_LABELS.size).mapNotNull { yi ->
            val t1 = yi * 2 + 1
            val t2 = yi * 2 + 2
            val sel = courses.filter { it.term == t1 || it.term == t2 }
            if (sel.isEmpty()) return@mapNotNull null
            CurriculumYear(
                label = YEAR_LABELS[yi],
                terms = listOf(t1, t2).mapNotNull { t ->
                    val rows = sel.filter { it.term == t }
                    if (rows.isEmpty()) null
                    else CurriculumTerm(term = t, label = termLabel(t), courses = rows)
                },
                courseCount = sel.size,
                credits = totalCredit(sel),
            )
        }

    /**
     * 写库前的完整性校验。返回 null = 通过，否则是给用户看的原因。
     *
     * 存在的意义与 [ScholarProgressRules.validate] 相同——**挡住半张表**：教务改版时
     * 正则可能只匹配到一小部分行，那时如果照常「先删后插」，用户库里的 80+ 门课就被
     * 换成了几条垃圾。宁可这次不更新，也不能拿解析残值覆盖好数据。
     */
    fun validate(curriculum: Curriculum): String? {
        if (curriculum.courses.isEmpty()) return "没解析到任何课程"
        if (curriculum.courses.none { it.term in 1..TERM_MAX }) {
            return "课程都没有开设学期，页面结构可能已变"
        }
        return null
    }
}
