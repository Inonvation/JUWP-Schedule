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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.EbikeAutoReturn
import edu.jxslu.schedule.domain.EbikeFreeRide
import edu.jxslu.schedule.domain.capabilities
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.AppPermissions
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingChoiceRow
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsNote
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.rememberSheetDismisser
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.Notification02
import me.rerere.hugeicons.stroke.Settings01
import me.rerere.hugeicons.stroke.UserAccount

/**
 * 「快趣出行设置」弹层（DESIGN §3.9）：**快趣相关设置的唯一入口**。
 *
 * 快趣出行页顶栏 ⚙ 打开这一份，两档共用，按主题分组：账号 / 开锁与还车 / 自动还车 /
 * 乘车码 / 提醒 / 地图缓存。
 * 使用方式不在这里：它搬到骑行页标题栏那枚常驻 chip（`RideModeChip`）。
 *
 * 顶部副行只报「使用方式 + 登录态」；分组沿革、已删入口与各条红线见
 * `.agents/rules/ebike.md`。
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
                    if (prefs.useMode.isAccount) append(if (loggedIn) " · 已登录" else " · 未登录")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )

            // 使用方式不在这里：见骑行页标题栏那枚常驻 chip（`RideModeChip` + `RideModeSheet`）。

            if (caps.inAppRide) {
                // 先让弹层滑走再进账号页，中间不留"弹层突然没了"的那一帧
                KvcxAccountRow(loggedIn = loggedIn, onClick = { dismiss { onOpenKvcxAccount() } })
                RideConfirmSettingsSection(prefs = prefs)
                AutoReturnSettingsSection(
                    prefs = prefs,
                    onAutoReturnChanged = viewModel::onAutoReturnChanged,
                    withNotificationPermission = withNotificationPermission,
                )
            }

            CodeSettingsSection(prefs = prefs)

            ReminderSettingsSection(
                prefs = prefs,
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
                // 过渡落在弹窗窗口自己的组合里（内容槽第一行）；放 AlertDialog 外面取到的是
                // 页面窗口、挂不上本应用样式（等于没调用，见 ui-common.md）
                DialogWindowFadeEffect()
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
 * 「开锁与还车」区：只放开锁那一项——四道闸里它是唯一"用户对自己账号"的授权，
 * 且开锁之后 App 侧一路有进度与结果提示；还车与重试开锁保留每次确认。
 *
 * 开锁弹窗里那个「不再提醒」勾选落的就是这一项（`ebike_unlock_confirm`），
 * 两个入口写同一份偏好，别在弹窗里另存一份状态。
 */
@Composable
private fun RideConfirmSettingsSection(prefs: EbikePrefsSnapshot) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var unlockConfirm by remember(prefs.unlockConfirm) { mutableStateOf(prefs.unlockConfirm) }
    SettingsSection(title = "开锁与还车") {
        SettingSwitchRow(
            title = "开启前确认",
            subtitle = "开启前弹确认框，写明计费与车号；关掉后点「开启」直接创建订单（车辆保持锁定，解锁在骑行卡上点）",
            checked = unlockConfirm,
            onCheckedChange = { checked ->
                unlockConfirm = checked
                scope.launch { Graph.displayPrefs(context).setEbikeUnlockConfirm(checked) }
            },
        )
        SettingsNote("还车与重试开锁始终要确认；扫车身码自动开锁遵循此开关。")
    }
}

/**
 * 「自动还车」区：账号登录方式下，用车满 15 分钟前自动发起还车。
 *
 * 触发点必须落在免费时段内（提前量下限 10 秒就是这条口径的保证），失败每次通知并按
 * 「重试次数」自动重试；时刻 / 重试 / 文案口径见 `.agents/rules/ebike.md`。
 *
 * 只在 `caps.inAppRide`（账号方式）里出现：小程序方式没有 App 内还车这回事。
 */
@Composable
private fun AutoReturnSettingsSection(
    prefs: EbikePrefsSnapshot,
    onAutoReturnChanged: () -> Unit,
    withNotificationPermission: (() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember(prefs.autoReturnEnabled) { mutableStateOf(prefs.autoReturnEnabled) }
    var leadSeconds by remember(prefs.autoReturnLeadSeconds) {
        mutableStateOf(prefs.autoReturnLeadSeconds)
    }
    SettingsSection(title = "自动还车") {
        SettingSwitchRow(
            title = "用车满 ${EbikeFreeRide.FREE_MINUTES} 分钟自动还车",
            subtitle = "免费时段内自动还车，需已登录快趣账号",
            checked = enabled,
            onCheckedChange = { checked ->
                enabled = checked
                scope.launch {
                    Graph.displayPrefs(context).setEbikeAutoReturnEnabled(checked)
                    onAutoReturnChanged()
                }
                // 结果要发通知：开启时顺手要一次通知权限（关闭是撤能力，不要权限）
                if (checked) withNotificationPermission { onAutoReturnChanged() }
            },
        )
        if (enabled) {
            AutoReturnLeadSlider(
                value = leadSeconds,
                onValueChange = { leadSeconds = it },
                onValueChangeFinished = {
                    scope.launch {
                        Graph.displayPrefs(context).setEbikeAutoReturnLeadSeconds(leadSeconds)
                        onAutoReturnChanged()
                    }
                },
            )
            SettingChoiceRow(
                title = "失败重试次数",
                subtitle = "失败后自动重试，每次失败都发通知",
                options = (EbikeAutoReturn.RETRY_MIN..EbikeAutoReturn.RETRY_MAX).map { "$it 次" },
                selectedIndex = prefs.autoReturnRetries - EbikeAutoReturn.RETRY_MIN,
                onSelect = { index ->
                    scope.launch {
                        Graph.displayPrefs(context)
                            .setEbikeAutoReturnRetries(index + EbikeAutoReturn.RETRY_MIN)
                        onAutoReturnChanged()
                    }
                },
            )
        }
        SettingsNote("还车判定以车辆上传的位置为准。")
    }
}

/**
 * 自动还车提前量滑块（10~60 秒，步长 5；默认 30）。
 *
 * 拖动中只改本地值，**松手才落盘**（`onValueChangeFinished`）：DataStore 每动一下就写
 * 会让整张设置弹层跟着重组（prefs 是 stateIn 快照），拖起来发飘。
 */
@Composable
private fun AutoReturnLeadSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "触发提前量",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "满 ${EbikeFreeRide.FREE_MINUTES} 分钟前多少秒发起",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = "$value 秒",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(EbikeAutoReturn.coerceLeadSeconds(it.roundToInt())) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = EbikeAutoReturn.LEAD_MIN_SECONDS.toFloat()..
                EbikeAutoReturn.LEAD_MAX_SECONDS.toFloat(),
            steps = (EbikeAutoReturn.LEAD_MAX_SECONDS - EbikeAutoReturn.LEAD_MIN_SECONDS) /
                EbikeAutoReturn.LEAD_STEP_SECONDS - 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 6.dp),
        )
    }
}

/** 「乘车码」区：生成后自动保存、骑完车自动删除。只影响生成、保存与清除。 */
@Composable
private fun CodeSettingsSection(prefs: EbikePrefsSnapshot) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var autoSaveChecked by remember(prefs.autoSave) { mutableStateOf(prefs.autoSave) }
    var burnChecked by remember(prefs.burnAfterScan) { mutableStateOf(prefs.burnAfterScan) }
    SettingsSection(title = "乘车码") {
        SettingSwitchRow(
            title = "生成后自动保存到相册",
            subtitle = "每次生成乘车码就存进系统相册",
            checked = autoSaveChecked,
            onCheckedChange = { checked ->
                autoSaveChecked = checked
                scope.launch { Graph.displayPrefs(context).setEbikeAutoSave(checked) }
            },
        )
        SettingSwitchRow(
            title = "骑完车自动删除",
            subtitle = "免费时长结束或手动结束后，清除相册里的二维码",
            checked = burnChecked,
            onCheckedChange = { checked ->
                burnChecked = checked
                scope.launch { Graph.displayPrefs(context).setEbikeBurnAfterScan(checked) }
            },
        )
    }
}

/** 「提醒」区：免费时长提醒（含提前量）。计时起点两档不同，写在分区注记里。 */
@Composable
private fun ReminderSettingsSection(
    prefs: EbikePrefsSnapshot,
    onFreeReminderChanged: () -> Unit,
    onReminderPermissionGranted: () -> Unit,
    withNotificationPermission: (() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var freeEnabled by remember(prefs.freeReminderEnabled) {
        mutableStateOf(prefs.freeReminderEnabled)
    }
    SettingsSection(title = "提醒") {
        // 提前量 1~5 分钟（默认 3）；15 分钟这个数由 EbikeFreeRide.FREE_MINUTES 出，别手抄。
        SettingSwitchRow(
            title = "免费时长提醒",
            subtitle = "计时开始后常驻倒计时，免费结束前提醒",
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
            // 横幅弹不弹由系统 / ROM 决定，App 只能把渠道设成 HIGH。设置不对时用户
            // 在这里有一条出路，否则只有"通知了但看不到"的死局。
            SettingItem(
                title = "通知与悬浮",
                subtitle = "没弹出、没震动时进系统通知：允许通知，「免费时长提醒」设为横幅 + 震动",
                icon = HugeIcons.Notification02,
                onClick = { AppPermissions.jumpNotificationSettings(context) },
            )
        }
        SettingsNote(
            "小程序方式从「打开微信扫一扫」后 5 秒开始计时，账号方式从开锁成功起；" +
                "前 ${EbikeFreeRide.FREE_MINUTES} 分钟免费以骑行套餐卡为准。",
        )
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
        subtitle = "离线瓦片与还车点数据；清除后下次打开重新下载，不影响账号与订单",
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
