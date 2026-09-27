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
 * 收在这里。切走页面时 Composable 离开组合，循环跟着停，不会在后台空转。
 */
@Composable
fun rememberQzxyWateringClock(startedAtMillis: Long): String {
    val text by produceState(
        initialValue = QzxyWateringFormat.duration(System.currentTimeMillis() - startedAtMillis),
        key1 = startedAtMillis,
    ) {
        while (true) {
            value = QzxyWateringFormat.duration(System.currentTimeMillis() - startedAtMillis)
            delay(1_000L)
        }
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
