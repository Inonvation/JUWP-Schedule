package edu.jxslu.schedule.ui.qzxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.QzxyWateringFormat

/**
 * 今日页点余额弹出的趣智校园开水面板（DESIGN §3.18）。
 *
 * 与胖乖的开水面板同构：余额在顶、状态在中、按钮在下，跟页面共享同一个
 * [QzxyViewModel]（今日页那份挂在 MainActivity 上），面板里发起的开阀与结算
 * 在页面上状态一致。关闭面板不取消流程——卡片副行会继续显示「用水中 12:35」。
 *
 * 为什么入口是余额而不是卡片右侧另一个按钮：半行宽度放不下第二个动作，
 * 而余额本来就是这张卡最想被点的地方（与胖乖生活卡同口径）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QzxyEntrySheet(
    state: QzxyUiState,
    /** 登录态取自仓库那条流，不由 [state] 转述：会话登录可能发生在别的窗口。 */
    loggedIn: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAbandon: () -> Unit,
    onDismissSettlement: () -> Unit,
    onOpenPage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val flow = state.flow
    val watering = state.watering
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "趣智校园",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Column {
                Text(
                    text = when {
                        !loggedIn -> "还没登录"
                        state.balance != null -> "¥${state.balance?.text}"
                        else -> "余额读取中…"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (loggedIn) {
                        state.schoolName.ifBlank { "水控账户" }
                    } else {
                        "洗澡开热水要先用趣智校园账号登录"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.55f),
                )
            }
            if (loggedIn) {
                Text(
                    text = watering?.deviceName
                        ?: state.selected?.let { device ->
                            state.deviceInfos[device.addressKey]?.deviceName?.takeIf { it.isNotBlank() }
                                ?: device.name
                        }
                        ?: state.lastUsedDevice?.name
                        ?: "还没选设备，去页面里挑一台",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurface.copy(alpha = 0.8f),
                )
            }

            when {
                // 没登录时面板只有一件事可做：去登录。开阀按钮在这儿只会是灰的
                !loggedIn -> Button(
                    onClick = {
                        onDismiss()
                        onOpenPage()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text("去登录")
                }

                flow is QzxyFlowState.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = flow.step,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onSurface.copy(alpha = 0.7f),
                    )
                }

                state.lastSettlement != null -> Column {
                    Text(
                        text = "已结束用水 · ${QzxyWateringFormat.money(state.lastSettlement.consumeMoneyMilli)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    state.lastSettlement.durationMillis?.let { duration ->
                        Text(
                            text = "本次用水 ${QzxyWateringFormat.duration(duration)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurface.copy(alpha = 0.55f),
                        )
                    }
                    state.lastSettlement.note?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            onDismissSettlement()
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .height(46.dp),
                    ) {
                        Text("完成")
                    }
                }

                watering != null -> Column {
                    if (isQzxyWateringExpired(watering.startedAtMillis)) {
                        // 过期的那一帧（store 清理在 ViewModel 侧异步完成）显示待处理文案，
                        // 不显示进行中的计时
                        Text(
                            text = "上次用水已超过 1 小时，点「结束用水」核对设备记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(
                            onClick = onStop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .height(48.dp),
                        ) {
                            Text("结束用水")
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "用水中",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = rememberQzxyWateringClock(watering.startedAtMillis),
                                // 等宽数字，避免每秒刷新时整行抽动
                                style = MaterialTheme.typography.titleLarge
                                    .copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = watering.preDeductMilli?.let {
                                "服务端预扣 ${QzxyWateringFormat.money(it)}，按实际用量结算"
                            } ?: "结束后按实际用量结算",
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurface.copy(alpha = 0.55f),
                        )
                        (flow as? QzxyFlowState.Failed)?.let { failed ->
                            Text(
                                text = failed.reason,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Button(
                            onClick = onStop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .height(48.dp),
                        ) {
                            Text(if (flow is QzxyFlowState.Failed) "重试结束用水" else "结束用水")
                        }
                        Text(
                            text = "结算要走蓝牙，点之前先站到热水器旁边",
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurface.copy(alpha = 0.55f),
                        )
                        TextButton(onClick = onAbandon) {
                            Text("水已经停了，标记为已结束")
                        }
                    }
                }

                flow is QzxyFlowState.Failed -> Column {
                    Text(
                        text = flow.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    flow.detail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurface.copy(alpha = 0.55f),
                        )
                    }
                    Button(
                        onClick = onStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .height(48.dp),
                    ) {
                        Text("重试开阀")
                    }
                }

                else -> Button(
                    onClick = onStart,
                    // 面板没经过「选设备」这一步：有上次用的那台就算就绪，
                    // 真正开阀前 ViewModel 会把它选上
                    enabled = state.selected != null ||
                        state.lastUsedDevice != null ||
                        state.boundDevices.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text("开始用水")
                }
            }

            TextButton(
                onClick = {
                    onDismiss()
                    onOpenPage()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开趣智校园页")
            }
        }
    }
}
