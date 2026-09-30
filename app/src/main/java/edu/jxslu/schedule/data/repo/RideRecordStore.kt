package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.data.local.RideRecordDao
import edu.jxslu.schedule.data.local.RideRecordEntity
import edu.jxslu.schedule.domain.RideRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 本机骑行记录（DESIGN §3.9「最近骑行」）：追加一条 + 只读列表 + 清空。
 *
 * 只有本机用车（快趣）这条链路写入（理由见 `domain/RideRecord.kt`）；页面只读。
 * 每次写入顺手 `trim` 到 [RideRecord.LIMIT]，表不会无限长。
 */
class RideRecordStore(private val dao: RideRecordDao) {

    /** 全部记录，最近的在前（页面自己截断到 [RideRecord.SHOW_LIMIT] 条展示）。 */
    fun observeAll(): Flow<List<RideRecord>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * 记一次骑行。[startAt] 由 `endAt - durationSeconds` 反推（快趣不给开锁时刻），
     * 与展示的时长自洽。
     */
    suspend fun add(
        carNum: String,
        endAt: Long,
        durationSeconds: Long,
        feeCents: Long?,
        settled: Boolean,
    ) {
        val duration = durationSeconds.coerceAtLeast(0)
        withContext(Dispatchers.IO) {
            dao.insert(
                RideRecordEntity(
                    carNum = carNum,
                    startAt = endAt - duration * 1000L,
                    endAt = endAt,
                    durationSeconds = duration,
                    feeCents = feeCents,
                    settled = settled,
                ),
            )
            dao.trim(RideRecord.LIMIT)
        }
    }

    /** 清空本机记录（用户在快趣页手动清）。 */
    suspend fun clear() {
        withContext(Dispatchers.IO) { dao.clear() }
    }

    /** 还有多少条「未结清」（结清复查的前置闸）。 */
    suspend fun countUnsettled(): Int = withContext(Dispatchers.IO) { dao.countUnsettled() }

    /**
     * 快趣侧确认已无欠费时，把全部「未结清」翻成「已结清」，返回更新的行数。
     * 语义见 `KvcxViewModel.recheckUnsettledRecords`：本机的「未结清」只是**当时没确认到**，
     * 不是欠费事实；快趣已无欠费即事实结清。
     */
    suspend fun markUnsettledSettled(): Int =
        withContext(Dispatchers.IO) { dao.markUnsettledSettled() }
}

private fun RideRecordEntity.toDomain(): RideRecord = RideRecord(
    id = id,
    carNum = carNum,
    startAt = startAt,
    endAt = endAt,
    durationSeconds = durationSeconds,
    feeCents = feeCents,
    settled = settled,
)
