package edu.jxslu.schedule.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditSheet(
    course: Course?,
    onDismiss: () -> Unit,
    onSave: (Course) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember(course) { mutableStateOf(course?.name ?: "") }
    var teacher by remember(course) { mutableStateOf(course?.teacher ?: "") }
    var position by remember(course) { mutableStateOf(course?.position ?: "") }
    var day by remember(course) { mutableStateOf((course?.day ?: 1).toString()) }
    var start by remember(course) { mutableStateOf((course?.startSection ?: 1).toString()) }
    var end by remember(course) { mutableStateOf((course?.endSection ?: 1).toString()) }
    var weeks by remember(course) {
        mutableStateOf(course?.let { weeksToInput(it.weeks) } ?: "1-16")
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                if (course == null) "添加课程" else "编辑课程",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            CourseEditorFields(
                name = name,
                onName = { name = it },
                teacher = teacher,
                onTeacher = { teacher = it },
                position = position,
                onPosition = { position = it },
                day = day.toIntOrNull() ?: 1,
                onDay = { day = it.toString() },
                startSection = start.toIntOrNull() ?: 1,
                onStart = { start = it.toString() },
                endSection = end.toIntOrNull() ?: 1,
                onEnd = { end = it.toString() },
                weeksText = weeks,
                onWeeks = { weeks = it },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(
                    onClick = {
                        val nameVal = name.trim()
                        if (nameVal.isEmpty()) return@TextButton
                        val d = (day.toIntOrNull() ?: 1).coerceIn(1, 7)
                        val s = start.toIntOrNull() ?: 1
                        val e = (end.toIntOrNull() ?: s).coerceAtLeast(s)
                        val w = parseWeeksInput(weeks).ifEmpty { setOf(1) }
                        onSave(
                            Course(
                                id = course?.id ?: 0,
                                name = nameVal,
                                teacher = teacher.trim(),
                                position = position.trim(),
                                day = d,
                                startSection = s,
                                endSection = e,
                                weeks = w,
                                // 编辑时必须把非表单字段带回来。
                                // 这里原先只带了 colorIndex，于是改一门自定义时间的课会被打回按作息表计算，
                                // 加了 kind 之后改一门实验课还会把它变成理论课——都是同一个疏漏。
                                isCustomTime = course?.isCustomTime ?: false,
                                customStartTime = course?.customStartTime,
                                customEndTime = course?.customEndTime,
                                // 新增课程交给 Repository 按「尚未占用的调色板下标」分配：
                                // 用课程名哈希取色在 12 个桶里必然撞色，顺序占位才不会。
                                colorIndex = course?.colorIndex ?: 0,
                                kind = course?.kind ?: CourseKind.Theory,
                            ),
                        )
                    },
                ) { Text("保存") }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun DeleteConfirmDialog(
    courseName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除课程") },
        text = { Text("确认删除「$courseName」？") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
