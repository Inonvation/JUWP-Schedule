package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.Course

/**
 * 一键导入（DESIGN §4.4）：理论课表 + 实验课表两次抽取结果的合成口径。
 *
 * 为什么要有这一层：连着抽两张表，任何一张出问题都只能靠"条数"表现出来。实验课表在
 * 前期学期**本来就是空的**，理论课表却几乎不可能为空——把两者混在一起处理，要么
 * 空实验课挡住整次导入，要么页面结构变了却静默地只导一半。所以这里显式区分
 * 「这张表没课」与「拿到的不是这张表」，并把识别结果原样写进确认弹窗，
 * 让用户在写库之前看到。纯逻辑，JVM 可测。
 */
data class ImportSource(
    /** 展示名，直接进弹窗分项文案。 */
    val label: String,
    val courses: List<Course>,
    /** 页面学期下拉里的学期号；读不到为 null。 */
    val term: String?,
    /** 页面/解析层面识别成功；false = 大概率不是这张课表（结构变了，或根本没打开）。 */
    val recognized: Boolean,
)

/**
 * 一键导入的识别结果。
 *
 * [blocked] 非空 = 两张表都没拿到东西，调用方直接报错、不弹窗。
 * [breakdown] 含 0 条的那一项：「实验课表 0 条」本身就是用户要看到的识别结果。
 */
data class OneClickResult(
    val courses: List<Course>,
    val term: String?,
    val breakdown: List<Pair<String, Int>>,
    val note: String?,
    val blocked: String? = null,
)

object OneClickImport {
    const val THEORY_LABEL = "理论课表"
    const val LAB_LABEL = "实验课表"

    /** 理论课表抽取 JSON → 来源结果。识别成功看整屏网格 `td[name=kbDataTd]` 有没有扫到。 */
    fun theorySource(json: String): ImportSource {
        val meta = readExtractMeta(json)
        val courses = (QiangzhiScheduleParser.parseExtractJson(json) as? ImportParseResult.Success)
            ?.courses.orEmpty()
        return ImportSource(
            label = THEORY_LABEL,
            courses = courses,
            term = extractTermField(json),
            recognized = meta.ok && meta.cells > 0,
        )
    }

    /** 实验课表抽取 JSON → 来源结果。识别成功看课表 tbody 在不在。 */
    fun labSource(json: String): ImportSource {
        val meta = readExtractMeta(json)
        val courses = (SyjxScheduleParser.parseExtractJson(json) as? ImportParseResult.Success)
            ?.courses.orEmpty()
        return ImportSource(
            label = LAB_LABEL,
            courses = courses,
            term = extractTermField(json),
            recognized = meta.ok && meta.container,
        )
    }

    /**
     * 合成一批待导入的课程。
     *
     * 理论在前、实验在后，顺序只影响确认弹窗里的列表观感——落库时颜色按课程名重排
     * （`ScheduleRepository.withSortedNameColors`）。跨来源不去重：`mergeKey` 含 `kind`，
     * 同名同节次的理论课与实验课本来就该各占一行。
     */
    fun combine(theory: ImportSource, lab: ImportSource): OneClickResult {
        val courses = theory.courses + lab.courses
        val term = theory.term ?: lab.term
        val breakdown = listOf(
            theory.label to theory.courses.size,
            lab.label to lab.courses.size,
        )
        if (courses.isEmpty()) {
            return OneClickResult(
                courses = emptyList(),
                term = term,
                breakdown = breakdown,
                note = null,
                blocked = "两张课表都没解析到课程。请确认已登录教务、能打开学期理论课表后重试。",
            )
        }
        val notes = buildList {
            if (theory.courses.isEmpty()) {
                add(
                    if (theory.recognized) "理论课表 0 条：本学期没有理论课，或课次字段未解析出来。"
                    else "理论课表页未识别到课表网格（页面结构可能已变化）。",
                )
            }
            if (lab.courses.isEmpty()) {
                add(
                    if (lab.recognized) "实验课表 0 条：本学期暂无实验课安排。"
                    else "实验课表页未识别到课表，本次只导入理论课。",
                )
            }
            if (theory.term != null && lab.term != null && theory.term != lab.term) {
                add("两页学期不一致（理论 ${theory.term} / 实验 ${lab.term}），导入的是两批不同学期的数据。")
            }
        }
        return OneClickResult(
            courses = courses,
            term = term,
            breakdown = breakdown,
            note = notes.takeIf { it.isNotEmpty() }?.joinToString(" "),
        )
    }
}
