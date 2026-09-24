package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 子集解析（DESIGN §4.20）。
 *
 * 解析错的表现是「标记没生效」或更糟——**吃掉用户内容**（未闭合的强调、公式）。
 * 这里把支持清单的每一条都钉一遍，未闭合路径专门覆盖。
 */
class MarkdownParserTest {

    private fun text(block: MdBlock): String = when (block) {
        is MdBlock.Paragraph -> plainTextOf(block.content)
        is MdBlock.Heading -> plainTextOf(block.content)
        else -> ""
    }

    private fun firstParagraph(text: String): String =
        parseMarkdown(text).filterIsInstance<MdBlock.Paragraph>().first().let { plainTextOf(it.content) }

    // ---- 块级 ----

    @Test
    fun heading_levels() {
        val blocks = parseMarkdown("# 一级\n## 二级\n###### 六级")
        assertEquals(3, blocks.size)
        assertEquals(listOf(1, 2, 6), blocks.map { (it as MdBlock.Heading).level })
        assertEquals("一级", text(blocks[0]))
    }

    @Test
    fun heading_requiresSpaceAfterHashes() {
        // `#话题` 不是标题
        val blocks = parseMarkdown("#标签 这种不算标题")
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun paragraph_keepsSingleNewline() {
        // 单换行 = 换行（对齐 Obsidian 默认），不折叠成空格
        assertEquals("第一行\n第二行", firstParagraph("第一行\n第二行"))
    }

    @Test
    fun bulletList_withNestedItems() {
        val list = parseMarkdown(
            """
            - 第一项
            - 第二项
              - 嵌套一
              - 嵌套二
            """.trimIndent(),
        ).first() as MdBlock.ListBlock
        assertEquals(2, list.items.size)
        assertEquals("第二项", plainTextOf(list.items[1].content))
        val sub = list.items[1].sub.first() as MdBlock.ListBlock
        assertEquals(2, sub.items.size)
        assertEquals("嵌套一", plainTextOf(sub.items[0].content))
    }

    @Test
    fun orderedList_keepsStartNumber() {
        val list = parseMarkdown("3. 三\n4. 四").first() as MdBlock.ListBlock
        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
    }

    @Test
    fun taskList_checkedAndUnchecked() {
        val list = parseMarkdown("- [ ] 待办\n- [x] 已完成").first() as MdBlock.ListBlock
        assertEquals(false, list.items[0].task)
        assertEquals(true, list.items[1].task)
        assertEquals("待办", plainTextOf(list.items[0].content))
    }

    @Test
    fun quote_canHoldBlocks() {
        val quote = parseMarkdown("> 引用一行\n> \n> - 列表项").first() as MdBlock.Quote
        assertTrue(quote.blocks.any { it is MdBlock.Paragraph })
        assertTrue(quote.blocks.any { it is MdBlock.ListBlock })
    }

    @Test
    fun fence_codeBlock_keepsRawText() {
        val code = parseMarkdown("```kotlin\nval a = **1**\n```").first() as MdBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val a = **1**", code.text)
    }

    @Test
    fun fence_unclosedSwallowsToEnd() {
        // 未闭合围栏：内容照常展示（不能丢）
        val code = parseMarkdown("```\nnot closed").first() as MdBlock.Code
        assertEquals("not closed", code.text)
    }

    @Test
    fun divider_variants() {
        assertEquals(3, parseMarkdown("---\n***\n___").size)
        assertEquals(1, parseMarkdown("-----").size)
        assertTrue(parseMarkdown("-----").first() is MdBlock.Divider)
    }

    @Test
    fun mathBlock_inlineAndMultiline() {
        val dollar = "$"
        assertEquals(
            "E = mc^2",
            (parseMarkdown(dollar + dollar + "E = mc^2" + dollar + dollar).first() as MdBlock.MathBlock).tex,
        )
        val multi = parseMarkdown("$$\n\\frac{a}{b}\n$$").first() as MdBlock.MathBlock
        assertEquals("\\frac{a}{b}", multi.tex)
    }

    @Test
    fun table_likeRawsStayParagraphs() {
        // 表格不在支持清单内：按普通段落渲染，不吞内容
        val blocks = parseMarkdown("| a | b |\n| --- | --- |\n| 1 | 2 |")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(firstParagraph("| a | b |\n| - |").contains("| a | b |"))
    }

    // ---- 行内 ----

    private fun inlines(text: String): List<MdInline> = parseInlines(text)

    @Test
    fun bold_italic_strike() {
        val nodes = inlines("**粗** *斜* ~~删~~")
        assertTrue(nodes.any { it is MdInline.Styled && it.style == MdStyle.Bold })
        assertTrue(nodes.any { it is MdInline.Styled && it.style == MdStyle.Italic })
        assertTrue(nodes.any { it is MdInline.Styled && it.style == MdStyle.Strike })
    }

    @Test
    fun emphasis_notInsideWords() {
        // snake_case 不该变斜体
        assertEquals("snake_case_name", plainTextOf(inlines("snake_case_name")))
    }

    @Test
    fun nestedBoldItalic() {
        val bold = inlines("**粗里的 *斜* 字**").first { it is MdInline.Styled } as MdInline.Styled
        assertEquals(MdStyle.Bold, bold.style)
        assertTrue(bold.content.any { it is MdInline.Styled && it.style == MdStyle.Italic })
    }

    @Test
    fun unclosedEmphasis_staysLiteral() {
        // 未闭合：字面文本，一个字符都不能丢
        assertEquals("**没有闭合", plainTextOf(inlines("**没有闭合")))
        assertEquals("*单星号", plainTextOf(inlines("*单星号")))
    }

    @Test
    fun codeSpan_andEscapes() {
        val nodes = inlines("`a**b**` 与 \\*转义\\*")
        assertEquals("a**b**", (nodes.first { it is MdInline.Code } as MdInline.Code).text)
        assertTrue(plainTextOf(nodes).contains("*转义*"))
    }

    @Test
    fun inlineMath_basicAndAmounts() {
        val nodes = inlines("面积 \$\\pi r^2\$ 与金额 \$100")
        val math = nodes.filterIsInstance<MdInline.Math>()
        assertEquals(1, math.size)
        assertEquals("\\pi r^2", math[0].tex)
        assertTrue(plainTextOf(nodes).contains("\$100"))
    }

    @Test
    fun image_andLink() {
        val nodes = inlines("看图 ![照片](img:20260921_abcd.jpg) 与 [链接](https://example.com)")
        val image = nodes.filterIsInstance<MdInline.Image>().single()
        assertEquals("img:20260921_abcd.jpg", image.ref)
        val link = nodes.filterIsInstance<MdInline.Link>().single()
        assertEquals("https://example.com", link.url)
        assertEquals("链接", plainTextOf(link.content))
    }

    @Test
    fun chineseWithNumbers_mixed() {
        assertEquals("第 3 章共 12 讲", firstParagraph("第 3 章共 12 讲"))
    }

    // ---- 段落切分：独占一行的图片走块级（2026-09-24 修「作业预览图片显示为蓝链」） ----

    private fun paragraphOf(text: String): MdBlock.Paragraph =
        parseMarkdown(text).single() as MdBlock.Paragraph

    /**
     * 根因钉子：单换行不构成新段落，连插两张图在 AST 里是**同一个 Paragraph**，
     * 渲染层必须自己切开（旧实现只认「整段唯一节点是图片」，于是退化成蓝标签）。
     */
    @Test
    fun consecutiveImages_shareOneParagraph() {
        val para = paragraphOf("![](img:a.jpg)\n![](img:b.jpg)")
        assertEquals(
            listOf("img:a.jpg", "img:b.jpg"),
            para.content.filterIsInstance<MdInline.Image>().map { it.ref },
        )
    }

    @Test
    fun splitParagraph_consecutiveImagesBecomeBlocks() {
        val parts = splitParagraph(paragraphOf("![](img:a.jpg)\n![](img:b.jpg)").content)
        assertEquals(2, parts.size)
        assertEquals("img:a.jpg", (parts[0] as MdParagraphPart.BlockImage).ref)
        assertEquals("img:b.jpg", (parts[1] as MdParagraphPart.BlockImage).ref)
    }

    @Test
    fun splitParagraph_textLineThenImage() {
        val parts = splitParagraph(paragraphOf("作业要求\n![](img:a.jpg)").content)
        assertEquals(2, parts.size)
        assertEquals("作业要求", plainTextOf((parts[0] as MdParagraphPart.Inline).content))
        assertEquals("img:a.jpg", (parts[1] as MdParagraphPart.BlockImage).ref)
    }

    @Test
    fun splitParagraph_imageWithTextOnSameLineStaysInline() {
        val parts = splitParagraph(paragraphOf("见下图 ![](img:a.jpg) 交作业").content)
        assertEquals(1, parts.size)
        assertTrue(parts[0] is MdParagraphPart.Inline)
    }

    @Test
    fun splitParagraph_twoImagesOnOneLineStayInline() {
        val parts = splitParagraph(paragraphOf("![](img:a.jpg)![](img:b.jpg)").content)
        assertEquals(1, parts.size)
        assertTrue(parts[0] is MdParagraphPart.Inline)
    }

    @Test
    fun splitParagraph_blankEdgesDoNotLeakIntoInline() {
        val parts = splitParagraph(
            listOf(
                MdInline.Text("\n"),
                MdInline.Image(alt = "", ref = "img:a.jpg"),
                MdInline.Text("\n  \n"),
                MdInline.Text("后面还有话"),
            ),
        )
        assertEquals(2, parts.size)
        assertEquals("img:a.jpg", (parts[0] as MdParagraphPart.BlockImage).ref)
        assertEquals("后面还有话", plainTextOf((parts[1] as MdParagraphPart.Inline).content))
    }

    @Test
    fun splitParagraph_externalImageKeepsBlockForm() {
        // 外链图片同样是块级（渲染层换成「外链图片不在本机显示」提示，不是蓝标签）
        val parts = splitParagraph(paragraphOf("![](https://example.com/a.png)").content)
        assertEquals("https://example.com/a.png", (parts.single() as MdParagraphPart.BlockImage).ref)
    }
}
