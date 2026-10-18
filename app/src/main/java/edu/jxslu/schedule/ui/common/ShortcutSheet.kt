package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.ShortcutItem
import edu.jxslu.schedule.domain.ShortcutSettings
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.PackageSearch01

/**
 * 取快递弹窗（从下弹出）：预设归「取快递」段、自定义归「其他」段，中间一条分割线；
 * 条目并列铺开。长按条目弹菜单（添加到桌面 / 编辑），末尾虚线「新增」进设置页。
 *
 * 「取快递」段末尾另有一条「菜鸟批量查」整宽行：它是 App 内页面（粘贴多个单号一次导入
 * 菜鸟），与「点一下拉起外部 App」的图标块不同，故单独一行、放在预设块之下。
 *
 * 2026-10-19 从生活页移到今日页「江水生活」里的图标入口：组件本身搬进 ui/common，
 * 生活页与今日页不再各留一份。关弹层走 [rememberSheetDismisser]，点条目先播退场再拉起。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutSheet(
    settings: ShortcutSettings,
    onDismiss: () -> Unit,
    onLaunchItem: (ShortcutItem) -> Unit,
    onPinItem: (ShortcutItem) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenCainiao: () -> Unit,
) {
    val presets = settings.items.filter { it.presetIndex >= 0 }
    val customs = settings.items.filter { it.presetIndex < 0 }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dismiss = rememberSheetDismisser(sheetState, onDismiss)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 自定义条目最多 8 条（含预设），小屏 / 大字体下整页会超过一屏：
                // 允许滚动，保证末尾「新增」够得到（本弹窗无输入框，不适用固定不滚的口径）
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("快捷方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "点一下直接跳转，长按可编辑",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            if (presets.isNotEmpty()) {
                ShortcutCategoryLabel("取快递", presets.size)
                ShortcutTileGrid(
                    items = presets,
                    onLaunch = { item -> dismiss { onLaunchItem(item) } },
                    onPin = { item -> dismiss { onPinItem(item) } },
                    onEdit = { id -> dismiss { onEdit(id) } },
                    onAdd = null,
                )
                Spacer(Modifier.height(12.dp))
                SheetActionRow(
                    icon = HugeIcons.PackageSearch01,
                    title = "菜鸟批量查",
                    subtitle = "多单号一次导入取件码",
                    onClick = { dismiss { onOpenCainiao() } },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            ShortcutCategoryLabel("其他", customs.size)
            ShortcutTileGrid(
                items = customs,
                onLaunch = { item -> dismiss { onLaunchItem(item) } },
                onPin = { item -> dismiss { onPinItem(item) } },
                onEdit = { id -> dismiss { onEdit(id) } },
                onAdd = { dismiss { onAdd() } },
            )
        }
    }
}

/** 弹窗里的分类标题：「取快递 · 3 项」。没有条目（count = 0）时不带计数后缀，只留分类名。 */
@Composable
private fun ShortcutCategoryLabel(title: String, count: Int) {
    Row(Modifier.padding(bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "· $count 项",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

/** 三列图标块网格；[onAdd] 非空时末尾补一枚虚线「新增」格子。 */
@Composable
private fun ShortcutTileGrid(
    items: List<ShortcutItem>,
    onLaunch: (ShortcutItem) -> Unit,
    onPin: (ShortcutItem) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val cells: List<Pair<ShortcutItem?, Boolean>> =
            items.map { it to false } + listOfNotNull(onAdd?.let { null to true })
        cells.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (item, isAdd) ->
                    if (isAdd) {
                        AddShortcutTile(Modifier.weight(1f)) { onAdd?.invoke() }
                    } else {
                        ShortcutSheetTile(
                            item = item!!,
                            modifier = Modifier.weight(1f),
                            onLaunch = { onLaunch(item) },
                            onPin = { onPin(item) },
                            onEdit = { onEdit(item.id) },
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 弹窗里的一枚快捷方式：图标块 + 名称；长按弹「添加到桌面 / 编辑」。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutSheetTile(
    item: ShortcutItem,
    modifier: Modifier = Modifier,
    onLaunch: () -> Unit,
    onPin: () -> Unit,
    onEdit: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .combinedClickable(
                        onClick = {
                            haptics.tap()
                            onLaunch()
                        },
                        onLongClick = {
                            haptics.tap()
                            menuOpen = true
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                ShortcutIcon(item, Modifier.size(30.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("添加到桌面") },
                    leadingIcon = { Icon(HugeIcons.Link01, null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        menuOpen = false
                        haptics.tap()
                        onPin()
                    },
                )
                DropdownMenuItem(
                    text = { Text("编辑") },
                    leadingIcon = { Icon(HugeIcons.Edit02, null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 虚线「新增」格子。 */
@Composable
private fun AddShortcutTile(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(onSurface.copy(alpha = 0.03f))
                .clickable(onClickLabel = "新增快捷方式") { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                HugeIcons.Add01,
                contentDescription = null,
                tint = onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "新增",
            style = MaterialTheme.typography.labelMedium,
            color = onSurface.copy(alpha = 0.6f),
            maxLines = 1,
        )
    }
}
