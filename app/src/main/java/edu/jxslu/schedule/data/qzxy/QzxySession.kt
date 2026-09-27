package edu.jxslu.schedule.data.qzxy

/**
 * 趣智校园登录会话（DESIGN §4.30）。四个 id 缺一不可：
 * 后续每个接口都要以 query/form 参数把它们带上，`telephone` 还要同时以
 * `telephone` 与 `telPhone` 两个键名重复提交（服务端历史遗留，见 linyu 的踩坑记录）。
 */
data class QzxySession(
    val loginCode: String,
    val userId: String,
    val accountId: String,
    val projectId: String,
    val telephone: String,
    val name: String = "",
) {
    val isComplete: Boolean
        get() = loginCode.isNotBlank() && userId.isNotBlank() && projectId.isNotBlank()

    /** 认证参数；POST 走 form 字段，GET 走 query，两者共用这一份。 */
    fun authFields(): Map<String, String> = buildMap {
        put("loginCode", loginCode)
        if (userId.isNotBlank()) put("userId", userId)
        if (accountId.isNotBlank()) put("accountId", accountId)
        if (projectId.isNotBlank()) put("projectId", projectId)
        if (telephone.isNotBlank()) {
            put("telephone", telephone)
            put("telPhone", telephone)
        }
        put("phoneSystem", QzxyApiConfig.PHONE_SYSTEM)
        put("version", QzxyApiConfig.VERSION)
    }

    companion object {
        /**
         * 收敛登录响应的两种形态：顶层直接带 id（旧）或包一层 `userAccount`（新）。
         * 任一路径拿不到 projectId 就算失败，宁可报错也不要拿 0 去调后续接口。
         */
        fun from(data: QzxyLoginData, fallbackPhone: String): QzxySession {
            val account = data.userAccount ?: data
            val loginCode = data.loginCode.orEmpty()
            val telephone = (data.telephone ?: data.telPhone ?: fallbackPhone).orEmpty()
            return QzxySession(
                loginCode = loginCode,
                userId = (data.userId ?: data.v3UserId ?: account.userId ?: "").orEmpty(),
                accountId = (account.accountId ?: data.accountId ?: "").orEmpty(),
                projectId = (account.projectId ?: data.projectId ?: "").orEmpty(),
                telephone = telephone,
                name = (account.name ?: data.name ?: "").orEmpty(),
            )
        }
    }
}
