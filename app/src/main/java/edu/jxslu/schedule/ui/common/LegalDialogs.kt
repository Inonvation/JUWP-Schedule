package edu.jxslu.schedule.ui.common

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.Disclaimer
import edu.jxslu.schedule.domain.OpenSourceLicenses
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * 长文弹窗的正文高度上限：超出后正文自己滚，确认按钮始终留在可见区。
 * 正文一概不给固定高度，字体放大时靠滚动兜住。
 *
 * 取 420dp：免责声明全文（8 条）在 420dpi 档实测约 380dp，380dp 的老上限会把最后一条
 * 压在视口外面——用户以为看完了，其实还差一条。再高就挤到按钮了。
 */
private val LegalTextMaxHeight = 420.dp

/**
 * 关闭锁的剩余毫秒（0 = 可关）。
 *
 * **用 `SystemClock.elapsedRealtime()` 而不是 `System.currentTimeMillis()`**：后者跟着
 * 系统时间走，用户把时间往前调就能把锁瞬间走完。单调时钟不受改时间、时区与 NTP 校正影响。
 *
 * 计时起点是**本组合第一次进入时**，不是宿主算锁的那一刻——弹窗还没上屏就把秒数走掉，
 * 锁就白设了。
 */
@Composable
fun rememberCloseLock(totalMs: Long): Long {
    var remainingMs by remember(totalMs) { mutableLongStateOf(totalMs) }
    LaunchedEffect(totalMs) {
        if (totalMs <= 0L) {
            remainingMs = 0L
            return@LaunchedEffect
        }
        val startAt = SystemClock.elapsedRealtime()
        while (true) {
            val left = totalMs - (SystemClock.elapsedRealtime() - startAt)
            if (left <= 0L) break
            remainingMs = left
            delay(minOf(200L, left))
        }
        remainingMs = 0L
    }
    return remainingMs
}

/**
 * 长文声明弹窗（免责声明 / 用户须知 / 充值风险查看共用，DESIGN §3.3 / §3.16）。
 *
 * [closeLockMs] 大于 0 时进入强制阅读：确认按钮在锁结束前不可点，点弹窗外或按返回也关不掉。
 * **锁时长由宿主算好传入**（`domain/NoticeConsent.closeLockMs`）——弹窗只负责倒数与置灰，
 * 「是否首次」「要不要弹」的判断都在宿主，别塞进弹窗里。
 */
@Composable
fun NoticeDialog(
    title: String,
    intro: String,
    items: List<String>,
    onDismiss: () -> Unit,
    confirmLabel: String = "我知道了",
    closeLockMs: Long = 0L,
) {
    val remainingMs = rememberCloseLock(closeLockMs)
    val locked = remainingMs > 0L
    AlertDialog(
        onDismissRequest = { if (!locked) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = LegalTextMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = intro,
                    style = MaterialTheme.typography.bodyMedium,
                )
                items.forEach { item ->
                    Text(
                        text = "· $item",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !locked) {
                if (locked) {
                    // 强制阅读的进度画在确认键上：环走完 = 可关闭，比干等一串秒数更可感
                    CountdownRing(
                        remainingMs = remainingMs,
                        totalMs = closeLockMs,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (locked) "${ceil(remainingMs / 1000.0).toInt()} 秒后可关闭" else confirmLabel,
                )
            }
        },
    )
}

/**
 * 免责声明弹窗。两处使用：我的 → 关于（[readSeconds] = 0，随时可关），以及首启引导
 * （那里由 [NoticeDialog] 直接调用，锁 5 秒）。
 *
 * 正文来自 [Disclaimer]，与仓库根 README.md 的「免责声明」一节同源；
 * 不在弹窗里另抄一份，避免改一处漏一处。
 */
@Composable
fun DisclaimerDialog(
    onDismiss: () -> Unit,
    confirmLabel: String = "我知道了",
    readSeconds: Int = 0,
) = NoticeDialog(
    title = "免责声明",
    intro = Disclaimer.INTRO,
    items = Disclaimer.ITEMS,
    onDismiss = onDismiss,
    confirmLabel = confirmLabel,
    closeLockMs = readSeconds * 1000L,
)

/** 强制阅读倒计时环：剩余比例 = 弧长，随倒计时线性耗尽。 */
@Composable
private fun CountdownRing(remainingMs: Long, totalMs: Long, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(color = color.copy(alpha = 0.25f), style = stroke)
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * remainingMs.coerceAtLeast(0L).toFloat() /
                totalMs.coerceAtLeast(1L).toFloat(),
            useCenter = false,
            style = stroke,
        )
    }
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
