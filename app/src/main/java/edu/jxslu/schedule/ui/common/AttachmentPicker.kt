package edu.jxslu.schedule.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.insertImageRef
import kotlinx.coroutines.launch

/**
 * 图片插入的共用装配（DESIGN §4.20）：系统 Photo Picker（**零权限**）→ 复制压缩进
 * 应用私有目录 → 在光标处插入 `![](img:文件名)`。
 *
 * 返回一个「拉起选择器」的 lambda，交给编辑器工具条的图片按钮。多选按选择顺序逐张插入。
 * 失败（读取/解码失败）给一次提示，不静默丢。
 */
@Composable
fun rememberImageInserter(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onNotice: (String, NoticeTone) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Graph.attachmentStore(context) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var current = value
            var failed = 0
            uris.forEach { uri ->
                val fileName = store.importUri(uri)
                if (fileName == null) {
                    failed++
                } else {
                    val result = insertImageRef(
                        text = current.text,
                        cursor = current.selection.end,
                        fileName = fileName,
                    )
                    current = TextFieldValue(
                        text = result.text,
                        selection = TextRange(result.selectionStart, result.selectionEnd),
                    )
                }
            }
            onValueChange(current)
            if (failed > 0) {
                onNotice("有 $failed 张图片没能读取（跳过）", NoticeTone.Warning)
            }
        }
    }

    return {
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}
