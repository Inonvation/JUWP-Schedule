package edu.jxslu.schedule.data.ujing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U净 历史账单的纯列表逻辑（`UjingHistoryStore.upsertInto`）与快照映射
 * （`UjingHistoryEntry.fromSnapshot`）。落盘（org.json）依赖 Android 运行时，不在 JVM 测。
 */
class UjingHistoryStoreTest {

    private fun entry(
        orderId: String,
        status: String = "40",
        updatedAt: Long = 1_000L,
    ) = UjingHistoryEntry(
        orderId = orderId,
        deviceName = "波轮洗衣机",
        modelName = "标准洗",
        priceFen = 278,
        status = status,
        createdAt = 900L,
        updatedAt = updatedAt,
    )

    // ---- upsertInto ----

    @Test
    fun upsertPutsNewEntryOnTop() {
        val list = UjingHistoryStore.upsertInto(listOf(entry("b")), entry("a"))
        assertEquals(listOf("a", "b"), list.map { it.orderId })
    }

    @Test
    fun upsertReplacesSameOrderInsteadOfStacking() {
        val first = entry("a", status = "40", updatedAt = 1_000L)
        val refreshed = entry("a", status = "50", updatedAt = 2_000L)
        val list = UjingHistoryStore.upsertInto(listOf(first, entry("b")), refreshed)
        // 同 orderId 只留一条（刷新更新原条目），且顶到最前
        assertEquals(listOf("a", "b"), list.map { it.orderId })
        assertEquals("50", list.first().status)
        assertEquals(2_000L, list.first().updatedAt)
        assertEquals(2, list.size)
    }

    @Test
    fun upsertCapsAtFifty() {
        var list = List(UjingHistoryStore.CAP) { i -> entry("old-$i") }
        list = UjingHistoryStore.upsertInto(list, entry("new"))
        assertEquals(UjingHistoryStore.CAP, list.size)
        assertEquals("new", list.first().orderId)
        // 最老的被截掉
        assertTrue(list.none { it.orderId == "old-${UjingHistoryStore.CAP - 1}" })
    }

    // ---- fromSnapshot ----

    @Test
    fun fromSnapshotCarriesSnapshotFields() {
        val snapshot = UjingOrderSnapshot(
            orderId = "o1",
            orderNo = "N1",
            deviceName = "滚筒洗衣机 · 3F",
            modelName = "大物洗",
            priceFen = 500,
            status = "40",
            paid = true,
            createdAt = 1_234L,
        )
        val e = UjingHistoryEntry.fromSnapshot(snapshot, nowMillis = 9_999L)
        assertEquals("o1", e.orderId)
        assertEquals("N1", e.orderNo)
        assertEquals("滚筒洗衣机 · 3F", e.deviceName)
        assertEquals("大物洗", e.modelName)
        assertEquals(500, e.priceFen)
        assertEquals("40", e.status)
        assertTrue(e.paid)
        assertEquals(1_234L, e.createdAt)
        assertEquals(9_999L, e.updatedAt)
        // 展示时间基准：下单时刻优先
        assertEquals(1_234L, e.displayAt)
    }

    @Test
    fun fromSnapshotOverrideMarksCancelledAndFallsBackToUpdatedAt() {
        val snapshot = UjingOrderSnapshot(
            orderId = "o2",
            modelName = "标准洗",
            status = "10",
            // 旧版本快照没有 createdAt
            createdAt = 0L,
        )
        val e = UjingHistoryEntry.fromSnapshot(snapshot, statusOverride = "60", nowMillis = 5_000L)
        assertEquals("60", e.status)
        assertEquals(0L, e.createdAt)
        // 下单时刻缺失 → 展示退回最近更新时刻
        assertEquals(5_000L, e.displayAt)
    }

    // ---- displayAt 口径 ----

    @Test
    fun displayAtPrefersCreatedAtFallsBackToUpdatedAt() {
        assertEquals(7L, entry("a", updatedAt = 9L).copy(createdAt = 7L).displayAt)
        assertEquals(9L, entry("a", updatedAt = 9L).copy(createdAt = 0L).displayAt)
    }
}
