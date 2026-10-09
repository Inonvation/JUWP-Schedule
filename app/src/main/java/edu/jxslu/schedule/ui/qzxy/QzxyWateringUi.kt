package edu.jxslu.schedule.ui.qzxy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import edu.jxslu.schedule.domain.QzxyWatering
import edu.jxslu.schedule.domain.QzxyWateringFormat
import kotlinx.coroutines.delay

/**
 * 用水计时的每秒刷新（DESIGN §3.18）。
 *
 * 趣智校园页与今日页卡片都要显示「用水中 12:35」，两处各写一份 tick 迟早会不一致，
 * 收在这里。切走页面时 Composable 离开组合，循环跟着停，不会在后台空转；
 * 用时超过过期线（[QzxyWatering.EXPIRE_MILLIS]，1 小时）也停——过期文案由
 * [isQzxyWateringExpired] 接管，计时再跳没有意义，不能进页就永久每秒唤醒。
 */
@Composable
fun rememberQzxyWateringClock(startedAtMillis: Long): String {
    val text by produceState(
        initialValue = QzxyWateringFormat.duration(System.currentTimeMillis() - startedAtMillis),
        key1 = startedAtMillis,
    ) {
        while (System.currentTimeMillis() - startedAtMillis < QzxyWatering.EXPIRE_MILLIS) {
            value = QzxyWateringFormat.duration(System.currentTimeMillis() - startedAtMillis)
            delay(1_000L)
        }
        // 退出即定格真实经过时间（可能已超 1 小时）：硬截到 EXPIRE_MILLIS 会把
        // 「离开几分钟后回来」的真实时长抹成整 1:00:00
        value = QzxyWateringFormat.duration(System.currentTimeMillis() - startedAtMillis)
    }
    return text
}

/**
 * 用水状态是否已过期（超过 [QzxyWatering.EXPIRE_MILLIS]，1 小时）。
 *
 * 过期只是**展示口径**：状态卡与卡片把它当「待核对」渲染（没有进行中的计时），
 * 记账本身由结算成功、清除设备记录或用户显式「已到账」清理——2026-10-09 起
 * 不再静默清掉，清了会让预扣的去向失去界面痕迹。
 */
fun isQzxyWateringExpired(startedAtMillis: Long): Boolean =
    QzxyWatering(startedAtMillis, "", "").isExpired(System.currentTimeMillis())

/** 对账两次读取之间的间隔。见 [WateringReconcileEffect]。 */
private const val WATERING_RECONCILE_INTERVAL_MILLIS = 30_000L

/**
 * 用水对账的驱动（DESIGN §3.18）：页面/面板可见（RESUMED）期间周期调
 * [QzxyViewModel.reconcileWateringOnce]，把本地记账与设备真实状态核对——
 * 设备自动关停（停水过一阵，或一直用到预扣耗尽）没有可预测的时刻，只能这样反应式发现：
 * 读到「消费完成，待采集」就自动结算，读到空闲就把记账切「待核对」口径。
 *
 * [enabled] 为 false（本地没有记账）时不跑；[reconcileWateringOnce] 返回 false
 * 即停到本次前台周期结束（没有记账 / 到终端态 / 连续读不到设备）；回到前台时
 * 复位退避并立即再来一拍。与 `ResumedTickerEffect` 同一条生命周期口径：灭屏 /
 * 退后台立刻停，回前台立刻续。
 */
@Composable
fun WateringReconcileEffect(viewModel: QzxyViewModel, enabled: Boolean) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, enabled) {
        if (!enabled) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.onWateringWatchResumed()
            while (true) {
                if (!viewModel.reconcileWateringOnce()) break
                delay(WATERING_RECONCILE_INTERVAL_MILLIS)
            }
        }
    }
}
