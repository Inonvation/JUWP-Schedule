package edu.jxslu.schedule.domain

/**
 * 首启声明的两份须知（DESIGN §3.16，拆分）。
 *
 * 拆开的理由：原先一个弹窗把事实告知与责任条款混在一起，用户只会滚到底点掉。
 * 按**性质**分成两份，每份只说自己那件事：
 * - [UserNotice]：事实性告知——这个 App 是什么、数据去哪、接口有什么限制（锁 3 秒）；
 * - [Disclaimer]：法律条款与责任——禁止事项、风险自负（锁 5 秒，正文取自 [Disclaimer]）。
 *
 * 两份**不重复**：「严禁刷积分/绕过付费」「后果自负」这类条款只在免责声明里说一次，
 * 用户须知不再抄一遍——同一个承诺说两遍等于稀释。
 *
 * **充值风险不在首启**：首启的用户还没登录、没余额、没充过值，此刻弹等于白发。真正
 * 需要它的时刻是点「充值」那一秒，那条链路见 `ui/common/RechargeDisclaimerDialog`。
 */
enum class FirstRunNotice {
    UserNotice,
    Disclaimer,
}

/**
 * 首启两份须知的正文、顺序与关闭锁（DESIGN §3.16）。
 *
 * 文案放 domain 而不是 UI：与 [Disclaimer] 同口径（README 是对外的那一份），
 * 且版本号要能被纯 JVM 单测读到。免责声明那份**直接取 [Disclaimer]**，不另抄一份。
 */
object FirstRunNotices {

    /**
     * 文案版本号。**改任何一份的任何一个字都要 +1**——落盘的同意记录按版本比对
     * （`domain/NoticeConsent.isConsented`），不 bump 就等于"改了文案但用户不必重看"。
     */
    const val VERSION = 2

    /** 弹出顺序：先说自己是什么，再说责任。 */
    val ORDER: List<FirstRunNotice> = listOf(FirstRunNotice.UserNotice, FirstRunNotice.Disclaimer)

    /** 用户须知首次弹出的关闭锁。 */
    const val USER_NOTICE_CLOSE_LOCK_MS = 3_000L

    /** 免责声明首次弹出的关闭锁（沿用拆分前的 5 秒）。 */
    const val DISCLAIMER_CLOSE_LOCK_MS = 5_000L

    /**
     * 该份须知首次弹出时的关闭锁。已确认过的由 `domain/NoticeConsent.closeLockMs` 归零，
     * 这里只给"首次"那一档。
     */
    fun closeLockMs(notice: FirstRunNotice): Long = when (notice) {
        FirstRunNotice.UserNotice -> USER_NOTICE_CLOSE_LOCK_MS
        FirstRunNotice.Disclaimer -> DISCLAIMER_CLOSE_LOCK_MS
    }

    fun title(notice: FirstRunNotice): String = when (notice) {
        FirstRunNotice.UserNotice -> "用户须知"
        FirstRunNotice.Disclaimer -> "免责声明"
    }

    /** 正文开场句。免责声明那份与 [Disclaimer.INTRO] 同源。 */
    fun intro(notice: FirstRunNotice): String = when (notice) {
        FirstRunNotice.UserNotice -> USER_NOTICE_INTRO
        FirstRunNotice.Disclaimer -> Disclaimer.INTRO
    }

    /** 逐条正文。免责声明那份与 [Disclaimer.ITEMS] 同源。 */
    fun items(notice: FirstRunNotice): List<String> = when (notice) {
        FirstRunNotice.UserNotice -> USER_NOTICE_ITEMS
        FirstRunNotice.Disclaimer -> Disclaimer.ITEMS
    }
}

/** 用户须知的身份声明（免责声明的同款句子在 `Disclaimer.INTRO`）。 */
private const val USER_NOTICE_INTRO =
    "本项目为学生自用工具，**非学校官方应用**，未获学校及任何第三方平台授权。"

/**
 * 用户须知正文：**只讲事实**，不讲责任（责任在免责声明那份）。
 *
 * 第 3 条把天气与地图瓦片两处第三方请求都说清：天气用固定校区坐标（Open-Meteo），
 * 瓦片来自高德、只带当前查看范围。除此之外的数据只在本机与完成用户请求所必需的
 * 学校 / 平台接口之间流转。
 */
private val USER_NOTICE_ITEMS = listOf(
    "**完全免费**；任何付费代下、转卖或重打包的安装包均与开发者无关。",
    "**仅限本人账号使用**。课表、成绩、流水与登录凭证**只在本机保存**（Keystore 加密，" +
        "排除云备份），登录时只发给对应平台官方接口；应用无自建服务器，**不采集、不出售数据**。",
    "除完成你发起的登录、查询、充值等请求所必需外，只有两处第三方请求：" +
        "天气（Open-Meteo，固定校区坐标）与单车地图瓦片（高德，当前查看范围）。",
    "教务、一卡通、缴费、热水、开水、单车、U净、问渠均为**第三方私有接口**，" +
        "可能随时变更或失效，**不保证可用**。",
)
