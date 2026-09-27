package edu.jxslu.schedule.ui.qzxy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
