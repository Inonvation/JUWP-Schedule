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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.xg.XgForm
import edu.jxslu.schedule.data.xg.XgUrls
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.me.MeViewModel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar01
import me.rerere.hugeicons.stroke.ClipboardList
import me.rerere.hugeicons.stroke.CreditCard
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.Flash
import me.rerere.hugeicons.stroke.Repair
import me.rerere.hugeicons.stroke.ScooterElectric

/**
 * 我的 → 扩展服务（DESIGN §3.3）：学校系统 + 第三方服务，开关原样。
 *
 * 分成两张卡而不是一张，是因为两者的数据去向不同：前者的账号与内容都在学校服务器上
 * （走统一身份认证），后者的凭证在第三方手里。混在一起用户没法判断哪个能放心填。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionServicesHubScreen(
    onBack: () -> Unit,
    onOpenShortcuts: () -> Unit,
    onOpenCampusCard: () -> Unit,
    onOpenWater: () -> Unit,
    onOpenXgForm: (XgForm) -> Unit,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 胖乖登录态：进页与从开水页返回（ON_RESUME）时各读一次——子窗口返回不触发重组，
    // 只靠组合期读一次会在「登录后返回」时仍显示旧文案。
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var waterLoggedIn by remember { mutableStateOf(Graph.qiekj(context).localToken() != null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                waterLoggedIn = Graph.qiekj(context).localToken() != null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扩展服务") },
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
                subtitle = "江西水利电力大学 · 统一身份认证登录",
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
                title = "今日页",
                subtitle = "第三方服务 · 非学校官方功能",
            ) {
                SettingItem(
                    title = "快捷方式",
                    subtitle = "今日页快捷入口 · 添加与编辑",
                    icon = HugeIcons.Flash,
                    onClick = onOpenShortcuts,
                )
                SettingSwitchRow(
                    title = "快趣出行码",
                    subtitle = "今日页骑行二维码入口 · 非学校官方功能",
                    checked = state.displayPrefs.ebikeCardEnabled,
                    onCheckedChange = viewModel::setEbikeCardEnabled,
                    icon = HugeIcons.ScooterElectric,
                )
            }

            SettingsSection(title = "校园服务") {
                SettingItem(
                    title = "水宝宝一卡通",
                    subtitle = "攻破水宝宝，一键启动！",
                    icon = HugeIcons.CreditCard,
                    onClick = onOpenCampusCard,
                )
                SettingItem(
                    title = "胖乖生活一键开水",
                    subtitle = if (waterLoggedIn) "开水 / 余额 / 订单" else "点击登录胖乖生活",
                    icon = HugeIcons.Droplet,
                    onClick = onOpenWater,
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
