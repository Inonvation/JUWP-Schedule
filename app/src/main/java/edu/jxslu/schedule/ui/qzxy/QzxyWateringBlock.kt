package edu.jxslu.schedule.ui.qzxy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.QzxyWatering
import edu.jxslu.schedule.domain.QzxyWateringFormat
import edu.jxslu.schedule.ui.common.BreathingDot

/**
 * 「用水」状态块的唯一实现（2026-10-09 从页面与今日页面板两份重复里抽出来）。
 *
 * 两个形态：
 * - **用水中**（1 小时内且设备没被确认结束）：计时是主角，主动作「结束用水」；
 * - **待核对**（超过 1 小时，或对账已确认设备结束）：没有进行中的计时，主动作
 *   「结算核对」，并把预扣的去向说清楚——「消费记录」里这笔单先显示 ¥0，
 *   平台处理后显示实际金额（一般次日到账）。
 *
 * 为什么合并：两个出口（页面状态卡 / 今日页面板）此前各写一份、迟早分叉；
 * 2026-10-09 的「结算失败保留记录」「待核对留痕」两处行为都要求两块完全一致。
 */
@Composable
internal fun WateringStatusBlock(
    watering: QzxyWatering,
    /** 对账已确认设备那边这次用水结束（进程内标记，见 QzxyUiState.wateringDeviceEnded）。 */
    deviceEnded: Boolean,
    failure: QzxyFlowState.Failed?,
    onStop: () -> Unit,
    onAbandon: () -> Unit,
    /** 非空 = 给出「清除设备记录」出口（只在设备可能还压着记录时传）。 */
    onClearDeviceRecord: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val pending = deviceEnded || isQzxyWateringExpired(watering.startedAtMillis)
    Column(modifier.fillMaxWidth()) {
        if (pending) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "待核对",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(50),
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "开阀于 ${QzxyWateringFormat.clockText(watering.startedAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.55f),
                )
            }
            Text(
                text = watering.preDeductMilli?.let { "预扣 ${QzxyWateringFormat.money(it)}" }
                    ?: "预扣金额待服务端结算",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = "设备那边这次用水可能已经结束。点「结算核对」去设备旁试一次：" +
                    "记录还在就当场结算；记录已不在的话这笔单由平台统一处理（一般次日到账）。" +
                    "「消费记录」里它会先显示 ¥0，处理后显示实际金额。",
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WateringBadge()
                Spacer(Modifier.weight(1f))
                Text(
                    text = "开始于 ${QzxyWateringFormat.clockText(watering.startedAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.55f),
                )
            }
            Text(
                text = rememberQzxyWateringClock(watering.startedAtMillis),
                // 等宽数字（tnum）：默认字体的「1」比「8」窄，每秒刷新会让整行左右抽动
                style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = watering.preDeductMilli?.let {
                    "服务端预扣 ${QzxyWateringFormat.money(it)}，结束后按实际用量结算，多扣的部分退回"
                } ?: "结束后按实际用量结算，多扣的部分退回",
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (failure != null) {
            Text(
                text = failure.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 10.dp),
            )
            failure.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.55f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(48.dp),
        ) {
            Text(
                when {
                    pending && failure != null -> "重试结算核对"
                    pending -> "结算核对"
                    failure != null -> "重试结束用水"
                    else -> "结束用水"
                },
            )
        }
        Text(
            text = "结算要走蓝牙，点之前先站到热水器旁边",
            style = MaterialTheme.typography.bodySmall,
            color = onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 8.dp),
        )
        // 「放弃结算」的显式出口：只在结算失败（记录已确定保留）时给，避免日常误触
        if (onClearDeviceRecord != null && failure?.clearRecovery == true) {
            TextButton(onClick = onClearDeviceRecord) {
                Text("清除设备记录（放弃上报，预扣由平台处理）")
            }
        }
        TextButton(onClick = onAbandon) {
            Text(if (pending) "已到账或不再处理，清除本地记账" else "水已经停了，标记为已结束")
        }
    }
}

/**
 * 「用水中」徽标：左侧一枚呼吸的圆点。
 *
 * 只改透明度、不动尺寸（走 `graphicsLayer` 的 lambda 形式，动画值在绘制阶段读，
 * 不触发重组），也不是骨架流光那套（那个在 `ui/common/Skeleton.kt`）。呼吸给的
 * 是「水还在流」的现场感。
 */
@Composable
private fun WateringBadge() {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .background(scheme.primaryContainer, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BreathingDot()
        Spacer(Modifier.width(6.dp))
        Text(
            text = "用水中",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onPrimaryContainer,
        )
    }
}
