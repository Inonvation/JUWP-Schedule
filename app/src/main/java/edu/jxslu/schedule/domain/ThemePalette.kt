package edu.jxslu.schedule.domain

/**
 * 内置主题配色（DESIGN §3.3 观感）。动态取色关闭时按本项渲染深浅两套方案；
 * 动态取色开着（默认）时被忽略。持久化在显示偏好里（DataStore，键 theme_palette）。
 *
 * [id] 是落盘的稳定标识，只增不改——改名会让老用户的配色悄悄跳回默认。
 * 色值本体在 ui/theme/Palettes.kt（domain 不持 Color）。
 */
enum class ThemePalette(val id: String, val label: String) {
    Brand("brand", "水电青"),
    SkyBlue("sky_blue", "晴空蓝"),
    DuskPurple("dusk_purple", "暮山紫"),
    Sakura("sakura", "樱花粉"),
    Sunset("sunset", "落日橙"),
    Forest("forest", "森野绿"),
    ;

    companion object {
        /** 未知值一律退回品牌青：宁可回到默认观感，也不要因为脏数据渲染失败。 */
        fun fromId(id: String?): ThemePalette = entries.firstOrNull { it.id == id } ?: Brand
    }
}
