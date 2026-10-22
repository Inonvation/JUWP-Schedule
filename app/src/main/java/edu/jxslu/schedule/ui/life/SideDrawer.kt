package edu.jxslu.schedule.ui.life

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.ui.common.AppCardDefaults
import edu.jxslu.schedule.R
import edu.jxslu.schedule.data.ujing.UjingHouseStore
import edu.jxslu.schedule.data.ujing.UjingRepository
import edu.jxslu.schedule.domain.AccountMask
import edu.jxslu.schedule.domain.ShortcutSettings
import edu.jxslu.schedule.ui.campus.CampusCardViewModel
import edu.jxslu.schedule.ui.common.ShortcutLauncher
import edu.jxslu.schedule.ui.common.ShortcutPinner
import edu.jxslu.schedule.ui.common.ShortcutSheet
import edu.jxslu.schedule.ui.common.rememberResumeTick
import edu.jxslu.schedule.ui.common.rememberSheetDismisser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bolt
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.HeartHandshake
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.QrCodeScan
import me.rerere.hugeicons.stroke.RemoteControl
import me.rerere.hugeicons.stroke.ScooterElectric
import me.rerere.hugeicons.stroke.Settings01
import me.rerere.hugeicons.stroke.ShowerHead
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Task01
import me.rerere.hugeicons.stroke.Toolbox
import me.rerere.hugeicons.stroke.Wallet01
import me.rerere.hugeicons.stroke.WashingMachine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private enum class WalletDetail { Campus, Power }

/** 侧栏 U净 行右侧空闲状态的刷新节流：空闲看板本身约 30 秒滞后，刷更勤没意义。 */
private const val UJING_FREE_REFRESH_TTL_MILLIS = 30_000L

/** 侧栏 U净 聚合的并发上限（与 U净 页看板同口径，别一次打满收藏店）。 */
private const val UJING_FREE_CONCURRENCY = 4

/** 侧栏寝室电费读数的刷新节流（2026-10-22：旧生活页删除后，侧栏是它的唯一展示位）。 */
private const val DRAWER_POWER_REFRESH_TTL_MILLIS = 60_000L

/**
 * 拉收藏洗衣房的洗衣机空闲聚合（侧栏 U净 行右侧的展示数）。
 * 多店求和（free / total），数据源与 U净 页看板同源：`stores/near?keyword=<店名>`
 * 的门店统计（`devices/reserve` 在本校已被服务端关闭）。
 * 未登录 / 没收藏店 / 没有任何成功数据 → null：右侧不显示状态，整行点击直接进页。
 */
private suspend fun loadUjingFreeText(
    repo: UjingRepository,
    houseStore: UjingHouseStore,
): String? {
    if (!repo.loggedIn.value) return null
    val houses = houseStore.list()
    if (houses.isEmpty()) return null
    // 多店并发、单店失败只算这一家不参与求和（`runCatching` 会把取消一起吞掉，
    // 这里手写 catch：侧栏收起时必须照常向上抛，不把取消当失败）。
    val limiter = Semaphore(UJING_FREE_CONCURRENCY)
    val lines = coroutineScope {
        houses.map { house ->
            async {
                try {
                    val groups = limiter.withPermit { repo.storeCounts(house.name) }
                    repo.boardLine(groups)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll()
    }.filterNotNull()
    val free = lines.sumOf { it.free }
    val total = lines.sumOf { it.total }
    if (lines.isEmpty() || total <= 0) return null
    return if (free > 0) "空闲 $free/$total" else "暂无空闲"
}

/**
 * 四行服务的「进行中」标记（2026-10-21）：侧栏行描边高亮的依据。
 * 由调用侧从各真相源收集（快趣骑行/免费计时、胖乖 Working、趣智 watering、U净 在案订单），
 * 侧栏只消费布尔——数据口径与今日页「进行中服务卡」同源，两处展示不另立判据。
 */
data class ServiceActiveFlags(
    val ride: Boolean = false,
    val water: Boolean = false,
    val qzxy: Boolean = false,
    val ujing: Boolean = false,
)

/**
 * 左侧栏内容（2026-10-20 第二轮）。
 *
 * - 头部用「我的」页同一份身份数据（姓名 / 遮罩学号 / 班级），不再写死「水贝贝」；
 *   头像 + 姓名 + 班级整块可点，进学籍信息页（2026-10-21，入口从教务 Tab 挪来）；
 * - 校园卡与电费都是可点的余额行，详情弹层里放账单与充值；右侧一枚充值入口开选择弹层；
 *   电子账户与寝室电费并排一行（2026-10-21），省纵向空间；
 * - 生活（取快递 / 空调遥控 / 百宝箱 / 天气）· 江水生活（快趣 / 胖乖 / 趣智 / U净 / 问渠）
 *   · 学习（笔记 / 作业）三段，2026-10-21 起取消「工具」段：百宝箱与天气并入生活，
 *   五张服务卡单列品牌段；
 *   收件箱不进侧栏（2026-10-21 收口：唯一展示入口在今日页收件箱卡）；
 * - 底部左设置、右付款码，`navigationBarsPadding` 让开手势条，不再贴屏幕底。
 */
@Composable
fun SideDrawerContent(
    onOpenAccounts: () -> Unit,
    /** 头像 / 姓名 / 班级那一块（2026-10-21）：点开教务学籍信息页。 */
    onOpenStudentInfo: () -> Unit,
    onOpenPayCode: () -> Unit,
    onOpenShortcuts: (String?) -> Unit,
    onOpenCainiao: () -> Unit,
    /** U净：整行进二级页；右侧空闲状态点开的是入口弹窗（在案订单 + 扫码 + 概览）。 */
    onOpenUjing: () -> Unit,
    onOpenUjingSheet: () -> Unit,
    onOpenAcRemote: () -> Unit,
    onOpenRide: () -> Unit,
    /** 胖乖生活：整行进二级页；右侧余额点开的是入口弹窗（余额 / 积分 / 开水 / 结算）。 */
    onOpenWater: () -> Unit,
    onOpenWaterSheet: () -> Unit,
    /** 趣智校园：整行进二级页；右侧余额点开的是入口弹窗（余额 / 设备 / 用水状态 / 结算）。 */
    onOpenQzxy: () -> Unit,
    onOpenQzxySheet: () -> Unit,
    /** 胖乖小票余额文案（null = 不显示）。 */
    waterBalance: String?,
    /** 趣智余额文案（null = 不显示）。 */
    qzxyBalance: String?,
    /** 问渠总工时文案（如「12.5 工时」；null = 未登录 / 还没取到，不显示）。 */
    wenquHours: String?,
    /**
     * 四行服务各自的「进行中」标记（2026-10-21）：行描边高亮的依据。
     * 由调用侧（MainActivity）从各真相源收集——快趣骑行/免费计时、胖乖 Working、
     * 趣智 watering、U净 在案订单；这里只消费布尔，不发任何请求。
     */
    serviceActive: ServiceActiveFlags = ServiceActiveFlags(),
    /** 侧栏当前是否可见（U净 空闲摘要只在可见时拉，不空发请求）。 */
    drawerVisible: Boolean,
    onOpenWenqu: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenHomework: () -> Unit,
    onOpenToolbox: () -> Unit,
    onOpenWeather: () -> Unit,
    onOpenStatement: () -> Unit,
    onOpenPowerBill: () -> Unit,
    onOpenCampusRecharge: () -> Unit,
    onOpenPowerRecharge: () -> Unit,
    onOpenSettings: () -> Unit,
    onScan: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Graph.displayPrefs(context) }
    val profileName by prefs.profileName.collectAsStateWithLifecycle(initialValue = "")
    val profileClass by prefs.profileClass.collectAsStateWithLifecycle(initialValue = "")
    val profileStudentId by prefs.profileStudentId.collectAsStateWithLifecycle(initialValue = "")
    val resumeTick = rememberResumeTick()
    val vault = remember { Graph.credentialVault(context) }
    val casUsername = remember(resumeTick) { vault.readCas()?.username }
    val yktUsername = remember(resumeTick) { vault.readYkt()?.username }
    val username = casUsername ?: yktUsername ?: profileStudentId
    val masked = AccountMask.maskStudentId(username).orEmpty()
    // 2026-10-20 用户口径：学号跟在姓名后面，班级单独做次标题。
    val titleLine = buildList {
        if (profileName.isNotBlank()) add(profileName)
        if (masked.isNotBlank()) add(masked)
    }.joinToString(" ").ifBlank { "尚未配置" }
    val subtitle = profileClass.ifBlank { "登录教务后自动填班级" }
    val avatarText = profileName.firstOrNull()?.toString() ?: "水"

    val campusVm: CampusCardViewModel = viewModel(
        factory = CampusCardViewModel.Factory(context.applicationContext),
    )
    val lifeVm: LifeViewModel = viewModel(factory = LifeViewModel.Factory(context))
    val campusEnabled by campusVm.enabled.collectAsStateWithLifecycle()
    // 百宝箱总开关（设置 → 功能）：2026-10-22 顶栏「+」删除后，入口只剩侧栏这一行，
    // 开关必须继续生效——关掉时整行不渲染（不是置灰）。
    val toolboxEnabled by prefs.toolboxEnabled.collectAsStateWithLifecycle(initialValue = true)
    val balance by campusVm.balance.collectAsStateWithLifecycle()
    val balanceLoaded by campusVm.balanceLoaded.collectAsStateWithLifecycle()
    val lifeState by lifeVm.uiState.collectAsStateWithLifecycle()
    val power = lifeState.power
    val snapshot = balance
    val remain = power.snapshot?.meter?.remain
    val balanceText = when {
        !campusEnabled -> "未开启"
        snapshot != null -> "¥%.2f".format(snapshot.cardFen / 100.0)
        balanceLoaded -> "—"
        else -> "读取中…"
    }
    val accountValue = snapshot?.accountFen
        ?.let { "¥%.2f".format(it / 100.0) }
        ?: "—"
    val powerText = when {
        // 有读数就先留着：刷新期间不再退回「读取中…」（侧栏展开即静默刷一轮，
        // 每次把已显示的电量抹掉再填回来，读起来像在闪）。无凭证 / 未绑定房间
        // 是终态，优先于旧读数——那份读数已经不属于当前房间。
        power.noCredentials || power.roomNotBound -> "—"
        remain != null -> "%.1f 度".format(remain)
        power.loading -> "读取中…"
        power.error != null -> "读取失败"
        else -> "—"
    }

    // U净 行右侧的空闲摘要（2026-10-21）：侧栏**展开时**拉一次收藏店聚合，带 30 秒节流
    // （与空闲看板「云端数据约 30 秒滞后」同频，刷更勤没有意义）。未登录 / 无收藏店
    // 直接置 null：不显示状态、整行点击只进页，右侧弹窗入口随之隐藏。
    // **判据是 drawerVisible 不是组合存活**：侧栏是常驻组合（被上层盖住不离树），
    // 冷启动就发请求等于「没人看的查询打扰第三方接口」——与 MainActivity 余额刷新
    // 同一道闸（drawer.isVisible）。
    val ujingRepo = remember { Graph.ujing(context) }
    val ujingHouseStore = remember { Graph.ujingHouses(context) }
    val ujingLoggedIn by ujingRepo.loggedIn.collectAsStateWithLifecycle()
    var ujingFreeText by remember { mutableStateOf<String?>(null) }
    var ujingFreeLoading by remember { mutableStateOf(false) }
    var ujingFreeRefreshedAt by remember { mutableStateOf(0L) }
    // 在途请求的序号：侧栏收起会取消这个 effect，`finally` 里只有「仍是最新一次请求」
    // 才能把 loading 抹掉——否则收起瞬间取消，转圈会一直挂到下一次节流窗口结束。
    val ujingFreeRequest = remember { AtomicInteger() }
    LaunchedEffect(ujingLoggedIn, resumeTick, drawerVisible) {
        if (!drawerVisible) return@LaunchedEffect
        if (!ujingLoggedIn) {
            ujingFreeText = null
            // 退出登录清掉节流起点：重新登录后应立刻能取到空闲数，不等旧的 30 秒窗口
            ujingFreeRefreshedAt = 0
            return@LaunchedEffect
        }
        val now = System.currentTimeMillis()
        // 节流窗口内不重拉（resumeTick 驱动的回来刷新也走同一道闸）
        if (now - ujingFreeRefreshedAt < UJING_FREE_REFRESH_TTL_MILLIS) return@LaunchedEffect
        val token = ujingFreeRequest.incrementAndGet()
        ujingFreeLoading = true
        try {
            ujingFreeText = withContext(Dispatchers.IO) {
                loadUjingFreeText(ujingRepo, ujingHouseStore)
            }
            // 只在正常走完时落节流时刻：侧栏收起取消掉的请求不算数，下次展开要补拉
            ujingFreeRefreshedAt = now
        } finally {
            if (ujingFreeRequest.get() == token) ujingFreeLoading = false
        }
    }

    // 寝室电费读数：旧生活页删除后，侧栏是它唯一的常驻展示位——展开侧栏时静默刷一轮
    // （60 秒节流；仓库还有 120 秒缓存，切来切去不会真的重复打平台）。
    var powerRefreshedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(drawerVisible, resumeTick) {
        if (!drawerVisible) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - powerRefreshedAt < DRAWER_POWER_REFRESH_TTL_MILLIS) return@LaunchedEffect
        powerRefreshedAt = now
        lifeVm.refreshPower(force = false)
    }

    var showShortcutSheet by remember { mutableStateOf(false) }
    var showRechargeChoice by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<WalletDetail?>(null) }
    val shortcutSettings by remember(context) { Graph.repository(context).shortcutSettings }
        .collectAsStateWithLifecycle(initialValue = ShortcutSettings())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notice: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }

    Box(
        Modifier
            .fillMaxSize()
            // 侧栏收起时是被主页面盖住的后层，但它仍在组合树里：不清语义的话
            // TalkBack 会读到看不见的入口。展开（含动画中）才恢复。
            .then(if (drawerVisible) Modifier else Modifier.clearAndSetSemantics {}),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            val scrollState = rememberScrollState()
            // 底部 16dp 渐隐只在「下面还有内容」时出现：短屏滚动到一半能看出来还能滑。
            val drawerBg = MaterialTheme.colorScheme.surfaceContainerLow
            Column(
                modifier = Modifier
                    .weight(1f)
                    .drawWithContent {
                        drawContent()
                        if (scrollState.canScrollForward) {
                            val fade = 16.dp.toPx()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, drawerBg),
                                    startY = size.height - fade,
                                    endY = size.height,
                                ),
                                topLeft = Offset(0f, size.height - fade),
                                size = Size(size.width, fade),
                            )
                        }
                    }
                    .verticalScroll(scrollState),
            ) {
                DrawerHeader(
                    avatarText = avatarText,
                    title = titleLine,
                    subtitle = subtitle,
                    onOpenStudentInfo = onOpenStudentInfo,
                    onScan = onScan,
                )
                AccountRow(onClick = onOpenAccounts)
                WalletHero(
                    balanceText = balanceText,
                    accountValue = accountValue,
                    powerText = powerText,
                    onCampusDetail = { detail = WalletDetail.Campus },
                    onPowerDetail = { detail = WalletDetail.Power },
                    onRecharge = { showRechargeChoice = true },
                )

                // 2026-10-21 用户口径：百宝箱 / 天气从「工具」并入「生活」（空调遥控之后）；
                // 快趣 / 胖乖 / 趣智 / U净 / 问渠五张服务卡单列「江水生活」。
                DrawerSection("生活") {
                    DrawerRow(
                        label = "取快递",
                        icon = HugeIcons.Package01,
                        onClick = { showShortcutSheet = true },
                    )
                    DrawerRow("空调遥控", HugeIcons.RemoteControl, onClick = onOpenAcRemote)
                    if (toolboxEnabled) {
                        DrawerRow("百宝箱", HugeIcons.Toolbox, onClick = onOpenToolbox)
                    }
                    DrawerRow("天气", HugeIcons.Sun01, onClick = onOpenWeather)
                }
                DrawerSection("江水生活") {
                    // 快趣/胖乖/趣智/U净/问渠五行走**官方品牌图标**（2026-10-21 用户口径）：
                    // 位图自带彩色底，视觉体量比线性图标大，槽位取 24dp（线性 22dp）保持行内平衡
                    DrawerRow(
                        label = "快趣出行",
                        brandIcon = R.drawable.ic_brand_kqcx,
                        active = serviceActive.ride,
                        onClick = onOpenRide,
                    )
                    // 三张卡（2026-10-21 用户口径）：**行本体直接进二级页**，右侧状态 / 余额
                    // 才开原来的入口弹窗——「看状态」与「进页操作」是两个动作，不再让
                    // 整行都落在弹窗上。U净 无登录 / 无收藏店时右侧没有状态文字，但箭头
                    // 仍是入口（在案订单 / 扫码），命中区按 48dp 给足；数据侧等登录才拉。
                    DrawerRow(
                        label = "胖乖生活",
                        brandIcon = R.drawable.ic_brand_pangguai,
                        trailing = waterBalance,
                        active = serviceActive.water,
                        onClick = onOpenWater,
                        onTrailingClick = onOpenWaterSheet,
                    )
                    DrawerRow(
                        label = "趣智校园",
                        brandIcon = R.drawable.ic_brand_qzxy,
                        trailing = qzxyBalance,
                        active = serviceActive.qzxy,
                        onClick = onOpenQzxy,
                        onTrailingClick = onOpenQzxySheet,
                    )
                    DrawerRow(
                        label = "U净",
                        brandIcon = R.drawable.ic_brand_ujing,
                        trailing = ujingFreeText,
                        trailingLoading = ujingFreeLoading,
                        active = serviceActive.ujing,
                        onClick = onOpenUjing,
                        onTrailingClick = onOpenUjingSheet,
                    )
                    DrawerRow(
                        label = "问渠 · 志愿服务",
                        brandIcon = R.drawable.ic_brand_wenqu,
                        trailing = wenquHours,
                        onClick = onOpenWenqu,
                    )
                }
                DrawerSection("学习") {
                    DrawerRow("笔记 · 课件", HugeIcons.Book01, onClick = onOpenNotes)
                    DrawerRow("作业", HugeIcons.Task01, onClick = onOpenHomework)
                }
                Spacer(Modifier.height(16.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsEntry(onClick = onOpenSettings)
                PaymentCodeEntry(onClick = onOpenPayCode)
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp),
        )
    }

    if (showShortcutSheet) {
        ShortcutSheet(
            settings = shortcutSettings,
            onDismiss = { showShortcutSheet = false },
            onLaunchItem = { item -> ShortcutLauncher.launch(context, item)?.let(notice) },
            onPinItem = { item -> ShortcutPinner.pin(context, item)?.let(notice) },
            onEdit = { itemId ->
                showShortcutSheet = false
                onOpenShortcuts(itemId)
            },
            onAdd = {
                showShortcutSheet = false
                onOpenShortcuts(null)
            },
            onOpenCainiao = {
                showShortcutSheet = false
                onOpenCainiao()
            },
        )
    }

    if (showRechargeChoice) {
        RechargeChoiceSheet(
            onDismiss = { showRechargeChoice = false },
            onPickCampus = {
                showRechargeChoice = false
                onOpenCampusRecharge()
            },
            onPickPower = {
                showRechargeChoice = false
                onOpenPowerRecharge()
            },
        )
    }

    detail?.let { which ->
        WalletDetailSheet(
            detail = which,
            campusCardFen = snapshot?.cardFen,
            campusAccountFen = snapshot?.accountFen,
            campusAt = snapshot?.fetchedAtMs,
            powerRemain = remain,
            powerRoom = power.snapshot?.meter?.room?.room,
            powerAt = power.snapshot?.meter?.fetchedAtMs,
            onDismiss = { detail = null },
            // 弹层退场由 WalletDetailSheet 的 dismisser 负责，这里只做动作
            onOpenBill = {
                if (which == WalletDetail.Campus) onOpenStatement() else onOpenPowerBill()
            },
            // 详情弹层已经知道是哪一项：直接进对应充值，不再多问一次「充哪个」
            onRecharge = {
                if (which == WalletDetail.Campus) onOpenCampusRecharge() else onOpenPowerRecharge()
            },
        )
    }
}

@Composable
private fun DrawerHeader(
    avatarText: String,
    title: String,
    subtitle: String,
    onOpenStudentInfo: () -> Unit,
    onScan: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 头像 + 姓名 + 班级整块可点：进「学籍信息」（2026-10-21 用户口径，
        // 该入口从教务 Tab 的服务列表挪到这里）。扫一扫是独立按钮，不包进这块。
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "学籍信息", onClick = onOpenStudentInfo)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = avatarText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        ScanCircle(onClick = onScan)
    }
}

@Composable
private fun ScanCircle(onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "drawerScanScale")
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClickLabel = "扫一扫",
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = HugeIcons.QrCodeScan,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 账号与登录：紧跟头像信息，不再占底部位置。 */
@Composable
private fun AccountRow(onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .padding(start = 24.dp, end = 24.dp, top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "账号与登录", onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("账号与登录", style = MaterialTheme.typography.labelMedium, color = muted)
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = HugeIcons.ChevronRight,
            contentDescription = null,
            tint = muted,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun WalletHero(
    balanceText: String,
    accountValue: String,
    powerText: String,
    onCampusDetail: () -> Unit,
    onPowerDetail: () -> Unit,
    onRecharge: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = onSurface.copy(alpha = 0.55f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(horizontal = 24.dp)
            .padding(top = 12.dp),
    ) {
        Text("校园卡余额", style = MaterialTheme.typography.labelSmall, color = muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = balanceText,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "drawerBalance",
                modifier = Modifier.weight(1f),
            ) { text ->
                // 金额按长度分三档：¥ 开头最多 7 字保持 34sp，8–9 字降 28sp，
                // 再长降 24sp——大字号或四位数余额不会把充值按钮挤走。
                val balanceSize = when {
                    !text.startsWith("¥") -> 20.sp
                    text.length <= 7 -> 34.sp
                    text.length <= 9 -> 28.sp
                    else -> 24.sp
                }
                Text(
                    text = text,
                    // tnum：余额刷新时数字宽度不抖
                    style = MaterialTheme.typography.bodyLarge
                        .copy(fontFeatureSettings = "tnum"),
                    fontSize = balanceSize,
                    lineHeight = when (balanceSize) {
                        34.sp -> 40.sp
                        28.sp -> 34.sp
                        else -> 30.sp
                    },
                    fontWeight = FontWeight.Bold,
                    color = primary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "校园卡详情", onClick = onCampusDetail)
                        .padding(vertical = 2.dp),
                )
            }
            // 与余额数字同一行、垂直居中：上一版挂在「校园卡余额」小标题那一行，看着没对齐。
            RechargeButton(onClick = onRecharge)
        }
        // 电子账户与寝室电费并成一行（2026-10-21 用户口径）：两项都是可点的余额，
        // 各占一半、中间一道细分隔线；从原来的两行（小字 + 电费行）压到一行，
        // 也收掉了钱包区与「生活」段之间的空档。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(onSurface.copy(alpha = 0.04f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalletStat(
                label = "电子账户",
                value = accountValue,
                modifier = Modifier.weight(1f),
                onClick = onCampusDetail,
            )
            Box(
                Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(onSurface.copy(alpha = 0.08f)),
            )
            WalletStat(
                label = "寝室电费",
                value = powerText,
                modifier = Modifier.weight(1f),
                onClick = onPowerDetail,
            )
        }
    }
}

/**
 * 钱包区的一格余额（2026-10-21）：小字标签 + 右对齐的数值，整格可点开详情。
 * 数值走 [AnimatedContent]：异步加载时「—」→ 金额是渐变而不是跳字。
 */
@Composable
private fun WalletStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = "$label 详情", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = onSurface.copy(alpha = 0.55f),
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        AnimatedContent(
            targetState = value,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "drawerWalletStat",
            modifier = Modifier.weight(1f),
        ) { text ->
            Text(
                text = text,
                // tnum：金额 / 电量的数字宽度固定，刷新时不会把分隔线挤动
                style = MaterialTheme.typography.labelMedium
                    .copy(fontFeatureSettings = "tnum"),
                color = onSurface.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RechargeButton(onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "drawerRechargeScale")
    Row(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(99.dp))
            .background(primary.copy(alpha = 0.10f))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClickLabel = "充值",
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HugeIcons.Wallet01, contentDescription = null, tint = primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("充值", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletDetailSheet(
    detail: WalletDetail,
    campusCardFen: Long?,
    campusAccountFen: Long?,
    campusAt: Long?,
    powerRemain: Double?,
    powerRoom: String?,
    powerAt: Long?,
    onDismiss: () -> Unit,
    onOpenBill: () -> Unit,
    onRecharge: () -> Unit,
) {
    val isCampus = detail == WalletDetail.Campus
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    // 按钮动作先播完弹层退场再执行（账单页 / 充值弹层不会和这张弹层重叠一帧）
    val sheetState = rememberModalBottomSheetState()
    val dismissThen = rememberSheetDismisser(sheetState, onDismiss)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = if (isCampus) "校园卡" else "寝室电费",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (isCampus) {
                    campusCardFen?.let { "¥%.2f".format(it / 100.0) } ?: "—"
                } else {
                    powerRemain?.let { "%.2f 度".format(it) } ?: "—"
                },
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = if (isCampus) {
                    "正式卡余额；电子账户另计"
                } else {
                    powerRemain?.let { "约 ¥%.2f（按 0.62 元/度估算）".format(it * 0.62) } ?: "以缴费平台账单为准"
                },
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            Spacer(Modifier.height(12.dp))
            if (isCampus) {
                DetailRow("正式卡", campusCardFen?.let { "¥%.2f".format(it / 100.0) } ?: "—")
                DetailRow("电子账户", campusAccountFen?.let { "¥%.2f".format(it / 100.0) } ?: "—")
                DetailRow("更新时间", formatTime(campusAt))
            } else {
                DetailRow("房间", powerRoom ?: "—")
                DetailRow("更新时间", formatTime(powerAt))
            }
            // 旧「生活」页的钱包说明随页面删除后挪到这里：详情弹层是余额/电费的
            // 唯一常驻落点，这两句用户要求保留的口径不能丢。
            Text(
                text = if (isCampus) BALANCE_NOTE else ELECTRICITY_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { dismissThen(onOpenBill) },
                    modifier = Modifier.weight(1f),
                ) { Text("账单") }
                Button(
                    onClick = { dismissThen(onRecharge) },
                    modifier = Modifier.weight(1f),
                ) { Text("充值") }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RechargeChoiceSheet(
    onDismiss: () -> Unit,
    onPickCampus: () -> Unit,
    onPickPower: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val dismissThen = rememberSheetDismisser(sheetState, onDismiss)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("选择充值项目", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "两个系统各充各的，请确认要充哪一个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RechargeTile(
                    title = "一卡通充值",
                    subtitle = "正式卡 / 电子账户",
                    icon = HugeIcons.Wallet01,
                    modifier = Modifier.weight(1f),
                    // 退场播完再弹下一张，两张弹层不叠在同一帧
                    onClick = { dismissThen(onPickCampus) },
                )
                RechargeTile(
                    title = "寝室电费充值",
                    subtitle = "缴费平台 · 房间电表",
                    icon = HugeIcons.Bolt,
                    modifier = Modifier.weight(1f),
                    onClick = { dismissThen(onPickPower) },
                )
            }
        }
    }
}

@Composable
private fun RechargeTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClickLabel = title, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = primary, modifier = Modifier.size(20.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun formatTime(at: Long?): String =
    at?.let { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it)) } ?: "—"

@Composable
private fun DrawerSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        // 2026-10-21 收窄：上一版段头上下 18/4dp，钱包区收成一行后仍显松，压到 14/2dp
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 2.dp),
    )
    Column(content = content)
}

/**
 * 侧栏一行入口（2026-10-21 双命中区）：
 * - **行本体**（图标 + 名称 + 空白）→ [onClick]，进对应二级页；
 * - **右侧状态 / 余额**（[trailing]，有 [onTrailingClick] 才可点）→ 开入口弹窗。
 *   命中区加大（垂直 24dp），别让状态文案小到点不准；
 * - 状态字号从 labelSmall 提到 **titleSmall + SemiBold + 主色**：余额 / 空闲数是
 *   这行最该被看到的信息，也是可点的目标，视觉上要立得住；
 * - 状态与箭头**收在同一列右缘**（2026-10-21）：金额一律右对齐、箭头始终在最右，
 *   各行扫下来是一条竖线（此前可点状态多一层 8dp 内边距，金额比不可点的行缩进一截）；
 *   右侧命中区**至少 48dp 宽**（U净未登录 / 无收藏店时只剩一枚箭头，16dp 宽点不准），
 *   内容仍贴右缘（`Arrangement.End`），箭头位置不因加宽而左移；
 * - [active] = 对应服务**正在进行中**（骑行 / 开水 / 用水 / 洗烘）：整行加主色描边 +
 *   浅 tonal 底（180ms 过渡，与 `AppCard.highlighted` 同族口径），展开侧栏一眼即见
 *   「这行有事在跑」——右侧状态数字只是信息，描边才是「现在就值得看」的信号；
 * - 按下整行轻微回缩（0.985，与扫一扫 / 付款码同族），松手回弹。
 */
@Composable
private fun DrawerRow(
    label: String,
    icon: ImageVector? = null,
    /** 官方品牌图标（drawable 位图，自带彩色底）：与 [icon] 二选一，brandIcon 优先。 */
    brandIcon: Int? = null,
    trailing: String? = null,
    trailingLoading: Boolean = false,
    /** 服务进行中：主色描边点亮（见 KDoc）。 */
    active: Boolean = false,
    onClick: () -> Unit,
    onTrailingClick: (() -> Unit)? = null,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = onSurface.copy(alpha = 0.78f)
    val rowShape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        label = "drawerRowPress",
    )
    // 高亮过渡与 AppCard.highlighted 同一族（180ms）：描边色 / 底色 / 描边宽三处一起动
    val borderColor by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(AppCardDefaults.HighlightTransitionMillis),
        label = "drawerRowBorder",
    )
    val containerColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(AppCardDefaults.HighlightTransitionMillis),
        label = "drawerRowContainer",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(rowShape)
            .background(containerColor)
            .border(1.dp, borderColor, rowShape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClickLabel = label,
                onClick = onClick,
            )
            .heightIn(min = 50.dp)
            // 纵向内边距 1dp：给右侧 48dp 的命中区留出空间，行高仍是 50dp
            .padding(horizontal = 12.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            // 品牌位图 24dp：彩色底自带视觉重量，比线性图标放大一档才不显小，
            // 但仍比文字行高小一截（M3 行高 56dp 的 43%），不把行撑高
            brandIcon != null -> Image(
                painter = painterResource(brandIcon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            else -> Icon(
                imageVector = icon!!,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (trailingLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
        }
        // 状态 + 箭头是一组、钉在行的右缘：金额在左、箭头在右，所有行共用同一条收边。
        // 数值变化（余额 / 空闲数 / 工时异步到达）走淡入淡出，不跳字。
        val trailingSlot: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = trailing,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    contentAlignment = Alignment.CenterEnd,
                    label = "drawerRowTrailing",
                ) { text ->
                    if (text == null) {
                        Spacer(Modifier.size(0.dp))
                    } else {
                        Text(
                            text = text,
                            // tnum：余额 / 空闲数刷新时数字宽度不抖
                            style = MaterialTheme.typography.titleSmall
                                .copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (trailing != null) Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = HugeIcons.ChevronRight,
                    contentDescription = null,
                    tint = if (onTrailingClick != null) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    } else {
                        onSurface.copy(alpha = 0.3f)
                    },
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (onTrailingClick != null) {
            Row(
                modifier = Modifier
                    .widthIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        onClickLabel = "$label 余额与入口",
                        onClick = onTrailingClick,
                    )
                    // 20sp 文本 + 上下 14dp = 48dp 命中区（行内容高度正好 48dp）
                    .padding(vertical = 14.dp),
                // 内容贴右缘：补出来的宽度只长在左侧，箭头仍与其它行对齐
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trailingSlot()
            }
        } else {
            trailingSlot()
        }
    }
}

/** 左下角的设置入口：齿轮 + 文字，和右下付款码对称。 */
@Composable
private fun SettingsEntry(onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .clickable(onClickLabel = "设置", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = HugeIcons.Settings01,
            contentDescription = null,
            tint = onSurface.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "设置",
            style = MaterialTheme.typography.labelLarge,
            color = onSurface.copy(alpha = 0.7f),
        )
    }
}

/** 右下角的付款码图标入口：按下轻微缩放，松开回弹。 */
@Composable
private fun PaymentCodeEntry(onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        label = "drawerPayScale",
    )
    Row(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(99.dp))
            .background(primary.copy(alpha = 0.10f))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClickLabel = "出示付款码",
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = HugeIcons.QrCodeScan,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "付款码",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = primary,
        )
    }
}

/** 一卡通余额说明（钱包详情弹层，2026-09-24 口径；旧生活页删掉后挪到侧栏）。 */
private const val BALANCE_NOTE =
    "一卡通余额与流水都来自一卡通平台，服务端落账有延迟，刚发生的消费或充值可能暂时不计入。"

/** 电费说明（钱包详情弹层，2026-09-24 用户给的原话；只有这一处口径）。 */
private const val ELECTRICITY_NOTE = "受服务器影响，实际费用可能有延迟，电费不足时请及时缴费"
