package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 字体缩放锁定契约（「固定字体大小」默认开）。
 *
 * 钉住两点：锁定 = 固定 [FontScalePolicy.LockedScale]（0.9×，不是系统任何档位、也不做夹取）；
 * 未锁定 = 原样透传系统值（= 加这个开关之前的旧行为）。
 */
class FontScalePolicyTest {

    @Test
    fun lockedPinsToFixedBaseline() {
        assertEquals(0.9f, FontScalePolicy.LockedScale, 0.0001f)
        assertEquals(0.9f, FontScalePolicy.effectiveFontScale(true, 1.5f), 0.0001f)
        assertEquals(0.9f, FontScalePolicy.effectiveFontScale(true, 0.85f), 0.0001f)
        assertEquals(0.9f, FontScalePolicy.effectiveFontScale(true, 2f), 0.0001f)
        // 系统恰好也是 0.9（锁定倍率本身）：结果不变（调用点会跳过无谓的 Provider）
        assertEquals(0.9f, FontScalePolicy.effectiveFontScale(true, 0.9f), 0.0001f)
    }

    @Test
    fun unlockedPassesThrough() {
        assertEquals(1.3f, FontScalePolicy.effectiveFontScale(false, 1.3f), 0.0001f)
        assertEquals(1f, FontScalePolicy.effectiveFontScale(false, 1f), 0.0001f)
    }
}
