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

    private val ebike = SubpageRequest(SubpageScreen.EBIKE)
    private val map = SubpageRequest(SubpageScreen.EBIKE_MAP)
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
        assertNull(SubpageRequest.of(screenName = "ebike"))
    }

    @Test
    fun `链：窗口都在时不需要恢复`() {
        open(ebike, map)
        assertTrue(SubpageStack.pendingRestore().isEmpty())
    }

    @Test
    fun `链：窗口被拆掉后按可见顺序自下而上恢复`() {
        open(ebike, map)
        tornDown(ebike, map)
        assertEquals(listOf(ebike, map), SubpageStack.pendingRestore())
    }

    @Test
    fun `重建：按原参数接回原位，不会多出一层`() {
        open(ebike, map)
        tornDown(ebike, map)
        // 系统按栈序重建（先下后上）
        open(ebike, map)
        assertTrue(SubpageStack.pendingRestore().isEmpty())
        // 再拆一次：仍是两层，说明重建走的是「接回原位」而不是「又开一层」
        tornDown(ebike, map)
        assertEquals(listOf(ebike, map), SubpageStack.pendingRestore())
    }

    @Test
    fun `同参数两层：各自记账，不会并成一层`() {
        open(courseList, note, courseList)
        tornDown(courseList, note, courseList)
        assertEquals(listOf(courseList, note, courseList), SubpageStack.pendingRestore())
    }

    @Test
    fun `用户逐层返回：退到主界面后不再被恢复`() {
        open(ebike, map)
        // 从地图页返回出码页
        SubpageStack.onWindowFinished(map)
        SubpageStack.onWindowDestroyed(map)
        SubpageStack.onWindowResumed()
        // 再从出码页退回主界面
        SubpageStack.onWindowFinished(ebike)
        SubpageStack.onWindowDestroyed(ebike)
        assertTrue(SubpageStack.pendingRestore().isEmpty())
    }

    @Test
    fun `用户从上层返回：记录只剩下面那层`() {
        open(ebike, map)
        SubpageStack.onWindowFinished(map)
        SubpageStack.onWindowDestroyed(map)
        SubpageStack.onWindowResumed()
        // 此时若系统把剩下的出码页也拆了，恢复的只有它，不含已经被用户关掉的地图页
        SubpageStack.onWindowDestroyed(ebike)
        assertEquals(listOf(ebike), SubpageStack.pendingRestore())
    }

    @Test
    fun `拆掉一部分：记录保留完整层次，等窗口回来`() {
        open(ebike, map)
        SubpageStack.onWindowDestroyed(map)
        // 上层被拆、下层还在：不恢复（还有窗口活着）
        assertTrue(SubpageStack.pendingRestore().isEmpty())
        SubpageStack.onWindowDestroyed(ebike)
        assertEquals(listOf(ebike, map), SubpageStack.pendingRestore())
    }

    @Test
    fun `没有记录时重复记账不抛异常`() {
        SubpageStack.onWindowFinished(ebike)
        SubpageStack.onWindowDestroyed(ebike)
        SubpageStack.onWindowResumed()
        assertTrue(SubpageStack.pendingRestore().isEmpty())
    }

    @Test
    fun `全新 task 清空记录`() {
        open(ebike)
        tornDown(ebike)
        SubpageStack.clear()
        assertTrue(SubpageStack.pendingRestore().isEmpty())
    }
}
