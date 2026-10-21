package edu.jxslu.schedule.ui.me.hub

import edu.jxslu.schedule.domain.UpdateManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 启动自动检查 → 主界面弹窗的通道（DESIGN §3.3，2026-10-08）。
 *
 * 冷启动检查链（`JuwApplication`）发现新版本时 [publish]；宿主
 * [UpdateAvailableDialogHost] 挂在 MainActivity 根层（三个 Tab 之上）。
 *
 * 只存内存：进程被杀就丢——下次冷启动会重新检查，不值得落盘。
 * **与 `PayCodeResultBus` 的「取值即消费」不同：这里在用户关掉弹窗时
 * [consume]**——弹窗要活过转屏 / Activity 重建（取到即消费会闪没），而值只在
 * 本进程有效，不存在跨页迟到重弹的问题。
 */
object UpdateAvailableBus {

    private val _manifest = MutableStateFlow<UpdateManifest?>(null)
    val manifest: StateFlow<UpdateManifest?> = _manifest.asStateFlow()

    fun publish(manifest: UpdateManifest) {
        _manifest.value = manifest
    }

    fun consume() {
        _manifest.value = null
    }
}
