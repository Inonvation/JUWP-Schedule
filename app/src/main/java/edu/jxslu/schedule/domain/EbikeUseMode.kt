package edu.jxslu.schedule.domain

/**
 * 快趣出行的**使用方式**（DESIGN §3.9 / §4.32，2026-09-29）。
 *
 * 同一个「快趣出行码」页曾经被两套能力叠在一起：出码 + 微信扫一扫开车（旧版形态），
 * 以及账号登录后在 App 内直接开锁 / 锁车 / 还车（2026-09-28 加的本机用车）。
 * 两者互不兼容——小程序方式的「点扫一扫即起免费计时」与账号方式的「开锁即计时起点」
 * 是两套语义，摆在同一页只会让用户不知道哪个才是自己的路。这里用一档显式选择隔开，
 * **能力的唯一判据就是本枚举**，页面里不要再各自写一份 `if`：
 *
 * | 能力 | [MiniProgram] | [Account] |
 * |------|---------------|-----------|
 * | 车号输入 / 生成二维码 / 保存到相册 | ✅ | ✅ |
 * | 打开微信扫一扫（顺带起免费计时） | ✅ | ❌ |
 * | 直接开锁 / 临时锁车 / 还车 | ❌ | ✅（需登录） |
 * | 快趣账号页 / 本机骑行记录 | ❌ | ✅ |
 * | 精确倒计时（微信通知校准） | ✅ | ❌ |
 * | 地图：附近车辆 + 还车点 / 禁停区 | ✅ | ✅ |
 * | 地图：车行「开锁」与「当前用车」卡 | ❌ | ✅ |
 *
 * **默认 [MiniProgram]**：账号方式要登录、有计费后果、还有微信支付分授权的门槛
 * （见 §4.32 的 11035），只能由用户显式选择；小程序方式不需要任何凭证。
 *
 * 与 [ThemePalette] 同款：枚举自带存储 id 与显示名，**不带任何 UI 类型**。
 */
enum class EbikeUseMode(val id: String, val label: String) {
    /** 跳转微信小程序（旧版形态）：出码 + 微信扫一扫开车，App 内不做任何写操作。 */
    MiniProgram("mini_program", "微信小程序"),

    /** 账号登录：登录快趣后在 App 内直接开锁 / 锁车 / 还车（有风险，见页内提示）。 */
    Account("account", "账号登录"),
    ;

    /** 账号登录方式：App 内用车、账号页、骑行记录都只在这一档出现。 */
    val isAccount: Boolean get() = this == Account

    /** 微信小程序方式：出码 + 微信扫一扫开车；App 内用车与账号页都不出现。 */
    val isMiniProgram: Boolean get() = this == MiniProgram

    companion object {
        /** 默认档（见类注释：账号方式必须显式选择）。 */
        val Default: EbikeUseMode = MiniProgram

        /**
         * 从存储值还原。认不出的值（旧版残留 / 手改 / 结构改名）一律退回 [Default]：
         * 与主题模式同一口径——宁可落回不需要凭证的那一档，也不要因为一个认不出的
         * 字符串把用户带进一个要登录、有计费后果的页面。null = 从未设置过。
         */
        fun fromId(id: String?): EbikeUseMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Default
    }
}

/**
 * 「当前这一档 + 当前登录/订单状态」下**具体能做什么**（纯 JVM 可测）。
 *
 * 存在的理由：能力矩阵原本散在出码页、地图页、账号页、校园服务入口与通知监听器里
 * 各自写 `if`，改口径时容易漏一处。现在**判据只有 [EbikeUseMode.capabilities] 一个函数**，
 * 页面只消费这里的布尔量；矩阵本身由 `EbikeUseModeTest` 钉住。
 *
 * 注意 [wechatScan] 同时决定「点扫一扫起免费计时」那条路——两者是同一个动作。
 */
data class EbikeCapabilities(
    /** 出码卡给「打开微信扫一扫」（小程序方式的目标动作）。 */
    val wechatScan: Boolean,
    /** 精确倒计时（微信通知校准）的开关与监听链路。 */
    val wechatNoticeCalibration: Boolean,
    /**
     * App 内用车这一整套：骑行状态查询、骑行卡、账号行 / 账号页、本机骑行记录、
     * 地图上的「开锁」与「当前用车」卡。
     */
    val inAppRide: Boolean,
    /** 出码卡里的「直接开锁」：App 内用车 + 已登录 + 当前没有在案订单。 */
    val directUnlock: Boolean,
)

/**
 * 能力矩阵的唯一出处（DESIGN §3.9 的表）。
 *
 * @param loggedIn 快趣账号是否已登录（只影响 [EbikeCapabilities.directUnlock]）。
 * @param hasRide 是否有在案订单（同上；有订单时不给「直接开锁」，去骑行卡里还车）。
 */
fun EbikeUseMode.capabilities(
    loggedIn: Boolean = false,
    hasRide: Boolean = false,
): EbikeCapabilities = when (this) {
    EbikeUseMode.MiniProgram -> EbikeCapabilities(
        wechatScan = true,
        wechatNoticeCalibration = true,
        inAppRide = false,
        directUnlock = false,
    )
    EbikeUseMode.Account -> EbikeCapabilities(
        wechatScan = false,
        wechatNoticeCalibration = false,
        inAppRide = true,
        directUnlock = loggedIn && !hasRide,
    )
}
