package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.ScholarDimension
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
 * 教务学期下拉的一个选项。理论课表页 `select#xnxq01id` 的 options 会随抽取 JSON 一起
 * 回来（`terms`），导入确认弹窗用它渲染「数据学期」下拉——切换学期后 App 带着该学期
 * 参数重载课表页重新解析，不必回 WebView 里手动换学期。
 *
 * [value] 是 `xnxq01id` 参数值，[text] 是显示文本（教务两者一般同值，取 text 兜底）。
 */
data class TermOption(
    val value: String,
    val text: String,
)

/**
 * 从注入 JSON 读顶层 `terms`（学期下拉全部选项）。宽松解析：缺失、结构不符、
 * value 不满足 [JwUrls.TERM_PATTERN] 白名单的条目一律丢弃——value 会被拼进重载 URL，
 * 白名单与 [JwUrls.labScheduleUrl] 同一把尺子。任何异常都降级为空列表（弹窗退回纯文本）。
 */
fun extractTermOptions(jsonText: String): List<TermOption> = try {
    val root = termExtractJson.parseToJsonElement(jsonText).jsonObject
    val arr = root["terms"]?.jsonArray ?: return emptyList()
    arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val v = o["v"]?.jsonPrimitive?.content?.trim().orEmpty()
        val t = o["t"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (v.isEmpty()) null else TermOption(v, t.ifEmpty { v })
    }.filter { JwUrls.TERM_PATTERN.matches(it.value) }
} catch (_: Exception) {
    emptyList()
}

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
 * 抽取结果的**页面形态**：回答「这张课表本来就空」还是「拿到的不是这张课表」。
 *
 * 一键导入必须区分这两者。实验课表在多数前期学期就是空的，把它当失败会让用户在
 * 没排实验课的学期根本导不进来；反过来，页面结构变了却当成"空课表"，会静默地
 * 只导一半数据。识别结果弹窗里的分项条数就是给用户的第二道防线。
 *
 * [cells] 是理论课表页 `td[name=kbDataTd]` 的个数（整屏网格恒在，实测 41），
 * [container] 是实验课表页的课表 tbody 是否找到。两者由各自的 `EXTRACT_JS` 现算；
 * 旧脚本不带这两个字段时按 0/false 处理——拿不到形态信息时宁可让弹窗多提示一句，
 * 也不假装识别成功。
 */
data class ExtractMeta(
    val ok: Boolean,
    val cells: Int,
    val container: Boolean,
)

fun readExtractMeta(jsonText: String): ExtractMeta = try {
    val root = termExtractJson.parseToJsonElement(jsonText).jsonObject
    ExtractMeta(
        ok = root["ok"]?.let { runCatching { it.jsonPrimitive.content.toBoolean() }.getOrNull() } != false,
        cells = root["cells"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        container = root["container"]?.jsonPrimitive?.content?.toBoolean() ?: false,
    )
} catch (_: Exception) {
    ExtractMeta(ok = false, cells = 0, container = false)
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
     * 学期号白名单（如 2026-2027-1）。学期号会被拼进注入 JS 的单引号字符串与页面 URL，
     * 非此格式一律拒绝，既防脏值落库，也从根上杜绝引号注入（与考试导入同一把尺子）。
     */
    val TERM_PATTERN = Regex("""\d{4}-\d{4}-\d""")

    /**
     * 实验课表页 + 指定学期。学期为空或格式不合法时退化为裸地址（页面按教务默认学期渲染）。
     *
     * 为什么要带学期：一键导入连着抽两张表，两页各有一套默认学期，默认学期不一致时
     * 合并出来的课表会跨学期。带上理论页读到的学期号，两页看的就是同一份数据。
     */
    fun labScheduleUrl(term: String?): String =
        if (term != null && TERM_PATTERN.matches(term)) "$LAB_SCHEDULE?xnxq01id=$term"
        else LAB_SCHEDULE

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
     * 选课结果查询（我的 → 学习 → 选课，DESIGN §4.35）。
     *
     * 菜单 data-id = `NEW_XSD_PYGL_XKGL_XSXKJGCX`，「选课日志」是壳页
     * `/jsxsd/xkgl/loadXsxkjgList?lx=xkrz`（学期下拉在这里），数据接口加 `&type=list`。
     * **不带 .do**；带 .do 的同名地址回 no-open 页。
     */
    const val SELECTION_LIST_SHELL = "$XSD_BASE/jsxsd/xkgl/loadXsxkjgList?lx=xkrz"

    /** 选课结果数据接口（同上，加 `&type=list` 与分页参数后使用，见 `SelectionParser.listUrl`）。 */
    const val SELECTION_LIST_API = "$XSD_BASE/jsxsd/xkgl/loadXsxkjgList"

    /**
     * 学生选课中心（菜单 data-id = `NEW_XSD_PYGL_XKGL_NXSXKZX`）。
     * 页面上是轮次列表：`data-url` 指向 [SELECTION_ROUNDS_API]，
     * 「进入选课」跳 `/jsxsd/xsxk/newXsxkzx?jx0502zbid=<轮次id>`。
     */
    const val SELECTION_CENTER = "$XSD_BASE/jsxsd/xsxk/xklc_list"

    /** 选课轮次数据接口（学生选课中心表格的 `data-url`）。 */
    const val SELECTION_ROUNDS_API = "$XSD_BASE/jsxsd/xsxk/xklc_list_data"

    /**
     * 轮次 id（教务 `jx0502zbid`）白名单：会被拼进 URL，非此格式一律拒绝——
     * 与 [TERM_PATTERN] 同一把尺子（宁可退回列表页，也不拼一个可能出错的地址）。
     * 真实格式未实测（非选课期无轮次样本），故放宽到「字母数字下划线短横，1–64 位」。
     */
    val ROUND_ID_PATTERN = Regex("""[A-Za-z0-9_-]{1,64}""")

    /**
     * 「进入选课」页（轮次页 JS `jrxk` 的落点）：
     * `newXsxkzx?jx0502zbid=<轮次id>&isallsc=`；[preview] = true 走预览页 `yxxsxk_index`。
     *
     * 未实测：教务从轮次列表进选课时会先 POST `mzlist.do`（免责声明检查）再跳这里，
     * 直接进可能与那一步有关。真进不去就退回选课中心列表（窗口底部的「选课中心」按钮）。
     * 轮次 id 不合法返回 null，调用方回退到 [SELECTION_CENTER]。
     */
    fun selectionRoundUrl(roundId: String, preview: Boolean = false): String? {
        if (!ROUND_ID_PATTERN.matches(roundId)) return null
        return if (preview) {
            "$XSD_BASE/jsxsd/xsxk/yxxsxk_index?jx0502zbid=$roundId"
        } else {
            "$XSD_BASE/jsxsd/xsxk/newXsxkzx?jx0502zbid=$roundId&isallsc="
        }
    }

    /**
     * 学业完成情况壳页（学籍成绩 → 学籍管理 → 学业达成情况 → 学业完成情况，DESIGN §4.29）。
     *
     * 菜单 data-id = `NEW_XSD_XJCJ_XJGL_XXWCQKTX`。壳页只有四个 tab 的 tab 头，
     * 真正的数据在下面四个 `xxwcqkOn*.do` 里。
     */
    const val SCHOLAR_PROGRESS = "$XSD_BASE/jsxsd/xxwcqk/xxwcqk_idxOntx.do"

    /**
     * 学业完成情况的四个数据页（DESIGN §4.29）。
     *
     * `isdb=0` 是「本人视图」标志；教务的「对比学业情况」用 `isdb=1`，那是教职工查
     * 院系/年级/专业的入口，学生本人用不到，也不要去抓（页面要求先选院系，抓了也是空）。
     *
     * 返回的是 **HTML 而不是 JSON**，解析见 [ScholarProgressParser]。
     */
    fun scholarUrl(dimension: ScholarDimension): String = when (dimension) {
        ScholarDimension.System -> "$XSD_BASE/jsxsd/xxwcqk/xxwcqkOnkctx.do?isdb=0"
        ScholarDimension.Nature -> "$XSD_BASE/jsxsd/xxwcqk/xxwcqkOnkclb.do?isdb=0"
        ScholarDimension.Attribute -> "$XSD_BASE/jsxsd/xxwcqk/xxwcqkOnkcxz.do?isdb=0"
        ScholarDimension.Elective -> "$XSD_BASE/jsxsd/xxwcqk/xxwcqkOnszklb.do?isdb=0"
    }

    /**
     * 学籍卡片查看（学籍毕业 → 学籍管理 → 学籍卡片查看）。
     * 正文有「姓名 / 班级 / 学号」明文标签与结构化 form，可正则直读；
     * 个人资料头像不落盘——「我的」账号条只用向量图标。
     */
    const val STUDENT_CARD = "$XSD_BASE/jsxsd/grxx/xsxx"

    /**
     * 学生教材确认壳页（教材管理 → 学生教材确认，DESIGN §4.31）。
     * 菜单 data-id = `NEW_XSD_PYGL_NJCGL_XSJCQR`，data-src 指向本壳页（layui 表格），
     * 数据接口 [TEXTBOOK_LIST_API] 的学期参数名是 `xnxqid`（同考试页，不是课表的 xnxq01id）。
     */
    const val TEXTBOOK_QUERY = "$XSD_BASE/jsxsd/nxsjc/jccx"

    /** 教材数据接口（不带 .do，与考试/成绩同一套 layui 形态；OkHttp 直抓用）。 */
    const val TEXTBOOK_LIST_API = "$XSD_BASE/jsxsd/nxsjc/xsjcqr"

    /** 主页（SSO 落地） */
    const val STUDENT_HOME = "$XSD_BASE/jsxsd/framework/xsMainV.htmlx"

    fun isScheduleUrl(url: String?): Boolean =
        url != null && "xskb_list.do" in url && "viweType=0" in url

    fun isLabScheduleUrl(url: String?): Boolean =
        url != null && "syjx/toXskb" in url

    fun isExamQueryUrl(url: String?): Boolean =
        url != null && "xsks/xsksap_query" in url

    /**
     * 成绩查询表单页（学籍成绩 → 我的成绩 → 课程成绩查询）。
     *
     * 成绩页不属于 [JwSchedulePage]：课表页那套 `pageKind` 会驱动注入课表适配样式
     * 与自动导入触发，成绩页混进去会被当成课表页处理。
     */
    fun isScoreQueryUrl(url: String?): Boolean =
        url != null && "kscj/cjcx_frm" in url

    /**
     * 是否学生选课中心（DESIGN §4.35，含带参数的壳页形态）。
     *
     * **不要**把 `/jsxsd/xsxk/` 当判据：轮次页、进选课页（`newXsxkzx`）、预览页
     * （`yxxsxk_index`）都在这个前缀下，语义各不相同。
     */
    fun isSelectionCenterUrl(url: String?): Boolean =
        url != null && "xsxk/xklc_list" in url

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
