package edu.jxslu.schedule.domain

import java.security.MessageDigest

/**
 * 胖乖（轻乖生活）请求签名。
 *
 * 根因：服务端对带 token 的业务接口校验 `sign` 头，拼接顺序固定为
 * `appSecret -> channel -> timestamp -> token -> version` 后直接接请求路径；
 * 登录类接口（common 与 user/reg 路径）不签名。算法是整串小写 SHA-256 hex。
 *
 * 抽成纯函数（参数全部显式传入、不依赖 data 层配置）有两个目的：
 * JVM 单测可逐字节对齐 light-life 参考实现；Interceptor 只做装配不做计算。
 */
object QiekjSign {

    fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 参考实现 light-life HeaderInterceptor.sign：字段序与拼接方式不可调换。 */
    fun sign(
        appSecret: String,
        channel: String,
        timestamp: String,
        token: String,
        version: String,
        path: String,
    ): String = sha256Hex(
        "appSecret=$appSecret&channel=$channel&timestamp=$timestamp&token=$token&version=$version$path",
    )
}
