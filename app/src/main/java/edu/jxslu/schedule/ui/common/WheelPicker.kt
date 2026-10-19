package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 滚轮选择器（对齐拾光的滚轮交互）。
 *
 * 实现：LazyColumn + snapFlingBehavior，竖向 contentPadding 让「选中项」停在中心；
 * 选中下标由 firstVisibleItemIndex + 偏移过半与否推得（列表停稳时 snap 到整项，
 * 所以停稳瞬间 offset 必为 0，不会出现半格选中态）。
 *
 * 用它而不是 Android 原生 NumberPicker：原生控件样式与 M3 主题割裂，且无法做渐隐遮罩。
 *
 * **选中值实时回调**（2026-10-07 交互修）：[onSelected] 跟着视觉中心**即时**走，
 * 不再等停稳——此前必须等 snap 停稳才回写选中值，用户滚到位立刻点「确定」会提交
 * 上一次停稳的旧值（一卡通阈值 / 总周数等弹窗被报「要等计数停下来才生效」）。
 * 现在停稳只负责触感与半格吸附，取值永远以「确定」按下那一刻的视觉中心为准。
 */
@Composable
fun WheelPicker(
    values: List<String>,
    initialIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    visibleCount: Int = 5,
    itemHeight: Dp = 40.dp,
) {
    require(visibleCount % 2 == 1) { "visibleCount 必须为奇数，否则没有中心项" }
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.roundToPx() }
    val half = visibleCount / 2
    val haptics = rememberAppHaptics()
    // 空列表防御：coerceIn(0, -1) 会抛 IllegalArgumentException。现有调用点都传固定
    // 非空列表，但这是个通用组件，误用不该以崩溃收场——空列表渲染为空即可。
    val maxIndex = (values.size - 1).coerceAtLeast(0)
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex.coerceIn(0, maxIndex),
    )

    // 中心当前停在哪个下标：第一可见项 + 视偏移过半则 +1（滚动中只是过渡值，停稳后必为整数项）
    val centered by remember(state, itemHeightPx) {
        derivedStateOf {
            val overHalf = state.firstVisibleItemScrollOffset * 2 >= itemHeightPx
            (state.firstVisibleItemIndex + if (overHalf) 1 else 0)
                .coerceIn(0, maxIndex)
        }
    }

    // 选中值实时回调：视觉中心每变一档就发一次，滚动中也发（对话框的「确定」因此
    // 总能拿到当下的值）。首帧也照发——调用方的 selected 初值就是从 initialIndex
    // 来的，同一值 distinct 后只是省一次写。
    LaunchedEffect(state, values) {
        if (values.isEmpty()) return@LaunchedEffect
        snapshotFlow { centered }
            .distinctUntilChanged()
            .collect { onSelected(it) }
    }

    // 停稳收尾：给触感，并把半格状态吸附回整项（animateScrollToItem 对齐到 offset=0）。
    // 取值已由上面的实时回调负责，这里只管手感与对齐。
    LaunchedEffect(state) {
        var firstEmission = true
        snapshotFlow { state.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }
            .collect {
                if (!firstEmission && values.isNotEmpty()) haptics.tick()
                firstEmission = false
                if (values.isNotEmpty() && state.firstVisibleItemScrollOffset != 0) {
                    state.animateScrollToItem(centered)
                }
            }
    }

    // 点击直选（2026-10-07）：点非中心项直接滚过去（中心项不响应——它就是当前值）。
    // 长距离跳转（总周数 20 → 4）不用连拖几屏。目标位置 = 该项滚到中心需要的偏移，
    // animateScrollToItem 会把它对齐到 contentPadding 让出的中心位。
    val scope = rememberCoroutineScope()
    Box(modifier.height(itemHeight * visibleCount)) {
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
            contentPadding = PaddingValues(vertical = itemHeight * half),
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(values.size) { i ->
                val isCenter = i == centered
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .then(
                            if (isCenter) {
                                Modifier
                            } else {
                                Modifier.clickable(
                                    onClickLabel = values[i],
                                ) {
                                    haptics.tick()
                                    scope.launch { state.animateScrollToItem(i) }
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = values[i],
                        style = if (isCenter) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.bodyLarge
                        },
                        fontWeight = if (isCenter) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (isCenter) 1f else 0.35f,
                        ),
                    )
                }
            }
        }
        // 上下渐隐遮罩 + 中心选中带：拾光滚轮的视觉锚点
        val fade = MaterialTheme.colorScheme.surface
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to fade, 0.18f to fade.copy(alpha = 0f),
                        0.82f to fade.copy(alpha = 0f), 1f to fade,
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        )
    }
}

/** 「时：分」滚轮的常量表：每次重组新建 60 个格式化字符串纯属浪费，提到文件级。 */
private val HOURS = (0..23).map { "%02d".format(it) }
private val MINUTES = (0..59).map { "%02d".format(it) }

/**
 * 「时：分」双滚轮弹窗。initial 形如 "HH:mm"（解析失败回退 08:00），
 * 确认时以 "%02d:%02d" 回调。由作息表每行的起止时间触发。
 */
@Composable
fun TimeWheelDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val parts = initial.split(":")
    var hour by remember { mutableIntStateOf(parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8) }
    var minute by remember { mutableIntStateOf(parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0) }
    val hours = HOURS
    val minutes = MINUTES

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            DialogWindowFadeEffect()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    WheelPicker(
                        values = hours,
                        initialIndex = hour,
                        onSelected = { hour = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(":", style = MaterialTheme.typography.titleMedium)
                Box(Modifier.weight(1f)) {
                    WheelPicker(
                        values = minutes,
                        initialIndex = minute,
                        onSelected = { minute = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm("%02d:%02d".format(hour, minute)) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 单列滚轮弹窗（总周数这类小范围整数），复用 [WheelPicker]。 */
@Composable
fun WheelValueDialog(
    title: String,
    values: List<String>,
    initialIndex: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableIntStateOf(initialIndex.coerceIn(0, (values.size - 1).coerceAtLeast(0))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            DialogWindowFadeEffect()
            Box(Modifier.fillMaxWidth()) {
                WheelPicker(
                    values = values,
                    initialIndex = selected,
                    onSelected = { selected = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
