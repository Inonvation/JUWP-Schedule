package edu.jxslu.schedule

import edu.jxslu.schedule.domain.InboxKind
import edu.jxslu.schedule.domain.InboxMessage
import edu.jxslu.schedule.domain.InboxRules
import edu.jxslu.schedule.domain.InboxRules.PostDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收件箱投递与清理决策（DESIGN §4.38）：去重语义与保留策略的契约。
 *
 * 投递三条：无键插入 / 未删刷新并重新置未读 / 已删只刷新不复活。
 * 清理三条：软删 30 天清除 / 已读 90 天清除 / 未删总量 200 条保最新。
 */
class InboxRulesTest {

    private val now = 1_800_000_000_000L

    private fun msg(
        id: Long,
        createdAt: Long = now,
        readAt: Long? = null,
        deletedAt: Long? = null,
        key: String = "k$id",
    ) = InboxMessage(
        id = id,
        kind = InboxKind.EXAM,
        dedupeKey = key,
        title = "t",
        body = "b",
        createdAt = createdAt,
        readAt = readAt,
        deletedAt = deletedAt,
    )

    // ------------------------------------------------------------------
    // 投递决策
    // ------------------------------------------------------------------

    @Test
    fun `无同键消息时插入新行`() {
        assertEquals(PostDecision.Insert, InboxRules.onPost(existing = null, now = now))
    }

    @Test
    fun `同键未删消息刷新并重新置未读`() {
        // 已读的老消息重投 → Refresh：余额「已读后次日仍低 → 重新未读」靠这一条
        val existing = msg(id = 7, createdAt = now - 1, readAt = now - 1)
        assertEquals(PostDecision.Refresh(7), InboxRules.onPost(existing, now))
        // 未读的同键消息重投同样落 Refresh（内容刷新、保持未读）
        val unread = msg(id = 8)
        assertEquals(PostDecision.Refresh(8), InboxRules.onPost(unread, now))
    }

    @Test
    fun `同键已删消息只刷新内容不复活`() {
        val existing = msg(id = 9, deletedAt = now - 100)
        assertEquals(PostDecision.RefreshDeleted(9), InboxRules.onPost(existing, now))
    }

    // ------------------------------------------------------------------
    // 保留策略
    // ------------------------------------------------------------------

    @Test
    fun `软删超三十天物理清除未到期的保留`() {
        val stale = msg(id = 1, deletedAt = now - InboxRules.DELETED_RETENTION_MS - 1)
        val fresh = msg(id = 2, deletedAt = now - InboxRules.DELETED_RETENTION_MS + 1)
        val plan = InboxRules.planPurge(listOf(stale, fresh), now)
        assertEquals(listOf(1L), plan.ids)
    }

    @Test
    fun `已读且创建超九十天清除未读的再老也保留`() {
        val old = now - InboxRules.READ_RETENTION_MS - 1
        val readOld = msg(id = 1, createdAt = old, readAt = now - 1)
        val unreadOld = msg(id = 2, createdAt = old)
        val readRecent = msg(id = 3, createdAt = now - InboxRules.READ_RETENTION_MS + 1, readAt = now)
        val plan = InboxRules.planPurge(listOf(readOld, unreadOld, readRecent), now)
        assertEquals(listOf(1L), plan.ids)
    }

    @Test
    fun `总量超上限保最新删最旧`() {
        val messages = (1..InboxRules.MAX_MESSAGES + 5L).map { id ->
            // createdAt 相同时按 id 倒序保新：id 大的算新
            msg(id = id, createdAt = now, readAt = now)
        }
        val plan = InboxRules.planPurge(messages, now)
        assertEquals(5, plan.ids.size)
        // 被裁的正是最旧的 5 条（id 最小）
        assertEquals((1L..5L).toList(), plan.ids.sorted())
    }

    @Test
    fun `软删未到期的行不计入总量上限`() {
        // 200 条正常 + 50 条已软删（未到期）→ 上限内一条不裁
        val alive = (1..InboxRules.MAX_MESSAGES.toLong()).map { id -> msg(id = id, readAt = now) }
        val deleted = (1001..1050L).map { id ->
            msg(id = id, deletedAt = now - 1)
        }
        val plan = InboxRules.planPurge(alive + deleted, now)
        assertTrue(plan.ids.isEmpty())
    }

    @Test
    fun `上限裁剪只针对未删行`() {
        // 201 条未删（其中 1 条已软删到期）+ 1 条软删未到期：到期那条清掉，其余不动
        val toPurge = msg(id = 1, deletedAt = now - InboxRules.DELETED_RETENTION_MS - 1)
        val alive = (2..InboxRules.MAX_MESSAGES + 1L).map { id -> msg(id = id, readAt = now) }
        val plan = InboxRules.planPurge(listOf(toPurge) + alive, now)
        assertEquals(listOf(1L), plan.ids)
    }
}
