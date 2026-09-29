package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.RechargeDisclaimer
import kotlin.math.ceil

/**
 * 充值免责声明（2026-09-29 用户要求，DESIGN §3.13 / §4.19）。
 *
 * **电费与一卡通的每个充值入口**在打开充值弹层前都要过这一道（入口清单见
 * `LifeScreen` / `CampusCardSettingsScreen` 的接线）：点「继续充值」才放行，
 * 取消 = 不进充值流程。勾选「一周内不再提醒」后静默 7 天
 * （窗口口径单一来源 `domain/RechargeDisclaimer`，两种充值共用一份）。
 *
 * **首次弹出锁 5 秒**：宿主按 `domain/RechargeDisclaimer.closeLockMs` 算好
 * [closableAfterMs] 传进来，锁住期间两枚按钮置灰、返回/点遮罩无效，确认键上
 * 倒数秒数；确认过之后的弹出 [closableAfterMs] = 0，立即可关。倒计时本身走
 * [rememberCloseLock]（单调时钟，改系统时间绕不过去）。
 *
 * AlertDialog（无输入框，不走 ImeAwareModalBottomSheet——那是含输入框弹层的专属口径）；
 * 正文长，给 `verticalScroll` 防小屏裁切。文案在 `domain/RechargeDisclaimer.ITEMS`
 * （「我的 → 关于」的只读查看入口与这里共用一份），别顺手"精简"掉任何一条。
 */
@Composable
fun RechargeDisclaimerDialog(
    onContinue: (suppressWeek: Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** 关闭锁时长（毫秒）；0 = 立即可关（非首次弹出）。 */
    closableAfterMs: Long = 0L,
) {
    var suppressWeek by remember { mutableStateOf(false) }
    val remainingMs = rememberCloseLock(closableAfterMs)
    val locked = remainingMs > 0L
    AlertDialog(
        // 锁住期间返回键 / 点遮罩也不放走：首次必须停留满 5 秒
        onDismissRequest = { if (!locked) onDismiss() },
        title = { Text("充值免责声明", textAlign = TextAlign.Center) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                RechargeDisclaimer.ITEMS.forEachIndexed { index, item ->
                    Text(
                        text = "${index + 1}. $item",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Spacer(modifier = Modifier.padding(top = 4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { suppressWeek = !suppressWeek },
                ) {
                    Checkbox(checked = suppressWeek, onCheckedChange = { suppressWeek = it })
                    Text(
                        text = "一周内不再提醒",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onContinue(suppressWeek) },
                enabled = !locked,
            ) {
                Text(
                    if (locked) "继续充值（${ceil(remainingMs / 1000.0).toInt()} 秒）" else "继续充值",
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !locked) { Text("取消") }
        },
    )
}
