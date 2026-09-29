package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首启两份声明的同意判定、锁时长与文案来源（DESIGN §3.16）。
 */
class NoticeConsentTest {

    private val v = FirstRunNotices.VERSION

    @Test
    fun isConsentedComparesVersions() {
        assertFalse(NoticeConsent.isConsented(null, v))
        assertFalse(NoticeConsent.isConsented(0, v))
        assertFalse(NoticeConsent.isConsented(v - 1, v))
        assertTrue(NoticeConsent.isConsented(v, v))
        // 记录比当前版本新（装过新版本又退回旧包）也算同意过：不该拿旧文案再弹一次
        assertTrue(NoticeConsent.isConsented(v + 1, v))
    }

    @Test
    fun pendingKeepsOrderAndSkipsConsented() {
        // 全新安装：两份都要弹，顺序 = 用户须知 → 免责声明
        assertEquals(
            listOf(FirstRunNotice.UserNotice, FirstRunNotice.Disclaimer),
            NoticeConsent.pendingNotices(emptyMap(), v),
        )
        // 只确认过用户须知
        assertEquals(
            listOf(FirstRunNotice.Disclaimer),
            NoticeConsent.pendingNotices(mapOf(FirstRunNotice.UserNotice to v), v),
        )
        // 两份都确认过：队列为空，一个都不弹
        assertTrue(
            NoticeConsent.pendingNotices(
                mapOf(FirstRunNotice.UserNotice to v, FirstRunNotice.Disclaimer to v),
                v,
            ).isEmpty(),
        )
    }

    @Test
    fun versionBumpMakesBothPendingAgain() {
        val consented = mapOf(
            FirstRunNotice.UserNotice to v,
            FirstRunNotice.Disclaimer to v,
        )
        assertEquals(2, NoticeConsent.pendingNotices(consented, v + 1).size)
    }

    @Test
    fun closeLockIsThreeSecondsThenFiveSeconds() {
        assertEquals(3_000L, FirstRunNotices.closeLockMs(FirstRunNotice.UserNotice))
        assertEquals(5_000L, FirstRunNotices.closeLockMs(FirstRunNotice.Disclaimer))
    }

    /** 免责声明那份必须与 [Disclaimer] 同源——两边各存一份迟早改漏一处。 */
    @Test
    fun disclaimerNoticeReusesDisclaimerText() {
        assertEquals(Disclaimer.INTRO, FirstRunNotices.intro(FirstRunNotice.Disclaimer))
        assertEquals(Disclaimer.ITEMS, FirstRunNotices.items(FirstRunNotice.Disclaimer))
    }

    /** 两份须知不重复：用户须知只讲事实，责任条款只在免责声明里说一次。 */
    @Test
    fun userNoticeDoesNotRepeatDisclaimerItems() {
        val user = FirstRunNotices.items(FirstRunNotice.UserNotice)
        assertTrue(user.none { it in Disclaimer.ITEMS })
    }
}
