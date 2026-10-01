package edu.jxslu.schedule.data.repo

import androidx.room.withTransaction
import edu.jxslu.schedule.data.local.CourseSelectionEntity
import edu.jxslu.schedule.data.local.JuwDatabase
import edu.jxslu.schedule.domain.CourseSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 选课记录仓库（DESIGN §4.35）：按学期存储、按学期整体替换。
 *
 * 与 [ScoreRepository] 同构：选课记录不属于任何课表（timetableId），全局归属学生。
 * 写路径只有「先删该学期再插入」，重复同步不产生重复行；未涉及的学期不动。
 */
class SelectionRepository(private val db: JuwDatabase) {

    private val dao = db.courseSelectionDao()

    /** 有数据的学期（倒序，最新在前）。 */
    fun observeTerms(): Flow<List<String>> = dao.observeTerms()

    fun observeForTerm(term: String): Flow<List<CourseSelection>> =
        dao.observeForTerm(term).map { list -> list.map { it.toDomain() } }

    /** 全部记录（选课页一次取全，学期分组在内存里做）。 */
    fun observeAll(): Flow<List<CourseSelection>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    /** 「有没有数据」的廉价判定。 */
    suspend fun hasData(): Boolean = dao.count() > 0

    /** 某学期是否已有本地数据（决定要不要自动抓）。 */
    suspend fun hasTermData(term: String): Boolean = dao.countForTerm(term) > 0

    /** 按学期替换：该学期先删后插，importedAt 统一取本次同步时间。 */
    suspend fun replaceTerm(term: String, rows: List<CourseSelection>) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            dao.deleteForTerm(term)
            if (rows.isNotEmpty()) {
                dao.insertAll(rows.map { CourseSelectionEntity.fromDomain(it.copy(term = term), now) })
            }
        }
    }

    suspend fun deleteAll() = dao.deleteAll()
}
