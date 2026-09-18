package edu.jxslu.schedule.data.qiekj

import edu.jxslu.schedule.domain.QiekjSign
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 胖乖请求统一头装配（对齐 light-life HeaderInterceptor）。
 *
 * 关键规则：
 * - 登录类接口（common 与 user/reg 路径）用 LOGIN_CHANNEL，且**不带** sign 与 token 头；
 * - 其余业务接口带 token + Authorization + sign（SHA-256，见 [QiekjSign]）。
 * 计算已抽到 domain 纯函数，这里只做装配；token 不进任何日志（BASIC 级日志不打 header）。
 */
class QiekjHeaderInterceptor(
    private val tokenProvider: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val timestamp = System.currentTimeMillis().toString()
        val path = chain.request().url.encodedPath
        val isLoginApi = path.startsWith("/common/") || path.startsWith("/user/reg")
        val channel = if (isLoginApi) QiekjApiConfig.LOGIN_CHANNEL else QiekjApiConfig.API_CHANNEL

        val builder = chain.request().newBuilder()
            .header("Version", QiekjApiConfig.VERSION)
            .header("channel", channel)
            .header("phoneBrand", QiekjApiConfig.PHONE_BRAND)
            .header("User-Agent", QiekjApiConfig.USER_AGENT)
            .header("Content-Type", QiekjApiConfig.CONTENT_TYPE)
            .header("timestamp", timestamp)
            .header("Host", "userapi.qiekj.com")
            .header("Connection", "Keep-Alive")
            .header("Accept-Encoding", "gzip")

        tokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
            builder.header("token", token)
            builder.header("Authorization", token)
            if (!isLoginApi) {
                builder.header(
                    "sign",
                    QiekjSign.sign(
                        appSecret = QiekjApiConfig.ANDROID_SECRET,
                        channel = channel,
                        timestamp = timestamp,
                        token = token,
                        version = QiekjApiConfig.VERSION,
                        path = path,
                    ),
                )
            }
        }

        return chain.proceed(builder.build())
    }
}
