package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 作业中心的排序 / 汇总 / 截止文案（DESIGN §3.11）。
 *
 * 今日页作业卡、作业中心、课程作业列表三处共用这套口径，排序或文案写错的表现是
 * 「最该先做的作业沉底」「chip 文案对不上日期」——纯函数口径必须在 JVM 单测里钉死。
 */
class HomeworkCenterTest {

    private val today = LocalDate.parse("2026-09-21")

    private fun hw(
        id: Long,
        due: String? = null,
        done: Boolean = false,
        created: Long = 0,
    ) = Homework(
        id = id,
        courseName = "高等数学",
        dueDate = due?.let(LocalDate::parse),
        done = done,
        createdAt = created,
        updatedAt = created,
    )

    // ---- homeworkDisplayTitle（2026-09-23 去标题后的列表摘要口径） ----

    @Test
    fun displayTitle_plainText() {
        assertEquals("完成课后习题", homeworkDisplayTitle("完成课后习题\n\n详情…"))
    }

    @Test
    fun displayTitle_stripsMarkdownPrefixes() {
        assertEquals("任务", homeworkDisplayTitle("- [ ] 任务"))
        assertEquals("列表", homeworkDisplayTitle("- 列表"))
        assertEquals("引用", homeworkDisplayTitle("> 引用"))
        assertEquals("标题", homeworkDisplayTitle("## 标题"))
    }

    @Test
    fun displayTitle_stripsWrapping() {
        assertEquals("加粗", homeworkDisplayTitle("**加粗**"))
        assertEquals("斜体", homeworkDisplayTitle("*斜体*"))
        assertEquals("E = mc^2", homeworkDisplayTitle("\$E = mc^2\$"))
    }

    @Test
    fun displayTitle_skipsBlankLines() {
        assertEquals("正文在第三行", homeworkDisplayTitle("\n\n  \n正文在第三行"))
    }

    @Test
    fun displayTitle_emptyFallsBack() {
        assertEquals("未命名作业", homeworkDisplayTitle(""))
        assertEquals("未命名作业", homeworkDisplayTitle("\n\n"))
        assertEquals("未命名作业", homeworkDisplayTitle("**"))
    }

    /**
     * 图片引用不进摘要（2026-09-24）：此前只剥前缀与包边，正文首行是图片时
     * 列表行把 `![](img:20260924_…jpg)` 原样显示出来。
     */
    @Test
    fun displayTitle_skipsImageOnlyLines() {
        assertEquals("拍张照片", homeworkDisplayTitle("![](img:a.jpg)\n拍张照片"))
        assertEquals("现场图", homeworkDisplayTitle("![现场图](img:a.jpg)\n\n现场图"))
        assertEquals("外链也算", homeworkDisplayTitle("![](https://example.com/a.png)\n外链也算"))
    }

    @Test
    fun displayTitle_stripsImageRefsInsideLine() {
        assertEquals("完成实验", homeworkDisplayTitle("完成实验![](img:a.jpg)"))
        assertEquals("完成实验", homeworkDisplayTitle("- [ ] 完成实验 ![](img:a.jpg)"))
        assertEquals("打卡", homeworkDisplayTitle("## 打卡 ![图](img:a.jpg)"))
    }

    @Test
    fun displayTitle_imageOnlyBodyFallsBack() {
        assertEquals("未命名作业", homeworkDisplayTitle("![](img:a.jpg)"))
        assertEquals("未命名作业", homeworkDisplayTitle("![](img:a.jpg)\n\n![](img:b.jpg)"))
    }

    /** 逾期（降序：最近过期在前）→ 今天 → 未来（升序）→ 无截止（创建倒序） */
    @Test
    fun pendingOrder_overdueThenTodayThenFutureThenNoDue() {
        val items = listOf(
            hw(1, due = "2026-10-05"),            // 未来
            hw(2, due = "2026-09-21"),            // 今天
            hw(3, due = "2026-09-10"),            // 逾期 11 天
            hw(4, due = null, created = 100),     // 无截止
            hw(5, due = "2026-09-18"),            // 逾期 3 天
            hw(6, due = "2026-09-25"),            // 未来
            hw(7, due = null, created = 200),     // 无截止（更新）
        )
        val order = sortPendingHomework(items, today).map { it.id }
        // 逾期组：9-18（最近过期）在 9-10 之前
        // 今天组：2；未来组：6（9-25）在 1（10-05）之前；无截止：7（created 200）在 4 之前
        assertEquals(listOf(5L, 3L, 2L, 6L, 1L, 7L, 4L), order)
    }

    @Test
    fun pendingSummary_countsAndNearest() {
        val items = listOf(
            hw(1, due = "2026-10-05"),
            hw(2, due = "2026-09-10"),   // 逾期
            hw(3, due = "2026-09-18"),   // 逾期
            hw(4, due = "2026-09-25"),
            hw(5, due = "2026-09-01", done = true),  // 已完成：不进汇总
            hw(6, due = null),
        )
        val summary = pendingHomework(items, today)
        assertEquals(5, summary.total)
        assertEquals(2, summary.overdue)
        // 最近「未过期」截止 = 9-25（今天没有截止的作业）
        assertEquals(LocalDate.parse("2026-09-25"), summary.nextDue)
        // 最早截止（含逾期）
        assertEquals(LocalDate.parse("2026-09-10"), summary.earliestDue)
    }

    @Test
    fun pendingSummary_todayCountsAsNextDueNotOverdue() {
        val summary = pendingHomework(listOf(hw(1, due = "2026-09-21")), today)
        assertEquals(0, summary.overdue)
        assertEquals(today, summary.nextDue)
    }

    @Test
    fun pendingSummary_emptyWhenAllDone() {
        val summary = pendingHomework(listOf(hw(1, due = "2026-09-01", done = true)), today)
        assertTrue(summary.isEmpty)
        assertEquals(0, summary.total)
        assertNull(summary.nextDue)
    }

    @Test
    fun dueLabel_branches() {
        assertNull(dueLabel(null, today))
        assertEquals("今天", dueLabel(today, today))
        assertEquals("明天", dueLabel(today.plusDays(1), today))
        assertEquals("已过期 1 天", dueLabel(today.minusDays(1), today))
        assertEquals("已过期 30 天", dueLabel(today.minusDays(30), today))
        assertEquals("10月12日", dueLabel(LocalDate.parse("2026-10-12"), today))
    }

    /**
     * 详情页截止文案：**不再自我复读**（旧版对远期日期给出「11月21日（11月21日）」），
     * 远期补星期几，近期给「今天/已过期 N 天 · M月d日」。
     */
    @Test
    fun dueDetailLabel_noDuplicateDateAndWeekday() {
        val t = LocalDate.parse("2026-09-21") // 周一
        assertEquals("今天 · 9月21日", dueDetailLabel(t, t))
        assertEquals("明天 · 9月22日", dueDetailLabel(t.plusDays(1), t))
        assertEquals("已过期 2 天 · 9月19日", dueDetailLabel(t.minusDays(2), t))
        assertEquals("11月21日 · 周六", dueDetailLabel(LocalDate.parse("2026-11-21"), t))
        assertEquals("10月12日 · 周一", dueDetailLabel(LocalDate.parse("2026-10-12"), t))
        // 同一个日期只会出现一次
        val far = dueDetailLabel(LocalDate.parse("2026-11-21"), t)
        assertEquals(1, far.split("11月21日").size - 1)
    }

    /** 课程作业列表：未完成（走统一排序）在前，已完成在后（最近完成的在前）。 */
    @Test
    fun courseOrder_pendingFirstThenDone() {
        val items = listOf(
            hw(1, due = "2026-09-25", done = true, created = 100),
            hw(2, due = "2026-09-22"),
            hw(3, due = "2026-09-18"),
            hw(4, due = null, done = true, created = 300),
        )
        // updatedAt 参与「已完成」排序，用例里用 created 同值构造
        val order = courseHomeworkOrder(items, today).map { it.id }
        assertEquals(listOf(3L, 2L, 4L, 1L), order)
    }
}
