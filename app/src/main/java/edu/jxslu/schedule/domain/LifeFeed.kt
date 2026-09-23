package edu.jxslu.schedule.domain

/**
 * 生活页流水（DESIGN §3.13）：一卡通消费与寝室电费充值混排。
 *
 * 两个来源的原始记录形态完全不同（一卡通在本地 Room、电费在缴费平台接口），
 * 混排的排序与截断规则放在这里做纯逻辑，UI 只渲染结果。
 */

/** 流水来源（决定图标与副标题前缀）。 */
enum class LifeFeedKind {
    /** 一卡通消费/充值。 */
    CampusCard,

    /** 寝室电费充值。 */
    Power,
}

/** 混排后的一条流水。金额按分存，[income] = 入账（充值/退款）。 */
data class LifeFeedItem(
    val kind: LifeFeedKind,
    /** 交易时刻（epoch 毫秒）；解析不出来的记录为 0，排序沉底。 */
    val epochMs: Long,
    /** 时刻原文（展示用，如 `2026-09-23 12:09`）。 */
    val timeText: String,
    val title: String,
    val subtitle: String,
    val amountFen: Long,
    val income: Boolean,
)

object LifeFeed {

    /** 生活页「最近流水」条数。 */
    const val DEFAULT_LIMIT = 5

    /**
     * 合并两个来源并按时间倒序取前 [limit] 条。
     *
     * 时间相同（同秒操作）时保持入参顺序（Kotlin 的 `sortedWith` 稳定），
     * 于是同一批数据每次进页的顺序一致，不会跳来跳去。
     */
    fun merge(
        campusCard: List<LifeFeedItem>,
        power: List<LifeFeedItem>,
        limit: Int = DEFAULT_LIMIT,
    ): List<LifeFeedItem> {
        if (limit <= 0) return emptyList()
        return (campusCard + power)
            .sortedWith(compareByDescending { it.epochMs })
            .take(limit)
    }
}
