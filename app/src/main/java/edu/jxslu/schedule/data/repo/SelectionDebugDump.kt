package edu.jxslu.schedule.data.repo

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * debug 包专用：选课提交/退课应答原文落盘（`filesDir/selection_debug/`，2026-10-08）。
 *
 * 目的：提交被拒/成功的**真实返回码语义**没有官方文档，映射是按页面 JS + 只读样本建的——
 * 第一次真实提交的应答原文留住，联调时逐字段核对、必要时校准 `SelectionCenterParser` 的
 * 三态映射（照 `ujing_debug/` 先例）。
 *
 * 纪律：release 构建 `Graph.selectionCenterClient` 传 null（一个字节都不写）；目录已排除出
 * 两套备份规则（`backup_rules.xml` / `data_extraction_rules.xml`）；每类只留最近
 * [KEEP_PER_NAME] 份。
 */
class SelectionDebugDump(private val dir: File) {

    private val time = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")

    /** 落一份原文；任何 IO 失败都吞掉（留痕不该影响业务链）。 */
    fun dump(name: String, raw: String) {
        runCatching {
            dir.mkdirs()
            File(dir, "${name}_${LocalDateTime.now().format(time)}.txt")
                .writeText(raw, Charsets.UTF_8)
            trim(name)
        }
    }

    /** 同名文件只留最新 [KEEP_PER_NAME] 份（文件名前缀即时间序）。 */
    private fun trim(name: String) {
        val files = dir.listFiles { f -> f.name.startsWith("${name}_") } ?: return
        files.sortedByDescending { it.name }
            .drop(KEEP_PER_NAME)
            .forEach { it.delete() }
    }

    companion object {
        const val KEEP_PER_NAME = 20
    }
}
