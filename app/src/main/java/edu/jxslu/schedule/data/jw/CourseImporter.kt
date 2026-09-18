package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.Course

/**
 * 教务导入抽象。M1/本阶段：QiangzhiJsxdImporter。
 */
interface CourseImporter {
    val id: String
    val displayName: String
}

sealed interface ImportParseResult {
    data class Success(val courses: List<Course>) : ImportParseResult
    data class Failure(val message: String) : ImportParseResult
}

/**
 * 教务里可导入的课表页面。
 *
 * 根因：理论课表与实验课表是两套完全不同的页面（结构、字段、周次来源都不同），
 * 导入时必须先知道当前在哪一页，才能选对解析器——让用户自己选容易选错，
 * 而「猜解析器」在拿到空结果时也无法给出准确提示。
 */
enum class JwSchedulePage { Theory, Lab, None }

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

    /** 主页（SSO 落地） */
    const val STUDENT_HOME = "$XSD_BASE/jsxsd/framework/xsMainV.htmlx"

    fun isScheduleUrl(url: String?): Boolean =
        url != null && "xskb_list.do" in url && "viweType=0" in url

    fun isLabScheduleUrl(url: String?): Boolean =
        url != null && "syjx/toXskb" in url

    /** 当前页属于哪张课表；导入时据此选择解析器。 */
    fun schedulePageKind(url: String?): JwSchedulePage = when {
        isLabScheduleUrl(url) -> JwSchedulePage.Lab
        isScheduleUrl(url) -> JwSchedulePage.Theory
        else -> JwSchedulePage.None
    }

    fun isJwHost(url: String?): Boolean =
        url != null && "jiaowu.juwp.edu.cn" in url

    fun isCasHost(url: String?): Boolean =
        url != null && "eapp2.juwp.edu.cn" in url

    fun isPortalHost(url: String?): Boolean =
        url != null && "portal.juwp.edu.cn" in url
}

class QiangzhiJsxdImporter : CourseImporter {
    override val id: String = "qiangzhi_jsxd"
    override val displayName: String = "强智正方课表（江西水利电力大学）"
}
