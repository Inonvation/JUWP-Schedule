package edu.jxslu.schedule.ui.water

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.UnlockFlowState
import edu.jxslu.schedule.domain.UnlockResult
import edu.jxslu.schedule.domain.cashPaidAmount
import edu.jxslu.schedule.domain.calculateActualCost
import edu.jxslu.schedule.domain.ticketPaidAmount
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 今日页点余额弹出的开水操作面板（DESIGN §3.3，2026-09-23 起）：
 * 小票余额 + 积分 + 开水按钮 + 流程态（进度/结算/失败详情）+ 积分抵扣开关，
 * 与开水页共享同一份 [WaterViewModel]——弹窗内发起的解锁流程两页状态一致，
 * 弹窗关闭不取消流程（副行给简报，重开弹窗看完整进度）。
 *
 * 按钮固定单击（用户拍板）：点余额 → 弹窗 → 点开水已是明确意图链，
 * 全局「点击方式」偏好只对开水页大按钮生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaterEntrySheet(
    state: WaterUiState,
    onUnlock: () -> Unit,
    onDismissFlow: () -> Unit,
    onToggleUsePoints: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 失败详情与订单详情都是弹窗上的二级弹窗（与开水页 ErrorDetailDialog/OrderDetailDialog
    // 同一形态）；开水页私有，这里本地给等价实现，避免为两处拖出共享层
    var detailItem by remember { mutableStateOf<Any?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("胖乖生活", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val balance = state.balance
            Column {
                Text(
                    text = balance?.let { "小票 ¥${it.ticketText}" } ?: "小票读取中…",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "积分 ${balance?.pointsText ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            when (val flow = state.flow) {
                is UnlockFlowState.Idle -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("使用积分抵扣", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "关闭后开水不消耗积分",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                    Switch(checked = state.usePoints, onCheckedChange = { onToggleUsePoints() })
                }
                is UnlockFlowState.PreChecking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        flow.step,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                is UnlockFlowState.Working -> Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "正在出水 ${clock(flow.elapsedSeconds)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${(WaterViewModel.AUTO_SETTLE_SECONDS - flow.elapsedSeconds).coerceAtLeast(0)} 秒后自动关闭",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = {
                            (flow.elapsedSeconds.toFloat() / WaterViewModel.AUTO_SETTLE_SECONDS).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        flow.step,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                is UnlockFlowState.Success -> SettlementBlock(
                    result = flow.result,
                    onShowDetail = { detailItem = flow.result },
                )
                is UnlockFlowState.Failed -> Column {
                    Text(
                        flow.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "失败步骤：${flow.step}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                    Text(
                        "查看详情 ›",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable { detailItem = flow },
                    )
                }
            }
            // 流程按钮：Idle/失败给「开水 / 重试」，进行中禁点（VM 内 Mutex 另有防重入），
            // 成功给「完成」归位。固定单击，无确认步（用户拍板）。
            // enabled 只能卡「有没有设备」：这一支本就只覆盖 Idle 与 Failed，
            // 再写一句 `flow is Idle` 会把失败态的「重试」一起禁掉（点了没反应）
            when (state.flow) {
                is UnlockFlowState.Idle, is UnlockFlowState.Failed -> Button(
                    onClick = onUnlock,
                    enabled = state.selectedDevice != null,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text(
                        if (state.flow is UnlockFlowState.Failed) "重试"
                        else "开水" + (state.selectedDevice?.goodsName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    )
                }
                is UnlockFlowState.PreChecking, is UnlockFlowState.Working -> Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("开水进行中…") }
                is UnlockFlowState.Success -> OutlinedButton(
                    onClick = onDismissFlow,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("完成") }
            }
            if (state.selectedDevice == null) {
                Text(
                    "未选择设备：请先到开水页选择饮水机",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }

    when (val d = detailItem) {
        is UnlockFlowState.Failed -> EntryFailDetailDialog(item = d, onDismiss = { detailItem = null })
        is UnlockResult -> EntryOrderDetailDialog(item = d, onDismiss = { detailItem = null })
    }
}

/** 成功后的结算明细块（用户拍板口径）：本次花费大行 + 支付构成副行 + 「订单详情 ›」。 */
@Composable
private fun SettlementBlock(result: UnlockResult, onShowDetail: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "花费 ¥${calculateActualCost(result)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "订单详情 ›",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onShowDetail),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            settlementBreakdownText(result),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

/** 结算明细副行（DESIGN §4.10 账单口径，与开水页成功卡同一构成）。 */
private fun settlementBreakdownText(result: UnlockResult): String {
    fun money(value: BigDecimal): String =
        value.setScale(2, RoundingMode.HALF_UP).toPlainString()
    val parts = buildList {
        ticketPaidAmount(result)?.takeIf { it > BigDecimal.ZERO }
            ?.let { add("小票支付 ¥${money(it)}") }
        result.integralCost.takeIf { it != "-" }?.let { add("积分抵扣 $it") }
        cashPaidAmount(result)?.takeIf { it > BigDecimal.ZERO }?.let { cash ->
            add("${result.payTypeName ?: "现金"} ¥${money(cash)}")
        }
    }
    return parts.joinToString(" · ").ifBlank { "开水成功" }
}

private fun clock(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}

/** 失败详情二级弹窗（与开水页 ErrorDetailDialog 同形态；那边私有，本地等价实现）。 */
@Composable
private fun EntryFailDetailDialog(item: UnlockFlowState.Failed, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("失败详情") },
        text = {
            Column {
                Text("失败步骤：${item.step}")
                Spacer(Modifier.height(6.dp))
                if (item.rawError.isNotBlank()) {
                    Text(
                        item.rawError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                item.suggestions.forEach { s ->
                    Spacer(Modifier.height(4.dp))
                    Text("• $s", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

/** 订单详情二级弹窗（与开水页 OrderDetailDialog 同形态，DESIGN §4.10 账单口径）。 */
@Composable
private fun EntryOrderDetailDialog(item: UnlockResult, onDismiss: () -> Unit) {
    fun money(value: BigDecimal): String =
        value.setScale(2, RoundingMode.HALF_UP).toPlainString()

    @Composable
    fun DetailRow(label: String, value: String) {
        Row(modifier = Modifier.padding(vertical = 2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.width(64.dp),
            )
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("订单详情") },
        text = {
            Column {
                DetailRow("订单号", item.orderNo)
                DetailRow("原价", "¥${item.originPrice}")
                ticketPaidAmount(item)?.takeIf { it > BigDecimal.ZERO }
                    ?.let { DetailRow("小票支付", "¥${money(it)}") }
                if (item.integralCost != "-") DetailRow("积分抵扣", item.integralCost)
                item.otherPromotions.forEach { p ->
                    DetailRow("其他优惠", p.discountAmount ?: "-")
                }
                cashPaidAmount(item)?.let { cash ->
                    val channel = item.payTypeName?.let { "（$it）" } ?: ""
                    DetailRow("现金支付", "¥${money(cash)}$channel")
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                DetailRow("本次花费", "¥${calculateActualCost(item)}")
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
