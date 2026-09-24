package edu.jxslu.schedule.data.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import edu.jxslu.schedule.domain.CalendarSyncDefaults
import edu.jxslu.schedule.domain.ScheduleExporter.CourseEvent
import edu.jxslu.schedule.domain.ScheduleExporter.teacherDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 课表一键同步到系统日历（DESIGN §4.12）。
 *
 * 策略：**删除再写入**——先删本 App 写入的全部事件（[CalendarContract.Events.CUSTOM_APP_PACKAGE]
 * 等于本包名，另用 [LEGACY_SYNC_MARKER] 认领升级前写下的历史事件），
 * 再把展开后的课程事件整表插入第一个可写日历账户。教室/时间变更后重同步不留脏数据；
 * 代价是用户对旧事件的手工编辑被覆盖，以 App 课表为准。
 *
 * 权限（READ/WRITE_CALENDAR）由 UI 层运行时申请，本类不做检查——无权限时
 * ContentResolver 会直接抛 SecurityException，包装成 [CalendarSyncResult.NoPermission] 返回。
 */
object CalendarSyncer {

    /**
     * 2026-09-24 之前写在事件 `description` 里的归属标记。
     *
     * 它会显示在日历事件的正文里（用户要求「只保留关键信息」），已停用；
     * 常量留给删除条件兜底——升级前同步过的历史事件仍要靠它认领。
     */
    internal const val LEGACY_SYNC_MARKER = "水贝贝课表同步"

    /** 默认提前提醒分钟数（需求口径）；可被「我的 → 日历同步」的全局设置覆盖。 */
    const val DEFAULT_REMINDER_MINUTES = CalendarSyncDefaults.DEFAULT_REMINDER_MINUTES

    sealed interface CalendarSyncResult {
        /**
         * 成功写入 [count] 个事件；[reminderMissing] 是其中没能在日历 Provider 里
         * 确认落库提醒的条数（写入被 ROM 丢弃时才非 0，正常为 0）。
         */
        data class Success(val count: Int, val reminderMissing: Int = 0) : CalendarSyncResult

        /** 成功删除 [count] 个本 App 写入的事件。 */
        data class Deleted(val count: Int) : CalendarSyncResult

        /** 设备上没有可写日历账户（用户未登录任何日历账号）。 */
        data object NoCalendarAccount : CalendarSyncResult

        /** 日历权限在调用瞬间仍不可用（理论上 UI 层已申请过）。 */
        data object NoPermission : CalendarSyncResult

        /** 其他 ContentResolver 失败。 */
        data class Error(val message: String) : CalendarSyncResult
    }

    /**
     * 要写入的日历 `_ID`；无账户返回 null。
     *
     * 不在 selection 里引用 [CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL]——
     * 部分厂商 Provider 对该列做 selection 会抛 IllegalArgumentException，
     * 改为取回后在客户端过滤（CAL_ACCESS_CONTRIBUTOR=500 起可写）。
     * 选哪个由 [pickWritableCalendarId] 决定（纯函数，单测钉死）。
     */
    internal fun firstWritableCalendarId(resolver: android.content.ContentResolver): Long? =
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.VISIBLE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val levelIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            val visibleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE)
            val candidates = buildList {
                while (cursor.moveToNext()) {
                    add(
                        CalendarCandidate(
                            id = cursor.getLong(idIdx),
                            accessLevel = cursor.getInt(levelIdx),
                            visible = cursor.getInt(visibleIdx) == 1,
                        ),
                    )
                }
            }
            pickWritableCalendarId(candidates)
        }

    /**
     * 同步课程事件到系统日历。IO 全部在 [Dispatchers.IO]。
     *
     * @param events 已展开的课程事件（[edu.jxslu.schedule.domain.ScheduleExporter.expandEvents]）
     * @param reminderMinutes 提前提醒分钟数；≤0 不写提醒（全局设置，「我的 → 日历同步」）
     */
    suspend fun sync(
        context: Context,
        events: List<CourseEvent>,
        reminderMinutes: Int = DEFAULT_REMINDER_MINUTES,
    ): CalendarSyncResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            if (events.isEmpty()) return@withContext CalendarSyncResult.Success(count = 0)
            // 归属标记用调用方包名：debug 与 release 是两个包，各自只认领自己写的事件
            val packageName = context.packageName

            val calendarId = try {
                firstWritableCalendarId(resolver)
            } catch (e: SecurityException) {
                return@withContext CalendarSyncResult.NoPermission
            } ?: return@withContext CalendarSyncResult.NoCalendarAccount

            try {
                // 删旧：只删本 App 写入的事件
                resolver.delete(
                    CalendarContract.Events.CONTENT_URI,
                    ownEventsWhere(),
                    ownEventsArgs(packageName),
                )

                val insertedIds = mutableListOf<Long>()
                for (event in events) {
                    val eventUri = insertEvent(resolver, calendarId, packageName, event) ?: continue
                    val eventId = runCatching { ContentUris.parseId(eventUri) }.getOrNull() ?: continue
                    insertedIds += eventId
                    if (reminderMinutes > 0) insertReminder(resolver, eventId, reminderMinutes)
                }
                // 回读一次提醒落库情况：ROM 静默丢弃提醒时不能报「已同步成功」了事
                val reminderMissing =
                    if (reminderMinutes <= 0) 0
                    else insertedIds.size - countEventsWithReminder(resolver, insertedIds)
                CalendarSyncResult.Success(count = insertedIds.size, reminderMissing = reminderMissing)
            } catch (e: SecurityException) {
                CalendarSyncResult.NoPermission
            } catch (e: Exception) {
                CalendarSyncResult.Error(e.message ?: "写入日历失败")
            }
        }

    /**
     * 一键删除本 App 写入的全部日历事件（「我的 → 日历同步」入口）。
     * 只删本 App 认领的事件，用户自己的日历内容不受影响。
     */
    suspend fun deleteSynced(context: Context): CalendarSyncResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            try {
                val deleted = resolver.delete(
                    CalendarContract.Events.CONTENT_URI,
                    ownEventsWhere(),
                    ownEventsArgs(context.packageName),
                )
                CalendarSyncResult.Deleted(count = deleted)
            } catch (e: SecurityException) {
                CalendarSyncResult.NoPermission
            } catch (e: Exception) {
                CalendarSyncResult.Error(e.message ?: "删除日历事件失败")
            }
        }

    /**
     * 本 App 写入事件的删除条件：认 `CUSTOM_APP_PACKAGE`，另用历史标记兜底
     * （升级前写下的旧事件正文里有 [LEGACY_SYNC_MARKER]，不认领就会在重同步后重复）。
     */
    private fun ownEventsWhere(): String =
        "(${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ?) OR " +
            "(${CalendarContract.Events.DESCRIPTION} LIKE ?)"

    private fun ownEventsArgs(packageName: String): Array<String> =
        arrayOf(packageName, "%$LEGACY_SYNC_MARKER%")

    /** 插入单个事件，返回事件 Uri（供追加提醒）；失败返回 null。 */
    private fun insertEvent(
        resolver: android.content.ContentResolver,
        calendarId: Long,
        packageName: String,
        event: CourseEvent,
    ): Uri? {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.name)
            // 归属标记走 CUSTOM_APP_PACKAGE（日历 App 不展示这一列），正文只留关键信息
            put(CalendarContract.Events.CUSTOM_APP_PACKAGE, packageName)
            teacherDescription(event.teacher).takeIf { it.isNotEmpty() }
                ?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            if (event.position.isNotBlank()) {
                put(CalendarContract.Events.EVENT_LOCATION, event.position)
            }
            put(CalendarContract.Events.DTSTART, event.start.toEpochMilli())
            put(CalendarContract.Events.DTEND, event.end.toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
        }
        return resolver.insert(CalendarContract.Events.CONTENT_URI, values)
    }

    /**
     * 给刚插入的事件写一条提前 [minutes] 分钟的提醒。
     *
     * METHOD 取 [CalendarContract.Reminders.METHOD_ALERT] 而不是 `METHOD_DEFAULT`：
     * 平台文档说两者都会被处理，但 DEFAULT 的含义是「听账户默认值」，
     * 第三方写入的场景下没有账户默认值可依，写死 ALERT 语义才明确（2026-09-24）。
     */
    private fun insertReminder(
        resolver: android.content.ContentResolver,
        eventId: Long,
        minutes: Int,
    ) {
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        runCatching { resolver.insert(CalendarContract.Reminders.CONTENT_URI, values) }
    }

    /**
     * 统计 [eventIds] 里在 Reminders 表真的有提醒的事件数。
     *
     * 分块查询（SQLite 的绑定变量上限 999），单块失败按「查不到」计入缺少，
     * 不抛——回读只为了把「提醒没落库」这件事讲出来，不该让它中断同步。
     */
    private fun countEventsWithReminder(
        resolver: android.content.ContentResolver,
        eventIds: List<Long>,
    ): Int {
        if (eventIds.isEmpty()) return 0
        val withReminder = mutableSetOf<Long>()
        for (chunk in eventIds.chunked(400)) {
            val placeholders = chunk.joinToString(",") { "?" }
            runCatching {
                resolver.query(
                    CalendarContract.Reminders.CONTENT_URI,
                    arrayOf(CalendarContract.Reminders.EVENT_ID),
                    "${CalendarContract.Reminders.EVENT_ID} IN ($placeholders)",
                    chunk.map { it.toString() }.toTypedArray(),
                    null,
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Reminders.EVENT_ID)
                    while (cursor.moveToNext()) withReminder += cursor.getLong(idIdx)
                }
            }
        }
        return withReminder.size
    }
}

/** [LocalDateTime] → 纪元毫秒（系统默认时区），日历 Provider 口径。 */
private fun java.time.LocalDateTime.toEpochMilli(): Long =
    atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

/** [pickWritableCalendarId] 的输入：只带判断需要的三列。 */
internal data class CalendarCandidate(
    val id: Long,
    val accessLevel: Int,
    val visible: Boolean,
)

/**
 * 从候选日历里挑要写入的 `_ID`；没有可写的返回 null。
 *
 * 可见的可写日历优先：写进隐藏日历的事件不会在日历 App 里露出来，提醒也未必响，
 * 而取「第一个可写」在部分设备上正好会落到隐藏账户。都没有可见的才退到隐藏的，
 * 让同步至少能成。`minAccessLevel` 与 [CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR]
 * 同源（500 起可写）。
 */
internal fun pickWritableCalendarId(
    candidates: List<CalendarCandidate>,
    minAccessLevel: Int = CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
): Long? =
    candidates.firstOrNull { it.visible && it.accessLevel >= minAccessLevel }?.id
        ?: candidates.firstOrNull { it.accessLevel >= minAccessLevel }?.id
