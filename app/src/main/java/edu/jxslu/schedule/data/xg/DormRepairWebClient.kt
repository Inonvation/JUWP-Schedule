package edu.jxslu.schedule.data.xg

import android.webkit.CookieManager
import edu.jxslu.schedule.data.net.AppHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 报修列表的 **WebView 会话回退**（DESIGN §4.49）。
 *
 * 主路径是 `CasSession`（存过统一认证账号时走 CAS → 学工 SSO → office 会话）；
 * 但用户可能是「在报修 WebView 里手登、没把密码存进 App」的形状，这时 CAS 侧没有凭证，
 * 主路径静默失败。这里把 `CookieManager` 里现成的 office 会话（`session_oa` / `JSESSIONID`）
 * 抄出来，直接带 `Cookie` 头发同一个只读列表请求——与 `TranscriptCookies` 同一手法。
 *
 * **不落盘、不打印 Cookie、不碰写接口。** 进程重启后 WebView 会话没了就返回
 * [Result.NoSession]，由上层静默降级；不弹错误、不影响卡片上的旧快照。
 */
class DormRepairWebClient(
    private val http: OkHttpClient = AppHttp.base.newBuilder()
        .followRedirects(false)
        .build(),
) {

    sealed interface Result {
        data class Ok(val body: String) : Result
        data object NoSession : Result
        data class Failed(val message: String) : Result
    }

    suspend fun fetch(): Result {
        // CookieManager 的读要有 Looper，统一回主线程（与 WebViewCookieBridge 同口径）
        val cookie = withContext(Dispatchers.Main) {
            runCatching { CookieManager.getInstance().getCookie(XgUrls.OFFICE) }.getOrNull()
        }?.trim().orEmpty()
        if (cookie.isEmpty()) return Result.NoSession
        return withContext(Dispatchers.IO) {
            try {
                val form = FormBody.Builder().apply {
                    XgUrls.REPAIR.recordListForm(DormRepairParser.MAX_RECORDS)
                        .forEach { (name, value) -> add(name, value) }
                }.build()
                val request = Request.Builder()
                    .url(XgUrls.OFFICE_RECORD_LIST)
                    .header("Cookie", cookie)
                    .header("User-Agent", UA)
                    .header("Referer", XgUrls.REPAIR.applyUrl)
                    .post(form)
                    .build()
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        response.code in 300..399 -> Result.Failed("学工会话已失效（被重定向到登录页）")
                        !response.isSuccessful -> Result.Failed("学工返回 HTTP ${response.code}")
                        text.isBlank() -> Result.Failed("学工返回了空应答")
                        // office 接口未登录时可能回 302 跟到 CAS 登录页；这里不跟 302，
                        // 但保险起见把明显的 HTML 也当会话失效，别把登录页交给 JSON 解析
                        text.trimStart().startsWith("<") -> Result.Failed("学工会话已失效")
                        else -> Result.Ok(text)
                    }
                }
            } catch (e: IOException) {
                Result.Failed(e.message ?: "网络不可用")
            }
        }
    }

    private companion object {
        const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
