package edu.jxslu.schedule.qiekj

import edu.jxslu.schedule.data.qiekj.dumpQiekjResponse
import edu.jxslu.schedule.data.qiekj.dumpQiekjProbe
import edu.jxslu.schedule.data.qiekj.isQiekjDumpTarget
import edu.jxslu.schedule.data.qiekj.pruneQiekjDumps
import edu.jxslu.schedule.data.qiekj.QiekjProbeSnapshot
import edu.jxslu.schedule.data.qiekj.redactQiekjDump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 采样落盘的三处判据（2026-10-05）：白名单、凭证脱敏、按端点裁剪。
 * 拦截器本身要 okhttp Chain 才能跑，不在这里造；纯逻辑出问题才是真会翻车的地方。
 */
class QiekjDebugDumpTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun 白名单只收只读与开水链路() {
        assertTrue(isQiekjDumpTarget("goods/latestUsed"))
        assertTrue(isQiekjDumpTarget("goods/normal/details"))
        assertTrue(isQiekjDumpTarget("goods/normal/skus"))
        assertTrue(isQiekjDumpTarget("goods/water/sync"))
        assertTrue(isQiekjDumpTarget("goods/water/unlock"))
        assertTrue(isQiekjDumpTarget("appointNew/near/newMachines"))
    }

    @Test
    fun 登录与余额不在白名单() {
        // user/reg 的响应体带 token；user/balance 与设备状态无关
        assertFalse(isQiekjDumpTarget("user/reg"))
        assertFalse(isQiekjDumpTarget("user/balance"))
        assertFalse(isQiekjDumpTarget("common/sms/sendCode"))
    }

    @Test
    fun 脱敏替换token与phone字段() {
        val raw = """{"code":0,"data":{"token":"abc123","phone":"13800000000","goodsId":"9"}}"""
        val redacted = redactQiekjDump(raw)
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("13800000000"))
        assertTrue(redacted.contains(""""goodsId":"9""""))
    }

    @Test
    fun 脱敏不碰同名字段的普通文本() {
        // 没有 token/phone 字段时原样返回，避免误伤
        val raw = """{"code":0,"data":{"goodsName":"token 取水点"}}"""
        assertEquals(raw, redactQiekjDump(raw))
    }

    @Test
    fun 落盘后每个端点只留最新若干份() {
        val dir = temp.newFolder("qiekj_debug")
        repeat(45) { index ->
            File(dir, "goods_water_sync-20261005-1200%02d-000.json".format(index)).writeText("{}")
        }
        pruneQiekjDumps(dir, "goods_water_sync", keep = 40)
        val left = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(40, left.size)
        // 删的是最旧的五份
        assertFalse(left.contains("goods_water_sync-20261005-120000-000.json"))
        assertTrue(left.contains("goods_water_sync-20261005-120044-000.json"))
    }

    @Test
    fun 落盘文件名去掉路径分隔符且内容含端点与脱敏正文() {
        val dir = temp.newFolder("dump")
        dumpQiekjResponse(
            dir = dir,
            path = "goods/water/sync",
            httpStatus = 200,
            body = """{"code":0,"data":{"workStatus":2,"token":"leak"}}""",
        )
        val file = dir.listFiles()!!.single()
        assertTrue(file.name.startsWith("goods_water_sync-"))
        val text = file.readText()
        assertTrue(text.contains(""""workStatus":2"""))
        assertFalse(text.contains("leak"))
    }

    @Test
    fun 非JSON正文按字符串落盘不丢内容() {
        // 网关返回 html 错误页时不能因为解析失败把样本丢掉
        val dir = temp.newFolder("dump_html")
        dumpQiekjResponse(dir, "goods/water/sync", httpStatus = 502, body = "<html>bad gateway</html>")
        val text = dir.listFiles()!!.single().readText()
        assertTrue(text.contains("bad gateway"))
        assertTrue(text.contains(""""httpStatus":502"""))
    }

    @Test
    fun 探测摘要落盘文件名与关键字段() {
        val dir = temp.newFolder("probe")
        dumpQiekjProbe(
            dir = dir,
            snapshot = QiekjProbeSnapshot(
                at = "20261005-230000-000",
                goodsId = "1100",
                latestUsedWorkStatus = 10,
                skuId = "sku",
                syncStatus = 6,
                syncWorkStatus = 1,
                syncAmount = null,
                syncIdentifyPresent = false,
                shopId = "2024",
                deviceErrorCode = null,
                deviceErrorMsg = null,
                nearMachinesCode = 0,
                nearMachinesMsg = "成功",
            ),
        )
        val file = dir.listFiles()!!.single()
        assertEquals("probe-20261005-230000-000.json", file.name)
        val text = file.readText()
        assertTrue(text.contains(""""latestUsedWorkStatus":10"""))
        assertTrue(text.contains(""""nearMachinesCode":0"""))
        assertTrue(text.contains(""""syncIdentifyPresent":false"""))
    }
}
