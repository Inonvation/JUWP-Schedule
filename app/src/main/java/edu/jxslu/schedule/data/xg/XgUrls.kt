package edu.jxslu.schedule.data.xg

import edu.jxslu.schedule.data.jw.JwUrls

/**
 * 学工系统里的一个表单应用（DESIGN §4.26）。
 *
 * 报修、请假这类"填一张单子提交走流程"的东西，在学工里都是超星 office 表单引擎的
 * 一个审批表单，区别只有两个标识：表单页标识与审批应用 id。
 */
data class XgForm(
    /** 稳定标识（路由 extra、入口列表的 key）。改它等于换一个应用。 */
    val id: String,
    /** 窗口与入口的标题。 */
    val title: String,
    /** 入口副标题：这单子能干什么。 */
    val subtitle: String,
    /** 表单页标识，服务端下发。
     *
     *  别和 `uuid` 混：`uuidUtils.createUUID()` 生成的随机串第 13 位固定是 `4`，
     * 这里的是 MD5 形态，第 13 位不定。两者不是一类东西。 */
    private val pageEnc: String,
    /** 审批应用 id（URL 里 `aprvAppId` 与 `id` 同值）。 */
    private val appId: Int,
) {
    /**
     * 申请页直达地址。
     *
     * **不带 `uuid`**：申请页 URL 里常见 `uuid=…`，但引擎对它的处理只有
     * `urlParams.uuid = e.uuid || ""`（缺省空串），提交时用的是现场生成的
     * `e.uuid = createUUID()`。URL 上那一个没有读取点，写进来等于把一个
     * 每次点开都不同的随机值当成表单标识。
     */
    val applyUrl: String = buildString {
        append(XgUrls.OFFICE)
        append("apps/forms/mobile/apply.html")
        append("?formType=1&pageEnc=")
        append(pageEnc)
        append("&aprvAppId=")
        append(appId)
        append("&id=")
        append(appId)
        append("&isManager=false")
    }
}

/**
 * 学工系统（超星智慧学工）的地址、表单清单与纯判定（DESIGN §4.26）。
 *
 * 与教务 [JwUrls] 分开放：两个系统除了共用一套统一身份认证，其余没有关系——
 * 域不同，会话机制也不同（学工平台把 token 放在 localStorage，
 * 表单引擎那侧靠 cookie `session_oa` + `JSESSIONID`）。
 *
 * 纯 Kotlin，不碰 android.*：判定逻辑要能在 JVM 上直接测。
 */
object XgUrls {

    /** 学工平台根（超星智慧学工，学校侧 unitId = 343962）。 */
    const val BASE = "https://xgxt.juwp.edu.cn"

    /**
     * 接入学校统一身份认证的入口。未登录 CAS 时 302 到 `eapp2.juwp.edu.cn:9443`；
     * 已有会话则直接送回学工，也就是「从教务进来就免登」的那条链路。
     *
     * **不要**改成超星 passport 的 `/passport/mlogin`：那是学校没配统一认证时的兜底
     * 账号体系（手机号 + 学习通密码），与教务不是一个账号。见 DESIGN §4.26。
     */
    const val SSO_LOGIN = "$BASE/sfrz/login343962"

    /** 学工平台首页（右上角「回首页」的去处）。 */
    const val HOME = "$BASE/"

    /** 超星 office 表单引擎根；各表单跑在 `apps/forms/mobile/apply.html`。 */
    const val OFFICE = "$BASE/office/"

    /** 宿舍报修（学工 → 宿管服务 → 宿舍报修）。 */
    val REPAIR = XgForm(
        id = "dorm_repair",
        title = "宿舍报修",
        subtitle = "宿管服务 · 填表 / 上传附件 / 提交 / 查进度",
        pageEnc = "2a6e9319e7a9ac992f30e587d26f435d",
        appId = 278625,
    )

    /**
     * 请假。
     *
     * 副标题只写这张单子能干什么，不写它在学工里挂哪个分组——那个分组名我还没实测到
     * （学工菜单接口要登录态才能调），编一个分类名比不写更糟。
     */
    val LEAVE = XgForm(
        id = "leave",
        title = "请假",
        subtitle = "填表 / 上传附件 / 提交 / 查进度",
        pageEnc = "5f440bbcb35527e35a7c4e7169bcabc2",
        appId = 278616,
    )

    /**
     * 可直达的表单清单。扩展服务页按它渲染入口，加一个新表单只需在这里加一条
     * （再在 UI 层补一个图标映射）。
     */
    val FORMS: List<XgForm> = listOf(REPAIR, LEAVE)

    /** 按 [XgForm.id] 取表单；脏 extra（旧版本写下的 id）返回 null。 */
    fun formById(id: String?): XgForm? = id?.let { raw -> FORMS.firstOrNull { it.id == raw } }

    /** 学工域判定。host 段精确比较，复用 [JwUrls.hostOf] 的唯一实现。 */
    fun isXgHost(url: String?): Boolean = JwUrls.hostOf(url) == "xgxt.juwp.edu.cn"

    /** 统一认证域判定（`eapp2.juwp.edu.cn:9443`，与教务同一个 CAS）。 */
    fun isCasHost(url: String?): Boolean = JwUrls.isCasHost(url)

    /**
     * 是否已经落在表单引擎里（某张表单本身）。
     *
     * 学工是 SPA，路由变化不产生新导航，所以只能按首次加载的 URL 判断。
     */
    fun isFormPage(url: String?): Boolean =
        url != null && isXgHost(url) && "/office/apps/forms/" in url

    /**
     * 是否还在统一认证的接入点上（`/sfrz/login343962`，含 CAS 带 ticket 回跳的那一刻）。
     *
     * 必须单独认出来：这个路径**也在学工域**，但它出现时学工后端还没把 office 的
     * 会话 cookie 处理完，此时抢着往下跳会把登录链路截断。
     */
    fun isSsoEntry(url: String?): Boolean =
        url != null && isXgHost(url) && "/sfrz/" in url

    /**
     * 该不该从「刚落到学工域」再进一层，直接打开目标表单。
     *
     * 三个条件缺一不可：在学工域、已经离开认证链路、当前不在表单页。
     * 第三条同时防住了自跳自的循环。
     */
    fun shouldEnterForm(url: String?): Boolean =
        isXgHost(url) && !isSsoEntry(url) && !isFormPage(url)

    /**
     * 顶栏状态条文案（DESIGN §3.15）。只按域分档，不做更细的页面判断——
     * 学工是 SPA，路由变化不产生新导航，按 URL 猜页面一定会猜错。
     */
    fun statusHint(url: String?, formTitle: String): String = when {
        isCasHost(url) -> "用学校统一身份认证登录（与教务同一个账号）"
        isFormPage(url) -> "${formTitle}已打开，填完点页面下方的提交"
        isXgHost(url) -> "已进入学工系统，可从右上角回到首页找入口"
        else -> "正在打开${formTitle}…"
    }
}
