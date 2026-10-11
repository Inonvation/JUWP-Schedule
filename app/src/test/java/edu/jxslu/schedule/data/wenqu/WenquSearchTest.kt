package edu.jxslu.schedule.data.wenqu

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 搜索命中的合并（DESIGN §4.45）——本地索引命中 ∪ 服务端兜底命中。
 *
 * 纯函数、JVM 可测；夹具用假活动名，不碰任何真实数据。
 */
class WenquSearchTest {

    private fun activity(id: Long, title: String = "活动$id") = WenquActivity(id = id, title = title)

    @Test
    fun `合并搜索命中_按 id 去重且本地那份优先`() {
        val local = listOf(activity(1, "本地1"), activity(2, "本地2"))
        val server = listOf(activity(2, "服务端2"), activity(3, "服务端3"))
        val merged = mergeActivities(local, server)
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
        // 两边都有 id=2 时保留本地那份（本地字段来自同一份数据，且已过索引）
        assertEquals("本地2", merged[1].title)
    }

    @Test
    fun `合并_服务端没结果就原样返回本地`() {
        val local = listOf(activity(1), activity(2))
        assertEquals(local, mergeActivities(local, emptyList()))
    }

    @Test
    fun `增量合并_新页在前_同 id 以新页为准`() {
        // 平台列表开头按 id 倒序，新活动一定在最前面
        val old = listOf(activity(3, "旧3"), activity(2, "旧2"), activity(1, "旧1"))
        val fresh = listOf(activity(5, "新5"), activity(4, "新4"), activity(3, "更新过的3"))
        val merged = mergeNewest(old, fresh, cap = 100)
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), merged.map { it.id })
        // 同 id 的两份以**新页**为准（名额 / 状态 / 已报名这些可变字段要新值）
        assertEquals("更新过的3", merged[2].title)
    }

    @Test
    fun `增量合并_新页为空就原样返回`() {
        val old = listOf(activity(1), activity(2))
        assertEquals(old, mergeNewest(old, emptyList(), cap = 100))
    }

    @Test
    fun `增量合并_超上限砍尾巴`() {
        val old = (1L..10L).map { activity(it) }
        val fresh = listOf(activity(11), activity(12))
        val merged = mergeNewest(old, fresh, cap = 5)
        // 新的留前面，砍的是最旧的尾巴
        assertEquals(listOf(11L, 12L, 1L, 2L, 3L), merged.map { it.id })
    }

    @Test
    fun `合并_没有 id 的条目不去重_别把好几条挤成一条`() {
        // 平台偶尔不给 id（收成 0）：它们没有身份，按 0 合并会把不同活动吞掉
        val local = listOf(activity(0, "A"))
        val server = listOf(activity(0, "B"), activity(0, "C"))
        assertEquals(3, mergeActivities(local, server).size)
    }
}
