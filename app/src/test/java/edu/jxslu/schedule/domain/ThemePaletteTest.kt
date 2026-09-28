package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置主题配色的固定口径（DESIGN §3.3）：id 稳定可往返、未知值回落品牌青。
 * id 落盘在 DataStore（键 theme_palette），改名会让老用户的配色悄悄跳回默认——这里钉死。
 */
class ThemePaletteTest {

    @Test
    fun `id 往返一致`() {
        ThemePalette.entries.forEach { palette ->
            assertEquals(palette, ThemePalette.fromId(palette.id))
        }
    }

    @Test
    fun `默认值是品牌青`() {
        assertEquals(ThemePalette.Brand, ThemePalette.fromId(null))
        assertEquals(ThemePalette.Brand, ThemePalette.fromId(""))
        assertEquals(ThemePalette.Brand, ThemePalette.fromId("garbage"))
        assertEquals(ThemePalette.Brand, ThemePalette.fromId("BRAND")) // id 区分大小写，脏值回落
    }

    @Test
    fun `六套配色 id 不重复且全小写下划线`() {
        val ids = ThemePalette.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("[a-z_]+")) })
        // brand 必须是默认兜底：确保它一直在枚举里
        assertTrue("brand" in ids)
    }

    @Test
    fun `每个配色都有中文名`() {
        assertTrue(ThemePalette.entries.all { it.label.isNotBlank() })
    }
}
