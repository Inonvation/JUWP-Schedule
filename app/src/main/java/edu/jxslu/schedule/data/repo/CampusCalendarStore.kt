package edu.jxslu.schedule.data.repo

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import edu.jxslu.schedule.domain.CampusCalendarIndex
import edu.jxslu.schedule.domain.CampusCalendarRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 校历镜像的落盘缓存与远程刷新（DESIGN §4.34）。
 *
 * 数据源 = 开发者仓库镜像（`docs-public/calendar/`，规则在 [CampusCalendarRules]）：
 * `index.json`（学年 + 文件名）+ 校历图。刷新 = 拉 JSON → 比对本地已存学年 →
 * 不同才下载图，**原子落盘**（临时文件写完改名，中断不留半张图）。
 *
 * 为什么不用 Room/Not：就两样东西（一张图 + 一个学年标签），SharedPreferences + filesDir
 * 足够；StateFlow 只为「后台刷完页内立即换图」（同 `QzxyWateringStore` 的理由）。
 *
 * 失败静默（与 ScoreSync 同纪律）：校历是锦上添花的信息，拉不到就继续用本地缓存，
 * 连内置兜底都没有才是问题——兜底图在 `res/drawable-nodpi/ic_campus_calendar.jpg`，
 * 由 UI 层直接兜，本类不感知。无任何凭证与用户数据，镜像 URL 是公开仓库。
 */
class CampusCalendarStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val dir = File(context.applicationContext.filesDir, DIR_NAME)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 一次刷新一把锁：冷启动与页内手动撞上时后者让位（语义同 ScoreSync）。 */
    private val mutex = Mutex()

    private val _state = MutableStateFlow(readState())

    /** 当前校历状态（学年 + 本地图片文件；图未下载过时 file 为 null，UI 走内置兜底）。 */
    val state: StateFlow<CampusCalendarState> = _state.asStateFlow()

    /**
     * 刷新：[force] = 页内手动按钮，绕过 7 天闸门；否则闸门未到直接空跑返回。
     * 每一步失败都静默返回（不打断调用方；调用方也不该为此弹任何提示——
     * 后台刷新成功与否用户无感，页内手动失败由 UI 层按返回值给行内提示）。
     */
    suspend fun refresh(force: Boolean = false): RefreshResult = mutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && !CampusCalendarRules.shouldRefresh(prefs.getLong(KEY_LAST_SUCCESS, 0L), now)) {
            return RefreshResult.Skipped
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                // 1) 清单
                val index = fetchIndex() ?: return@runCatching RefreshResult.Failed
                // 2) 学年没变且图在 → 只记成功时刻（图不会变，省一次 1.2MB 下载）
                val current = _state.value
                if (current.year == index.year && current.localFile != null && exists(current.localFile)) {
                    prefs.edit().putLong(KEY_LAST_SUCCESS, now).apply()
                    return@runCatching RefreshResult.Unchanged
                }
                // 3) 下载图 → 原子落盘
                val file = downloadImage(index) ?: return@runCatching RefreshResult.Failed
                // 4) 换状态 + 记时刻；旧学年图顺带清掉（只保留当前一张，同背景图口径）
                val previous = current.localFile
                _state.value = CampusCalendarState(year = index.year, localFile = file.name)
                prefs.edit()
                    .putString(KEY_YEAR, index.year)
                    .putString(KEY_IMAGE, file.name)
                    .putLong(KEY_LAST_SUCCESS, now)
                    .apply()
                if (previous != null && previous != file.name) {
                    runCatching { File(dir, previous).delete() }
                }
                RefreshResult.Updated
            }.getOrElse {
                Log.w(TAG, "calendar refresh failed", it)
                RefreshResult.Failed
            }
        }
    }

    /** 本地图片文件句柄；文件名只能来自本类自己写下的偏好（外来的名字不认）。 */
    fun fileFor(name: String?): File? {
        if (name.isNullOrBlank()) return null
        if (!name.startsWith("calendar_")) return null
        return File(dir, name).takeIf { it.isFile }
    }

    private fun exists(name: String): Boolean = fileFor(name) != null

    /** 拉 index.json 并过校验；网络失败 / 脏清单都返回 null。 */
    private fun fetchIndex(): CampusCalendarIndex? {
        val text = http.newCall(Request.Builder().url(CampusCalendarRules.indexUrl()).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            } ?: return null
        return CampusCalendarRules.parseIndex(text)
    }

    /** 下载校历图，临时文件写完原子改名；解码探测保它真是张图。返回落好的文件。 */
    private fun downloadImage(index: CampusCalendarIndex): File? {
        val targetName = CampusCalendarRules.localFileName(index)
        dir.mkdirs()
        val tmp = File(dir, "$targetName.tmp")
        val request = Request.Builder().url(CampusCalendarRules.imageUrl(index)).build()
        val ok = http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use false
            val body = resp.body ?: return@use false
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            // 解码探测：镜像被改坏（比如传了个 HTML）时不落盘，保住旧图
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.absolutePath, bounds)
            bounds.outWidth > 0
        }
        if (!ok) {
            runCatching { tmp.delete() }
            return null
        }
        val target = File(dir, targetName)
        if (!tmp.renameTo(target)) {
            runCatching { tmp.delete() }
            return null
        }
        return target
    }

    private fun readState(): CampusCalendarState {
        val year = prefs.getString(KEY_YEAR, null) ?: return CampusCalendarState()
        val image = prefs.getString(KEY_IMAGE, null) ?: return CampusCalendarState()
        // 文件被系统清掉（存储紧张）时标签作废，回内置兜底
        if (!exists(image)) return CampusCalendarState()
        return CampusCalendarState(year = year, localFile = image)
    }

    /** 页面展示用的状态。[year] 空 = 本地没有任何已下载的校历（UI 走内置兜底图）。 */
    data class CampusCalendarState(
        val year: String = "",
        val localFile: String? = null,
    )

    /** 刷新结果（UI 层手动刷新时给行内提示用；后台静默刷新不看它）。 */
    enum class RefreshResult {
        /** 拉到新学年并已落盘。 */
        Updated,

        /** 学年没变（或已是最新），没下载。 */
        Unchanged,

        /** 闸门未到，零网络空跑。 */
        Skipped,

        /** 网络失败或镜像内容坏了。 */
        Failed,
    }

    private companion object {
        private const val TAG = "CampusCalendar"
        private const val FILE_NAME = "campus_calendar"
        private const val DIR_NAME = "campus_calendar"
        private const val KEY_YEAR = "year"
        private const val KEY_IMAGE = "image"
        private const val KEY_LAST_SUCCESS = "last_success"
    }
}
