package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 成绩/考试变动提醒的固定口径（DESIGN §4.33）。
 */
class ScoreAlertDefaultsTest {

    @Test
    fun `档位表是五个值且默认六小时`() {
        assertEquals(listOf(1, 3, 6, 12, 24), ScoreAlertDefaults.INTERVAL_CHOICES)
        assertEquals(6, ScoreAlertDefaults.INTERVAL_DEFAULT)
    }

    @Test
    fun `合法值原样通过`() {
        for (value in ScoreAlertDefaults.INTERVAL_CHOICES) {
            assertEquals(value, ScoreAlertDefaults.coerceIntervalHours(value))
            assertEquals(
                ScoreAlertDefaults.INTERVAL_CHOICES.indexOf(value),
                ScoreAlertDefaults.intervalChoiceIndex(value),
            )
        }
    }

    @Test
    fun `非法值归到默认档`() {
        assertEquals(6, ScoreAlertDefaults.coerceIntervalHours(0))
        assertEquals(6, ScoreAlertDefaults.coerceIntervalHours(-3))
        assertEquals(6, ScoreAlertDefaults.coerceIntervalHours(5))
        assertEquals(6, ScoreAlertDefaults.coerceIntervalHours(48))
        assertEquals(2, ScoreAlertDefaults.intervalChoiceIndex(999))
    }

    @Test
    fun `展示文案`() {
        assertEquals("每 6 小时", ScoreAlertDefaults.intervalLabel(6))
        assertEquals("每 1 小时", ScoreAlertDefaults.intervalLabel(1))
        assertEquals("每 24 小时", ScoreAlertDefaults.intervalLabel(24))
        // 非法值兜底到默认档后再出文案
        assertEquals("每 6 小时", ScoreAlertDefaults.intervalLabel(0))
    }
}
