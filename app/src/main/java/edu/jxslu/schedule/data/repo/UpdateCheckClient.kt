package edu.jxslu.schedule.data.repo

import android.util.Log
import edu.jxslu.schedule.BuildConfig
import edu.jxslu.schedule.data.net.AppHttp
import edu.jxslu.schedule.domain.UpdateCheck
import edu.jxslu.schedule.domain.UpdateManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 检查更新的网络执行（DESIGN §3.3）：拉仓库镜像 latest.json → 解析 → 比对版本。
 *
 * 只手动触发（关于页「检查更新」行），无状态、不落盘、无冷启动任务——没有
 * 「上次检查时刻」要记，也就没有偏好键。无凭证、无日志拦截器（清单是公开仓库
 * 文件，不含任何用户数据，但纪律一致）。
 *
 * 多源顺序见 [edu.jxslu.schedule.domain.RepoMirror]（gh-proxy 加速 → jsdelivr → raw
 * 兜底）：逐源尝试、首个成功即用；单源失败只记日志换下一源，全挂才返回失败。
 */
class UpdateCheckClient(private val currentVersionName: String) {

    constructor() : this(BuildConfig.VERSION_NAME)

    // 每源一个总上限：正常源 ~1s 命中；被静默丢包的源最多烧满 10s 就换下一源
    // （base 的 15s connect / 20s read 对「点完等着看结果」太宽，收紧到 8s/10s）
    private val http: OkHttpClient = AppHttp.withCallTimeout(10).newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * 执行一次检查。逐源轮询（第一个成功即用），**任何失败都不抛**——
     * 返回 [UpdateCheckResult.Failed] 由 UI 给提示。
     */
    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val latest = fetchFirstAvailableManifest()
        when {
            latest == null -> UpdateCheckResult.Failed(UpdateCheckResult.FailReason.Network)
            !UpdateCheck.isNewer(latest.version, currentVersionName) -> UpdateCheckResult.UpToDate(latest)
            else -> UpdateCheckResult.UpdateAvailable(latest)
        }
    }

    /** 逐源拉清单；单源不可达 / 非 2xx / 脏清单都换下一源，全挂返回 null。 */
    private fun fetchFirstAvailableManifest(): UpdateManifest? {
        for (url in UpdateCheck.LIST_URLS) {
            val manifest = fetchManifest(url)
            if (manifest != null) return manifest
        }
        return null
    }

    /** 拉单个清单地址并过校验；网络失败记日志后返回 null（交调用方换源）。 */
    private fun fetchManifest(url: String): UpdateManifest? {
        val text = try {
            http.newCall(Request.Builder().url(url).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    resp.body?.string()
                }
        } catch (e: IOException) {
            Log.w(TAG, "manifest source unreachable: $url", e)
            return null
        } ?: return null
        return UpdateCheck.parseManifest(text)
    }

    private companion object {
        private const val TAG = "UpdateCheck"
    }
}

/** 一次检查的结论（UI 按此给提示与弹窗）。 */
sealed interface UpdateCheckResult {

    /** 已是最新（含解析到的清单，弹窗/Snackbar 可展示「当前 vX.Y.Z 已是最新」）。 */
    data class UpToDate(val latest: UpdateManifest) : UpdateCheckResult

    /** 有新版本：UI 据此弹「双通道下载」弹窗。 */
    data class UpdateAvailable(val latest: UpdateManifest) : UpdateCheckResult

    /** 检查失败。 */
    data class Failed(val reason: FailReason) : UpdateCheckResult

    /** 失败原因细分（文案由 UI 出；当前实际只有网络一类——清单源至少两个域名）。 */
    enum class FailReason {
        /** 所有清单源都不可达 / 内容不合法。 */
        Network,
    }
}
