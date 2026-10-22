package edu.jxslu.schedule.ui.jw

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.PageRefreshAction
import edu.jxslu.schedule.ui.common.PageScaffoldColumn
import edu.jxslu.schedule.ui.common.SettingsNote
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.jwvw.JwWebActionButton
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * 教务 → 学籍信息（2026-10-20）。
 *
 * 数据来自 `/jsxsd/grxx/xsxx` 的学籍卡片，登录后由 [ProfileSync] 补抓并落 DataStore。
 * 这里只读展示；顶栏外链图标打开教务原始页面核对。
 */
@Composable
fun StudentInfoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { Graph.displayPrefs(context) }
    val repo = remember(context) { Graph.repository(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val name by prefs.profileName.collectAsStateWithLifecycle(initialValue = "")
    val studentId by prefs.profileStudentId.collectAsStateWithLifecycle(initialValue = "")
    val className by prefs.profileClass.collectAsStateWithLifecycle(initialValue = "")
    val college by prefs.profileCollege.collectAsStateWithLifecycle(initialValue = "")
    val major by prefs.profileMajor.collectAsStateWithLifecycle(initialValue = "")
    val duration by prefs.profileDuration.collectAsStateWithLifecycle(initialValue = "")
    val semester by repo.semester.collectAsStateWithLifecycle(initialValue = null)

    var refreshing by remember { mutableStateOf(false) }
    val currentWeek = semester?.let {
        ScheduleCalculator.weekNumberOf(it, LocalDate.now()).takeIf { week ->
            ScheduleCalculator.isWeekInTerm(it, week)
        }
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            val ok = runCatching {
                Graph.profileSync(context).syncOnce(force = true)
            }.getOrDefault(false)
            refreshing = false
            if (!ok) {
                snackbar.showNotice("没抓到学籍信息，请先在教务导入里登录一次")
            }
        }
    }

    // 旧用户升级后新增字段为空：进页补一次；ProfileSync 自己带「今天最多试一次」的闸门。
    LaunchedEffect(Unit) {
        runCatching { Graph.profileSync(context).syncOnce() }
    }

    PageScaffoldColumn(
        title = "学籍信息",
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            JwWebActionButton("学籍信息", JwUrls.STUDENT_CARD)
            PageRefreshAction(busy = refreshing, onClick = { refresh() }, label = "更新学籍信息")
        },
    ) {
        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = name.ifBlank { "尚未同步" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = studentId.ifBlank { "登录教务后自动补全" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    )
                }
            }
            if (className.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = className,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
            }
        }

        SettingsSection(title = "学籍信息", subtitle = "来自教务学籍卡片") {
            InfoRow("院系", college.ifBlank { "—" })
            InfoRow("专业", major.ifBlank { "—" })
            InfoRow("班级", className.ifBlank { "—" })
            InfoRow("学制", duration.ifBlank { "—" })
        }

        SettingsSection(title = "当前学期", subtitle = "来自本机课表配置") {
            InfoRow("当前学期", semester?.startDate?.let { "$it 起" } ?: "未配置")
            InfoRow(
                "教学周",
                currentWeek?.let { "第 $it 周" }
                    ?: if (semester == null) "未配置" else "不在学期内",
            )
        }

        SettingsNote("学籍信息由教务学籍卡片补抓；密码或会话失效时不会更新。")
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
