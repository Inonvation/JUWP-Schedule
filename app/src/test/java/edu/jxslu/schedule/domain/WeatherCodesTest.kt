package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WMO 码与派生文案的契约（DESIGN §3.29）：分类函数、风向八方位、紫外线档位。
 */
class WeatherCodesTest {

    @Test
    fun `雨雪雷雾各归各的类`() {
        assertTrue(WeatherCodes.isRain(61))
        assertTrue(WeatherCodes.isRain(80))
        // 雷暴码不算「雨」：话术更重，两者不能同时命中
        assertFalse(WeatherCodes.isRain(95))
        assertTrue(WeatherCodes.isThunder(95))
        assertTrue(WeatherCodes.isThunder(99))
        assertTrue(WeatherCodes.isSnow(71))
        assertTrue(WeatherCodes.isSnow(86))
        assertTrue(WeatherCodes.isFog(45))
        // 阴天（3）不该落进任何一类
        assertFalse(WeatherCodes.isRain(3))
        assertFalse(WeatherCodes.isSnow(3))
        assertFalse(WeatherCodes.isThunder(3))
        assertFalse(WeatherCodes.isFog(3))
    }

    @Test
    fun `风向落八方位`() {
        assertEquals("北风", WeatherCodes.windLabel(0))
        assertEquals("东北风", WeatherCodes.windLabel(45))
        assertEquals("东风", WeatherCodes.windLabel(90))
        assertEquals("东南风", WeatherCodes.windLabel(135))
        assertEquals("南风", WeatherCodes.windLabel(180))
        assertEquals("西南风", WeatherCodes.windLabel(225))
        assertEquals("西风", WeatherCodes.windLabel(270))
        assertEquals("西北风", WeatherCodes.windLabel(315))
        assertNull(WeatherCodes.windLabel(null))
    }

    @Test
    fun `风向边界进扇区`() {
        // 22.5° 是「北」与「东北」的分界（写成整数就是 22 归北、23 归东北）
        assertEquals("北风", WeatherCodes.windLabel(22))
        assertEquals("东北风", WeatherCodes.windLabel(23))
        // 359° 仍在北扇区（+22.5 后越过一圈，取模回来）
        assertEquals("北风", WeatherCodes.windLabel(359))
        // 负角度与超过一圈都归一
        assertEquals("北风", WeatherCodes.windLabel(360))
        assertEquals("西风", WeatherCodes.windLabel(-90))
    }

    @Test
    fun `紫外线档位按 WMO 分级`() {
        assertEquals("弱", WeatherCodes.uvLabel(0.0))
        assertEquals("中等", WeatherCodes.uvLabel(3.0))
        assertEquals("强", WeatherCodes.uvLabel(6.0))
        assertEquals("很强", WeatherCodes.uvLabel(8.0))
        assertEquals("极强", WeatherCodes.uvLabel(11.0))
        assertNull(WeatherCodes.uvLabel(null))
    }

    @Test
    fun `晴天昼夜给不同图标`() {
        assertEquals("☀️", WeatherCodes.emoji(0, isDay = true))
        assertEquals("🌙", WeatherCodes.emoji(0, isDay = false))
        // 认不出的码有一个可读的兜底，不抛异常
        assertEquals("天气未知", WeatherCodes.label(1000))
    }
}
