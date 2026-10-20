package edu.jxslu.schedule.data.ir

import android.content.Context
import android.hardware.ConsumerIrManager
import android.util.Log
import edu.jxslu.schedule.domain.MideaR05d

/**
 * 空调红外发射（DESIGN §4.48）：ConsumerIrManager 的薄封装。
 *
 * 平台的红外出口只有一条：`ConsumerIrManager.transmit(载波频率, 时长序列)`，
 * 由系统服务转给红外 HAL；`TRANSMIT_IR` 是普通权限（安装即授予，无运行时申请）。
 * 时长序列由协议层（[MideaR05d]）生成，这里只管发射与「有没有发射器」。
 *
 * 注意 `transmit` 在多数机型上是**同步**的（等整条序列发完才返回，
 * 一组约 192 ms，ECO 三帧约 286 ms），
 * 所以调用方必须在后台线程、并串行化调用。
 */
class AcIrBlaster(context: Context) {

    private val manager: ConsumerIrManager? =
        context.applicationContext.getSystemService(ConsumerIrManager::class.java)

    /** 本机有没有红外发射器（没有时页面给提示，不发空炮）。 */
    val supported: Boolean
        get() = manager?.hasIrEmitter() == true

    /**
     * 发射一条时长序列。
     *
     * 失败不再静默：系统拒绝（含小米 `ir-spi` HAL 因总时长超限整条丢弃）与无发射器
     * 都返回带原因的 [TransmitResult]，由页面提示——否则只有「按了没反应」这一种观感。
     */
    fun transmit(pattern: IntArray): TransmitResult {
        val current = manager
            ?: return TransmitResult.Failed("本机没有红外服务")
        if (!current.hasIrEmitter()) return TransmitResult.Failed("本机没有红外发射器")
        val totalUs = MideaR05d.totalMicros(pattern)
        if (totalUs > MideaR05d.TRANSMIT_BUDGET_US) {
            // 自己先拦：超预算的序列在小米上是整条丢弃，发出去等于没发。
            Log.w(TAG, "pattern 超预算被拦下: ${totalUs}us")
            return TransmitResult.Failed("发射序列过长（${totalUs / 1000}ms）")
        }
        return runCatching { current.transmit(MideaR05d.FREQUENCY_HZ, pattern) }
            .fold(
                onSuccess = { TransmitResult.Sent },
                onFailure = { error ->
                    Log.w(TAG, "transmit 失败", error)
                    TransmitResult.Failed(error.message ?: "系统拒绝发射")
                },
            )
    }

    /** 一次发射的结果。[Sent] 只代表系统收下了，空调有没有收到红外没有回执。 */
    sealed interface TransmitResult {
        data object Sent : TransmitResult
        data class Failed(val reason: String) : TransmitResult
    }

    private companion object {
        const val TAG = "AcIrBlaster"
    }
}
