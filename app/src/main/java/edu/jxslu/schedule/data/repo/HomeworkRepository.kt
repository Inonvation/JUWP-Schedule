package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.data.local.HomeworkEntity
import edu.jxslu.schedule.data.local.JuwDatabase
import edu.jxslu.schedule.domain.Homework
import edu.jxslu.schedule.domain.HomeworkCourseGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 作业仓库（DESIGN §4.20）：薄封装，排序/汇总/截止文案一律归 `domain/HomeworkCenter`。
 *
 * 归属键是**课程名原样字符串**，没有 timetableId 过滤（理由与取舍见 DESIGN §4.20「归属」）。
 */
class HomeworkRepository(private val db: JuwDatabase) {

    private val dao = db.homeworkDao()

    /** 某课程的全部作业（含已完成；排序由 UI 走 domain 的 `courseHomeworkOrder`）。 */
    fun observeForCourse(courseName: String): Flow<List<Homework>> =
        dao.observeForCourse(courseName).map { list -> list.map(HomeworkEntity::toDomain) }

    /** 未完成作业（今日页作业卡 / 作业中心 / 截止提醒共用）。 */
    fun observePending(): Flow<List<Homework>> =
        dao.observePending().map { list -> list.map(HomeworkEntity::toDomain) }

    fun observeGroups(): Flow<List<HomeworkCourseGroup>> = dao.observeGroups()

    suspend fun homework(id: Long): Homework? = dao.getById(id)?.toDomain()

    /** 新建 / 更新；时间戳口径与 [NoteRepository.save] 一致（创建时间编辑不改）。 */
    suspend fun save(homework: Homework): Long {
        val now = System.currentTimeMillis()
        val stamped = homework.copy(
            createdAt = if (homework.id > 0 && homework.createdAt > 0) homework.createdAt else now,
            updatedAt = now,
        )
        return dao.upsert(HomeworkEntity.fromDomain(stamped))
    }

    /**
     * 勾选 / 取消完成（像待办）。只动 done/doneAt/updatedAt 三列（DAO 走定向 UPDATE），
     * 不整行回写——编辑页正开着旧值时不至于把别处的修改覆盖掉。
     */
    suspend fun setDone(id: Long, done: Boolean) {
        val now = System.currentTimeMillis()
        dao.setDone(id, done, doneAt = if (done) now else null, updatedAt = now)
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    /** 附件清扫的引用来源（`AttachmentStore.sweep`）。 */
    suspend fun allDetails(): List<String> = dao.allDetails()
}
