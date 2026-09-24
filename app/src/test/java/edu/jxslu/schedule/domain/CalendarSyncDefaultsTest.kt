package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 日历同步固定口径（DESIGN §4.12）：提醒选项表与文案。
 * 设置页轮选器、DataStore 默认值、CalendarSyncer 默认提醒三处共用一份表，锁死不变量。
 */
class CalendarSyncDefaultsTest {

    @Test
    fun `默认提醒 20 分钟`() {
        assertEquals(20, CalendarSyncDefaults.DEFAULT_REMINDER_MINUTES)
    }

    @Test
    fun `选项表 0 到 120 步长 5`() {
        val choices = CalendarSyncDefaults.REMINDER_CHOICES
        assertEquals(0, choices.first())
        assertEquals(120, choices.last())
        assertEquals(25, choices.size) // (120 / 5) + 1
        assertEquals((0..120 step 5).toList(), choices)
    }

    @Test
    fun `值域夹取`() {
        assertEquals(0, CalendarSyncDefaults.coerceReminderMinutes(-1))
        assertEquals(120, CalendarSyncDefaults.coerceReminderMinutes(500))
        assertEquals(20, CalendarSyncDefaults.coerceReminderMinutes(20))
    }

    @Test
    fun `存储值到选项下标`() {
        assertEquals(0, CalendarSyncDefaults.reminderChoiceIndex(0))
        assertEquals(4, CalendarSyncDefaults.reminderChoiceIndex(20))
        assertEquals(24, CalendarSyncDefaults.reminderChoiceIndex(120))
        // 夹边：非法值不越界
        assertEquals(0, CalendarSyncDefaults.reminderChoiceIndex(-5))
        assertEquals(24, CalendarSyncDefaults.reminderChoiceIndex(121))
        // 非 5 倍数向下取档
        assertEquals(4, CalendarSyncDefaults.reminderChoiceIndex(23))
    }

    @Test
    fun `文案 0 为不提醒其余提前 N 分钟`() {
        assertEquals("不提醒", CalendarSyncDefaults.reminderLabel(0))
        assertEquals("提前 20 分钟", CalendarSyncDefaults.reminderLabel(20))
        assertEquals("不提醒", CalendarSyncDefaults.reminderLabel(-3)) // 夹取后为 0
    }

    @Test
    fun `同步成功文案提醒齐了才报成功`() {
        assertEquals(
            "已同步 42 条课程到手机日历（提前 20 分钟）",
            CalendarSyncDefaults.syncSuccessMessage(count = 42, reminderMinutes = 20, reminderMissing = 0),
        )
        assertEquals(
            "已同步 42 条课程到手机日历（不提醒）",
            CalendarSyncDefaults.syncSuccessMessage(count = 42, reminderMinutes = 0, reminderMissing = 0),
        )
        // 提醒被 ROM 吞掉时如实说，不报「已同步」了事
        assertEquals(
            "已同步 42 条课程到手机日历，其中 3 条没写上提醒，手机日历可能不提醒",
            CalendarSyncDefaults.syncSuccessMessage(count = 42, reminderMinutes = 20, reminderMissing = 3),
        )
    }
}
