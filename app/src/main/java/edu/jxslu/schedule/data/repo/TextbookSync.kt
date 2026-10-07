package edu.jxslu.schedule.data.repo

import android.util.Log
import edu.jxslu.schedule.data.jw.TextbookParser
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.session.AutoSyncRules
import edu.jxslu.schedule.data.session.CasEnsureResult
import edu.jxslu.schedule.data.session.CasSession
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/**
 * 教材抓取（DESIGN §4.31）。走 [CasSession]（OkHttp + 已存凭证），**不开 WebView**，
 * 触发点是「教务导入课表写库成功后」——用户刚在导入窗口登录过教务，会话大概率还热着；
 * 会话失效就静默放弃（教材是快照数据，晚一点没关系），**冷启动补抓**兜住这一次失败
 * （[backfillIfMissing]，2026-10-06）。
 *
 * 与 [ScholarProgressSync] 同一套纪律，但**没有 7 天闸门**：它只跟导入走（一学期一次），
 * 天然低频；也正因如此，force 参数不存在。
 *
 * 四条纪律：
 * 1. **没学期钥匙不抓**：term 为空或不合法直接跳过——抓回来也没地方挂（课表 term 没写进去）；
 * 2. **解析结果先校验再写库**：[TextbookParser] 抛异常/空结果时不动旧数据；
 * 3. **失败静默**（对调用方是返回值不是异常）：教材同步绝不挡住课表导入主流程；
 * 4. **成功才记日期**：与成绩/学业同口径，`textbook_sync_date` 只做记录不做闸门
 *    （补抓那道闸门记的是**尝试**时刻，见 [backfillIfMissing]）。
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
            val result = syncLocked(term)
            // 教材链路的 Result 只有一个出口，而调用点把返回值丢掉了（页面上什么都不显示），
            // 于是「导入成功但详情里没教材」在真机上无从查起（2026-10-06 用户报障）。
            // 这行日志是 release 包唯一能自证的地方；只记学期、条数与失败原因，不含任何凭证。
            Log.i(TAG, "textbook sync $term -> $result")
            return result
        } finally {
            mutex.unlock()
        }
    }

    /**
     * 冷启动补抓（2026-10-06）：当前课表**有学期号、但该学期一条教材都没有**时补抓一次。
     *
     * 为什么需要它：教材原本只跟着「导入课表」抓那一次，而那一次会因为会话被别处登录顶掉
     * （强智单会话）静默失败——导入明明成功、详情里就是没有教材，用户只能靠重新导入碰运气。
     * 补抓把「一次性失败」变成可恢复：只要那一学期还是空的，下次冷启动就会再试。
     *
     * 闸门在 [AutoSyncRules.textbookBackfillDue]，三道判据：
     * 1. 该学期教材非空 → 跳过（成功过就不再打扰教务）；
     * 2. **该学期抓成功过**（含「成功但 0 条」，`textbook_fetched_term`）→ 跳过。
     *    少了这一道，下学期没征订 / 实习毕设本来没教材时会每 6 小时打一次教务、永不停止；
     * 3. 距上次**尝试**不足 6 小时 → 跳过。失败会一直保持「空」，没有这道闸门就成了
     *    每次冷启动都打一次教务。**这里记的是尝试时刻，不是成功时刻**
     *    （与 `textbook_sync_date` 相反）：它挡的是「反复打」，而「还该不该重试」由前两条判定。
     */
    suspend fun backfillIfMissing(): Result {
        val term = repo.currentTerm() ?: return Result.Skipped
        val now = System.currentTimeMillis()
        val due = AutoSyncRules.textbookBackfillDue(
            hasTextbooks = repo.hasTextbooksFor(term),
            fetchedTerm = prefs.textbookFetchedTerm(),
            currentTerm = term,
            lastAttemptMillis = prefs.textbookBackfillAttempt(),
            nowMillis = now,
        )
        if (!due) return Result.Skipped
        prefs.setTextbookBackfillAttempt(now)
        return syncForTerm(term)
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
        } catch (e: CancellationException) {
            throw e // 协程被取消，不能吞成解析失败
        } catch (e: Exception) {
            return Result.Failed(e.message ?: "教材解析失败")
        }
        if (parsed.count > TextbookParser.PAGE_SIZE) {
            return Result.Failed("教材条数（${parsed.count}）超出一页上限，本次未写入")
        }

        // **空结果不写库**：`replaceTextbooks` 是「删后插」，空列表等于把该学期旧教材清掉。
        // 征订窗口关闭 / 选课变动 / 教务抽风时接口就会回空，那不该毁掉已经拿到的数据。
        if (parsed.books.isNotEmpty()) {
            repo.replaceTextbooks(term, parsed.books)
        }
        // 但「抓过这个学期」要记下来（哪怕 0 条）：否则补抓闸门永远满足
        prefs.setTextbookFetchedTerm(term)
        prefs.setTextbookSyncDate(today().toString())
        return Result.Updated(term = term, books = parsed.books.size)
    }

    private companion object {
        const val TAG = "TextbookSync"
    }
}
