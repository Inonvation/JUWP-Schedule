package edu.jxslu.schedule.data.repo

import android.util.Log
import edu.jxslu.schedule.data.DefaultData
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.jw.TeachingCalendarParser
import edu.jxslu.schedule.data.session.CasEnsureResult
import edu.jxslu.schedule.data.session.CasSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/**
 * 开学日校准（DESIGN §4.42）：教务导入课表写库成功后，按导入学期抓「教学日历查询」，
 * 把**第 1 周周一**写进目标课表的开学日（`SemesterConfig.startDate`）。
 *
 * 口径（2026-10-07 用户拍板）：「以此为准」——每次导入都按教务重设；用户之后仍可在
 * 课表设置里手改（下一次导入会再按教务校准）。
 *
 * 与 [TextbookSync] 同一套纪律，四条：
 * 1. **没学期钥匙不抓**：term 为空直接跳过（不知道抓哪份日历）；
 * 2. **学期必须自证**：解析器要求响应学期 == 请求学期，对不上不算数（不猜学期）；
 * 3. **失败静默**：返回值不抛异常，绝不挡住导入主流程；只记 [Log.i]——
 *    开学日原本就有值，失败 = 维持现状，不是数据丢失，故不补失败气泡；
 * 4. **值没变不写库**：已和教务一致时不产生写操作，调用方也不弹气泡。
 */
class TeachingCalendarSync(
    private val cas: CasSession,
    private val repo: ScheduleRepository,
) {

    /** 抓取互斥：撞上了直接让位（与教材同款）。 */
    private val mutex = Mutex()

    sealed interface Result {
        /** [startDate] 是校准后的开学日（yyyy-MM-dd）；[changed] = 这次是否真的改了库。 */
        data class Applied(val startDate: String, val changed: Boolean) : Result

        /** 没有可用的学期号（导入没带 term），本次不校准。 */
        data object Skipped : Result

        /** 已经有一次在跑，这次让位。 */
        data object InProgress : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * 导入课表成功后调用。[timetableId] 为 null 时按导入落库的同一口径取当前课表
     * （`importParsedCourses` 的缺省目标）；导入调用点知道目标就把 targetId 传进来。
     */
    suspend fun applyAfterImport(term: String?, timetableId: Long? = null): Result {
        if (term.isNullOrBlank()) return Result.Skipped
        if (!mutex.tryLock()) return Result.InProgress
        try {
            val result = applyLocked(term, timetableId)
            Log.i(TAG, "calendar apply $term -> $result")
            return result
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun applyLocked(term: String, timetableId: Long?): Result {
        val url = JwUrls.teachingCalendarUrl(term) ?: return Result.Skipped
        when (val ensured = cas.ensureValid()) {
            is CasEnsureResult.Ready -> Unit
            else -> return Result.Failed(ScholarProgressSync.describe(ensured))
        }
        val html = cas.fetchHtml(url)
            ?: return Result.Failed("没取到教学日历（会话可能已失效）")
        val calendar = TeachingCalendarParser.parse(html, term)
            ?: return Result.Failed("教学日历解析失败或学期不符")

        val ttId = timetableId ?: repo.currentTimetableId.first()
        val old = repo.semesterFor(ttId)
        val newStart = calendar.startDate.toString()
        if (old != null && old.startDate == newStart) {
            return Result.Applied(startDate = newStart, changed = false)
        }
        // 还没配过学期的课表：以默认配置为底（总周数/首日不动），只改开学日
        val desired = (old ?: DefaultData.defaultSemester).copy(startDate = newStart)
        repo.updateSemesterFor(ttId, desired)
        return Result.Applied(startDate = newStart, changed = true)
    }

    private companion object {
        const val TAG = "TeachingCalendarSync"
    }
}
