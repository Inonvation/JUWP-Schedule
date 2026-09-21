package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器纯函数（DESIGN §3.11「自动补全」）。
 *
 * 续行/包裹算错的表现是「打字时文本被吃掉」或「列表越写越乱」，都属于手感级问题，
 * 只能在纯函数层钉死——UI 侧不许再写一份判断。
 */
class MarkdownEditTest {

    // ---- 回车续行 ----

    @Test
    fun enter_continuesBulletWithIndent() {
        val text = "  - 第一项"
        val result = continueOnEnter(text, text.length)!!
        assertEquals("  - 第一项\n  - ", result.text)
        assertEquals(result.text.length, result.selectionStart)
    }

    @Test
    fun enter_continuesOrderedAndIncrements() {
        val text = "1. 第一项"
        val r = continueOnEnter(text, text.length)!!
        assertEquals("1. 第一项\n2. ", r.text)

        val nested = "   9. 第九项"
        assertEquals("   9. 第九项\n   10. ", continueOnEnter(nested, nested.length)!!.text)
    }

    @Test
    fun enter_continuesTaskAsUnchecked() {
        val text = "- [x] 做完了"
        assertEquals("- [x] 做完了\n- [ ] ", continueOnEnter(text, text.length)!!.text)
    }

    @Test
    fun enter_continuesQuote() {
        val text = "> 引用"
        assertEquals("> 引用\n> ", continueOnEnter(text, text.length)!!.text)
    }

    @Test
    fun enter_onEmptyItem_endsList() {
        val text = "- 第一项\n- "
        val r = continueOnEnter(text, text.length)!!
        assertEquals("- 第一项\n", r.text)
        assertEquals(text.length - 2, r.selectionStart)
    }

    @Test
    fun enter_insideFence_doesNothing() {
        val text = "```\n- 代码里的横杠"
        assertNull(continueOnEnter(text, text.length))
    }

    @Test
    fun enter_plainLineAndMidLine_doesNothing() {
        assertNull(continueOnEnter("普通段落", 4))
        // 光标在行中间（右侧还有内容）→ 交给输入框，不续行
        assertNull(continueOnEnter("- 列表项", 3))
        assertNull(continueOnEnter("", 0))
    }

    // ---- 工具条动作 ----

    @Test
    fun bold_wrapsSelection() {
        val text = "重点"
        val r = applyMarkdownAction(text, 0, 2, MdAction.Bold)
        assertEquals("**重点**", r.text)
        assertEquals(2, r.selectionStart)
        assertEquals(4, r.selectionEnd)
    }

    @Test
    fun bold_withoutSelection_pairsMarkers() {
        val r = applyMarkdownAction("", 0, 0, MdAction.Bold)
        assertEquals("****", r.text)
        assertEquals(2, r.selectionStart)
    }

    @Test
    fun bold_againOnWrappedSelection_unwraps() {
        val r = applyMarkdownAction("**重点**", 0, 6, MdAction.Bold)
        assertEquals("重点", r.text)
    }

    @Test
    fun math_withoutSelection_pairsDollar() {
        val r = applyMarkdownAction("能量 ", 3, 3, MdAction.Math)
        assertEquals("能量 \$\$", r.text)
        assertEquals(4, r.selectionStart)
    }

    @Test
    fun heading_replacesOtherLevelAndToggles() {
        assertEquals("## 标题", applyMarkdownAction("标题", 0, 0, MdAction.Heading).text)
        assertEquals("## 标题", applyMarkdownAction("### 标题", 0, 0, MdAction.Heading).text)
        assertEquals("标题", applyMarkdownAction("## 标题", 0, 0, MdAction.Heading).text)
    }

    @Test
    fun bullet_togglesOnAllSelectedLines() {
        val text = "甲\n乙"
        val r = applyMarkdownAction(text, 0, text.length, MdAction.Bullet)
        assertEquals("- 甲\n- 乙", r.text)
        val back = applyMarkdownAction(r.text, 0, r.text.length, MdAction.Bullet)
        assertEquals("甲\n乙", back.text)
    }

    @Test
    fun ordered_numbersSelectedLines() {
        val text = "甲\n乙\n丙"
        assertEquals("1. 甲\n2. 乙\n3. 丙", applyMarkdownAction(text, 0, text.length, MdAction.Ordered).text)
    }

    @Test
    fun task_insertsCheckboxPrefix() {
        assertEquals("- [ ] 交作业", applyMarkdownAction("交作业", 0, 0, MdAction.Task).text)
    }

    // ---- `$` 自动配对 ----

    @Test
    fun dollar_autopairs() {
        val before = "公式 "
        val after = "公式 \$"
        val r = autoPairDollar(before, after, after.length)!!
        assertEquals("公式 \$\$", r.text)
        assertEquals(4, r.selectionStart)
    }

    @Test
    fun dollar_typingSecondOne_doesNotPair() {
        // 用户自己敲第二个 `$`
        assertNull(autoPairDollar("公式 \$", "公式 \$\$", 4))
    }

    @Test
    fun dollar_otherEdits_ignored() {
        assertNull(autoPairDollar("abc", "abcd", 4))
        assertNull(autoPairDollar("", "", 0))
    }

    // ---- 图片插入 ----

    @Test
    fun insertImage_onEmptyText() {
        val r = insertImageRef("", 0, "a.jpg")
        assertEquals("![](img:a.jpg)\n", r.text)
    }

    @Test
    fun insertImage_splitsLines() {
        val text = "正文"
        val r = insertImageRef(text, text.length, "a.jpg")
        assertTrue(r.text.startsWith("正文\n\n"))
        assertTrue(r.text.contains("![](img:a.jpg)"))
        assertEquals(r.text.length, r.selectionStart)
    }
}
