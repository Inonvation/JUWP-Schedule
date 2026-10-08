package edu.jxslu.schedule.data.power

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * debug 包专用：缴费平台响应原文落盘（`filesDir/power_debug/`，2026-10-08）。
 *
 * 目的：`singleFeeitem.sceneinfo 为空 = 未设置寝室` 这条判据没有真实未绑定账号的样本，
 * `sceneBind/add` 也只有「同房间重绑」一次实证——原文留住，首个真实案例一到就能逐字段
 * 核对（照 `ujing_debug/` / `qiekj_debug/` 先例）。
 *
 * 纪律：release 构建 `Graph.powerRepository` 传 null（一个字节都不写）；目录已排除出
 * 两套备份规则（`backup_rules.xml` / `data_extraction_rules.xml`）；每类只留最近
 * [KEEP_PER_NAME] 份。
 */
class PowerDebugDump(private val dir: File) {

    private val time = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")

    /**
     * 落一份原文；任何 IO 失败都吞掉（留痕不该影响业务链）。
     * 文件 IO 收在 [Dispatchers.IO]：调用方协程可能跑在主线程调度器上
     * （`viewModelScope.launch`），别让 mkdirs/writeText/trim 那点磁盘活压上去。
     */
    suspend fun dump(name: String, raw: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                File(dir, "${name}_${LocalDateTime.now().format(time)}.txt")
                    .writeText(raw, Charsets.UTF_8)
                trim(name)
            }
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
