package edu.jxslu.schedule.data.repo

import android.content.Context
import android.webkit.WebStorage
import android.webkit.WebView
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.session.WebViewCookieBridge
import edu.jxslu.schedule.ui.ebike.EbikeMapCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 关于页「清理缓存」（DESIGN §3.3，2026-10-08）：一键清掉**可自愈的临时数据**，
 * 绝不碰用户数据。清单与「绝不清」的对照是本功能的安全边界，逐项依据：
 *
 * **清（清完各走各的重取路径，无需任何恢复操作）**
 * - **WebView Cookie**：[WebViewCookieBridge.clearAll]（退出登录同款）。App 内 WebView
 *   只访问学校域（导入/学工/成绩单三个入口），整库清不误伤第三方登录态；有凭证的用户
 *   下次进教务页面自动填表重登，没凭证的手登一次。不影响 OkHttp 直取的后台同步
 *   （成绩/考试/选课/电费……走的是内存 CookieJar，与 WebView 两套）。
 * - **WebView HTTP 缓存 / 表单 / localStorage**：官方 API 清（见 [clearWebview]），
 *   **不许手删 `app_webview/` 目录**——Chromium 内部结构无版本承诺。localStorage 里
 *   存着学工平台的登录 token，清掉后学工表单 WebView 掉登录 = 重新登录一次，属预期。
 * - **osmdroid 瓦片 + 快趣图层缓存**：直接复用 [EbikeMapCache.clear]——瓦片库是
 *   SQLite 且连接静态常开，**必须走 SQL purge + VACUUM**，删文件只是假清（详见其类注释）。
 * - **AI 调课图片**（ai_img/）：走 [AiImageStore.sweep]，它的 Mutex 保证与进行中的
 *   导入串行（本类不另开删法，防并发交错）。
 * - **崩溃记录**（crash/）：诊断数据，2026-10-08 用户拍板纳入清理（想反馈问题先导出）。
 * - **校历海报图**（campus_calendar/）：只删图**不删 prefs**——prefs 里的文件名悬空时
 *   `CampusCalendarStore.fileFor` 因 `isFile` 守卫返回 null，页面自动回落内置兜底图。
 * - **debug 协议样本**（4 个 *_debug/ 目录）：release 包通常不存在，debug 包自动重写。
 * - **实习基地快照 / 电费流水缓存**（internship_bases.json / power_history_cache.json）：
 *   7 天闸门 / 下次查询时重拉。
 *
 * **绝不清（用户数据 / 凭证 / 变动检测基准，动了就是事故）**
 * - Room 数据库与 DataStore、全部凭证 prefs（备份都排除的那批）。
 * - notes_img/（笔记正文引用的图片）、schedule_bg/（课表背景）、transcripts/（导出的成绩单 PDF）。
 * - schedule_baseline.json / exam_snapshot.json / selection_rounds.json：名为快照实为
 *   **变动检测基准**——删掉后下次同步会把旧数据当「新变动」误报、丢考试与选课提醒的判据。
 * - ai_apply_history.json（AI 调课撤销入口）、quiet_hours_queue.json（待补发的免扰提醒）。
 *
 * WebView 的磁盘占用没有公开 API 可查，统计按 `dataDir/app_webview` 目录实际求和
 * （同 uid 可读，目录不存在回 0）；这是纯探测，失败不影响清理。
 */
object AppCacheCleaner {

    /**
     * 清理动作各块的成败聚合：`webView` = Cookie + 缓存 + localStorage；`tiles` =
     * osmdroid（[EbikeMapCache.clear] 的原样返回）。文件块尽力而为不进聚合
     * （单项 `runCatching` 删，失败不影响其余项）。
     */
    data class Result(val webView: Boolean, val tiles: Boolean) {
        val allOk: Boolean get() = webView && tiles
    }

    /** 进页统计一次的当前占用（字节）。WebView 数据目录也算进去，给文案一个实数。 */
    suspend fun measure(context: Context): Long = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        EbikeMapCache.measure(context).totalBytes +
            dirBytes(File(app.filesDir, AI_IMG_DIR)) +
            dirBytes(CrashReporter.crashDir(app)) +
            dirBytes(File(app.filesDir, CALENDAR_DIR)) +
            DEBUG_DIRS.sumOf { dirBytes(File(app.filesDir, it)) } +
            fileBytes(File(app.filesDir, INTERNSHIP_SNAPSHOT_FILE)) +
            fileBytes(File(app.filesDir, POWER_HISTORY_FILE)) +
            dirBytes(File(app.applicationInfo.dataDir, WEBVIEW_DATA_DIR))
    }

    /**
     * 顺序：先 WebView 块（主线程）→ 瓦片（SQL purge）→ 文件块（IO）。返回 [Result]，
     * UI 据此提示「已清理」或「部分没清干净」。
     */
    suspend fun clear(context: Context): Result {
        val webView = clearWebview(context)
        val tiles = EbikeMapCache.clear(context)
        clearFileCaches(context)
        return Result(webView = webView, tiles = tiles)
    }

    /**
     * WebView 三件套，全部走官方 API（主线程）：
     * 1. Cookie 整库清（[WebViewCookieBridge.clearAll]，内部自切 Main）；
     * 2. `WebStorage.deleteAllData()` 清 localStorage；
     * 3. 临时 [WebView] 实例（**applicationContext**，避免持有 Activity）`clearCache(true)`
     *    + `clearFormData()` + `clearSslPreferences()` 后立即 `destroy()`——临时实例
     *    从未 attach，destroy 合法；这是 Chromium 官方缓存清除路径，目录本身不动。
     */
    private suspend fun clearWebview(context: Context): Boolean {
        WebViewCookieBridge.clearAll()
        return withContext(Dispatchers.Main) {
            runCatching {
                WebStorage.getInstance().deleteAllData()
                val webView = WebView(context.applicationContext)
                webView.clearCache(true)
                webView.clearFormData()
                webView.clearSslPreferences()
                webView.destroy()
                true
            }.getOrDefault(false)
        }
    }

    /** 文件块：逐项尽力而为，单项失败不中断其余项。 */
    private suspend fun clearFileCaches(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        Graph.aiImageStore(app).sweep()
        clearDir(CrashReporter.crashDir(app))
        clearDir(File(app.filesDir, CALENDAR_DIR))
        DEBUG_DIRS.forEach { clearDir(File(app.filesDir, it)) }
        runCatching { File(app.filesDir, INTERNSHIP_SNAPSHOT_FILE).delete() }
        runCatching { File(app.filesDir, POWER_HISTORY_FILE).delete() }
        Unit
    }

    /** 清空目录内容、保留目录本身（各 Store 持有的是目录句柄，留着无副作用）。 */
    private fun clearDir(dir: File) {
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    /** 目录总字节（目录不存在回 0）。 */
    private fun dirBytes(dir: File): Long = runCatching {
        dir.walkTopDown().filter(File::isFile).sumOf(File::length)
    }.getOrDefault(0L)

    /** 单文件字节（不存在回 0）。 */
    private fun fileBytes(file: File): Long =
        if (file.isFile) runCatching { file.length() }.getOrDefault(0L) else 0L

    // 以下文件名/目录名与各 Store 的 private 常量互为镜像——改名必须两处同步：
    private const val AI_IMG_DIR = "ai_img" // AiImageStore.DIR_NAME
    private const val CALENDAR_DIR = "campus_calendar" // CampusCalendarStore.DIR_NAME
    private const val INTERNSHIP_SNAPSHOT_FILE = "internship_bases.json" // InternshipSnapshotStore.SNAPSHOT_FILE
    private const val POWER_HISTORY_FILE = "power_history_cache.json" // PowerHistoryCache.CACHE_FILE（companion private，镜像）

    /** debug 包协议样本目录（定义散在 Graph.kt，各处 `File(filesDir, 名)` 直建）。 */
    private val DEBUG_DIRS = listOf("ujing_debug", "qiekj_debug", "power_debug", "selection_debug")

    /** Chromium App WebView 数据目录（Cookie / HTTP cache / localStorage 的落盘处）。 */
    private const val WEBVIEW_DATA_DIR = "app_webview"
}
