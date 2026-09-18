package edu.jxslu.schedule.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * v3 起所有课表数据的查询都按 timetableId 过滤（DESIGN §4.9）：
 * 不加过滤的话，跨课表的配色分配、合并去重、覆盖导入会互相污染。
 */
@Dao
interface CourseDao {
    @Query("SELECT * FROM courses WHERE timetableId = :timetableId ORDER BY day, startSection, name")
    fun observeForTimetable(timetableId: Long): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses WHERE timetableId = :timetableId ORDER BY day, startSection, name")
    suspend fun getForTimetable(timetableId: Long): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getById(id: Long): CourseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(course: CourseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(courses: List<CourseEntity>): List<Long>

    @Delete
    suspend fun delete(course: CourseEntity)

    @Query("DELETE FROM courses WHERE timetableId = :timetableId")
    suspend fun clearForTimetable(timetableId: Long)

    @Query("SELECT COUNT(*) FROM courses WHERE timetableId = :timetableId")
    suspend fun countForTimetable(timetableId: Long): Int
}

@Dao
interface TimetableDao {
    @Query("SELECT * FROM timetables ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<TimetableEntity>>

    @Query("SELECT * FROM timetables ORDER BY sortOrder, id")
    suspend fun getAll(): List<TimetableEntity>

    @Query("SELECT * FROM timetables WHERE id = :id")
    suspend fun getById(id: Long): TimetableEntity?

    @Query("SELECT COUNT(*) FROM timetables")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(timetable: TimetableEntity): Long

    @Query("DELETE FROM timetables WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TimeSlotDao {
    @Query("SELECT * FROM time_slots WHERE timetableId = :timetableId ORDER BY number")
    fun observeForTimetable(timetableId: Long): Flow<List<TimeSlotEntity>>

    @Query("SELECT * FROM time_slots WHERE timetableId = :timetableId ORDER BY number")
    suspend fun getForTimetable(timetableId: Long): List<TimeSlotEntity>

    /** 全部课表的作息表：结构性迁移要按课表逐张覆盖。 */
    @Query("SELECT * FROM time_slots ORDER BY timetableId, number")
    suspend fun getAll(): List<TimeSlotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(slots: List<TimeSlotEntity>)

    @Query("DELETE FROM time_slots WHERE timetableId = :timetableId")
    suspend fun clearForTimetable(timetableId: Long)
}

@Dao
interface SemesterConfigDao {
    @Query("SELECT * FROM semester_config WHERE timetableId = :timetableId LIMIT 1")
    fun observeForTimetable(timetableId: Long): Flow<SemesterConfigEntity?>

    @Query("SELECT * FROM semester_config WHERE timetableId = :timetableId LIMIT 1")
    suspend fun getForTimetable(timetableId: Long): SemesterConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: SemesterConfigEntity)

    @Query("DELETE FROM semester_config WHERE timetableId = :timetableId")
    suspend fun deleteForTimetable(timetableId: Long)
}
