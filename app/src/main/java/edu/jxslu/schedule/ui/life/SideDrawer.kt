package edu.jxslu.schedule.ui.life

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.ujing.UjingHouseStore
import edu.jxslu.schedule.data.ujing.UjingRepository
import edu.jxslu.schedule.domain.AccountMask
import edu.jxslu.schedule.domain.ShortcutSettings
import edu.jxslu.schedule.domain.UjingState
import edu.jxslu.schedule.ui.campus.CampusCardViewModel
import edu.jxslu.schedule.ui.common.ShortcutLauncher
import edu.jxslu.schedule.ui.common.ShortcutPinner
import edu.jxslu.schedule.ui.common.ShortcutSheet
import edu.jxslu.schedule.ui.common.rememberResumeTick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

private enum class WalletDetail { Campus, Power }

/** 侧栏 U净 行右侧空闲状态的刷新节流：空闲看板本身约 30 秒滞后，刷更勤没意义。 */
private const val UJING_FREE_REFRESH_TTL_MILLIS = 30_000L

/**
 * 拉收藏洗衣房的洗衣机空闲聚合（侧栏 U净 行右侧的展示数）。
 * 多店求和（free / total），文案语义与 U净 页看板同源（[UjingState.BoardLine]）。
 * 未登录 / 没收藏店 / 没有任何成功数据 → null：右侧不显示状态，整行点击直接进页。
 */
private suspend fun loadUjingFreeText(
    repo: UjingRepository,
    houseStore: UjingHouseStore,
): String? {
    if (!repo.loggedIn.value) return null
    val houses = houseStore.list()
    if (houses.isEmpty()) return null
    // 并发上限与 U净 页看板同口径（4）：侧栏展开时一次拉全量收藏店
    val lines = coroutineScope {
        houses.map { house ->
            async {
                runCatching { repo.devicesReserve(house.storeId) }
                    .getOrNull()
                    ?.let { groups -> repo.boardLine(groups) }
            }
        }.awaitAll()
    }
    val valid = lines.filterNotNull()
    val free = valid.sumOf { it.free }
    val total = valid.sumOf { it.total }
    if (valid.isEmpty() || total <= 0) return null
    return if (free > 0) "空闲 $free/$total" else "暂无空闲"
}

/**
 * 左侧栏内容（2026-10-20 第二轮）。
 *
 * - 头部用「我的」页同一份身份数据（姓名 / 遮罩学号 / 班级），不再写死「水贝贝」；
 *   头像 + 姓名 + 班级整块可点，进学籍信息页（2026-10-21，入口从教务 Tab 挪来）；
 * - 校园卡与电费都是可点的余额行，详情弹层里放账单与充值；右侧一枚充值入口开选择弹层；
 * - 生活 / 学习 / 工具三段：删掉已迁去教务 Tab 的成绩/考试/学业/更多，补百宝箱/天气；
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
    val accountText = snapshot?.accountFen
        ?.let { "电子账户 ¥%.2f".format(it / 100.0) }
        ?: "电子账户 —"
    val powerText = when {
        power.loading -> "读取中…"
        power.noCredentials || power.roomNotBound -> "—"
        remain != null -> "%.1f 度".format(remain)
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
    LaunchedEffect(ujingLoggedIn, resumeTick, drawerVisible) {
        if (!drawerVisible) return@LaunchedEffect
        if (!ujingLoggedIn) {
            ujingFreeText = null
            return@LaunchedEffect
        }
        val now = System.currentTimeMillis()
        // 节流窗口内不重拉（resumeTick 驱动的回来刷新也走同一道闸）
        if (now - ujingFreeRefreshedAt < UJING_FREE_REFRESH_TTL_MILLIS) return@LaunchedEffect
        ujingFreeRefreshedAt = now
        ujingFreeLoading = true
        ujingFreeText = withContext(Dispatchers.IO) {
            loadUjingFreeText(ujingRepo, ujingHouseStore)
        }
        ujingFreeLoading = false
    }

    var showShortcutSheet by remember { mutableStateOf(false) }
    var showRechargeChoice by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<WalletDetail?>(null) }
    val shortcutSettings by remember(context) { Graph.repository(context).shortcutSettings }
        .collectAsStateWithLifecycle(initialValue = ShortcutSettings())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notice: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
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
                    accountText = accountText,
                    powerText = powerText,
                    onCampusDetail = { detail = WalletDetail.Campus },
                    onPowerDetail = { detail = WalletDetail.Power },
                    onRecharge = { showRechargeChoice = true },
                )

                DrawerSection("生活") {
                    DrawerRow(
                        label = "取快递",
                        icon = HugeIcons.Package01,
                        onClick = { showShortcutSheet = true },
                    )
                    DrawerRow("空调遥控", HugeIcons.RemoteControl, onClick = onOpenAcRemote)
                    DrawerRow("快趣出行", HugeIcons.ScooterElectric, onClick = onOpenRide)
                    // 三张卡（2026-10-21 用户口径）：**行本体直接进二级页**，右侧状态 / 余额
                    // 才开原来的入口弹窗——「看状态」与「进页操作」是两个动作，不再让
                    // 整行都落在弹窗上。U净 无登录 / 无收藏店时右侧没有状态可点，
                    // 弹窗入口随行内隐藏；数据侧同样等有数据才拉（见 ujingFreeText）。
                    DrawerRow(
                        label = "胖乖生活",
                        icon = HugeIcons.Wallet01,
                        trailing = waterBalance,
                        onClick = onOpenWater,
                        onTrailingClick = onOpenWaterSheet,
                    )
                    DrawerRow(
                        label = "趣智校园",
                        icon = HugeIcons.ShowerHead,
                        trailing = qzxyBalance,
                        onClick = onOpenQzxy,
                        onTrailingClick = onOpenQzxySheet,
                    )
                    DrawerRow(
                        label = "U净",
                        icon = HugeIcons.WashingMachine,
                        trailing = ujingFreeText,
                        trailingLoading = ujingFreeLoading,
                        onClick = onOpenUjing,
                        onTrailingClick = onOpenUjingSheet,
                    )
                    DrawerRow("问渠 · 志愿服务", HugeIcons.HeartHandshake, onClick = onOpenWenqu)
                }
                DrawerSection("学习") {
                    DrawerRow("笔记 · 课件", HugeIcons.Book01, onClick = onOpenNotes)
                    DrawerRow("作业", HugeIcons.Task01, onClick = onOpenHomework)
                }
                DrawerSection("工具") {
                    DrawerRow("百宝箱", HugeIcons.Toolbox, onClick = onOpenToolbox)
                    DrawerRow("天气", HugeIcons.Sun01, onClick = onOpenWeather)
                }
                Spacer(Modifier.height(8.dp))
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
            onOpenBill = {
                detail = null
                if (which == WalletDetail.Campus) onOpenStatement() else onOpenPowerBill()
            },
            onRecharge = {
                detail = null
                showRechargeChoice = true
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
    accountText: String,
    powerText: String,
    onCampusDetail: () -> Unit,
    onPowerDetail: () -> Unit,
    onRecharge: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 6.dp),
    ) {
        Text("校园卡余额", style = MaterialTheme.typography.labelSmall, color = muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = balanceText,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "drawerBalance",
                modifier = Modifier.weight(1f),
            ) { text ->
                Text(
                    text = text,
                    fontSize = if (text.startsWith("¥")) 34.sp else 20.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "校园卡详情", onClick = onCampusDetail)
                        .padding(vertical = 2.dp),
                )
            }
            // 与余额数字同一行、垂直居中：上一版挂在「校园卡余额」小标题那一行，看着没对齐。
            RechargeButton(onClick = onRecharge)
        }
        Text(
            text = accountText,
            style = MaterialTheme.typography.labelSmall,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClickLabel = "校园卡详情", onClick = onCampusDetail)
                .padding(vertical = 2.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClickLabel = "寝室电费详情", onClick = onPowerDetail)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("寝室电费", style = MaterialTheme.typography.labelSmall, color = muted)
            Spacer(Modifier.weight(1f))
            Text(
                text = powerText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = HugeIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                modifier = Modifier.size(14.dp),
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onOpenBill, modifier = Modifier.weight(1f)) { Text("账单") }
                Button(onClick = onRecharge, modifier = Modifier.weight(1f)) { Text("充值") }
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
                    onClick = onPickCampus,
                )
                RechargeTile(
                    title = "寝室电费充值",
                    subtitle = "缴费平台 · 房间电表",
                    icon = HugeIcons.Bolt,
                    modifier = Modifier.weight(1f),
                    onClick = onPickPower,
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
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 4.dp),
    )
    Column(content = content)
}

/**
 * 侧栏一行入口（2026-10-21 双命中区）：
 * - **行本体**（图标 + 名称 + 空白）→ [onClick]，进对应二级页；
 * - **右侧状态 / 余额**（[trailing]，有 [onTrailingClick] 才可点）→ 开入口弹窗。
 *   命中区加大（垂直 24dp + 水平 8dp），别让状态文案小到点不准；
 * - 状态字号从 labelSmall 提到 **titleSmall + SemiBold + 主色**：余额 / 空闲数是
 *   这行最该被看到的信息，也是可点的目标，视觉上要立得住；
 * - 最右一枚箭头（[trailing] 为 null 的行也带）统一「往右还有一层」的指向；
 *   侧栏宽 300dp，箭头与状态一起给右侧留出稳定收边。
 */
@Composable
private fun DrawerRow(
    label: String,
    icon: ImageVector,
    trailing: String? = null,
    trailingLoading: Boolean = false,
    onClick: () -> Unit,
    onTrailingClick: (() -> Unit)? = null,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = onSurface.copy(alpha = 0.78f)
    val trailingMuted = onSurface.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = label, onClick = onClick)
            .heightIn(min = 50.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = muted,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailingLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (trailing != null) {
            if (onTrailingClick != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "$label 状态", onClick = onTrailingClick)
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = trailing,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = HugeIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            } else {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // 尾箭头只在「没有自己的状态弹窗」的行上出现一次：可点状态的行把箭头
        // 合进状态区（见上），其余行统一收边（AccountRow / 电费行同款口径）
        if (onTrailingClick == null) {
            Icon(
                imageVector = HugeIcons.ChevronRight,
                contentDescription = null,
                tint = onSurface.copy(alpha = 0.3f),
                modifier = Modifier.size(16.dp),
            )
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
