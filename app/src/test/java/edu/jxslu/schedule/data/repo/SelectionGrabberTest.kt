package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.domain.GrabStop
import edu.jxslu.schedule.domain.SelectionCourse
import edu.jxslu.schedule.domain.SelectionGrabPolicy
import edu.jxslu.schedule.domain.SelectionWish
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 抢课引擎编排（DESIGN §4.36）：匹配 → 提交 → 收场；用虚拟时钟与假 client，不碰真网络。 */
class SelectionGrabberTest {

    private class VirtualClock(var now: Long = 1_000_000L) {
        val sleeps = mutableListOf<Long>()
        suspend fun sleep(ms: Long) {
            sleeps += ms
            now += ms
        }
    }

    private class FakeClient(
        private val courses: List<SelectionCourse>,
        private val submitResults: MutableList<SelectionSubmitResult> = mutableListOf(),
        private val fetchError: (() -> Exception?)? = null,
    ) : SelectionCenterClient {
        var fetchCalls = 0
        val submitted = mutableListOf<String>()
        override val isConfigured = true

        override suspend fun fetchCourses(roundId: String): List<SelectionCourse> {
            fetchCalls++
            fetchError?.invoke()?.let { throw it }
            return courses
        }

        override suspend fun submit(roundId: String, courseId: String): SelectionSubmitResult {
            submitted += courseId
            return if (submitResults.isEmpty()) {
                SelectionSubmitResult.Success()
            } else {
                submitResults.removeAt(0)
            }
        }

        // 抢课引擎不调退课（红线：只加课）；FakeClient 给它一个明确的「不提供」答复
        override suspend fun drop(roundId: String, courseId: String): SelectionSubmitResult =
            SelectionSubmitResult.Fatal("测试 FakeClient 不提供退课")
    }

    private fun wish(name: String, id: String = "w1") = SelectionWish(id = id, nameKeyword = name)

    private fun course(id: String, name: String, teacher: String = "张三", selected: Boolean = false) =
        SelectionCourse(id = id, name = name, teacher = teacher, selected = selected)

    private fun run(
        client: SelectionCenterClient,
        clock: VirtualClock,
        wishes: List<SelectionWish>,
        roundEndAt: Long? = null,
    ): List<SelectionGrabber.Event> = runBlocking {
        SelectionGrabber(
            client = client,
            clock = clock::now,
            sleep = clock::sleep,
        ).run(
            GrabRequest(roundId = "R1", roundName = "第一轮", roundEndAt = roundEndAt, wishes = wishes),
        ).toList()
    }

    @Test
    fun grabsMatchingCourseAndFinishes() {
        val client = FakeClient(listOf(course("c1", "机械设计基础A", teacher = "曾刚")))
        val events = run(client, VirtualClock(), listOf(wish("机械设计", id = "w1")))

        assertEquals(listOf("c1"), client.submitted)
        assertTrue(events.any { it is SelectionGrabber.Event.Grabbed })
        val stop = events.last() as SelectionGrabber.Event.Stopped
        assertEquals(GrabStop.AllMatched, stop.stop)
        assertEquals(listOf("c1"), stop.grabbed.map { it.id })
    }

    @Test
    fun teacherKeywordMustMatchToo() {
        val client = FakeClient(listOf(course("c1", "机械设计基础A", teacher = "张三")))
        val clock = VirtualClock()
        val events = run(
            client,
            clock,
            listOf(SelectionWish(id = "w1", nameKeyword = "机械", teacherKeyword = "李四")),
        )
        // 教师不匹配 → 不提交；会话靠时长上限收场（而不是空转）
        assertTrue(client.submitted.isEmpty())
        val stop = events.last() as SelectionGrabber.Event.Stopped
        assertEquals(GrabStop.TimeLimit, stop.stop)
    }

    @Test
    fun skipsAlreadySelectedCourse() {
        val client = FakeClient(listOf(course("c1", "机械设计基础A", selected = true)))
        val events = run(client, VirtualClock(), listOf(wish("机械")))

        assertTrue(client.submitted.isEmpty())
        assertEquals(GrabStop.TimeLimit, (events.last() as SelectionGrabber.Event.Stopped).stop)
    }

    @Test
    fun rejectedKeepsPendingAndRetriesNextRound() {
        val client = FakeClient(
            courses = listOf(course("c1", "机械设计基础A")),
            submitResults = mutableListOf(SelectionSubmitResult.Rejected("名额已满")),
        )
        val events = run(client, VirtualClock(), listOf(wish("机械")))

        assertEquals(listOf("c1", "c1"), client.submitted)
        assertTrue(events.any { it is SelectionGrabber.Event.Failed })
        assertEquals(GrabStop.AllMatched, (events.last() as SelectionGrabber.Event.Stopped).stop)
    }

    @Test
    fun fatalFetchStopsSessionImmediately() {
        val client = FakeClient(
            courses = emptyList(),
            fetchError = { SelectionClientException(fatal = true, message = "会话已失效") },
        )
        val events = run(client, VirtualClock(), listOf(wish("机械")))

        assertEquals(1, client.fetchCalls)
        val stop = (events.last() as SelectionGrabber.Event.Stopped).stop
        assertEquals(GrabStop.Fatal("会话已失效"), stop)
    }

    @Test
    fun transientFetchFailuresBackOffThenStop() {
        val clock = VirtualClock()
        val client = FakeClient(
            courses = emptyList(),
            fetchError = { SelectionClientException(fatal = false, message = "连不上") },
        )
        val events = run(client, clock, listOf(wish("机械")))

        val stop = (events.last() as SelectionGrabber.Event.Stopped).stop
        assertEquals(GrabStop.TooManyFailures, stop)
        assertEquals(SelectionGrabPolicy.MAX_CONSECUTIVE_FAILURES, client.fetchCalls)
        // 退避：10s / 20s / 30s / 40s（第 5 次失败到阈值，不再空等最后一轮）
        assertEquals(listOf(10_000L, 20_000L, 30_000L, 40_000L), clock.sleeps)
    }

    @Test
    fun roundEndStopsBeforeTimeLimit() {
        val clock = VirtualClock()
        val client = FakeClient(
            courses = emptyList(),
            fetchError = { SelectionClientException(fatal = false, message = "连不上") },
        )
        // 截止时刻设在第 2 次失败之后：轮次截止先于「连续失败到阈值」收场
        val events = run(client, clock, listOf(wish("机械")), roundEndAt = clock.now + 25_000)
        val stop = (events.last() as SelectionGrabber.Event.Stopped).stop
        assertEquals(GrabStop.RoundEnded, stop)
    }

    @Test
    fun emptyWishesStopsAsAllMatched() {
        val client = FakeClient(emptyList())
        val events = run(client, VirtualClock(), emptyList())
        assertNull(client.submitted.firstOrNull())
        assertEquals(GrabStop.AllMatched, (events.last() as SelectionGrabber.Event.Stopped).stop)
        assertEquals(0, client.fetchCalls)
    }
}
