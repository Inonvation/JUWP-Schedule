package edu.jxslu.schedule.data.qiekj

/**
 * 胖乖生活（轻乖/qiekj）接口配置。
 * 全部取自参考实现 light-life v3.0（ApiConfig.kt），版本号与 secret 变更时只改这里（DESIGN 7.2）。
 *
 * 每日签到走独立的 [POINTS_VERSION]：`doUserSignIn` 有版本门槛，旧版本会回
 * 「版本过低，请升级APP后再使用」；开水链路继续用 [VERSION]。
 */
object QiekjApiConfig {
    const val BASE_URL = "https://userapi.qiekj.com/"
    const val VERSION = "1.60.3"
    const val POINTS_VERSION = "1.123.0"
    const val LOGIN_CHANNEL = "android_app"
    const val API_CHANNEL = "android_app"
    const val PHONE_BRAND = "Redmi"
    const val USER_AGENT = "okhttp/3.14.9"
    const val CONTENT_TYPE = "application/x-www-form-urlencoded;charset=UTF-8"
    const val ANDROID_SECRET = "nFU9pbG8YQoAe1kFh+E7eyrdlSLglwEJeA0wwHB1j5o="
}
