package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 悬浮球几何（DESIGN §3.1）：分数 ↔ 像素、夹取。位置不吸附边缘。 */
class FloatingBallTest {

    private val eps = 0.0001f

    @Test
    fun defaultIsBottomRight() {
        assertEquals(1f, FloatingBall.DefaultPosition.x, eps)
        assertEquals(1f, FloatingBall.DefaultPosition.y, eps)
    }

    @Test
    fun offsetScalesByTrack() {
        assertEquals(0f, FloatingBall.offsetPx(0f, 200f), eps)
        assertEquals(100f, FloatingBall.offsetPx(0.5f, 200f), eps)
        assertEquals(200f, FloatingBall.offsetPx(1f, 200f), eps)
    }

    @Test
    fun offsetClampsFractionAndHandlesNegativeTrack() {
        assertEquals(200f, FloatingBall.offsetPx(1.7f, 200f), eps)
        assertEquals(0f, FloatingBall.offsetPx(-0.3f, 200f), eps)
        // 未布局（轨道为负）不产生负偏移
        assertEquals(0f, FloatingBall.offsetPx(1f, -50f), eps)
    }

    @Test
    fun fractionInvertsOffset() {
        assertEquals(0.5f, FloatingBall.fraction(100f, 200f), eps)
        assertEquals(1f, FloatingBall.fraction(260f, 200f), eps)
        assertEquals(0f, FloatingBall.fraction(-40f, 200f), eps)
    }

    @Test
    fun fractionHandlesZeroTrack() {
        assertEquals(0f, FloatingBall.fraction(100f, 0f), eps)
    }

    @Test
    fun clampBoundsBothAxes() {
        val clamped = FloatingBall.clamp(FloatingBall.Position(1.9f, -0.2f))
        assertEquals(1f, clamped.x, eps)
        assertEquals(0f, clamped.y, eps)
    }

    @Test
    fun clampRejectsNonFinite() {
        val nan = FloatingBall.clamp(FloatingBall.Position(Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(FloatingBall.DefaultPosition.x, nan.x, eps)
        assertEquals(FloatingBall.DefaultPosition.y, nan.y, eps)
    }
}
