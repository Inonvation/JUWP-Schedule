package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.Course
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 教务导入抽象。M1/本阶段：QiangzhiJsxdImporter。
 */
interface CourseImporter {
    val id: String
    val displayName: String
}

sealed interface ImportParseResult {
    /**
     * [term] 是课表页学期下拉的当前选中项（如 2026-2027-1），即本次解析数据的实际学期；
     * 用于导入确认弹窗展示。页面异常没有下拉时为 null，不阻塞导入。
     * [note] 是需要用户在确认前知情的补充说明（如历史学期考试的估算周次口径），null = 无。
     */
    data class Success(
        val courses: List<Course>,
        val term: String? = null,
        val note: String? = null,
    ) : ImportParseResult
    data class Failure(val message: String) : ImportParseResult
}

/** 只用于读注入 JSON 的顶层 term 字段；宽松解析，任何异常都降级为 null。 */
private val termExtractJson = Json { ignoreUnknownKeys = true }

/**
 * 从注入 JS 返回的 JSON 里读顶层 `term`（两个课表解析器共用）。
 * 缺失、非字符串或空白一律返回 null——学期只用于展示，任何异常都不能挡住导入。
 */
fun extractTermField(jsonText: String): String? = try {
    termExtractJson.parseToJsonElement(jsonText).jsonObject["term"]
        ?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
} catch (_: Exception) {
    null
}

/**
 * 教务里可导入的课表页面。
 *
 * 根因：理论课表与实验课表是两套完全不同的页面（结构、字段、周次来源都不同），
 * 导入时必须先知道当前在哪一页，才能选对解析器——让用户自己选容易选错，
 * 而「猜解析器」在拿到空结果时也无法给出准确提示。
 *
 * [Exam]：考试安排壳页（xsksap_query），数据走同源 fetch JSON 接口（DESIGN §4.14）。
 */
enum class JwSchedulePage { Theory, Lab, Exam, None }

object JwUrls {
    /** 统一身份认证（学校官网登录） */
    const val CAS_BASE = "https://eapp2.juwp.edu.cn:9443"

    /** 教务 SSO 回跳（不要带 :81/:8080，否则 500） */
    const val JW_SSO_SERVICE = "http://jiaowu.juwp.edu.cn/sso.jsp"

    /**
     * 默认入口：学校统一身份认证 → 登录后自动进教务。
     * service 已 URL 编码为 http://jiaowu.juwp.edu.cn/sso.jsp
     */
    const val ENTRY =
        "$CAS_BASE/cas/login?service=http%3A%2F%2Fjiaowu.juwp.edu.cn%2Fsso.jsp"

    /**
     * 预热入口：先落教务域写 `bzb_njw`，再由页面自带 JS 跳统一认证（目标与 [ENTRY] 完全一致）。
     *
     * 根因（2026-09-18 手机实测）：`sso.jsp?ticket=` 的票据校验依赖教务域 cookie `bzb_njw`
     * （`scripts/README.md` §3：「预热 `:81/` 必须，缺了教务不认」）。WebView 若从未访问过
     * 教务域，CAS 回跳的第一次 `sso.jsp?ticket=` 会直接 **500**；而该 500 响应会顺手写下
     * `bzb_njw` —— 这就是「第一次必挂、过一会刷新又好」的全部原因。
     * 从本入口进可无条件先写 cookie，消除"第一次必挂"。
     */
    const val SSO_WARMUP = "http://jiaowu.juwp.edu.cn/sso.jsp"

    /** 强智独立登录（教务单独账号，非统一认证）备用 */
    const val QZ_DIRECT_ENTRY = "http://jiaowu.juwp.edu.cn/"

    /** HTTPS 备用（:81） */
    const val HTTPS_ENTRY = "https://jiaowu.juwp.edu.cn:81/"

    /** SSO 成功后学生端（端口 8080，HTTP） */
    const val XSD_BASE = "http://jiaowu.juwp.edu.cn:8080"

    /** 学期理论课表（需已登录；页面为 shell，真正数据在 iframe viweType=0） */
    const val SCHEDULE_LIST = "$XSD_BASE/jsxsd/xskb/xskb_list.do?viweType=0"

    /**
     * 实验课表查询（实践实验 → 实验课表查询，菜单 data-id = NEW_XSD_PYGL_WDKB_SYKBCX）。
     * 页面按「周次 × 节次」两级分组，解析见 [SyjxScheduleParser]。
     */
    const val LAB_SCHEDULE = "$XSD_BASE/jsxsd/syjx/toXskb.do"

    /**
     * 考试安排查询壳页（考试报名 → 我的考试 → 考试安排查询，DESIGN §4.14）。
     * 数据接口 `/jsxsd/xsks/xsksap_list`（不带 .do），由注入 JS 同源 fetch。
     */
    const val EXAM_QUERY = "$XSD_BASE/jsxsd/xsks/xsksap_query"

    /** 考试安排数据接口（供注入 fetch；带 .do 的同名地址是 no-open 开关页）。 */
    const val EXAM_LIST_API = "$XSD_BASE/jsxsd/xsks/xsksap_list"

    /**
     * 成绩查询表单页（学籍成绩 → 我的成绩 → 课程成绩查询，DESIGN §4.15）。
     * 数据接口 `/jsxsd/kscj/cjcx_list`（不带 .do），由注入 JS 同源 fetch。
     */
    const val SCORE_FRM = "$XSD_BASE/jsxsd/kscj/cjcx_frm"

    /** 成绩数据接口。 */
    const val SCORE_LIST_API = "$XSD_BASE/jsxsd/kscj/cjcx_list"

    /**
     * 学籍卡片查看（学籍毕业 → 学籍管理 → 学籍卡片查看）。
     * 正文有「姓名 / 班级 / 学号」明文标签与结构化 form，可正则直读；
     * 个人资料头像不落盘——「我的」账号条只用向量图标。
     */
    const val STUDENT_CARD = "$XSD_BASE/jsxsd/grxx/xsxx"

    /** 主页（SSO 落地） */
    const val STUDENT_HOME = "$XSD_BASE/jsxsd/framework/xsMainV.htmlx"

    fun isScheduleUrl(url: String?): Boolean =
        url != null && "xskb_list.do" in url && "viweType=0" in url

    fun isLabScheduleUrl(url: String?): Boolean =
        url != null && "syjx/toXskb" in url

    fun isExamQueryUrl(url: String?): Boolean =
        url != null && "xsks/xsksap_query" in url

    /** 当前页属于哪张课表；导入时据此选择解析器。 */
    fun schedulePageKind(url: String?): JwSchedulePage = when {
        isExamQueryUrl(url) -> JwSchedulePage.Exam
        isLabScheduleUrl(url) -> JwSchedulePage.Lab
        isScheduleUrl(url) -> JwSchedulePage.Theory
        else -> JwSchedulePage.None
    }

    /**
     * 允许放行证书错误的域白名单。学校 HTTPS 端点的证书链不被系统 WebView 信任，
     * 名单内放行；名单外一律取消——无条件放行等于关掉整个 WebView 的传输层校验，
     * 而统一认证登录表单就在这个 WebView 里，不能为图省事全放。
     */
    val TRUSTED_SSL_HOSTS = setOf(
        "eapp2.juwp.edu.cn",  // 统一认证
        "jiaowu.juwp.edu.cn", // 教务（:81 与 :8080 同域）
        "portal.juwp.edu.cn", // 门户
    )

    /**
     * 取 URL 的 host 段（去掉协议、端口、路径、查询与 fragment）。
     *
     * 根因：此前三个 `isXxxHost` 都是「整串 `in` 子串匹配」，对
     * `ENTRY`（`https://eapp2…/cas/login?service=http%3A%2F%2Fjiaowu.juwp.edu.cn%2Fsso.jsp`）
     * 这种 **host 与 query 里各有一个域名** 的 URL 会同时命中两个判定——
     * CAS 页被当成教务域，导致会话探测在用户正登录时误报「会话失效」。
     * 这类漏洞来自「整串匹配」而非语义，故改为解析 host 后精确比较。
     */
    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        return afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .lowercase()
            .ifBlank { null }
    }

    /** 是否教务域（`jiaowu.juwp.edu.cn`，`:` 与 `:8080` 两套部署同域）。 */
    fun isJwHost(url: String?): Boolean = hostOf(url) == "jiaowu.juwp.edu.cn"

    /** 是否统一认证域（`eapp2.juwp.edu.cn:9443`）。 */
    fun isCasHost(url: String?): Boolean = hostOf(url) == "eapp2.juwp.edu.cn"

    /** 是否门户域（`portal.juwp.edu.cn`）。 */
    fun isPortalHost(url: String?): Boolean =
        hostOf(url)?.let { it == "portal.juwp.edu.cn" || it.endsWith(".portal.juwp.edu.cn") } == true
}

class QiangzhiJsxdImporter : CourseImporter {
    override val id: String = "qiangzhi_jsxd"
    override val displayName: String = "强智正方课表（江西水利电力大学）"
}
