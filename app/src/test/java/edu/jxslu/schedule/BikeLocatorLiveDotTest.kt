package edu.jxslu.schedule

import edu.jxslu.schedule.ui.ebike.LocationFix
import edu.jxslu.schedule.ui.ebike.shouldMoveDot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝点实时更新的挪点口径（DESIGN §3.9）：挪远了要跟、精度大涨要换、原地抖动不动。
 *
 * 纬度 0.0001° ≈ 11 米，样本位移都按这个折算。
 */
class BikeLocatorLiveDotTest {

    private fun fix(
        lat: Double,
        lng: Double,
        accuracy: Float = 20f,
        time: Long = 0L,
    ): LocationFix = LocationFix(lat, lng, accuracy, time)

    /** 第一次有读数：不管准不准，蓝点先画上再说。 */
    @Test
    fun firstFixAlwaysMoves() {
        assertTrue(shouldMoveDot(null, fix(28.0, 112.0, accuracy = 0f)))
    }

    /** 站定不动，GPS 每两秒的读数都带几米随机抖动：不挪点。 */
    @Test
    fun stationaryJitterHeld() {
        val previous = fix(28.0000, 112.0000, accuracy = 15f)
        val next = fix(28.00001, 112.00001, accuracy = 15f)
        assertFalse(shouldMoveDot(previous, next))
    }

    /** 真走动了（约 11 米）：蓝点跟着人走。 */
    @Test
    fun realMovementFollows() {
        val previous = fix(28.0000, 112.0000, accuracy = 15f)
        val next = fix(28.0001, 112.0000, accuracy = 15f)
        assertTrue(shouldMoveDot(previous, next))
    }

    /** 原地没动，但网络点换成了准 4 倍的 GPS 点：换上更准的位置。 */
    @Test
    fun accuracyGainSwaps() {
        val previous = fix(28.0000, 112.0000, accuracy = 40f)
        val next = fix(28.000005, 112.000005, accuracy = 10f)
        assertTrue(shouldMoveDot(previous, next))
    }

    /** 原地没动、精度只小涨一点：不值得挪。 */
    @Test
    fun marginalAccuracyGainHeld() {
        val previous = fix(28.0000, 112.0000, accuracy = 20f)
        val next = fix(28.000005, 112.000005, accuracy = 15f)
        assertFalse(shouldMoveDot(previous, next))
    }

    /** 系统没给精度（0）时不走精度口径，只认位移，也不能把点挪没了。 */
    @Test
    fun unknownAccuracyFallsBackToMovement() {
        val previous = fix(28.0000, 112.0000, accuracy = 0f)
        val jitter = fix(28.000005, 112.000005, accuracy = 0f)
        assertFalse(shouldMoveDot(previous, jitter))
        val moved = fix(28.0001, 112.0000, accuracy = 0f)
        assertTrue(shouldMoveDot(previous, moved))
    }
}
