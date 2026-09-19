package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷方式的纯逻辑口径（DESIGN §4.16）：拉起口径折算、表单校验、预设表、
 * JSON 编解码兜底、列表操作。Intent 构造在 ui/common/ShortcutLauncher（android 类，JVM 测不了）。
 */
class ShortcutsTest {

    // ---- resolveLaunchPlan：三种口径的优先级 = Activity > 链接 > 只填包名 ----

    @Test
    fun plan_activityWins() {
        val plan = Shortcuts.resolveLaunchPlan(
            ShortcutItem(id = "x", pkg = "com.a.b", activity = "com.a.b.Main", uri = "https://a.b"),
        )
        assertEquals(ShortcutLaunchPlan.ExplicitComponent("com.a.b", "com.a.b.Main"), plan)
    }

    @Test
    fun plan_uriCarriesPkgOnlyIfPresent() {
        assertEquals(
            ShortcutLaunchPlan.UriView("https://a.b", "com.a.b"),
            Shortcuts.resolveLaunchPlan(ShortcutItem(id = "x", uri = "https://a.b", pkg = "com.a.b")),
        )
        assertEquals(
            ShortcutLaunchPlan.UriView("pinduoduo://x/y", null),
            Shortcuts.resolveLaunchPlan(ShortcutItem(id = "x", uri = "pinduoduo://x/y")),
        )
    }

    @Test
    fun plan_pkgOnlyLaunchesPackage() {
        assertEquals(
            ShortcutLaunchPlan.LaunchPackage("com.cainiao.wireless"),
            Shortcuts.resolveLaunchPlan(ShortcutItem(id = "x", pkg = "com.cainiao.wireless")),
        )
    }

    @Test
    fun plan_noTargetIsNull() {
        assertNull(Shortcuts.resolveLaunchPlan(ShortcutItem(id = "x", name = "空")))
    }

    // ---- validate：挡在 startActivity 之前的可读错误 ----

    @Test
    fun validate_blankName() {
        assertTrue(
            Shortcuts.validate(ShortcutItem(id = "x", pkg = "com.a.b"))!!.contains("名称"),
        )
    }

    @Test
    fun validate_noTarget() {
        assertTrue(
            Shortcuts.validate(ShortcutItem(id = "x", name = "空"))!!.contains("至少"),
        )
    }

    @Test
    fun validate_activityNeedsPkg() {
        assertTrue(
            Shortcuts.validate(ShortcutItem(id = "x", name = "a", activity = "com.a.b.Main"))!!
                .contains("包名"),
        )
    }

    @Test
    fun validate_activityNeedsFullClassName() {
        assertNotNull(
            Shortcuts.validate(ShortcutItem(id = "x", name = "a", pkg = "com.a.b", activity = "Main")),
        )
    }

    @Test
    fun validate_pkgMustLookLikePackage() {
        assertNotNull(
            Shortcuts.validate(ShortcutItem(id = "x", name = "a", pkg = "cainiao")),
        )
    }

    @Test
    fun validate_uriNeedsScheme() {
        assertTrue(
            Shortcuts.validate(ShortcutItem(id = "x", name = "a", uri = "pages-fast.m.taobao.com/x"))!!
                .contains("协议"),
        )
    }

    @Test
    fun validate_validForms() {
        assertNull(Shortcuts.validate(ShortcutItem(id = "x", name = "a", pkg = "com.a.b", activity = "com.a.b.Main")))
        assertNull(Shortcuts.validate(ShortcutItem(id = "x", name = "a", uri = "https://a.b", pkg = "com.a.b")))
        assertNull(Shortcuts.validate(ShortcutItem(id = "x", name = "a", pkg = "com.a.b")))
    }

    // ---- 预设表：三条、口径与实测一致 ----

    @Test
    fun presets_shape() {
        assertEquals(3, Shortcuts.PRESET_SHORTCUTS.size)
        Shortcuts.PRESET_SHORTCUTS.forEachIndexed { index, item ->
            assertEquals("预设下标应连续 0..2", index, item.presetIndex)
            assertTrue("预设 id 应以 preset_ 开头", item.id.startsWith("preset_"))
            assertNull("预设必须自带合法目标", Shortcuts.validate(item))
        }
        val pdd = Shortcuts.PRESET_SHORTCUTS[0]
        assertEquals("pinduoduo://com.xunmeng.pinduoduo/mdkd/package", pdd.uri)
        assertEquals("com.xunmeng.pinduoduo", pdd.pkg)
        val taobao = Shortcuts.PRESET_SHORTCUTS[1]
        assertTrue(taobao.uri.startsWith("https://pages-fast.m.taobao.com/"))
        assertTrue(taobao.uri.endsWith("identity-code"))
        assertEquals("com.taobao.taobao", taobao.pkg)
        val cainiao = Shortcuts.PRESET_SHORTCUTS[2]
        assertEquals("com.cainiao.wireless", cainiao.pkg)
        assertEquals(
            "com.cainiao.wireless.homepage.view.activity.HomePageActivity",
            cainiao.activity,
        )
        // 菜鸟靠显式 Activity 跳过开屏广告，折算必须是 ExplicitComponent 口径
        assertTrue(Shortcuts.resolveLaunchPlan(cainiao) is ShortcutLaunchPlan.ExplicitComponent)
    }

    // ---- JSON：脏数据回退预设、空列表保持为空、roundtrip ----

    @Test
    fun decode_dirtyJsonFallsBackToPresets() {
        assertEquals(Shortcuts.PRESET_SHORTCUTS, Shortcuts.decode("不是 JSON"))
        assertEquals(Shortcuts.PRESET_SHORTCUTS, Shortcuts.decode("{\"a\":1}"))
    }

    @Test
    fun decode_emptyListStaysEmpty() {
        // 用户删光自定义条目是合法状态，不能被「回退预设」吞掉
        assertEquals(emptyList<ShortcutItem>(), Shortcuts.decode("[]"))
    }

    @Test
    fun decode_encodeRoundtrip_andIgnoreUnknownKeys() {
        val items = listOf(
            Shortcuts.PRESET_SHORTCUTS[0],
            ShortcutItem(id = "custom-1", name = "学校官网", uri = "https://example.edu.cn"),
        )
        assertEquals(items, Shortcuts.decode(Shortcuts.encode(items)))
        // 加字段前导出的旧 JSON（多出未知字段）也要能读
        val future = """[{"id":"c1","name":"n","pkg":"com.a.b","unknownField":1}]"""
        assertEquals(listOf(ShortcutItem(id = "c1", name = "n", pkg = "com.a.b")), Shortcuts.decode(future))
    }

    @Test
    fun decode_iconFieldRoundtrip_andLegacyJsonWithoutIcon() {
        val items = listOf(ShortcutItem(id = "c1", name = "n", pkg = "com.a.b", icon = "rocket"))
        assertEquals(items, Shortcuts.decode(Shortcuts.encode(items)))
        // 加 icon 字段之前的存量 JSON：解码出空 icon（渲染时回退默认链接图标）
        val legacy = Shortcuts.decode("""[{"id":"c1","name":"n","pkg":"com.a.b"}]""")
        assertEquals("", legacy[0].icon)
    }

    // ---- ShortcutOps：增、调序、重置 ----

    @Test
    fun ops_moveSwapsNeighbours_andIgnoresEdges() {
        val list = listOf(
            ShortcutItem(id = "a", name = "1"),
            ShortcutItem(id = "b", name = "2"),
            ShortcutItem(id = "c", name = "3"),
        )
        assertEquals(listOf("b", "a", "c"), ShortcutOps.move(list, "a", 1).map { it.id })
        assertEquals(listOf("a", "b", "c"), ShortcutOps.move(list, "a", -1).map { it.id })
        assertEquals(list, ShortcutOps.move(list, "a", 5))
        assertEquals(list, ShortcutOps.move(list, "missing", -1))
    }

    @Test
    fun ops_resetPresetReplacesInPlace() {
        val edited = listOf(
            Shortcuts.PRESET_SHORTCUTS[0],
            Shortcuts.PRESET_SHORTCUTS[1].copy(name = "被改过的淘宝"),
            Shortcuts.PRESET_SHORTCUTS[2],
        )
        val reset = ShortcutOps.resetPreset(edited, 1)
        assertEquals(3, reset.size)
        assertEquals(Shortcuts.PRESET_SHORTCUTS[1], reset[1])
        assertEquals(edited[0], reset[0])
        assertEquals(edited[2], reset[2])
        // 未知下标不动
        assertEquals(edited, ShortcutOps.resetPreset(edited, 9))
    }

    @Test
    fun ops_resetAllRestoresPresets() {
        val mess = listOf(ShortcutItem(id = "custom-x", name = "x"))
        assertEquals(Shortcuts.PRESET_SHORTCUTS, ShortcutOps.resetAll())
        assertNotEquals(mess, ShortcutOps.resetAll())
    }

    @Test
    fun ops_newCustomIdIsUnique() {
        assertNotEquals(ShortcutOps.newCustomId(), ShortcutOps.newCustomId())
        assertTrue(ShortcutOps.newCustomId().startsWith("custom-"))
    }
}
