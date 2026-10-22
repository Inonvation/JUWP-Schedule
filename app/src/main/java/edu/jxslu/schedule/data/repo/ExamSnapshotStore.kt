package edu.jxslu.schedule.data.repo

import android.content.Context
import edu.jxslu.schedule.domain.ExamMapper.ExamEntry
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 考试基线快照（DESIGN §4.33）：上次成功抓到的考试安排落盘（`filesDir` 下一份 JSON）。
 *
 * 考试不像成绩有 Room 表——它只在手动导入时经 [ExamMapper] 映射成课程行，App 内没有
 * 「教务眼里的考试全量」可比对。变动检测需要旧值，就把上次成功抓取的最小字段存一份。
 *
 * 读写都必须在 IO 线程（调用方负责调度）；两端都 runCatching——快照坏了等于没有快照，
 * 下次按「首跑」重建基线，不影响主链路。**只存考试条目，不存任何凭证**。
 */
class ExamSnapshotStore(context: Context) {

    private val file = File(context.filesDir, SNAPSHOT_FILE)
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable
    data class Snapshot(
        /** 快照对应的学年学期（如 2026-2027-1）；空 = 未记。 */
        val term: String = "",
        val exams: List<ExamEntry> = emptyList(),
    )

    fun load(): Snapshot = runCatching {
        json.decodeFromString<Snapshot>(file.readText())
    }.getOrDefault(Snapshot())

    /** 空条目不写：一次「取到但为空」不该把上次有数的那份基线洗掉，宁旧勿空。 */
    fun save(snapshot: Snapshot) {
        if (snapshot.exams.isEmpty()) return
        runCatching {
            file.writeText(json.encodeToString(snapshot))
        }
    }

    companion object {
        private const val SNAPSHOT_FILE = "exam_snapshot.json"
    }
}
