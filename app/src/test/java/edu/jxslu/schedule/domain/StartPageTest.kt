package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动页口径（DESIGN §3.3，2026-10-20 起 3 Tab）：
 * - 选项集 = 底栏 Tab 集（今日 / 课表 / 教务），顺序与底栏一致；
 * - 旧版本存下的「生活 / 我的」不再有 Tab，统一落回今日（不写回存储）；
 * - 认不出的存储值退回今日页。
 */
class StartPageTest {

    @Test
    fun `显示名`() {
        assertEquals("今日", StartPage.Today.label)
        assertEquals("课表", StartPage.Week.label)
        assertEquals("教务", StartPage.Jw.label)
        assertEquals("生活", StartPage.Life.label)
        assertEquals("我的", StartPage.Me.label)
    }

    @Test
    fun `选项顺序与底栏一致`() {
        assertEquals(
            listOf(StartPage.Today, StartPage.Week, StartPage.Jw),
            StartPage.visiblePages(),
        )
    }

    @Test
    fun `旧的生活与我的选择落回今日`() {
        assertEquals(StartPage.Today, StartPage.effectivePage(StartPage.Life))
        assertEquals(StartPage.Today, StartPage.effectivePage(StartPage.Me))
    }

    @Test
    fun `其余选项原样生效`() {
        assertEquals(StartPage.Week, StartPage.effectivePage(StartPage.Week))
        assertEquals(StartPage.Jw, StartPage.effectivePage(StartPage.Jw))
        assertEquals(StartPage.Today, StartPage.effectivePage(StartPage.Today))
    }

    @Test
    fun `认不出的存储值退回今日`() {
        assertEquals(StartPage.Today, StartPage.fromName(null))
        assertEquals(StartPage.Today, StartPage.fromName(""))
        assertEquals(StartPage.Today, StartPage.fromName("timetable"))
        // 大小写不敏感：手改过的存储值不该把用户扔到别处
        assertEquals(StartPage.Week, StartPage.fromName("week"))
        assertEquals(StartPage.Me, StartPage.fromName("ME"))
        assertEquals(StartPage.Jw, StartPage.fromName("jw"))
    }

    @Test
    fun `每个选项都能经存储名往返`() {
        StartPage.entries.forEach { page ->
            assertEquals(page, StartPage.fromName(page.name))
        }
    }

    @Test
    fun `生效页必定出现在选项表里`() {
        // 设置页用 indexOf 找选中下标，找不到会返回 -1（分段按钮整排没有选中态）
        val visible = StartPage.visiblePages()
        StartPage.entries.forEach { stored ->
            val effective = StartPage.effectivePage(stored)
            assertTrue(
                "存储 $stored 的生效页 $effective 必须出现在选项表 $visible 里",
                visible.contains(effective),
            )
        }
    }
}
