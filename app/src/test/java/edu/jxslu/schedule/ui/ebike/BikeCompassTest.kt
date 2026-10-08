package edu.jxslu.schedule.ui.ebike

import android.hardware.SensorManager
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 罗盘朝向的纯函数（DESIGN §3.9「蓝点朝向」）：环形插值 / 节流判定 / remap 轴向。
 * 传感器与 `SensorManager` 矩阵链不可在 JVM 测，不硬测——那条链的真实性交给真机点验。
 */
class BikeCompassTest {

    // ---------- smoothAngle：低通滤波 + 环形插值 ----------

    @Test
    fun `首帧直接落位不从零度爬`() {
        assertEquals(359f, BikeCompass.smoothAngle(null, 359f, BikeCompass.SMOOTHING_FACTOR))
        assertEquals(0f, BikeCompass.smoothAngle(null, 0f, BikeCompass.SMOOTHING_FACTOR))
    }

    @Test
    fun `普通角度向目标收敛`() {
        // 0.15 的系数：100 + (200 - 100) * 0.15 = 115（一步走差值的 15%）
        assertEquals(115f, BikeCompass.smoothAngle(100f, 200f, 0.15f))
    }

    @Test
    fun `环形短路不绕远路`() {
        // 359 → 2：走 +3° 的短路（359 + 3 * 0.15 = 359.45），不是 -357° 的远路
        val smoothed = BikeCompass.smoothAngle(359f, 2f, 0.15f)
        assertTrue("实际 $smoothed", smoothed > 358.9f && smoothed < 360f)
        // 0 → 359：走 -1° 的短路（0 - 1 * 0.15 = -0.15 → 359.85），不是 +359° 的远路
        val back = BikeCompass.smoothAngle(0f, 359f, 0.15f)
        assertTrue("实际 $back", back > 359.8f)
    }

    @Test
    fun `插值结果归一化到 0 到 360`() {
        // -1 * 0.15 = -0.15，必须被归一化进 [0, 360)
        val result = BikeCompass.smoothAngle(0f, 359f, 0.15f)
        assertTrue(result >= 0f && result < 360f)
    }

    // ---------- shouldEmit：节流判定 ----------

    @Test
    fun `首个值立即发射`() {
        assertTrue(BikeCompass.shouldEmit(previousEmitted = null, next = 10f, now = 1000, lastEmitAt = 1000))
    }

    @Test
    fun `角度差不足不发`() {
        // 2° 以内的罗盘漂移不值得动画面
        assertFalse(BikeCompass.shouldEmit(previousEmitted = 10f, next = 11f, now = 5000, lastEmitAt = 4000))
    }

    @Test
    fun `间隔不足不发`() {
        // 角度够了，但距上次发射不到 100ms——整幅重绘的喘息间隔
        assertFalse(BikeCompass.shouldEmit(previousEmitted = 10f, next = 20f, now = 4050, lastEmitAt = 4000))
    }

    @Test
    fun `超阈值且间隔够才发`() {
        assertTrue(BikeCompass.shouldEmit(previousEmitted = 10f, next = 20f, now = 4100, lastEmitAt = 4000))
    }

    @Test
    fun `环形角度差按最短路计`() {
        // 359 与 1 的最短差是 2°，不是 358°
        assertEquals(2f, BikeCompass.emittedAngleDistance(359f, 1f))
        assertEquals(180f, BikeCompass.emittedAngleDistance(0f, 180f))
        assertEquals(90f, BikeCompass.emittedAngleDistance(270f, 0f))
    }

    // ---------- remapAxes：屏幕旋转档 → remap 轴向 ----------

    @Test
    fun `四档旋转的轴向映射与官方示例一致`() {
        assertEquals(
            SensorManager.AXIS_X to SensorManager.AXIS_Y,
            BikeCompass.remapAxes(Surface.ROTATION_0),
        )
        assertEquals(
            SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X,
            BikeCompass.remapAxes(Surface.ROTATION_90),
        )
        assertEquals(
            SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y,
            BikeCompass.remapAxes(Surface.ROTATION_180),
        )
        assertEquals(
            SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X,
            BikeCompass.remapAxes(Surface.ROTATION_270),
        )
    }

    // ---------- headingFromRotation：非向量读数直接拒 ----------

    @Test
    fun `空读数返回空不画扇形`() {
        assertNull(BikeCompass.headingFromRotation(FloatArray(0), Surface.ROTATION_0))
    }

    /**
     * 方位角出口的 NaN 防线（2026-10-08 复查轮 P3）：矩阵退化（全零 rotation vector）
     * 会产出 NaN——必须在出口丢掉，不能顺着 `smoothed`/`lastEmitted` 污染状态机
     * （`NaN > 2f` 恒 false），否则罗盘流整段哑掉。（`SensorManager` 的矩阵链在 JVM
     * 是 not-mocked stub，那条链本身只能真机验，这里钉的是防线函数。）
     */
    @Test
    fun `非有限方位角返回空`() {
        assertNull(BikeCompass.normalizeHeading(Float.NaN))
        assertNull(BikeCompass.normalizeHeading(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `方位角归一化到 0 到 360`() {
        assertEquals(0f, BikeCompass.normalizeHeading(0f))
        assertEquals(270f, BikeCompass.normalizeHeading(-90f))
        assertEquals(90f, BikeCompass.normalizeHeading(450f))
        assertEquals(359.5f, BikeCompass.normalizeHeading(-0.5f))
    }
}
