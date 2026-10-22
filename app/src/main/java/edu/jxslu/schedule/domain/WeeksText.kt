package edu.jxslu.schedule.domain

/**
 * 周次文本的解析与压缩（教务原文 / 课程编辑 / AI 提示共用一套口径）。
 *
 * 支持 `1-16`、`1,3,5`、`1-16单周`、`1-16双周`、全角逗号 / 顿号、空格分隔与 `至`。
 * 单/双周标注挂在整串末尾（教务实测 `1,11单周` / `2,6双周`），数字照解析、最后按奇偶过滤。
 */
object WeeksText {

    private const val MAX_WEEK = 40

    fun parse(text: String): Set<Int> {
        val weeks = mutableSetOf<Int>()
        val cleaned = text
            .removeSuffix("周次")
            .removeSuffix("周")
            .trim()
        if (cleaned.isEmpty()) return emptySet()

        val oddOnly = cleaned.endsWith("单")
        val evenOnly = cleaned.endsWith("双")
        val body = if (oddOnly || evenOnly) cleaned.dropLast(1).trim() else cleaned

        for (part in body.split(',', '，', '、', ' ')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val range = p.split('-', '—', '~', '至')
            if (range.size == 2) {
                val a = range[0].trim().toIntOrNull()
                val b = range[1].trim().toIntOrNull()
                if (a != null && b != null && a in 1..MAX_WEEK && b >= a) {
                    weeks += (a..b.coerceAtMost(MAX_WEEK))
                    continue
                }
            }
            val single = p.toIntOrNull()
            if (single != null) {
                if (single in 1..MAX_WEEK) weeks += single
            } else {
                // 兜底（与 scripts 侧 parse_weeks 同一口径）：从脏 token 里抠数字
                Regex("\\d+").findAll(p).forEach { m ->
                    m.value.toIntOrNull()?.let { if (it in 1..MAX_WEEK) weeks += it }
                }
            }
        }
        return when {
            oddOnly -> weeks.filter { it % 2 == 1 }.toSet()
            evenOnly -> weeks.filter { it % 2 == 0 }.toSet()
            else -> weeks
        }
    }

    /** 压缩成 `1-16,18`；空集返回空串。 */
    fun format(weeks: Collection<Int>): String {
        val sorted = weeks.filter { it in 1..MAX_WEEK }.distinct().sorted()
        if (sorted.isEmpty()) return ""
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
        return parts.joinToString(",")
    }

    /** 压缩成 `第1-16,18周`；空集 / 全部越界返回「周次未定」。 */
    fun label(weeks: Collection<Int>): String {
        val text = format(weeks)
        return if (text.isEmpty()) "周次未定" else "第${text}周"
    }
}
