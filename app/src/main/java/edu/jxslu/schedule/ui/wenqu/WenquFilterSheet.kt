package edu.jxslu.schedule.ui.wenqu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.WenquRules
import edu.jxslu.schedule.ui.common.rememberSheetDismisser

/**
 * 问渠活动列表的「筛选与排序」底部弹层（DESIGN §3.28，2026-10-11）。
 *
 * 为什么收进弹层：主页那一行横向滚动的 chips（5 枚）在 360dp 屏上会被裁掉最右边那枚
 * ——「看不见的选项等于不存在」（用户报过同类问题）。现在页面上只留**高频**的状态分段，
 * 低频的开关与排序进弹层，四项状态永远全在屏内。
 *
 * 无输入框 → 走 M3 `ModalBottomSheet` + [rememberSheetDismisser]（关弹层先播退场动画）。
 * 所有改动**本地即时生效、不发请求**；「看 N 条」实时反映筛选后的条数，不用退出去才知道。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WenquFilterSheet(
    appliedOnly: Boolean,
    hideFull: Boolean,
    sort: WenquRules.Sort,
    /** 当前筛选下会剩几条（实时算，页面传进来）。 */
    visibleCount: Int,
    onToggleAppliedOnly: (Boolean) -> Unit,
    onToggleHideFull: (Boolean) -> Unit,
    onSelectSort: (WenquRules.Sort) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dismiss = rememberSheetDismisser(sheetState, onDismiss)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 18.dp),
        ) {
            Text("筛选与排序", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "都在本地生效，切换不发请求",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(8.dp))

            CheckRow(
                title = "只看我报名的",
                subtitle = "平台回的「已报名」标记",
                checked = appliedOnly,
                onCheckedChange = onToggleAppliedOnly,
            )
            CheckRow(
                title = "隐藏已满",
                subtitle = "招募人数没填的不算满",
                checked = hideFull,
                onCheckedChange = onToggleHideFull,
            )

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(6.dp))

            Text(
                text = "排序",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            WenquRules.SORT_CHOICES.forEach { option ->
                SortRow(
                    label = option.label,
                    selected = option == sort,
                    onSelect = { onSelectSort(option) },
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                    Text("清除筛选")
                }
                Button(onClick = { dismiss {} }, modifier = Modifier.weight(1f)) {
                    Text("看 $visibleCount 条")
                }
            }
        }
    }
}

/** 开关行：整行可点（≥48dp 命中区），勾选框只是视觉提示。 */
@Composable
private fun CheckRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

/** 排序单选行：整行可点（≥48dp 命中区）。 */
@Composable
private fun SortRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
