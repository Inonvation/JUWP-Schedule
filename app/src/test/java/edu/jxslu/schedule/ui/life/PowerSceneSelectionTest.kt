package edu.jxslu.schedule.ui.life

import edu.jxslu.schedule.data.power.PowerSceneOption
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 级联选择的纯逻辑（2026-10-08）：钉住「改选上游层级后，更深层的旧选择被清掉」。
 *
 * 背景（复查轮修的真实缺陷）：改选楼栋后旧房间残留，会让「房间」层直接命中旧预览
 * 而不是候选列表；`applySceneChoice` 是选择器状态机里唯一做这件事的地方。
 */
class PowerSceneSelectionTest {

    private fun option(name: String) = PowerSceneOption(name, value = name)

    private fun names(chosen: List<PowerSceneOption?>) = chosen.map { it?.name }

    @Test
    fun `改选楼栋清掉上一层选过的房间`() {
        val chosen = listOf(option("校区"), option("1A"), option("1A101"))
        val next = applySceneChoice(chosen, step = 1, option = option("2B"))
        assertEquals(listOf("校区", "2B", null), names(next))
    }

    @Test
    fun `改选首层清掉下面所有层`() {
        val chosen = listOf(option("校区"), option("1A"), option("1A101"))
        val next = applySceneChoice(chosen, step = 0, option = option("校区二"))
        assertEquals(listOf("校区二", null, null), names(next))
    }

    @Test
    fun `最深层选择不动其他层`() {
        val chosen = listOf(option("校区"), option("1A"), option("1A101"))
        val next = applySceneChoice(chosen, step = 2, option = option("1A102"))
        assertEquals(listOf("校区", "1A", "1A102"), names(next))
    }
}
