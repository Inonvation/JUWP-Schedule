package edu.jxslu.schedule.data.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import edu.jxslu.schedule.domain.EbikeFreeRide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * 共享单车免费时长倒计时 → 系统日历（DESIGN §3.9，2026-09-23 由 App 通知改系统日历）。
 *
 * 事件锚在**免费结束那一刻**（计时起点 + 15 分钟），挂两条提醒：[EbikeFreeRide.reminderOffsets]。
 * 之所以不锚在计时起点：系统日历的 `Reminders.MINUTES` 是「事件开始前 N 分钟」且非负，
 * 「免费结束前 N 分钟」只能靠把结束时刻本身设成事件开始时间来表达。
 *
 * 写入账户复用课表同步的口径（[CalendarSyncer.firstWritableCalendarId]，本机第一个可写日历），
 * 但**标记独立**：description 带 [MARKER]，而课表那套只认 [CalendarSyncer.SYNC_MARKER]，
 * 两边的删除/重写互不误伤。删事件走 DataStore 里存的 id；按标记的全量删除只在
 * 「冷启动兜底清扫」时用（清了 DataStore 或上一轮删除失败留下的孤儿）。
 *
 * 权限（READ/WRITE_CALENDAR）由 UI 层运行时申请，本类不做检查——无权限时
 * ContentResolver 抛 SecurityException，包装成 [CreateResult.NoPermission] 返回。
 */
object EbikeCalendarEvents {

    /** 本类写入事件的标记（与课表的 `水贝贝课表同步` 区分开）。 */
    internal const val MARKER = "水贝贝骑行提醒"

    private const val MINUTE_MS = 60_000L

    sealed interface CreateResult {
        /** 写入成功，[eventId] 供后续按 id 删除。 */
        data class Ok(val eventId: Long) : CreateResult

        /** 日历权限在调用瞬间不可用（UI 层拒绝或权限被系统回收）。 */
        data object NoPermission : CreateResult

        /** 设备上没有可写日历账户（用户未登录任何日历账号）。 */
        data object NoCalendarAccount : CreateResult

        data class Error(val message: String) : CreateResult
    }

    sealed interface DeleteResult {
        data class Deleted(val count: Int) : DeleteResult
        data object NoPermission : DeleteResult
        data class Error(val message: String) : DeleteResult
    }

    /**
     * 创建（或重建）免费时长事件 + 两条提醒。
     * IO 全部在 [Dispatchers.IO]。
     */
    suspend fun create(
        context: Context,
        startAtMillis: Long,
        leadMinutes: Int,
    ): CreateResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val calendarId = try {
            CalendarSyncer.firstWritableCalendarId(resolver)
        } catch (e: SecurityException) {
            return@withContext CreateResult.NoPermission
        } ?: return@withContext CreateResult.NoCalendarAccount

        try {
            val freeEnd = EbikeFreeRide.freeEndMillis(startAtMillis)
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, EbikeFreeRide.EVENT_TITLE)
                put(
                    CalendarContract.Events.DESCRIPTION,
                    "${EbikeFreeRide.eventDescription(leadMinutes)}\n$MARKER",
                )
                put(CalendarContract.Events.DTSTART, freeEnd)
                put(
                    CalendarContract.Events.DTEND,
                    freeEnd + EbikeFreeRide.EVENT_DURATION_MINUTES * MINUTE_MS,
                )
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                // 不占用户日程的忙碌时段：它只是一条个人提醒
                put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
                put(CalendarContract.Events.HAS_ALARM, 1)
            }
            val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return@withContext CreateResult.Error("写入日历失败")
            val eventId = ContentUris.parseId(uri)
            EbikeFreeRide.reminderOffsets(leadMinutes).forEach { minutes ->
                insertReminder(resolver, eventId, minutes)
            }
            CreateResult.Ok(eventId)
        } catch (e: SecurityException) {
            CreateResult.NoPermission
        } catch (e: Exception) {
            CreateResult.Error(e.message ?: "写入日历失败")
        }
    }

    /** 按事件 id 删除（用户已在日历里手动删掉时 count = 0，不算失败）。 */
    suspend fun deleteById(context: Context, eventId: Long): DeleteResult =
        withContext(Dispatchers.IO) {
            if (eventId <= 0L) return@withContext DeleteResult.Deleted(count = 0)
            try {
                val count = context.contentResolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                    null,
                    null,
                )
                DeleteResult.Deleted(count = count)
            } catch (e: SecurityException) {
                DeleteResult.NoPermission
            } catch (e: Exception) {
                DeleteResult.Error(e.message ?: "删除日历事件失败")
            }
        }

    /**
     * 按标记删除本 App 写的全部免费时长事件（孤儿清扫；课表事件不受影响）。
     *
     * 只在「没有进行中的计时」时调用——同时只该有一条这样的日历事件。
     */
    suspend fun deleteAll(context: Context): DeleteResult = withContext(Dispatchers.IO) {
        try {
            val count = context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events.DESCRIPTION} LIKE ?",
                arrayOf("%$MARKER%"),
            )
            DeleteResult.Deleted(count = count)
        } catch (e: SecurityException) {
            DeleteResult.NoPermission
        } catch (e: Exception) {
            DeleteResult.Error(e.message ?: "删除日历事件失败")
        }
    }

    /**
     * 给事件追加一条提前 [minutes] 分钟的提醒；单条失败不拖垮整次写入。
     *
     * 用 `METHOD_ALERT` 而不是 `METHOD_DEFAULT`：2026-09-23 在 Redmi K70（澎湃OS）实测，
     * `method=0` 的提醒**不会在 CalendarAlerts 里落行**，也就不会响——同一台机器上
     * 能响的提醒清一色 `method=1`（162 条 method=0 无一有告警行，13 条 method=1 全有）。
     * 别改回 `METHOD_DEFAULT`。
     */
    private fun insertReminder(resolver: ContentResolver, eventId: Long, minutes: Int) {
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        runCatching { resolver.insert(CalendarContract.Reminders.CONTENT_URI, values) }
    }
}
