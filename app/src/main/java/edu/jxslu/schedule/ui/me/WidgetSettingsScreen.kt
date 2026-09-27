package edu.jxslu.schedule.ui.me

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.LocalTimeLike
import edu.jxslu.schedule.domain.buildTodayState
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.PermissionRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.courseColor
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.widget.CampusCardWidgetReceiver
import edu.jxslu.schedule.ui.widget.LifeWidgetSync
import edu.jxslu.schedule.ui.widget.PowerWidgetReceiver
import edu.jxslu.schedule.ui.widget.ScheduleWidgetReceiver
import edu.jxslu.schedule.ui.widget.WidgetDay
import edu.jxslu.schedule.ui.widget.WidgetFocus
import edu.jxslu.schedule.ui.widget.WidgetLayout
import edu.jxslu.schedule.ui.widget.WidgetModel
import edu.jxslu.schedule.ui.widget.WidgetSnapshot
import edu.jxslu.schedule.ui.widget.WidgetSnapshotStore
import edu.jxslu.schedule.ui.widget.buildWidgetSnapshot
import edu.jxslu.schedule.ui.widget.buildWidgetWeek
import edu.jxslu.schedule.ui.widget.forSize
import edu.jxslu.schedule.ui.widget.widgetMetricsFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BatteryCharging01
import me.rerere.hugeicons.stroke.Calendar01
import me.rerere.hugeicons.stroke.Energy
import me.rerere.hugeicons.stroke.Wallet03
import java.time.LocalDate

/**
 * 「我的 → 桌面小组件」设置页（DESIGN §3.6）。
 *
 * 2026-09-27 **三条目改版**：可添加条目按内容扩为三条——课表（2026-09-20 单条目自适应，
 * 不变）/ 校园卡 / 电费（各一条 receiver，**不是按尺寸拆**，旧规矩「不要按尺寸拆 receiver」
 * 不变）。页面随之从单条目页重排为「添加区（三条）+ 各条目数据与点击说明 + 公共节」：
 *
 * 1. 添加到桌面：三行添加条目，各自 `requestPinAppWidget`、各自计数徽标；
 * 2. 课表尺寸形态：四张预览（同一份快照按四档裁剪，只作说明用）收进 2×2 网格省纵向空间；
 * 3. 校园卡：口径 / 更新时机 / 点击行为 bullets + 「在小组件中隐藏余额」开关（默认关）；
 * 4. 电费：读数口径 / 「小组件自身不联网」/ 点击行为 bullets；
 * 5. 后台及时性（可选）/ 说明：三条目共用。
 *
 * 交互规则不变（用户已拍板「自动检测 + 逐项申请」）：进页只读检测；**首次进入**自动弹
 * 一次说明弹层（含一键添加课表条目），此后不再自动弹；不主动拉任何系统框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // 能力/权限状态：从系统设置返回（onResume）时重读，保证徽标与真实状态一致
    var caps by remember { mutableStateOf(readCaps(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) caps = readCaps(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 「在小组件中隐藏余额」（校园卡条目）：进页读一次，开关切换走 LifeWidgetSync
    // （内部写完立即重渲染，桌面马上变样）
    var hideBalance by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        hideBalance = runCatching { LifeWidgetSync.campusSnapshot(context).hideBalance }
            .getOrDefault(false)
    }

    // 首次进入的说明弹层（只弹一次，DataStore 标记）
    var showIntro by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prefs = Graph.displayPrefs(context)
        if (!prefs.widgetSetupSeen()) {
            showIntro = true
            prefs.setWidgetSetupSeen()
        }
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 添加失败（桌面不支持应用内添加）的提示出口，走全 App 统一卡片
    val showNotice: (String) -> Unit = { message ->
        scope.launch {
            snackbar.showSnackbar(AppNoticeVisuals(message, tone = NoticeTone.Warning))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("桌面小组件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(
                title = "添加到桌面",
                subtitle = if (caps.canPin) {
                    "点「添加」后在系统确认框里一键放到桌面；三个条目各自独立添加，可以同时上桌面"
                } else {
                    "当前桌面不支持应用内添加，请长按桌面空白处 → 小组件 → 水贝贝"
                },
            ) {
                Spacer(Modifier.height(4.dp))
                entries.forEach { entry ->
                    AddRow(
                        label = entry.label,
                        description = entry.description,
                        icon = entry.icon,
                        iconTint = entry.iconTint,
                        added = entry.countOf(caps),
                        canPin = caps.canPin,
                        onAdd = {
                            onAddClicked(context, entry.receiver)?.let(showNotice)
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            SettingsSection(
                title = "课表尺寸形态",
                subtitle = "拖到以下大致尺寸时的样子；中间尺寸会自动落在最合适的一档",
            ) {
                Spacer(Modifier.height(6.dp))
                Row {
                    FormGridItem(
                        modifier = Modifier.weight(1f),
                        size = WidgetPreviewSize.Small,
                        label = "紧凑",
                        caption = "日期 + 正在上 / 下一节",
                    )
                    Spacer(Modifier.width(12.dp))
                    FormGridItem(
                        modifier = Modifier.weight(1f),
                        size = WidgetPreviewSize.Wide,
                        label = "横条",
                        caption = "焦点课课名更宽，地名全显示",
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    FormGridItem(
                        modifier = Modifier.weight(1f),
                        size = WidgetPreviewSize.Tall,
                        label = "竖条",
                        caption = "焦点课 + 今日剩余",
                    )
                    Spacer(Modifier.width(12.dp))
                    FormGridItem(
                        modifier = Modifier.weight(1f),
                        size = WidgetPreviewSize.Large,
                        label = "大方",
                        caption = "摘要 + 本周课表（今天 / 明天列高亮）",
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            SettingsSection(title = "校园卡", subtitle = "数据与点击") {
                Spacer(Modifier.height(4.dp))
                Bullet("余额只算正式卡（食堂 / 门禁），电子账户在副行小字单独展示。")
                Bullet("点小组件任意位置 = 打开全屏付款码页：防截屏、亮度拉满、扫码后自动退出。")
                Bullet("余额更新：打开 App 生活页或付款码页即时更新；后台约每 2 小时一次；" +
                    "取数失败保留上次余额与更新时刻，不会清空。")
                Bullet("未开启凭证时显示引导文案，点击前往「我的 → 校园卡」。")
                HideBalanceSwitch(
                    checked = hideBalance,
                    onCheckedChange = { checked ->
                        hideBalance = checked
                        scope.launch {
                            runCatching { LifeWidgetSync.setCampusHideBalance(context, checked) }
                        }
                    },
                )
                Spacer(Modifier.height(2.dp))
            }

            SettingsSection(title = "电费", subtitle = "数据与点击") {
                Spacer(Modifier.height(4.dp))
                Bullet("显示最近一次读数：剩余电量（度）+ 按单价折合金额 + 寝室房号。")
                Bullet("更新时机：打开生活页 / 充值 / 每日余额检查后同步；小组件自身不联网取数" +
                    "（读数密度 = 打开 App 的密度）。")
                Bullet("点小组件任意位置 = 打开「缴费账单 · 用电统计」。")
                Bullet("还没有读数时显示引导；凭证关闭时一并回落，不残留旧数字。")
                Spacer(Modifier.height(2.dp))
            }

            SettingsSection(
                title = "后台及时性（可选）",
                subtitle = "小组件在上下课时刻自动更新；不开也能用，开启后刷新更及时",
            ) {
                Spacer(Modifier.height(4.dp))
                PermissionRow(
                    title = "忽略电池优化",
                    detail = "防止系统冻结后台刷新；不同手机叫「电池优化白名单 / 省电策略无限制」",
                    granted = caps.batteryWhitelisted,
                    icon = HugeIcons.BatteryCharging01,
                    actionText = if (caps.batteryWhitelisted) "查看" else "去开启",
                    onClick = { WidgetCapabilities.jumpBatteryOptimization(context) },
                )
                WidgetEntryDivider()
                PermissionRow(
                    title = "允许自启动",
                    detail = "开机与被杀后能自行恢复刷新；在厂商设置里找「自启动 / 允许后台运行」",
                    granted = null,
                    icon = HugeIcons.Energy,
                    actionText = "去设置",
                    onClick = { WidgetCapabilities.jumpAutoStart(context) },
                )
                Spacer(Modifier.height(4.dp))
            }

            SettingsSection(title = "说明") {
                Spacer(Modifier.height(6.dp))
                Bullet("三个条目各自独立添加，可以同时上桌面。")
                Bullet("内容与 App 内同口径：课表与「今日」页一致，余额与生活页钱包卡一致。")
                Bullet("跟随系统深浅色；课表条目不占用额外网络，电费条目只显示已取到的读数。")
                Bullet("「还有 N 分钟」按刷新时刻计算，两次刷新之间不会跳动。")
                Spacer(Modifier.height(6.dp))
                LauncherNoteRow()
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    if (showIntro) {
        IntroDialog(
            canPin = caps.canPin,
            onAdd = {
                showIntro = false
                // 弹层里的「现在添加」默认钉一个课表 4×2（推荐）：横条在桌面上信息密度与
                // 可读性平衡最好，之后可随时拖动改大小。校园卡 / 电费条目由用户按需自行添加。
                // 弹层刚关（它也是独立窗口），提示改由页面宿主展示，不被盖住
                onAddClicked(context, ScheduleWidgetReceiver::class.java)?.let(showNotice)
            },
            onDismiss = { showIntro = false },
        )
    }
}

/** 可添加的三条目（DESIGN §3.6 三条目改版）；顺序即页面里的顺序。 */
private data class WidgetEntry(
    val label: String,
    val description: String,
    val receiver: Class<*>,
    val icon: ImageVector,
    val iconTint: Color,
    val countOf: (WidgetCaps) -> Int,
)

private val entries = listOf(
    WidgetEntry(
        label = "水贝贝 · 课表",
        description = "小尺寸看正在上 / 下一节，拖到 4×4 变本周课表",
        receiver = ScheduleWidgetReceiver::class.java,
        icon = HugeIcons.Calendar01,
        iconTint = Color(0xFF0F7C7C),
        countOf = { it.scheduleAdded },
    ),
    WidgetEntry(
        label = "水贝贝 · 校园卡",
        description = "桌面看余额，点一下直接出示付款码",
        receiver = CampusCardWidgetReceiver::class.java,
        icon = HugeIcons.Wallet03,
        iconTint = Color(0xFF1F7A4D),
        countOf = { it.campusAdded },
    ),
    WidgetEntry(
        label = "水贝贝 · 电费",
        description = "桌面看剩余电度，点一下看用电统计",
        receiver = PowerWidgetReceiver::class.java,
        icon = HugeIcons.Energy,
        iconTint = Color(0xFF9A6400),
        countOf = { it.powerAdded },
    ),
)

private fun readCaps(context: Context): WidgetCaps = WidgetCaps(
    canPin = WidgetCapabilities.canPin(context),
    batteryWhitelisted = WidgetCapabilities.isIgnoringBatteryOptimizations(context),
    scheduleAdded = WidgetCapabilities.addedCountOf(context, ScheduleWidgetReceiver::class.java),
    campusAdded = WidgetCapabilities.addedCountOf(context, CampusCardWidgetReceiver::class.java),
    powerAdded = WidgetCapabilities.addedCountOf(context, PowerWidgetReceiver::class.java),
)

private data class WidgetCaps(
    val canPin: Boolean,
    val batteryWhitelisted: Boolean,
    val scheduleAdded: Int,
    val campusAdded: Int,
    val powerAdded: Int,
)

/**
 * 添加指定条目。返回 null = 已提交系统确认框；非 null = 用户可读错误，
 * 由调用方走页面统一的 [AppSnackbarHost]（此前是系统 Toast，与本页其余提示两套观感）。
 */
private fun onAddClicked(context: Context, receiver: Class<*>): String? =
    if (WidgetCapabilities.requestPinOf(context, receiver)) null else WidgetCapabilities.manualAddHint

/** 卡内两行之间的换气线（与分区卡的克制风格一致）。 */
@Composable
private fun WidgetEntryDivider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/**
 * 一条添加行：图标徽标 + 名称 + 摘要 + 已添加徽标 + 「添加」。
 *
 * 三条各指向自己的 receiver；「已添加 N 个」按各自 receiver 计数，互不相干。
 */
@Composable
private fun AddRow(
    label: String,
    description: String,
    icon: ImageVector,
    iconTint: Color,
    added: Int,
    canPin: Boolean,
    onAdd: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconTint.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (added > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (added > 1) "已添加 $added 个" else "已添加",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                shape = MaterialTheme.shapes.small,
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canPin) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    haptics.tap()
                    onAdd()
                },
            ) { Text("添加") }
        }
        // 桌面不支持应用内 pin（DESIGN §3.6）：按钮隐藏，只留分区副标题里的手动引导
    }
}

/** 「在小组件中隐藏余额」开关行（校园卡节内）：状态在 DataStore，写完桌面立即重渲染。 */
@Composable
private fun HideBalanceSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "在小组件中隐藏余额",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "桌面可能被旁人看到；隐藏后显示 ¥ ••••，点击仍可出示付款码",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun Bullet(text: String) {
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/**
 * 负一屏说明行（DESIGN §3.6「负一屏」）。
 *
 * 单独成块而不是混在 Bullet 里：这是用户明确问过的点，值得给一句能照着做的话
 * （去哪儿搜、搜不到怎么办），而不是一句「不支持」。
 */
@Composable
private fun LauncherNoteRow() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = "负一屏",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = WidgetCapabilities.launcherNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

// ---------- 预览缩略图：用真实课表数据画一个迷你 widget ----------

/**
 * 设置页展示用的快照（与小组件同一套状态推导），30 秒随时间重算一次。
 *
 * 与 widget 侧的差别只有一处：**周网格直接构建**（不按尺寸跳过），
 * 因为四张预览里有一张就是周网格档。渲染仍走 `forSize`，与桌面所见同源。
 */
@Composable
private fun rememberPreviewSnapshot(): WidgetSnapshot? {
    val context = LocalContext.current
    val repo = remember { Graph.repository(context) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    return produceState<WidgetSnapshot?>(initialValue = null, tick) {
        value = runCatching {
            val semester = repo.semester.first()
            val slots = repo.timeSlots.first()
            val courses = repo.courses.first()
            val prefs = repo.displayPrefs.first()
            val today = LocalDate.now()
            val now = LocalTimeLike.now()
            val state = buildTodayState(semester, slots, courses, today, now)
            val week = if (state.inTerm) state.week else 1
            val highlight = state.focus?.day
                ?: state.tomorrowDay.takeIf { state.tomorrowVisible }
            buildWidgetSnapshot(
                state,
                buildWidgetWeek(
                    courses = courses,
                    week = week,
                    showSaturday = prefs.showSaturday,
                    showSunday = prefs.showSunday,
                    filter = prefs.courseFilter,
                    highlightDay = highlight,
                    slots = slots,
                    now = now,
                    markNow = state.focus != null || state.remaining.isNotEmpty(),
                    nowDay = state.day,
                ),
            )
        }.getOrNull()
    }.value
}

/** 2×2 网格里的一格预览：缩略图在上、名称与说明在下（不是可添加条目，纯形态参考）。 */
@Composable
private fun FormGridItem(
    modifier: Modifier,
    size: WidgetPreviewSize,
    label: String,
    caption: String,
) {
    Column(modifier) {
        PreviewTile(size)
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 迷你预览：按该档实测 dp 走同一套 [widgetMetricsFor] 分档；课表为空时给占位文案。 */
@Composable
private fun PreviewTile(size: WidgetPreviewSize) {
    val snapshot = rememberPreviewSnapshot()
    val (w, h) = when (size) {
        WidgetPreviewSize.Small -> 84.dp to 84.dp
        WidgetPreviewSize.Wide -> 116.dp to 58.dp
        WidgetPreviewSize.Tall -> 58.dp to 116.dp
        WidgetPreviewSize.Large -> 128.dp to 128.dp
    }
    Box(
        modifier = Modifier
            .width(w)
            .height(h)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(7.dp),
    ) {
        val model = snapshot?.forSize(widgetMetricsFor(size.widthDp, size.heightDp))
        if (model == null) {
            Text(
                text = "课表\n为空",
                fontSize = 8.sp,
                lineHeight = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = model.header,
                    fontSize = 7.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PreviewBody(model)
            }
        }
    }
}

@Composable
private fun PreviewBody(model: WidgetModel) {
    // 周网格档：画一个极简的 7×5 网格示意（真实网格在 128dp 缩略图里画不出可读信息，
    // 这里只表达「大尺寸会给周课表」这件事；实际效果以桌面为准）
    val week = model.week
    if (week != null) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                week.days.forEach { day ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .background(
                                if (day == week.highlightDay) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                                },
                            ),
                    ) {}
                }
            }
            repeat(4) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    week.days.forEach { day ->
                        val hasCourse = week.blocks.any {
                            it.day == day && row == (it.startSection - 1) / 3
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(7.dp)
                                .background(
                                    if (hasCourse) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.06f)
                                    },
                                ),
                        ) {}
                    }
                }
            }
        }
        return
    }
    PreviewFocus(model.focus)
    // 明日接棒时列表标题也画出来（正是「不留空白」的落点）
    model.listTitle?.let { title ->
        Text(
            text = title,
            fontSize = 6.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    model.rows.take(2).forEach { row ->
        PreviewRow(row.colorIndex, row.name, row.clock)
    }
}

@Composable
private fun PreviewFocus(focus: WidgetFocus) {
    when (focus) {
        is WidgetFocus.Course -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(courseColor(focus.colorIndex).copy(alpha = 0.16f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Column {
                    Text(
                        text = focus.label,
                        fontSize = 6.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = courseColor(focus.colorIndex),
                    )
                    Text(
                        text = focus.name,
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        is WidgetFocus.Idle -> Text(
            text = focus.title,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PreviewRow(colorIndex: Int, name: String, clock: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = clock,
            fontSize = 6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(3.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(courseColor(colorIndex).copy(alpha = 0.16f))
                .padding(horizontal = 3.dp, vertical = 1.dp),
        ) {
            Text(
                text = name,
                fontSize = 7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 首次进入的说明弹层（DESIGN §3.6「进入设置界面自动申请」的落点）。 */
@Composable
private fun IntroDialog(
    canPin: Boolean,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("把水贝贝放上桌面") },
        text = {
            Text(
                text = "课表：小到 2×2 看下一节，拖到 4×4 变本周课表。\n\n" +
                    "校园卡：桌面看余额，点一下直接出示付款码。\n\n" +
                    "电费：桌面看剩余电度，点一下看用电统计。\n\n" +
                    "小组件在上下课时刻自动更新；希望更及时可在页面下方开启" +
                    "「忽略电池优化」与「允许自启动」（可选，不开也能用）。",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            if (canPin) {
                TextButton(onClick = onAdd) { Text("现在添加") }
            } else {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后") }
        },
    )
}
