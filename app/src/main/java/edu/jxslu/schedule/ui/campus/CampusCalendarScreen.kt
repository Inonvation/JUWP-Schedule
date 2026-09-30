package edu.jxslu.schedule.ui.campus

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.R
import edu.jxslu.schedule.data.repo.CampusCalendarStore
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.ImageSource
import edu.jxslu.schedule.ui.common.ImageViewerDialog
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Refresh

/**
 * 我的 → 校园服务 → 校历（DESIGN §4.34）。
 *
 * 整页就是一张校历图：**进页即整图**（满宽、按真实比例、可点开全屏手势缩放），
 * 没有任何要登录的东西。图源三层——已下载的镜像图 → 内置兜底图 → 骨架占位；
 * 刷新 = 顶栏按钮（force 绕闸门），平时冷启动静默刷（7 天闸门）。
 *
 * 点图开 [ImageViewerDialog]：全屏黑底 + 双指缩放/拖动，与笔记附件看大图同一组件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusCalendarScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { Graph.campusCalendar(context) }
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    var refreshing by remember { mutableStateOf(false) }
    var viewerOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("校历") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!refreshing) {
                                scope.launch {
                                    refreshing = true
                                    val result = store.refresh(force = true)
                                    refreshing = false
                                    if (result == CampusCalendarStore.RefreshResult.Failed) {
                                        snackbar.showSnackbar("刷新失败，请检查网络后重试")
                                    }
                                }
                            }
                        },
                    ) {
                        Icon(HugeIcons.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(state = snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 图源：已下载镜像图优先，没有走内置兜底；两者都是本地解码，进页不卡网络
            val localBitmap by produceState<ImageBitmap?>(
                initialValue = null,
                key1 = state.localFile,
            ) {
                value = withContext(Dispatchers.IO) {
                    store.fileFor(state.localFile)
                        ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                        ?.asImageBitmap()
                }
            }
            val mirror = localBitmap
            when {
                mirror != null -> CalendarImage(
                    bitmap = mirror,
                    year = state.year,
                    onClick = { viewerOpen = true },
                )

                else -> BuiltinFallbackImage(
                    onClick = { viewerOpen = true },
                )
            }

            // 年次与来源说明：镜像没下到时只说内置图的归属学年，不编造
            Text(
                text = if (state.year.isNotBlank()) "$state.year 学年 · 图片来自仓库镜像，每学年自动更新"
                else "图片为内置版本，联网后自动更新到最新学年",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            // 边界说明只留一行（与快趣账号页同款口径）
            InlineNoticeRow(
                message = "校历内容以学校官方发布为准（非官方整理，可能滞后）。",
                tone = NoticeTone.Info,
            )
        }
    }

    if (viewerOpen) {
        // 全屏看图（手势缩放）。镜像图没下载到（内置兜底状态）也照开——
        // ImageSource.CampusCalendar 分支解不到文件时回退打包资源图，点击放大永远可用
        ImageViewerDialog(
            fileName = state.localFile ?: BUILTIN_IMAGE_KEY,
            source = ImageSource.CampusCalendar,
            onDismiss = { viewerOpen = false },
        )
    }
}

/** 已下载的镜像图：满宽按真实比例，圆角卡形态，点开全屏。 */
@Composable
private fun CalendarImage(bitmap: ImageBitmap, year: String, onClick: () -> Unit) {
    val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
    Image(
        bitmap = bitmap,
        contentDescription = if (year.isNotBlank()) "$year 学年校历" else "校历",
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio.coerceIn(0.4f, 3f))
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    )
}

/** 内置兜底图（res/drawable-nodpi/ic_campus_calendar.jpg，2026-2027 学年）。 */
@Composable
private fun BuiltinFallbackImage(onClick: () -> Unit) {
    // 打包资源是固定图，比例写死 1375×1020（与 res 里那张一致；改图要同步这行）
    Image(
        painter = painterResource(R.drawable.ic_campus_calendar),
        contentDescription = "2026-2027 学年校历",
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1375f / 1020f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    )
}

/** 内置兜底态传给看图弹窗的占位文件名（ CampusCalendar 分支解不到文件即回退资源图）。 */
private const val BUILTIN_IMAGE_KEY = "builtin"
