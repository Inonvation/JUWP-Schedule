package edu.jxslu.schedule.ui.ebike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.EbikeCapabilities
import edu.jxslu.schedule.domain.EbikeFreeRide
import edu.jxslu.schedule.domain.capabilities
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.AppPermissions
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingChoiceRow
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.rememberSheetDismisser
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.Settings01
import me.rerere.hugeicons.stroke.UserAccount

/**
 * 「快趣出行设置」弹层（2026-09-29 界面收敛，DESIGN §3.9）：**快趣相关设置的唯一入口**。
 *
 * 快趣出行页顶栏 ⚙ 打开这一份，两档共用。2026-09-30 按主题分组：
 * **账号 / 出码 / 提醒 / 地图 / 其他**——低频项不再和高频开关挤在同一张卡里。
 * 使用方式不在这里：它搬到快趣出行页标题栏那枚常驻 chip（`RideModeChip`）。
 *
 * 弹层自带「当前配置速览」副行：使用方式 · 登录态 · 提醒开关 · 地图缓存占用，
 * 不展开就能看到现在是什么口径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EbikeSettingsSheet(
    viewModel: EbikeViewModel,
    /** 快趣登录态：只影响「账号」区的展示文案（能力判定不依赖它）。 */
    loggedIn: Boolean,
    onDismiss: () -> Unit,
    /** 「账号」区整行点击：进快趣账号页（登录 / 管理都在那边）。 */
    onOpenKvcxAccount: () -> Unit,
    /** 清缓存等结果提示的出口（走宿主页面的 Snackbar）。 */
    onNotice: (String, NoticeTone) -> Unit,
) {
    val prefs by viewModel.ebikePrefs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 地图缓存占用（进弹层时统计一次；瓦片是浏览地图的时长，弹层开着不会变多少）
    var cacheUsage by remember { mutableStateOf<EbikeMapCache.Usage?>(null) }
    var clearingCache by remember { mutableStateOf(false) }
    var showClearCache by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        cacheUsage = EbikeMapCache.measure(context)
    }
    // 「通知使用权」是否已授予（精确倒计时用）：进弹层读一次，从系统设置回来
    // （ON_RESUME）再读一次——用户刚勾选完回来，未授权的提示行要立刻消失。
    var listenerGranted by remember {
        mutableStateOf(AppPermissions.notificationListenerGranted(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                listenerGranted = AppPermissions.notificationListenerGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val withNotificationPermission = rememberNotificationPermissionGate()
    val caps = prefs.useMode.capabilities(loggedIn = loggedIn, hasRide = false)
    // 关弹层走退场动画：点 X 直接置 false 会把弹层从组合里瞬间抽掉（"啪"地消失），
    // 与车号面板 / 使用方式弹层同一条出口
    val dismiss = rememberSheetDismisser(sheetState, onDismiss)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    HugeIcons.Settings01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "快趣出行设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { dismiss {} }) {
                    Icon(
                        HugeIcons.Cancel01,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                text = buildString {
                    append(if (prefs.useMode.isAccount) "账号登录方式" else "微信小程序方式")
                    if (prefs.useMode.isAccount && loggedIn) append(" · 已登录")
                    append(" · 免费提醒")
                    append(if (prefs.freeReminderEnabled) "开" else "关")
                    cacheUsage?.takeIf { !it.isEmpty }?.let {
                        append(" · 地图缓存 ").append(EbikeMapCache.sizeLabel(it.totalBytes))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )

            // 使用方式（DESIGN §3.9 / §4.32）**不在这里**：2026-09-29 结构重构后它搬到
            // 快趣出行页标题栏那枚常驻 chip（`RideModeChip` + `RideModeSheet`）——一个决定
            // 主动作是什么的开关，不该藏在设置里、更不该在切换后让整页变形。

            if (caps.inAppRide) {
                // 先让弹层滑走再进账号页，中间不留"弹层突然没了"的那一帧
                KvcxAccountRow(loggedIn = loggedIn, onClick = { dismiss { onOpenKvcxAccount() } })
            }

            CodeSettingsSection(prefs = prefs)

            ReminderSettingsSection(
                prefs = prefs,
                caps = caps,
                listenerGranted = listenerGranted,
                onFreeReminderChanged = viewModel::onFreeReminderChanged,
                onReminderPermissionGranted = viewModel::onReminderPermissionGranted,
                withNotificationPermission = withNotificationPermission,
            )

            MapCacheCard(
                usage = cacheUsage,
                onClear = {
                    if (cacheUsage?.isEmpty == true) {
                        onNotice("暂无缓存可清除", NoticeTone.Info)
                    } else {
                        showClearCache = true
                    }
                },
            )

            OpenKvcooButton(onClick = { openKvcoo(context) { message -> onNotice(message, NoticeTone.Warning) } })
        }
    }

    // 清除地图缓存：二次确认后才动。文案把"下次会重新下载"与"不影响账号/订单/车辆查询"
    // 都交代清楚——清缓存最容易被误解成"数据没了"（实现与红线见 .agents/rules/ebike.md）
    if (showClearCache) {
        val usage = cacheUsage
        AlertDialog(
            onDismissRequest = { if (!clearingCache) showClearCache = false },
            title = { Text("清除地图缓存？") },
            text = {
                Text(
                    buildString {
                        append("将删除离线地图瓦片")
                        usage?.let { append("（${EbikeMapCache.sizeLabel(it.tileBytes)}）") }
                        append("与停车点 / 禁停区数据")
                        usage?.let { append("（${EbikeMapCache.sizeLabel(it.zoneBytes)}）") }
                        append("。下次打开「附近单车地图」会重新下载；不影响账号、订单与车辆查询。")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearingCache = true
                        scope.launch {
                            val ok = EbikeMapCache.clear(context)
                            cacheUsage = EbikeMapCache.measure(context)
                            clearingCache = false
                            showClearCache = false
                            onNotice(
                                if (ok) "已清除地图缓存" else "缓存没清干净，请稍后重试",
                                if (ok) NoticeTone.Success else NoticeTone.Warning,
                            )
                        }
                    },
                    enabled = !clearingCache,
                ) {
                    Text(
                        text = if (clearingCache) "清除中…" else "清除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearCache = false },
                    enabled = !clearingCache,
                ) { Text("取消") }
            },
        )
    }
}

/**
 * 快趣账号行（DESIGN §4.32）：登录 / 骑行状态 / 退出的入口。
 * 未登录时副标题写清登录后能干什么——本机开锁能力的发现入口就在这一行。
 */
@Composable
private fun KvcxAccountRow(loggedIn: Boolean, onClick: () -> Unit) {
    AppCardRow(
        onClick = onClick,
        onClickLabel = if (loggedIn) "打开快趣账号" else "登录快趣",
    ) {
        Icon(
            HugeIcons.UserAccount,
            contentDescription = null,
            tint = if (loggedIn) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "快趣账号",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (loggedIn) {
                    "已登录 · 账号与本机骑行记录"
                } else {
                    "未登录 · 登录后可本机开锁与还车"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Text(
            text = if (loggedIn) "管理" else "去登录",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            HugeIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 「乘车码」区：生成后自动保存、骑完车自动删除。
 *
 * 从 2026-09-30 起设置弹层按主题分组（账号 / 乘车码 / 提醒 / 地图 / 其他），
 * 低频项不再和高频开关挤在同一张卡里。
 */
@Composable
private fun CodeSettingsSection(prefs: EbikePrefsSnapshot) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var autoSaveChecked by remember(prefs.autoSave) { mutableStateOf(prefs.autoSave) }
    var burnChecked by remember(prefs.burnAfterScan) { mutableStateOf(prefs.burnAfterScan) }
    SettingsSection(
        title = "乘车码",
        subtitle = "只影响生成、保存与清除，与登录状态无关。",
    ) {
        SettingSwitchRow(
            title = "生成后自动保存到相册",
            subtitle = "开启后每次生成乘车码即存入相册「水贝贝」",
            checked = autoSaveChecked,
            onCheckedChange = { checked ->
                autoSaveChecked = checked
                scope.launch { Graph.displayPrefs(context).setEbikeAutoSave(checked) }
            },
        )
        SettingSwitchRow(
            title = "骑完车自动删除",
            subtitle = "免费时长结束或手动结束骑行后，自动清除相册里的二维码",
            checked = burnChecked,
            onCheckedChange = { checked ->
                burnChecked = checked
                scope.launch { Graph.displayPrefs(context).setEbikeBurnAfterScan(checked) }
            },
        )
    }
}

/**
 * 「提醒」区：免费时长提醒（含提前量）与精确倒计时。
 *
 * 两档只有两处不同，都由参数决定：**免费时长提醒的副标题**（计时起点是「点扫一扫」
 * 还是「开锁成功」）与**精确倒计时是否出现**（`caps.wechatNoticeCalibration`）。
 */
@Composable
private fun ReminderSettingsSection(
    prefs: EbikePrefsSnapshot,
    caps: EbikeCapabilities,
    listenerGranted: Boolean,
    onFreeReminderChanged: () -> Unit,
    onReminderPermissionGranted: () -> Unit,
    withNotificationPermission: (() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    var freeEnabled by remember(prefs.freeReminderEnabled) {
        mutableStateOf(prefs.freeReminderEnabled)
    }
    SettingsSection(
        title = "提醒",
        subtitle = "通知栏常驻倒计时 + 两个精确闹钟，不写系统日历。",
    ) {
        // 免费时长提醒（DESIGN §3.9）：提前量 1~5 分钟（用户拍板默认 3）。
        SettingSwitchRow(
            title = "免费时长提醒",
            // 计时起点两档不同：小程序方式是「点打开微信扫一扫」这个准备动作，
            // 账号方式是服务端确认的开锁时刻
            subtitle = if (caps.wechatScan) {
                "点「打开微信扫一扫」后起常驻倒计时，到点发通知提醒"
            } else {
                "开锁成功后起常驻倒计时，到点发通知提醒"
            },
            checked = freeEnabled,
            onCheckedChange = { checked ->
                freeEnabled = checked
                scope.launch {
                    Graph.displayPrefs(context).setEbikeFreeReminderEnabled(checked)
                    onFreeReminderChanged()
                }
                // 只有开启才要权限：关闭是「撤掉提醒」，不需要任何权限
                if (checked) {
                    withNotificationPermission { onReminderPermissionGranted() }
                }
            },
        )
        if (freeEnabled) {
            SettingChoiceRow(
                title = "提前量",
                subtitle = "免费结束前几分钟提醒；结束那一刻再提醒一次",
                options = listOf("1 分钟", "2 分钟", "3 分钟", "4 分钟", "5 分钟"),
                selectedIndex = prefs.freeLeadMinutes - EbikeFreeRide.LEAD_MIN,
                onSelect = { index ->
                    scope.launch {
                        Graph.displayPrefs(context)
                            .setEbikeFreeLeadMinutes(index + EbikeFreeRide.LEAD_MIN)
                        onFreeReminderChanged()
                    }
                },
            )

            // 「精确倒计时」（DESIGN §3.9）：默认关，开启后要「通知使用权」。
            // 计时起点原本只能取「点打开微信扫一扫」的时刻，开了它就能拿微信的租车成功
            // 通知把起点校准到真正开始计费那一刻；兼听完成通知，还车后自动结束计时。
            // **只在微信小程序方式下出现**：账号方式的开锁时刻本来就是服务端确认的，
            // 留着只会让用户去开一个用不上的高危权限（服务端侧也按能力拦了一道）
            if (caps.wechatNoticeCalibration) {
                var preciseEnabled by remember(prefs.preciseCountdownEnabled) {
                    mutableStateOf(prefs.preciseCountdownEnabled)
                }
                SettingSwitchRow(
                    title = "精确倒计时",
                    subtitle = "识别微信的先享后付通知：租车成功校准计时起点，还车后自动结束计时",
                    checked = preciseEnabled,
                    onCheckedChange = { checked ->
                        preciseEnabled = checked
                        scope.launch {
                            Graph.displayPrefs(context).setEbikePreciseCountdownEnabled(checked)
                        }
                        // 只有开启才要授权：关闭是「不再校准」。通知使用权只能由用户去
                        // 系统设置里勾选，没有弹框可申请
                        if (checked && !AppPermissions.notificationListenerGranted(context)) {
                            AppPermissions.jumpNotificationListenerSettings(context)
                        }
                    },
                )
                // 开关开着但还没授权：功能不会生效，得让用户看得见（并且点得到设置页）
                if (preciseEnabled && !listenerGranted) {
                    InlineNoticeRow(
                        message = "还没授予通知使用权，精确倒计时不会生效",
                        tone = NoticeTone.Warning,
                    )
                    TextButton(onClick = {
                        haptics.tap()
                        AppPermissions.jumpNotificationListenerSettings(context)
                    }) {
                        Text("去开启通知使用权")
                    }
                }
            }
        }
    }
}

/**
 * 「地图缓存」卡：地图是两档共用的能力，缓存管理两档都要有
 * （小程序方式的人也看得到地图，也得清得掉那 60 MB 瓦片）。
 */
@Composable
private fun MapCacheCard(usage: EbikeMapCache.Usage?, onClear: () -> Unit) {
    SettingsSection(
        title = "地图缓存",
        subtitle = "「附近单车地图」的离线瓦片与还车点 / 禁停区数据缓存；清除后下次打开重新下载，不影响车辆查询。",
    ) {
        SettingItem(
            title = when {
                usage == null -> "正在统计…"
                usage.isEmpty -> "暂无缓存"
                else -> "当前占用 ${EbikeMapCache.sizeLabel(usage.totalBytes)}"
            },
            subtitle = if (usage != null && !usage.isEmpty) {
                "离线瓦片 ${EbikeMapCache.sizeLabel(usage.tileBytes)} · " +
                    "停车点数据 ${EbikeMapCache.sizeLabel(usage.zoneBytes)}；点此清除"
            } else {
                "浏览地图时自动缓存，超过 60 MB 自动回收旧瓦片"
            },
            onClick = onClear,
        )
    }
}

/**
 * 「打开官方快趣出行」文字入口：内置地图已经能看车在哪，官方 App 不再是必经步骤，
 * 留一个入口给习惯用它的人。低频，所以收进设置弹层。
 *
 * 标题带「官方」（2026-10-01）：本 App 里那个页面现在也叫快趣出行，不带限定词会出现
 * 两行同名、一个开本页一个开别的 App。
 */
@Composable
private fun OpenKvcooButton(onClick: () -> Unit) {
    SettingsSection(
        title = "其他",
        subtitle = null,
    ) {
        SettingItem(
            title = "打开官方快趣出行 App",
            subtitle = "已安装则直达官方 App 启动页（与本页不是同一个东西）",
            onClick = onClick,
        )
    }
}
