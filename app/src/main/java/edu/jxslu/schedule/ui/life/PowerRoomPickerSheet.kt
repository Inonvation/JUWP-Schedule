package edu.jxslu.schedule.ui.life

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.data.power.PowerSceneOption
import edu.jxslu.schedule.ui.common.ImeAwareModalBottomSheet
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01

/**
 * 「选择寝室」弹层（DESIGN §4.24「未设置寝室」，2026-10-08）。
 *
 * 用在两处宿主：生活页电费栏（未设置时的 CTA）与「我的 → 校园卡」的「寝室电费房间」行
 * （已设置时改绑）。流程与官方 H5 对齐：校区 → 楼栋 → 房间（带本地搜索过滤）→
 * 读表预览 → **二次确认弹窗** → 绑定写一次（[PowerRoomPickerViewModel.bind]）。
 *
 * 无状态组件：流程状态全在 [PowerRoomPickerViewModel]（宿主在打开前调 `start()` 重置）。
 * 含搜索输入框，键盘遮挡与退场走 [ImeAwareModalBottomSheet]（与充值弹层同款）。
 */
@Composable
fun PowerRoomPickerSheet(
    viewModel: PowerRoomPickerViewModel,
    onDismiss: () -> Unit,
    /** 绑定成功（参数 = 房间显示名）：宿主负责收弹层、刷卡面、给提示。 */
    onBound: (roomLabel: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = rememberAppHaptics()
    var confirmBind by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }

    // 切层时清掉上一层的搜索词（否则「房间」里搜的楼栋号会带进下一栋的列表）
    LaunchedEffect(state.step) { search = "" }

    // 绑定成功：交给宿主收尾（这里不做「完成页」，避免两套状态）
    LaunchedEffect(state.done) {
        if (state.done) onBound(state.pickedRoom?.name ?: "寝室")
    }

    ImeAwareModalBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "设置寝室",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "缴费平台需要先确认寝室，之后的电费查询与充值都以这里设置的房间为准。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            // 面包屑 + 返回上一层（已选过的层级）
            if (state.breadcrumb.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.breadcrumb.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { haptics.tap(); viewModel.back() },
                        enabled = !state.busy && !state.binding,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) { Text("返回上一层", style = MaterialTheme.typography.labelMedium) }
                }
            }

            val error = state.error
            when {
                // 加载 / 读表 / 绑定失败：错误 + 手动重试（唯一重试入口，不自动重发）
                error != null && !state.busy -> {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { haptics.tap(); viewModel.retry() }) {
                        Text(if (state.bindFailed) "重新绑定" else "重试")
                    }
                }

                // 房间已选：预览 + 确认
                state.atRoomLevel && state.pickedRoom != null -> {
                    RoomPreview(
                        room = state.pickedRoom!!.name,
                        remainText = state.preview?.remain?.let { "%.2f 度".format(it) },
                        previewLoading = state.busy,
                        binding = state.binding,
                        onReselect = { haptics.tap(); viewModel.back() },
                        onConfirm = { haptics.tap(); confirmBind = true },
                    )
                }

                // 候选列表（校区 / 楼栋 / 房间）
                else -> {
                    val levelName = state.levels.getOrNull(state.step)?.name ?: "选项"
                    Text(
                        text = "选择$levelName",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (state.busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "加载中…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    } else {
                        // 候选多时给本地搜索过滤（楼栋 50+、单楼栋房间上百，2026-10-08 实测口径）
                        if (state.options.size > 12) {
                            OutlinedTextField(
                                value = search,
                                onValueChange = { search = it },
                                placeholder = { Text("搜索$levelName") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        val keyword = search.trim()
                        val filtered = remember(state.options, keyword) {
                            if (keyword.isEmpty()) {
                                state.options
                            } else {
                                state.options.filter { it.name.contains(keyword, ignoreCase = true) }
                            }
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                        ) {
                            items(filtered) { option ->
                                OptionRow(option = option, onClick = {
                                    haptics.tap()
                                    search = ""
                                    viewModel.selectOption(option)
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    // 二次确认（写接口前的最后一道）：措辞说清「会同步到缴费平台」
    if (confirmBind) {
        val room = state.pickedRoom?.name.orEmpty()
        val path = (state.breadcrumb + room).joinToString(" - ")
        AlertDialog(
            onDismissRequest = { if (!state.binding) confirmBind = false },
            title = { Text("确认设置寝室") },
            text = {
                Text(
                    "将把「$path」设为你的寝室房间。\n\n" +
                        "设置会同步到缴费平台的「我的房间」，之后的电费查询与充值都以它为准。",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.binding,
                    onClick = {
                        confirmBind = false
                        viewModel.bind()
                    },
                ) { Text("确认绑定") }
            },
            dismissButton = {
                TextButton(enabled = !state.binding, onClick = { confirmBind = false }) { Text("取消") }
            },
        )
    }
}

/** 候选一行：名称 + 右箭头，整行可点。 */
@Composable
private fun OptionRow(option: PowerSceneOption, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "选择${option.name}", onClick = onClick)
                .padding(vertical = 12.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = option.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "选择",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "›",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** 房间预览块：读表读数（不落库）+「重选 / 确认绑定」。 */
@Composable
private fun RoomPreview(
    room: String,
    remainText: String?,
    previewLoading: Boolean,
    binding: Boolean,
    onReselect: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = room,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onReselect,
                enabled = !previewLoading && !binding,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) { Text("重选", style = MaterialTheme.typography.labelMedium) }
        }
        when {
            previewLoading -> Text(
                text = "正在读取该房间电表…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            remainText != null -> Text(
                text = "当前剩余 $remainText",
                style = MaterialTheme.typography.bodyMedium,
            )

            // 读数认不出也不拦绑定：平台给的电表字段形态可能变化，绑定本身不依赖读数
            else -> Text(
                text = "电表已连上，但没认出错表读数（不影响设置）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Button(
            onClick = onConfirm,
            enabled = !previewLoading && !binding,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (binding) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (binding) "设置中…" else "确认绑定")
        }
    }
}
