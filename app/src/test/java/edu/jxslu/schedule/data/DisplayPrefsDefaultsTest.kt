package edu.jxslu.schedule.data

import edu.jxslu.schedule.data.prefs.DisplayPrefs
import edu.jxslu.schedule.domain.StartPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显示偏好默认值契约（新装用户第一眼看到的形态；改默认值必须是有意的需求变更）。
 */
class DisplayPrefsDefaultsTest {

    @Test
    fun lifeTabIsOnByDefault() {
        assertTrue("生活页默认开（DESIGN §3.13）", DisplayPrefs().lifeTabEnabled)
    }

    @Test
    fun existingSwitchesKeepTheirDefaults() {
        val prefs = DisplayPrefs()
        assertFalse("悬浮导航栏默认关", prefs.floatingNavBar)
        assertFalse("一卡通付款码默认关（涉及凭证）", prefs.campusCardEnabled)
        assertTrue("触感反馈默认开", prefs.hapticsEnabled)
        assertTrue("开水双击确认默认开", prefs.waterRequireDoubleClick)
        assertTrue("百宝箱默认开（DESIGN §4.47）", prefs.toolboxEnabled)
    }

    @Test
    fun fontScaleIsLockedByDefault() {
        assertTrue(
            "字体缩放默认锁定（「固定字体大小」默认开，不随系统「字体大小」缩放）",
            DisplayPrefs().fontScaleLocked,
        )
    }

    @Test
    fun startPageDefaultsToToday() {
        assertEquals("启动页默认今日（DESIGN §3.3）", StartPage.Today, DisplayPrefs().startPage)
    }
}
