package edu.jxslu.schedule.data.jw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 签章系统（`jwxyxx.juwp.edu.cn`）成绩单导出的契约与纯逻辑（DESIGN §4.25）。
 *
 * 这套东西不在强智教务里：门户应用「电子签章成绩单」指向的是**金格签章管理系统**，
 * 成绩单 PDF 由它生成并加盖教务处电子签章。链路（2026-09-24 实测）：
 *
 * ```
 * CAS 单点（service=http://jwxyxx.juwp.edu.cn/ptwork/cas）
 *   → 落地 mainIndex，拿到该域唯一 cookie `sid`
 *   → POST /ptwork/DzqzController/ddqzcjList   取 pagePri（一个不透明的加密条件串）
 *   → POST /ptwork/DzqzController/printStartCj 回传 PDF 字节流
 * ```
 *
 * 三条实测口径，实现时不许按直觉改（改错了会出空白成绩单，见 [classifyNonPdf]）：
 *
 * 1. **`dysj`（= pagePri）才是权威条件，`xnxq` 被服务端忽略**。故意制造不一致
 *    （`dysj` 对应 2025-2026-2、`xnxq` 传 2024-2025-1）导出，出来的仍是 2025-2026-2 的内容。
 *    所以流程只能是「先列后导」，不能用学期号直接拼请求。
 * 2. **列表分页不影响出单**。列表只翻出 15 行时，PDF 里照样是全部 16 门。pagePri 编码的是
 *    查询条件而非当页数据，故导出前只需一次列表请求，不必翻页。
 * 3. **`pagePri` 存在不等于有数据**。无成绩的学期照样返回 344 字节的 pagePri，导出会失败；
 *    合法但解不开的 token 更糟——服务端返回一张**空白却带章**的模板 PDF。因此导出前必须用
 *    `total` 当闸门（见 [TranscriptClient.export]）。
 *
 * 另一个坑不在本文件里：签章系统只有 HTTP 明文（443 实测连接超时），CAS 票据与成绩单都走
 * 明文回传。学校就这么部署的，App 端无法兜底，只能在导出页写明。
 */
object PtworkTranscript {

    /** 签章系统根地址（只有 HTTP；`NETWORK_SECURITY_CONFIG` 已放行 `juwp.edu.cn` 明文）。 */
    const val BASE = "http://jwxyxx.juwp.edu.cn"

    /** 签章系统域，用于判定「授权是否真的完成」。 */
    const val HOST = "jwxyxx.juwp.edu.cn"

    /** CAS 回跳地址（**不要**带端口，与教务 `sso.jsp` 同理）。 */
    const val CAS_SERVICE = "$BASE/ptwork/cas"

    /** 授权窗口的起始地址：统一认证登录页，service 指向签章系统。 */
    val CAS_ENTRY: String =
        JwUrls.CAS_BASE + "/cas/login?service=" + URLEncoder.encode(CAS_SERVICE, Charsets.UTF_8.name())

    const val LIST_PATH = "/ptwork/DzqzController/ddqzcjList"
    const val PRINT_PATH = "/ptwork/DzqzController/printStartCj"

    /** 登录页路径片段；落到这里说明 `sid` 无效（授权没完成或已过期）。 */
    const val LOGIN_PAGE_MARK = "LoginPage"

    /**
     * 服务端固定 15 行一页。`limit` 参数实测被忽略（传 200、500 都只回 15 行），
     * 翻页只能按 `pages` 递增 `page`。
     */
    const val PAGE_SIZE = 15

    /** 成绩方式：服务端只开放了 1（「最好成绩」在页面里被注释掉了）。 */
    const val CJFS_ALL = "1"

    /** 翻页上限：66 行实测 5 页，给到 40 页足够，同时挡住 pages 异常时的死循环。 */
    const val MAX_PAGES = 40

    /** 无内容时服务端的原话（HTML 里的 `parent.wzalert('未发现打印内容')`）。 */
    private const val NO_CONTENT_TEXT = "未发现打印内容"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val alertRe = Regex("""wzalert\(\s*'([^']*)'\s*\)""")

    private val stampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    /** 列表接口的表单。`xnxq` 多值用重复字段表达；空列表 = 全部学期（服务端按空串处理）。 */
    fun listForm(terms: List<String>, page: Int): List<Pair<String, String>> {
        val form = mutableListOf(
            "page" to page.toString(),
            "limit" to PAGE_SIZE.toString(),
            "sort" to "",
            "order" to "",
            "dysj" to "",
            "dytype" to "",
            "cjfs" to CJFS_ALL,
        )
        if (terms.isEmpty()) {
            form += "xnxq" to ""
        } else {
            terms.forEach { form += "xnxq" to it }
        }
        return form
    }

    /**
     * 导出接口的表单。**不带 `xnxq`**：实测条件全在 `dysj` 里，多传一份学期只会有两种结果
     * （被忽略，或与 token 冲突），没有收益。
     */
    fun printForm(pagePri: String): List<Pair<String, String>> = listOf(
        "dysj" to pagePri,
        "dytype" to "1",
        "cjfs" to CJFS_ALL,
    )

    /** 解析 `ddqzcjList` 的应答 `[1, { total, pages, pagePri, list: [...] }]`。 */
    fun parsePage(body: String): TranscriptPage {
        val root = try {
            json.parseToJsonElement(body)
        } catch (_: Exception) {
            throw TranscriptException.Protocol("列表接口返回的不是 JSON（页面结构可能已变）")
        }
        val arr = root as? JsonArray
            ?: throw TranscriptException.Protocol("列表接口返回结构已变")
        if (arr.size < 2) throw TranscriptException.Protocol("列表接口返回结构已变")
        // 强智那边返回的是字符串 "1"，这里实测是数字 1；两种都认，只比较字面量。
        // 非 "1" 时没有可靠的语义可推断（实测只见过成功态），一律按协议异常报出 code，
        // 不替服务端编造「0 = 会话失效」这类规则
        val flag = (arr[0] as? JsonPrimitive)?.content?.trim()
        if (flag != "1") throw TranscriptException.Protocol("列表接口返回 code=$flag")
        val payload = arr[1] as? JsonObject
            ?: throw TranscriptException.Protocol("列表接口返回结构已变")
        val rows = (payload["list"] as? JsonArray).orEmpty().mapNotNull { parseRow(it) }
        val total = payload["total"].asInt() ?: rows.size
        return TranscriptPage(
            total = total,
            pages = payload["pages"].asInt() ?: pagesOf(total),
            pagePri = payload["pagePri"].asString(),
            rows = rows,
        )
    }

    private fun parseRow(el: JsonElement): TranscriptCourse? {
        val obj = el as? JsonObject ?: return null
        val term = obj["XNXQID"].asString()
        val name = obj["KCMC"].asString()
        if (term.isBlank() && name.isBlank()) return null
        return TranscriptCourse(
            term = term,
            name = name,
            credit = obj["XF"].asDouble() ?: 0.0,
            scoreStr = obj["ZCJSTR"].asString().ifBlank { obj["ZCJ"].asString() },
        )
    }

    /**
     * 从列表行归并出「有成绩的学期 + 门数」，按学期倒序（最新在前）。
     * 学期号形如 `2025-2026-2`，字典序倒排与时间倒排一致。
     */
    fun termsFrom(rows: List<TranscriptCourse>): List<TranscriptTerm> =
        rows.filter { it.term.isNotBlank() }
            .groupingBy { it.term }
            .eachCount()
            .map { (term, count) -> TranscriptTerm(term, count) }
            .sortedByDescending { it.term }

    /** 总行数 → 页数（`total=0` 也算一页：请求仍要发一次才拿得到 token）。 */
    fun pagesOf(total: Int): Int =
        if (total <= 0) 1 else (total + PAGE_SIZE - 1) / PAGE_SIZE

    /** PDF 魔数。导出应答不是 PDF 时一律按失败处理，绝不落盘。 */
    fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == '%'.code.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()

    /** 取 `parent.wzalert('…')` 里的文案（服务端的失败提示就藏在这段脚本里）。 */
    fun alertMessage(text: String): String? =
        alertRe.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 签章系统登录页的特征。用它判定「授权没完成」。
     *
     * 不按 URL 判定：`sid` 失效时列表接口是 302 到 `/ptwork/LoginPage`，跟随后拿到 200 的
     * 登录页 HTML；而正文里的 `name="dsing"`（登录表单的隐藏字段）与「立即登录」按钮
     * 是这页独有的，用它比 URL 稳（跟随时原始 URL 会丢）。
     */
    fun looksLikeLoginPage(text: String): Boolean =
        "name=\"dsing\"" in text || "立即登录" in text

    /** 非 PDF 应答的失败归类。 */
    fun classifyNonPdf(text: String): TranscriptException {
        val alert = alertMessage(text)
        return when {
            alert != null && NO_CONTENT_TEXT in alert ->
                TranscriptException.NoContent("所选学期没有成绩，换个学期再导")
            alert != null -> TranscriptException.NoContent(alert)
            looksLikeLoginPage(text) -> TranscriptException.SessionExpired()
            text.isBlank() -> TranscriptException.Protocol("服务端返回了空内容")
            else -> TranscriptException.Protocol("服务端返回的不是成绩单（页面结构可能已变）")
        }
    }

    /**
     * 授权是否已落地：停在签章系统域、且不是它自己的登录页。
     *
     * 走到这里就说明 CAS 换票成功（签章系统拿到 ticket 后写下 `sid` 并跳到 `mainIndex`），
     * 后面的接口调用就不再需要 WebView。
     */
    fun isAuthorizedLanding(url: String?): Boolean =
        JwUrls.hostOf(url) == HOST && url?.contains(LOGIN_PAGE_MARK) != true

    /** Cookie 读取用的地址（[android.webkit.CookieManager.getCookie] 要一个 URL 当作用域）。 */
    const val COOKIE_URL = "$BASE/"

    /**
     * 本地文件名。学期标签取**最新的那个**（调用方按倒序传），多学期写成「…等N个学期」，
     * 末尾带导出时刻，避免同一学期重复导出互相覆盖。
     */
    fun defaultFileName(
        terms: List<String>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val label = when {
            terms.isEmpty() -> "全部学期"
            terms.size == 1 -> terms.first()
            else -> "${terms.first()}等${terms.size}个学期"
        }
        val stamp = Instant.ofEpochMilli(nowMillis).atZone(zone).format(stampFormatter)
        return "成绩单-${sanitizeFileLabel(label)}-$stamp.pdf"
    }

    /** 去掉文件名里的非法字符（学期号本来干净，防的是将来标签形态变化）。 */
    fun sanitizeFileLabel(label: String): String =
        label.map { if (it in ILLEGAL_FILE_CHARS) '_' else it }.joinToString("").trim()

    private const val ILLEGAL_FILE_CHARS = "\\/:*?\"<>|"
}

/** 一个有成绩的学期与其门数（学期选择列表的条目）。 */
data class TranscriptTerm(val term: String, val courseCount: Int)

/** 列表接口返回的一行课程成绩（只取展示所需的字段）。 */
data class TranscriptCourse(
    val term: String,
    val name: String,
    val credit: Double,
    val scoreStr: String,
)

/** `ddqzcjList` 的一页。 */
data class TranscriptPage(
    val total: Int,
    val pages: Int,
    val pagePri: String,
    val rows: List<TranscriptCourse>,
)

/**
 * 导出链路的失败分类。分开是因为用户该做的事不同：
 * [SessionExpired] 要去重新授权，[NoContent] 该换个学期，[Network] 查网络，[Protocol] 是页面变了。
 */
sealed class TranscriptException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class SessionExpired : TranscriptException("需要重新登录学校统一认证")

    class NoContent(message: String) : TranscriptException(message)

    class Network(cause: Throwable) : TranscriptException(cause.message ?: "网络错误", cause)

    class Protocol(message: String) : TranscriptException(message)
}

/** `JsonElement` 的安全取值：null、JsonNull、类型不符一律给 null，不抛异常。 */
private fun JsonElement?.asPrimitive(): JsonPrimitive? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

private fun JsonElement?.asString(): String = asPrimitive()?.content?.trim().orEmpty()

private fun JsonElement?.asDouble(): Double? = asPrimitive()?.content?.trim()?.toDoubleOrNull()

private fun JsonElement?.asInt(): Int? = asDouble()?.toInt()
