package edu.jxslu.schedule.ui.common

import edu.jxslu.schedule.domain.DormRepairTone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 宿舍报修卡 / 详情页共用的两个小口径（DESIGN §4.49）：
 * 语气映射只此一处；时间格式只此一处。
 */
internal fun DormRepairTone.toHeroTone(): HeroTone = when (this) {
    DormRepairTone.Primary -> HeroTone.Primary
    DormRepairTone.Neutral -> HeroTone.Neutral
    DormRepairTone.Success -> HeroTone.Success
    DormRepairTone.Error -> HeroTone.Error
}

internal fun formatDormRepairTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA))

/** 审批人列表太长时只留前 3 个；完整名单在详情页流程卡里也按这个口径收。 */
internal fun approverSummary(names: List<String>, max: Int = 3): String {
    if (names.isEmpty()) return ""
    val head = names.take(max).joinToString("、")
    return if (names.size > max) "$head 等 ${names.size} 人" else head
}
