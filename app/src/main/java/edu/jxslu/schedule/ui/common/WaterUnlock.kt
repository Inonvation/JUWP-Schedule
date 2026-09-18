package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import kotlinx.coroutines.delay

/**
 * 开水确认点击策略。
 *
 * 默认双击：一键出水误触代价高，第一次点提示「再点确认」，超时重置；
 * 设置里可切回单击。今日快捷卡与开水页主按钮共用，两处手感一致。
 */
object WaterClickPolicy {
    /** 两次点击间隔超过该值则重新计第一次。 */
    const val DOUBLE_CLICK_WINDOW_MS = 2500L
}

/** 读取全局「开水需双击」偏好；流未就绪时按默认双击处理。 */
@Composable
fun rememberWaterRequireDoubleClick(): Boolean {
    val context = LocalContext.current
    val prefs by remember { Graph.repository(context).displayPrefs }
        .collectAsStateWithLifecycle(initialValue = null)
    return prefs?.waterRequireDoubleClick != false
}

/**
 * 开水按钮：按全局偏好走单击/双击。
 * 双击模式下第一次点击只改文案提示确认，不触发 unlock。
 */
@Composable
fun WaterUnlockButton(
    enabled: Boolean,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    requireDoubleClick: Boolean = rememberWaterRequireDoubleClick(),
    text: String = "开水",
    height: Dp = 36.dp,
    fillMaxWidth: Boolean = false,
) {
    var armedAt by remember { mutableLongStateOf(0L) }
    var awaitingConfirm by remember { mutableStateOf(false) }

    // 超时自动收回「待确认」，避免用户以为已点上却没反应
    LaunchedEffect(awaitingConfirm) {
        if (awaitingConfirm) {
            delay(WaterClickPolicy.DOUBLE_CLICK_WINDOW_MS)
            awaitingConfirm = false
            armedAt = 0L
        }
    }

    val label = if (awaitingConfirm) "再点确认" else text

    Button(
        onClick = {
            if (!enabled) return@Button
            if (!requireDoubleClick) {
                awaitingConfirm = false
                armedAt = 0L
                onUnlock()
                return@Button
            }
            val now = System.currentTimeMillis()
            if (awaitingConfirm && now - armedAt <= WaterClickPolicy.DOUBLE_CLICK_WINDOW_MS) {
                awaitingConfirm = false
                armedAt = 0L
                onUnlock()
            } else {
                armedAt = now
                awaitingConfirm = true
            }
        },
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
        modifier = modifier
            .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
            .height(height),
    ) {
        Text(label)
    }
}
