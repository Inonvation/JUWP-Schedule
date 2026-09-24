package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 含输入框的弹层容器：**退场与键盘严格分两段**——先收键盘（弹层跟着键盘一起下移），
 * 键盘收完再滑走。
 *
 * ## 为什么不能并行收（2026-09-24 二次定位；第一版「并行」是错的）
 *
 * M3 `SheetState` 的退场是一条 `tween(300ms, FastOutSlowIn)`（`BottomSheetAnimationSpec`），
 * 而锚点 Expanded/Hidden 由**弹层容器高度**算出：容器的父 Box 是
 * `fillMaxSize().imePadding()`，键盘一收，容器高度就从 1604px 涨回 2400px。于是
 * `hide()` 与收键盘并行时：
 *
 * 1. 键盘动画每帧改一次容器高度 → `.draggableAnchors{}` 每帧 `updateAnchors()`；
 * 2. 动画进行中的 `updateAnchors` 会**取消并重启**这条退场动画（foundation `restartable{}`
 *    注释原文："restart the ongoing anchoredDrag operation (e.g. an animation) with the
 *    new anchors"）；
 * 3. 重启用的还是那条 tween——时长型 spec 每次都从缓动曲线的 0 点**重新计时**、不吸收初速度，
 *    而终点 Hidden 每帧还在往下跑（1604 → 2400）。
 *
 * 净效果：键盘收起的整段里，弹层每帧只推进剩余距离的不到 1%（`FastOutSlowIn` 在 16ms 处
 * ≈0.7%），几乎停在原地；等键盘收完锚点不再变，才把剩下 1500+ 像素用一条 tween 一口气滑完。
 * 真机观感正是用户报的「先键盘收回、弹窗延迟收回、收回动画诡异」。第一版
 * （`SheetDismissIme(sheetState)`）在 `targetValue` 变 Hidden 时收键盘，只修掉了「弹层先滑
 * 下去、键盘再收」的两段串行，没躲开这条重锚点——点遮罩退场（M3 的 `animateToDismiss`）就是
 * 并行，于是又报了一起。
 *
 * ## 现在的路径
 *
 * `confirmValueChange` 是 M3 自己询问「这次能不能去 Hidden」的钩子（点遮罩的
 * `animateToDismiss`、下滑松手的 `settle` 都会先问它）。键盘还起着时一律**否决**这次
 * Hidden，改由弹层内容里的宿主跑序列：
 *
 * 1. 交还焦点 + 收键盘 → 弹层跟着键盘一起下移（这一段是 `trySnapTo(Expanded)` 的逐帧吸附，
 *    也就是用户认可的「键盘和弹窗一起跟随收回」）；
 * 2. 等 IME inset 真归零（`WindowInsets.ime` 逐帧回调，收到 0 = 键盘动画结束）再 `hide()`：
 *    此时锚点不再变，300ms tween 一次跑完，没有中途重启。
 *
 * 两段之间没有空档（键盘到位的同一帧接着滑），合起来是一次连续下移。超时兜底
 * [IME_COLLAPSE_TIMEOUT_MILLIS] 只防「键盘收不动」（无焦点等异常态）时把弹层卡住不退。
 *
 * ## 硬要求
 *
 * 新弹层照抄 [ImeAwareModalBottomSheet]，别自己拼 `ModalBottomSheet`：
 *
 * - **收键盘必须用弹窗窗口的** `LocalFocusManager` / `LocalSoftwareKeyboardController`：
 *   写在 `ModalBottomSheet { … }` 外面拿到的是 Activity 窗口那一份，收不动弹窗里的键盘
 *   （第一版实测无效）。`WindowInsets.ime` 同理——它是 `@Composable` getter，取的是当前
 *   组合所属窗口（弹窗）的 insets，所以宿主 composable 必须在弹层内容里。本容器已内建。
 * - 顺带钉死 `skipPartiallyExpanded = true`：键盘弹起后 M3 会造出半高锚点并把 target 从
 *   Expanded 改判过去，底部按钮被键盘盖住（定位过程见 DESIGN §4.19「充值弹层的键盘」）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImeAwareModalBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 键盘可见性：由弹层内容里的宿主逐帧从弹窗窗口的 IME inset 同步（M3 询问
    // confirmValueChange 时读到的是最新一帧的值）
    val imeVisible = remember { mutableStateOf(false) }
    // 退场序列已排队：否决 M3 这次 Hidden，交给宿主「先收键盘」
    val sequenced = remember { mutableStateOf(false) }

    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { value ->
                if (value == SheetValue.Hidden && imeVisible.value) {
                    // 连点遮罩也只排队一次；序列自己会走 hide()，那时键盘已收（imeVisible=false）
                    sequenced.value = true
                    false
                } else {
                    true
                }
            },
        )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        SheetDismissImeHost(
            sheetState = sheetState,
            imeVisible = imeVisible,
            sequenced = sequenced,
            onDismiss = onDismiss,
        )
        content()
    }
}

/** 键盘收起动画通常 250–350ms；只做兜底，正常路径在 inset 归零那一帧就往下走。 */
private const val IME_COLLAPSE_TIMEOUT_MILLIS = 450L

/**
 * 退场序列的宿主：**必须组合在弹层内容里**（拿到的才是弹窗窗口的焦点/键盘/insets）。
 * [ImeAwareModalBottomSheet] 已自动放进内容，调用方不用自己接。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetDismissImeHost(
    sheetState: SheetState,
    imeVisible: MutableState<Boolean>,
    sequenced: MutableState<Boolean>,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime

    // 逐帧同步键盘可见性（键盘起落动画期间 inset 每帧回调）
    LaunchedEffect(imeInsets, density) {
        snapshotFlow { imeInsets.getBottom(density) }.collect { imeVisible.value = it > 0 }
    }

    // 序列：收键盘 → 等 inset 归零 → hide() → onDismiss()
    LaunchedEffect(sequenced.value) {
        if (!sequenced.value) return@LaunchedEffect
        focusManager.clearFocus()
        keyboard?.hide()
        withTimeoutOrNull(IME_COLLAPSE_TIMEOUT_MILLIS) {
            snapshotFlow { imeInsets.getBottom(density) }.first { it == 0 }
        }
        // 已实测归零（或超时兜底）：先放开闸门，别让 confirmValueChange 把这次 hide 拦下
        imeVisible.value = false
        sheetState.hide()
        onDismiss()
    }

    // 兜底：其余路径直接 hide()（系统返回、下滑手势…）时，至少让键盘与退场并行，别再串成
    // 「弹层先滑下去、键盘再收」两段（旧版 `SheetDismissIme` 的行为）
    val hiding = sheetState.isVisible && sheetState.targetValue == SheetValue.Hidden
    LaunchedEffect(hiding) {
        if (hiding) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
}
