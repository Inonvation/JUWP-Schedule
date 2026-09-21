package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.data.local.JuwDatabase
import edu.jxslu.schedule.data.local.NoteEntity
import edu.jxslu.schedule.domain.Note
import edu.jxslu.schedule.domain.NoteCourseGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 课程笔记·课件仓库（DESIGN §4.20）：薄封装，排序/摘要/文案一律归 domain。
 *
 * 归属键是**课程名原样字符串**，没有 timetableId 过滤（理由与取舍见 DESIGN §4.20「归属」）。
 */
class NoteRepository(private val db: JuwDatabase) {

    private val dao = db.noteDao()

    /** 某课程的笔记（最近更新在前）。 */
    fun observeForCourse(courseName: String): Flow<List<Note>> =
        dao.observeForCourse(courseName).map { list -> list.map(NoteEntity::toDomain) }

    fun observeAll(): Flow<List<Note>> =
        dao.observeAll().map { list -> list.map(NoteEntity::toDomain) }

    /** 笔记库的课程分组行（篇数 + 最近更新）。 */
    fun observeGroups(): Flow<List<NoteCourseGroup>> = dao.observeGroups()

    suspend fun note(id: Long): Note? = dao.getById(id)?.toDomain()

    /**
     * 新建 / 更新。时间戳口径：**新记录**（id=0）createdAt 盖当前时间；
     * **更新**保留首建时间（编辑不改创建日期），updatedAt 一律盖当前时间。
     */
    suspend fun save(note: Note): Long {
        val now = System.currentTimeMillis()
        val stamped = note.copy(
            createdAt = if (note.id > 0 && note.createdAt > 0) note.createdAt else now,
            updatedAt = now,
        )
        return dao.upsert(NoteEntity.fromDomain(stamped))
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    /** 附件清扫的引用来源（`AttachmentStore.sweep`）。 */
    suspend fun allBodies(): List<String> = dao.allBodies()
}
