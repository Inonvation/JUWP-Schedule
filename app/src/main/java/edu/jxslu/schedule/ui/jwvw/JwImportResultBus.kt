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

    /**
     * 「入队 + 发布」要是一个原子动作：`publish` 可能来自后台协程（如教材补抓的失败提示），
     * 而 `consume` 在主线程，分开做会出现「刚消费掉的消息又被写回去」。
     */
    private val lock = Any()
    private val queue = OutcomeQueue<JwImportOutcome>()
    private val _outcome = MutableStateFlow<JwImportOutcome?>(null)
    val outcome: StateFlow<JwImportOutcome?> = _outcome.asStateFlow()

    /**
     * 发一条消息。**前一条还没消费时排队**（见 [OutcomeQueue]），不再把它顶掉——
     * 「导入完成」与「教材未同步」是同一次导入的两条结果，两条都该看得到（2026-10-06）。
     */
    fun publish(text: String, tone: NoticeTone = NoticeTone.Success) = synchronized(lock) {
        _outcome.value = queue.push(
            JwImportOutcome(text = text, publishedAtMs = SystemClock.elapsedRealtime(), tone = tone),
        )
    }

    fun consume(shown: JwImportOutcome?) = synchronized(lock) {
        _outcome.value = queue.consume(shown)
    }
}

/**
 * 「一条在屏 + 后续排队」的一次性消息队列。
 *
 * 为什么需要它：[JwImportResultBus] 原本是单值 StateFlow，后一条 `publish` 直接覆盖前一条
 * 还没消费的消息。2026-10-06 给教材抓取补失败提示时正好踩上——「导入完成」的气泡还在屏上，
 * 教材那条就把它顶掉了，而失败完全可能是即时的（凭证已停用时 `ensureValid` 一个包都不发）。
 *
 * 抽成独立的泛型类是为了能在 JVM 单测里钉住顺序：bus 本身要 `SystemClock`，测不了。
 */
internal class OutcomeQueue<T> {

    private val lock = Any()
    private val pending = ArrayDeque<T>()
    private var current: T? = null

    /** 入队并返回**当前该在屏上**的那条：空闲时就是刚进来的这条，否则还是原来那条。 */
    fun push(value: T): T? = synchronized(lock) {
        if (current == null) current = value else pending.addLast(value)
        current
    }

    /**
     * 消费当前这条并返回下一条（没有排队的就返回 null）。
     *
     * [shown] 是调用方**当前正在显示**的值，只有它确实就是 `current` 时才推进队列。
     * 不做这个校验的话，两个消费者（分屏双窗口；或转场期间两个落点页同时在组合里）
     * 各 consume 一次，第二次会把队列里**还没上屏**的那条一起顶掉——
     * 结果是**丢消息**，而不是重复消费。
     *
     * 首次调用传 null（还没有任何东西在屏上）：此时不推进，原样返回 current。
     */
    fun consume(shown: T?): T? = synchronized(lock) {
        if (current != null && shown === current) {
            current = pending.removeFirstOrNull()
        }
        current
    }
}

/**
 * 在「导入窗口 finish 之后可能落到的页面」里调一次：取到即消费，用页面下方那条气泡显示。
 *
 * 落点有五个（课表页 / 今日页 / 成绩页 / 学校统一认证页 / 课表中心页），各自记一遍
 * 「订阅 + 消费 + 判新鲜」容易写岔，收成一个调用。
 *
 * 清通道的时机要紧（2026-09-30 修正，此前「先 consume 再 show」五处落点全都不显示）：
 * [JwImportResultBus.consume] 把 flow 换成下一条（没有排队的就是 null）后，`LaunchedEffect(outcome)`
 * 会在下一帧重启、取消还挂在 `showSnackbar` 上的这个协程——M3 的契约是「caller cancelled →
 * snackbar removed from display」，气泡刚挂上去就被撤掉。所以过期消息在**进 show 之前**丢（防止它在
 * 后续组合里反复触发），新鲜消息**等 `showSnackbar` 返回后**才清（返回即上一条已下场，
 * 排队中的下一条这时才顶上来）。
 */
@Composable
fun JwImportOutcomeEffect(snackbar: SnackbarHostState) {
    val outcome by JwImportResultBus.outcome.collectAsStateWithLifecycle()
    LaunchedEffect(outcome) {
        val pending = outcome ?: return@LaunchedEffect
        if (!pending.isFresh()) {
            JwImportResultBus.consume(pending)
            return@LaunchedEffect
        }
        snackbar.showSnackbar(AppNoticeVisuals(pending.text, tone = pending.tone))
        JwImportResultBus.consume(pending)
    }
}
