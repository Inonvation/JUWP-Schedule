package edu.jxslu.schedule.data.power

import edu.jxslu.schedule.data.local.PowerReadingDao
import edu.jxslu.schedule.data.local.PowerReadingEntity
import edu.jxslu.schedule.domain.PowerReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 电表读数本机记录（DESIGN §3.13「用电统计」，2026-09-24）。
 *
 * 写入只有一处：`PowerRepository.snapshot()` 真的打了一次平台、拿到新读数时。
 * 读取给账单页的「用电统计」用（响应式，R() 落库后页面自动重算）。
 *
 * 房间过滤口径：**只看最新那条读数的房间**（`PowerUsage.readingsOfLatestRoom`）——
 * 换寝室后旧房间的读数不再混进新房间的曲线，但也不删（历史留着无害）。
 */
class PowerReadingStore(private val dao: PowerReadingDao) {

    /** 全部读数（升序）。 */
    fun observeAll(): Flow<List<PowerReading>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** 一次性取全部读数（升序）。 */
    suspend fun all(): List<PowerReading> = dao.getAll().map { it.toDomain() }

    /**
     * 记一条读数。返回是否真的写进去了（`false` = 认不出电量/房间、时刻无效，
     * 或这一读数已经在库里——`(epochMs, roomId)` 唯一索引 + IGNORE）。
     *
     * [priceYuan] 取读数那一刻的项目单价：0 或负数当「未知」存，`PowerUsage`
     * 见 0 就不做度数换算（宁可这一段没有结论，也不按默认单价编）。
     */
    suspend fun record(
        meter: PowerMeter,
        priceYuan: Double?,
        source: String,
    ): Boolean {
        val remain = meter.remain ?: return false
        val roomId = (meter.room.roomId ?: meter.room.room).orEmpty().trim()
        if (roomId.isEmpty()) return false
        if (meter.fetchedAtMs <= 0L) return false
        val row = PowerReadingEntity(
            epochMs = meter.fetchedAtMs,
            remainKwh = remain,
            priceYuan = priceYuan?.takeIf { it > 0 } ?: 0.0,
            roomId = roomId,
            source = source,
        )
        return dao.insertIgnore(row) != -1L
    }

    private fun PowerReadingEntity.toDomain() = PowerReading(
        epochMs = epochMs,
        remainKwh = remainKwh,
        priceYuan = priceYuan,
        roomId = roomId,
    )
}

/** 读数来源（只用于排错时回答「这条读数哪来的」）。 */
object PowerReadingSource {
    /** 生活页进页 / 点卡片刷新 / 充值成功后刷新。 */
    const val LIFE = "life"

    /** 缴费账单页下拉刷新。 */
    const val BILL = "bill"

    /** 余额提醒的每日核对。 */
    const val ALERT = "alert"
}
