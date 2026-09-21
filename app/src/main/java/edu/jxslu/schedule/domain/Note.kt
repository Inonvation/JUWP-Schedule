package edu.jxslu.schedule.domain

/**
 * 一条课程笔记·课件（DESIGN §4.20）。
 *
 * 归属键是 [courseName] **原样字符串**：课程行 id 在覆盖导入（`replaceAllCourses` 清表重建）、
 * 调课（删行重建）、撤销（原 id 回滚）里都不稳定，绑 id 必丢数据；多课表 = 多学期，
 * 换课表后旧笔记也应可查。代价（有意取舍）：跨课表同名课程共享内容，课程改名后旧笔记
 * 留在旧名下（仍可见可编辑，不自动重绑）。
 *
 * [body] 是 Markdown 源码，图片以 `![](img:文件名)` 内联引用（文件在应用私有目录，
 * 见 `data/repo/AttachmentStore`）；创建日期 [createdAt] 自动记录、编辑不改。
 */
data class Note(
    val id: Long = 0,
    val courseName: String,
    val title: String,
    val body: String,
    /** 创建时间（列表展示日期口径；编辑不刷新）。 */
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * 笔记库的课程分组行（DAO 投影，DESIGN §3.11「课程库」）。
 *
 * 分组在 SQL 里做（`GROUP BY courseName`），避免把全部正文读进内存再分组。
 */
data class NoteCourseGroup(
    val courseName: String,
    /** 该课程的笔记篇数。 */
    val count: Int,
    /** 最近更新时间（分组行按它倒序）。 */
    val latestAt: Long,
)
