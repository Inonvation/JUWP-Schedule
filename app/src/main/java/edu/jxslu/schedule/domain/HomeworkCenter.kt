package edu.jxslu.schedule.domain

import java.time.LocalDate

/**
 * 未完成作业的口径（DESIGN §3.11）：排序、计数、截止文案全在这里，纯函数可 JVM 测
 * （`HomeworkCenterTest`）。今日页作业卡、作业中心、课程作业列表共用同一份，
 * 免得三处各写一遍排序把口径写分叉。
 */
data class PendingHomework(
    /** 未完成作业，已按「逾期 → 今天 → 未来 → 无截止」排序（见 [sortPendingHomework]）。 */
    val items: List<Homework>,
    /** 已过期（截止日 **早于** 今天）的条数；截止日当天不算过期。 */
    val overdue: Int,
    /** 最近一个**尚未过期**的截止日期（含今天）；没有则为 null。 */
    val nextDue: LocalDate?,
    /** 最早的截止日期（含逾期）；全部未设截止时为 null。 */
    val earliestDue: LocalDate?,
) {
    val total: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        val EMPTY = PendingHomework(emptyList(), 0, null, null)
    }
}

/**
 * 未完成作业排序（今日卡与作业中心共用）：
 *
 * 1. 逾期（截止 < 今天）：**最近过期的在前**——刚错过的最该先处理，陈年旧账沉底但仍可见；
 *    若按升序，一条上月的作业会永久压在最上面。
 * 2. 今天与未来（截止 ≥ 今天）：截止升序，最快到的在前。
 * 3. 无截止：按创建时间倒序（新布置的在前）。
 *
 * 已完成的不参与（列表侧自行过滤）。
 */
fun sortPendingHomework(items: List<Homework>, today: LocalDate): List<Homework> =
    items.sortedWith { a, b ->
        val ra = dueRank(a, today)
        val rb = dueRank(b, today)
        val byRank = if (ra != rb) ra - rb else 0
        if (byRank != 0) {
            byRank
        } else {
            val byDue = when (ra) {
                // 逾期组：最近过期的在前（降序）
                0 -> b.dueDate!!.compareTo(a.dueDate!!)
                // 今天 + 未来：截止升序
                1 -> a.dueDate!!.compareTo(b.dueDate!!)
                // 无截止：新创建的在前
                else -> b.createdAt.compareTo(a.createdAt)
            }
            if (byDue != 0) byDue else b.id.compareTo(a.id)
        }
    }

private fun dueRank(homework: Homework, today: LocalDate): Int = when {
    homework.dueDate == null -> 2
    homework.dueDate.isBefore(today) -> 0
    else -> 1
}

/** 未完成作业的过滤 + 排序 + 汇总；[all] 可含已完成（会被过滤掉）。 */
fun pendingHomework(all: List<Homework>, today: LocalDate): PendingHomework {
    val pending = all.filter { !it.done }
    if (pending.isEmpty()) return PendingHomework.EMPTY
    val due = pending.mapNotNull { it.dueDate }
    return PendingHomework(
        items = sortPendingHomework(pending, today),
        overdue = due.count { it.isBefore(today) },
        nextDue = due.filter { !it.isBefore(today) }.minOrNull(),
        earliestDue = due.minOrNull(),
    )
}

/**
 * 课程作业列表的排序（DESIGN §3.11）：**未完成在前**（走 [sortPendingHomework] 的统一口径），
 * 已完成在后、最近完成的在前（刚勾掉的在最上面，方便就地反悔）。
 */
fun courseHomeworkOrder(all: List<Homework>, today: LocalDate): List<Homework> {
    val pending = sortPendingHomework(all.filter { !it.done }, today)
    val done = all.filter { it.done }
        .sortedWith(
            compareByDescending<Homework> { it.doneAt ?: it.updatedAt }
                .thenByDescending { it.id },
        )
    return pending + done
}

/**
 * 截止文案（作业行右侧 chip、今日卡副行共用）：
 * `今天` / `明天` / `已过期 N 天` / `M月d日`；[due] 为 null（未设截止）返回 null。
 */
fun dueLabel(due: LocalDate?, today: LocalDate): String? {
    if (due == null) return null
    val days = due.toEpochDay() - today.toEpochDay()
    return when {
        days == 0L -> "今天"
        days == 1L -> "明天"
        days < 0L -> "已过期 ${-days} 天"
        else -> MONTH_DAY_FORMAT.format(due)
    }
}
