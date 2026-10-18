package edu.jxslu.schedule.ui.ujing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.ui.common.rememberSheetDismisser
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.QrCodeScan

/**
 * U净 弹窗（从下弹出）：与胖乖 / 趣智校园那种入口面板同形态。
 * 有在案洗涤订单时，顶部先显示它的状态与剩余时间（[UjingOrderStatusLine]，订单对表在
 * 打开弹窗那一下做，见调用点）；主按钮「扫码识别洗衣机」，副行进 U净 页。
 *
 * 2026-10-19 从生活页随入口一起移到今日页「江水生活」：组件搬进 ui/ujing 包，
 * 「查看洗衣房空闲情况」走 [rememberSheetDismisser] 播完退场再进页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UjingEntrySheet(
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onOpenPage: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember(context) { Graph.ujing(context) }
    val order by repo.orderState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState()
    val dismiss = rememberSheetDismisser(sheetState, onDismiss)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("U净 · 洗衣房", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            // 在案订单的状态行：出现 / 收摊都走高度伸缩，弹窗不会凭空多一块再跳回去
            // （首帧就有时不播入场——AnimatedVisibility 的初值即目标态）
            val liveOrder = order?.takeIf { !it.isTerminal }
            AnimatedVisibility(
                visible = liveOrder != null,
                enter = fadeIn(tween(220)) +
                    expandVertically(
                        tween(220, easing = FastOutSlowInEasing),
                        expandFrom = Alignment.Top,
                    ),
                exit = fadeOut(tween(160)) +
                    shrinkVertically(tween(160), shrinkTowards = Alignment.Top),
            ) {
                liveOrder?.let { UjingOrderStatusLine(it) }
            }
            Text(
                text = "扫洗衣机机身上的二维码即可下单",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Button(
                onClick = onScan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(HugeIcons.QrCodeScan, contentDescription = null, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(9.dp))
                Text("扫码识别洗衣机", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = "查看洗衣房空闲情况") { dismiss { onOpenPage() } }
                    .padding(vertical = 11.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("查看洗衣房空闲情况", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Icon(
                    HugeIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
