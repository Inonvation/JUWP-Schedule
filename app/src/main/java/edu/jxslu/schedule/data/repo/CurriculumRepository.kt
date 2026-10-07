package edu.jxslu.schedule.data.repo

import androidx.room.withTransaction
import edu.jxslu.schedule.data.local.CurriculumCourseEntity
import edu.jxslu.schedule.data.local.JuwDatabase
import edu.jxslu.schedule.domain.CurriculumCourse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 培养方案课程设置仓库（DESIGN §4.41）。全局归属学生，与课表、成绩都无关。
 *
 * 写入口径 = **整体替换**：一次导入把全量课程一起换掉。理由见 [CurriculumCourseEntity]
 * 的 KDoc——页面一次给全量，分开更新会留下互相矛盾的中间态。
 */
class CurriculumRepository(private val db: JuwDatabase) {

    private val dao = db.curriculumDao()

    val courses: Flow<List<CurriculumCourse>> =
        dao.observeCourses().map { list -> list.map { it.toDomain() } }

    /** 有没有数据。给导入闸门与 UI 空态用，只数行不做全表读。 */
    suspend fun hasData(): Boolean = dao.count() > 0

    /** 整批替换。空列表会被调用方拦下（见 `CurriculumSync` 的完整性校验），这里不再兜。 */
    suspend fun replaceAll(courses: List<CurriculumCourse>) {
        db.withTransaction {
            dao.deleteAll()
            dao.insertAll(courses.map { CurriculumCourseEntity.fromDomain(it) })
        }
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }
}
