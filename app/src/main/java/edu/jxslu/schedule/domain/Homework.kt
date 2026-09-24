package edu.jxslu.schedule.domain

import java.time.LocalDate

/**
 * 一条作业（DESIGN §4.20）。归属口径与 [Note] 完全一致：挂**课程名**，不带 courseId / timetableId。
 *
 * 与笔记的差异只有两处：[dueDate]（提交截止日期，**只到日粒度**，可空 = 布置了但没给期限）
 * 与 [done]（完成勾选，像待办）。详情 [detail] 复用笔记那套 Markdown 源码 + `img:` 图片引用。
 */
data class Homework(
    val id: Long = 0,
    val courseName: String,
    val detail: String = "",
    /** 提交截止日期；null = 未设定。当天不算过期（文案「今天」，见 [dueLabel]）。 */
    val dueDate: LocalDate? = null,
    val done: Boolean = false,
    /** 勾选完成的时间；取消完成置回 null。 */
    val doneAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * 作业列表行显示文本（2026-09-23 去标题后新增）：取**正文第一行**、剥掉 Markdown 符号。
 *
 * 每行剥的顺序：
 * 1. 图片引用 `![](…)`（含 `img:` 与外链）整条剥掉——图片进不了摘要（与
 *    [plainTextOf] 同口径），一行只剩图片时该行跳过，继续看下一行；
 * 2. 任务项前缀 `- [ ] ` / `- [x] `（x 大小写都认）；
 * 3. 列表 / 引用前缀 `- `、`* `、`+ `、`> `；
 * 4. 行首 `#`+空格（标题行）；
 * 5. 行内强调包边 `**` `*` `~~` `` ` ``（只剥成对的行首/行尾，不碰中间的）；
 * 6. 行内公式包边 `$…$`（剥 `$` 保留内容——正文里公式是内容，丢掉等于空行）。
 *
 * 空行跳过；全是空行、空串、或只有图片时返回「未命名作业」（与旧 title.ifBlank 的兜底文案一致）。
 */
fun homeworkDisplayTitle(detail: String): String =
    detail.lineSequence()
        .map { stripMarkdownLine(it) }
        .firstOrNull { it.isNotBlank() }
        ?: "未命名作业"

/** 行内图片语法（`![alt](目标)`）。收集 `img:` 引用的正则在 [MarkdownImages]，这里要连外链一起剥。 */
private val INLINE_IMAGE = Regex("""!\[[^\]]*]\([^)\n]*\)""")

private fun stripMarkdownLine(line: String): String {
    var s = line.trim()
    s = INLINE_IMAGE.replace(s, "").trim()
    if (s.isEmpty()) return s
    s = s.removePrefix("- [ ] ").removePrefix("- [X] ").removePrefix("- [x] ")
    s = s.removePrefix("- ").removePrefix("* ").removePrefix("+ ").removePrefix("> ")
    // 标题级从长到短剥：先剥 6 个 `#` 才轮到 5 个，避免 `######` 被 1 个 `#` 抢先剥成 `#####`
    s = s.removePrefix("######").removePrefix("#####").removePrefix("####")
        .removePrefix("###").removePrefix("##").removePrefix("#")
    s = s.trim()
    for (pair in listOf("**", "~~", "`", "*")) {
        if (s.length >= pair.length * 2 && s.startsWith(pair) && s.endsWith(pair)) {
            s = s.removePrefix(pair).removeSuffix(pair).trim()
        }
    }
    if (s.length >= 2 && s.firstOrNull() == '$' && s.lastOrNull() == '$' && s.length > 2) {
        s = s.substring(1, s.length - 1).trim()
    }
    return s
}

/**
 * 作业库的课程分组行（DAO 投影，DESIGN §3.11）。
 * [pending] = 未完成数（分组行副标题「N 项未完成」），[total] = 全部条数。
 */
data class HomeworkCourseGroup(
    val courseName: String,
    val total: Int,
    val pending: Int,
    /** 最近更新时间（分组行按它倒序）。 */
    val latestAt: Long,
)
