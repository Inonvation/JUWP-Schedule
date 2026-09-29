package edu.jxslu.schedule.domain

/**
 * 本机骑行记录（DESIGN §3.9「最近骑行」，2026-09-28）。
 *
 * 为什么自己记：快趣只给了「进行中订单」与「未支付订单」两个接口，**没有订单列表**
 * （逆向产物 `docs/kvcoo-miniprogram-analysis.md` 可查），"这个月骑了几次、花了多少"
 * 只能由本机记下来。
 *
 * **只记本机用车（快趣）这条链路**：它的起点（真正开锁时刻）、终点（还车确认）、车号与
 * 费用都齐。「点扫一扫 → 在微信里骑」那条路只有"点按时刻"这个猜测值，终点还得靠免费计时
 * 或手动结束去猜——把猜出来的数字写进统计是污染，所以不记（`.agents/rules/ebike.md`）。
 *
 * 纯数据 + 纯格式化（[durationText] 可单测）；Room 实体与读写见
 * `data/local/RideRecordEntity.kt` 与 `data/repo/RideRecordStore.kt`。
 */
data class RideRecord(
    val id: Long,
    val carNum: String,
    /** 开锁时刻（epoch 毫秒）。 */
    val startAt: Long,
    /** 还车时刻。 */
    val endAt: Long,
    /** 骑行时长（秒）；快趣没给过时长时为 0（列表显示「不到 1 分钟」，不编数字）。 */
    val durationSeconds: Long,
    /** 费用（分）；null = 快趣没给过当前费用（比如刚开锁就还车）。 */
    val feeCents: Long?,
    /** 结算状态：true = 已结清 / 无需支付；false = 还有未结算费用（以快趣为准）。 */
    val settled: Boolean,
) {
    companion object {
        /** 本机保留的记录上限（超出丢最旧）：这是"最近骑了什么"的回顾，不当时序数据库用。 */
        const val LIMIT = 100

        /** 快趣页展示的条数：再往回翻也没人看，列表越短越干净。 */
        const val SHOW_LIMIT = 8

        /**
         * 时长文案（分钟 / 小时口径）：历史列表用**人话**，不用骑行卡那一套
         * `formatRideDuration` 的怪格式（`00:13` 那种是官方小程序的展示口径，
         * 放在"上周骑了几次"的回顾里只会让人读成 13 秒）。
         */
        fun durationText(seconds: Long): String {
            val minutes = seconds.coerceAtLeast(0) / 60
            val hours = minutes / 60
            val rest = minutes % 60
            return when {
                hours > 0 && rest > 0 -> "$hours 小时 $rest 分钟"
                hours > 0 -> "$hours 小时"
                minutes > 0 -> "$minutes 分钟"
                else -> "不到 1 分钟"
            }
        }
    }
}
