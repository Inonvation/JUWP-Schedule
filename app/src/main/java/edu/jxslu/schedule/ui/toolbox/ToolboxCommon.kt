package edu.jxslu.schedule.ui.toolbox

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.GallerySaveResult
import edu.jxslu.schedule.ui.common.ImageViewerDialog
import edu.jxslu.schedule.ui.common.saveBitmapToGallery
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 百宝箱各工具页的共用装配（DESIGN §4.47）：单图选择器、滑杆行、保存 / 分享出口。
 *
 * 出口口径与课表导出图片（§4.12）逐条对齐：
 * - 保存到相册走 `ui/common/BitmapSave`（MediaStore `Pictures/水贝贝`）；
 * - 分享走 `ScheduleShareStore` 的机制（cacheDir 落盘 + FileProvider 临时授权 Uri），
 *   只是目录换成 `toolbox_share`（`Graph.toolboxShareStore`），两边各裁各的 5 份；
 * - 格式统一 JPEG q92：这三样产物多是截图或照片，PNG 会把体积放大一个量级，
 *   而 q92 下叠加的小字仍然锐利（课表导出是纯色块图，才用 PNG）。
 */
object ToolboxExport {

    private val timeFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** 落盘 / 分享共用的文件名：`水贝贝-百宝箱-<工具>-<时间戳>.jpg`。 */
    fun fileName(tool: String, now: LocalDateTime = LocalDateTime.now()): String =
        "水贝贝-百宝箱-$tool-${now.format(timeFormat)}.jpg"

    suspend fun save(context: Context, bitmap: Bitmap, fileName: String): GallerySaveResult =
        withContext(Dispatchers.IO) {
            saveBitmapToGallery(
                context = context,
                bitmap = bitmap,
                displayName = fileName,
                format = Bitmap.CompressFormat.JPEG,
                quality = 92,
            )
        }

    /** @return null = 成功拉起分享面板；非 null = 给用户看的失败原因 */
    suspend fun share(context: Context, bitmap: Bitmap, fileName: String): String? {
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    out.toByteArray()
                }
                val store = Graph.toolboxShareStore(context)
                store.uriOf(store.save(bytes, fileName))
            }.getOrNull()
        } ?: return "分享失败：无法写出图片"
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val ok = runCatching {
            context.startActivity(Intent.createChooser(send, "分享图片"))
        }.isSuccess
        return if (ok) null else "没有可用的分享目标"
    }
}

/** 单图选择器（系统 Photo Picker，零权限；多选版见笔记的 `rememberImageInserter`）。 */
@Composable
fun rememberSingleImagePicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) onPicked(uri)
    }
    return {
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

/**
 * 只读图片尺寸（`inJustDecodeBounds`，不分配像素内存）。
 * 拼接前先拿它算目标宽与总高——不先把九张全解码出来量尺寸。
 */
fun readImageBounds(context: Context, uri: Uri): Pair<Int, Int>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // 与 `decodeUriSampled` 同坑：量尺寸那一遍的 decodeStream 返回 null 是常态，
    // 不能拿它做「流打不开」的判据（见 ui/common/DecodeSampled.kt 的注释）。
    val boundsStream = context.contentResolver.openInputStream(uri) ?: return null
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return bounds.outWidth to bounds.outHeight
}

/**
 * 工具页的滑杆行：上行「标题 + 当前值」，下行整宽滑杆。
 * 与课表显示设置里的滑块同一形态（`DisplaySettingsContent.RangeLabeledSlider`），
 * 但那只是那个面板的私有件；工具页需要的是带标题+值的简版，不复用私有件也不动它。
 */
@Composable
fun ToolboxSliderRow(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 工具页的贴底动作条（DESIGN §4.47）：主操作按钮**冻结在屏幕底部**，不随内容滚动。
 * 底色 `surfaceContainer`、`navigationBarsPadding` 自理；按钮由调用方给（整宽一颗或成对）。
 * [progress] 为真时在按钮上方加一条进度线（拼接 / 写盘期间）。
 */
@Composable
fun ToolboxActionBar(
    progress: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            if (progress) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/**
 * 工具页的预览区（三个工具页共用）：有图显图（等比完整显示、**点开全屏看大图**），
 * 无图给「点一下选图」的占位。
 */
@Composable
fun ToolboxPreview(
    bitmap: Bitmap?,
    placeholder: String,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 有图时点开全屏（复用 ui/common 的看图弹窗，双指缩放那套不另写），无图时去选图
    var viewing by remember { mutableStateOf(false) }
    AppCard(
        modifier = modifier.fillMaxWidth(),
        onClick = {
            if (bitmap == null) onPick() else viewing = true
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap == null) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    "点图放大",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                )
            }
        }
    }
    if (viewing && bitmap != null) {
        ImageViewerDialog(bitmap = bitmap, onDismiss = { viewing = false })
    }
}

/** 颜色圆点行（观感对齐主题配色弹层里的色点：圆 + 描边，选中套主色圈）。 */
@Composable
fun ColorDotRow(
    colors: List<Int>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEachIndexed { index, color ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    )
                    .clickable { onSelect(index) },
            )
        }
    }
}
