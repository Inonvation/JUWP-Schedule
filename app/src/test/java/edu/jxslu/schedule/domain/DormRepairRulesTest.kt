package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 报修卡片/详情页的纯规则（DESIGN §4.49）：3 天保留、在途优先、完成过渡只通知一次。 */
class DormRepairRulesTest {

    private val now = 1_000_000_000_000L

    private fun record(
        id: Long,
        approval: Int = DormRepairRules.APPROVAL_PENDING,
        repair: String = "待处理",
        submittedAt: Long = now - 10_000,
        finishedAt: Long? = null,
        finishedSeenAt: Long? = null,
    ) = DormRepairRecord(
        formUserId = id,
        approvalStatus = "待处理",
        approvalStatusId = approval,
        repairStatus = repair,
        dorm = "某栋101",
        fault = "水电维修-灯",
        submittedAt = submittedAt,
        updatedAt = submittedAt,
        finishedAt = finishedAt,
        finishedSeenAt = finishedSeenAt,
    )

    private fun snapshot(vararg records: DormRepairRecord, dismissed: List<Long> = emptyList(), notified: List<Long> = emptyList()) =
        DormRepairSnapshot(
            fetchedAt = now - 1_000,
            records = records.toList(),
            dismissedIds = dismissed,
            notifiedIds = notified,
            checkedOnce = true,
            tracking = records.any { !DormRepairRules.isFinished(it) },
        )

    @Test
    fun `finished means repair done or rejected or approved without finer status`() {
        assertTrue(DormRepairRules.isFinished(record(1, repair = "已完成")))
        assertTrue(DormRepairRules.isFinished(record(1, approval = DormRepairRules.APPROVAL_REJECTED)))
        assertTrue(DormRepairRules.isFinished(record(1, approval = DormRepairRules.APPROVAL_REVOKED)))
        assertTrue(
            DormRepairRules.isFinished(
                record(1, approval = DormRepairRules.APPROVAL_APPROVED, repair = ""),
            ),
        )
        // 审批走到终点（含「学生核实完成」节点）就是完成；表单字段没来得及改也算
        assertTrue(
            DormRepairRules.isFinished(
                record(1, approval = DormRepairRules.APPROVAL_APPROVED, repair = "已派单"),
            ),
        )
        assertFalse(DormRepairRules.isFinished(record(1)))
    }

    @Test
    fun `finished card stays for three days and dismissed hides it`() {
        val justDone = record(1, repair = "已完成", finishedAt = now - 1_000)
        val exactlyThreeDays = record(1, repair = "已完成", finishedAt = now - DormRepairRules.RETAIN_FINISHED_MS)
        val tooOld = record(1, repair = "已完成", finishedAt = now - DormRepairRules.RETAIN_FINISHED_MS - 1)
        assertTrue(DormRepairRules.shouldShow(justDone, snapshot(justDone), now))
        assertTrue(DormRepairRules.shouldShow(exactlyThreeDays, snapshot(exactlyThreeDays), now))
        assertFalse(DormRepairRules.shouldShow(tooOld, snapshot(tooOld), now))
        assertFalse(
            DormRepairRules.shouldShow(
                justDone,
                snapshot(justDone, dismissed = listOf(1L)),
                now,
            ),
        )
    }

    @Test
    fun `card prefers active record over older finished one`() {
        val finished = record(1, repair = "已完成", submittedAt = now - 200_000, finishedAt = now - 1_000)
        val active = record(2, submittedAt = now - 100_000)
        assertEquals(2L, DormRepairRules.cardRecord(snapshot(finished, active), now)?.formUserId)
        assertEquals(1L, DormRepairRules.cardRecord(snapshot(finished), now)?.formUserId)
        assertNull(
            DormRepairRules.cardRecord(
                snapshot(finished, dismissed = listOf(1L)),
                now,
            ),
        )
    }

    @Test
    fun `only a real pending-to-finished transition counts as newly finished`() {
        val pending = record(1)
        val done = record(1, repair = "已完成", finishedAt = now)
        val before = snapshot(pending)
        assertEquals(listOf(done), DormRepairRules.newlyFinished(before, listOf(done)))
        assertTrue(DormRepairRules.newlyFinished(DormRepairSnapshot(), listOf(done)).isEmpty())
        assertTrue(
            DormRepairRules.newlyFinished(
                snapshot(done),
                listOf(done),
            ).isEmpty(),
        )
        assertTrue(
            DormRepairRules.newlyFinished(
                snapshot(pending, notified = listOf(1L)),
                listOf(done),
            ).isEmpty(),
        )
    }

    @Test
    fun `merge keeps the first seen finished time`() {
        val done = record(1, repair = "已完成")
        val previousDone = record(1, repair = "已完成").copy(finishedSeenAt = 500)
        assertEquals(
            500L,
            DormRepairRules.mergeRecord(done, previousDone, now).finishedSeenAt,
        )
        assertEquals(now, DormRepairRules.mergeRecord(done, record(1), now).finishedSeenAt)
        assertNull(DormRepairRules.mergeRecord(record(1), previousDone, now).finishedSeenAt)
    }

    @Test
    fun `status label and tone mapping`() {
        val rejected = record(1, approval = DormRepairRules.APPROVAL_REJECTED)
        assertEquals("已拒绝", DormRepairRules.statusLabel(rejected))
        assertEquals(DormRepairTone.Error, DormRepairRules.toneOf(rejected))

        val revoked = record(1, approval = DormRepairRules.APPROVAL_REVOKED)
        assertEquals("已撤销", DormRepairRules.statusLabel(revoked))
        assertEquals(DormRepairTone.Neutral, DormRepairRules.toneOf(revoked))

        val dispatched = record(1, approval = DormRepairRules.APPROVAL_APPROVED, repair = "已派单")
        // 审批已到终点：卡片主状态按「已完成」，表单的「已派单」留给详情页并列说明
        assertEquals("已完成", DormRepairRules.statusLabel(dispatched))
        assertEquals("已通过", DormRepairRules.approvalStatusLabel(dispatched))
        assertEquals(DormRepairTone.Success, DormRepairRules.toneOf(dispatched))

        val processing = record(1, approval = DormRepairRules.APPROVAL_PROCESSING, repair = "已派单")
        assertEquals("已派单", DormRepairRules.statusLabel(processing))
        assertEquals(DormRepairTone.Primary, DormRepairRules.toneOf(processing))

        val done = record(1, repair = "已完成")
        assertEquals(DormRepairTone.Success, DormRepairRules.toneOf(done))
    }
}
