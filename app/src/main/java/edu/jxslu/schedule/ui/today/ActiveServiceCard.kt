package edu.jxslu.schedule.ui.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.data.qiekj.QiekjWateringSession
import edu.jxslu.schedule.data.ujing.UjingOrderSnapshot
import edu.jxslu.schedule.domain.EbikeFreeRide
import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.QzxyWatering
import edu.jxslu.schedule.domain.QzxyWateringFormat
import edu.jxslu.schedule.domain.UjingState
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardDefaults
import edu.jxslu.schedule.ui.common.AppCardDivider
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.ebike.KvcxRideState
import edu.jxslu.schedule.ui.ebike.unlockCooldownSecondsLeft
import edu.jxslu.schedule.ui.qzxy.isQzxyWateringExpired
import edu.jxslu.schedule.ui.qzxy.rememberQzxyWateringClock
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronRight

/**
 * 今日页「进行中服务」状态卡（2026-10-21）：快趣骑行 / 胖乖开水 / 趣智洗澡 / U净 洗烘
 * 四项服务谁在进行中谁占一行，一张卡平铺（`AppCardDivider` 分隔），全无则整卡不渲染。
 *
 * 数据源（各自已有的真相源，本卡零新增网络路径）：
 * - 快趣：`KvcxRideController.state`（Graph 单例，进页/下拉各静默查一次，不轮询）；
 * - 胖乖：`WaterViewModel.uiState.flow is Working`（进程内存态，进程重启即失——服务端
 *   会话状态会黏住不可信，不做伪恢复）；
 * - 趣智：`QzxyWateringStore.watering`（落盘），对账沿用今日页已有的 `WateringReconcileEffect`；
 * - U净：`UjingRepository.orderState`（落盘快照），对表沿用 10s TTL 节流。
 *
 * 动效：行的出现/消失走 `AnimatedVisibility`（进 220ms / 退 160ms，ui-common 口径），
 * 卡体 `animateContentSize` 收平高度；计时文案 `tnum` 等宽 + 每秒走（不做 crossfade，
 * 高频数值口径）；进行中行首一枚 900ms 呼吸圆点（同 `WateringBadge` 手法）。
 *
 * 快趣骑行中（明确未锁车）把**整张卡片的边缘**点亮（`AppCard.highlighted` +
 * `highlightedFill = false`：主色 1.5dp 描边、不抬底色，180ms 过渡）：卡内行保持平铺，
 * 别在行内再套描边 / 竖条——那是气泡套气泡（2026-10-22 用户口径）。临时锁车 / 锁状态未知不点亮。
 */
@Composable
internal fun ActiveServiceCard(
    /**
     * 快趣骑行状态（能力闸 + 登录双闸已在调用侧滤过）：null 或无在案订单 = 行不出现。
     */
    kvcxState: KvcxRideState?,
    /**
     * 账号方式骑行的免费窗口起点（`ebikeRideStartAt`）：给快趣行的倒计时 / 超时进度条用。
     * 小程序方式的同一起点走 [freeRideStartAt]（那一行优先）；两档由调用侧按能力档传。
     */
    kvcxFreeStartAt: Long? = null,
    /**
     * 微信小程序方式的免费时长计时起点（`ebikeRideStartAt`，DataStore）：
     * `isActive` 为 true = 小程序方式正在骑行（扫码起计时），展示倒计时行——
     * 小程序方式查不了在案订单（无账号 token），但计时是本机动作，状态照样能摆。
     */
    freeRideStartAt: Long?,
    /**
     * 胖乖「出水中」的落盘会话（`QiekjWateringStore`，2026-10-21）：跨窗口同步的真相源。
     * null = 没有进行中的开水；过期残留在 store 读取时已清。
     */
    waterSession: QiekjWateringSession?,
    /** 本窗口的开水流程是否正在「结束中」（置灰按钮 + 文案切换）。 */
    waterStopping: Boolean,
    /** 本窗口能否直接结束（流程在本 Activity 的 ViewModel 里跑；否则只能点行进页）。 */
    waterCanStop: Boolean,
    qzxyWatering: QzxyWatering?,
    /** 趣智对账已确认设备那边结束（转「待核对」，不点亮）。 */
    qzxyDeviceEnded: Boolean,
    ujingOrder: UjingOrderSnapshot?,
    /** 快趣动作回调（null = 行不渲染）。 */
    kvcxOnUnlock: (() -> Unit)?,
    kvcxOnLock: (() -> Unit)?,
    kvcxOnReturn: (() -> Unit)?,
    waterOnEnd: () -> Unit,
    qzxyOnStop: () -> Unit,
    /** 快趣行整行落点（骑行页：地图 / 完整骑行卡；行内按钮管动作，行本体管「去看详情」）。 */
    onOpenEbike: () -> Unit = {},
    /** 胖乖行整行落点（开水页：完整流程态 / 订单结果卡）。 */
    onOpenWater: () -> Unit = {},
    /** 趣智行整行落点（趣智页：用水状态卡 / 消费记录）。 */
    onOpenQzxy: () -> Unit = {},
    /** U净 行整行落点（U净 页：订单英雄卡 / 扫码）。 */
    onOpenUjing: () -> Unit,
    /** 小程序免费计时行的「结束计时」意图（确认弹窗在调用侧）。 */
    onEndFreeRide: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ride = kvcxState?.ride
    val kvcxVisible = ride != null && kvcxOnUnlock != null
    // 骑行中 = 明确未锁车（2026-10-22）：高亮**外层卡片边缘**（`AppCard.highlighted`），
    // 行内不再套描边 / 竖条——卡内画高亮只会变成气泡套气泡。临时锁车 / 锁状态未知不点亮。
    val kvcxRiding = kvcxVisible && kvcxOnLock != null && kvcxOnReturn != null && ride?.locked == false
    val qzxyVisible = qzxyWatering != null
    val ujingLive = ujingOrder?.takeIf { !it.isTerminal }
    // 秒级走时按行隔离（2026-10-22）：此前 `now` 读在卡顶层，免费计时 / 开水期间整卡
    // （含快趣行与按钮）每秒重组。现在时钟收进各自的 Slot，只有那一行每秒重组；
    // 到点由 Slot 回调父级收行（freeRideExpired / waterExpired），父级不再自己走秒。
    val freeRideExpired = remember(freeRideStartAt) { mutableStateOf(false) }
    val waterExpired = remember(waterSession?.startedAtMillis) { mutableStateOf(false) }
    val freeRideVisible = freeRideStartAt != null && !freeRideExpired.value &&
        EbikeFreeRide.isActive(freeRideStartAt, System.currentTimeMillis())
    // 胖乖：落盘会话未过期即「出水中」（store 已在读取时清掉过期残留；页面开着跨过
    // 5 分钟线时由 WaterSlot 的时钟回调把它收掉）
    val waterVisible = waterSession != null && !waterExpired.value &&
        !waterSession.isStale(System.currentTimeMillis())
    val anyActive = kvcxVisible || freeRideVisible || waterVisible || qzxyVisible || ujingLive != null

    // 整卡壳的显隐也走 AnimatedVisibility：**不能 early return**——组合一卸载，
    // 收拢动画就没有载体了（用户报的「状态卡片消失是瞬间消失的」正是它）。
    // 全部行退完，卡片再收平 + 淡出；第一行出现时反向展开。
    AnimatedVisibility(
        visible = anyActive,
        enter = EnterRow,
        exit = ExitRow,
        modifier = modifier,
    ) {
        AppCard(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            highlighted = kvcxRiding,
            // 只亮卡边、不抬底色：服务卡本身就在页面底色上，抬高底色会再叠出一层卡片
            highlightedFill = false,
        ) {
            AnimatedVisibility(
                visible = kvcxVisible,
                enter = EnterRow,
                exit = ExitRow,
            ) {
                if (ride != null && kvcxOnUnlock != null && kvcxOnLock != null && kvcxOnReturn != null) {
                    KvcxActiveRow(
                        ride = ride,
                        state = kvcxState ?: KvcxRideState(),
                        freeStartAt = kvcxFreeStartAt,
                        onUnlock = kvcxOnUnlock,
                        onLock = kvcxOnLock,
                        onReturn = kvcxOnReturn,
                        onOpen = onOpenEbike,
                    )
                }
            }
            // 免费时长行（小程序方式）：骑行行不出现时才轮到它（账号方式的骑行信息更全，
            // 两者不会同时成立——能力闸互斥；防御性写法不叠分隔线即可）
            val afterKvcx = kvcxVisible || freeRideVisible
            if (freeRideStartAt != null) {
                FreeRideSlot(
                    startAtMillis = freeRideStartAt,
                    onOpen = onOpenEbike,
                    onEndTimer = onEndFreeRide,
                    onExpired = { freeRideExpired.value = true },
                )
            }
            AnimatedDivider(visible = afterKvcx && (waterVisible || qzxyVisible || ujingLive != null))
            waterSession?.let { session ->
                WaterSlot(
                    session = session,
                    stopping = waterStopping,
                    canStop = waterCanStop,
                    onEnd = waterOnEnd,
                    onOpen = onOpenWater,
                    onExpired = { waterExpired.value = true },
                )
            }
            AnimatedDivider(visible = waterVisible && (qzxyVisible || ujingLive != null))
            AnimatedVisibility(
                visible = qzxyVisible,
                enter = EnterRow,
                exit = ExitRow,
            ) {
                qzxyWatering?.let { watering ->
                    QzxyActiveRow(
                        watering = watering,
                        deviceEnded = qzxyDeviceEnded,
                        onStop = qzxyOnStop,
                        onOpen = onOpenQzxy,
                    )
                }
            }
            AnimatedDivider(visible = qzxyVisible && ujingLive != null)
            AnimatedVisibility(
                visible = ujingLive != null,
                enter = EnterRow,
                exit = ExitRow,
            ) {
                ujingLive?.let { UjingActiveRow(order = it, onOpen = onOpenUjing) }
            }
        }
    }
}

/**
 * 免费计时行：**行内自己走秒**（每拍只重组这一行），到点回调 [onExpired] 让父级收行。
 * 父级因此不再持有秒级状态——开水 / 免费计时期间快趣行与按钮不跟着每秒重组。
 */
@Composable
private fun FreeRideSlot(
    startAtMillis: Long,
    onOpen: () -> Unit,
    onEndTimer: () -> Unit,
    onExpired: () -> Unit,
) {
    val now by rememberLiveSecond(startAtMillis) { EbikeFreeRide.isActive(startAtMillis, it) }
    val active = EbikeFreeRide.isActive(startAtMillis, now)
    LaunchedEffect(active) { if (!active) onExpired() }
    AnimatedVisibility(visible = active, enter = EnterRow, exit = ExitRow) {
        FreeRideRow(
            startAtMillis = startAtMillis,
            now = now,
            onOpen = onOpen,
            onEndTimer = onEndTimer,
        )
    }
}

/** 开水行：同上，秒级走时留在本行；超过 5 分钟（过期）回调 [onExpired] 撤行。 */
@Composable
private fun WaterSlot(
    session: QiekjWateringSession,
    stopping: Boolean,
    canStop: Boolean,
    onEnd: () -> Unit,
    onOpen: () -> Unit,
    onExpired: () -> Unit,
) {
    val now by rememberLiveSecond(session.startedAtMillis) { !session.isStale(it) }
    val active = !session.isStale(now)
    LaunchedEffect(active) { if (!active) onExpired() }
    AnimatedVisibility(visible = active, enter = EnterRow, exit = ExitRow) {
        WaterActiveRow(
            session = session,
            now = now,
            stopping = stopping,
            canStop = canStop,
            onEnd = onEnd,
            onOpen = onOpen,
        )
    }
}

/**
 * 每秒一拍，**只在 [stillActive] 为 true 时走**：到点落一次最终值后停表，不空转。
 * key 变化时先落当前值（`produceState` 的 initialValue 只在首次组合生效，重启必须显式赋值）。
 */
@Composable
private fun rememberLiveSecond(key: Any?, stillActive: (Long) -> Boolean): State<Long> =
    produceState(initialValue = System.currentTimeMillis(), key1 = key) {
        value = System.currentTimeMillis()
        while (stillActive(value)) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }

/** 卡内分隔线的显隐也走收拢/淡出：行在退场时分隔线同步收掉，不闪一截硬边。 */
@Composable
private fun AnimatedDivider(visible: Boolean) {
    AnimatedVisibility(visible = visible, enter = EnterRow, exit = ExitRow) {
        AppCardDivider()
    }
}

/** 行的进出场规格（ui-common 动效口径：进 220ms / 退 160ms，顶部展开收拢）。 */
private val EnterRow = fadeIn(tween(220)) +
    expandVertically(tween(220, easing = FastOutSlowInEasing), expandFrom = Alignment.Top)
private val ExitRow = fadeOut(tween(160)) +
    shrinkVertically(tween(160), shrinkTowards = Alignment.Top)

/** 进行中行首的呼吸圆点（`WateringBadge` 同手法：只改透明度，绘制阶段读值不重组）。 */
@Composable
private fun BreathingDot() {
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "active-service-dot")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "active-service-dot-alpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .graphicsLayer { alpha = dotAlpha }
            .background(scheme.primary, CircleShape),
    )
}

/** 品牌图标：与侧栏同款位图，24dp。**必须用 `Image`**——`Icon(painter)` 会把位图整体
 *  染成 LocalContentColor（真机上呈黑色块），侧栏同款位图就是用 `Image` 渲染的。 */
@Composable
private fun BrandIcon(resId: Int) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(resId),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
    )
}

/**
 * 单行骨架：呼吸点 + 品牌图标 + 两行文本（标题 + 副行）+ 右侧动作区。
 * 计时走秒由各自的副行文案负责；文本统一 `tnum` 等宽防抖。
 *
 * [pulsing] = 圆点是否呼吸（默认是）：「待核对」这类**已结束待处理**的行不呼吸——
 * 呼吸的含义是「正在进行中」，结束时呼吸反而是误导。
 *
 * 点击反馈（2026-10-22 用户报「矩形的丑陋灰色背景边框」）：不再用默认灰色涟漪——
 * 圆角裁切 + 按下时浅主色底、0.985 轻微回缩（与侧栏行 / 扫一扫同族），松手回弹。
 * 行的「骑行中」高亮**不在这里**：它归外层卡片边缘（`ActiveServiceCard` 的
 * `AppCard.highlighted`），行内保持平铺，与其它服务行同一轮廓。
 *
 * [status] = 标题后的状态词（如「骑行中」，主色 `labelSmall`）：骑行态不只靠卡边描边
 * 表达，也进无障碍朗读。null = 不显示。
 *
 * [progress] = 行底的时长进度条（0..1，可为 null = 不画）：快趣免费窗口倒计时 / 超时增长，
 * 开水、趣智、U净 的进行中时长。`progressError` 时走 error 色（超时）。
 */
@Composable
private fun ActiveRow(
    brandRes: Int,
    title: String,
    subtitle: String,
    trailing: @Composable RowScope.() -> Unit,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    pulsing: Boolean = true,
    status: String? = null,
    statusColor: Color? = null,
    progress: Float? = null,
    progressError: Boolean = false,
) {
    val haptics = rememberAppHaptics()
    val scheme = MaterialTheme.colorScheme
    val rowShape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        label = "serviceRowPress",
    )
    val containerColor by animateColorAsState(
        targetValue = if (pressed) scheme.primary.copy(alpha = 0.05f) else Color.Transparent,
        animationSpec = tween(AppCardDefaults.HighlightTransitionMillis),
        label = "serviceRowContainer",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(rowShape)
            .background(containerColor)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        // 默认涟漪是灰色矩形（用户报的就是它）；按下感由上面的底色 + 回缩表达
                        indication = null,
                        onClickLabel = onClickLabel ?: title,
                        onClick = {
                            haptics.tap()
                            onClick()
                        },
                    )
                } else {
                    Modifier
                },
            )
            .heightIn(min = 52.dp)
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pulsing) {
                BreathingDot()
                Spacer(Modifier.width(8.dp))
            } else {
                // 占位同尺寸的静态点（secondaryContainer 弱色）：布局不跳，语气降下来
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
            }
            BrandIcon(brandRes)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 标题先让位：状态词是固定宽度的短标签，不能被长标题挤成省略号
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (status != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = statusColor ?: MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 7.dp)
                    .height(4.dp),
                color = if (progressError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            )
        }
    }
}

/**
 * 快趣骑行行：副行 = 车号 · 已骑时长（本地走时）· 电量；右侧「开锁⇄锁车」+「还车」。
 * 行本体点击进骑行页（[onOpen]）——完整骑行卡 / 地图在那里；行内按钮管动作，行本体管「去看」。
 *
 * 锁态口径（与骑行页同源）：`locked == false` 显「锁车」（免确认）；`locked == true`
 * 显「开锁」；悬置态（开锁未确认 / 锁车确认中）显「确认中…」置灰。开锁后 3 秒冷却内
 * 锁车 / 还车置灰并显示剩余秒。还车与骑行页共用同一确认闸（调用方接 `kvcxConfirmDialog`）。
 */
@Composable
private fun KvcxActiveRow(
    ride: KqcxAuth.Ride,
    state: KvcxRideState,
    /** 免费窗口起点（`ebikeRideStartAt`）；null / ≤0 = 没有计时，不画进度条。 */
    freeStartAt: Long?,
    onUnlock: () -> Unit,
    onLock: () -> Unit,
    onReturn: () -> Unit,
    onOpen: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    val now by rememberResumedSecond(key = ride.carNum to state.fetchedAt)
    val elapsed = rideElapsedSecondsShared(ride, state.fetchedAt, now)
    val locked = ride.locked == true
    val pending = state.unlockPending || state.lockPending
    val busy = state.busy != null
    val cooldownLeft = unlockCooldownSecondsLeft(state.unlockCooldownUntil, now)

    // 免费窗口进度（2026-10-22）：15 分钟内倒计时条（与骑行页同一条口径），
    // 过点后切成 error 色的超时增长条；状态词报「超时 X 分钟」，进度条按 15 分钟一格
    // 增长（见 EbikeFreeRide.overtimeProgressFraction）。锁车后照挂：计费仍在继续。
    val freeStart = freeStartAt?.takeIf { it > 0L }
    val overtime = freeStart != null && !EbikeFreeRide.isActive(freeStart, now)
    val overtimeMinutes = if (overtime) {
        EbikeFreeRide.overtimeMinutes(freeStart, now).coerceAtLeast(1)
    } else {
        0
    }
    val freeProgress = when {
        freeStart == null -> null
        overtime -> EbikeFreeRide.overtimeProgressFraction(freeStart, now)
        else -> EbikeFreeRide.progressFraction(freeStart, now)
    }
    val status = when {
        overtime -> "超时 $overtimeMinutes 分钟"
        ride.locked == false && freeStart != null ->
            "骑行中 · " + EbikeFreeRide.formatRemaining(
                EbikeFreeRide.remainingSeconds(freeStart, now),
            )
        ride.locked == false -> "骑行中"
        else -> null
    }
    val statusColor = if (overtime) MaterialTheme.colorScheme.error else null

    ActiveRow(
        brandRes = edu.jxslu.schedule.R.drawable.ic_brand_kqcx,
        title = "快趣骑行",
        status = status,
        statusColor = statusColor,
        progress = freeProgress,
        progressError = overtime,
        onClick = onOpen,
        onClickLabel = "查看骑行详情",
        subtitle = buildString {
            append(ride.carNum)
            elapsed?.let { append(" · 已骑 ").append(KqcxAuth.formatRideDuration(it)) }
            ride.batteryPercent?.let { append(" · 电量 ").append(it).append("%") }
            // 临时锁车 ≠ 订单结束：不写清楚，用户会以为已经还了车（与骑行页状态胶囊同口径）
            if (ride.locked == true) append(" · 已锁车（计费继续）")
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 开锁 ⇄ 锁车（免确认的是锁车；开锁走重发解锁那条现成路）
                if (pending || busy) {
                    ActionChip(label = "确认中…", enabled = false, onClick = {})
                } else {
                    ActionChip(
                        label = if (locked) "开锁" else "锁车",
                        enabled = if (locked) true else cooldownLeft <= 0,
                        onClick = {
                            haptics.tap()
                            if (locked) onUnlock() else onLock()
                        },
                    )
                }
                Spacer(Modifier.width(6.dp))
                ActionChip(
                    label = if (cooldownLeft > 0) "还车 ${cooldownLeft}s" else "还车",
                    enabled = !busy && !pending && cooldownLeft <= 0,
                    filled = false,
                    onClick = {
                        haptics.tap()
                        onReturn()
                    },
                )
            }
        },
    )
}

/**
 * 快趣免费时长行（微信小程序方式，2026-10-21 补）：小程序方式查不了在案订单
 * （无账号 token），但「点扫一扫起计时」是本机动作——`ebikeRideStartAt` 落了盘，
 * 免费窗口内照样有状态可摆。副行 = 免费剩余倒计时（`remainingSeconds` 钳满格，
 * 起点缓冲期显 15:00 不动），点整行进骑行页（查车 / 图层仍可用）。
 *
 * 右侧「结束计时」（2026-10-21）：小程序里提前还车后本地倒计时会挂到 15 分钟
 * （幽灵倒计时），给一个显式收尾出口；调用方负责确认弹窗，这里只发意图。
 * 计时到点（`isActive` 为 false）行自动消失。
 */
@Composable
private fun FreeRideRow(
    startAtMillis: Long,
    now: Long,
    onOpen: () -> Unit,
    onEndTimer: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    ActiveRow(
        brandRes = edu.jxslu.schedule.R.drawable.ic_brand_kqcx,
        title = "快趣骑行 · 免费时长",
        subtitle = "剩余 " + EbikeFreeRide.formatRemaining(
            EbikeFreeRide.remainingSeconds(startAtMillis, now),
        ) + "（超时开始计费）",
        // 免费窗口倒计时条（与骑行页同口径：满格开始、随免费时间流干）
        progress = EbikeFreeRide.progressFraction(startAtMillis, now),
        onClick = onOpen,
        onClickLabel = "查看骑行页",
        trailing = {
            ActionChip(
                label = "结束计时",
                filled = false,
                onClick = {
                    haptics.tap()
                    onEndTimer()
                },
            )
        },
    )
}

/**
 * 胖乖开水行（2026-10-21 改为落盘会话驱动）：副行 = 设备 · 出水计时；右侧「结束开水」。
 * 行本体进开水页。耗时按会话起点本地走（同一轮出水只登记一次，不随步骤推进重置）。
 *
 * [canStop] = 开水流程在**本窗口**的 ViewModel 里跑：从侧栏面板/今日页发起时能在这儿结束；
 * 若流程在开水页（另一个 Activity 的 ViewModel）里跑，这里只展示与跳转——跨窗口结束
 * 需要那边的 ViewModel，硬做只会发一条注定落空的结束请求。
 */
@Composable
private fun WaterActiveRow(
    session: QiekjWateringSession,
    now: Long,
    stopping: Boolean,
    canStop: Boolean,
    onEnd: () -> Unit,
    onOpen: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    ActiveRow(
        brandRes = edu.jxslu.schedule.R.drawable.ic_brand_pangguai,
        title = when {
            stopping -> "胖乖生活 · 正在结束…"
            canStop -> "胖乖生活 · 出水中"
            else -> "胖乖生活 · 出水中（点按查看）"
        },
        onClick = onOpen,
        onClickLabel = "查看开水详情",
        subtitle = buildString {
            append(session.deviceName.ifBlank { "开水设备" })
            append(" · ")
            append(QzxyWateringFormat.duration((now - session.startedAtMillis).coerceAtLeast(0L)))
        },
        // 出水进度（与开水页同口径：165 秒自动关阀兜底）
        progress = session.progressFraction(now),
        trailing = {
            if (canStop) {
                ActionChip(label = "结束开水", enabled = !stopping, onClick = {
                    haptics.tap()
                    onEnd()
                })
            } else {
                androidx.compose.material3.Icon(
                    HugeIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    modifier = Modifier.size(16.dp),
                )
            }
        },
    )
}

/**
 * 趣智用水行：用水中（计时 + 结束用水）/ 待核对（超 1h 或对账确认设备结束）两形态，
 * 与 `WateringStatusBlock` 同一条判据；「待核对」仍给「结算核对」按钮（要去设备旁）。
 */
@Composable
private fun QzxyActiveRow(
    watering: QzxyWatering,
    deviceEnded: Boolean,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    val pending = deviceEnded || isQzxyWateringExpired(watering.startedAtMillis)
    // 只在「用水中」走秒：转「待核对」后停表，别让一行静态文案继续每秒重组
    val now by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = watering.startedAtMillis,
        key2 = pending,
    ) {
        value = System.currentTimeMillis()
        if (pending) return@produceState
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    ActiveRow(
        brandRes = edu.jxslu.schedule.R.drawable.ic_brand_qzxy,
        title = if (pending) "趣智校园 · 待核对" else "趣智校园 · 用水中",
        // 待核对 = 用水已结束：圆点不呼吸（呼吸只属于「正在进行中」）
        pulsing = !pending,
        onClick = onOpen,
        onClickLabel = "查看用水详情",
        subtitle = if (pending) {
            "开阀于 ${QzxyWateringFormat.clockText(watering.startedAtMillis)} · 预扣 " +
                QzxyWateringFormat.money(watering.preDeductMilli)
        } else {
            "${watering.deviceName.ifBlank { "热水器" }} · " + rememberQzxyWateringClock(watering.startedAtMillis)
        },
        // 用水时长按 1 小时记账窗口推（超过即转「待核对」，不再画进行中的条）
        progress = if (pending) {
            null
        } else {
            (watering.elapsedMillis(now).toFloat() / QzxyWatering.EXPIRE_MILLIS)
                .coerceIn(0f, 1f)
        },
        trailing = {
            ActionChip(label = if (pending) "结算核对" else "结束用水", onClick = {
                haptics.tap()
                onStop()
            })
        },
    )
}

/** U净 洗烘行：纯展示（写操作只在 U净 主页），点整行进 U净 页。 */
@Composable
private fun UjingActiveRow(order: UjingOrderSnapshot, onOpen: () -> Unit) {
    val running = order.status == "40"
    val now by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = order.snapshotAt,
        key2 = running,
    ) {
        while (running) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val liveRemain = if (running) {
        UjingState.liveRemainSeconds(order.status, order.remainSeconds, order.snapshotAt, now)
    } else {
        0
    }
    ActiveRow(
        brandRes = edu.jxslu.schedule.R.drawable.ic_brand_ujing,
        title = "U净 · " + UjingState.statusLabel(order.status, order.statusRemark),
        subtitle = buildString {
            append(order.deviceName.ifBlank { "洗衣机" })
            if (running && order.remainSeconds > 0) {
                append(" · 剩 ").append(UjingState.remainText(liveRemain))
            }
        },
        // 洗涤进度（与 U净 页英雄卡同口径：1 - 剩余/总时长；旧快照无时长则不画）
        progress = if (running && order.durationSeconds > 0) {
            (1f - liveRemain.toFloat() / order.durationSeconds).coerceIn(0f, 1f)
        } else {
            null
        },
        onClick = onOpen,
        onClickLabel = "查看洗衣订单",
        trailing = {
            androidx.compose.material3.Icon(
                HugeIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.size(16.dp),
            )
        },
    )
}

/** 行尾的紧凑动作钮（32dp 高，与 `PowerRoomPickerSheet` 的行内小钮同规格）。 */
@Composable
private fun ActionChip(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    filled: Boolean = true,
) {
    val shape = CircleShape
    val content: @Composable () -> Unit = {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.height(32.dp),
            shape = shape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            content = { content() },
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.height(32.dp),
            shape = shape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            content = { content() },
        )
    }
}

// ── 秒级走时的小工具（本文件内共用，避免各页已实现的 internal 直接跨包引用）──

/** 每秒一拍（RESUMED 才走）：key 变化时立即重置。 */
@Composable
private fun rememberResumedSecond(key: Any?): State<Long> =
    produceState(initialValue = System.currentTimeMillis(), key1 = key) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }

/** 骑行时长本地走时：与 `KvcxRideController.rideElapsedSeconds` 同一口径（不跨包引 internal）。 */
private fun rideElapsedSecondsShared(ride: KqcxAuth.Ride, fetchedAt: Long, now: Long): Long? {
    val base = ride.totalDateSeconds ?: return null
    if (fetchedAt <= 0L) return base
    return base + (now - fetchedAt).coerceAtLeast(0L) / 1_000L
}
