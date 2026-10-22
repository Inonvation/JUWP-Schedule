package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预选命中归组（DESIGN §3.20 / §4.36，2026-10-08 v3「预购匹配」）：
 * 优先级排序、同一课程只归一条、教师关键词本地复核。
 */
class SelectionWishHitsTest {

    private fun wish(
        id: String,
        name: String,
        teacher: String = "",
        priority: Int = SelectionWishes.PRIORITY_MEDIUM,
    ) = SelectionWish(id = id, nameKeyword = name, teacherKeyword = teacher, priority = priority)

    private fun course(id: String, name: String, teacher: String = "张三") =
        SelectionCourse(id = id, name = name, teacher = teacher)

    @Test
    fun groupsByPriorityAndClaimsEachCourseOnce() {
        val w1 = wish("w1", "机械", priority = SelectionWishes.PRIORITY_HIGH)
        val w2 = wish("w2", "机械设计", priority = SelectionWishes.PRIORITY_LOW)
        val a = course("a", "机械设计基础A")
        val b = course("b", "机械制造基础A")
        val c = course("c", "机械设计课程设计")
        val hits = SelectionWishHits.group(
            wishes = listOf(w2, w1), // 乱序传入也要按优先级排
            fetched = mapOf("w1" to listOf(a, b), "w2" to listOf(a, c)),
        )
        assertEquals(listOf("w1", "w2"), hits.map { it.wish.id })
        // a 被优先级更高的 w1 认领，w2 只剩 c
        assertEquals(listOf("a", "b"), hits[0].courses.map { it.id })
        assertEquals(listOf("c"), hits[1].courses.map { it.id })
    }

    @Test
    fun teacherKeywordIsRecheckedLocally() {
        // 服务端只按课程名查，教师关键词必须本地复核（与清单页防重复对照同一口径）
        val w = wish("w1", "机械", teacher = "王")
        val hits = SelectionWishHits.group(
            wishes = listOf(w),
            fetched = mapOf(
                "w1" to listOf(
                    course("x", "机械设计基础A", teacher = "王五,李四"),
                    course("y", "机械制造基础A", teacher = "张三"),
                ),
            ),
        )
        assertEquals(1, hits.size)
        assertEquals(listOf("x"), hits[0].courses.map { it.id })
    }

    @Test
    fun samePriorityKeepsEntryOrder() {
        val first = wish("w1", "机械")
        val second = wish("w2", "机械")
        val shared = course("a", "机械设计基础A")
        val hits = SelectionWishHits.group(
            wishes = listOf(first, second),
            fetched = mapOf("w1" to listOf(shared), "w2" to listOf(shared)),
        )
        // 同档保持录入顺序：先到先得
        assertEquals(listOf("w1"), hits.map { it.wish.id })
    }

    @Test
    fun emptyOrFailedFetchIsSkipped() {
        val w1 = wish("w1", "机械")
        val w2 = wish("w2", "液压")
        val hits = SelectionWishHits.group(
            wishes = listOf(w1, w2),
            fetched = mapOf("w2" to listOf(course("h", "液压与气压传动A"))),
        )
        // w1 没有结果（查询失败 / 没命中）→ 不占位
        assertEquals(listOf("w2"), hits.map { it.wish.id })
        assertTrue(SelectionWishHits.group(emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun idsAndCountCoverAllHitRows() {
        val w1 = wish("w1", "机械", priority = SelectionWishes.PRIORITY_HIGH)
        val w2 = wish("w2", "液压")
        val hits = SelectionWishHits.group(
            wishes = listOf(w1, w2),
            fetched = mapOf(
                "w1" to listOf(course("a", "机械设计基础A"), course("b", "机械制造基础A")),
                "w2" to listOf(course("h", "液压与气压传动A")),
            ),
        )
        assertEquals(setOf("a", "b", "h"), SelectionWishHits.ids(hits))
        assertEquals(3, SelectionWishHits.count(hits))
    }

    @Test
    fun blankCourseNameNeverMatches() {
        val w = wish("w1", "机械")
        val hits = SelectionWishHits.group(
            wishes = listOf(w),
            fetched = mapOf("w1" to listOf(course("z", ""))),
        )
        assertTrue(hits.isEmpty())
    }
}
