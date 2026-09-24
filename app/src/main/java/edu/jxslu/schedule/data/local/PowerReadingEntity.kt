package edu.jxslu.schedule.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 电表读数本机记录（DESIGN §3.13「用电统计」，2026-09-24）。
 *
 * 缴费平台只给**当前**剩余电量（`map.showData` 的「当前剩余电量」），没有任何
 * 用电量/用电账单接口（2026-09-24 逐个试过 H5 bundle 里的统计类接口：
 * `/turnover/mouthAccount` 是一卡通月消费、`/turnover/app_totalAccount` 是累计缴费、
 * `/turnover/appAccountDetail` 直接 500，都不是用电量）。所以用电量只能靠**本机把每次
 * 读到的度数记下来**，相邻两条之间差分：
 * `用电 = 上次度数 + 期间充值度数 − 这次度数`（算法见 `domain/PowerUsage.kt`）。
 *
 * 去重键是 `(epochMs, roomId)` 唯一索引：`epochMs` 用读数自身的
 * `PowerMeter.fetchedAtMs`（真·读数时刻）而不是落库时刻，于是仓库内存缓存
 * （TTL 2 分钟）把同一份快照重复交出来时，`OnConflictStrategy.IGNORE` 自然吞掉重复行。
 *
 * 按房间存 `roomId`：换寝室后旧房间的读数不参与新房间的统计（统计只看最新那条读数的房间）。
 *
 * 落库位置在 `PowerReadingStore`，写入时机只有一处——`PowerRepository.snapshot()` 真的
 * 打了一次平台并拿到新读数时（见其 KDoc）。**不做后台轮询**：这是第三方平台，
 * 记录密度就等于用户打开 App 的密度。
 */
@Entity(
    tableName = "power_readings",
    indices = [
        Index(value = ["epochMs", "roomId"], unique = true),
        Index(value = ["roomId"]),
    ],
)
data class PowerReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 读数时刻（epoch 毫秒，取 `PowerMeter.fetchedAtMs`）。 */
    val epochMs: Long,
    /** 剩余电量（度）。 */
    val remainKwh: Double,
    /** 此刻的单价（元/度）；项目详情没给单价时为 0（0 = 未知，不做折算也不做度数换算）。 */
    val priceYuan: Double,
    /** 房间标识（`map.data.roomid`，缺了退房间名）。 */
    val roomId: String,
    /** 触发来源，排错用：`life` 生活页 / `bill` 账单页刷新 / `alert` 余额提醒 / 其它。 */
    val source: String,
)

@Dao
interface PowerReadingDao {

    /** 全部读数，按时间升序（房间过滤在 `PowerReadingStore` 里按最新一条的房间做）。 */
    @Query("SELECT * FROM power_readings ORDER BY epochMs, id")
    fun observeAll(): Flow<List<PowerReadingEntity>>

    @Query("SELECT * FROM power_readings ORDER BY epochMs, id")
    suspend fun getAll(): List<PowerReadingEntity>

    /** 追加一条读数；`(epochMs, roomId)` 撞上已有行时静默跳过（重复快照不重复记）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(reading: PowerReadingEntity): Long

    /** 条数（响应式，用于判断「有没有攒够两条读数」）。 */
    @Query("SELECT COUNT(*) FROM power_readings")
    fun observeCount(): Flow<Int>
}
