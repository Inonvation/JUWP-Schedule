package edu.jxslu.schedule.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 全 App「描边卡片」的唯一规格出处（DESIGN §3.2）。
 *
 * 2026-09-22 之前两套规格并存：12dp 圆角 + `outline` 22% 描边（笔记 / 作业 / 课程库 /
 * 今日页作业卡）与 14dp 圆角 + `outlineVariant` 描边（今日页底部服务卡）。同一屏里两种观感，
 * 且 22% 描边在浅色主题下几乎看不见。这里收成一份，改规格只动本文件。
 *
 * 只管观感与点击；间距归调用方——**外间距**写在传入的 [modifier] 上（卡片外缩进），
 * **内间距**由 [AppCard] 的 contentPadding 定。点击涟漪由内部 `clickable` 产生，自动被
 * [AppCardDefaults.Shape] 裁切，调用方不要自己在外面再包一层 clip。
 */
object AppCardDefaults {
    /** 卡片圆角：14dp（与提示卡 `AppSnackbarHost`、设置卡同档）。 */
    val Shape = RoundedCornerShape(14.dp)

    /** 默认内间距：左右 14dp、上下 12dp（两行文本约 56dp 高，与旧服务卡 58dp 基本持平）。 */
    val Padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)

    /**
     * 高亮态的描边宽度。颜色取主题主色（见 [AppCard] 的 highlighted 参数）。
     * 只加粗到 1.5dp：再加就成"选中框"了，卡片是列表里的一行，不是被选中的单选项。
     */
    val HighlightedBorderWidth = 1.5.dp

    /** 常态描边宽度（[HighlightedBorderWidth] 的起点，也是高亮过渡的另一端）。 */
    val NormalBorderWidth = 1.dp

    /** 高亮态的过渡时长（与全 App 其它状态过渡同档）。 */
    const val HighlightTransitionMillis = 180
}

/**
 * 描边卡片（纵向内容）。
 *
 * [onClick] 为 null = 纯展示卡（不可点、无涟漪、不进无障碍可点集合）；
 * 非 null 时由本组件统一触发触感反馈（开关口径见 [rememberAppHaptics]），
 * 调用方不要再自己调 `haptics.tap()`。
 *
 * [highlighted] 用于「从别处定位到这张卡」的场景（比如点地图上的标记，列表滚过去并把它点亮），
 * 也用于「卡片即单选项」的场景（如 U净 下单页的档位卡）：
 * 换主色描边 + 抬一档底色。默认 false，不影响既有调用点。
 * 三处颜色（底 / 描边色 / 描边宽）都走 180ms 过渡——几何不变，选中与取消选中不再突变。
 *
 * [highlightedFill] = false 时只换描边、不抬底色（2026-10-22）：卡片本身就是页面底色里的
 * 一块，抬高底色会把「卡边高亮」又变成一层卡片。默认 true 保持既有调用点的观感。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    /** 长按（列表多选入口等）。与 [onClick] 一样由本组件统一触发触感反馈。 */
    onLongClick: (() -> Unit)? = null,
    /** 只影响点击：false = 卡片可见但不可点（如开水卡在出水过程中不许再进页面）。 */
    enabled: Boolean = true,
    highlighted: Boolean = false,
    highlightedFill: Boolean = true,
    contentPadding: PaddingValues = AppCardDefaults.Padding,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 触感实例在这里取（与列表行同口径：每行一份 AppHaptics，内部按开关短路）
    val haptics = rememberAppHaptics()
    val containerColor by animateColorAsState(
        targetValue = if (highlighted && highlightedFill) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(durationMillis = AppCardDefaults.HighlightTransitionMillis),
        label = "appCardContainer",
    )
    val borderColor by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(durationMillis = AppCardDefaults.HighlightTransitionMillis),
        label = "appCardBorder",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (highlighted) {
            AppCardDefaults.HighlightedBorderWidth
        } else {
            AppCardDefaults.NormalBorderWidth
        },
        animationSpec = tween(durationMillis = AppCardDefaults.HighlightTransitionMillis),
        label = "appCardBorderWidth",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppCardDefaults.Shape)
            .background(containerColor)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = AppCardDefaults.Shape,
            )
            .then(
                if (onClick == null && onLongClick == null) {
                    Modifier
                } else {
                    // 涟漪由 combinedClickable 产生、被上面的 clip 裁进圆角；
                    // 命中区 = 描边内整张卡。有长按才需要它，纯点击走同一入口无行为差
                    @OptIn(ExperimentalFoundationApi::class)
                    Modifier.combinedClickable(
                        enabled = enabled,
                        onClickLabel = onClickLabel,
                        onClick = {
                            haptics.tap()
                            onClick?.invoke()
                        },
                        onLongClick = onLongClick?.let { longClick ->
                            {
                                haptics.tap()
                                longClick()
                            }
                        },
                    )
                },
            )
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * 描边卡片（单行内容，内容默认竖直居中）。
 *
 * 行内容自己 `weight`/`fillMaxWidth`；本组件已给足宽度（`fillMaxWidth`）。
 * 两行以上的复合内容用 [AppCard] + 自组 Row，或用本组件 + 内部 Column（见作业卡）。
 *
 * 内容用 `Arrangement.Center` 而非默认 Top：调用方常配 `heightIn(min = …)` 让一排卡片等高
 * （今日页服务格/开水卡 58dp），而 Column 的默认 Top 会把"卡片比内容高出来的那几 dp"
 * 全留在底部——观感就是文字偏上（2026-09-22 用户反馈）。
 */
@Composable
fun AppCardRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    contentPadding: PaddingValues = AppCardDefaults.Padding,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    AppCard(
        modifier = modifier,
        onClick = onClick,
        onClickLabel = onClickLabel,
        onLongClick = onLongClick,
        enabled = enabled,
        highlighted = highlighted,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = verticalAlignment,
            content = content,
        )
    }
}

/** 卡片内分隔线（同一张卡里区分两组内容，比再切一张卡更省纵向空间）。 */
@Composable
fun AppCardDivider(modifier: Modifier = Modifier) {
    androidx.compose.material3.HorizontalDivider(
        modifier = modifier,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}
