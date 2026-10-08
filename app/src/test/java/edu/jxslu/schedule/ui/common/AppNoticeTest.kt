package edu.jxslu.schedule.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 提示的**顶替**语义（2026-10-08 用户报「连着切电脑版 / 手机版，第二条要等第一条过期才出来」）。
 *
 * 这里钉的是 [showNotice] 相对 M3 `SnackbarHostState.showSnackbar` 的差别：后者是排队语义
 * （内部一把 `Mutex`），前者当场把当前那条 `dismiss()` 掉。两条策略：
 * ① 纯信息提示 → 新提示顶掉旧的；
 * ② **正在等用户动作的提示（有 `actionLabel`）不让位**——顶掉它等于把「撤销 / 去设置」的出口
 *    静默收走；它自己最长 `Long` 就会过期，让新提示等是有界的。
 *
 * 不需要 Compose 宿主：`SnackbarHostState` 的排队与 `dismiss` 都在状态里，计时器（真正的超时）
 * 才在 `SnackbarHost` 里——所以用例里所有提示都用 `Indefinite`，只考排队与顶替。
 */
class AppNoticeTest {

    /** 有界推进：让挂起的协程按顺序跑，最多 [steps] 步（跑不出来就返回 false，绝不空转）。 */
    private suspend fun awaitUntil(steps: Int = 64, condition: () -> Boolean): Boolean {
        repeat(steps) {
            if (condition()) return true
            yield()
        }
        return condition()
    }

    @Test
    fun newNoticeReplacesTheOneOnScreen() = runBlocking {
        val state = SnackbarHostState()
        val first = async { state.showSnackbar(AppNoticeVisuals("第一条", duration = SnackbarDuration.Indefinite)) }
        awaitUntil { state.currentSnackbarData != null }

        val second = async { state.showNotice(AppNoticeVisuals("第二条", duration = SnackbarDuration.Indefinite)) }
        awaitUntil { state.currentSnackbarData?.visuals?.message == "第二条" }

        // 在屏的是新的那条，且旧的**当场**结束（返回 Dismissed，而不是等到超时）
        assertEquals("第二条", state.currentSnackbarData?.visuals?.message)
        assertEquals(SnackbarResult.Dismissed, first.await())
        second.cancel()
    }

    /** 排队语义下第二条要等第一条结束才轮到——这条用例守着"我们为什么需要 showNotice"。 */
    @Test
    fun plainShowSnackbarStillQueues() = runBlocking {
        val state = SnackbarHostState()
        val first = async { state.showSnackbar(AppNoticeVisuals("第一条", duration = SnackbarDuration.Indefinite)) }
        awaitUntil { state.currentSnackbarData != null }
        val second = async { state.showSnackbar(AppNoticeVisuals("第二条", duration = SnackbarDuration.Indefinite)) }
        awaitUntil(steps = 8) { state.currentSnackbarData?.visuals?.message == "第二条" }

        // 第一条还挂着 → 第二条只能在锁外等，这正是用户看到的那条延迟
        assertEquals("第一条", state.currentSnackbarData?.visuals?.message)

        // 把第一条收掉，第二条立刻顶上来
        state.currentSnackbarData?.dismiss()
        awaitUntil { state.currentSnackbarData?.visuals?.message == "第二条" }
        assertEquals("第二条", state.currentSnackbarData?.visuals?.message)
        second.cancel()
    }

    /** 等用户动作的那条不让位：新提示只能排队（「撤销」不会因为别的播报被静默收走）。 */
    @Test
    fun actionableNoticeIsNotReplaced() = runBlocking {
        val state = SnackbarHostState()
        val undo = async {
            state.showNotice(AppNoticeVisuals("已删除", actionLabel = "撤销", duration = SnackbarDuration.Indefinite))
        }
        awaitUntil { state.currentSnackbarData != null }

        val other = async { state.showNotice(AppNoticeVisuals("别的播报", duration = SnackbarDuration.Indefinite)) }
        awaitUntil(steps = 8) { state.currentSnackbarData?.visuals?.message == "别的播报" }

        assertEquals("已删除", state.currentSnackbarData?.visuals?.message)
        assertEquals("撤销", state.currentSnackbarData?.visuals?.actionLabel)

        // 用户点了「撤销」→ 让位给排队的下一条
        state.currentSnackbarData?.performAction()
        awaitUntil { state.currentSnackbarData?.visuals?.message == "别的播报" }
        assertEquals("别的播报", state.currentSnackbarData?.visuals?.message)
        assertEquals(SnackbarResult.ActionPerformed, undo.await())
        other.cancel()
    }

    /**
     * 字符串重载的默认时长与 M3 逐字一致（`actionLabel` 非空 → `Indefinite`）：
     * 照抄是刻意的，改成 `Long` 会让带动作的提示提前过期。
     */
    @Test
    fun stringOverloadKeepsM3Defaults() = runBlocking {
        val state = SnackbarHostState()
        val job = async { state.showNotice("只有一句话") }
        awaitUntil { state.currentSnackbarData != null }
        assertEquals(SnackbarDuration.Short, state.currentSnackbarData?.visuals?.duration)
        assertEquals(null, state.currentSnackbarData?.visuals?.actionLabel)
        job.cancel()

        val state2 = SnackbarHostState()
        val job2 = async { state2.showNotice("带动作", actionLabel = "去设置") }
        awaitUntil { state2.currentSnackbarData != null }
        assertEquals(SnackbarDuration.Indefinite, state2.currentSnackbarData?.visuals?.duration)
        job2.cancel()
    }
}
