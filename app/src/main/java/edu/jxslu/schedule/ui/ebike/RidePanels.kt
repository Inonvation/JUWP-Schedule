package edu.jxslu.schedule.ui.ebike

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import edu.jxslu.schedule.domain.BikeCluster
import edu.jxslu.schedule.domain.BikeNearby
import edu.jxslu.schedule.domain.EbikeCapabilities
import edu.jxslu.schedule.domain.EbikeFreeRide
import edu.jxslu.schedule.domain.EbikeQr
import edu.jxslu.schedule.domain.EbikeUseMode
import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.KvcxParkSpot
import edu.jxslu.schedule.domain.NearbyBike
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardDivider
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.ImeAwareModalBottomSheet
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.rememberSheetDismisser
import edu.jxslu.schedule.ui.theme.semanticColors
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.Filter
import me.rerere.hugeicons.stroke.Keyboard
import me.rerere.hugeicons.stroke.LockOpen
import me.rerere.hugeicons.stroke.MapPin
import me.rerere.hugeicons.stroke.MapsLocation02
import me.rerere.hugeicons.stroke.QrCode01
import me.rerere.hugeicons.stroke.QrCodeScan
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.ScooterElectric
import me.rerere.hugeicons.stroke.UserAccount
import kotlin.math.roundToInt

/**
 * 骑行页的**底部动作区**与各面板（DESIGN §3.9；2026-09-30 结构重构，2026-10-01 收口）。
 *
 * 全页只有两块可动的：
 *
 * - **车辆面板**（[RideBikePanel]）：把手 + 头行 + 列表，高度由用户拖，**只装列表**；
 * - **底部动作区**（[RideActionArea]）：高度由内容决定、不可拖，钉在面板**之外**的页面底部，
 *   与「车辆数据来自快趣接口…」那行免责说明同住一个常驻块。
 *
 * 动作区原来塞在定高面板的 `footer` 槽里，骑行态的仪表盘一长，最矮的档位就装不下
 * （把手 + 仪表盘 + 免责已经超过面板高度），底部按钮被面板圆角裁掉。高度是定值、内容是变量，
 * 这个组合迟早出事——所以动作区搬到面板之外，面板的高度全部归列表。
 *
 * 车号输入在 [RideCarNumberSheet]（出码与按车号用车共用的唯一容器）；「选中车辆」「选中停车点」
 * 都只是动作区的一个形态，不另起浮层。
 */

/** 出码位的边长（240dp 居中，DESIGN §3.9）：旧版整宽方形会把用码按钮顶出首屏。 */
internal val QR_PANEL_SIZE = 240.dp

/** 「更新于」超过这个时长就把时间标出来，提示数据可能已经不准。 */
private const val STALE_AFTER_MS = 90_000L

/** 时间戳每 15 秒重算一次，够用来把「新鲜」翻成「可能过期」。 */
private const val CLOCK_TICK_MS = 15_000L

/** 骑行态列出的还车点条数：再往下看也没人看，列表越短越干净。 */
private const val SPOT_LIST_LIMIT = 6

/** 停车点卡里最多列几辆车，多出来的走「看全部」。 */
private const val SPOT_PREVIEW_LIMIT = 3

/** 还车点标记的官方色相（DESIGN §3.9，与地图图层同一口径）。 */
private val SPOT_COLOR = Color(0xFFD7535D)

// ──────────────────────────── 悬浮动作区 ────────────────────────────

/**
 * 页面底部的**常驻动作区**（2026-09-30 结构，2026-10-01 搬到面板之外）。
 *
 * 用户口径一直是「跟说明文字那样固定在弹窗底部」，所以它和免责那行同住页面底部的常驻块
 * （`RideScreen` 里面板之下），不随列表滚动，也不浮在地图上。
 *
 * 形态随状态换：找车（默认 / 车辆卡）、骑行、结算。切换走上区升起、下区常驻那套
 * （见 [RideActionUpper] / [RideActionButtons] 的分工）。
 */
@Composable
internal fun RideActionArea(
    phase: RidePhase,
    caps: EbikeCapabilities,
    loggedIn: Boolean,
    state: BikeMapUiState,
    pickedCar: String?,
    ride: KqcxAuth.Ride?,
    rideFetchedAt: Long,
    busy: KvcxAction?,
    unlockPending: Boolean,
    timerActive: Boolean,
    timerStartAt: Long,
    timerCanEnd: Boolean,
    spotsHint: String?,
    summary: KvcxReturnSummary?,
    /** 小程序方式下"码已经生成好了"：只有这时才给「打开微信扫一扫」。 */
    hasCode: Boolean,
    onDismissPicked: () -> Unit,
    onWechatScan: () -> Unit,
    onScanBodyCode: () -> Unit,
    onOpenCarNumber: () -> Unit,
    onLogin: () -> Unit,
    onUnlock: (String) -> Unit,
    onGenerateForCar: (String) -> Unit,
    onFocusRide: () -> Unit,
    onTempLock: () -> Unit,
    onResume: () -> Unit,
    onReturn: () -> Unit,
    onRetryUnlock: () -> Unit,
    onRefreshRide: () -> Unit,
    onEndTimer: () -> Unit,
    onTimerExpired: () -> Unit,
    onSettle: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 动作条换内容时**走交叉淡入 + 高度过渡**：旧版是"啪"地换一块，
    // 眼睛每次都要重新找主动作落在哪，状态一多就显得毛躁。
    //
    // key 必须把**找车态的四种排版**都分开（2026-09-30 用户反馈）：出过码之后主动作会从
    // 「按车号出码」翻成「打开微信扫一扫」，只按 phase 分的话它会在 `when` 里硬切、
    // 一点过渡都没有。
    val barKey = when (phase) {
        RidePhase.Riding -> BAR_RIDING
        RidePhase.Settled -> BAR_SETTLED
        RidePhase.Finding -> when {
            // 车号也进 key：在车辆卡上直接换一辆车（点列表里另一行）时上区要重走一遍升起动画，
            // 不然只有文字原地换掉（2026-09-30 用户口径「切换车辆时也要有动画」）
            pickedCar != null -> BAR_CAR + ":" + pickedCar
            caps.wechatScan && hasCode -> BAR_FINDING_CODE
            caps.wechatScan -> BAR_FINDING_PLAIN
            !loggedIn -> BAR_FINDING_LOGIN
            else -> BAR_FINDING_SCAN
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        // 上区：**状态相关的额外内容**（车辆卡的头行与详情、骑行指标、结算明细…）。
        // 换状态时它像底部窗口那样**从下沿升起来**，而不是原地换一块
        // （2026-09-30 用户口径「做成底部窗口升起的过渡动画」）。
        AnimatedContent(
            targetState = barKey,
            transitionSpec = {
                // 进：从下沿升起，减速停住（FastOutSlowIn 是 Material 的标准缓动）；
                // 出：沉回去。两个方向都要有，只做进不出会像"啪"地换掉。
                val enter = slideInVertically(
                    animationSpec = tween<IntOffset>(BAR_RISE_MS, easing = FastOutSlowInEasing),
                ) { it } + fadeIn(animationSpec = tween(BAR_RISE_MS, easing = LinearOutSlowInEasing))
                val exit = slideOutVertically(
                    animationSpec = tween<IntOffset>(BAR_FALL_MS, easing = FastOutSlowInEasing),
                ) { it } + fadeOut(animationSpec = tween(BAR_FALL_MS))
                // 容器高度也走同一条缓动曲线（默认的弹簧会让收起来那一下显得很急）
                (enter togetherWith exit).using(
                    SizeTransform(clip = true) { _, _ ->
                        tween<IntSize>(BAR_RISE_MS, easing = FastOutSlowInEasing)
                    },
                )
            },
            label = "rideActionUpper",
            // 底部对齐：升起来的那块贴着按钮，而不是贴着头顶
            contentAlignment = Alignment.BottomStart,
            modifier = Modifier.fillMaxWidth(),
        ) { key ->
            // **进入这一档那一刻**的数据快照。AnimatedContent 会把退场的那一份继续组合
            // [BAR_FALL_MS]，而它读的是外层参数的最新值：还车时 `ride` 已经变 null、
            // 点「继续找车」时 `summary` 已经变 null，退场那一份于是当场画不出东西——
            // 看到的就是"内容先没了、那块区域再收回去"。按 key 记一份，退场时还有东西可画。
            //
            // 计时那两项同理：小程序方式「结束计时」一按，`timerActive` 立刻变 false，
            // 倒计时会在窗口滑走的路上先消失。
            val held = remember(key) {
                RideUpperHeld(
                    ride = ride,
                    summary = summary,
                    timerActive = timerActive,
                    timerStartAt = timerStartAt,
                )
            }
            RideActionUpper(
                key = key,
                caps = caps,
                state = state,
                ride = ride ?: held.ride,
                rideFetchedAt = rideFetchedAt,
                busy = busy,
                // 计时中用实时值（骑行中途换车会换起点）；退场那一份用快照把倒计时留住
                timerActive = timerActive || held.timerActive,
                timerStartAt = if (timerActive) timerStartAt else held.timerStartAt,
                spotsHint = spotsHint,
                summary = summary ?: held.summary,
                onDismissPicked = onDismissPicked,
                onGenerateForCar = onGenerateForCar,
                onRefreshRide = onRefreshRide,
                onFocusRide = onFocusRide,
                onTimerExpired = onTimerExpired,
            )
        }
        // 下区：**主动作**。它是常驻的一层，**不参与上面的动画**——找车态和车辆卡里
        // 它都是同一枚按钮、同一个位置，换状态时不该看见它跳（2026-09-30 用户口径）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RideActionButtons(
                key = barKey,
                caps = caps,
                ride = ride,
                busy = busy,
                unlockPending = unlockPending,
                timerCanEnd = timerCanEnd,
                summary = summary,
                onWechatScan = onWechatScan,
                onScanBodyCode = onScanBodyCode,
                onOpenCarNumber = onOpenCarNumber,
                onLogin = onLogin,
                onUnlock = onUnlock,
                onGenerateForCar = onGenerateForCar,
                onTempLock = onTempLock,
                onResume = onResume,
                onReturn = onReturn,
                onRetryUnlock = onRetryUnlock,
                onEndTimer = onEndTimer,
                onSettle = onSettle,
                onContinue = onContinue,
            )
        }
    }
}

/** 上区升起 / 收回的时长；下区不参与，所以只有这两个值在管"窗口升降"的手感。 */
private const val BAR_RISE_MS = 240
private const val BAR_FALL_MS = 190

/** 动作条状态之间做交叉淡入，别让内容"啪"地换一块。 */
private const val BAR_FADE_IN_MS = 200
private const val BAR_FADE_OUT_MS = 120

private const val BAR_FINDING_PLAIN = "finding-plain"
private const val BAR_FINDING_CODE = "finding-code"
private const val BAR_FINDING_LOGIN = "finding-login"
private const val BAR_FINDING_SCAN = "finding-scan"
/**
 * 车辆卡的 key 前缀，后面接完整车号（`car:100000398`）。
 *
 * 带上车号和带上找车态的四种排版，都是为了**让 AnimatedContent 认出这是一次状态切换**：
 * 只按 phase 分的话，换车与「出过码之后主动作翻面」都会在 `when` 里硬切，一点过渡都没有。
 */
private const val BAR_CAR = "car"
private const val BAR_RIDING = "riding"
private const val BAR_SETTLED = "settled"

/**
 * 退场动画期间要用的那一份数据（快照在 [RideActionArea] 里按 key 记）。
 *
 * 只放"状态换掉那一刻会被清空"的几项：其它参数（更新时刻、筛选开关）照旧读实时值，
 * 冻住它们会把骑行中的走时也一起冻掉。
 */
private data class RideUpperHeld(
    val ride: KqcxAuth.Ride?,
    val summary: KvcxReturnSummary?,
    val timerActive: Boolean,
    val timerStartAt: Long,
)

/** 车辆卡 key 里的车号（`car:100000398` → `100000398`）；不是车辆卡返回 null。 */
private fun carNumOf(key: String): String? {
    if (!key.startsWith(BAR_CAR)) return null
    return key.removePrefix(BAR_CAR).removePrefix(":").takeIf { it.isNotBlank() }
}

// ──────────────────────────── 动作区：上区 / 下区 ────────────────────────────

/**
 * **上区**：状态相关的额外内容，不含主动作。
 *
 * 换状态时它从下沿升起（见 [RideActionArea] 的 `AnimatedContent`）。找车态的四种排版
 * 都没有上区内容——那一档主动作自己就是全部。
 *
 * 车辆卡的车号**从 key 里取**，不从外层参数取：外层那份在换车那一刻已经是新车了，
 * 退场中的旧卡片会跟着变成新车，两层画同一张卡。
 */
@Composable
private fun RideActionUpper(
    key: String,
    caps: EbikeCapabilities,
    state: BikeMapUiState,
    ride: KqcxAuth.Ride?,
    rideFetchedAt: Long,
    busy: KvcxAction?,
    timerActive: Boolean,
    timerStartAt: Long,
    spotsHint: String?,
    summary: KvcxReturnSummary?,
    onDismissPicked: () -> Unit,
    onGenerateForCar: (String) -> Unit,
    onRefreshRide: () -> Unit,
    onFocusRide: () -> Unit,
    onTimerExpired: () -> Unit,
) {
    when {
        key == BAR_RIDING -> RideRidingUpper(
            ride = ride,
            rideFetchedAt = rideFetchedAt,
            busy = busy,
            timerActive = timerActive,
            timerStartAt = timerStartAt,
            spotsHint = spotsHint,
            onRefresh = onRefreshRide,
            onLocateCar = onFocusRide,
            onTimerExpired = onTimerExpired,
        )

        key == BAR_SETTLED -> summary?.let { RideSettledUpper(summary = it) }

        else -> carNumOf(key)?.let { car ->
            RideCarUpper(
                carNum = car,
                bike = state.clusters.asSequence().flatMap { it.bikes }
                    .firstOrNull { it.carNum == car },
                caps = caps,
                distanceFromUser = state.distanceFromUser,
                onDismiss = onDismissPicked,
                onGenerate = onGenerateForCar,
            )
        }
    }
}

/**
 * **下区**：主动作。这一层**常驻、不参与上区那套升起动画**——找车态和车辆卡里
 * 它是同一枚按钮、同一个位置，换状态时不该看见它跳（2026-09-30 用户口径）。
 */
@Composable
private fun RideActionButtons(
    key: String,
    caps: EbikeCapabilities,
    ride: KqcxAuth.Ride?,
    busy: KvcxAction?,
    unlockPending: Boolean,
    timerCanEnd: Boolean,
    summary: KvcxReturnSummary?,
    onWechatScan: () -> Unit,
    onScanBodyCode: () -> Unit,
    onOpenCarNumber: () -> Unit,
    onLogin: () -> Unit,
    onUnlock: (String) -> Unit,
    onGenerateForCar: (String) -> Unit,
    onTempLock: () -> Unit,
    onResume: () -> Unit,
    onReturn: () -> Unit,
    onRetryUnlock: () -> Unit,
    onEndTimer: () -> Unit,
    onSettle: () -> Unit,
    onContinue: () -> Unit,
) {
    val carNum = carNumOf(key)
    when {
        key == BAR_FINDING_PLAIN ->
            RideActionButton("按车号生成乘车码", onOpenCarNumber, icon = HugeIcons.QrCode01)

        key == BAR_FINDING_CODE ->
            RideActionButton("打开微信扫一扫", onWechatScan, icon = HugeIcons.QrCodeScan)

        key == BAR_FINDING_LOGIN ->
            RideActionButton("登录快趣账号", onLogin, icon = HugeIcons.UserAccount)

        key == BAR_FINDING_SCAN ->
            RideActionButton("扫车身码", onScanBodyCode, icon = HugeIcons.QrCodeScan)

        carNum != null -> when {
            caps.directUnlock -> RideActionButton(
                text = "开锁",
                onClick = { onUnlock(carNum) },
                icon = HugeIcons.LockOpen,
            )

            caps.wechatScan -> RideActionButton(
                text = "生成乘车码",
                onClick = { onGenerateForCar(carNum) },
                icon = HugeIcons.QrCode01,
            )

            else -> RideActionButton("登录后可开锁", onLogin, icon = HugeIcons.UserAccount)
        }

        key == BAR_RIDING -> if (ride != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    unlockPending -> RideActionButton(
                        text = if (busy == KvcxAction.RETRY_UNLOCK) "重试中…" else "重试开锁",
                        onClick = onRetryUnlock,
                        enabled = busy == null,
                        modifier = Modifier.weight(1f),
                        filled = false,
                    )

                    ride.locked == true -> RideActionButton(
                        text = if (busy == KvcxAction.RESUME) "解锁中…" else "解锁",
                        onClick = onResume,
                        enabled = busy == null,
                        modifier = Modifier.weight(1f),
                        filled = false,
                    )

                    else -> RideActionButton(
                        text = if (busy == KvcxAction.LOCK) "锁车中…" else "锁车",
                        onClick = onTempLock,
                        enabled = busy == null,
                        modifier = Modifier.weight(1f),
                        filled = false,
                    )
                }
                RideActionButton(
                    text = if (busy == KvcxAction.RETURN) "还车中…" else "还车",
                    onClick = onReturn,
                    enabled = busy == null,
                    modifier = Modifier.weight(1f),
                )
            }
        } else if (timerCanEnd) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEndTimer) { Text("结束计时") }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "开车 / 还车在微信小程序里完成",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
        }

        key == BAR_SETTLED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (summary?.needsSettle == true) {
                RideActionButton(
                    text = "去微信结清",
                    onClick = onSettle,
                    modifier = Modifier.weight(1f),
                    filled = false,
                )
            }
            RideActionButton(
                text = "继续找车",
                onClick = onContinue,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 车辆卡的**上区**：车号 + 详情 +（账号方式）出码的次级入口。
 *
 * 次级入口是**右对齐的一枚小文字按钮**，摆在主动作上方（2026-09-30 用户口径「整宽描边
 * 按钮太占高度」）；别把它做成与主动作同宽，也别挪到主动作下方（那会把主动作顶上去）。
 *
 * 详情那行与车辆行共用 [bikeInfoText]：同一辆车在列表和卡片里说法不同（尤其距离参照点）
 * 比少一行字更让人犯迷糊。
 */
@Composable
private fun RideCarUpper(
    carNum: String,
    bike: NearbyBike?,
    caps: EbikeCapabilities,
    /** 距离的参照点是用户位置（true）还是地图中心（false），与列表同一口径。 */
    distanceFromUser: Boolean,
    onDismiss: () -> Unit,
    onGenerate: (String) -> Unit,
) {
    val detailColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    val bikeInfo = bike?.let { bikeInfoText(it, detailColor, distanceFromUser) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                HugeIcons.ScooterElectric,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = "车 $carNum",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButtonSmall(HugeIcons.Cancel01, "收起车辆卡", onClick = onDismiss)
        }
        Text(
            text = if (bike != null && bikeInfo != null) {
                buildAnnotatedString {
                    append(bike.siteName.ifBlank { bike.campusName.ifBlank { "位置未知" } })
                    append(" · ")
                    append(bikeInfo)
                }
            } else {
                AnnotatedString("这辆车不在附近这批结果里；用「按车号」输入后点定位图标可以在图上找它")
            },
            style = MaterialTheme.typography.bodySmall,
            color = detailColor,
        )
        // 账号方式：支付分免押的账号开不了锁，只能出码去微信扫，所以这一枚要留着。
        // **做小**（2026-09-30 用户口径）：整宽描边按钮太占高度，改成右对齐的一枚小按钮，
        // 轻到不抢主动作的位置。
        if (caps.inAppRide) {
            RideTextAction(
                icon = HugeIcons.QrCode01,
                text = "生成乘车码",
                onClick = { onGenerate(carNum) },
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** 骑行态的**上区**：头行 + 倒计时 + 指标行。动作行在下区。 */
@Composable
private fun RideRidingUpper(
    ride: KqcxAuth.Ride?,
    rideFetchedAt: Long,
    busy: KvcxAction?,
    timerActive: Boolean,
    timerStartAt: Long,
    spotsHint: String?,
    onRefresh: () -> Unit,
    onLocateCar: () -> Unit,
    onTimerExpired: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                HugeIcons.ScooterElectric,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = ride?.let { "车 ${it.carNum}" } ?: "骑行计时中",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            ride?.locked?.let { locked ->
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (locked) "已锁" else "未锁",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (ride != null) {
                IconButtonSmall(
                    icon = HugeIcons.MapsLocation02,
                    label = "在地图上定位到车",
                    enabled = busy == null,
                    onClick = onLocateCar,
                )
                IconButtonSmall(
                    icon = HugeIcons.Refresh,
                    label = "刷新骑行状态",
                    enabled = busy == null,
                    onClick = onRefresh,
                )
            }
        }
        if (timerActive) {
            RideCountdown(startAt = timerStartAt, onExpired = onTimerExpired)
        }
        if (ride != null) {
            val elapsed = rememberRideElapsed(ride, rideFetchedAt)
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                LabeledValue(label = "已骑", value = elapsed ?: "--")
                ride.payMoneyCents?.takeIf { it > 0 }?.let { cents ->
                    LabeledValue(label = "当前费用", value = "¥%.2f".format(cents / 100.0))
                }
                ride.batteryPercent?.takeIf { it in 1..100 }?.let { percent ->
                    LabeledValue(label = "电量", value = "$percent%")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "更新于 ${clockText(rideFetchedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                LabeledValue(label = "计时起点", value = clockText(timerStartAt))
                LabeledValue(label = "免费时长", value = "15 分钟")
            }
        }
        // 还车点列表就在下面那块面板里，这里只在小程序方式没有图层可看时补一句说明
        if (spotsHint != null) {
            Text(
                text = spotsHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

/** 结算态的**上区**：时长 / 费用 / 结算状态。两个出路在下区。 */
@Composable
private fun RideSettledUpper(summary: KvcxReturnSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "已还车",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            summary.carNum?.let { car ->
                Text(
                    text = "车 $car",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            summary.durationText?.let { LabeledValue(label = "本次骑行", value = it) }
            summary.feeText?.let { LabeledValue(label = "费用", value = it) }
        }
        Text(
            text = summary.settleText,
            style = MaterialTheme.typography.bodySmall,
            color = if (summary.settleWarning) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            },
        )
        Text(
            text = "金额与结算以快趣小程序为准。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}

// ──────────────────────────── 车辆列表面板 ────────────────────────────

/**
 * 底部车辆面板（2026-09-30；2026-10-01 收口）：**可拖高度**的列表容器。
 *
 * 面板只装三样：拖动把手、头行（摘要 + 按车号 + 筛选 + 刷新）、列表。主动作条与免责那行
 * 在面板**之外**的页面底部常驻块里（`RideScreen`）——面板高度是定值、内容是变量，
 * 把动作条塞进来就是在赌它永远装得下（骑行态的仪表盘就是装不下的那个）。
 *
 * [showSpots] 由页面给：只有账号方式的骑行态才把列表换成还车点。小程序方式没有那一层
 * 图层，换成还车点只会得到一屏"没有数据"，不如让列表和地图上的车保持一致。
 *
 * 四态就地展示：还没查过（加载）/ 空（附「回到校区」出口）/ 失败（**保留上一次的列表**）/
 * 有数据。失败时不清空列表：拿用户刚看到的那批车配一条提示，比清成空白有用。
 */
@Composable
internal fun RideBikePanel(
    state: BikeMapUiState,
    caps: EbikeCapabilities,
    showSpots: Boolean,
    /** 头行要不要摆「按车号」（主动作已经是它的时候不摆，见头行那处注释）。 */
    showCarNumberEntry: Boolean,
    spots: List<KvcxParkSpot>,
    refLat: Double,
    refLng: Double,
    refFromUser: Boolean,
    panelHeight: Dp,
    onResize: (Float) -> Unit,
    onResizeFinished: () -> Unit,
    onClusterTap: (String) -> Unit,
    onPick: (String) -> Unit,
    onUnlock: (String) -> Unit,
    onGenerateForCar: (String) -> Unit,
    onResetToCampus: () -> Unit,
    onRefresh: () -> Unit,
    /** 头行那枚「按车号」：打开车号 / 出码面板。 */
    onOpenCarNumber: () -> Unit,
    onOpenFilter: () -> Unit,
    onSpotTap: (KvcxParkSpot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    // 点地图标记 / 识别定位要滚到对应分组卡片：记录每张卡在滚动内容里的 y 偏移
    val clusterOffsets = remember { mutableMapOf<String, Int>() }
    LaunchedEffect(state.focusNonce) {
        val key = state.focusKey ?: return@LaunchedEffect
        val y = clusterOffsets[key] ?: return@LaunchedEffect
        scrollState.animateScrollTo((y - 8).coerceAtLeast(0))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(panelHeight)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            // 导航栏那份内边距归页面底部的常驻块（动作条 + 免责说明），面板飘在它上面
            .background(MaterialTheme.colorScheme.surface),
    ) {
        PanelDragHandle(onResize = onResize, onResizeFinished = onResizeFinished)
        // 面板内容在「车辆列表 ↔ 还车点」之间换时淡入淡出：高度是定值，内容别硬切
        Crossfade(
            targetState = showSpots,
            animationSpec = tween(BAR_FADE_IN_MS),
            label = "panelContent",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { spotsMode ->
            if (spotsMode) {
                RideSpotsContent(
                    spots = spots,
                    refLat = refLat,
                    refLng = refLng,
                    refFromUser = refFromUser,
                    onSpotTap = onSpotTap,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            !state.queried -> "附近车辆"
                            // 两枚开关默认都开，这时只说「可用 N 辆」会漏掉"本校"这个前提
                            state.onlyAvailable && state.onlyOurCampus ->
                                "本校可用 ${state.bikeCount} 辆"
                            state.onlyAvailable -> "可用 ${state.bikeCount} 辆"
                            state.onlyOurCampus -> "本校 ${state.bikeCount} 辆"
                            else -> "附近 ${state.bikeCount} 辆"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = subtitleText(
                            state.queried,
                            state.loading,
                            state.completing,
                            state.updatedAtMillis,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                // 「按车号」摆在头行、带文字（2026-10-01）：旧版是地图左下角一枚没有文字的
                // 圆形图标，压在面板上沿、跟拖动把手抢位置。两种使用方式里它都是同一枚，
                // 只是小程序方式没出码时主动作本身就是"按车号生成乘车码"，那时不重复摆。
                if (showCarNumberEntry) {
                    RideTextAction(
                        icon = HugeIcons.Keyboard,
                        text = "按车号",
                        onClick = onOpenCarNumber,
                    )
                }
                // 筛选收成一枚图标、摆在刷新左边（2026-09-30 用户口径）：两枚开关常驻会吃掉
                // 面板一大截高度，而列表本身才是这块面板的主角
                RideFilterButton(
                    filtered = !state.onlyAvailable || !state.onlyOurCampus,
                    onClick = onOpenFilter,
                )
                if (state.loading) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .size(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    IconButtonSmall(
                        icon = HugeIcons.Refresh,
                        label = "刷新附近车辆",
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = onRefresh,
                    )
                }
            }
            // 失败提示留在列表上方：它要一直看得见，不被列表滚走
            state.failure?.let { failure ->
                InlineNoticeRow(
                    message = BikeMapViewModel.failureText(failure),
                    tone = NoticeTone.Warning,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    !state.queried -> LoadingHint(
                        title = "正在查附近的车",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                    )

                    // 空态走公共的 EmptyHint：标题 + 正文 + 一个出口，与其它页的空态同一套观感
                    state.clusters.isEmpty() -> EmptyHint(
                        title = "附近没有车",
                        body = when {
                            state.onlyOurCampus && state.onlyAvailable ->
                                "这一带没有可用的本校车，可关掉「只看本校」或「只看可用」看看全部"
                            state.onlyOurCampus -> "这一带没有本校的车，关掉「只看本校」看看全部"
                            state.onlyAvailable -> "这一带没有可用的车，关掉「只看可用」看看全部"
                            else -> "这一带暂时没有车，把地图拖到别处再看看"
                        },
                        actionLabel = "回到校区",
                        onAction = onResetToCampus,
                    )

                    else -> state.clusters.forEach { cluster ->
                        RideClusterCard(
                            cluster = cluster,
                            expanded = cluster.key == state.expandedKey,
                            distanceFromUser = state.distanceFromUser,
                            caps = caps,
                            onClick = { onClusterTap(cluster.key) },
                            onPick = onPick,
                            onUnlock = onUnlock,
                            onGenerateForCar = onGenerateForCar,
                            modifier = Modifier.onGloballyPositioned { coords ->
                                clusterOffsets[cluster.key] = coords.positionInParent().y.roundToInt()
                            },
                        )
                    }
                }
            }
                }
            }
        }
    }
}

/**
 * 面板头行的筛选图标（2026-09-30）：两枚开关收进弹层，图标本身只占一个按钮位。
 *
 * 尺寸与头行其它控件一致（40dp）：它旁边就是「按车号」与刷新，三个按钮高低不齐最显眼。
 *
 * [filtered] = 有开关被关掉（偏离默认）。默认两枚都开，所以"被关掉"才是需要提示的状态：
 * 图标加一枚小点，用户一眼知道列表里混着平时会被筛掉的车。
 */
@Composable
private fun RideFilterButton(filtered: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(
                HugeIcons.Filter,
                contentDescription = if (filtered) "筛选（有开关已关掉）" else "筛选",
                tint = if (filtered) scheme.primary else scheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(19.dp),
            )
        }
        if (filtered) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 9.dp, end = 9.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(scheme.primary),
            )
        }
    }
}

/**
 * 筛选弹层：两枚开关收进一个入口，面板头行只留摘要 + 按车号 + 筛选 + 刷新。
 *
 * 数据已在手上，改动只重算簇与图层、不重查接口（DESIGN §3.9）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RideFilterSheet(
    onlyOurCampus: Boolean,
    onlyAvailable: Boolean,
    onToggleOnlyOurCampus: () -> Unit,
    onToggleOnlyAvailable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 把手用 M3 默认那根（全项目一致）；顶距交给它自带的那一段，自己不再垫
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "筛选附近车辆",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            SettingSwitchRow(
                title = "只看本校",
                subtitle = "快趣同时服务隔壁江西师大，不筛会把师大校园的车也画进来；" +
                    "同时也筛还车点（禁停区不筛，那是安全提示）",
                checked = onlyOurCampus,
                onCheckedChange = { onToggleOnlyOurCampus() },
            )
            SettingSwitchRow(
                title = "只看可用",
                subtitle = "过滤离线与电量低于运营方阈值的车",
                checked = onlyAvailable,
                onCheckedChange = { onToggleOnlyAvailable() },
            )
            Text(
                text = "两个开关都会记住；改动只重算列表与图层，不重新请求接口。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

/** 分组卡片：收起时一行，展开后在其下逐个列出该停车点的车。 */
@Composable
private fun RideClusterCard(
    cluster: BikeCluster,
    expanded: Boolean,
    distanceFromUser: Boolean,
    caps: EbikeCapabilities,
    onClick: () -> Unit,
    onPick: (String) -> Unit,
    onUnlock: (String) -> Unit,
    onGenerateForCar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberAppHaptics()
    // **一张卡**装下整个停车点：头行 + 展开后的车辆行，行与行之间用发丝线分。
    // 旧版是"头一张卡 + 每辆车各一张卡"，展开后一屏能摞出七八个描边圆角，
    // 从属关系全靠缩进暗示（用户 2026-09-30 反馈「好丑」）。
    AppCard(
        modifier = modifier.fillMaxWidth(),
        highlighted = expanded,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = if (expanded) "收起该停车点" else "展开该停车点") {
                        haptics.tap()
                        onClick()
                    }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    HugeIcons.ScooterElectric,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cluster.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = clusterMetaText(cluster, distanceFromUser),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 箭头转到朝下表示展开，转场比直接换图标顺眼
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 90f else 0f,
                    label = "clusterChevron",
                )
                Icon(
                    HugeIcons.ChevronRight,
                    contentDescription = null,
                    tint = scheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = rotation },
                )
            }
            if (expanded) {
                cluster.bikes.forEach { bike ->
                    // 卡内分隔线的规格在 AppCardDivider 里（0.6 透明度），别在这写死一份
                    AppCardDivider(modifier = Modifier.padding(start = 14.dp))
                    RideBikeRow(
                        bike = bike,
                        distanceFromUser = distanceFromUser,
                        // 账号方式已登录才给「开锁」：那是写操作，有计费后果；其余给「生成乘车码」
                        canUnlock = caps.directUnlock,
                        onClick = { onPick(bike.carNum) },
                        onUnlock = { onUnlock(bike.carNum) },
                        onGenerate = { onGenerateForCar(bike.carNum) },
                    )
                }
            }
        }
    }
}

/**
 * 车辆行：**整行点击 = 选中这辆车**（动作条换成车辆卡），行尾直接给生成乘车码 / 开锁。
 *
 * 它不再是独立的描边卡（那是"卡片摞卡片"的来源）：行属于上面那张停车点卡，
 * 与相邻行之间用发丝线分，缩进对齐头行的文字。
 *
 * 行尾那枚动作是 2026-09-30 用户口径加上来的：行的信息（车号 / 状态 / 电量 / 距离）
 * 和车辆卡里那份差不多，那就没必要非得先点开卡片才能出码——**能力直接放在行上**。
 * 账号方式已登录给「开锁」，其余给「生成乘车码」（没登录也能生成一张去微信扫）。
 *
 * 术语按 DESIGN §3.9 收口：界面里不再出现"出码"这个说法。
 */
@Composable
private fun RideBikeRow(
    bike: NearbyBike,
    distanceFromUser: Boolean,
    canUnlock: Boolean,
    onClick: () -> Unit,
    onUnlock: () -> Unit,
    onGenerate: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "选中车 ${bike.carNum}") {
                haptics.tap()
                onClick()
            }
            // 缩进到停车点名那一列（14 + 图标 18 + 间距 10）：车号与它属于同一个点这件事
            // 一眼看得出来，而不是和图标平级（2026-09-30）
            .padding(start = 42.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = bike.carNum,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (bike.batteryLowBadge) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "电量低",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.semanticColors.warning,
                    )
                }
            }
            Text(
                text = bikeInfoText(bike, scheme.onSurface.copy(alpha = 0.6f), distanceFromUser),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = if (canUnlock) onUnlock else onGenerate,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Text(
                text = if (canUnlock) "开锁" else "生成乘车码",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ──────────────────────────── 车号 / 出码面板 ────────────────────────────

/**
 * 车号面板（2026-09-30；2026-10-01 收口）：**生成乘车码与按车号用车共用的唯一容器**。
 *
 * 输入、最近车号、码、用码动作都在一个面板里走完。
 *
 * 两档的差别只有"主动作是什么"：小程序方式是「生成乘车码」，账号方式是「开锁」。
 * **码区那一段是同一份**（含保存 / 去微信那两个用码按钮）——旧版把整段按档复制了一遍，
 * 结果同一个位置两档长得不一样：小程序方式是常占位的空框 + 常显置灰的按钮，
 * 账号方式是整块条件渲染，码出来时把下方内容顶一下。现在统一成前者。
 *
 * 含输入框，所以走 [ImeAwareModalBottomSheet]（键盘与退场时序只有那一份实现）；
 * 关面板统一走它的 `pendingDismiss`，退场动画跑完再落状态、再执行动作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RideCarNumberSheet(
    caps: EbikeCapabilities,
    carInput: String,
    inputError: String?,
    /** 最近生成过的那**一个**车号（完整车号）；null = 还没有过。 */
    lastRecent: String?,
    resolvedCarNum: String?,
    /** 想要哪辆车出码；与 [qrCarNum] 对齐且位图非空时才算"码已就绪"。 */
    wantedCarNum: String?,
    qrCarNum: String?,
    qrBitmap: Bitmap?,
    qrSaved: Boolean,
    onCarInput: (String) -> Unit,
    onPickRecent: () -> Unit,
    onScanBodyCode: (() -> Unit)?,
    onLocateCar: () -> Unit,
    onGenerate: () -> Unit,
    onSave: () -> Unit,
    onWechatScan: () -> Unit,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    val mini = caps.wechatScan
    // 生成是异步的（zxing 渲染在位图线程）：按车号对齐，码真出来了才铺进框里
    val ready = wantedCarNum != null && qrCarNum == wantedCarNum && qrBitmap != null
    /** 手里这张码**就是当前输入这辆车的**——这时再摆一枚「生成乘车码」就是重复。 */
    val upToDate = ready && resolvedCarNum != null && resolvedCarNum == qrCarNum
    /**
     * 关面板 / 关掉再做一件事（去微信、开锁）都走这条请求：交给弹层先播退场动画、
     * 再落状态、最后执行动作（[ImeAwareModalBottomSheet] 的 `pendingDismiss`）。
     */
    var closeAfter by remember { mutableStateOf<(() -> Unit)?>(null) }
    val requestClose: (after: () -> Unit) -> Unit = { after ->
        // 只认第一次：退场那 300ms 里内容还点得动，重复触发会把「打开微信」跑两遍
        if (closeAfter == null) closeAfter = after
    }

    // 把手用 M3 默认那根：本页另外三个弹层与全项目其余弹层都是它，观感统一；
    // 内容顶距交给把手自带的那一段，自己不再垫。
    // 面板内容**可滚**：输入框在上、码区 240dp 在下，小屏上本来就不可能一屏放下
    // （键盘起来更不可能），不滚成了"用码按钮永远够不着"。这一处的取舍与充值弹层
    // 「内容固定不可滚」不同，那边的问题是滚到底滚不到位。
    ImeAwareModalBottomSheet(onDismiss = onDismiss, pendingDismiss = closeAfter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (mini) "按车号生成乘车码" else "按车号用车",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                // 尺寸不写死：M3 的 IconButton 自己保证 48dp 触摸区（旧版压到 32dp）
                IconButton(onClick = { requestClose {} }) {
                    Icon(
                        HugeIcons.Cancel01,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                text = if (mini) {
                    "车身上的码看不清时，用这里生成一张，存进相册后在微信「扫一扫 → 相册」选图。"
                } else {
                    "输入完整车号或尾部 3 位，确认后回到页面开锁。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            RideCarField(
                value = carInput,
                onValueChange = onCarInput,
                isError = inputError != null,
                canLocate = resolvedCarNum != null,
                onLocateCar = onLocateCar,
                onScanBodyCode = onScanBodyCode,
                lastRecent = lastRecent,
                onPickRecent = onPickRecent,
            )
            inputError?.let { InlineNoticeRow(message = it, tone = NoticeTone.Warning) }

            if (mini) {
                // 生成按钮：**当前这辆车的码已经出来了就不再摆**（2026-09-30 用户口径
                // 「弹窗里怎么还有个生成乘车码的按钮」）——面板标题就是「按车号出码」，
                // 里面再放一枚同名按钮是重复。改了车号它自己回来，不必留常驻位。
                AnimatedVisibility(
                    visible = !upToDate,
                    enter = fadeIn(animationSpec = tween(BAR_FADE_IN_MS)) +
                        expandVertically(expandFrom = Alignment.Top),
                    exit = fadeOut(animationSpec = tween(BAR_FADE_OUT_MS)) +
                        shrinkVertically(shrinkTowards = Alignment.Top),
                ) {
                    RideActionButton(
                        text = "生成乘车码",
                        onClick = onGenerate,
                        icon = HugeIcons.QrCode01,
                        enabled = resolvedCarNum != null,
                    )
                }
            } else {
                RideActionButton(
                    text = if (resolvedCarNum != null) "开锁 · 车 $resolvedCarNum" else "开锁",
                    onClick = { requestClose { onUnlock() } },
                    icon = HugeIcons.LockOpen,
                    enabled = resolvedCarNum != null,
                )
                // 生成乘车码这条路**账号方式也要留着**：支付分免押的账号开不了锁，生成一张
                // 去微信扫是它唯一的出路，不能只藏在车辆卡里那枚小按钮上。
                // 同样地，**当前这辆车的码已经出来了就不摆**——面板里已经铺着那张码了。
                AnimatedVisibility(
                    visible = !upToDate,
                    // 对齐挂在 AnimatedVisibility 上（它才是 Column 的直接子节点）；
                    // 挂到里面的按钮上会落到 AnimatedVisibilityScope，位置就散了
                    modifier = Modifier.align(Alignment.End),
                    enter = fadeIn(animationSpec = tween(BAR_FADE_IN_MS)),
                    exit = fadeOut(animationSpec = tween(BAR_FADE_OUT_MS)),
                ) {
                    RideTextAction(
                        icon = HugeIcons.QrCode01,
                        text = "生成乘车码",
                        onClick = onGenerate,
                        enabled = resolvedCarNum != null,
                    )
                }
            }

            // ── 码区：两档共用同一份 ──
            // 未出码时是**常占位的空框**，两个用码按钮常显置灰（DESIGN §3.9：按钮行不做条件
            // 渲染，整行出现或消失会顶动下方内容）。
            AnimatedContent(
                targetState = ready,
                transitionSpec = {
                    fadeIn(animationSpec = tween(BAR_FADE_IN_MS)) togetherWith
                        fadeOut(animationSpec = tween(BAR_FADE_OUT_MS))
                },
                label = "qrPanel",
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) { isReady ->
                val bitmap = if (isReady) qrBitmap else null
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "乘车码 · $qrCarNum",
                        modifier = Modifier.size(QR_PANEL_SIZE),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(QR_PANEL_SIZE)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(14.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                HugeIcons.QrCode01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(28.dp),
                            )
                            Text(
                                text = "还没有生成乘车码",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = ready,
                enter = fadeIn(animationSpec = tween(BAR_FADE_IN_MS)),
                exit = fadeOut(animationSpec = tween(BAR_FADE_OUT_MS)),
            ) {
                Text(
                    text = "车 $qrCarNum",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 用码按钮常显、等宽并排
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RideActionButton(
                    text = "保存到相册",
                    onClick = onSave,
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                    filled = false,
                )
                RideActionButton(
                    text = "打开微信扫一扫",
                    onClick = { requestClose { onWechatScan() } },
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = if (qrSaved) {
                    "已存入相册；在微信里点「扫一扫 → 相册」选这张图即可开车。"
                } else {
                    "点「打开微信扫一扫」会先存进相册，再从微信「相册」选图。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            if (!mini) {
                Text(
                    text = "开锁即按快趣规则计费。若账号为微信支付分免押，开锁时需跳微信完成授权" +
                        "（微信限制，App 无法代做）；联系快趣客服可关闭该授权。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
    }
}

/**
 * 车号输入行：车身号 +（账号方式）扫车身码 + 定位。
 *
 * 一条输入同时接受「1~3 位尾部」与「6~12 位完整车号」，口径全在 [EbikeQr.resolveCarNum]。
 * 前缀 `100000` 用 outline 灰：默认色读起来像"已经帮填好了"，置灰后一眼可辨是提示。
 */
@Composable
private fun RideCarField(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    canLocate: Boolean,
    onLocateCar: () -> Unit,
    onScanBodyCode: (() -> Unit)?,
    /** 最近生成过的那一个车号；null = 没有，不占位。 */
    lastRecent: String?,
    onPickRecent: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val prefixText = EbikeQr.inputPrefix(value)
    val shape = RoundedCornerShape(12.dp)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, if (isError) scheme.error else scheme.outlineVariant, shape)
                .background(scheme.surface)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "车身号",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (prefixText.isNotEmpty()) {
                        Text(
                            text = prefixText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.outline,
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                            cursorBrush = SolidColor(scheme.primary),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (value.isEmpty()) {
                            Text(
                                text = "669",
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.outline,
                            )
                        }
                    }
                }
            }
            if (onScanBodyCode != null) {
                IconButtonSmall(
                    icon = HugeIcons.QrCodeScan,
                    label = "扫车身码",
                    tint = scheme.primary,
                    onClick = onScanBodyCode,
                )
            }
            if (canLocate) {
                IconButtonSmall(
                    icon = HugeIcons.MapsLocation02,
                    label = "在地图上定位这辆车",
                    tint = scheme.primary,
                    onClick = onLocateCar,
                )
            }
            // 最近生成的那一个车号收在**输入框内部右侧**（2026-09-30 用户口径）：
            // 它就是一个"再出一张"的快捷方式，不该单独占一行、更不该列一排。
            if (lastRecent != null) {
                RideRecentChip(
                    label = "上次 " + EbikeQr.chipLabel(lastRecent),
                    selected = EbikeQr.isCurrentInput(value, lastRecent),
                    onClick = onPickRecent,
                )
            }
        }
        // 说明只在空输入时占位：填了车号就不用再念一遍规则
        if (value.isEmpty()) {
            Text(
                text = EbikeQr.INPUT_HINT,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.outline,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
}

/** 最近车号气泡：回填并直接出码（口径同旧版 chip）。 */
@Composable
private fun RideRecentChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            // 竖向内边距 8dp：气泡本身约 32dp 高，够点（旧版 5dp 只有 26dp）
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

// ──────────────────────────── 还车点（面板内容） ────────────────────────────

/**
 * 骑行态的面板内容：附近还车点按距离列最近的几个。
 *
 * 骑行中用户要找的是"停哪儿"，车辆列表在那一刻没有用（地图也改画还车点了）。
 */
@Composable
private fun RideSpotsContent(
    spots: List<KvcxParkSpot>,
    refLat: Double,
    refLng: Double,
    refFromUser: Boolean,
    onSpotTap: (KvcxParkSpot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nearest = remember(spots, refLat, refLng) {
        spots
            .map { it to BikeNearby.distanceMeters(refLat, refLng, it.lat, it.lng).roundToInt() }
            .sortedBy { it.second }
            .take(SPOT_LIST_LIMIT)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            // 导航栏那份内边距归页面底部的常驻块，这里再垫一份就是双倍留白
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "附近还车点",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (refFromUser) {
                "按距你排序，点一条把镜头对准它。"
            } else {
                "按距校区中心排序（还没定位），点一条把镜头对准它。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        if (nearest.isEmpty()) {
            Text(
                text = "这一带没有还车点数据；可以点地图上的「回到校区」换一片看看。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        } else {
            nearest.forEach { (spot, meters) ->
                AppCardRow(
                    onClick = { onSpotTap(spot) },
                    onClickLabel = "把镜头对准这个还车点",
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        HugeIcons.MapPin,
                        contentDescription = null,
                        tint = SPOT_COLOR,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "还车点 · 距${if (refFromUser) "你" else "中心"} " +
                            BikeNearby.formatDistance(meters),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        HugeIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

// ──────────────────────────── 小件 ────────────────────────────

/**
 * 面板顶上的拖动把手：捏住上下拖，改面板高度（地图跟着让位）。
 *
 * 用 [detectVerticalDragGestures] 而不是 `draggable`：这里只需要垂直方向，且要 1:1 跟手，
 * 不做吸附动画——拖动过程中任何动画都会让地图跟着抖。松手才落盘。
 */
@Composable
internal fun PanelDragHandle(onResize: (Float) -> Unit, onResizeFinished: () -> Unit) {
    // 回调走 rememberUpdatedState 再进 pointerInput：`pointerInput(Unit)` 的块只跑一次，
    // 闭包里捕获的会是首次组合那版的 lambda（它的 maxPanelDp 是首帧窗口高度算出来的）。
    val resize by rememberUpdatedState(onResize)
    val finished by rememberUpdatedState(onResizeFinished)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { finished() },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        resize(dragAmount)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(38.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)),
        )
    }
}

/**
 * 骑行页的**动作按钮（唯一规格）**：整行、46dp 高、胶囊形，主次只差实心与描边。
 *
 * 旧版是三套并存：找车态用自定义的 46dp 胶囊、骑行与结算用 M3 默认的 40dp、码面板里
 * 那两个用码按钮写死 48dp。同一屏里主动作换个状态就换高度、换字号，看着像没做完。
 * 46dp 是从 52 收下来的（2026-09-30），仍在拇指舒适区内。
 *
 * 按钮本身**常驻不动**，只有图标与文字交叉淡入：换状态时不该看见按钮跳。
 */
@Composable
private fun RideActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    /** true = 实心（主动作）；false = 描边（次动作，或者与主动作并排的另一半）。 */
    filled: Boolean = true,
) {
    val shape = RoundedCornerShape(23.dp)
    val size = modifier
        .fillMaxWidth()
        .height(46.dp)
    val label: @Composable () -> Unit = {
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                fadeIn(animationSpec = tween(BAR_FADE_IN_MS)) togetherWith
                    fadeOut(animationSpec = tween(BAR_FADE_OUT_MS))
            },
            label = "actionLabel",
        ) { current ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
                }
                Text(
                    text = current,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = size,
            shape = shape,
            content = { label() },
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = size,
            shape = shape,
            content = { label() },
        )
    }
}

/**
 * 卡内 / 面板里的**次级文字动作**（带图标，右对齐一枚）。
 *
 * 车辆卡的「生成乘车码」与车号面板里同一枚曾各写一份；现在只有这一处规格。
 * 高度交给 M3 默认的最小高度（40dp），不抢主动作的位置。
 */
@Composable
private fun RideTextAction(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 卡内「小标签 + 数值」一对（已骑 / 当前费用 / 还车结算）。 */
@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum", // 等宽数字：每秒跳动不抖
            ),
        )
    }
}

@Composable
private fun IconButtonSmall(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    // 40dp：面板头行的摘要两行正好是 40dp 高，控件跟它齐平最省地方（旧版 36dp 谁也贴不上）
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * 免费时长倒计时块（DESIGN §3.9）：大号倒计时 + 细进度线。
 *
 * 每秒自刷新（[produceState] 计时循环）；到点调 [onExpired] 让**父级**重组一次把卡撤掉
 * ——tick 只跑在这块里，整页不跟着每秒重组。
 */
@Composable
private fun RideCountdown(startAt: Long, onExpired: () -> Unit) {
    val now by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = startAt,
    ) {
        while (true) {
            val tick = System.currentTimeMillis()
            value = tick
            if (!EbikeFreeRide.isActive(startAt, tick)) {
                onExpired()
                break
            }
            delay(1_000L)
        }
    }
    val remaining = EbikeFreeRide.remainingSeconds(startAt, now)
    val progress = EbikeFreeRide.progressFraction(startAt, now)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = EbikeFreeRide.formatRemaining(remaining),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum", // 等宽数字：每秒跳动不抖
                ),
            )
            Text(
                text = "  免费剩余",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        )
    }
}

/**
 * 地图右上角的圆形浮层按钮（定位 / 回到校区）。
 *
 * [busy] 时把图标换成进度指示并停止响应点击：定位最长 8 秒，没有这个状态用户看不出来
 * 点没点上，只会接着点。
 */
@Composable
internal fun MapCircleButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        enabled = !busy,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .padding(10.dp)
                .size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// ──────────────────────────── 使用方式弹层 ────────────────────────────

/**
 * 使用方式切换弹层（DESIGN §3.9）：**骑行中打不开**（调用方拦住），
 * 因为切换会让在案订单从界面消失，是最容易让人以为"车丢了 / 钱没了"的一刻。
 *
 * 两张选项卡各写清后果：切过去之后能做什么、有什么代价。切换**不改变页面结构**，
 * 只改变主动作——这句话写在副标题里，用户才敢切。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RideModeSheet(
    mode: EbikeUseMode,
    onSelect: (EbikeUseMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 选中即关：**先播完退场动画再落状态**，顺带把切换提示留到关完之后再弹
    val dismiss = rememberSheetDismisser(sheetState, onDismiss)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "使用方式",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "两档互斥，随时可切。切换不改变页面结构，只改变主动作。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            EbikeUseMode.entries.forEach { entry ->
                RideModeOption(
                    mode = entry,
                    selected = entry == mode,
                    onClick = { dismiss { onSelect(entry) } },
                )
            }
        }
    }
}

@Composable
private fun RideModeOption(mode: EbikeUseMode, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) scheme.surfaceContainerHigh else scheme.surface)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) scheme.primary else scheme.outlineVariant,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = mode.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text(
                    text = "当前",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onPrimaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(scheme.primaryContainer)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
        }
        Text(
            text = if (mode.isAccount) {
                "登录后在 App 内直接开锁 / 锁车 / 还车，从开锁起计费；非官方客户端，订单与善后以快趣为准。"
            } else {
                "只生成乘车码与计时，开车 / 还车都在微信小程序里完成；App 内不做任何写操作。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurface.copy(alpha = 0.62f),
        )
    }
}

// ──────────────────────────── 文案与工具 ────────────────────────────

/** 分组行里的距离文案，**带上参照点**。 */
private fun clusterDistanceText(meters: Int, fromUser: Boolean): String =
    (if (fromUser) "距你 " else "距中心 ") + BikeNearby.formatDistance(meters)

/** 停车点那一行小字：几辆 · 距离 · 可用情况。 */
private fun clusterMetaText(cluster: BikeCluster, fromUser: Boolean): String = buildString {
    append(cluster.bikes.size).append(" 辆 · ")
    append(clusterDistanceText(cluster.nearestDistanceMeters, fromUser)).append(" · ")
    append(clusterStatusText(cluster))
}

/** 分组里有多少辆能骑，比逐个看状态省事。 */
private fun clusterStatusText(cluster: BikeCluster): String {
    val usable = cluster.bikes.count { it.available }
    return if (usable == cluster.bikes.size) "全部可用" else "$usable 辆可用"
}

/**
 * 摘要行下方那句状态：刷新中 / 更新于几点（补全中） / 还没拿到数据。
 *
 * 中心结果落地就不再是"刷新中"——撒点还在飞时只挂一句「正在补全周围…」，
 * 它只影响列表的完整度，不影响已看到的车。超过 [STALE_AFTER_MS] 补一句提示。
 */
@Composable
private fun subtitleText(
    queried: Boolean,
    loading: Boolean,
    completing: Boolean,
    updatedAtMillis: Long,
): String {
    // 每 15 秒重算一次"现在"，把更新时间从新鲜翻成可能过期
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(CLOCK_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    val base = when {
        loading && queried -> "刷新中…"
        completing && updatedAtMillis > 0 -> "更新于 ${clockText(updatedAtMillis)} · 正在补全周围…"
        updatedAtMillis > 0 -> "更新于 ${clockText(updatedAtMillis)}"
        queried -> "尚未获取到数据"
        else -> "正在获取…"
    }
    val stale = updatedAtMillis > 0 && now - updatedAtMillis > STALE_AFTER_MS
    return if (stale) "$base · 数据可能已过期" else base
}

/** 车辆那行小字：状态 · 电量 · 车型 · 距离。电量低于档位就单独着色。 */
@Composable
private fun bikeInfoText(
    bike: NearbyBike,
    baseColor: Color,
    distanceFromUser: Boolean,
): AnnotatedString {
    val batteryColor = if (bike.batteryLow) {
        MaterialTheme.semanticColors.warning
    } else {
        baseColor
    }
    return buildAnnotatedString {
        append(bike.status.label)
        append(" · ")
        withStyle(SpanStyle(color = batteryColor)) { append(bike.batteryText) }
        if (bike.model.isNotBlank()) {
            append(" · ")
            append(bike.model)
        }
        append(" · ")
        append((if (distanceFromUser) "距你 " else "距中心 ") + BikeNearby.formatDistance(bike.distanceMeters))
    }
}
