package edu.jxslu.schedule.ui.water

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.UnlockFlowState

/** 状态区的动画分组键：同一组内的变化（「正在出水」每秒走字）不触发切换动画。 */
enum class WaterFlowPhase { Idle, PreChecking, Working, Success, Failed }

fun waterFlowPhase(flow: UnlockFlowState): WaterFlowPhase = when (flow) {
    is UnlockFlowState.Idle -> WaterFlowPhase.Idle
    is UnlockFlowState.PreChecking -> WaterFlowPhase.PreChecking
    is UnlockFlowState.Working -> WaterFlowPhase.Working
    is UnlockFlowState.Success -> WaterFlowPhase.Success
    is UnlockFlowState.Failed -> WaterFlowPhase.Failed
}

/**
 * 流程状态区的过渡容器（2026-10-05 用户反馈「弹窗突变了都，没一点动画变化」）：
 * 待机 / 准备 / 出水 / 结算 / 失败之间切换时淡入淡出，高度差交给 [AnimatedContent]
 * 自带的尺寸动画（与趣智校园面板同一手法）。
 *
 * `contentKey` 按相位分桶，所以「正在出水」每秒走字、失败文案变化都不会触发整块重放动画。
 */
@Composable
fun WaterFlowStatusHost(
    flow: UnlockFlowState,
    modifier: Modifier = Modifier,
    content: @Composable (UnlockFlowState) -> Unit,
) {
    AnimatedContent(
        targetState = flow,
        contentKey = { waterFlowPhase(it) },
        transitionSpec = {
            fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(160))
        },
        label = "water-flow-status",
        modifier = modifier,
    ) { current -> content(current) }
}

/** 出水中的动作位三态：还不能结束 / 可结束 / 结束中（等设备回报账单）。 */
private enum class WaterEndAction { Waiting, Endable, Stopping }

/**
 * 出水中的动作位（2026-10-05 用户拍板，开水页与今日页开水面板共用）：
 * 出水满 [WaterViewModel.END_SESSION_MIN_SECONDS] 秒前是禁用的「开水进行中…」，
 * 之后**原地**换成可点的「结束用水」；点下后转禁用的「正在结束用水…」等设备回报。
 *
 * 切换交给 [AnimatedContent] 的淡入淡出与自带尺寸动画：早先把按钮塞在状态区里，
 * 5 秒一到整块内容往下长一截，看着像弹窗突然变了（用户反馈）。
 *
 * 2026-10-16 改：机型不支持 App 结束（或平台拒绝）时，结束要靠设备上的「结束」键——
 * 副行给出「未停止时请在设备上点击『结束』」的引导，仓库侧继续轮询等账单；
 * 不写「已关阀」这类拿不到证据的话。
 */
@Composable
fun WaterWorkingAction(
    canEndSession: Boolean,
    onEndSession: () -> Unit,
    stopping: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val action = when {
        stopping -> WaterEndAction.Stopping
        canEndSession -> WaterEndAction.Endable
        else -> WaterEndAction.Waiting
    }
    AnimatedContent(
        targetState = action,
        transitionSpec = {
            fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(120))
        },
        label = "water-working-action",
        modifier = modifier.fillMaxWidth(),
    ) { state ->
        Column(modifier = Modifier.fillMaxWidth()) {
            when (state) {
                WaterEndAction.Endable -> {
                    Button(
                        onClick = onEndSession,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("结束用水") }
                    Text(
                        "未停止时，请在设备上点击『结束』",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                WaterEndAction.Stopping -> {
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("正在结束用水…") }
                    Text(
                        "等待结束回报；未停止时请在设备上点击『结束』",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                WaterEndAction.Waiting -> {
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("开水进行中…") }
                }
            }
        }
    }
}
