package edu.jxslu.schedule.data.ir

import android.content.Context
import android.hardware.ConsumerIrManager
import edu.jxslu.schedule.domain.MideaAc

/**
 * 空调红外发射（DESIGN §4.48）：ConsumerIrManager 的薄封装。
 *
 * 平台的红外出口只有一条：`ConsumerIrManager.transmit(载波频率, 时长序列)`，
 * 由系统服务转给红外 HAL；`TRANSMIT_IR` 是普通权限（安装即授予，无运行时申请）。
 * 时长序列由 [MideaAc.pattern] 生成，这里只管发射与「有没有发射器」。
 */
class AcIrBlaster(context: Context) {

    private val manager: ConsumerIrManager? =
        context.applicationContext.getSystemService(ConsumerIrManager::class.java)

    /** 本机有没有红外发射器（没有时页面给提示，不发空炮）。 */
    val supported: Boolean
        get() = manager?.hasIrEmitter() == true

    /** 发射一组波形；返回是否真的发出（无发射器 / 系统拒绝时 false）。 */
    fun transmit(pattern: IntArray): Boolean {
        val current = manager ?: return false
        if (!current.hasIrEmitter()) return false
        return runCatching { current.transmit(MideaAc.FREQUENCY_HZ, pattern) }.isSuccess
    }
}
