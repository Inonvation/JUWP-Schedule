package edu.jxslu.schedule.domain

import java.time.LocalDate

/**
 * 一条作业（DESIGN §4.20）。归属口径与 [Note] 完全一致：挂**课程名**，不带 courseId / timetableId。
 *
 * 与笔记的差异只有两处：[dueDate]（提交截止日期，**只到日粒度**，可空 = 布置了但没给期限）
 * 与 [done]（完成勾选，像待办）。详情 [detail] 复用笔记那套 Markdown 源码 + `img:` 图片引用。
 */
data class Homework(
    val id: Long = 0,
    val courseName: String,
    val title: String,
    val detail: String = "",
    /** 提交截止日期；null = 未设定。当天不算过期（文案「今天」，见 [dueLabel]）。 */
    val dueDate: LocalDate? = null,
    val done: Boolean = false,
    /** 勾选完成的时间；取消完成置回 null。 */
    val doneAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * 作业库的课程分组行（DAO 投影，DESIGN §3.11）。
 * [pending] = 未完成数（分组行副标题「N 项未完成」），[total] = 全部条数。
 */
data class HomeworkCourseGroup(
    val courseName: String,
    val total: Int,
    val pending: Int,
    /** 最近更新时间（分组行按它倒序）。 */
    val latestAt: Long,
)
