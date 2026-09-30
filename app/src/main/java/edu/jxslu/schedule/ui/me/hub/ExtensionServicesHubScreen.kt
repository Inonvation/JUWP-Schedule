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
import edu.jxslu.schedule.domain.capabilities
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar01
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.ClipboardList
import me.rerere.hugeicons.stroke.CreditCard
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.Repair
import me.rerere.hugeicons.stroke.ScooterElectric
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
    /** 快趣出行主页面（DESIGN §3.9）：两档共用的地图 / 生成乘车码 / 免费时长计时。 */
    onOpenRide: () -> Unit,
    /** 快趣账号页（DESIGN §4.32）：登录与本机骑行记录，A 档不碰开车还车。 */
    onOpenKvcx: () -> Unit,
    /** 校历（DESIGN §4.34）：免登录，图来自仓库镜像 + 内置兜底。 */
    onOpenCampusCalendar: () -> Unit,
    onOpenXgForm: (XgForm) -> Unit,
) {
    val context = LocalContext.current
    // 三方登录态订阅仓库的流（2026-09-27 口径）：登录 / 退出在任何窗口发生都即时生效
    val waterLoggedIn by remember { Graph.qiekj(context).loggedIn }
        .collectAsStateWithLifecycle()
    val qzxyLoggedIn by remember { Graph.qzxy(context).loggedIn }
        .collectAsStateWithLifecycle()
    val kvcxLoggedIn by remember { Graph.kqcx(context).loggedIn }
        .collectAsStateWithLifecycle()
    // 使用方式（DESIGN §3.9 / §4.32）：**快趣出行账号入口只属于账号登录方式**——
    // 小程序方式不碰快趣账号，入口留着就是它用不到的功能（切回去入口自然回来）。
    // 初值阻塞读一次（同 MeViewModel.initialPrefs 模式）：入口行不该先冒出来再消失
    val ebikePrefs = remember(context) { Graph.displayPrefs(context) }
    val ebikeUseMode by ebikePrefs.ebikeUseMode.collectAsStateWithLifecycle(
        initialValue = remember { runBlocking { ebikePrefs.ebikeUseMode.first() } },
    )
    // 能力矩阵（DESIGN §3.9）：账号入口只属于 App 内用车那一档
    val kvcxAccountMode = ebikeUseMode.capabilities().inAppRide

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
                // 快趣出行（2026-10-01 加入）：**主页面**，两种使用方式都该有入口——
                // 它管的是「看车在哪、生成乘车码、免费时长计时」，与登录与否无关。
                // 小程序方式原来在本页只能从今日页卡片进，进「我的」反而没有路。
                SettingItem(
                    title = "快趣出行",
                    subtitle = "附近车辆 · 生成乘车码 · 免费时长计时",
                    // 电单车：与今日页快趣卡同图标（DESIGN §3.9）
                    icon = HugeIcons.ScooterElectric,
                    onClick = onOpenRide,
                )
                // 快趣账号页：只在「账号登录」使用方式下出现（见上）。
                // 小程序方式要改使用方式，去快趣出行页标题栏那枚 chip
                if (kvcxAccountMode) {
                    SettingItem(
                        title = "快趣账号",
                        subtitle = "登录 · 本机骑行记录（开锁 / 还车在快趣出行页）",
                        icon = HugeIcons.ScooterElectric,
                        value = if (kvcxLoggedIn) "已登录" else "点击登录",
                        onClick = onOpenKvcx,
                    )
                }
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
