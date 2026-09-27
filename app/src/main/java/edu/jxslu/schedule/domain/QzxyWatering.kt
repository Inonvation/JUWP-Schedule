package edu.jxslu.schedule.domain

/**
 * 一次进行中的用水（DESIGN §3.18）。
 *
 * 存在的理由：开阀成功到结束用水之间，设备在放水、账户挂着预扣，而这段时间
 * 用户可能切走 App、甚至进程被杀。状态落在本地，重进页面才能提醒
 * 「上次开阀 21:02，可能还在用水，去结算」。
 *
 * 本文件只有纯 JVM 计算与文案，不含 Android API，可单测。
 */
data class QzxyWatering(
    /** 开阀成功的时刻（epoch 毫秒）。 */
    val startedAtMillis: Long,
    /** 设备蓝牙地址，用来在重进页面时回填设备。 */
    val deviceAddress: String,
    /** 设备显示名，蓝牙地址变了也能认出是哪一台。 */
    val deviceName: String,
    /** 服务端下单返回的预扣金额，单位**厘**（0.04 元返回 40）。空 = 服务端没给。 */
    val preDeductMilli: String? = null,
) {
    /** 已用水时长（毫秒）；起点在未来时按 0 计，防线脏数据。 */
    fun elapsedMillis(nowMillis: Long): Long = (nowMillis - startedAtMillis).coerceAtLeast(0L)

    /**
     * 是否超过 [expireMillis]（自开阀时刻起算）。
     *
     * 用途：本地「用水中」是一条**离线记账**——设备是不是真在放水只有问了才知道。
     * 记账超时（默认 1 小时）后它更可能是「开完没结算的残留」，提醒文案与
     * 空闲态自动清理的判断都以它为准，不能永远挂着。
     */
    fun isExpired(nowMillis: Long, expireMillis: Long = EXPIRE_MILLIS): Boolean =
        nowMillis - startedAtMillis >= expireMillis

    companion object {
        /**
         * 「用水中」记账的过期时长：1 小时。
         *
         * 洗澡以分钟计，1 小时还没结算基本只有两种可能：结算走不通被搁置，
         * 或者手机没电/进程被杀。过期后界面把这条当残留处理（见
         * `QzxyViewModel.applyWatering`），不再显示进行中的计时。
         */
        const val EXPIRE_MILLIS: Long = 60L * 60 * 1000
    }
}

/**
 * 用水相关的文案格式化。金额口径与 [edu.jxslu.schedule.domain.QzxyWatering] 同源，
 * 全 App 只此一处换算，避免「预扣」与「实扣」两处各写一遍。
 */
object QzxyWateringFormat {

    /**
     * 厘 → 「¥x.xx」。服务端不给金额时返回兜底文案。
     *
     * 结算接口的 `consumeMoney` 与下单接口的 `preDeductMoney` 都是厘。
     */
    fun money(milli: String?): String {
        val value = milli?.trim()?.toLongOrNull() ?: return "金额待服务端结算"
        return "¥%.2f".format(value / 1000.0)
    }

    /** 用水时长：不足一小时 `mm:ss`，超过一小时 `h:mm:ss`。 */
    fun duration(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    /** 开阀时刻的短文案，用于「上次开阀 21:02」。 */
    fun clockText(epochMillis: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(epochMillis))
}
