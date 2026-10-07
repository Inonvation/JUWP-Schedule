package edu.jxslu.schedule.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免扰推迟队列的纯逻辑契约（DESIGN §3.7）：
 * - upsert 幂等键 = `(tag, notificationId)`，同键替换不堆叠，超 CAP 截最老；
 * - flushPlan：时刻型（eventAt 非空）已过期（>10 分钟）丢弃，事实型永远保留；
 * - encode/decode 往返；坏 JSON 由 load 层兜空表（不在此测）。
 */
class QuietHoursQueueTest {

    private fun entry(
        tag: String? = "t",
        id: Int = 1,
        eventAt: Long? = null,
        requestCode: Int = 3005,
    ) = PendingNotification(
        tag = tag,
        notificationId = id,
        channelId = "ch",
        category = "reminder",
        title = "标题",
        text = "正文",
        eventAt = eventAt,
        enqueuedAt = 1000L,
        launch = PendingLaunch(
            kind = PendingLaunch.Kind.SUBPAGE,
            requestCode = requestCode,
            screen = "SCORES",
            clearTask = true,
        ),
    )

    // ---- upsert ----

    @Test
    fun `同幂等键替换不堆叠`() {
        val list = QuietHoursQueue.upsertInto(emptyList(), entry(tag = "t", id = 1))
        val updated = QuietHoursQueue.upsertInto(
            list,
            entry(tag = "t", id = 1).copy(title = "替换后"),
        )
        assertEquals(1, updated.size)
        assertEquals("替换后", updated[0].title)
    }

    @Test
    fun `不同键共存新在顶`() {
        var list = QuietHoursQueue.upsertInto(emptyList(), entry(tag = "a", id = 1))
        list = QuietHoursQueue.upsertInto(list, entry(tag = "b", id = 2))
        assertEquals(2, list.size)
        assertEquals("b", list[0].tag) // 新条目在顶
    }

    @Test
    fun `tag 为 null 时按 id 区分`() {
        var list = QuietHoursQueue.upsertInto(emptyList(), entry(tag = null, id = 1))
        list = QuietHoursQueue.upsertInto(list, entry(tag = null, id = 2))
        assertEquals(2, list.size)
        list = QuietHoursQueue.upsertInto(list, entry(tag = null, id = 1).copy(title = "替换"))
        assertEquals(2, list.size)
        assertEquals("替换", list.first { it.notificationId == 1 }.title)
    }

    @Test
    fun `超出 CAP 截掉最老的`() {
        var list = emptyList<PendingNotification>()
        for (i in 1..(QuietHoursQueue.CAP + 5)) {
            list = QuietHoursQueue.upsertInto(list, entry(tag = "t", id = i))
        }
        assertEquals(QuietHoursQueue.CAP, list.size)
        // 最老（id=1..5）被截掉，最新（id=35）在顶
        assertEquals(QuietHoursQueue.CAP.toLong() + 5, list.first().notificationId.toLong())
    }

    // ---- flushPlan ----

    @Test
    fun `时刻型已过期丢弃`() {
        val now = 1_000_000L
        val entries = listOf(
            entry(tag = "a", id = 1, eventAt = now - QuietHoursQueue.EVENT_EXPIRY_MS - 1),
        )
        val (keep, expired) = QuietHoursQueue.flushPlan(entries, now)
        assertTrue(keep.isEmpty())
        assertEquals(1, expired)
    }

    @Test
    fun `时刻型未过期保留`() {
        val now = 1_000_000L
        val entries = listOf(entry(tag = "a", id = 1, eventAt = now - 60_000L)) // 才过 1 分钟
        val (keep, expired) = QuietHoursQueue.flushPlan(entries, now)
        assertEquals(1, keep.size)
        assertEquals(0, expired)
    }

    @Test
    fun `事实型永不过期`() {
        val now = 1_000_000L
        val entries = listOf(entry(tag = "a", id = 1, eventAt = null))
        val (keep, expired) = QuietHoursQueue.flushPlan(entries, now - 86_400_000L) // 一天前
        assertEquals(1, keep.size)
        assertEquals(0, expired)
    }

    @Test
    fun `混合条目只丢过期的时刻型`() {
        val now = 1_000_000L
        val entries = listOf(
            entry(tag = "stale", id = 1, eventAt = now - QuietHoursQueue.EVENT_EXPIRY_MS - 60_000L),
            entry(tag = "fresh", id = 2, eventAt = now - 30_000L),
            entry(tag = "fact", id = 3, eventAt = null),
        )
        val (keep, expired) = QuietHoursQueue.flushPlan(entries, now)
        assertEquals(listOf("fresh", "fact"), keep.map { it.tag })
        assertEquals(1, expired)
    }

    // ---- 序列化 ----

    @Test
    fun `encode decode 往返保留落点描述`() {
        val list = listOf(entry(tag = "t", id = 1, eventAt = 123L))
        val decoded = QuietHoursQueue.decode(QuietHoursQueue.encode(list))
        assertEquals(list, decoded)
        assertEquals(PendingLaunch.Kind.SUBPAGE, decoded[0].launch?.kind)
        assertEquals("SCORES", decoded[0].launch?.screen)
        assertTrue(decoded[0].launch?.clearTask == true)
    }
}
