package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** U净 纯逻辑：金额 / 扫码徽标 / 看板聚合（DESIGN §4.37）。 */
class UjingStateTest {

    // ---- 金额 ----

    @Test
    fun fen2yuanKeepsTwoDecimals() {
        assertEquals("2.78", UjingState.fen2yuan(278))
        assertEquals("3.00", UjingState.fen2yuan(300))
        assertEquals("0.50", UjingState.fen2yuan(50))
        assertEquals("0.00", UjingState.fen2yuan(0))
        assertEquals("-0.05", UjingState.fen2yuan(-5))
    }

    // ---- 扫码徽标 ----

    @Test
    fun badgeFreeWhenCreatable() {
        // 能下单就是空闲——即使服务端 status 字段还挂着旧值
        assertEquals(UjingState.ScanBadge.Free, UjingState.scanBadge(true, "1", "使用中"))
    }

    @Test
    fun badgeOfflineByStatusOrReason() {
        assertEquals(UjingState.ScanBadge.Offline, UjingState.scanBadge(false, "8", null))
        assertEquals(UjingState.ScanBadge.Offline, UjingState.scanBadge(false, "1", "设备离线"))
    }

    @Test
    fun badgeFaultByStatusOrReason() {
        assertEquals(UjingState.ScanBadge.Fault, UjingState.scanBadge(false, "2", null))
        assertEquals(UjingState.ScanBadge.Fault, UjingState.scanBadge(false, null, "设备故障"))
    }

    @Test
    fun badgeInUseByDefault() {
        assertEquals(UjingState.ScanBadge.InUse, UjingState.scanBadge(false, "1", "被占用"))
        assertEquals(UjingState.ScanBadge.InUse, UjingState.scanBadge(false, null, null))
    }

    // ---- 通信模块 ----

    @Test
    fun moduleTypeLabels() {
        assertEquals("蓝牙 Nordic", UjingState.moduleTypeLabel(1))
        assertEquals("蓝牙 Cypress", UjingState.moduleTypeLabel(5))
        assertEquals("4G", UjingState.moduleTypeLabel(7))
        // 未返回（-1）与未知值都不猜
        assertEquals("未知", UjingState.moduleTypeLabel(-1))
        assertEquals("未知", UjingState.moduleTypeLabel(99))
    }

    // ---- 下单参数（2026-10-01 体验补） ----

    @Test
    fun temperatureLabelsMatchProtocolEnum() {
        // 协议固定枚举：1=常温 2=30℃ 3=40℃ 4=60℃；未知 id 不猜
        assertEquals("常温", UjingState.temperatureLabel(1))
        assertEquals("30℃", UjingState.temperatureLabel(2))
        assertEquals("40℃", UjingState.temperatureLabel(3))
        assertEquals("60℃", UjingState.temperatureLabel(4))
        assertEquals("水温 9", UjingState.temperatureLabel(9))
        // 选项全集与 label 同源，顺序即档位序
        assertEquals(listOf(1, 2, 3, 4), UjingState.temperatureOptions.map { it.first })
        assertEquals(4, UjingState.temperatureOptions.map { it.second }.distinct().size)
    }

    @Test
    fun payWindowRemainCountsDownTwoMinutes() {
        val created = 1_000_000L
        // 未知下单时刻（旧快照 0）→ -1（UI 不显示窗口）
        assertEquals(-1, UjingState.payWindowRemainSeconds(0L, created))
        // 下单即 120 秒整
        assertEquals(120, UjingState.payWindowRemainSeconds(created, created))
        // 过了 30 秒剩 90
        assertEquals(90, UjingState.payWindowRemainSeconds(created, created + 30_000L))
        // 窗口已过夹 0（服务端还没关单，提示刷新而不是假装有时间）
        assertEquals(0, UjingState.payWindowRemainSeconds(created, created + 300_000L))
    }

    @Test
    fun dryTimeIsModelTimeOverTen() {
        // 协议事实：dryTime = 模式时长 / 10（仅烘干机发送）
        assertEquals(3, UjingState.dryTimeFor(35))
        assertEquals(3, UjingState.dryTimeFor(30))
        assertEquals(0, UjingState.dryTimeFor(0))
        assertEquals(9, UjingState.dryTimeFor(90))
    }

    // ---- 烘干机判定 ----

    @Test
    fun isDryerByTypeOrFallback() {
        // type==2 为准（协议事实）
        assertTrue(UjingState.isDryer(type = 2, deviceTypeId = 2, deviceTypeName = "滚筒洗衣机"))
        // type 没给对时靠 deviceTypeId==3 兜底
        assertTrue(UjingState.isDryer(type = 1, deviceTypeId = 3, deviceTypeName = ""))
        // 再靠机型名兜底（与看板同一「烘干 / 干衣」判据）
        assertTrue(UjingState.isDryer(type = 1, deviceTypeId = 1, deviceTypeName = "烘干机"))
        assertTrue(UjingState.isDryer(type = 1, deviceTypeId = 1, deviceTypeName = "干衣机"))
        // 洗衣机不误判
        assertFalse(UjingState.isDryer(type = 1, deviceTypeId = 1, deviceTypeName = "滚筒洗衣机"))
    }

    // ---- 投放加购 ----

    @Test
    fun addonGearsMatchCommunityEnum() {
        // 社区档位枚举：1 = 洗衣液标准、4 = 消毒液标准；这两个值进下单 body，改前重抓样本
        assertEquals(1, UjingState.DETERGENT_STANDARD_GEAR)
        assertEquals(4, UjingState.DISINFECTANT_STANDARD_GEAR)
    }

    @Test
    fun addonDefaultsFollowForcedDeclarationForWashers() {
        // 洗衣机业务：机型声明强制投放的默认勾上，用户可在结果卡里改
        assertEquals(true to true, UjingState.addonDefaults(false, true, true))
        assertEquals(true to false, UjingState.addonDefaults(false, true, false))
        assertEquals(false to true, UjingState.addonDefaults(false, false, true))
        assertEquals(false to false, UjingState.addonDefaults(false, false, false))
    }

    @Test
    fun addonDefaultsAlwaysOffForDryers() {
        // 烘干机没有投放概念（下单 body 也不发档位字段）——即使声明了强制也恒 false
        assertEquals(false to false, UjingState.addonDefaults(true, true, true))
        assertEquals(false to false, UjingState.addonDefaults(true, false, false))
    }

    // ---- 看板聚合 ----

    @Test
    fun boardSumsWashersAndSkipsDryers() {
        val line = UjingState.boardLine(
            listOf(
                UjingState.BoardGroup("滚筒洗衣机", 2, 4, 0),
                UjingState.BoardGroup("烘干机", 3, 3, 0),
            ),
        )!!
        assertEquals("空闲 2/4", line.primaryText)
        assertNull(line.secondaryText)
    }

    @Test
    fun boardShowsMinWaitWhenAllBusy() {
        val line = UjingState.boardLine(
            listOf(
                UjingState.BoardGroup("滚筒洗衣机", 0, 4, 40),
                UjingState.BoardGroup("波轮洗衣机", 0, 2, 15),
            ),
        )!!
        assertEquals("暂无空闲", line.primaryText)
        assertEquals("约等 15 分钟", line.secondaryText)
    }

    @Test
    fun boardNullWithoutWashers() {
        assertNull(UjingState.boardLine(listOf(UjingState.BoardGroup("烘干机", 1, 1, 0))))
        assertNull(UjingState.boardLine(emptyList()))
    }

    @Test
    fun boardBusyWithoutWaitInfoStillReadable() {
        val line = UjingState.boardLine(listOf(UjingState.BoardGroup("洗衣机", 0, 3, 0)))!!
        assertEquals("暂无空闲", line.primaryText)
        assertNull(line.secondaryText)
    }

    @Test
    fun dryerLineSumsDryersOnly() {
        val line = UjingState.dryerLine(
            listOf(
                UjingState.BoardGroup("滚筒洗衣机", 2, 4, 0),
                UjingState.BoardGroup("烘干机", 1, 2, 0),
            ),
        )!!
        assertEquals("空闲 1/2", line.primaryText)
        assertNull(line.secondaryText)
    }

    @Test
    fun dryerLineWaitWhenAllBusy() {
        val line = UjingState.dryerLine(
            listOf(
                UjingState.BoardGroup("烘干机", 0, 3, 25),
                UjingState.BoardGroup("干衣机", 0, 1, 10),
            ),
        )!!
        assertEquals("暂无空闲", line.primaryText)
        assertEquals("约等 10 分钟", line.secondaryText)
    }

    @Test
    fun dryerLineNullWithoutDryers() {
        assertNull(UjingState.dryerLine(listOf(UjingState.BoardGroup("滚筒洗衣机", 2, 4, 0))))
        assertNull(UjingState.dryerLine(emptyList()))
    }
}
