package edu.jxslu.schedule.ui.qzxy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
 * 组合期只判定一次就够：过期清理在 [edu.jxslu.schedule.ui.qzxy.QzxyViewModel.applyWatering]
 * 做（store 层面清掉），这里只是让卡片/面板在「还没清掉的那一帧」也显示过期文案，
 * 别闪出一个进行中的计时。
 */
fun isQzxyWateringExpired(startedAtMillis: Long): Boolean =
    QzxyWatering(startedAtMillis, "", "").isExpired(System.currentTimeMillis())
