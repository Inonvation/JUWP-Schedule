package edu.jxslu.schedule.data.repo

import androidx.room.withTransaction
import edu.jxslu.schedule.data.local.JuwDatabase
import edu.jxslu.schedule.data.local.ScholarCourseEntity
import edu.jxslu.schedule.data.local.ScholarGroupEntity
import edu.jxslu.schedule.domain.ScholarCourse
import edu.jxslu.schedule.domain.ScholarGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 学业完成情况仓库（DESIGN §4.29）。全局归属学生，与课表、成绩都无关。
 *
 * 写入口径 = **整体替换**：一次导入把四个维度一起换掉。理由见 [ScholarProgressEntity]
 * 的 KDoc——四个维度出自同一次教务快照，分开更新会留下互相矛盾的中间态。
 */
class ScholarProgressRepository(private val db: JuwDatabase) {

    private val dao = db.scholarProgressDao()

    val groups: Flow<List<ScholarGroup>> =
        dao.observeGroups().map { list -> list.map { it.toDomain() } }

    val courses: Flow<List<ScholarCourse>> =
        dao.observeCourses().map { list -> list.map { it.toDomain() } }

    /** 有没有数据。给导入闸门与 UI 空态用，只数一张表的行。 */
    suspend fun hasData(): Boolean = dao.countGroups() > 0

    /** 整批替换。空列表会被调用方拦下（见 `ScholarProgressSync` 的完整性校验），这里不再兜。 */
    suspend fun replaceAll(groups: List<ScholarGroup>, courses: List<ScholarCourse>) {
        db.withTransaction {
            dao.deleteGroups()
            dao.deleteCourses()
            dao.insertGroups(groups.map { ScholarGroupEntity.fromDomain(it) })
            dao.insertCourses(courses.map { ScholarCourseEntity.fromDomain(it) })
        }
    }

    suspend fun deleteAll() {
        db.withTransaction {
            dao.deleteGroups()
            dao.deleteCourses()
        }
    }
}
