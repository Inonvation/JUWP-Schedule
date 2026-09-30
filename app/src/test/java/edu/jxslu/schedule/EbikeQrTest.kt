package edu.jxslu.schedule

import edu.jxslu.schedule.domain.EbikeQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享单车骑行二维码（DESIGN §3.9 / §4.18）：URL 拼装、车号校验、输入规整、
 * BitMatrix 参数、最近车号序列化 roundtrip 与旧格式兼容读取。
 *
 * 2026-09-23 车号口径由「尾部 3 位」改为「完整车号」：输入框接受两种形态
 * （1~3 位尾部 / 6~12 位完整车号），拼 URL 只认完整车号。
 */
class EbikeQrTest {

    // ---- bikeUrl：拼装与校验 ----

    @Test
    fun `完整车号拼出行链接`() {
        assertEquals(
            "https://www.kvcoogo.com/ebike?id=100000669",
            EbikeQr.bikeUrl("100000669"),
        )
        // 别的车队的前缀原样保留：不能再补一遍 100000
        assertEquals(
            "https://www.kvcoogo.com/ebike?id=300000604",
            EbikeQr.bikeUrl("300000604"),
        )
        // 前导零保留：车号是字符串不是数字，"007" 不能变 7
        assertEquals(
            "https://www.kvcoogo.com/ebike?id=100000007",
            EbikeQr.bikeUrl("100000007"),
        )
    }

    @Test
    fun `车号位数越界一律拒绝`() {
        assertNull(EbikeQr.bikeUrl(""))
        // 3 位是尾部，不是完整车号：先过 resolveCarNum 补前缀
        assertNull(EbikeQr.bikeUrl("669"))
        // 4~5 位既不是合法尾部也不是完整车号
        assertNull(EbikeQr.bikeUrl("1000"))
        assertNull(EbikeQr.bikeUrl("10000"))
        assertNull(EbikeQr.bikeUrl("1".repeat(EbikeQr.CAR_NUM_MAX_LENGTH + 1)))
        // 边界值本身要能过
        assertNotNull(EbikeQr.bikeUrl("1".repeat(EbikeQr.CAR_NUM_MIN_LENGTH)))
        assertNotNull(EbikeQr.bikeUrl("1".repeat(EbikeQr.CAR_NUM_MAX_LENGTH)))
    }

    @Test
    fun `非数字字符拒绝`() {
        assertNull(EbikeQr.bikeUrl("10000066a"))
        assertNull(EbikeQr.bikeUrl("-100000669"))
        assertNull(EbikeQr.bikeUrl(" 10000669"))
        assertNull(EbikeQr.bikeUrl("一〇〇〇〇〇六六九"))
    }

    // ---- parseScannedCarNum：相机扫一扫的车号识别（2026-09-29） ----

    @Test
    fun `扫描裸车号直接识别`() {
        assertEquals("100000669", EbikeQr.parseScannedCarNum("100000669"))
        // 尾部形态照 resolveCarNum 口径补前缀
        assertEquals("100000669", EbikeQr.parseScannedCarNum("669"))
        assertEquals("300000604", EbikeQr.parseScannedCarNum("300000604"))
    }

    @Test
    fun `扫描官方车身码链接取 id 参数`() {
        // 车身码 = 一条普通链接的二维码，与本 App 出的码同构（可带多余参数）
        assertEquals(
            "100000669",
            EbikeQr.parseScannedCarNum("https://www.kvcoogo.com/ebike?id=100000669"),
        )
        assertEquals(
            "100000669",
            EbikeQr.parseScannedCarNum("https://www.kvcoogo.com/ebike?scene=1&id=100000669&from=body"),
        )
    }

    @Test
    fun `扫描认不出的内容返回null`() {
        // 非车号链接、小程序码、名片码、乱码一律 null，调用方提示"未识别到有效车号"
        assertNull(EbikeQr.parseScannedCarNum(""))
        assertNull(EbikeQr.parseScannedCarNum("   "))
        assertNull(EbikeQr.parseScannedCarNum("https://www.kvcoogo.com/ebike"))
        assertNull(EbikeQr.parseScannedCarNum("https://www.kvcoogo.com/ebike?id=abc"))
        assertNull(EbikeQr.parseScannedCarNum("https://weixin.qq.com/r/abc123"))
        assertNull(EbikeQr.parseScannedCarNum("hello world"))
        // id 位数不成型（4~5 位）也不放行
        assertNull(EbikeQr.parseScannedCarNum("https://www.kvcoogo.com/ebike?id=1000"))
    }

    // ---- decodeFromPixels：相册图片的二维码解码（2026-09-30） ----

    /** [EbikeQr.qrMatrix] 的位阵 → ARGB 像素（黑模块 0xFF000000、白底 0xFFFFFFFF）。 */
    private fun matrixPixels(matrix: com.google.zxing.common.BitMatrix): IntArray =
        IntArray(matrix.width * matrix.height) { i ->
            val x = i % matrix.width
            val y = i / matrix.width
            if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }

    @Test
    fun `相册像素解出码内容`() {
        val url = EbikeQr.bikeUrl("100000669")!!
        val matrix = EbikeQr.qrMatrix(url)
        val pixels = matrixPixels(matrix)
        assertEquals(url, EbikeQr.decodeFromPixels(pixels, matrix.width, matrix.height))
        // 与相机那条路同一个出口：解出的内容照 parseScannedCarNum 取车号
        assertEquals(
            "100000669",
            EbikeQr.parseScannedCarNum(EbikeQr.decodeFromPixels(pixels, matrix.width, matrix.height)!!),
        )
    }

    @Test
    fun `相册像素认不出时返回null`() {
        // 纯白图（用户在相册里随手挑了一张）
        assertNull(EbikeQr.decodeFromPixels(IntArray(64 * 64) { 0xFFFFFFFF.toInt() }, 64, 64))
        // 尺寸与像素对不上：脏输入直接拒，别把越界读带进解码器
        assertNull(EbikeQr.decodeFromPixels(IntArray(10), 64, 64))
        assertNull(EbikeQr.decodeFromPixels(IntArray(0), 0, 0))
    }

    // ---- normalizeCarInput：输入框入口口径 ----

    @Test
    fun `输入只留数字且限长`() {
        assertEquals("", EbikeQr.normalizeCarInput(""))
        assertEquals("", EbikeQr.normalizeCarInput("六六九"))
        assertEquals("69", EbikeQr.normalizeCarInput(" 6a9"))
        assertEquals("100000669", EbikeQr.normalizeCarInput("100000669"))
        // 超长截到上限，不把 13 位原样带进状态
        assertEquals(
            "1".repeat(EbikeQr.INPUT_MAX_LENGTH),
            EbikeQr.normalizeCarInput("1".repeat(EbikeQr.INPUT_MAX_LENGTH + 5)),
        )
    }

    // ---- resolveCarNum：规整输入 → 完整车号 ----

    @Test
    fun `尾部补模板前缀`() {
        assertEquals("100000669", EbikeQr.resolveCarNum("669"))
        assertEquals("100000007", EbikeQr.resolveCarNum("007"))
        // 恰好三位且以模板开头仍是尾部，不该被当成完整车号
        assertEquals("100000100", EbikeQr.resolveCarNum("100"))
    }

    @Test
    fun `完整车号原样保留`() {
        // 粘贴整条校园车号：旧版剥前缀再截三位，结果相同；新版原样留着
        assertEquals("100000669", EbikeQr.resolveCarNum("100000669"))
        // 别的车队：前缀不是 100000，靠尾部三位拼不出来
        assertEquals("300000604", EbikeQr.resolveCarNum("300000604"))
    }

    @Test
    fun `构不成车号的输入返回 null`() {
        assertNull(EbikeQr.resolveCarNum(""))
        assertNull(EbikeQr.resolveCarNum("1000"))
        assertNull(EbikeQr.resolveCarNum("10000"))
        assertNull(EbikeQr.resolveCarNum("10000066a"))
        assertNull(EbikeQr.resolveCarNum("1".repeat(EbikeQr.CAR_NUM_MAX_LENGTH + 1)))
    }

    // ---- inputPrefix：前缀提示只在尾部输入时出现 ----

    @Test
    fun `前缀只在尾部输入时出现`() {
        assertEquals("", EbikeQr.inputPrefix(""))
        assertEquals("100000", EbikeQr.inputPrefix("6"))
        assertEquals("100000", EbikeQr.inputPrefix("669"))
        // 已经输/粘了完整车号：前缀必须消失，否则出的是别家车队的车却顶着校园前缀
        assertEquals("", EbikeQr.inputPrefix("1000"))
        assertEquals("", EbikeQr.inputPrefix("300000604"))
    }

    @Test
    fun `尾部与 chip 文案`() {
        assertEquals("669", EbikeQr.tailOf("100000669"))
        assertEquals("604", EbikeQr.tailOf("300000604"))
        assertEquals("…669", EbikeQr.chipLabel("100000669"))
        // 别的车队给全串：两批车号的尾部会撞（100000669 与 300000669），撞了就得分开
        assertEquals("300000669", EbikeQr.chipLabel("300000669"))
    }

    // ---- needsRegenerate：生成按钮的主次（2026-09-28） ----

    @Test
    fun `没出过码时要生成`() {
        assertTrue(EbikeQr.needsRegenerate("", null))
        assertTrue(EbikeQr.needsRegenerate("669", null))
    }

    @Test
    fun `输入与已出码车号一致时不需要再生成`() {
        // 尾部输入与完整车号是同一辆车（669 → 100000669）
        assertFalse(EbikeQr.needsRegenerate("669", "100000669"))
        assertFalse(EbikeQr.needsRegenerate("100000669", "100000669"))
    }

    @Test
    fun `改了车号或码与输入不符都要重新生成`() {
        assertTrue(EbikeQr.needsRegenerate("512", "100000669"))
        assertTrue(EbikeQr.needsRegenerate("300000604", "100000669"))
        // 输入被清空：码还在屏上，但已经不是"当前输入"的码了
        assertTrue(EbikeQr.needsRegenerate("", "100000669"))
        // 输入还构不成车号（4 位）：同样算需要重新生成
        assertTrue(EbikeQr.needsRegenerate("1000", "100000669"))
    }

    // ---- isCurrentInput：最近 chip 的选中态 ----

    @Test
    fun `chip 选中态认同一辆车`() {
        assertTrue(EbikeQr.isCurrentInput("669", "100000669"))
        assertTrue(EbikeQr.isCurrentInput("100000669", "100000669"))
        assertFalse(EbikeQr.isCurrentInput("512", "100000669"))
        // 别的车队尾部相同也不算同一辆
        assertFalse(EbikeQr.isCurrentInput("100000669", "300000669"))
        // 空输入没有选中项
        assertFalse(EbikeQr.isCurrentInput("", "100000669"))
    }

    // ---- qrMatrix：参数与内容 ----

    @Test
    fun `矩阵尺寸与白边符合约定`() {
        val m = EbikeQr.qrMatrix(EbikeQr.bikeUrl("100000669")!!)
        assertEquals(EbikeQr.QR_SIZE_PX, m.width)
        assertEquals(EbikeQr.QR_SIZE_PX, m.height)
        // MARGIN=1：四角 1 个模块宽的静区应为白
        assertFalse(m.get(0, 0))
        assertFalse(m.get(m.width - 1, 0))
        assertFalse(m.get(0, m.height - 1))
        // 中央必有黑模块（定位图案区），不是全白图
        assertTrue(m.get(m.width / 2, m.height / 2) || m.get(60, 60))
    }

    @Test
    fun `不同车号矩阵不同`() {
        val a = EbikeQr.qrMatrix(EbikeQr.bikeUrl("100000669")!!)
        val b = EbikeQr.qrMatrix(EbikeQr.bikeUrl("100000670")!!)
        var diff = false
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.get(x, y) != b.get(x, y)) {
                    diff = true
                    break
                }
            }
            if (diff) break
        }
        assertTrue("不同车号必须产出不同二维码", diff)
    }

    // ---- 最近车号：merge / roundtrip ----

    @Test
    fun `mergeRecent 倒序去重`() {
        assertEquals(listOf("100000669"), EbikeQr.mergeRecent(emptyList(), "100000669"))
        assertEquals(
            listOf("100000670", "100000669"),
            EbikeQr.mergeRecent(listOf("100000669"), "100000670"),
        )
        // 重复生成同一车号：提前、不重复
        assertEquals(
            listOf("100000669", "100000670"),
            EbikeQr.mergeRecent(listOf("100000670", "100000669"), "100000669"),
        )
    }

    @Test
    fun `mergeRecent 上限8条最旧被挤出`() {
        var list = (1..8).map { "10000000$it" }
        list = EbikeQr.mergeRecent(list, "100000009")
        assertEquals(8, list.size)
        assertEquals("100000009", list.first())
        assertFalse(list.contains("100000008"))
        assertTrue(list.contains("100000001"))
    }

    @Test
    fun `序列化 roundtrip`() {
        val list = listOf("100000669", "100000007", "300000604")
        assertEquals(list, EbikeQr.decodeRecent(EbikeQr.encodeRecent(list)))
    }

    @Test
    fun `旧格式的纯三位条目补前缀读出来`() {
        // 2026-09-23 之前只存尾部三位；升级读法不能把老用户的历史清空
        assertEquals(
            listOf("100000669", "100000007"),
            EbikeQr.decodeRecent("""["669","007"]"""),
        )
    }

    @Test
    fun `脏JSON回空列表`() {
        assertEquals(emptyList<String>(), EbikeQr.decodeRecent(null))
        assertEquals(emptyList<String>(), EbikeQr.decodeRecent(""))
        assertEquals(emptyList<String>(), EbikeQr.decodeRecent("not json"))
        assertEquals(emptyList<String>(), EbikeQr.decodeRecent("""{"a":1}"""))
    }

    @Test
    fun `decode 清理越界与非法条目`() {
        // 超上限截断 + 非法条目剔除 + 去重。
        // "12" 只有两位：旧格式只会存恰好三位，所以它是坏数据，不该被补成 10000012
        val json = """["100000669","100000669","12","abc","001","002","003","004","005","006"]"""
        assertEquals(
            listOf(
                "100000669",
                "100000001",
                "100000002",
                "100000003",
                "100000004",
                "100000005",
                "100000006",
            ),
            EbikeQr.decodeRecent(json),
        )
    }

    // ---- 扫完即焚：待焚毁 key 编码 / 解析 / 合并 ----

    @Test
    fun `待焚毁key 编码与解析 roundtrip`() {
        // MediaStore uri（API 29+）与文件路径（26–28）两条通道
        val media = EbikeQr.pendingMediaKey("content://media/external/images/media/123")
        assertEquals(
            EbikeQr.PendingKind.MediaStore to "content://media/external/images/media/123",
            EbikeQr.parsePendingKey(media),
        )
        val file = EbikeQr.pendingFileKey("/storage/emulated/0/Pictures/水贝贝/ebike-100000669.jpg")
        assertEquals(
            EbikeQr.PendingKind.FilePath to "/storage/emulated/0/Pictures/水贝贝/ebike-100000669.jpg",
            EbikeQr.parsePendingKey(file),
        )
    }

    @Test
    fun `待焚毁key 未知前缀与空目标拒绝`() {
        // 删错文件比漏删一张码严重：脏 key 一律丢弃
        assertNull(EbikeQr.parsePendingKey("x:content://media/1"))
        assertNull(EbikeQr.parsePendingKey("plain-path"))
        assertNull(EbikeQr.parsePendingKey(""))
        assertNull(EbikeQr.parsePendingKey("m:"))
        assertNull(EbikeQr.parsePendingKey("f:"))
    }

    @Test
    fun `mergePendingDelete 去重且不膨胀`() {
        var set = EbikeQr.mergePendingDelete(emptySet(), "m:1")
        assertEquals(setOf("m:1"), set)
        set = EbikeQr.mergePendingDelete(set, "m:1")
        assertEquals(setOf("m:1"), set)
        set = EbikeQr.mergePendingDelete(set, "f:/a/b.jpg")
        assertEquals(setOf("m:1", "f:/a/b.jpg"), set)
    }

    @Test
    fun `mergePendingDelete 超上限兜底不超32`() {
        var set = emptySet<String>()
        repeat(EbikeQr.PENDING_DELETE_LIMIT + 8) { i ->
            set = EbikeQr.mergePendingDelete(set, "m:$i")
        }
        assertEquals(EbikeQr.PENDING_DELETE_LIMIT, set.size)
        assertTrue(set.contains("m:39")) // 最新保留
        assertFalse(set.contains("m:0")) // 兜底挤掉旧记录
    }
}