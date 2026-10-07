package edu.jxslu.schedule.data.session

import kotlin.random.Random
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

    /**
     * 毫秒级间隔闸门（DESIGN §4.33，成绩/考试变动提醒用）。提醒间隔是小时级，
     * [shouldAttempt] 的日期粒度不够用。
     *
     * - 从没成功过（last 无效/≤0）→ 抓；
     * - 时钟被回拨（last 在未来）→ 不抓，等它自然走到区间内（与 [shouldAttempt] 同口径）；
     * - [intervalHours] 是脏值（≤0）按 1 小时兜底，不能让坏配置退化成「每次都抓」。
     */
    fun shouldAttemptAt(lastSuccessMillis: Long?, intervalHours: Int, nowMillis: Long): Boolean {
        val hours = intervalHours.coerceAtLeast(1)
        val last = lastSuccessMillis?.takeIf { it > 0 } ?: return true
        if (last > nowMillis) return false
        return nowMillis - last >= hours * MILLIS_PER_HOUR
    }

    /**
     * 教材补抓闸门（2026-10-06）。
     *
     * 教材原本只跟「导入课表」抓那一次，而那次会因为会话被别处登录顶掉（强智单会话）
     * 静默失败——导入成功、详情里就是没有教材。补抓的条件是「**该学期教材为空**」：
     * 抓成功过就不再打扰教务；但失败会一直保持「空」，所以还要一道时间闸门，
     * 否则每次冷启动都打一次教务。
     *
     * 与 [shouldAttempt] 的差别：那个记的是**成功**日期（一次网络抖动不该顶掉 7 天），
     * 这里的 [lastAttemptMillis] 记的是**尝试**时刻——它挡的是「反复打」，
     * 「还该不该重试」由 [hasTextbooks] 判定。
     *
     * 时钟被回拨（last 在未来）→ 不抓，等它自然走到区间内，与另外两条闸门同口径。
     */
    fun textbookBackfillDue(
        hasTextbooks: Boolean,
        fetchedTerm: String?,
        currentTerm: String,
        lastAttemptMillis: Long?,
        nowMillis: Long,
    ): Boolean {
        if (hasTextbooks) return false
        // 「抓成功但 0 条」也要收闸：下学期没征订 / 实习毕设本来没教材时，
        // 只看「表里有没有行」会永远满足，变成每 6 小时打一次教务、永不停止
        if (fetchedTerm != null && fetchedTerm == currentTerm) return false
        val last = lastAttemptMillis?.takeIf { it > 0 } ?: return true
        if (last > nowMillis) return false
        return nowMillis - last >= TEXTBOOK_BACKFILL_INTERVAL_MS
    }

    /** 教材补抓的最小间隔。改它等于改「教材缺失时多久自动打一次教务」。 */
    const val TEXTBOOK_BACKFILL_INTERVAL_MS: Long = 6L * 60L * 60L * 1000L

    /**
     * 壳页学期缓存是否可用（考试/选课同步共用，2026-10-04 风控优化）。
     *
     * 考试与选课的同步每次都先 GET 壳页读「教务当前学期」，而学期下拉一学期才变一次。
     * TTL 内直接复用上次抓到的学期，省一个壳页 GET；force（用户手动刷新）与
     * 缓存缺失/过期都不算可用。学期翻年最多滞后半个窗口，而翻年发生在假期，
     * 旧学期查出来是空的正巧无害；手动刷新随时可以拿到真值。
     */
    fun shellTermCacheUsable(cached: String?, savedAt: Long, force: Boolean, nowMillis: Long): Boolean =
        !force && !cached.isNullOrBlank() && savedAt > 0 && nowMillis - savedAt < SHELL_CACHE_TTL_MS

    /**
     * 同一次同步内相邻两个教务请求之间的随机间隔（毫秒）。
     *
     * 0 间隔连发（翻页 / 多学期 / 四个学业维度连着打）是最像机器的请求节奏；
     * 几百毫秒的随机间隔用户无感，却让请求流不再等间隔。这是「放慢」不是「重试」——
     * 失败不重试的纪律不变（§4.33）。
     */
    fun interRequestDelayMs(): Long = Random.nextLong(INTER_REQUEST_DELAY_MIN_MS, INTER_REQUEST_DELAY_MAX_MS)

    private const val MILLIS_PER_HOUR = 60L * 60L * 1000L

    /** 壳页学期缓存 TTL（12 小时），见 [shellTermCacheUsable]。 */
    const val SHELL_CACHE_TTL_MS: Long = 12L * 60L * 60L * 1000L

    private const val INTER_REQUEST_DELAY_MIN_MS = 200L
    private const val INTER_REQUEST_DELAY_MAX_MS = 600L
}
