package edu.jxslu.schedule.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.LegalNotice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「使用前须知」周期声明的宿主（`domain/LegalNotice`）。
 *
 * 挂在 `MainActivity` 根层（`JuwApp` 之后），所以**任何页面之上**都能弹。
 *
 * 触发时机两条，**都直接 `first()` 读当前真值**（不依赖 `collectAsState` 的重组时序——
 * 冷启动首帧偏好还没读到，靠重组补判会漏）：
 * 1. **冷启动**（`LaunchedEffect`）——读一次判一次；
 * 2. **每次回到前台**（`LifecycleEventEffect(ON_RESUME)`）——覆盖"App 挂后台跨过 3 天"
 *    或"进程没被杀、隔天再打开"。
 *
 * 点「同意」/点弹窗外都算关闭并**记下弹出时刻**（[LegalNotice.shouldShow] 据此判下一次），
 * 没有"不再提醒"的出口——这是用户要的"不能永久关闭"。
 *
 * 首启走的是独立 Activity（`OnboardingActivity`），本宿主在它结束后回到主界面时才
 * `ON_RESUME`，所以新用户也会弹到这一份（与首启那两份在时间上错开）。
 */
@Composable
fun LegalNoticeHost() {
    val context = LocalContext.current
    val prefs = remember { Graph.displayPrefs(context) }
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 读当前"上次弹出时刻"，到期就置显。真值读取，判完即返回，不挂状态。
    suspend fun checkDue() {
        if (visible) return
        val last = prefs.legalNoticeLastShownAt.first()
        if (LegalNotice.shouldShow(last, System.currentTimeMillis())) visible = true
    }

    LaunchedEffect(Unit) { checkDue() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { checkDue() } }

    if (visible) {
        LegalNoticeDialog(
            onDismiss = {
                visible = false
                // 先落盘再显示下一轮：写失败也不阻塞关闭（顶多下次冷启动再弹一次）
                scope.launch { prefs.markLegalNoticeShown() }
            },
        )
    }
}
