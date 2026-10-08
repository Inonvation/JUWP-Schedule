package edu.jxslu.schedule.domain

/**
 * 预选清单的一条命中分组（DESIGN §3.20 / §4.36，2026-10-08 v3「预购匹配」）。
 *
 * 数据来源：选课中心按预选清单**逐条**查教务（服务端只按课程名/编号搜，教师关键词
 * 本地复核）→ 命中课程优先显示，选课仍由用户逐次确认（没有任何自动提交路径）。
 */
data class WishHit(
    val wish: SelectionWish,
    /** 这条预选命中的候选班级（同一门课的不同教学班各占一行，同教务列表口径）。 */
    val courses: List<SelectionCourse>,
)

/**
 * 预选命中的归组（纯逻辑，JVM 可测）。
 *
 * 三条口径：
 * 1. **教师关键词本地复核**——服务端查询只带课程名关键词，`SelectionWishes.matches`
 *    在这里补上教师匹配（口径与清单页的防重复对照同源）；
 * 2. **同一门课只归一条预选**——被多条预选命中时归**优先级最高**的那条（同档取录入
 *    顺序在前者）：[SelectionWishes.sorted] 是稳定降序，先到先得即可；
 * 3. 分组按优先级降序稳定排序（同 `sorted` 口径），空命中不占位。
 */
object SelectionWishHits {

    /**
     * [fetched] = 预选 id → 该预选关键词查回的候选行（服务端结果，未复核教师）。
     * 没有结果 / 查询失败的预选不会出现在返回值里。
     */
    fun group(
        wishes: List<SelectionWish>,
        fetched: Map<String, List<SelectionCourse>>,
    ): List<WishHit> {
        val claimed = mutableSetOf<String>()
        val hits = mutableListOf<WishHit>()
        SelectionWishes.sorted(wishes).forEach { wish ->
            val courses = fetched[wish.id].orEmpty()
                .filter { SelectionWishes.matches(wish, it.name, it.teacher) }
                // 先到先得 = 优先级高者认领（sorted 稳定降序保证顺序即优先级）
                .filter { claimed.add(it.id) }
            if (courses.isNotEmpty()) hits += WishHit(wish = wish, courses = courses)
        }
        return hits
    }

    /** 命中课程的 id 集合（主列表给命中行打标用）。 */
    fun ids(hits: List<WishHit>): Set<String> =
        hits.flatMapTo(mutableSetOf()) { hit -> hit.courses.map { it.id } }

    /** 命中总条数（教学班行级，区块标题与状态行用）。 */
    fun count(hits: List<WishHit>): Int = hits.sumOf { it.courses.size }
}
