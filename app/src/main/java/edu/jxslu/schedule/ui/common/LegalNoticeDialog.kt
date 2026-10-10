package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.LegalNotice

/**
 * 「使用前须知」周期声明弹窗（`domain/LegalNotice`）。
 *
 * 与 [NoticeDialog]（首启两份声明）的差别：
 * - **字号大一档**——标题 `titleLarge`、正文 `bodyLarge`（用户要求"比其他大一些"）；
 * - **重点加粗**——正文里的 `**…**` 由 [LegalNotice.parseBold] 解析成 `AnnotatedString`；
 * - **没有"不再提醒"出口**——点「同意」关掉，`LegalNotice.INTERVAL_MS` 后还会再弹
 *   （宿主侧 `LegalNoticeHost` 记"上次弹出时刻"，见 `domain/LegalNotice.shouldShow`）。
 *
 * 点「同意」与点弹窗外/返回**都算关闭并记录**（用户口径：只是不能永久关闭，不是强制停留），
 * 所以两条出口都走 [onDismiss]。
 *
 * 窗口进出场动画走 [DialogWindowFadeEffect]——**必须放在 `AlertDialog` 的内容槽里**
 * （此时 `LocalView` 才是 `DialogLayout`），放外面取不到 dialog window、静默失效。
 */
@Composable
fun LegalNoticeDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = LegalNotice.TITLE,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            DialogWindowFadeEffect()
            Column(
                modifier = Modifier
                    // 与 LegalDialogs 同口径：正文超长自己滚，确认键始终可见
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = LegalNotice.INTRO,
                    style = MaterialTheme.typography.bodyLarge,
                )
                LegalNotice.ITEMS.forEachIndexed { index, item ->
                    Text(
                        text = numberedBold(index + 1, item),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("同意") }
        },
    )
}

/** `**…**` → 加粗 `AnnotatedString`，前面加「N. 」序号。 */
private fun numberedBold(index: Int, item: String): AnnotatedString = buildAnnotatedString {
    append("$index. ")
    LegalNotice.parseBold(item).forEach { segment ->
        if (segment.bold) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(segment.text) }
        } else {
            append(segment.text)
        }
    }
}
