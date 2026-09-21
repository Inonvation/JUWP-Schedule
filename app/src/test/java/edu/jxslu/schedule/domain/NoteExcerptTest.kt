package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摘要提取与图片引用（DESIGN §3.11 列表项 / §4.20 附件）。
 *
 * 摘要是列表项的"正文首行"，图片引用是附件清理的唯一依据——两处错了都不会崩，
 * 只会悄悄丢图或显示乱码，必须在纯函数层钉住。
 */
class NoteExcerptTest {

    @Test
    fun excerpt_prefersBodyOverHeading() {
        // 标题不占摘要位（笔记另有 title 字段），正文优先
        assertEquals("正文内容", plainExcerpt("# 第一章 绪论" + "\n" + "正文内容"))
        // 正文只有标题时，标题兜底
        assertEquals("第一章 绪论", plainExcerpt("# 第一章 绪论"))
    }

    @Test
    fun excerpt_skipsMathAndImagesAndCode() {
        val body = """
            ![图](img:a.jpg)

            推导如下：${'$'}\frac{a}{b}${'$'}

            ```kotlin
            val a = 1
            ```

            结论是 42
        """.trimIndent()
        val excerpt = plainExcerpt(body)
        assertFalse(excerpt.contains("img:"))
        assertFalse(excerpt.contains("frac"))
        assertFalse(excerpt.contains("val a"))
        assertTrue(excerpt.contains("推导如下"))
        assertTrue(excerpt.contains("结论是 42"))
    }

    @Test
    fun excerpt_truncatesWithEllipsis() {
        val long = "字".repeat(200)
        val excerpt = plainExcerpt(long, max = 20)
        assertEquals(20, excerpt.length)
        assertTrue(excerpt.endsWith("…"))
    }

    @Test
    fun excerpt_emptyBody() {
        assertEquals("", plainExcerpt(""))
        assertEquals("", plainExcerpt("![图](img:a.jpg)"))
    }

    @Test
    fun excerpt_collapsesWhitespace() {
        assertEquals("甲 乙", plainExcerpt("甲\n\n\n乙"))
    }

    // ---- 图片引用 ----

    @Test
    fun imageRefs_collectsUniqueNames() {
        val body = "![](img:a.jpg)\n\n文字\n\n![说明](img:b.png)\n![](img:a.jpg)"
        assertEquals(setOf("a.jpg", "b.png"), imageRefs(body))
        assertTrue(hasImageRef(body))
        assertFalse(hasImageRef("没有图片"))
    }

    @Test
    fun imageRefToken_roundTrips() {
        val body = "前\n\n" + imageRefToken("20260921_ab.jpg") + "\n\n后"
        assertEquals(setOf("20260921_ab.jpg"), imageRefs(body))
    }

    @Test
    fun removeImageRef_cleansEmptyLines() {
        val body = "甲\n\n![](img:a.jpg)\n\n乙"
        val cleaned = removeImageRef(body, "a.jpg")
        assertFalse(cleaned.contains("img:"))
        assertEquals("甲\n\n乙", cleaned)
        // 其他图片不受影响
        val two = "![](img:a.jpg)\n![](img:b.jpg)"
        assertEquals("![](img:b.jpg)", removeImageRef(two, "a.jpg"))
    }
}
