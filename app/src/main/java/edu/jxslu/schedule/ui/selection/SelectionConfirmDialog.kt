package edu.jxslu.schedule.ui.selection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.SelectionCourse
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect

/**
 * 选课 / 退课的二次确认弹窗（DESIGN §3.22，2026-10-08 v3 重做）。
 *
 * 口径：**关键信息放大**——课程名大字主视觉；「开设校区」与「时间冲突」单独一块
 * 高亮（冲突有内容时 error 色，无冲突给中性「无时间冲突」；退课场景给「当前已选」
 * 标记）；其余（编号/类别/学分/教师/时间/地点/已选·容量/余量）退成次级小字。
 * 大字体档位下内容可滚（`heightIn` + `verticalScroll`），按钮不被挤出；
 * 进出场走项目统一的 [DialogWindowFadeEffect]（窗口层淡入淡出，不是硬切）。
 */
@Composable
fun SelectionConfirmDialog(
    course: SelectionCourse,
    dropping: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (dropping) "确认退课？" else "确认选课？",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    course.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                // 高亮块：开设校区 + 冲突状态（退课场景换成「当前已选」标记）
                Surface(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        KeyValueRow(
                            label = "开设校区",
                            value = course.campus.ifBlank { "未标注" },
                        )
                        if (dropping) {
                            KeyValueRow(
                                label = "当前状态",
                                value = "已选（本次将退掉这门课）",
                                valueColor = MaterialTheme.colorScheme.primary,
                            )
                        } else if (course.conflictNote.isNotBlank()) {
                            KeyValueRow(
                                label = "时间冲突",
                                value = course.conflictNote,
                                valueColor = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            KeyValueRow(
                                label = "时间冲突",
                                value = "无时间冲突",
                            )
                        }
                    }
                }
                val meta = listOfNotNull(
                    course.courseNo.takeIf { it.isNotBlank() },
                    course.courseType.takeIf { it.isNotBlank() },
                    course.credit?.let { "${creditText(it)} 学分" },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    SecondaryLine(meta)
                }
                if (course.teacher.isNotBlank()) {
                    SecondaryLine("教师：${course.teacher}")
                }
                course.timeText.lines().filter { it.isNotBlank() }.forEach { line ->
                    SecondaryLine("时间：$line")
                }
                course.placeText.lines().filter { it.isNotBlank() }.forEach { line ->
                    SecondaryLine("地点：$line")
                }
                val seats = when {
                    course.enrolled != null && course.capacity != null ->
                        "已选 ${course.enrolled} / 容量 ${course.capacity}"
                    course.enrolled != null -> "已选 ${course.enrolled}"
                    course.capacity != null -> "容量 ${course.capacity}"
                    else -> null
                }
                seats?.let { SecondaryLine(it) }
                course.remaining?.let { SecondaryLine("余量：$it") }
                if (dropping) {
                    Text(
                        "退课以教务处理结果为准，可能涉及选课规则（如退课次数限制）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(
                    if (dropping) "退课" else "选课",
                    color = if (dropping) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 44.dp),
            ) { Text("取消") }
        },
    )
    DialogWindowFadeEffect()
}

@Composable
private fun KeyValueRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        )
    }
}

@Composable
private fun SecondaryLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = Modifier.padding(top = 6.dp),
    )
}
