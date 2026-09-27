package edu.jxslu.schedule.domain

import edu.jxslu.schedule.data.qzxy.QzxySession

/**
 * 会话串的解析与导出（DESIGN §4.30）。
 *
 * 趣智校园没有独立的 token：`loginCode` 要跟 `projectId` / `accountId` / `userId` /
 * `telephone` 凑成一份完整会话才能调接口。所以「粘贴登录」粘的是一整串，不是一个 token
 * ——这一点与胖乖不同，那边的 token 单独就能用。
 *
 * 认三种写法，都是抓包或导出时最容易拿到的形态：
 *
 * ```
 * {"loginCode":"…","projectId":"…","accountId":"…","userId":"…","telephone":"…"}  登录响应原文
 * loginCode=…&projectId=…&accountId=…&userId=…&telephone=…                        表单
 * loginCode: …
 * projectId: …                                                                    逐行贴
 * ```
 *
 * 只在 [parse] 里做文本处理，不碰网络：能不能用由服务端说了算（见仓库的 `validateSession`）。
 */
object QzxySessionLink {

    /** 必填字段。缺任一项都不成会话，`loginCode` 单独发出去服务端也认不出来。 */
    private val REQUIRED = listOf("loginCode", "projectId")

    /** 值里不允许出现的字符：引号、逗号、`&`、空白、花括号。 */
    private const val VALUE_CLASS = "[^\"',&\\s{}]+"

    fun parse(text: String): QzxySession? {
        val loginCode = field(text, "loginCode") ?: return null
        val projectId = field(text, "projectId") ?: return null
        return QzxySession(
            loginCode = loginCode,
            userId = field(text, "userId").orEmpty(),
            accountId = field(text, "accountId").orEmpty(),
            projectId = projectId,
            telephone = field(text, "telephone") ?: field(text, "telPhone").orEmpty(),
        )
    }

    /** 缺哪个必填字段。给用户一句能照着补的提示，别只说「格式不对」。 */
    fun missingField(text: String): String? = REQUIRED.firstOrNull { field(text, it) == null }

    /** 导出成一行表单样式，便于从一台设备复制到另一台。 */
    fun format(session: QzxySession): String = buildString {
        append("loginCode=").append(session.loginCode)
        if (session.userId.isNotBlank()) append("&userId=").append(session.userId)
        if (session.accountId.isNotBlank()) append("&accountId=").append(session.accountId)
        append("&projectId=").append(session.projectId)
        if (session.telephone.isNotBlank()) append("&telephone=").append(session.telephone)
    }

    /**
     * 取一个字段的值。
     *
     * 前面的 `(?<![A-Za-z0-9])` 不能省：登录响应里同时有 `loginCode` 与 `v3LoginCode`，
     * 少了它，粘一份登录响应会把 `v3LoginCode` 的值当成 `loginCode`。
     */
    private fun field(text: String, key: String): String? =
        Regex(
            "(?<![A-Za-z0-9])[\"']?$key[\"']?\\s*[:=]\\s*[\"']?($VALUE_CLASS)",
            RegexOption.IGNORE_CASE,
        ).find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}
