package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.data.jw.TextbookParser
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.session.CasEnsureResult
import edu.jxslu.schedule.data.session.CasSession
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex

/**
 * 教材抓取（DESIGN §4.31）。走 [CasSession]（OkHttp + 已存凭证），**不开 WebView**，
 * 触发点是「教务导入课表写库成功后」——用户刚在导入窗口登录过教务，会话大概率还热着；
 * 会话失效就静默放弃，等下一次导入再试（教材是快照数据，晚一点没关系）。
 *
 * 与 [ScholarProgressSync] 同一套纪律，但**没有 7 天闸门**：它只跟导入走（一学期一次），
 * 天然低频；也正因如此，只有导入路径会调它，force 参数不存在。
 *
 * 四条纪律：
 * 1. **没学期钥匙不抓**：term 为空或不合法直接跳过——抓回来也没地方挂（课表 term 没写进去）；
 * 2. **解析结果先校验再写库**：[TextbookParser] 抛异常/空结果时不动旧数据；
 * 3. **失败静默**（对调用方是返回值不是异常）：教材同步绝不挡住课表导入主流程；
 * 4. **成功才记日期**：与成绩/学业同口径，日期只做记录不做闸门。
 */
class TextbookSync(
    private val cas: CasSession,
    private val repo: ScheduleRepository,
    private val prefs: DisplayPrefsStore,
    private val today: () -> LocalDate = LocalDate::now,
) {

    /** 抓取互斥：撞上了直接让位，不排队重抓一遍。 */
    private val mutex = Mutex()

    sealed interface Result {
        /** [term] 是写入的学期，[books] 是落库条数。 */
        data class Updated(val term: String, val books: Int) : Result

        /** 没有可用的学期号（导入没带 term），本次不抓。 */
        data object Skipped : Result

        /** 已经有一次抓取在跑，这次让位。 */
        data object InProgress : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun syncForTerm(term: String?): Result {
        if (term.isNullOrBlank()) return Result.Skipped
        if (!mutex.tryLock()) return Result.InProgress
        try {
            return syncLocked(term)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun syncLocked(term: String): Result {
        val url = TextbookParser.listUrl(term, page = 1) ?: return Result.Skipped
        when (val ensured = cas.ensureValid()) {
            is CasEnsureResult.Ready -> Unit
            else -> return Result.Failed(ScholarProgressSync.describe(ensured))
        }

        // 单学期教材远小于一页上限，单次 GET 拿全；count 超了也只抓一页
        // （教材是确认清单不是流水，异常大的 count 更可能是接口行为变化，
        // 静默失败比拿着半页数据写库安全）
        val body = cas.fetchHtml(url)
            ?: return Result.Failed("没取到教材数据（会话可能已失效）")
        val parsed = try {
            TextbookParser.parseFetchJson(body, term)
        } catch (e: Exception) {
            return Result.Failed(e.message ?: "教材解析失败")
        }
        if (parsed.count > TextbookParser.PAGE_SIZE) {
            return Result.Failed("教材条数（${parsed.count}）超出一页上限，本次未写入")
        }

        repo.replaceTextbooks(term, parsed.books)
        prefs.setTextbookSyncDate(today().toString())
        return Result.Updated(term = term, books = parsed.books.size)
    }
}
