package edu.jxslu.schedule.ui.ebike

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import edu.jxslu.schedule.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.SqlTileWriter
import java.io.File
import java.util.Locale

/**
 * 「地图缓存」的统计与清除（DESIGN §3.9，2026-09-28 用户口径）。
 *
 * 缓存是两块，卡上分开报：
 * 1. **离线瓦片**：osmdroid 的瓦片库 `cacheDir/osmdroid/tiles/cache.db`。
 *    注意它**不是**一堆图片文件——osmdroid 6.1.18 默认走 `SqlTileWriter`（SQLite 库），
 *    而且这个库的连接是**静态的、`onDetach()` 是空实现**（拆包确认）：进程内被第一次
 *    打开后就一直开着。所以清除**不能只删文件**——删掉文件只是 unlink，库继续从那个
 *    inode 读写，用户看着"清了"实际照样命中旧瓦片，空间也要等进程退出才释放。
 *    正确做法是走 [SqlTileWriter.purgeCache] 删行（`DELETE FROM tiles`），再尽力 VACUUM
 *    把文件缩回去（DELETE 不缩文件，不 VACUUM 的话那几十兆磁盘看着像没清）。
 * 2. **停车点数据**：还车点 / 禁停区图层的落盘缓存文件（`ZoneCacheStore`，几十 KB）。
 *
 * 路径一律经 [ensureOsmdroidConfiguration] 取（与地图页同一份配置），**别另抄一份**。
 */
internal object EbikeMapCache {

    /** 两块缓存各自的大小（字节）与合计。 */
    data class Usage(val tileBytes: Long, val zoneBytes: Long) {
        val totalBytes: Long get() = tileBytes + zoneBytes
        val isEmpty: Boolean get() = totalBytes <= 0L
    }

    /**
     * 统计当前占用。进页与回到本页（ON_RESUME）各调一次——瓦片是浏览地图时长的；
     * 文件遍历放 IO，不吃主线程。
     */
    suspend fun measure(context: Context): Usage = withContext(Dispatchers.IO) {
        ensureOsmdroidConfiguration(context)
        Usage(
            tileBytes = dirBytes(Configuration.getInstance().osmdroidTileCache),
            zoneBytes = Graph.zoneCacheStore(context).bytes(),
        )
    }

    /**
     * 清除两块缓存。返回 false = 瓦片库没清掉（库打不开等），卡片据此提示重试。
     *
     * 停车点那份是普通文件，删掉即失效；瓦片那份见类注释，必须走 SQL 删行。
     *
     * **库文件不存在时什么都不做**：`SqlTileWriter()` 的构造器就会 `openOrCreateDatabase`
     * （拆包确认）——为了"清除"反而建出一个空库，卡片当场显示几 KB 的占用，像是没清干净。
     */
    suspend fun clear(context: Context): Boolean = withContext(Dispatchers.IO) {
        ensureOsmdroidConfiguration(context)
        val dbFile = File(Configuration.getInstance().osmdroidTileCache, TILE_DB_NAME)
        val purged = if (dbFile.exists()) {
            runCatching { SqlTileWriter().purgeCache() }.getOrDefault(false).also { ok ->
                // 删完缩文件（尽力而为）：瓦片库那条连接是共用的（地图页/别的页面都可能持有），
                // 正好在写就让它失败——行已经删了，瓦片不会再命中，只是空间晚点回收
                if (ok) vacuum(dbFile)
            }
        } else {
            true
        }
        // 回滚日志（cache.db-journal）顺手清掉：purge 的写事务提交成功，就说明库里没有未决
        // 事务——真有条热日志，SQLite 在打开那一刻就回滚并消费掉了，之后不会再用它；而它会以
        // 高水位留在磁盘上（真机实测 512 KB），让"已清除"看起来没清干净。purge 失败时不动它
        // （那时它可能是唯一能恢复库的东西）。时序上也安全：清除只从出码页发起，地图页已退出，
        // 没有在跑的事务。
        if (purged) runCatching { File(dbFile.absolutePath + JOURNAL_SUFFIX).delete() }
        Graph.zoneCacheStore(context).delete()
        purged
    }

    /** 体积文案。口径与 `TranscriptHistory.sizeLabel` 一致：Locale.US 固定小数点。 */
    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1f MB".format(Locale.US, bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%d KB".format(Locale.US, bytes / 1024)
        else -> "$bytes B"
    }

    /**
     * 把瓦片库的空闲页还给系统。[dbFile] 不存在（从没打开过地图）直接跳过——
     * 别为了 VACUUM 把库建出来。
     */
    private fun vacuum(dbFile: File) {
        if (!dbFile.exists()) return
        runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                .use { it.execSQL("VACUUM") }
        }
    }

    /** 目录总字节（含 `-journal` 这类附属文件；目录不存在回 0）。 */
    private fun dirBytes(dir: File): Long = runCatching {
        dir.walkTopDown().filter(File::isFile).sumOf(File::length)
    }.getOrDefault(0L)

    /** `SqlTileWriter` 的库文件名（拆包确认：`getOsmdroidTileCache()` 下的 `cache.db`）。 */
    private const val TILE_DB_NAME = "cache.db"

    /** SQLite 回滚日志的后缀（`cache.db-journal`）。 */
    private const val JOURNAL_SUFFIX = "-journal"
}
