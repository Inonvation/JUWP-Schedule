package edu.jxslu.schedule.ui.me.hub

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.UpdateCheck
import edu.jxslu.schedule.domain.UpdateManifest
import edu.jxslu.schedule.startActivityOutsideApp
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 打开站外链接（下载页 / 开源仓库）；失败返回提示文案，成功 null。同包内（关于页）共用。 */
internal fun openUrl(context: Context, url: String): String? = try {
    // 二级页窗口拉起外部应用统一走 startActivityOutsideApp：抑制右推入过渡，
    // 不然从浏览器回来时本页会凭空重放一次入场动画（devlog 4.26 的「页面跳动」）
    context.startActivityOutsideApp(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    null
} catch (_: ActivityNotFoundException) {
    "没有可打开链接的应用"
}

/**
 * 「发现新版本」弹窗（DESIGN §3.3）：更新日志可滚（长日志小屏不裁切）+ SHA-256 行
 * （点按复制）+ 下载双通道 + 「忽略此次更新」。镜像链接来自仓库镜像清单里维护的
 * `mirrorPrefix`，前缀缺失/不合法时只出「GitHub 下载」一个出口。下载都是
 * `ACTION_VIEW` 交给浏览器，App 不落 APK。
 *
 * 两处共用：关于页「检查更新」（手动）与 [UpdateAvailableDialogHost]（冷启动自动）。
 * 反馈收在弹窗内（[InlineNoticeRow]）——弹窗是独立窗口，外层 Snackbar 穿不透；
 * 根层宿主也没有 Scaffold 可挂 Snackbar。打开链接失败**不关弹窗**，提示留在原地。
 *
 * @param onIgnore 点「忽略此次更新」：调用方负责落「已忽略的版本号」并关闭弹窗
 *   （自动检查不再弹该版本；手动检查不读它，永远弹）。
 */
@Composable
fun UpdateAvailableDialog(
    manifest: UpdateManifest,
    onDismiss: () -> Unit,
    onIgnore: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val mirrorUrl = UpdateCheck.mirrorDownloadUrl(manifest)
    // 复制反馈：每次点按重新计时（计数翻转触发重新延时），2 秒后自动收
    var copiedAt by remember { mutableStateOf(0) }
    var openFailure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedAt) {
        if (copiedAt > 0) {
            delay(2000)
            copiedAt = 0
        }
    }

    fun openAndClose(url: String) {
        val failure = openUrl(context, url)
        if (failure == null) onDismiss() else openFailure = failure
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 v${manifest.version}") },
        text = {
            // DialogWindowFadeEffect 必须放内容槽第一行才生效（放 AlertDialog 外是死代码）
            DialogWindowFadeEffect()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (manifest.notes.isNotBlank()) {
                    Text(
                        manifest.notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                if (UpdateCheck.hasUsableDigest(manifest)) {
                    Text(
                        "SHA-256：${manifest.sha256}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                clipboard.setText(AnnotatedString(manifest.sha256))
                                copiedAt++
                            }
                            .padding(vertical = 2.dp),
                    )
                    Text(
                        "点按复制 · 镜像下载为第三方通道，可核对此值确认安装包完整",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    )
                }
                if (copiedAt > 0) InlineNoticeRow(message = "已复制 SHA-256", tone = NoticeTone.Success)
                openFailure?.let { InlineNoticeRow(message = it, tone = NoticeTone.Warning) }
            }
        },
        confirmButton = {
            if (mirrorUrl != null) {
                TextButton(onClick = { openAndClose(mirrorUrl) }) { Text("通过国内镜像下载") }
            } else {
                TextButton(onClick = { openAndClose(UpdateCheck.githubDownloadUrl(manifest)) }) {
                    Text("GitHub 下载")
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onIgnore) { Text("忽略此次更新") }
                if (mirrorUrl != null) {
                    TextButton(onClick = { openAndClose(UpdateCheck.githubDownloadUrl(manifest)) }) {
                        Text("GitHub 下载")
                    }
                }
            }
        },
    )
}

/**
 * 启动自动检查的弹窗宿主：collect [UpdateAvailableBus]，非空就弹。
 * 挂在 MainActivity 根层（`CampusPayResultDialogHost` 旁）；用户关掉弹窗
 * （稍后 / 忽略 / 下载）时才消费——转屏重建时弹窗还在。
 *
 * @param enabled 展示闸门——引导未走完（onboardingSeen=false）或「同步完成」
 *   结果弹窗还没消费时先不抢镜（防叠窗），条件满足后自动接着弹。
 */
@Composable
fun UpdateAvailableDialogHost(enabled: Boolean) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val manifest by UpdateAvailableBus.manifest.collectAsStateWithLifecycle()
    val current = manifest
    if (enabled && current != null) {
        UpdateAvailableDialog(
            manifest = current,
            onDismiss = { UpdateAvailableBus.consume() },
            onIgnore = {
                UpdateAvailableBus.consume()
                // 写偏好走 appScope：不随页面组合销毁；与关弹窗同刻落盘
                Graph.appScope.launch {
                    Graph.repository(appContext).setIgnoredUpdateVersion(current.version)
                }
            },
        )
    }
}
