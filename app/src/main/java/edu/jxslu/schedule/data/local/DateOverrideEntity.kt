package edu.jxslu.schedule.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import edu.jxslu.schedule.domain.OverrideKind
import edu.jxslu.schedule.domain.ScheduleOverride
import kotlinx.coroutines.flow.Flow

/**
 * 日期覆盖（调休 / 停课，DESIGN §4.40，2026-10-06；领域模型见
 * [edu.jxslu.schedule.domain.ScheduleOverride]）。
 *
 * **列不带 Kotlin 默认值**（`note` 特意声明成无默认的非空列）：Room 会把构造默认值
 * 写进预期 schema 的 `DEFAULT`，带默认值的列在迁移建表时漏写 `DEFAULT` 就是校验崩溃
 * （v7→v8 remark、v11→v12 roomName、v13→v14 教材展示列三处老坑）。主键 `id = 0`
 * 不进预期 schema（同 v17→v18）。
 */
@Entity(
    tableName = "date_overrides",
    indices = [Index("timetableId")],
)
data class DateOverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timetableId: Long,
    /** yyyy-MM-dd */
    val fromDate: String,
    /** yyyy-MM-dd；单日时与 [fromDate] 相同。 */
    val toDate: String,
    /** [OverrideKind] 的名字。 */
    val kind: String,
    /** FOLLOW 的源周次；其余为 null。 */
    val sourceWeek: Int?,
    /** FOLLOW 的源星期（1=周一 … 7=周日）；其余为 null。 */
    val sourceWeekday: Int?,
    val note: String,
) {
    fun toDomain(): ScheduleOverride = ScheduleOverride(
        id = id,
        fromDate = fromDate,
        toDate = toDate,
        kind = runCatching { OverrideKind.valueOf(kind) }.getOrDefault(OverrideKind.SUSPENDED),
        sourceWeek = sourceWeek,
        sourceWeekday = sourceWeekday,
        note = note,
    )

    companion object {
        fun of(override: ScheduleOverride, timetableId: Long) = DateOverrideEntity(
            id = override.id,
            timetableId = timetableId,
            fromDate = override.fromDate,
            toDate = override.toDate,
            kind = override.kind.name,
            sourceWeek = override.sourceWeek,
            sourceWeekday = override.sourceWeekday,
            note = override.note,
        )
    }
}

@Dao
interface DateOverrideDao {

    /**
     * 某课表的覆盖，**按写入顺序**（id）返回。
     *
     * 顺序即胜负：`ScheduleOverrides.plan` 摊平时**后写的覆盖前面的**，同一槽位出现
     * 多条时最后写入的那条生效——这是「用户后来应用/更正的安排赢过旧的」的落地。
     * **不要**改成按 fromDate 排序（2026-10-07 修）：那样同一槽位的胜负会变成
     * 「日期晚的赢」，后应用一个更早/更宽的区间时会输给旧覆盖，语义就反了。
     */
    @Query("SELECT * FROM date_overrides WHERE timetableId = :timetableId ORDER BY id ASC")
    fun observeForTimetable(timetableId: Long): Flow<List<DateOverrideEntity>>

    /** 非 Flow 读（导出、提醒这类一次性快照用）；排序口径同 [observeForTimetable]。 */
    @Query("SELECT * FROM date_overrides WHERE timetableId = :timetableId ORDER BY id ASC")
    suspend fun getForTimetable(timetableId: Long): List<DateOverrideEntity>

    /** 写入并返回各行的自增 id（AI 调课的「撤销」要用它按 id 删回去）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<DateOverrideEntity>): List<Long>

    @Query("DELETE FROM date_overrides WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 整张课表的覆盖一次清空（删课表 / 清空课程时同步）。 */
    @Query("DELETE FROM date_overrides WHERE timetableId = :timetableId")
    suspend fun deleteForTimetable(timetableId: Long)
}
