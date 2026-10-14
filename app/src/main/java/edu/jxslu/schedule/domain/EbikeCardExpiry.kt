package edu.jxslu.schedule.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 骑行卡券（[KqcxAuth.AssetCoupon]）临近到期的提醒口径（DESIGN §4.32）。
 *
 * 快趣只给 `endTime` 字符串（`yyyy-MM-dd HH:mm:ss`），本文件负责把它变成时刻、
 * 判「是否在到期前一天之内」、算剩余文案与去重键。纯 JVM，不含 Android API。
 *
 * **为什么是「前一天」**：骑行卡券是免费时长权益的来源（前 15 分钟免费以骑行套餐卡为准），
 * 到期当天才提醒往往来不及续费，提前一天给一次窗口。窗口是 [WINDOW_MS]（24 小时），
 * 语义为 `0 < endAt - now ≤ 24h`——已过期的卡不再提醒（那是另一件事）。
 *
 * **去重按卡**（[dedupKey] 取原始 `endTime` 串）：同一张卡只提醒一次，换卡（新 `endTime`）
 * 自然换键；落盘在 `DisplayPrefsStore.ebikeCardExpiryNotifiedKeys`。
 */
object EbikeCardExpiry {

    /** 到期前多久开始提醒（毫秒）。 */
    const val WINDOW_MS = 24 * 60 * 60 * 1000L

    /** 到期提醒正文末尾那句后果说明（文案唯一出处，页面别再手抄一份）。 */
    const val REMINDER_NOTE =
        "到期后骑行按快趣规则计费（前 15 分钟免费以骑行套餐卡为准）。" +
            "续费或购买新卡请在快趣官方渠道（微信小程序）完成。"

    private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * 解析 `endTime` 为 epoch 毫秒。两种形态都收：
     * 带时分秒的按原值；**只给到日期**的按当天 `23:59:59` 收尾（「有效期至某天」= 那天结束前）。
     * 空串 / 解析失败返回 null（缺数据不下断言，与电量那套容错同一口径）。
     */
    fun parseEndAt(raw: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val dateTime = runCatching { LocalDateTime.parse(text, DATE_TIME_FORMAT) }
            .recoverCatching { LocalDate.parse(text, DATE_FORMAT).atTime(23, 59, 59) }
            .getOrNull() ?: return null
        return dateTime.atZone(zone).toInstant().toEpochMilli()
    }

    /** 是否落在「到期前一天」窗口内：未过期且剩余不超过 [WINDOW_MS]。 */
    fun isExpiringSoon(endAtMillis: Long?, nowMillis: Long): Boolean {
        if (endAtMillis == null) return false
        val remain = endAtMillis - nowMillis
        return remain > 0L && remain <= WINDOW_MS
    }

    /** 去重键（同一张卡只提醒一次）：取原始 `endTime` 串，换卡自然换键。 */
    fun dedupKey(rawEndAt: String): String = "card|${rawEndAt.trim()}"

    /**
     * 剩余文案（按**日历天**算，不是按 24 小时）：`今天到期` / `明天到期` / `还剩 N 天`。
     * 已过期或今天到期都归「今天到期」——提醒只在未过期时发，这个分支只是兜底。
     */
    fun remainingText(
        endAtMillis: Long,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(endAtMillis).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, endDate)
        return when {
            days <= 0L -> "今天到期"
            days == 1L -> "明天到期"
            else -> "还剩 $days 天"
        }
    }

    /**
     * 这次该提醒的卡券：**在窗口内**且**没提醒过**（按 [dedupKey] 去重）。
     * 无 `endTime` 的卡跳过——没有到期日就无从谈「临近到期」。
     */
    fun pendingReminders(
        coupons: List<KqcxAuth.AssetCoupon>,
        notifiedKeys: Set<String>,
        nowMillis: Long,
    ): List<KqcxAuth.AssetCoupon> = coupons.filter { coupon ->
        val raw = coupon.endAt?.trim().orEmpty()
        raw.isNotEmpty() &&
            dedupKey(raw) !in notifiedKeys &&
            isExpiringSoon(parseEndAt(raw), nowMillis)
    }

    /** 提醒正文的逐条明细（每张卡一行）；页面在它之后接一句 [REMINDER_NOTE]。 */
    fun reminderLines(
        coupons: List<KqcxAuth.AssetCoupon>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<String> = coupons.mapNotNull { coupon ->
        val raw = coupon.endAt?.trim().orEmpty()
        val endAt = parseEndAt(raw, zone) ?: return@mapNotNull null
        val date = Instant.ofEpochMilli(endAt).atZone(zone).toLocalDate()
        "· ${coupon.titleText} · ${coupon.deviceLabel}：有效期至 $date（${remainingText(endAt, nowMillis, zone)}）"
    }
}
