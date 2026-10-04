package edu.jxslu.schedule.data.repo

import android.content.Context
import edu.jxslu.schedule.domain.ScheduleCourseRow
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 课表基线快照（DESIGN §4.17，2026-10-04 重立）：「上次与教务达成一致时的课表状态」，
 * `filesDir` 下一份 JSON。
 *
 * 调课检测拿它与教务最新抓取做对比——**不是**拿当前课表比：用户手动改课、手动加课
 * 都只动 Room，基线保持不动，差异才纯粹是「教务那边变了什么」。基线在三个时点推进：
 * 导入写库成功（`importParsedCourses` / `importJson`）、合并差异成功（`applyScheduleChanges`）、
 * 检查首跑（老用户升级上来没有基线，用第一次抓到的教务状态建立，不通知）。
 *
 * **允许存空列表**（一门课没有的学期是教务的合法状态，空基线才能在下学期正确报出
 * 「教务新增了 N 门课」）——但**只由「可信的成功路径」写入**：写库成功、抓取解析成功。
 * 文件坏了按「没有基线」处理，下轮检查重建，不影响主链路。只存课表条目，不存任何凭证。
 */
class ScheduleBaselineStore(context: Context) {

    private val file = File(context.filesDir, SNAPSHOT_FILE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Snapshot(
        /** 基线归属的课表。调课检查只对「当前活跃课表」做，id 不匹配 = 首跑。 */
        val timetableId: Long = 0,
        /** 基线的学年学期（如 2026-2027-1）；与抓取学期不一致 = 换学期，按首跑处理。 */
        val term: String = "",
        val courses: List<ScheduleCourseRow> = emptyList(),
        val savedAt: Long = 0,
    )

    fun load(): Snapshot = runCatching {
        json.decodeFromString<Snapshot>(file.readText())
    }.getOrDefault(Snapshot())

    fun save(snapshot: Snapshot) {
        runCatching {
            file.writeText(json.encodeToString(snapshot))
        }
    }

    companion object {
        private const val SNAPSHOT_FILE = "schedule_baseline.json"

        fun snapshotOf(timetableId: Long, term: String, courses: List<edu.jxslu.schedule.domain.Course>, savedAt: Long) =
            Snapshot(
                timetableId = timetableId,
                term = term,
                courses = courses.map(ScheduleCourseRow::of),
                savedAt = savedAt,
            )
    }
}
