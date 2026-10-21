package edu.jxslu.schedule.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 开锁前的「没有可用骑行卡」判定（2026-10-22 用户要求）。
 *
 * 快趣的骑行卡是免费时长权益的来源（前 15 分钟免费以骑行套餐卡为准），没有卡也照样能开锁，
 * 只是从开锁起就按规则计费。所以这里不是闸门，而是**独立于开锁二次确认的一条提醒**：
 * 账号**有可用卡就不提醒**；没有 / 已过期 / 次数用完才提醒，并允许「今日不再提醒」。
 *
 * 纯 JVM（无 Android API），口径由 `EbikeRideCardTest` 钉住。
 */
object EbikeRideCard {

    /** 提醒正文的后半句：说明后果与出路（页面别再手抄一份）。 */
    const val CONSEQUENCE =
        "开锁下单后会按快趣规则计费，前 15 分钟免费也以骑行套餐卡为准；" +
            "续卡或买卡请在快趣小程序完成。"

    /** 「今日不再提醒」的日期键形态。 */
    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * 这张卡还能不能用：**到期时间已知且已过 = 不可用**；剩余次数为 0 = 不可用。
     *
     * 缺数据不下断言（与电量、到期提醒同一容错口径）：`endAt` 缺失 / 解析不了、
     * 次数缺失，都不把卡判死——宁可不提醒，也不误报「你的卡过期了」。
     * 「无限次数卡」（官方 > 1e5 视作无限）在到期前恒可用。
     */
    fun isUsable(
        coupon: KqcxAuth.AssetCoupon,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val endAt = EbikeCardExpiry.parseEndAt(coupon.endAt, zone)
        if (endAt != null && endAt <= nowMillis) return false
        return coupon.remainFrequency != 0L
    }

    /** 是否持有至少一张可用卡（空 list = 没有卡）。 */
    fun hasUsableCard(
        coupons: List<KqcxAuth.AssetCoupon>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean = coupons.any { isUsable(it, nowMillis, zone) }

    /**
     * 开锁前要展示的提醒正文；**有可用卡时返回 null（不提醒）**。
     * 原因分三档说清楚（没有 / 已过期 / 次数用完），随后接 [CONSEQUENCE] 与那句确认。
     */
    fun warningText(
        coupons: List<KqcxAuth.AssetCoupon>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        if (hasUsableCard(coupons, nowMillis, zone)) return null
        val reason = when {
            coupons.isEmpty() -> "当前账号还没有骑行卡。"
            coupons.all { coupon ->
                EbikeCardExpiry.parseEndAt(coupon.endAt, zone)?.let { it <= nowMillis } == true
            } -> "当前骑行卡已过期（${latestExpiryText(coupons, zone)}）。"
            else -> "当前骑行卡的剩余次数已用完。"
        }
        return "$reason${CONSEQUENCE}确认要开车吗？"
    }

    /** 「今日不再提醒」的日期键（本地日历日；跨天自然失效，不需要额外清理）。 */
    fun dayKey(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().format(DAY_FORMAT)

    private fun latestExpiryText(
        coupons: List<KqcxAuth.AssetCoupon>,
        zone: ZoneId,
    ): String {
        val latest = coupons.mapNotNull { EbikeCardExpiry.parseEndAt(it.endAt, zone) }.maxOrNull()
            ?: return "有效期已过"
        val date: LocalDate = Instant.ofEpochMilli(latest).atZone(zone).toLocalDate()
        return "有效期至 $date"
    }
}
