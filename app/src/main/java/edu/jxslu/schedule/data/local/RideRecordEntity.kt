package edu.jxslu.schedule.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 本机骑行记录（DESIGN §3.9「最近骑行」，2026-09-28；表 `ride_records`，DB v16）。
 *
 * 写入时机只有一处：本机还车成功（`KvcxRideController.returnBike` → `KvcxSideEffects.recordRide`）。
 * 只记本机用车这条链路，理由见 `domain/RideRecord.kt` 的 KDoc。
 *
 * [startAt] 由 `endAt - durationSeconds` 反推（快趣只给"已骑秒数"，不给开锁时刻），
 * 与列表上展示的时长自洽；**不要**拿它当精确的开锁时刻用。
 *
 * [settled] 存 Boolean 而不是可空：拿不到结算结果时按"未结清"记（提醒用户去小程序核对，
 * 宁可多提醒一次，也不把不确定写成"已结清"）。
 */
@Entity(
    tableName = "ride_records",
    indices = [Index(value = ["endAt"])],
)
data class RideRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 完整车号（`100000669` 形态）。 */
    val carNum: String,
    /** 开锁时刻（epoch 毫秒，由 `endAt - durationSeconds` 反推）。 */
    val startAt: Long,
    /** 还车时刻（epoch 毫秒）。 */
    val endAt: Long,
    /** 骑行时长（秒）。 */
    val durationSeconds: Long,
    /** 费用（分）；null = 快趣没给过当前费用。 */
    val feeCents: Long?,
    /** 结算状态（见上：拿不到结果按未结清算）。 */
    val settled: Boolean,
)

@Dao
interface RideRecordDao {

    /** 全部记录，按还车时刻倒序（最近的骑行在最上面）。 */
    @Query("SELECT * FROM ride_records ORDER BY endAt DESC, id DESC")
    fun observeAll(): Flow<List<RideRecordEntity>>

    /** 追加一条记录。 */
    @Insert
    suspend fun insert(record: RideRecordEntity): Long

    /** 只留最新的 [keep] 条（写完就收一次，表不会无限长）。 */
    @Query(
        "DELETE FROM ride_records WHERE id NOT IN " +
            "(SELECT id FROM ride_records ORDER BY endAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)

    /** 清空本机记录（用户在快趣页手动清）。 */
    @Query("DELETE FROM ride_records")
    suspend fun clear()
}
