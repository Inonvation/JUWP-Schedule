package edu.jxslu.schedule.ui.jwvw

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import edu.jxslu.schedule.JwImportActivity
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ExternalLink

/**
 * 业务页顶栏的「在教务网页核对」动作（2026-10-20）。
 *
 * 统一走 [JwImportActivity.startBrowse]：网页窗口与课表导入共用同一套能力
 * （电脑版 / 手机版、统一认证、返回先关弹层、错误态），不再另起一套 WebView。
 */
@Composable
fun JwWebActionButton(title: String, url: String) {
    val context = LocalContext.current
    IconButton(onClick = { JwImportActivity.startBrowse(context, title, url) }) {
        Icon(HugeIcons.ExternalLink, contentDescription = "在教务网页核对$title")
    }
}
