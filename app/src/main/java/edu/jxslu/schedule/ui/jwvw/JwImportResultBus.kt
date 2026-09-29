package edu.jxslu.schedule.ui.jwvw

import android.os.SystemClock
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 教务导入 → 主界面的一次性结果通道（DESIGN §3.3）。
 *
 * 导入窗口（`JwImportActivity`）与主界面是两个 Activity，没有共同的 CompositionLocal，
 * 「导入完成」弹窗只在导入窗口里出现，点「完成」就 `finish()` 回课表页，那一页什么都看不到
 * （2026-09-29 用户指出）。这里按 [edu.jxslu.schedule.ui.campus.PayCodeResultBus] 的形态
 * 开一条进程内通道，由课表页 / 今日页**取到即消费**。
 *
 * [JwImportOutcome.publishedAtMs] 用 `SystemClock.elapsedRealtime()`，取值方只在新鲜期内展示：
 * 从「我的 → 学校统一认证」进导入的用户回到的是二级页，课表页要等切 Tab 才组合，没有这道闸门，
 * 十分钟前导完的消息会在用户随手切到课表页时凭空冒出来。
 */
data class JwImportOutcome(
    val text: String,
    val publishedAtMs: Long,
    val tone: NoticeTone = NoticeTone.Success,
) {
    /** 是否还在新鲜期内（[FRESH_WINDOW_MS]）。 */
    fun isFresh(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        nowMs - publishedAtMs <= FRESH_WINDOW_MS

    companion object {
        /** 读完完成弹窗再点「完成」通常几秒，给一分钟足够。 */
        const val FRESH_WINDOW_MS = 60_000L
    }
}

object JwImportResultBus {

    private val _outcome = MutableStateFlow<JwImportOutcome?>(null)
    val outcome: StateFlow<JwImportOutcome?> = _outcome.asStateFlow()

    fun publish(text: String, tone: NoticeTone = NoticeTone.Success) {
        _outcome.value = JwImportOutcome(text = text, publishedAtMs = SystemClock.elapsedRealtime(), tone = tone)
    }

    fun consume() {
        _outcome.value = null
    }
}

/**
 * 在「导入窗口 finish 之后可能落到的页面」里调一次：取到即消费，用页面下方那条气泡显示。
 *
 * 落点有五个（课表页 / 今日页 / 成绩页 / 学校统一认证页 / 课表中心页），各自记一遍
 * 「订阅 + 消费 + 判新鲜」容易写岔，收成一个调用。
 *
 * 顺序要紧：**先 [JwImportResultBus.consume] 再判新鲜**。反过来的话，过期的那条会一直留在
 * 通道里，下一次组合又读到、又判过期，白跑一轮。
 */
@Composable
fun JwImportOutcomeEffect(snackbar: SnackbarHostState) {
    val outcome by JwImportResultBus.outcome.collectAsStateWithLifecycle()
    LaunchedEffect(outcome) {
        val pending = outcome ?: return@LaunchedEffect
        JwImportResultBus.consume()
        if (pending.isFresh()) {
            snackbar.showSnackbar(AppNoticeVisuals(pending.text, tone = pending.tone))
        }
    }
}
