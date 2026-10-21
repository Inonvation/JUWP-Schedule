package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 正文图片的**只读缩略图行**（2026-10-21，笔记/作业列表展开区用）。
 *
 * 列表里不能再走 `AttachmentImage` 的满宽 1080px 解码——一条作业两张图、十门课展开，
 * 就是二十张满宽位图。这里统一按 240px 解码（`loadAttachmentBitmap` 的下采样 + LRU 缓存），
 * 72dp 方图裁切，点开仍交给 `ImageViewerDialog`。
 *
 * 与编辑态的 [AttachmentStrip] 分工：那个带移除按钮、只出现在编辑态；这个只读。
 */
@Composable
fun AttachmentThumbnailRow(
    fileNames: List<String>,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    onOpen: ((String) -> Unit)? = null,
) {
    if (fileNames.isEmpty()) return
    val context = LocalContext.current
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(fileNames, key = { it }) { name ->
            val imageState by produceState<AttachmentImageState>(
                initialValue = AttachmentImageState.Loading,
                name,
            ) {
                value = loadAttachmentBitmap(context, name, targetWidthPx = 240)
                    ?.let { AttachmentImageState.Loaded(it) }
                    ?: AttachmentImageState.Missing
            }
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .then(if (onOpen != null) Modifier.clickable { onOpen(name) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                when (val state = imageState) {
                    is AttachmentImageState.Loaded -> Image(
                        bitmap = state.bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(size),
                    )

                    AttachmentImageState.Loading -> Unit

                    AttachmentImageState.Missing -> Text(
                        text = "图片\n已丢失",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
