package edu.jxslu.schedule.data.ujing

/**
 * U净接口配置（DESIGN §4.37）。
 *
 * 网关与三组「客户端指纹」来自社区多份独立实测（2026-09 实机验证版）。
 * 服务端对 `x-app-code` 有一定宽容（ZI/BI 与 ZA/BA 两套都被验证可用），这里固定
 * 实机端到端验证过的一组。服务端收紧校验时**先动这里**（需重新对齐）。
 *
 * 说明：这些头是接口指纹（客户端版本 + 平台字段），照抄社区验证组合，不是身份声明；
 * 若实测证明可换成中性值，再替换。
 */
object UjingApiConfig {
    const val BASE_URL = "https://phoenix.ujing.online/api/v1"

    /** 取验证码 / 登录（鉴权前）。 */
    const val APP_CODE_ACCOUNT = "ZI"
    const val APP_VERSION_ACCOUNT = "2.4.3"
    const val UA_ACCOUNT = "U jing/2.4.3 (iPhone; iOS 17.3; Scale/3.00)"

    /**
     * 门店查询（`stores/near` / `devices/reserve`，鉴权后）。
     *
     * 2026-10-21 修看板取不到数：这两个接口**不属于**扫码下单那组业务指纹。
     * 社区实测（liteU / DUT-Ujing，2026-09-30）用的是 `ZA` + `2.4.18`；
     * 此前跟 `BI` + `2.4.2` 一起发，`devices/reserve` 回 401，仓库按会话过期清了 token，
     * 于是看板与侧栏状态一直取不到（重登后再次 401，像"刷新无效"）。
     * 业务请求（扫码 / 套餐 / 订单）仍走下面的 BI 组。
     */
    const val APP_CODE_QUERY = "ZA"
    const val APP_VERSION_QUERY = "2.4.18"

    /** 业务请求（鉴权后）。 */
    const val APP_CODE_BUSINESS = "BI"
    const val APP_VERSION_BUSINESS = "2.4.2"
    const val UA_BUSINESS = "U jing/2.4.2 (iPhone; iOS 17.0.1; Scale/3.00)"
    const val WEEX_VERSION = "1.1.30"

    /** 未授权位置占位（官方客户端未定位时也发这个值）。 */
    const val USER_GEO_UNKNOWN = "-180.000000,-180.000000"

    const val MOBILE_BRAND = "apple"
    const val MOBILE_MODEL = "iPhone14,5"
}
