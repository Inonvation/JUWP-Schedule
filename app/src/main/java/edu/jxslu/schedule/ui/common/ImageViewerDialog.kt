package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import edu.jxslu.schedule.ui.week.loadScheduleBackground

/**
 * 图片所在的存储位置。两处的目录与解码口径不同，看图时要指明是从哪来的。
 */
enum class ImageSource {
    /** 笔记·课件与作业的附件（DESIGN §4.20），目录 `notes_img/`。 */
    NoteAttachment,

    /** 课表页背景图（DESIGN §4.21），目录 `schedule_bg/`。 */
    ScheduleBackground,
}

/**
 * 全屏看图（DESIGN §3.11「图片点开全屏」、§4.21 背景图预览）。
 *
 * 黑底 + 双指缩放/拖动（`transformable`）+ 点空白关闭；图片按原比例 Fit。
 * [source] 决定从哪个目录解码：默认的笔记附件是历史行为，背景图从 §4.21 起也复用这个弹窗。
 * 附件向来不解码到全尺寸原图（1440px 目标宽足够看清笔记上的字，省内存）。
 */
@Composable
fun ImageViewerDialog(
    fileName: String,
    onDismiss: () -> Unit,
    source: ImageSource = ImageSource.NoteAttachment,
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, fileName, source) {
        value = when (source) {
            ImageSource.NoteAttachment -> loadAttachmentBitmap(context, fileName, targetWidthPx = 1440)
            // 背景图铺满整屏，看大图给足分辨率；`cache = false` 不改动渲染用的单槽缓存
            ImageSource.ScheduleBackground ->
                loadScheduleBackground(context, fileName, targetLongSidePx = 1920, cache = false)
        }
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformable = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale <= 1f) Offset.Zero else offset + offsetChange
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                // 点空白关闭（缩放态下的拖动由 transformable 消费，不会误触）
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        ) {
            image?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        )
                        .transformable(transformable),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
