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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.xg.XgForm
import edu.jxslu.schedule.data.xg.XgUrls
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar01
import me.rerere.hugeicons.stroke.ClipboardList
import me.rerere.hugeicons.stroke.CreditCard
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.Repair
import me.rerere.hugeicons.stroke.ShowerHead

/**
 * 我的 → 校园服务（DESIGN §3.3，2026-09-28 自「扩展服务」改名瘦身）。
 *
 * 本页只剩两类**要登录的系统**：学校系统（统一身份认证，账号与内容都在学校服务器上）
 * 与第三方账号（凭证在第三方手里）。节标题即数据去向声明——分开列，用户才知道
 * 哪个能放心填（2026-09-23 改版定下的原则，原样保留）。
 *
 * 本轮迁出的东西：「今日页」节（快捷方式入口 + 快趣出行码开关）整体迁往
 * 通用设置的「功能开关」节，本页不再管「功能是否出现」，只管登录与服务本身。
 *
 * 三方账号行右侧带登录态（实时订阅仓库流）。胖乖 / 趣智被功能开关关掉后，
 * 本页入口**仍然显示**：这里是重新打开服务的入口，否则关掉后唯一入口在
 * 通用设置会造成死路。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionServicesHubScreen(
    onBack: () -> Unit,
    onOpenCampusCard: () -> Unit,
    onOpenWater: () -> Unit,
    /** 趣智校园开热水（DESIGN §4.30）。登录 / 余额 / 账单都在这一页。 */
    onOpenQzxy: () -> Unit,
    onOpenXgForm: (XgForm) -> Unit,
) {
    val context = LocalContext.current
    // 三方登录态订阅仓库的流（2026-09-27 口径）：登录 / 退出在任何窗口发生都即时生效
    val waterLoggedIn by remember { Graph.qiekj(context).loggedIn }
        .collectAsStateWithLifecycle()
    val qzxyLoggedIn by remember { Graph.qzxy(context).loggedIn }
        .collectAsStateWithLifecycle()

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
            }

            SettingsSection(
                title = "第三方账号",
                subtitle = "凭证存于第三方 · 非学校官方功能",
            ) {
                SettingItem(
                    title = "水宝宝一卡通",
                    subtitle = "余额 · 付款码 · 流水 · 充值",
                    icon = HugeIcons.CreditCard,
                    onClick = onOpenCampusCard,
                )
                SettingItem(
                    title = "胖乖生活",
                    subtitle = "开水 · 余额 · 订单",
                    icon = HugeIcons.Droplet,
                    value = if (waterLoggedIn) "已登录" else "点击登录",
                    onClick = onOpenWater,
                )
                SettingItem(
                    title = "趣智校园",
                    subtitle = "开热水 · 余额 · 账单",
                    // 花洒：这是「洗澡开热水」，不是喝水（与账户卡那一格同图标）
                    icon = HugeIcons.ShowerHead,
                    value = if (qzxyLoggedIn) "已登录" else "点击登录",
                    onClick = onOpenQzxy,
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
