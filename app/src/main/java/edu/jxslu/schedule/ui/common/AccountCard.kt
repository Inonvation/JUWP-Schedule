package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.R
import edu.jxslu.schedule.data.session.LoginState
import edu.jxslu.schedule.domain.AccountMask
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff

/**
 * 账户卡（DESIGN §3.3）：校徽 + 姓名 + 学号（可遮罩）+ 副行 + 一行登录状态。
 *
 * **两处共用**：教务账户页（`JwAccountScreen`）与一卡通设置页（`CampusCardSettingsScreen`）。
 * 原先各画一套（一处用 `AppCard`、一处自己拼 `SettingsCard`），同样一句「我是谁」
 * 在两个页面长得不一样。
 *
 * 状态文案由调用方给——两处语义本就不同（教务会说「会话过期时自动重新登录」，
 * 一卡通每次都用保存的凭证重登、没有这回事），这里只按 [statusState] 取颜色。
 */
@Composable
fun AccountCard(
    username: String,
    name: String,
    statusText: String,
    statusState: LoginState,
    modifier: Modifier = Modifier,
    /** 副行（班级等）。空则不占位。 */
    subtitle: String = "",
) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    val masked = AccountMask.maskStudentId(username).orEmpty()
    // 姓名与学号都没有 = 拿不到身份：标题按状态给词，而不是留一片空白。
    // **不能一律写「未登录」**：只有网页会话（引导跳过教务、后来在导入页手登）时学号是空的，
    // 那时写「未登录」会与卡上「已登录」那行自相矛盾（2026-09-27 用户报）。
    val title = name.ifBlank { masked.ifBlank { statusState.stateWord() } }
    val idText = if (revealed) username else masked
    val hasName = name.isNotBlank()

    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_school_emblem),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // fill = false：姓名短就贴着自己宽度，学号紧跟着；姓名长才让位给省略号
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (hasName && idText.isNotBlank()) {
                        Text(
                            text = idText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            maxLines = 1,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 没有学号时不显示眼睛：遮罩一个空串没有意义
            if (username.isNotBlank()) {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) HugeIcons.ViewOff else HugeIcons.View,
                        contentDescription = if (revealed) "隐藏学号" else "显示学号",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = statusState.tint(),
        )
    }
}

/**
 * 登录态 → 颜色。已登录用主色、失效用 error、未登录用灰。
 *
 * `internal` 而非 private：「我的」页账号卡的三格服务入口（`SettingsScreen`）用同一份口径，
 * 两处各写一个 when 迟早会对不上。
 */
@Composable
internal fun LoginState.tint(): Color = when (this) {
    LoginState.LoggedIn -> MaterialTheme.colorScheme.primary
    LoginState.Expired -> MaterialTheme.colorScheme.error
    LoginState.NotLoggedIn -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
}

/**
 * 登录态 → 状态词。用于空间只够放名词的位置（「我的」页账号卡三格）；
 * 整句状态文案（含「点此更新」这类动作提示）仍由调用方给全文。
 */
internal fun LoginState.stateWord(): String = when (this) {
    LoginState.LoggedIn -> "已登录"
    LoginState.Expired -> "已失效"
    LoginState.NotLoggedIn -> "未登录"
}
