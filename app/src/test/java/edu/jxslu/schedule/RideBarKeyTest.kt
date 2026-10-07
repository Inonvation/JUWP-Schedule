package edu.jxslu.schedule

import edu.jxslu.schedule.domain.EbikeCapabilities
import edu.jxslu.schedule.ui.ebike.RidePhase
import edu.jxslu.schedule.ui.ebike.barBlockShrinks
import edu.jxslu.schedule.ui.ebike.rideBarKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作区形态 key（DESIGN §3.9）：它一变，`RideActionArea` 就走一次高度动画，页面同时把地图的
 * 让位高度冻结 240ms（否则动画每帧都 resize 一次地图、osmdroid 每帧整幅重绘——2026-10-01
 * 用户报的「开锁后掉帧、然后骑行面板弹出来」）。
 *
 * 所以这份 key 的语义是**契约**：该分开的形态必须分开（不然动画不播、还可能漏掉冻结），
 * 同一形态必须稳定（不然每次重组都重新触发一次冻结）。
 */
class RideBarKeyTest {

    private val mini = EbikeCapabilities(
        wechatScan = true,
        inAppRide = false,
        directUnlock = false,
        cameraScan = false,
    )
    private val account = EbikeCapabilities(
        wechatScan = false,
        inAppRide = true,
        directUnlock = true,
        cameraScan = true,
    )

    @Test
    fun `找车态的四种排版互不相同`() {
        val code = rideBarKey(RidePhase.Finding, mini, loggedIn = true, pickedCar = null, hasCode = true)
        val plain = rideBarKey(RidePhase.Finding, mini, loggedIn = true, pickedCar = null, hasCode = false)
        val login = rideBarKey(RidePhase.Finding, account, loggedIn = false, pickedCar = null, hasCode = false)
        val scan = rideBarKey(RidePhase.Finding, account, loggedIn = true, pickedCar = null, hasCode = false)

        assertEquals(4, setOf(code, plain, login, scan).size)
        // 同一形态稳定：同样的输入必须给同一个 key（否则每次重组都重跑一次升起动画）
        assertEquals(scan, rideBarKey(RidePhase.Finding, account, loggedIn = true, pickedCar = null, hasCode = false))
    }

    @Test
    fun `车辆卡按车号区分`() {
        val first = rideBarKey(RidePhase.Finding, account, loggedIn = true, pickedCar = "100000669", hasCode = false)
        val second = rideBarKey(RidePhase.Finding, account, loggedIn = true, pickedCar = "100000670", hasCode = false)

        // 在车辆卡上换一辆车：上区要重走一遍升起动画（2026-09-30 用户口径）
        assertNotEquals(first, second)
    }

    @Test
    fun `骑行与结算各自一个形态`() {
        val riding = rideBarKey(RidePhase.Riding, account, loggedIn = true, pickedCar = null, hasCode = false)
        val settled = rideBarKey(RidePhase.Settled, account, loggedIn = true, pickedCar = null, hasCode = false)

        // 电池/车辆卡不该影响骑行态与结算态的 key：phase 定了就是它
        assertNotEquals(riding, settled)
        assertEquals(riding, rideBarKey(RidePhase.Riding, account, loggedIn = true, pickedCar = "100000669", hasCode = true))
    }

    @Test
    fun `下沉方向判定`() {
        // 2026-10-07：落位时机按方向分流——下沉的块沉到底就落位（修「继续找车后地图抽搐」）
        val plain = rideBarKey(RidePhase.Finding, mini, loggedIn = true, pickedCar = null, hasCode = false)
        val settled = rideBarKey(RidePhase.Settled, account, loggedIn = true, pickedCar = null, hasCode = false)
        val riding = rideBarKey(RidePhase.Riding, account, loggedIn = true, pickedCar = null, hasCode = false)
        val car = rideBarKey(RidePhase.Finding, account, loggedIn = true, pickedCar = "100000669", hasCode = false)

        // 结算卡 → 继续找车（用户报的场景）、骑行 → 结算卡、结束计时、车辆卡 → 找车：都变矮
        assertTrue(barBlockShrinks(settled, plain))
        assertTrue(barBlockShrinks(riding, settled))
        assertTrue(barBlockShrinks(riding, plain))
        assertTrue(barBlockShrinks(car, plain))
        // 升起与同档互切不按下沉处理（走等满程的稳妥路径）
        assertFalse(barBlockShrinks(plain, settled))
        assertFalse(barBlockShrinks(plain, riding))
        assertFalse(barBlockShrinks(settled, riding))
        // 结算卡与车辆卡谁高不一定，互切不算下沉
        assertFalse(barBlockShrinks(settled, car))
        assertFalse(barBlockShrinks(car, settled))
    }
}
