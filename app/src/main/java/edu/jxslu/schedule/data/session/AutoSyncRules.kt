package edu.jxslu.schedule.data.session

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 自动导入的闸门（DESIGN §4.29）：学业完成情况与成绩共用这一套。
 *
 * 和 [ProfileSyncRules] 是同一类东西，判据不同：
 * - 学籍卡补抓是「字段还空着 + 今天没试过」；
 * - 这里是「**库里还没数据**（首次，立刻就抓）或**距上次成功超过 7 天**」。
 *
 * 为什么是 7 天而不是每次冷启动：这两份数据一学期才动一次，天天抓只是白撞教务的风控
 * （登录闸门是按失败次数停用的，没必要的请求就是没必要的风险）。用户想立刻刷新，
 * 页面里有「重新导入」按钮强制绕过闸门。
 */
object AutoSyncRules {

    /** 自动刷新间隔。改它等于改「多久后会自动打一次教务」，想清楚再动。 */
    const val REFRESH_INTERVAL_DAYS = 7L

    /**
     * 该不该现在去抓。
     *
     * [hasData] 是库里有没有数据；[lastSuccessDate] 是上次**成功**导入的 ISO 日期
     * （失败不记，所以不会因为一次网络抖动把闸门顶掉 7 天）。
     *
     * 四种情况都要想清楚：
     * - 没数据 → 抓（首次配置完就该看到东西，这是用户要的「自动导入」）；
     * - 有数据但没日期（老版本升上来的库）→ 抓一次把日期补上，之后回到 7 天节奏；
     * - 日期是脏值 / 解不开 → 当作没记录，抓；
     * - 日期在未来（时钟被回拨）→ 不抓，等它自然走到区间内。
     */
    fun shouldAttempt(
        hasData: Boolean,
        lastSuccessDate: String?,
        today: LocalDate = LocalDate.now(),
    ): Boolean {
        if (!hasData) return true
        val last = lastSuccessDate
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return true
        return ChronoUnit.DAYS.between(last, today) >= REFRESH_INTERVAL_DAYS
    }
}
