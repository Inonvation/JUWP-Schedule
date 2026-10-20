package edu.jxslu.schedule.ui.me.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.data.xg.XgForm
import edu.jxslu.schedule.data.xg.XgUrls
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar01
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.ClipboardList
import me.rerere.hugeicons.stroke.LockPassword
import me.rerere.hugeicons.stroke.Repair

/**
 * 我的 → 校园服务（DESIGN §3.3，2026-09-28 自「扩展服务」改名瘦身）。
 *
 * 本页只保留**学校系统**（统一身份认证，账号与内容都在学校服务器上）与校历。
 * 第三方账号 2026-10-20 整体并入「账号与登录」：这里不再维护第二份登录态与入口，
 * 否则同一个账号在两处各有一套「已登录 / 点击登录」文案，迟早对不上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionServicesHubScreen(
    onBack: () -> Unit,
    /** 账号与登录（2026-10-20）：第三方账号的状态与补登入口统一在那一页。 */
    onOpenAccounts: () -> Unit,
    /** 校历（DESIGN §4.34）：免登录，图来自仓库镜像 + 内置兜底。 */
    onOpenCampusCalendar: () -> Unit,
    onOpenXgForm: (XgForm) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("校园服务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(
                title = "学校系统",
                subtitle = "统一身份认证登录 · 账号与内容都在学校服务器",
            ) {
                // 清单来自 XgUrls.FORMS：加一个新表单只需在那边加一条，入口自动出现
                XgUrls.FORMS.forEach { form ->
                    SettingItem(
                        title = form.title,
                        subtitle = form.subtitle,
                        icon = form.icon,
                        onClick = { onOpenXgForm(form) },
                    )
                }
                // 校历（DESIGN §4.34）：本节唯一的**免登录**条目——放在学校系统组而不是
                // 另开一节，是因为它是学校发布物、数据源头在学校，只是获取方式不走统一
                // 认证（镜像图）。节标题的「要登录」口径在此放宽，其余条目不受影响
                SettingItem(
                    title = "校历",
                    subtitle = "学年校历大图 · 每学年自动更新（无需登录）",
                    icon = HugeIcons.Calendar03,
                    onClick = onOpenCampusCalendar,
                )
            }

            SettingsSection(
                title = "账号",
                subtitle = "第三方账号的状态与补登入口",
            ) {
                SettingItem(
                    title = "账号与登录",
                    subtitle = "教务 · 一卡通 · 开水 · 洗澡 · 洗衣 · 骑行 · 志愿",
                    icon = HugeIcons.LockPassword,
                    onClick = onOpenAccounts,
                )
            }
        }
    }
}

/**
 * 学工表单的入口图标。
 *
 * 映射放在 UI 层，不塞进 [XgForm]：data 层不该出现 Compose 的 ImageVector。
 * 每加一个表单在这里补一条，没补的落默认图标。
 */
private val XgForm.icon: ImageVector
    get() = when (id) {
        XgUrls.REPAIR.id -> HugeIcons.Repair
        XgUrls.LEAVE.id -> HugeIcons.Calendar01
        else -> HugeIcons.ClipboardList
    }
