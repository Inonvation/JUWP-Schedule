package edu.jxslu.schedule.ui.week

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import edu.jxslu.schedule.domain.GridFont

/**
 * 网格字号换算结果（唯一来源）。
 *
 * 原实现在 `WeekScreen` 函数体里（2026-10-09 抽出）。抽出的原因：**导出为图片**
 * （`ScheduleShareDialog`）要按同一套换算渲染网格——两处各写一遍必然会漂移，
 * 表现成「屏幕上看着刚好、导出图里日期被裁」这类没法归因的问题。
 *
 * 各字段语义见 `WeekScreen` 原注释与 `GridFont`；这里只做搬运，不改口径。
 */
internal data class GridTypeSpec(
    /** 网格内统一字号倍率（课名目标 dp → 实际 fontScale）。 */
    val gridScale: Float,
    /** 教室（地点）行实际渲染 sp；null = 跟随课名等比缩放。 */
    val roomFontSp: Float?,
    /** 教师行实际渲染 sp；null = 跟随课名等比缩放。 */
    val teacherFontSp: Float?,
    /** 时间轴（节次 + 起止时间）实际渲染 sp。 */
    val railFontSp: Float,
    /** 月份 / 日期表头实际渲染 sp。 */
    val dateFontSp: Float,
    /** 表头高度有效下限（dp）：装得下两行日期文字。 */
    val headerMinDp: Float,
)

/**
 * 把显示偏好 + 当前生效的字体倍率 + 列数换算成网格排版参数。
 * （字体倍率取自组合的 `LocalDensity`：锁定开启时恒为 0.9×，见
 * [edu.jxslu.schedule.domain.FontScalePolicy]。）
 *
 * 网格字号：课名目标字号 dp（用户设置或跟随系统）→ 网格内统一倍率，换算规则见 [GridFont]。
 * 教室/教师：用户设置过目标 dp 时换算成卡片内的实际 sp——网格还套着 [GridTypeSpec.gridScale]
 * 的密度倍率，预除一次才能让净渲染值等于目标 dp；未设置传 null，跟随课名等比（旧行为）。
 *
 * 时间轴 / 日期表头与课名**完全解耦**：它们不在 gridScale 的语义范围内（是定位参照，不是内容），
 * 但仍渲染在 `GridTypography` 提供的密度里，所以要把课名倍率除回去。
 * 这里不能写成 `state.gridRailDp?.let{...}`——用户没拖过滑块时结果是 null，
 * 时间轴便回落到硬编码 sp，而那仍会被 gridScale 乘一遍（「调课名字号，时间轴跟着变」的老问题）。
 */
@Composable
internal fun rememberGridTypeSpec(state: WeekUiState, days: Int): GridTypeSpec {
    val effectiveFontScale = LocalDensity.current.fontScale
    val gridScale = GridFont.scaleFromDp(
        GridFont.resolveDp(effectiveFontScale, state.gridFontDp, days),
        days,
    )
    val roomFontSp = state.gridRoomDp?.let {
        GridFont.resolveDetailDp(effectiveFontScale, it, days) / gridScale
    }
    val teacherFontSp = state.gridTeacherDp?.let {
        GridFont.resolveDetailDp(effectiveFontScale, it, days) / gridScale
    }
    val railFontSp = GridFont.resolveRailDp(effectiveFontScale, state.gridRailDp, days) / gridScale
    val dateFontSp = GridFont.resolveDateDp(effectiveFontScale, state.gridDateDp, days) / gridScale
    return GridTypeSpec(
        gridScale = gridScale,
        roomFontSp = roomFontSp,
        teacherFontSp = teacherFontSp,
        railFontSp = railFontSp,
        dateFontSp = dateFontSp,
        // 表头高度有效下限：装得下两行日期文字的最小高度（已夹进滑块范围）
        headerMinDp = minHeaderHeightForDateFont(dateFontSp),
    )
}
