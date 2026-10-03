package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp

/**
 * 正文附件的缩略条（编辑态专用，DESIGN §3.11）：列出当前正文引用的图片，
 * 点缩略图看大图、点右上角移除（同时从正文里摘掉引用）。
 *
 * 存在的理由：图片在正文里是一行 `![](img:…)` 文本，用户很难"找到并删掉某张图"——
 * 这个条把附件管理显性化。
 */
@Composable
fun AttachmentStrip(
    fileNames: List<String>,
    onRemove: (String) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (fileNames.isEmpty()) return
    val context = LocalContext.current
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(fileNames, key = { it }) { name ->
            // 三态：缩略图解码也要走磁盘，加载中不喊「已丢失」（同 AttachmentImage 口径）
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
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { onOpen(name) },
            ) {
                when (val state = imageState) {
                    is AttachmentImageState.Loaded -> Image(
                        bitmap = state.bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(64.dp).width(64.dp),
                    )

                    AttachmentImageState.Loading -> Unit

                    AttachmentImageState.Missing -> Text(
                        text = "图片\n已丢失",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(4.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .clickable { onRemove(name) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "移除图片",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}
