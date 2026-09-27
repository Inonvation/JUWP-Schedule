package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.data.jw.ScoreParser
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.session.AutoSyncRules
import edu.jxslu.schedule.data.session.CasEnsureResult
import edu.jxslu.schedule.data.session.CasSession
import edu.jxslu.schedule.domain.ScoreRecord
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/**
 * 成绩自动导入（DESIGN §4.29）。抓取的接口、解析、写库口径与成绩页的「导入」**完全一致**
 * （同一份 [ScoreParser]、同一个 `ScoreRepository.replaceTerm`），区别只在触发时机与传输层：
 *
 * - 成绩页那个走 WebView 注入 fetch（用户在页面上手登过，会话在 WebView 里）；
 * - 这里走 [CasSession.fetchHtml]（已存凭证 + OkHttp），不需要开窗口。
 *
 * 同一份数据两条路是**有意为之**：首启配置完就自动导入（用户要的「打开就有数据」），
 * 而会话失效、需要人工输验证码时，WebView 那条仍然是唯一能走通的后路。
 *
 * 与 [ScholarProgressSync] 同一套闸门纪律：首次立即抓、之后 7 天一次、成功才记日期、
 * 失败静默。写库是**按学期替换**，未涉及的学期不动（同成绩导入的既有口径）。
 */
class ScoreSync(
    private val cas: CasSession,
    private val repo: ScoreRepository,
    private val prefs: DisplayPrefsStore,
    private val today: () -> LocalDate = LocalDate::now,
) {

    /** 抓取互斥（口径同 `ScholarProgressSync`）：撞上了直接让位，不排队重抓一遍。 */
    private val mutex = Mutex()

    sealed interface Result {
        /** [terms] 是写入的学期数，[records] 是条数。 */
        data class Updated(val terms: Int, val records: Int) : Result

        data object Skipped : Result

        /** 已经有一次抓取在跑，这次让位。 */
        data object InProgress : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun sync(force: Boolean = false): Result {
        if (!mutex.tryLock()) return Result.InProgress
        try {
            return syncLocked(force)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun syncLocked(force: Boolean): Result {
        if (!force) {
            val hasData = repo.hasData()
            val last = prefs.scoreSyncDate.first()
            if (!AutoSyncRules.shouldAttempt(hasData, last, today())) return Result.Skipped
        }

        when (val ensured = cas.ensureValid()) {
            is CasEnsureResult.Ready -> Unit
            else -> return Result.Failed(ScholarProgressSync.describe(ensured))
        }

        val records = mutableListOf<ScoreRecord>()
        var count = 0
        var page = 1
        do {
            // 学期传空 = 全部学期，与成绩页导入同一个口径
            val body = cas.fetchHtml(ScoreParser.listUrl(term = "", page = page))
                ?: return Result.Failed("没取到成绩数据（会话可能已失效）")
            val parsed = try {
                ScoreParser.parseFetchJson(body)
            } catch (e: Exception) {
                return Result.Failed(e.message ?: "成绩解析失败")
            }
            count = parsed.count
            records += parsed.records
            page++
        } while (records.size < count && page <= MAX_PAGES)

        if (records.isEmpty()) return Result.Failed("教务没有返回任何成绩")

        val grouped = records.groupBy { it.term }
        grouped.forEach { (term, list) -> repo.replaceTerm(term, list) }
        prefs.setScoreSyncDate(today().toString())
        return Result.Updated(terms = grouped.size, records = records.size)
    }

    companion object {
        /** 翻页上限，与成绩页导入一致：挡住 count 异常时不至于空转。 */
        const val MAX_PAGES = 20
    }
}
