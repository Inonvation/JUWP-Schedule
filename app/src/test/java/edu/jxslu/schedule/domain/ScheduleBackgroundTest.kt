package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表页背景图的纯逻辑（DESIGN §4.21）。
 *
 * 渲染与文件层都只调这几个函数，所以这里钉住的是**口径**：默认值是「没有背景」、
 * 模糊档位与解码尺寸一一对应、目标尺寸不超过屏幕、文件名白名单挡得住越界取值。
 */
class ScheduleBackgroundTest {

    /** 默认态 = 没有背景，且三个数值参数处在「不改变图片原样」的位置。 */
    @Test
    fun defaultsMeanNoBackground() {
        val prefs = TimetablePrefs()
        assertNull(prefs.bgImageName)
        assertEquals(1f, prefs.bgImageOpacity, 0f)
        assertEquals(0f, prefs.bgImageDim, 0f)
        assertEquals(0f, prefs.bgImageBlur, 0f)
        assertEquals(BgScale.Fill, prefs.bgImageScale)
        assertTrue(TimetablePrefs.MinBgImageOpacity in 0f..1f)
        assertTrue(TimetablePrefs.MaxBgImageDim in 0f..1f)
    }

    /** 老 JSON（写背景功能之前存的）解码后落到「没有背景」，不能凭空冒出一张图。 */
    @Test
    fun legacyJsonWithoutBackgroundFieldsStaysOff() {
        val decoded = TimetablePrefs.decode("""{"showWeekend":true,"cellOpacity":0.8}""")
        assertNull(decoded.bgImageName)
        assertEquals(BgScale.Fill, decoded.bgImageScale)
        assertEquals(0f, decoded.bgImageBlur, 0f)
    }

    /** 背景偏好要能往返：encode → decode 后逐字段一致。 */
    @Test
    fun backgroundPrefsSurviveRoundTrip() {
        val stored = TimetablePrefs(
            bgImageName = "bg_20260922_101500_1a2b.jpg",
            bgImageOpacity = 0.6f,
            bgImageDim = 0.35f,
            bgImageBlur = 0.5f,
            bgImageScale = BgScale.Tile,
        ).encode()
        val decoded = TimetablePrefs.decode(stored)
        assertEquals("bg_20260922_101500_1a2b.jpg", decoded.bgImageName)
        assertEquals(0.6f, decoded.bgImageOpacity, 0f)
        assertEquals(0.35f, decoded.bgImageDim, 0f)
        assertEquals(0.5f, decoded.bgImageBlur, 0f)
        assertEquals(BgScale.Tile, decoded.bgImageScale)
    }

    /** 模糊档位：0 = 原分辨率档，1 = 最糊档，越界夹取而不是抛异常。 */
    @Test
    fun blurMapsToDecodeLongSide() {
        assertEquals(ScheduleBackground.BLUR_LONG_SIDES.first(), ScheduleBackground.decodeLongSide(0f))
        assertEquals(ScheduleBackground.BLUR_LONG_SIDES.last(), ScheduleBackground.decodeLongSide(1f))
        assertEquals(ScheduleBackground.BLUR_LONG_SIDES.first(), ScheduleBackground.decodeLongSide(-3f))
        assertEquals(ScheduleBackground.BLUR_LONG_SIDES.last(), ScheduleBackground.decodeLongSide(9f))

        // 单调不增：模糊越强，解码得越小
        val samples = (0..10).map { ScheduleBackground.decodeLongSide(it / 10f) }
        assertEquals(samples.sortedDescending(), samples)
        assertTrue("最糊档必须明显小于最清晰档", samples.last() < samples.first() / 2)
    }

    /** 目标尺寸取「模糊档位」与「屏幕长边」的较小者，且给下限兜底，不给 0 或负数。 */
    @Test
    fun targetLongSideNeverExceedsScreen() {
        assertEquals(1080, ScheduleBackground.targetLongSide(1080, 0f))
        assertEquals(100, ScheduleBackground.targetLongSide(100, 0f))
        assertEquals(
            ScheduleBackground.BLUR_LONG_SIDES.last(),
            ScheduleBackground.targetLongSide(4000, 1f),
        )
        assertEquals(64, ScheduleBackground.targetLongSide(0, 0f))
        assertEquals(64, ScheduleBackground.targetLongSide(-500, 0f))
    }

    /**
     * 文件名白名单。偏好是本地 JSON，正常不会坏；坏掉时读路径必须拒绝拼出
     * `filesDir` 之外的路径，而不是老老实实去读。
     */
    @Test
    fun invalidFileNamesAreRejected() {
        assertTrue(ScheduleBackground.isValidFileName("bg_20260922_101500_1a2b.jpg"))
        assertTrue(ScheduleBackground.isValidFileName("bg_1.png"))

        assertFalse("空串", ScheduleBackground.isValidFileName(""))
        assertFalse("空白", ScheduleBackground.isValidFileName("   "))
        assertFalse("上跳目录", ScheduleBackground.isValidFileName("../notes_img/a.jpg"))
        assertFalse("子目录", ScheduleBackground.isValidFileName("sub/a.jpg"))
        assertFalse("反斜杠", ScheduleBackground.isValidFileName("sub\\a.jpg"))
        assertFalse("当前目录", ScheduleBackground.isValidFileName("."))
        assertFalse("上级目录", ScheduleBackground.isValidFileName(".."))
        assertFalse("超长", ScheduleBackground.isValidFileName("a".repeat(65)))
    }

    /** 三档缩放都有中文名，面板 chip 直接取 label，缺一个就是空 chip。 */
    @Test
    fun everyScaleHasLabel() {
        assertEquals(3, BgScale.entries.size)
        assertTrue(BgScale.entries.all { it.label.isNotBlank() })
    }
}
