package edu.jxslu.schedule.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 「回到前台」计数：每次宿主窗口从后台（或被另一个 Activity 盖住）回到前台 +1。
 *
 * 用作 `remember` 的 key，强制重读一次只读快照（凭证在不在、CookieManager 里有没有会话）。
 * 为什么需要它：Compose 的组合在「被另一个 Activity 盖住又回来」时**不重建**，
 * `remember` 里的值原样留着；而登录态这类快照是组合期读一次的，从引导页 / 导入页 /
 * 校园卡设置页返回后不重读，卡片就停在旧状态（2026-09-27 用户报：在导入课表里登录过
 * 教务，「我的」页仍写着未登录）。
 *
 * **注册时补发的那次 ON_RESUME 不算**——它就是「进页」本身，首帧已经读过一次了；
 * 只有真正的「回来」才 +1，避免进页时白读两遍。
 */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var entering = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (entering) entering = false else tick++
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}
