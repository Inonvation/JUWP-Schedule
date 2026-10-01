package edu.jxslu.schedule.data.repo

import android.content.Context
import edu.jxslu.schedule.domain.SelectionRound
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 选课轮次快照（DESIGN §4.35）：学生选课中心列表落盘（`filesDir` 一份 JSON）。
 *
 * 轮次不像选课结果有 Room 表——它的生命周期以「选课季」为单位（一年出几次、开学前后
 * 各一轮），App 只需要"当前最新一份"；提醒调度也从这份快照取数据（不额外打教务）。
 *
 * 读写都必须在 IO 线程（调用方负责调度）；两端都 runCatching——快照坏了等于没有快照，
 * 下次同步重建。**只存轮次条目，不存任何凭证**。
 *
 * 与考试基线（`ExamSnapshotStore` 的「宁旧勿空」）不同：这里**允许存空**——
 * `count=0`（非选课期）是教务的权威状态，存空才能让页面如实显示、让过期提醒被撤销。
 */
class SelectionRoundsStore(context: Context) {

    private val file = File(context.filesDir, SNAPSHOT_FILE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Snapshot(
        /** 抓取成功时刻（epoch millis）；0 = 未知。 */
        val fetchedAt: Long = 0L,
        val rounds: List<Row> = emptyList(),
    )

    /** [SelectionRound] 的可序列化影子（本体不是 @Serializable，字段一一对应）。 */
    @Serializable
    data class Row(
        val id: String = "",
        val term: String = "",
        val name: String = "",
        val timeText: String = "",
        val startAt: Long? = null,
        val endAt: Long? = null,
        val canPreview: Boolean = false,
    )

    fun load(): Snapshot = runCatching {
        json.decodeFromString<Snapshot>(file.readText())
    }.getOrDefault(Snapshot())

    fun save(snapshot: Snapshot) {
        runCatching { file.writeText(json.encodeToString(snapshot)) }
    }

    companion object {
        private const val SNAPSHOT_FILE = "selection_rounds.json"

        fun rowOf(round: SelectionRound) = Row(
            id = round.id,
            term = round.term,
            name = round.name,
            timeText = round.timeText,
            startAt = round.startAt,
            endAt = round.endAt,
            canPreview = round.canPreview,
        )

        fun rowToRound(row: Row) = SelectionRound(
            id = row.id,
            term = row.term,
            name = row.name,
            timeText = row.timeText,
            startAt = row.startAt,
            endAt = row.endAt,
            canPreview = row.canPreview,
        )
    }
}
