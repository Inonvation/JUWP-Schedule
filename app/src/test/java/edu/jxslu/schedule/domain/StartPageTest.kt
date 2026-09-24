package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动页口径（DESIGN §3.3）：
 * - 选项集 = 底栏 Tab 集，顺序与底栏一致（MainActivity 的 tabs）；
 * - 生活页关掉时「生活」不出现，旧选择落回今日页（设置页选中态与启动落点共用）；
 * - 认不出的存储值退回今日页。
 */
class StartPageTest {

    @Test
    fun `显示名`() {
        assertEquals("今日", StartPage.Today.label)
        assertEquals("课表", StartPage.Week.label)
        assertEquals("生活", StartPage.Life.label)
        assertEquals("我的", StartPage.Me.label)
    }

    @Test
    fun `选项顺序与底栏一致`() {
        assertEquals(
            listOf(StartPage.Today, StartPage.Week, StartPage.Life, StartPage.Me),
            StartPage.visiblePages(lifeTabEnabled = true),
        )
    }

    @Test
    fun `生活页关掉时生活不列为选项`() {
        val pages = StartPage.visiblePages(lifeTabEnabled = false)
        assertFalse("生活页关了就不该再出现在启动页选项里", pages.contains(StartPage.Life))
        // 其余三项原样保留、顺序不变
        assertEquals(listOf(StartPage.Today, StartPage.Week, StartPage.Me), pages)
    }

    @Test
    fun `存着生活页但生活页关了落回今日`() {
        assertEquals(
            StartPage.Today,
            StartPage.effectivePage(StartPage.Life, lifeTabEnabled = false),
        )
        // 生活页开回来，用户原来的选择自己回来（不写回存储、不静默改偏好）
        assertEquals(
            StartPage.Life,
            StartPage.effectivePage(StartPage.Life, lifeTabEnabled = true),
        )
    }

    @Test
    fun `生活页开关不影响其余选项`() {
        assertEquals(StartPage.Week, StartPage.effectivePage(StartPage.Week, lifeTabEnabled = false))
        assertEquals(StartPage.Me, StartPage.effectivePage(StartPage.Me, lifeTabEnabled = false))
        assertEquals(StartPage.Today, StartPage.effectivePage(StartPage.Today, lifeTabEnabled = false))
    }

    @Test
    fun `认不出的存储值退回今日`() {
        assertEquals(StartPage.Today, StartPage.fromName(null))
        assertEquals(StartPage.Today, StartPage.fromName(""))
        assertEquals(StartPage.Today, StartPage.fromName("timetable"))
        // 大小写不敏感：手改过的存储值不该把用户扔到别处
        assertEquals(StartPage.Week, StartPage.fromName("week"))
        assertEquals(StartPage.Me, StartPage.fromName("ME"))
    }

    @Test
    fun `每个选项都能经存储名往返`() {
        StartPage.entries.forEach { page ->
            assertEquals(page, StartPage.fromName(page.name))
        }
    }

    @Test
    fun `生效页必定出现在当时的选项表里`() {
        // 设置页用 indexOf 找选中下标，找不到会返回 -1（分段按钮整排没有选中态）
        StartPage.entries.forEach { stored ->
            listOf(true, false).forEach { life ->
                val visible = StartPage.visiblePages(life)
                val effective = StartPage.effectivePage(stored, life)
                assertTrue(
                    "存储 $stored / 生活页 $life 的生效页 $effective 必须出现在选项表 $visible 里",
                    visible.contains(effective),
                )
            }
        }
    }
}
