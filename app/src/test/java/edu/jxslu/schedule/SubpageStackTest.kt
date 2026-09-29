package edu.jxslu.schedule

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 二级页「离开位置」记录（DESIGN §3.1）：窗口链、重建占位与恢复门控。
 *
 * 断言的主题是**从桌面图标回来时该不该把页面放回去**：
 * 窗口还活着就什么都不做（否则凭空多开一遍），只剩「记录在、窗口没了」才补。
 * 所以下面大量出现「先按系统拆掉窗口，再看恢复视图」的写法。
 */
class SubpageStackTest {

    private val ride = SubpageRequest(SubpageScreen.RIDE)
    private val account = SubpageRequest(SubpageScreen.KVCX)
    private val courseList = SubpageRequest(
        SubpageScreen.NOTES_COURSE,
        courseName = "机械制造基础A",
    )
    private val note = SubpageRequest(
        SubpageScreen.NOTE_DETAIL,
        courseName = "机械制造基础A",
        itemId = 12L,
    )

    @Before
    fun setUp() = SubpageStack.clear()

    @After
    fun tearDown() = SubpageStack.clear()

    /** 完整走一遍「开窗 → 可见」。 */
    private fun open(vararg requests: SubpageRequest) {
        requests.forEach { request ->
            SubpageStack.onWindowCreated(request)
            SubpageStack.onWindowResumed()
        }
    }

    /** 系统把窗口拆掉（clearTop / 内存回收）：不走 finish。 */
    private fun tornDown(vararg requests: SubpageRequest) {
        requests.reversed().forEach { SubpageStack.onWindowDestroyed(it) }
    }

    @Test
    fun `解析：认识的名字给出对应页面，参数原样带上`() {
        val parsed = SubpageRequest.of(
            screenName = SubpageScreen.NOTE_DETAIL.name,
            courseName = "机械制造基础A",
            itemId = 12L,
        )
        assertEquals(note, parsed)
    }

    @Test
    fun `解析：没有名字或名字不认识都返回 null`() {
        assertNull(SubpageRequest.of(screenName = null))
        assertNull(SubpageRequest.of(screenName = "SOME_OLD_SCREEN"))
        assertNull(SubpageRequest.of(screenName = ""))
        assertNull(SubpageRequest.of(screenName = "ride"))
    }

    /**
     * 跨版本的旧枚举名仍要认：闹钟 / 通知的 `PendingIntent` 里那份 Intent 是旧版本写下的，
     * 升级后才触发。没有这层映射，用户点一条骑行提醒会落到「课表管理」（脏 extra 的兜底）。
     */
    @Test
    fun `解析：出码页与地图页的旧名字都落到骑行页`() {
        assertEquals(SubpageRequest(SubpageScreen.RIDE), SubpageRequest.of(screenName = "EBIKE"))
        assertEquals(SubpageRequest(SubpageScreen.RIDE), SubpageRequest.of(screenName = "EBIKE_MAP"))
    }

    @Test
    fun `链：窗口都在时不需要恢复`() {
        open(ride, account)
        assertTrue(SubpageStack.pendingRestore().isEmpty())
    }

    @Test
    fun `链：窗口被拆掉后按可见顺序自下而上恢复`() {
        open(ride, account)
        tornDown(ride, account)
        assertEquals(listOf(ride, account), SubpageStack.pendingRestore())
    }

    @Test
    fun `重建：按原参数接回原位，不会多出一层`() {
        open(ride, account)
        tornDown(ride, account)
        // 系统按栈序重建（先下后上）
        open(ride, account)
        assertTrue(SubpageStack.pendingRestore().isEmpty())
        // 再拆一次：仍是两层，说明重建走的是「接回原位」而不是「又开一层」
        tornDown(ride, account)
        assertEquals(listOf(ride, account), SubpageStack.pendingRestore())
    }

    @Test
    fun `同参数两层：各自记账，不会并成一层`() {
        open(courseList, note, courseList)
        tornDown(courseList, note, courseList)
        assertEquals(listOf(courseList, note, courseList), SubpageStack.pendingRestore())
    }

    @Test
    fun `用户逐层返回：退到主界面后不再被恢复`() {
        open(ride, account)
        // 从账号页返回骑行页
        SubpageStack.onWindowFinished(account)
        SubpageStack.onWindowDestroyed(account)
        SubpageStack.onWindowResumed()
        // 再从骑行页退回主界面
        SubpageStack.onWindowFinished(ride)
        SubpageStack.onWindowDestroyed(ride)
        assertTrue(SubpageStack.pendingRestore().isEmpty())
    }

    @Test
    fun `用户从上层返回：记录只剩下面那层`() {
        open(ride, account)
        SubpageStack.onWindowFinished(account)
        SubpageStack.onWindowDestroyed(account)
        SubpageStack.onWindowResumed()
        // 此时若系统把剩下的骑行页也拆了，恢复的只有它，不含已经被用户关掉的账号页
        SubpageStack.onWindowDestroyed(ride)
        assertEquals(listOf(ride), SubpageStack.pendingRestore())
    }

    @Test
    fun `拆掉一部分：记录保留完整层次，等窗口回来`() {
        open(ride, account)
        SubpageStack.onWindowDestroyed(account)
        // 上层被拆、下层还在：不恢复（还有窗口活着）
        assertTrue(SubpageStack.pendingRestore().isEmpty())
        SubpageStack.onWindowDestroyed(ride)
        assertEquals(listOf(ride, account), SubpageStack.pendingRestore())
    }

    @Test
    fun `没有记录时重复记账不抛异常`() {
        SubpageStack.onWindowFinished(ride)
        SubpageStack.onWindowDestroyed(ride)
        SubpageStack.onWindowResumed()
        assertTrue(SubpageStack.pendingRestore().isEmpty())
    }

    @Test
    fun `全新 task 清空记录`() {
        open(ride)
        tornDown(ride)
        SubpageStack.clear()
        assertTrue(SubpageStack.pendingRestore().isEmpty())
    }
}
