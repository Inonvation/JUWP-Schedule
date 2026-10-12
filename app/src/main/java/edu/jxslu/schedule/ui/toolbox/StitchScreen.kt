package edu.jxslu.schedule.ui.toolbox

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.StitchScan
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardDivider
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
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete02

/** 一次最多拼几张（Photo Picker 的多选上限与这里一致）。 */
private const val MaxStitchImages = 9

/** 缩略图的长边（列表里的展示尺寸，几十 KB 一张）。 */
private const val ThumbLongSide = 220

/** 拼接时单张源图的解码长边上限（之后由 Canvas 缩到目标宽）。 */
private const val StitchSourceLongSide = 2600

/**
 * 图片拼接（DESIGN §4.47，等宽归一的竖拼；原版「一个木函」会顺手裁掉上下边缘，
 * 那是给滚动截屏留的，本项目不做——活动截图场景里裁掉的是内容）。
 *
 * 内存纪律：只先读各图尺寸（不占像素内存）→ 算目标宽与总高 → 逐张解码、画、回收
 * （[ToolboxRenderer.stitchStreaming]），任意时刻只有一张源图 + 一张成品在内存里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StitchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var thumbnails by remember { mutableStateOf<List<Bitmap?>>(emptyList()) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 拼接方式：**默认智能**（2026-10-12 用户口径：要 PPIICC 那套——裁掉每张重复的头尾 +
    // 按内容对齐去重叠，见 §4.47）。它**只适合同一页面连续滚动的一批截图**；
    // 一组不相关的图会被它裁掉真实内容，所以选择行下面写明了适用边界，并留「直接」一档。
    var smart by remember { mutableStateOf(true) }
    var tolerance by remember { mutableStateOf(StitchScan.Tolerance.Normal) }

    // 状态栏行数（px）：智能识别时它按「公共头」直接计入，不参与行比对
    // （时钟 / 电量每张都不同，会打断公共带的连续匹配）
    val statusBarRows = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this) }

    fun notice(message: String, tone: NoticeTone) {
        scope.launch { snackbar.showNotice(AppNoticeVisuals(message, tone = tone)) }
    }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = MaxStitchImages),
    ) { picked ->
        if (picked.isEmpty()) return@rememberLauncherForActivityResult
        val merged = (uris + picked).distinct()
        if (merged.size > MaxStitchImages) {
            notice("一次最多拼 $MaxStitchImages 张，多出的没加进来", NoticeTone.Warning)
        }
        uris = merged.take(MaxStitchImages)
    }

    // 列表一变：作废旧结果、重解码缩略图（缩略图很小，旧的一批交给 GC）
    LaunchedEffect(uris) {
        result = null
        if (uris.isEmpty()) {
            thumbnails = emptyList()
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.IO) {
            uris.map { decodeUriSampled(context, it, ThumbLongSide) }
        }
        thumbnails = decoded
        // 解码失败不再静默（2026-10-12）：缩略图空白当场说，别让人对着空列表猜
        if (decoded.any { it == null }) {
            notice("有图片的缩略图读不出来（拼接时会再试一次）", NoticeTone.Warning)
        }
    }

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target !in uris.indices) return
        uris = uris.toMutableList().also { it.add(target, it.removeAt(index)) }
    }

    fun startStitch() {
        if (uris.isEmpty()) {
            notice("先加几张图片", NoticeTone.Warning)
            return
        }
        busy = true
        scope.launch {
            // 尺寸与 Uri 成对过滤：跳过读不出的一两张后，下标必须仍指向同一张图
            val usable = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> readImageBounds(context, uri)?.let { uri to it } }
            }
            if (usable.size != uris.size) {
                notice("有图片读不出来（已跳过）", NoticeTone.Warning)
            }
            if (usable.isEmpty()) {
                notice("图片都读不出来，换个来源试试", NoticeTone.Error)
                busy = false
                return@launch
            }
            val sizes = usable.map { it.second }
            // 智能模式：先规划裁剪（原图坐标）。宽度不一致 / 识别不出结论 → 退回直接拼接
            // （高度不同不影响——长截图与普通截图混拼是常态）
            var keeps: List<StitchScan.KeepRows>? = null
            if (smart) {
                keeps = if (!StitchScan.sameWidth(sizes)) {
                    // 高度不同没关系（长截图 + 普通截图混拼是常态），宽度不同就真的没坐标系
                    val widths = sizes.map { it.first }.distinct().joinToString(" / ")
                    notice("这些图宽度不一致（$widths），这次按直接拼接", NoticeTone.Warning)
                    null
                } else {
                    StitchSmart.plan(
                        context = context,
                        uris = usable.map { it.first },
                        fullSizes = sizes,
                        statusBarRowsFull = statusBarRows,
                        tolerance = tolerance,
                    ).also {
                        if (it == null) {
                            notice("没识别出可裁的重复内容，按直接拼接", NoticeTone.Info)
                        }
                    }
                }
            }
            val rendered = withContext(Dispatchers.Default) {
                ToolboxRenderer.stitchStreaming(
                    sourceSizes = sizes,
                    spacingPx = 0,
                    keeps = keeps,
                ) { index ->
                    withContext(Dispatchers.IO) {
                        decodeUriSampled(context, usable[index].first, StitchSourceLongSide)
                    }
                }
            }
            if (keeps != null) {
                val removed = sizes.sumOf { it.second } - keeps.sumOf { it.height }
                if (removed > 0) {
                    notice("智能拼接：去掉了 $removed 行重复内容", NoticeTone.Success)
                }
            }
            result = rendered
            busy = false
        }
    }

    fun runExport(share: Boolean) {
        val bitmap = result
        if (bitmap == null) {
            notice("先点「开始拼接」生成图片", NoticeTone.Warning)
            return
        }
        busy = true
        scope.launch {
            val fileName = ToolboxExport.fileName("拼接")
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
            busy = false
        }
    }

    BackHandler(enabled = busy) {}

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = pinnedStatusBars(),
                title = { Text("图片拼接") },
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
                bitmap = result,
                placeholder = if (uris.isEmpty()) {
                    "选 2–9 张图片，竖着拼成一张长图"
                } else {
                    "点「开始拼接」生成预览"
                },
                onPick = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingChoiceRow(
                        title = "拼接方式",
                        subtitle = "智能：裁掉每张重复的状态栏 / 底栏，按内容对齐去掉滚动重叠" +
                            "——适合同一页面连续滚动的截图；不相关的一组图请用「直接」",
                        options = listOf("智能", "直接"),
                        selectedIndex = if (smart) 0 else 1,
                        onSelect = { smart = it == 0 },
                    )
                    if (smart) {
                        SettingChoiceRow(
                            title = "识别容差",
                            subtitle = "接缝错位就放宽；把不相关内容粘一起就收紧",
                            options = listOf("严格", "标准", "宽松"),
                            selectedIndex = tolerance.ordinal,
                            onSelect = { tolerance = StitchScan.Tolerance.entries[it] },
                        )
                    }
                }
            }
            if (uris.isNotEmpty()) {
                AppCard {
                    Column {
                        uris.forEachIndexed { index, _ ->
                            StitchRow(
                                index = index,
                                thumbnail = thumbnails.getOrNull(index),
                                canMoveUp = index > 0,
                                canMoveDown = index < uris.lastIndex,
                                onMoveUp = { move(index, -1) },
                                onMoveDown = { move(index, +1) },
                                onDelete = { uris = uris.toMutableList().also { it.removeAt(index) } },
                            )
                            if (index < uris.lastIndex) AppCardDivider()
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        if (uris.size >= MaxStitchImages) {
                            notice("最多 $MaxStitchImages 张", NoticeTone.Warning)
                        } else {
                            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("添加图片") }
                Button(
                    onClick = { startStitch() },
                    enabled = !busy && uris.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("开始拼接") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { runExport(share = false) },
                    enabled = !busy && result != null,
                    modifier = Modifier.weight(1f),
                ) { Text("保存到相册") }
                OutlinedButton(
                    onClick = { runExport(share = true) },
                    enabled = !busy && result != null,
                    modifier = Modifier.weight(1f),
                ) { Text("分享") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 拼图列表的一行（卡内平铺行，无卡底无描边，与笔记 / 流水的行同一口径）。 */
@Composable
private fun StitchRow(
    index: Int,
    thumbnail: Bitmap?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail == null) {
                Text("…", style = MaterialTheme.typography.bodySmall)
            } else {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            "第 ${index + 1} 张",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(HugeIcons.ArrowUp01, contentDescription = "上移")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(HugeIcons.ArrowDown01, contentDescription = "下移")
        }
        IconButton(onClick = onDelete) {
            Icon(HugeIcons.Delete02, contentDescription = "移除")
        }
    }
}
