package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 菜鸟批量查的纯逻辑口径（DESIGN §4.47）：输入清洗拆分、链接拼装、历史编解码。
 * 链接模板来自菜鸟 8.11.126 的真机实测（2026-10-12），改动前先在真机重验；
 * 清洗判据（全角折半角 / 8~40 位含数字 / 大小写不敏感去重 / 上限 20）见
 * [CainiaoQuery.parseInput] 的 KDoc。
 */
class CainiaoQueryTest {

    // ---- parseInput：分隔符与清洗 ----

    @Test
    fun parse_commaNewlineAndSpace() {
        assertEquals(
            listOf("JT1234567890", "YT9876543210", "789012345678"),
            CainiaoQuery.parseInput("JT1234567890,YT9876543210\n789012345678").numbers,
        )
    }

    @Test
    fun parse_fullWidthCommaDunHaoAndSemicolon() {
        assertEquals(
            listOf("SF1234567890123", "JD000123456789", "ZT1234567890", "YT1234567890123"),
            CainiaoQuery.parseInput("SF1234567890123，JD000123456789、ZT1234567890;YT1234567890123；")
                .numbers,
        )
    }

    @Test
    fun parse_fullWidthSpaceAndNbspAlsoSplit() {
        // U+3000（全角空格）与 U+00A0（不换行空格）——正则 \s 认不出，得显式补
        assertEquals(
            listOf("JT1234567890", "YT9876543210"),
            CainiaoQuery.parseInput("JT1234567890\u3000YT9876543210").numbers,
        )
        assertEquals(
            listOf("ZT1234567890", "SF1234567890123"),
            CainiaoQuery.parseInput("ZT1234567890\u00A0SF1234567890123").numbers,
        )
    }

    @Test
    fun parse_extractsNumberFromSurroundingText() {
        // 粘来的「运单号：JT…，请查收」：前缀说明被洗掉，后缀短句计入 ignored
        val r = CainiaoQuery.parseInput("运单号：JT1234567890，请查收")
        assertEquals(listOf("JT1234567890"), r.numbers)
        assertEquals(1, r.ignored)
    }

    @Test
    fun parse_fullWidthLettersAndDigitsFoldToHalfWidth() {
        assertEquals(
            listOf("JT1234567890"),
            CainiaoQuery.parseInput("ＪＴ１２３４５６７８９０").numbers,
        )
    }

    @Test
    fun parse_dropsShortOrDigitlessJunkAndCountsIt() {
        val r = CainiaoQuery.parseInput("您有1个包裹\nabcdefghij\nJT1234567890")
        assertEquals(listOf("JT1234567890"), r.numbers)
        assertEquals(2, r.ignored)
    }

    @Test
    fun parse_overlongTokenIgnored() {
        val long = "1234567890" + "a".repeat(CainiaoQuery.MAX_LEN)
        val r = CainiaoQuery.parseInput("$long,JT1234567890")
        assertEquals(listOf("JT1234567890"), r.numbers)
        assertEquals(1, r.ignored)
    }

    @Test
    fun parse_separatorNoiseIsNotCountedAsJunk() {
        // 首尾 / 连续的逗号分号只是分隔符，不算「忽略」
        val r = CainiaoQuery.parseInput(" , 、；\nJT1234567890，  ")
        assertEquals(listOf("JT1234567890"), r.numbers)
        assertEquals(0, r.ignored)
        assertEquals(0, r.duplicates)
    }

    @Test
    fun parse_punctuationOnlyInputGivesEmptyResult() {
        val blank = CainiaoQuery.parseInput("")
        assertEquals(emptyList<String>(), blank.numbers)
        assertEquals(0, blank.ignored)
        val punct = CainiaoQuery.parseInput("。。、、；\nJT1234567890")
        assertEquals(listOf("JT1234567890"), punct.numbers)
        assertEquals(1, punct.ignored) // 「。。」一段
    }

    // ---- parseInput：去重与上限 ----

    @Test
    fun parse_dedupesCaseInsensitivelyKeepingFirstForm() {
        val r = CainiaoQuery.parseInput("JT1234567890,jt1234567890,YT9876543210,JT1234567890")
        assertEquals(listOf("JT1234567890", "YT9876543210"), r.numbers)
        assertEquals(2, r.duplicates)
    }

    @Test
    fun parse_capsAtMaxCountAndCountsOverflow() {
        val many = (1..(CainiaoQuery.MAX_COUNT + 5)).joinToString(",") { "N${it}1234567890" }
        val r = CainiaoQuery.parseInput(many)
        assertEquals(CainiaoQuery.MAX_COUNT, r.numbers.size)
        assertEquals("N11234567890", r.numbers.first())
        assertEquals("N${CainiaoQuery.MAX_COUNT}1234567890", r.numbers.last())
        assertEquals(5, r.overflow)
        assertEquals(0, r.duplicates)
        assertEquals(0, r.ignored)
    }

    // ---- buildLink：模板前缀 + 内容编码 ----

    @Test
    fun buildLink_prefixIsTheProvenTemplate() {
        assertTrue(
            CainiaoQuery.buildLink(listOf("JT123")).startsWith(
                "cainiao://startapp/search_alert?querySourceId=68719476739&prePath=Pasteboard&content=",
            ),
        )
    }

    @Test
    fun buildLink_plainNumbersPassThroughVerbatim() {
        // 常规单号（字母数字）与逗号原样直出——这是真机验证过的形态，别加编码
        assertTrue(CainiaoQuery.buildLink(listOf("JT123", "YT456")).endsWith("JT123,YT456"))
        assertTrue(CainiaoQuery.buildLink(listOf("AB-C.1_2")).endsWith("AB-C.1_2"))
    }

    @Test
    fun buildLink_specialCharsArePercentEncoded() {
        assertTrue(CainiaoQuery.buildLink(listOf("A#B")).endsWith("A%23B"))
        // 中文按 UTF-8 百分号编码（兜底用，正常单号不会走到）
        assertTrue(CainiaoQuery.buildLink(listOf("中")).endsWith("%E4%B8%AD"))
    }

    // ---- 历史：入史、编解码 ----

    @Test
    fun entryOf_joinsWithComma() {
        assertEquals("A1,B2", CainiaoQuery.entryOf(listOf("A1", "B2")))
    }

    @Test
    fun entryCount_countsNonBlankSegments() {
        assertEquals(3, CainiaoQuery.entryCount("A1,B2,C3"))
        assertEquals(1, CainiaoQuery.entryCount("A1"))
        assertEquals(0, CainiaoQuery.entryCount(""))
    }

    @Test
    fun pushHistory_movesToFrontDedupesAndCaps() {
        assertEquals(
            listOf("c", "a", "b", "d", "e"),
            CainiaoQuery.pushHistory(listOf("a", "b", "c", "d", "e"), "c"),
        )
        assertEquals(
            listOf("f", "a", "b", "c", "d"),
            CainiaoQuery.pushHistory(listOf("a", "b", "c", "d", "e"), "f"),
        )
    }

    @Test
    fun historyCodec_roundtrip() {
        val history = listOf("A1,B2", "C3")
        assertEquals(history, CainiaoQuery.decodeHistory(CainiaoQuery.encodeHistory(history)))
    }

    @Test
    fun decodeHistory_dirtyInputFallsBackToEmpty() {
        assertEquals(emptyList<String>(), CainiaoQuery.decodeHistory(null))
        assertEquals(emptyList<String>(), CainiaoQuery.decodeHistory(""))
        assertEquals(emptyList<String>(), CainiaoQuery.decodeHistory("不是 JSON"))
        assertEquals(emptyList<String>(), CainiaoQuery.decodeHistory("""{"a":1}"""))
    }

    @Test
    fun decodeHistory_cleansBlanksDuplicatesAndCaps() {
        assertEquals(
            listOf("a", "b", "c", "d", "e"),
            CainiaoQuery.decodeHistory("""["a","","a","b","c","d","e","f"]"""),
        )
    }
}
