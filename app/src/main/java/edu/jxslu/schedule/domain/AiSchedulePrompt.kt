package edu.jxslu.schedule.domain

import java.time.LocalDate

/**
 * AI 调课的 prompt 组装与脱敏（DESIGN §4.39 / §4.40，2026-10-06）。
 *
 * 纯逻辑、无 Android 依赖，JVM 可测。课表以**1 起始的编号清单**交给模型，
 * 模型只需回引编号（见 [AiScheduleTool]），**不要求它回抄课程字段**——
 * 回抄会漂移，`ScheduleDiff.fingerprint` 匹配不上就被静默跳过。
 *
 * **必须给学期日历**：课表是「周次 × 星期」两维的，而学校的放假 / 调休通知是
 * **按具体日期**发的。不给日期对照，模型没有任何办法把「9月20日(星期日)按照
 * 9月28日(第五周周一)的教学安排进行」换算成周次与星期，只能回一句「没有需要改动
 * 的课程」——这是 2026-10-06 用户实测踩到的坑，不是模型能力问题。
 *
 * 传给模型的 [courses] 必须是**非考试**条目（调用方先滤 `CourseKind.Exam`），
 * 编号与 [AiScheduleTool.parse] 的 `existingIndex` 索引同源。
 */
object AiSchedulePrompt {

    /** 发给模型的 system 提示：角色 + 两类改动的分野 + 纪律。 */
    val SYSTEM: String = """
        你是「水贝贝」课表 App 的调课助手。用户会给你一条调课通知、他当前的课表清单（带编号），
        以及学期日历（周次 ↔ 日期对照）。把通知里的安排翻译成结构化改动，调用 propose_schedule_changes 返回。

        有**两类**改动，别混：

        A. 课程本身的改动（changes）——某门课的时间 / 地点 / 周次变了：
           - 时间 / 地点 / 周次变化 → kind=modify，只填变化的字段；
           - 只调**某一周**的课（通知写「原上课周次 8 周 → 新上课周次 9 周」这类）时，
             **不要**把这门课的周次整体改成新周次：modify 原条目、weeks 去掉被调走的那一周，
             再 add 一条只含新周次的新时段；
           - 新增一门清单里没有的课 → kind=add；
           - 取消某门课 → kind=remove。
           只能引用清单里已有的课程编号（existingIndex），不要臆造课程。

        B. 日期覆盖（overrides）——按**具体日期**发布的安排。这类安排用 changes 表达不了，必须走 overrides：
           - 「某天放假 / 停课 / 教学活动暂停」→ kind=suspended，填 fromDate；连续区间再填 toDate。
           - 「某日按照另一日的教学安排进行」（调休补课）→ kind=follow，fromDate=那个日期，
             sourceWeek / sourceWeekday = **被照搬的那天**属于第几周、星期几。
             例：「9月20日(星期日)按照9月28日(第五周周一)的教学安排进行」
             → {kind:"follow", fromDate:"2026-09-20", sourceWeek:5, sourceWeekday:1}。
             放假让那天本来不上课时，**同时**给被照搬的那天加一条 suspended（两边都要给）。

        规则：
        1. 通知里提到的课程不在清单里时，changes 返回空数组，并在 message 里说明。
        2. 日期一律换算成 yyyy-MM-dd；周次 / 星期用上面给的日历推算，不要凭空猜。
        3. 拿不准就不要改；宁可返回空数组并在 message 里说明原因。
        4. 星期是 1=周一 … 7=周日；节次是 1..11；周次是 1 起始的整数数组。
        5. message 用简体中文，一句话说清你做了什么。

        已生效的安排（消息里的【已生效的日期安排】）：
        - 通知里的某条安排若已经在那里、内容相同，**不要再给出**——那是重复，不是新改动；
        - 同一天已有旧安排、通知给出不同安排时，按**通知**给出新安排（应用后以新的为准）；
        - 判断「是不是同一件事」看日期与内容，不看措辞。

        风格（重要）：
        - message 直接说结论，不超过 30 字。**不要**写「好的」「我来帮你分析」这类开场白，
          也不要写总结、客套或免责说明。

        用户发了图片时（截图里的调课通知）：
        - 先把图里读到的通知要点逐条填进 readback（一行一条），供用户核对识别是否准确；
        - 再按上面两类改动处理；
        - **看不清或读不准的部分不要猜**，在 message 里说明哪一条没读清、需要用户补文字。
        - 没发图片时 readback 留空字符串。
    """.trimIndent()

    /**
     * 课表编号清单（1 起始）。[maskTeacher] 为 true 时教师名替换为「教师N」。
     * 空课表给一句占位，避免模型面对空字符串瞎猜。
     */
    fun listing(courses: List<Course>, maskTeacher: Boolean): String {
        if (courses.isEmpty()) return "（当前课表为空）"
        val mask = if (maskTeacher) maskedTeachers(courses) else emptyMap()
        return courses.mapIndexed { index, course ->
            val teacher = mask[course.teacher] ?: course.teacher
            buildString {
                append(index + 1).append(". ").append(course.name)
                if (teacher.isNotBlank()) append(" | ").append(teacher)
                if (course.position.isNotBlank()) append(" | ").append(course.position)
                append(" | ").append(weekdayLabel(course.day)).append(" ").append(sectionLabel(course))
                append(" | ").append(weeksLabel(course.weeks))
                append(" | ").append(course.kind.label)
            }
        }.joinToString("\n")
    }

    /**
     * 学期日历：今天 + 每一周的起止日期。
     * **这是模型能把日期换算成周次的唯一依据**，缺了它调休通知就没法落地。
     */
    fun calendar(semester: SemesterConfig?, today: LocalDate): String {
        if (semester == null) return "（学期未配置，无法给出日期对照）"
        val start = runCatching { ScheduleCalculator.parseDate(semester.startDate) }.getOrNull()
            ?: return "（学期起始日不合法，无法给出日期对照）"
        val monday = ScheduleCalculator.startOfWeek(start)
        val week = runCatching { ScheduleCalculator.weekNumberOf(semester, today) }.getOrNull() ?: 0
        return buildString {
            append("今天是 ").append(today).append("（")
            if (week in 1..semester.totalWeeks) {
                append("第").append(week).append("周 ").append(weekdayLabel(today.dayOfWeek.value))
            } else {
                append("不在学期内")
            }
            append("）\n")
            for (w in 1..semester.totalWeeks) {
                val from = monday.plusWeeks((w - 1).toLong())
                val to = from.plusDays(6)
                append("第").append(w).append("周：").append(from).append(" ~ ").append(to).append("\n")
            }
        }.trim()
    }

    /**
     * 用户消息：课表清单 + 学期日历 + 已生效的日期安排 + 通知原文。
     * **只放事实**——每一段都是模型做判断必需的原始数据，不收用户的自由诉求
     * （v2 起没有追问/微调输入；判断依据只有通知本身）。
     */
    fun userMessage(
        notice: String,
        courses: List<Course>,
        maskTeacher: Boolean,
        semester: SemesterConfig? = null,
        today: LocalDate = LocalDate.now(),
        existingOverrides: List<ScheduleOverride> = emptyList(),
    ): String = buildString {
        append("【我的课表】\n").append(listing(courses, maskTeacher))
        append("\n\n【学期日历】\n").append(calendar(semester, today))
        append("\n\n【已生效的日期安排】\n").append(overridesBrief(existingOverrides))
        append("\n\n【调课通知】\n").append(notice.trim())
    }

    /**
     * 已生效的日期安排（`date_overrides`），喂给模型的人话清单。
     *
     * **这是「同一个通知再发一遍仍然报差异」的根治**（2026-10-07）：课表清单只含课程行，
     * 已应用的调休 / 停课对模型完全不可见——它只能把通知原样重新解析，又把同一组
     * 覆盖报成待确认。给了这段，模型才能判断「这条安排已经生效了」。
     *
     * 没有时写「无」——段落本身必须在，模型才知道是「没有已生效安排」而不是「没给这信息」。
     */
    fun overridesBrief(overrides: List<ScheduleOverride>): String {
        if (overrides.isEmpty()) return "无"
        return overrides.joinToString("\n") { o ->
            when (o.kind) {
                OverrideKind.SUSPENDED ->
                    if (o.fromDate == o.toDate) {
                        "${o.fromDate} 放假（无课）"
                    } else {
                        "${o.fromDate} 至 ${o.toDate} 放假（无课）"
                    }

                OverrideKind.FOLLOW -> {
                    val sourceStr = if (o.sourceWeek != null && o.sourceWeekday != null) {
                        "第${o.sourceWeek}周${weekdayLabel(o.sourceWeekday)}"
                    } else {
                        "指定安排"
                    }
                    "${o.fromDate} 按 $sourceStr 的课表上课"
                }
            }
        }
    }

    /**
     * 教师名脱敏：按**姓名排序**给不同教师分配「教师1 / 教师2 …」。
     * 排序保证同一份课表跨调用得到稳定映射；同名同号；空教师名不参与（原样留空）。
     */
    fun maskedTeachers(courses: List<Course>): Map<String, String> =
        courses.map { it.teacher }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .mapIndexed { index, name -> name to "教师${index + 1}" }
            .toMap()

    fun weekdayLabel(day: Int): String = when (day) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        7 -> "周日"
        else -> "周$day"
    }

    fun sectionLabel(course: Course): String =
        if (course.startSection == course.endSection) {
            "第${course.startSection}节"
        } else {
            "第${course.startSection}-${course.endSection}节"
        }

    /** 周次压缩：连续段写「a-b」，零散段用逗号。 */
    fun weeksLabel(weeks: Set<Int>): String {
        if (weeks.isEmpty()) return "周次未定"
        val sorted = weeks.sorted()
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var prev = start
        for (week in sorted.drop(1)) {
            if (week == prev + 1) {
                prev = week
                continue
            }
            parts += if (start == prev) "$start" else "$start-$prev"
            start = week
            prev = week
        }
        parts += if (start == prev) "$start" else "$start-$prev"
        return "第" + parts.joinToString(",") + "周"
    }
}
