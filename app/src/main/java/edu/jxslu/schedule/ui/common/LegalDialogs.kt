package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.Disclaimer
import edu.jxslu.schedule.domain.OpenSourceLicenses
import kotlinx.coroutines.delay

/**
 * 长文弹窗的正文高度上限：超出后正文自己滚，确认按钮始终留在可见区。
 * 正文一概不给固定高度，字体放大时靠滚动兜住。
 *
 * 取 420dp：免责声明全文（8 条）在 420dpi 档实测约 380dp，380dp 的老上限会把最后一条
 * 压在视口外面——用户以为看完了，其实还差一条。再高就挤到按钮了。
 */
private val LegalTextMaxHeight = 420.dp

/**
 * 免责声明全文弹窗。两处使用：我的 → 关于，以及首启引导第一步（DESIGN §3.3 / §3.16）。
 *
 * 正文来自 [Disclaimer]，与仓库根 README.md 的「免责声明」一节同源；
 * 不在弹窗里另抄一份，避免改一处漏一处。
 *
 * [readSeconds] 大于 0 时进入强制阅读：确认按钮在倒计时结束前不可点，点弹窗外或按返回
 * 也关不掉。首启引导第一步要的就是「看过」，那里传 5；「我的 → 关于」里是随时可关的
 * 查看，用默认的 0。
 */
@Composable
fun DisclaimerDialog(
    onDismiss: () -> Unit,
    confirmLabel: String = "我知道了",
    readSeconds: Int = 0,
) {
    val remaining by produceState(initialValue = readSeconds, key1 = readSeconds) {
        var left = readSeconds
        while (left > 0) {
            delay(1_000L)
            left -= 1
            value = left
        }
    }
    val canDismiss = remaining <= 0
    AlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        title = { Text("免责声明") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = LegalTextMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = Disclaimer.INTRO,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Disclaimer.ITEMS.forEach { item ->
                    Text(
                        text = "· $item",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = canDismiss) {
                Text(if (canDismiss) confirmLabel else "$remaining 秒后可关闭")
            }
        },
    )
}

/**
 * 开源许可弹窗：本应用许可 + 随包分发的主要第三方组件。
 *
 * 数据在 [OpenSourceLicenses]；这里只负责排版，不写死任何组件或许可名。
 */
@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开源许可") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = LegalTextMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "${OpenSourceLicenses.SELF_NAME} · ${OpenSourceLicenses.SELF_LICENSE}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                OpenSourceLicenses.ENTRIES.forEach { entry ->
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = entry.license,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Text(
                    text = OpenSourceLicenses.HUGEICONS_NOTE,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Text(
                    text = OpenSourceLicenses.FOOTER,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
