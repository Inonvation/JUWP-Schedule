package edu.jxslu.schedule.domain

/**
 * 生活页流水（DESIGN §3.13）：一卡通消费与寝室电费充值。
 *
 * 两个来源的原始记录形态完全不同（一卡通在本地 Room、电费在缴费平台接口），
 * 分组、排序与截断规则放在这里做纯逻辑，UI 只渲染结果。
 *
 * **2026-09-26 起不再混排**：原来两个来源合成一条列表、共用一个出口，于是「全部流水」
 * 进去只有一卡通、「缴费账单」进去只有电费，点之前看不出会看到什么。现在按来源分两段，
 * 出口各自落在自己那段标题上，列表内容和出口一一对应。
 */

/** 流水来源（决定图标与副标题前缀）。 */
enum class LifeFeedKind {
    /** 一卡通消费/充值。 */
    CampusCard,

    /** 寝室电费充值。 */
    Power,
}

/** 一条流水。金额按分存，[income] = 入账（充值/退款）。 */
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

/**
 * 生活页「最近流水」按来源分好的两段。
 *
 * 两段各自可空：一卡通没同步过、电费没充过值时只有一段有内容，[isEmpty] 才是整卡空态。
 */
data class LifeFeedSections(
    val campusCard: List<LifeFeedItem> = emptyList(),
    val power: List<LifeFeedItem> = emptyList(),
) {
    val isEmpty: Boolean get() = campusCard.isEmpty() && power.isEmpty()
}

object LifeFeed {

    /** 每段展示条数。两段各取这么多条，合起来仍是过去一屏的量级。 */
    const val SECTION_LIMIT = 2

    /**
     * 按来源分段：各自按时间倒序取前 [limitPerSection] 条。
     *
     * 时间相同（同秒操作）时保持入参顺序（Kotlin 的 `sortedWith` 稳定），
     * 于是同一批数据每次进页的顺序一致，不会跳来跳去。
     */
    fun sections(
        campusCard: List<LifeFeedItem>,
        power: List<LifeFeedItem>,
        limitPerSection: Int = SECTION_LIMIT,
    ): LifeFeedSections {
        if (limitPerSection <= 0) return LifeFeedSections()
        return LifeFeedSections(
            campusCard = campusCard
                .sortedWith(compareByDescending { it.epochMs })
                .take(limitPerSection),
            power = power
                .sortedWith(compareByDescending { it.epochMs })
                .take(limitPerSection),
        )
    }
}
