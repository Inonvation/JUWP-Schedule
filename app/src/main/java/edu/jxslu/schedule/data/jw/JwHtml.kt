package edu.jxslu.schedule.data.jw

/**
 * 教务壳页 HTML 的学期下拉解析（考试 / 课表 / 选课 / 教学日历共用）。
 *
 * 优先按 `id` / `name` 收窄到指定 select，避免整页多个下拉时串值；
 * `selectId = null` 只用于确认页面上只有一个 select 的壳页。
 * value 属性优先、option 文本兜底；`requireTermPattern` 时用 [JwUrls.TERM_PATTERN] 校验。
 */
internal object JwHtml {

    fun selectedTerm(
        html: String,
        selectId: String? = null,
        requireTermPattern: Boolean = true,
    ): String? {
        val scope = if (selectId == null) {
            html
        } else {
            val selectRe = Regex(
                """(?is)<select[^>]*(?:id|name)\s*=\s*["']?${Regex.escape(selectId)}["']?[^>]*>([\s\S]*?)</select>""",
            )
            selectRe.find(html)?.groupValues?.getOrNull(1) ?: return null
        }
        val optionRe = Regex("""(?is)<option\b([^>]*)>([\s\S]*?)</option>""")
        for (option in optionRe.findAll(scope)) {
            val attrs = option.groupValues[1]
            if (!attrs.contains("selected", ignoreCase = true)) continue
            // value 优先、文本兜底：value 存在但格式不对时也要再试文本（旧调用点的口径）
            val text = option.groupValues[2].trim().takeIf { it.isNotEmpty() }
            for (candidate in listOfNotNull(valueOf(attrs), text).distinct()) {
                if (!requireTermPattern || JwUrls.TERM_PATTERN.matches(candidate)) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun valueOf(attrs: String): String? =
        Regex("""value\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}
