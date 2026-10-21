package edu.jxslu.schedule.ui.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 */
@Composable
internal fun ActiveServiceCard(
    /**
     * 快趣骑行状态（能力闸 + 登录双闸已在调用侧滤过）：null 或无在案订单 = 行不出现。
     */
    kvcxState: KvcxRideState?,
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
    val qzxyVisible = qzxyWatering != null
    val ujingLive = ujingOrder?.takeIf { !it.isTerminal }
    // 秒级走时只在「免费计时 / 开水会话」窗口内跑：到点即停并落最终值——行随之收拢退场。
    // 此前在组合期算一次 `System.currentTimeMillis()`，父级不重组时计时到点行也不消失。
    val now by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = freeRideStartAt,
        key2 = waterSession?.startedAtMillis,
    ) {
        while (true) {
            val freeActive = freeRideStartAt?.let { EbikeFreeRide.isActive(it, value) } == true
            val waterActive = waterSession?.let { !it.isStale(value) } == true
            if (!freeActive && !waterActive) break
            delay(1_000)
            value = System.currentTimeMillis()
        }
        value = System.currentTimeMillis()
    }
    val freeRideVisible = freeRideStartAt != null && EbikeFreeRide.isActive(freeRideStartAt, now)
    // 胖乖：落盘会话未过期即「出水中」（store 已在读取时清掉过期残留；页面开着跨过
    // 5 分钟线时靠上面的秒级走时把它收掉）
    val waterVisible = waterSession != null && !waterSession.isStale(now)
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
        AppCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            AnimatedVisibility(
                visible = kvcxVisible,
                enter = EnterRow,
                exit = ExitRow,
            ) {
                if (ride != null && kvcxOnUnlock != null && kvcxOnLock != null && kvcxOnReturn != null) {
                    KvcxActiveRow(
                        ride = ride,
                        state = kvcxState ?: KvcxRideState(),
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
            AnimatedVisibility(
                visible = freeRideVisible,
                enter = EnterRow,
                exit = ExitRow,
            ) {
                freeRideStartAt?.takeIf { EbikeFreeRide.isActive(it, now) }?.let { startAt ->
                    FreeRideRow(startAtMillis = startAt, onOpen = onOpenEbike, onEndTimer = onEndFreeRide)
                }
            }
            AnimatedDivider(visible = afterKvcx && (waterVisible || qzxyVisible || ujingLive != null))
            AnimatedVisibility(
                visible = waterVisible,
                enter = EnterRow,
                exit = ExitRow,
            ) {
                waterSession?.takeIf { !it.isStale(now) }?.let { session ->
                    WaterActiveRow(
                        session = session,
                        now = now,
                        stopping = waterStopping,
                        canStop = waterCanStop,
                        onEnd = waterOnEnd,
                        onOpen = onOpenWater,
                    )
                }
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
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = onClickLabel ?: title) {
                        haptics.tap()
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 8.dp),
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
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

    ActiveRow(
        brandRes = edu.jxslu.schedule.R.drawable.ic_brand_kqcx,
        title = "快趣骑行",
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
    onOpen: () -> Unit,
    onEndTimer: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    val now by rememberResumedSecond(key = startAtMillis)
    ActiveRow(
        brandRes = edu.jxslu.schedule.R.drawable.ic_brand_kqcx,
        title = "快趣骑行 · 免费时长",
        subtitle = "剩余 " + EbikeFreeRide.formatRemaining(
            EbikeFreeRide.remainingSeconds(startAtMillis, now),
        ) + "（超时开始计费）",
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
    ActiveRow(
        brandRes = edu.jxslu.schedule.R.drawable.ic_brand_ujing,
        title = "U净 · " + UjingState.statusLabel(order.status, order.statusRemark),
        subtitle = buildString {
            append(order.deviceName.ifBlank { "洗衣机" })
            if (running && order.remainSeconds > 0) {
                append(" · 剩 ").append(UjingState.remainText(UjingState.liveRemainSeconds(order.status, order.remainSeconds, order.snapshotAt, now)))
            }
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
