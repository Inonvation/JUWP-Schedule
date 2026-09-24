package edu.jxslu.schedule.data.jw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 一次表单 POST 的结果。只有 HTTP 状态码与原始字节，如何解读交给 [TranscriptClient]。 */
data class TranscriptResponse(val code: Int, val bytes: ByteArray) {

    // data class 里有 ByteArray 必须自己写 equals/hashCode，否则是引用比较（单测断言会莫名失败）
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is TranscriptResponse && code == other.code && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * code + bytes.contentHashCode()

    /** 应答正文按 UTF-8 解出文本（失败提示是 HTML/JSON，都是 UTF-8）。 */
    fun text(): String = bytes.toString(Charsets.UTF_8)
}

/** HTTP 传输层。抽出来是为了让导出流程能在 JVM 单测里跑完整分支（不联网）。 */
fun interface TranscriptTransport {
    suspend fun post(
        url: String,
        form: List<Pair<String, String>>,
        cookie: String?,
    ): TranscriptResponse
}

/**
 * 签章系统成绩单导出（DESIGN §4.25）。契约与坑见 [PtworkTranscript]。
 *
 * 会话不走账号密码：[cookie] 由调用方从 WebView 的 CookieManager 取来（授权窗口走一遍 CAS
 * 就有了），本类既不存凭证也不碰 WebView。取 Cookie 与重登都由 UI 驱动，这个类只负责
 * 「有条件 → 拿 PDF」这一段，并且保证**拿不到真 PDF 就不返回**。
 */
class TranscriptClient(
    private val transport: TranscriptTransport = OkHttpTranscriptTransport(),
) {

    /**
     * 拉取「有成绩的学期 + 门数」。按 `pages` 翻完全部（服务端固定 15 行一页，`limit` 无效）。
     *
     * 失败分类：会话失效抛 [TranscriptException.SessionExpired]，其余见各类。
     */
    suspend fun fetchTerms(cookie: String?): List<TranscriptTerm> {
        val rows = ArrayList<TranscriptCourse>()
        var pageNo = 1
        var total = 0
        // 连同 MAX_PAGES 一起当循环条件：写在循环体末尾再 break 会多打一次接口（翻页号先自增）
        while (pageNo <= PtworkTranscript.MAX_PAGES) {
            val page = requestList(terms = emptyList(), page = pageNo, cookie = cookie)
            rows += page.rows
            total = page.total
            // 三个出口：翻到服务端说的末页、已收满 total、这一页什么都没有。
            // 后两个是防脏数据（pages 报得比实际大时不必空转），MAX_PAGES 兜底
            if (pageNo >= page.pages || rows.size >= total || page.rows.isEmpty()) break
            pageNo++
        }
        return PtworkTranscript.termsFrom(rows)
    }

    /**
     * 导出 PDF。两步：先按 [terms] 换取 pagePri，再回传它拿字节流。
     *
     * **导出前用 `total` 当闸门**：无成绩的学期服务端同样会给 pagePri，直接导出会失败；
     * 若 token 解不开，服务端会返回一张**空白却带章**的模板 PDF——那种文件绝不能落到用户手里
     * （实测 `dysj` 传垃圾串时返回 60KB 的空白模板，0 门课，照样有签章注释）。
     *
     * @param terms 目标学期（空列表 = 全部学期）。顺序不影响结果，服务端按学期升序排版。
     */
    suspend fun export(terms: List<String>, cookie: String?): ByteArray {
        val page = requestList(terms = terms, page = 1, cookie = cookie)
        if (page.total <= 0) {
            throw if (terms.isEmpty()) {
                TranscriptException.NoContent("教务里没有可导出的成绩")
            } else {
                TranscriptException.NoContent("所选学期没有成绩，换个学期再导")
            }
        }
        if (page.pagePri.isBlank()) {
            throw TranscriptException.Protocol("列表接口没有返回导出令牌")
        }
        val response = post(PtworkTranscript.BASE + PtworkTranscript.PRINT_PATH,
            PtworkTranscript.printForm(page.pagePri), cookie)
        if (PtworkTranscript.isPdf(response.bytes)) return response.bytes
        throw PtworkTranscript.classifyNonPdf(response.text())
    }

    private suspend fun requestList(
        terms: List<String>,
        page: Int,
        cookie: String?,
    ): TranscriptPage {
        val response = post(
            PtworkTranscript.BASE + PtworkTranscript.LIST_PATH,
            PtworkTranscript.listForm(terms, page),
            cookie,
        )
        val text = response.text()
        // 会话失效时服务端是 302 到登录页，跟随之后拿到 200 的登录页 HTML；
        // 先按内容判定，再交给 JSON 解析（否则会被报成「不是 JSON」这种误导性错误）
        if (PtworkTranscript.looksLikeLoginPage(text)) throw TranscriptException.SessionExpired()
        return PtworkTranscript.parsePage(text)
    }

    private suspend fun post(
        url: String,
        form: List<Pair<String, String>>,
        cookie: String?,
    ): TranscriptResponse = try {
        transport.post(url, form, cookie)
    } catch (e: IOException) {
        throw TranscriptException.Network(e)
    }
}

/**
 * OkHttp 实现。
 *
 * IPv4 优先的 DNS 复用 [JwHttpSession.ipv4First] 的排序规则：校园域名同时有 A 与 AAAA 记录，
 * 校园 IPv6 在移动网络下实测是黑洞，等 TCP 超时要多花十几到三十秒。
 * 只复用那条纯逻辑、不共用对方的 OkHttpClient：教务那边是「一次检测用完即弃」的短命实例，
 * 这里是随进程常驻的导出通道，两者的超时与重定向策略本来就不同。
 */
class OkHttpTranscriptTransport : TranscriptTransport {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(JwIpv4FirstDns)
        .connectTimeout(20, TimeUnit.SECONDS)
        // 出单在服务端要跑一次 Excel → PDF 转换，PDF 约 350KB，给足读超时
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private object JwIpv4FirstDns : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> =
            JwHttpSession.ipv4First(Dns.SYSTEM.lookup(hostname))
    }

    override suspend fun post(
        url: String,
        form: List<Pair<String, String>>,
        cookie: String?,
    ): TranscriptResponse = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply {
            form.forEach { (name, value) -> add(name, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
            .post(body)
            .build()
        // 让 OkHttp 自己跟 302（登录页判定靠正文，不靠 URL；见 TranscriptClient.requestList）
        client.newCall(request).execute().use { response ->
            TranscriptResponse(response.code, response.body?.bytes() ?: ByteArray(0))
        }
    }

    private companion object {
        const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}

/** 从 WebView 的 CookieManager 读签章系统的会话 Cookie。 */
object TranscriptCookies {

    /**
     * 返回可直接当请求头用的 `sid=…`，未授权时为 null。
     *
     * HttpOnly 的 Cookie 这里也读得到：`CookieManager.getCookie` 给的是**该 URL 会发出的
     * Cookie 头原文**，不受脚本可见性限制。读不到只有一种解释——授权窗口还没走过一遍。
     */
    fun sessionHeader(): String? = runCatching {
        android.webkit.CookieManager.getInstance().getCookie(PtworkTranscript.COOKIE_URL)
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
