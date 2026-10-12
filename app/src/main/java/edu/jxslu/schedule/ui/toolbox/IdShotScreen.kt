package edu.jxslu.schedule.ui.toolbox

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
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

/** 预览用降采样源的长边（与图片水印同一口径）。 */
private const val PreviewLongSide = 1200

/** 成品解码的长边上限（与图片水印同一口径）。 */
private const val FullLongSide = 2600

/**
 * 文字颜色预设（点选即用）；三个 RGB 滑块是「自定义」口。
 * 默认黑：学校要的活动截图多为浅色界面的截图，黑字直接压得住；
 * 深色截图一键换白（取色圆点第一枚）。
 */
private val IdShotColorPresets = listOf(
    0xFF000000.toInt(),
    0xFFFFFFFF.toInt(),
    0xFFE53935.toInt(),
    0xFFFB8C00.toInt(),
    0xFF43A047.toInt(),
    0xFF1E88E5.toInt(),
    0xFF8E24AA.toInt(),
    0xFF757575.toInt(),
)

private const val DefaultIdShotColor = 0xFF000000.toInt()

/**
 * 活动截图（DESIGN §4.47）：导入一张图，把**姓名、学号两行**画到图上，
 * 一键生成学校收集线上活动材料要的那种截图。姓名 / 学号自动取自
 * `DisplayPrefsStore` 的学籍卡（教务登录 / 导成绩时落库），不联网；取不到就手填。
 *
 * 2026-10-12 用户口径：**只留文字、不要底衬框**；位置默认**图片正中间**
 * （另有 底部居中 / 左下 / 右下），颜色预设 + RGB 滑块自定义，字号可调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdShotScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var previewSource by remember { mutableStateOf<Bitmap?>(null) }
    var name by remember { mutableStateOf("") }
    var studentId by remember { mutableStateOf("") }
    var anchor by remember { mutableStateOf(ToolboxImage.Anchor.Center) }
    var textColor by remember { mutableStateOf(DefaultIdShotColor) }
    var scalePercent by remember { mutableStateOf(100f) }
    var busy by remember { mutableStateOf(false) }

    // 学籍卡预填：只在第一次拿到数据时填一次，之后用户改过的不再覆盖
    val prefs = remember(context) { Graph.displayPrefs(context) }
    val profileName by prefs.profileName.collectAsStateWithLifecycle(initialValue = "")
    val profileStudentId by prefs.profileStudentId.collectAsStateWithLifecycle(initialValue = "")
    var prefilled by remember { mutableStateOf(false) }
    LaunchedEffect(profileName, profileStudentId) {
        if (prefilled) return@LaunchedEffect
        if (profileName.isBlank() && profileStudentId.isBlank()) return@LaunchedEffect
        name = profileName.ifBlank { name }
        studentId = profileStudentId.ifBlank { studentId }
        prefilled = true
    }

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

    val previewKeys = listOf(previewSource, name, studentId, anchor, textColor, scalePercent)
    val preview by produceState<Bitmap?>(initialValue = null, previewKeys) {
        val src = previewSource
        if (src == null) {
            value = null
            return@produceState
        }
        val lines = ToolboxImage.idShotLines(name, studentId)
        value = withContext(Dispatchers.Default) {
            ToolboxRenderer.idShot(src, lines, textColor, scalePercent / 100f, anchor)
        }
    }

    fun notice(message: String, tone: NoticeTone) {
        scope.launch { snackbar.showNotice(AppNoticeVisuals(message, tone = tone)) }
    }

    fun runExport(share: Boolean) {
        val uri = sourceUri
        if (uri == null) {
            notice("先选一张图片", NoticeTone.Warning)
            return
        }
        val lines = ToolboxImage.idShotLines(name, studentId)
        if (lines.isEmpty()) {
            notice("先填姓名或学号", NoticeTone.Warning)
            return
        }
        busy = true
        scope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                val src = decodeUriSampled(context, uri, FullLongSide) ?: return@withContext null
                val rendered = ToolboxRenderer.idShot(
                    src = src,
                    lines = lines,
                    textColor = textColor,
                    scale = scalePercent / 100f,
                    anchor = anchor,
                )
                src.recycle()
                rendered
            }
            if (bitmap == null) {
                notice("读取图片失败，请重试", NoticeTone.Error)
                busy = false
                return@launch
            }
            val fileName = ToolboxExport.fileName("活动截图")
            if (share) {
                val error = ToolboxExport.share(context, bitmap, fileName)
                if (error != null) notice(error, NoticeTone.Warning)
            } else {
                when (val saved = ToolboxExport.save(context, bitmap, fileName)) {
                    is GallerySaveResult.Saved ->
                        notice("已保存到相册（Pictures/水贝贝）", NoticeTone.Success)
                    is GallerySaveResult.Failed -> notice(saved.message, NoticeTone.Error)
                }
            }
            bitmap.recycle()
            busy = false
        }
    }

    BackHandler(enabled = busy) {}

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = pinnedStatusBars(),
                title = { Text("活动截图") },
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
                placeholder = "选一张活动截图，自动落上姓名与学号",
                onPick = pickImage,
            )
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("姓名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = studentId,
                        onValueChange = { studentId = it },
                        label = { Text("学号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (profileName.isBlank() && profileStudentId.isBlank()) {
                        Text(
                            "姓名与学号没有自动带出——在教务登录一次（或导入一次成绩）就会记住，也可以直接手填。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                    SettingChoiceRow(
                        title = "位置",
                        subtitle = "文字画在图片的哪个位置",
                        options = listOf("居中", "底部居中", "左下", "右下"),
                        selectedIndex = anchor.ordinal,
                        onSelect = { anchor = ToolboxImage.Anchor.entries[it] },
                    )
                    ToolboxSliderRow(
                        title = "字号",
                        valueText = "${scalePercent.toInt()}%",
                        value = scalePercent,
                        valueRange = 50f..200f,
                        onValueChange = { scalePercent = it },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("文字颜色", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    ColorDotRow(
                        colors = IdShotColorPresets,
                        selectedIndex = IdShotColorPresets.indexOf(textColor),
                        onSelect = { textColor = IdShotColorPresets[it] },
                    )
                    Text(
                        "浅色截图用黑色、深色截图用白色；下面三根滑块可以调任意颜色。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                    val red = (textColor shr 16) and 0xFF
                    val green = (textColor shr 8) and 0xFF
                    val blue = textColor and 0xFF
                    ToolboxSliderRow(
                        title = "红",
                        valueText = red.toString(),
                        value = red.toFloat(),
                        valueRange = 0f..255f,
                        onValueChange = { value ->
                            textColor = AndroidColor.rgb(value.toInt(), green, blue)
                        },
                    )
                    ToolboxSliderRow(
                        title = "绿",
                        valueText = green.toString(),
                        value = green.toFloat(),
                        valueRange = 0f..255f,
                        onValueChange = { value ->
                            textColor = AndroidColor.rgb(red, value.toInt(), blue)
                        },
                    )
                    ToolboxSliderRow(
                        title = "蓝",
                        valueText = blue.toString(),
                        value = blue.toFloat(),
                        valueRange = 0f..255f,
                        onValueChange = { value ->
                            textColor = AndroidColor.rgb(red, green, value.toInt())
                        },
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
