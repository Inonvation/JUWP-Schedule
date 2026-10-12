package edu.jxslu.schedule.ui.toolbox

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.ToolboxImage
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.GallerySaveResult
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingChoiceRow
import edu.jxslu.schedule.ui.common.decodeUriSampled
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import edu.jxslu.schedule.ui.common.showNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 预览用降采样源的长边（成品按原图另解一遍，两遍用同一组比例参数，预览即所得）。 */
private const val PreviewLongSide = 1200

/** 成品解码的长边上限：再大对截图/照片这类输入没有意义，只是内存风险。 */
private const val FullLongSide = 2600

/** 水印颜色预设（深色为主，浅底深色图上都压得住）。 */
private val WatermarkColors = listOf(
    "白" to 0xFFFFFFFF.toInt(),
    "黑" to 0xFF000000.toInt(),
    "红" to 0xFFE53935.toInt(),
    "橙" to 0xFFFB8C00.toInt(),
    "绿" to 0xFF43A047.toInt(),
    "蓝" to 0xFF1E88E5.toInt(),
    "紫" to 0xFF8E24AA.toInt(),
    "灰" to 0xFF757575.toInt(),
)

/**
 * 图片水印（DESIGN §4.47，实现思路借鉴「一个木函」的平铺水印）：
 * 文字先渲染成一小块平铺单元（多行用 StaticLayout），再经 BitmapShader 的
 * REPEAT 平铺满图；不透明度 / 旋转 / 字号 / 颜色 / 密度五档可调。
 *
 * 预览（长边 ≤ [PreviewLongSide]）与成品（长边 ≤ [FullLongSide]）都走
 * [ToolboxRenderer.watermark]，尺寸只按图宽取比例，所以两边观感一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatermarkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var previewSource by remember { mutableStateOf<Bitmap?>(null) }
    var text by remember { mutableStateOf("水贝贝") }
    var density by remember { mutableStateOf(ToolboxImage.Density.Normal) }
    var alphaPercent by remember { mutableStateOf(35f) }
    var rotationDeg by remember { mutableStateOf(-30f) }
    var scalePercent by remember { mutableStateOf(100f) }
    // 默认黑（[WatermarkColors] 的第 2 个）：截图这类浅底输入是常见来源，白字压不住
    // （2026-10-12 用户口径）
    var colorIndex by remember { mutableStateOf(1) }
    var busy by remember { mutableStateOf(false) }

    val pickImage = rememberSingleImagePicker { uri -> sourceUri = uri }

    LaunchedEffect(sourceUri) {
        val uri = sourceUri ?: return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            decodeUriSampled(context, uri, PreviewLongSide)
        }
        previewSource = decoded
        // 解码失败不再静默（2026-10-12）：读不出来当场说，预览区不空等
        if (decoded == null) {
            scope.launch {
                snackbar.showNotice(
                    AppNoticeVisuals("图片读不出来，换一张试试", tone = NoticeTone.Error),
                )
            }
        }
    }

    // 预览重算：参数一动就整幅重渲染。源是降采样图，几十毫秒级，不做防抖。
    // 七个参数收成一个列表 key（produceState 最多五个 key 参数，列表按结构比较）。
    val previewKeys = listOf(
        previewSource, text, density, alphaPercent, rotationDeg, scalePercent, colorIndex,
    )
    val preview by produceState<Bitmap?>(initialValue = null, previewKeys) {
        val src = previewSource
        if (src == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            ToolboxRenderer.watermark(
                src = src,
                text = text,
                color = WatermarkColors[colorIndex].second,
                alphaPercent = alphaPercent,
                rotationDeg = rotationDeg,
                scale = scalePercent / 100f,
                density = density,
            )
        }
    }

    fun notice(message: String, tone: NoticeTone) {
        scope.launch { snackbar.showNotice(AppNoticeVisuals(message, tone = tone)) }
    }

    fun renderFull(uri: Uri): Bitmap? {
        val src = decodeUriSampled(context, uri, FullLongSide) ?: return null
        val rendered = ToolboxRenderer.watermark(
            src = src,
            text = text,
            color = WatermarkColors[colorIndex].second,
            alphaPercent = alphaPercent,
            rotationDeg = rotationDeg,
            scale = scalePercent / 100f,
            density = density,
        )
        src.recycle()
        return rendered
    }

    fun runExport(share: Boolean) {
        val uri = sourceUri
        if (uri == null) {
            notice("先选一张图片", NoticeTone.Warning)
            return
        }
        if (text.isBlank()) {
            notice("先写上水印文字", NoticeTone.Warning)
            return
        }
        busy = true
        scope.launch {
            val bitmap = withContext(Dispatchers.Default) { renderFull(uri) }
            if (bitmap == null) {
                notice("读取图片失败，请重试", NoticeTone.Error)
                busy = false
                return@launch
            }
            val fileName = ToolboxExport.fileName("水印")
            if (share) {
                val error = ToolboxExport.share(context, bitmap, fileName)
                if (error != null) notice(error, NoticeTone.Warning)
            } else {
                when (val result = ToolboxExport.save(context, bitmap, fileName)) {
                    is GallerySaveResult.Saved ->
                        notice("已保存到相册（Pictures/水贝贝）", NoticeTone.Success)
                    is GallerySaveResult.Failed -> notice(result.message, NoticeTone.Error)
                }
            }
            bitmap.recycle()
            busy = false
        }
    }

    // 生成 / 写盘期间不接受退出（与课表导出同一口径）
    BackHandler(enabled = busy) {}

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = pinnedStatusBars(),
                title = { Text("图片水印") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToolboxPreview(
                bitmap = preview,
                placeholder = "选一张图片，水印会平铺满图",
                onPick = pickImage,
            )
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("水印文字") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SettingChoiceRow(
                        title = "密度",
                        subtitle = "平铺单元的疏密",
                        options = listOf("无", "标准", "大"),
                        selectedIndex = density.ordinal,
                        onSelect = { density = ToolboxImage.Density.entries[it] },
                    )
                    ToolboxSliderRow(
                        title = "不透明度",
                        valueText = "${alphaPercent.toInt()}%",
                        value = alphaPercent,
                        valueRange = 5f..100f,
                        onValueChange = { alphaPercent = it },
                    )
                    ToolboxSliderRow(
                        title = "旋转角度",
                        valueText = "${rotationDeg.toInt()}°",
                        value = rotationDeg,
                        valueRange = -90f..90f,
                        onValueChange = { rotationDeg = it },
                    )
                    ToolboxSliderRow(
                        title = "字号",
                        valueText = "${scalePercent.toInt()}%",
                        value = scalePercent,
                        valueRange = 30f..250f,
                        onValueChange = { scalePercent = it },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("颜色", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    ColorDotRow(
                        colors = WatermarkColors.map { it.second },
                        selectedIndex = colorIndex,
                        onSelect = { colorIndex = it },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = pickImage,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (sourceUri == null) "选图" else "换图") }
                Button(
                    onClick = { runExport(share = false) },
                    enabled = !busy && sourceUri != null,
                    modifier = Modifier.weight(1f),
                ) { Text("保存到相册") }
            }
            OutlinedButton(
                onClick = { runExport(share = true) },
                enabled = !busy && sourceUri != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("分享") }
            Spacer(Modifier.height(12.dp))
        }
    }
}
