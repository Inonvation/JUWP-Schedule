package edu.jxslu.schedule.domain

import edu.jxslu.schedule.data.wenqu.WenquActivity
import java.time.LocalDateTime
import java.time.ZoneId

internal val hour = 60 * 60 * 1000L
internal val zone = ZoneId.of("Asia/Shanghai")

internal fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
    LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

/** 可报名的基线：招募中 + 未满 + 没报过 + 在报名窗口内 + 没被限制。 */
internal fun blockReason(
        status: Int = 1,
        applied: Boolean = false,
        peopleNum: Int = 20,
        balanceNum: Int = 5,
        applyStart: String = "26-10-01 00:00",
        applyEnd: String = "26-10-20 00:00",
        blacklisted: Boolean = false,
        now: Long = at(2026, 10, 11, 12, 0),
    ): String = WenquRules.applyBlockReason(
        status = status,
        applied = applied,
        peopleNum = peopleNum,
        balanceNum = balanceNum,
        applyStartTime = applyStart,
        applyEndTime = applyEnd,
        blacklisted = blacklisted,
        now = now,
        zone = zone,
    )

/** 活动夹具：默认「招募中 + 没满 + 没报过 + 在报名窗口内」，即**可报名**的基线。 */
internal fun activity(
        id: Long = 1L,
        title: String = "爱心家教志愿服务",
        status: Int = 1,
        applied: Boolean = false,
        peopleNum: Int = 20,
        balanceNum: Int = 5,
        applyStart: String = "26-10-01 00:00",
        applyEnd: String = "26-10-20 00:00",
    ): WenquActivity = WenquActivity(
        id = id,
        title = title,
        status = status,
        applied = applied,
        peopleNum = peopleNum,
        balanceNum = balanceNum,
        applyStartTime = applyStart,
        applyEndTime = applyEnd,
    )
