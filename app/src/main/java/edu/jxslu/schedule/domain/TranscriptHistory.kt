package edu.jxslu.schedule.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 「最近导出」里的一条记录。只带展示与排序要用的字段，不持有 File（纯逻辑层不碰文件系统）。 */
data class TranscriptEntry(
    val name: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
)

/**
 * 导出成绩单的保留策略与文件名解析（DESIGN §4.25）。
 *
 * 定位是**最近导出**而不是档案库：单据服务端随时能重出，本地留一份只是为了省掉
 * 「刚导完又要分享」时重来一遍。所以只保留 [DEFAULT_KEEP] 份，且每条记录都是从
 * 文件名反推出来的，不建表、不加索引文件——文件和记录天然一一对应，不会出现
 * 「记录还在、文件没了」的两套真相。
 *
 * 纯逻辑，JVM 可测：Store 负责把目录列出来，按这里给的名单删。
 */
object TranscriptHistory {

    /** 文件名前缀（`PtworkTranscript.defaultFileName` 生成）。 */
    const val PREFIX = "成绩单-"

    /** 正式产物的后缀。 */
    const val SUFFIX = ".pdf"

    /**
     * 写盘中间态的后缀：先写 `.part` 再改名，崩溃或被杀时才会留下它。
     * 这类半成品既不进列表，也不占保留名额，读到就清掉。
     */
    const val PARTIAL_SUFFIX = ".part"

    /** 默认保留份数。 */
    const val DEFAULT_KEEP = 10

    /**
     * 是否算一条记录。
     *
     * 只认后缀，不强制要求 [PREFIX]：将来改了命名前缀，老文件仍要照常出现在列表里
     * （认前缀的话它们会静默消失，用户只会觉得「东西丢了」）。
     */
    fun isEntryName(name: String): Boolean =
        name.endsWith(SUFFIX, ignoreCase = true) && !isPartialName(name)

    /** 是否是写盘半成品。**先判它再判 [isEntryName]**：`x.pdf.part` 同时满足两个后缀条件。 */
    fun isPartialName(name: String): Boolean = name.endsWith(PARTIAL_SUFFIX, ignoreCase = true)

    /**
     * 文件名是否安全。删除/取文件只接受纯文件名（来源是 [sortNewestFirst] 列出来的那批），
     * 带路径分隔符的一律拒绝：拼接路径时 `../` 会越出 `transcripts/` 目录。
     */
    fun isSafeName(name: String): Boolean =
        name.isNotBlank() && '/' !in name && '\\' !in name

    /**
     * 从文件名反推展示用标签：`成绩单-2025-2026-2等4个学期-20260924-1928.pdf`
     * → `2025-2026-2等4个学期`。取不到就回退整串（去掉后缀），宁可难看也不留空标题。
     */
    fun labelOf(fileName: String): String {
        val body = fileName.removeSuffix(SUFFIX).removeSuffix(SUFFIX.uppercase())
        val inner = if (body.startsWith(PREFIX)) body.removePrefix(PREFIX) else body
        return inner.replace(STAMP_RE, "").ifBlank { inner.ifBlank { fileName } }
    }

    /** 最新在前。同秒写入的两份（连点两次导出）按名字倒排，保证顺序稳定可预期。 */
    fun sortNewestFirst(entries: List<TranscriptEntry>): List<TranscriptEntry> =
        entries.sortedWith(
            compareByDescending<TranscriptEntry> { it.modifiedAtMillis }
                .thenByDescending { it.name },
        )

    /** 该删哪些（返回文件名）。[keep] <= 0 视为不留。 */
    fun filesToPrune(entries: List<TranscriptEntry>, keep: Int = DEFAULT_KEEP): List<String> =
        if (keep <= 0) entries.map { it.name } else sortNewestFirst(entries).drop(keep).map { it.name }

    /** 列表里的时间文案。时区可注入，方便单测钉死结果。 */
    fun timeLabel(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(TIME_FORMATTER)

    /** 体积文案。用 [Locale.US] 固定小数点，避免某些区域把「1.5 MB」写成「1,5 MB」。 */
    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1f MB".format(Locale.US, bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%d KB".format(Locale.US, bytes / 1024)
        else -> "$bytes B"
    }

    /** 文件名末尾的 `-yyyyMMdd-HHmm`（与 `defaultFileName` 的格式一一对应）。 */
    private val STAMP_RE = Regex("""-\d{8}-\d{4}$""")

    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}
