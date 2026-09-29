package edu.jxslu.schedule.ui.ebike

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import edu.jxslu.schedule.startActivityOutsideApp
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppPermissions
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 快趣出行的跨页面动作与提示出口（DESIGN §3.9）。
 *
 * 抽成一份的理由与 `KvcxRideController` 相同：拉起微信、打开官方 App、通知权限门、页内提示
 * 这四件事在骑行页的多个位置都要用，各写一份迟早漂移（提示语气、失败兜底文案都是口径）。
 */

/** 页内一次性提示（Snackbar）的统一出口。 */
internal fun showNotice(
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    message: String,
    tone: NoticeTone = NoticeTone.Warning,
) {
    scope.launch { snackbar.showSnackbar(AppNoticeVisuals(message, tone = tone)) }
}

/**
 * 通知权限的门（API 33+）：有权限直接跑，缺权限先申请、授予后跑（与上课提醒同口径）。
 * 出码动作与设置弹层里的提醒开关共用同一份。
 */
@Composable
internal fun rememberNotificationPermissionGate(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var resumeAfterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        val resume = resumeAfterPermission
        resumeAfterPermission = null
        resume?.invoke()
    }
    return { action: () -> Unit ->
        val needed = AppPermissions.missingNotification(context)
        if (needed.isEmpty()) {
            action()
        } else {
            resumeAfterPermission = action
            launcher.launch(needed.toTypedArray())
        }
    }
}

/**
 * 拉起微信「扫一扫」。入口按可靠性排序：
 * 1. `ShortCutDispatchAction` + `launch_type_scan_qrcode`——微信桌面长按「扫一扫」
 *    快捷方式的真身（`dumpsys shortcut com.tencent.mm` 实测），直达扫一扫相机页；
 * 2. `BIZSHORTCUT` + `LauncherUI.From.Scaner.Shortcut`——旧式快捷入口，部分版本
 *    只落微信首页（真机实测），仅作兜底；
 * 3. 打开微信首页给手动引导——出码本身已成功，这一步只是省一次手动切 App。
 *
 * 三级都走 [startActivityOutsideApp]：微信是 singleTask、永远开不进本 task，
 * 从它返回时本页的右推入过渡会被重放（用户报「界面跳动」），拉起前要换静止过渡。
 */
internal fun openWechatScan(context: Context, onError: (String) -> Unit) {
    val dispatchScan = Intent("com.tencent.mm.ui.ShortCutDispatchAction")
        .setPackage("com.tencent.mm")
        .putExtra("LauncherUI.Shortcut.LaunchType", "launch_type_scan_qrcode")
    try {
        context.startActivityOutsideApp(dispatchScan)
        return
    } catch (_: Exception) {
        // 落到下一级
    }
    val bizShortcut = Intent("com.tencent.mm.action.BIZSHORTCUT")
        .setPackage("com.tencent.mm")
        .addFlags(0x14000000) // NEW_TASK | CLEAR_TOP（沿用微信 shortcut 的 launchFlags）
        .putExtra("LauncherUI.From.Scaner.Shortcut", true)
    try {
        context.startActivityOutsideApp(bizShortcut)
        return
    } catch (_: Exception) {
        // 落到手动引导
    }
    val launch = context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
    if (launch != null) {
        try {
            context.startActivityOutsideApp(launch)
            onError("微信已打开，请在「发现 → 扫一扫」对准二维码")
            return
        } catch (_: Exception) {
            // 落到统一失败文案
        }
    }
    onError("无法自动打开微信，请手动打开「扫一扫」扫码")
}

/** 「快趣出行」App 包名（DESIGN §3.9）。 */
internal const val KVCOO_PACKAGE = "com.kvcoo.go"

/**
 * 打开「快趣出行」App（需已安装）。
 *
 * 只剩桌面启动意图一级（2026-09-23 收敛）：内置地图已经把「看车在哪」接过来，
 * 官方 App 不再是必经步骤。装了则打开（启动页），未装给一句提示。
 * manifest 里保留 `com.kvcoo.go` 的 `queries` 声明仍是必须的，否则包可见性
 * 会让 `getLaunchIntentForPackage` 对已装应用也返回 null。
 */
internal fun openKvcoo(context: Context, onError: (String) -> Unit) {
    val launch = try {
        context.packageManager.getLaunchIntentForPackage(KVCOO_PACKAGE)
    } catch (_: Exception) {
        null
    }
    if (launch == null) {
        onError("未安装快趣出行；可直接用本页地图找车，或输入车号生成乘车码")
        return
    }
    try {
        context.startActivityOutsideApp(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        onError("打开快趣出行失败，请手动打开")
    }
}
