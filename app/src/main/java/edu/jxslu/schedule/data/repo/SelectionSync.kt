package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.data.jw.SelectionParser
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.session.CasEnsureResult
import edu.jxslu.schedule.data.session.CasSession
import edu.jxslu.schedule.domain.CourseSelection
import edu.jxslu.schedule.domain.SelectionRound
import edu.jxslu.schedule.domain.SelectionRounds
import kotlinx.coroutines.sync.Mutex

/**
 * 选课同步（DESIGN §4.35）：走 [CasSession]（已存凭证 + OkHttp），不开窗口。
 *
 * 两条链：
 * 1. **选课结果**：先 GET 壳页读学期下拉的选中项（缺省学期以教务为准，不猜），
 *    再 GET 数据接口翻页抓全；**先全部抓到内存、再一次性写库**——多学期同步中途失败
 *    宁可一行不写，也不留「本学期替换了、下学期还是旧的」的半套。
 * 2. **选课轮次**：GET `/jsxsd/xsxk/xklc_list_data`，落 [SelectionRoundsStore] 快照。
 *    非选课期 `count=0` 是正常空态（不是失败），照样落盘——空快照才会让页面如实显示、
 *    让过期提醒被撤销。
 *
 * 闸门（同成绩/考试纪律）：`MIN_REFRESH_MS` 内成功过就 Skipped（除非 force），
 * **成功才落时刻**（失败不写，下个周期自动补查）；不加重试（防撞风控）。
 *
 * 轮次更新后**提醒的闹钟重排由调用方负责**（页面/周期核对各自调
 * `SelectionAlertReminder.scheduleNext`）：本类不持 Context，不直接碰 AlarmManager。
 */
class SelectionSync(
    private val cas: CasSession,
    private val repo: SelectionRepository,
    private val prefs: DisplayPrefsStore,
    private val roundsStore: SelectionRoundsStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** 网络互斥（口径同 `ScoreSync`）：撞上了直接让位，不排队重抓一遍。 */
    private val mutex = Mutex()

    sealed interface Result {
        /** [terms] 是实际写库的学期，[rowCount] 是总行数（可能为 0 = 都抓到了、确实没记录）。 */
        data class Updated(val terms: List<String>, val rowCount: Int) : Result

        /** 闸门挡住（`MIN_REFRESH_MS` 内成功过且非 force）。 */
        data object Skipped : Result

        data object InProgress : Result

        data class Failed(val reason: String) : Result
    }

    sealed interface RoundsResult {
        data class Updated(val rounds: List<SelectionRound>, val fetchedAt: Long) : RoundsResult

        data object Skipped : RoundsResult

        data object InProgress : RoundsResult

        data class Failed(val reason: String) : RoundsResult
    }

    /** 一次「刷新选课页」的组合结果（结果 + 轮次）。 */
    data class RefreshOutcome(
        val results: Result,
        val rounds: RoundsResult,
    ) {
        val anyUpdated: Boolean
            get() = results is Result.Updated || rounds is RoundsResult.Updated

        /** 首个失败原因（两个都没失败时为 null）。 */
        val failedReason: String?
            get() = (results as? Result.Failed)?.reason
                ?: (rounds as? RoundsResult.Failed)?.reason
    }

    /**
     * 选课页「刷新」：结果 + 轮次各同步一次。非 force 时各自过闸门。
     * [terms] 为空 = 教务默认学期 + 紧邻下一学期（见 [resolveDefaultTerms]）。
     */
    suspend fun refresh(
        terms: List<String> = emptyList(),
        force: Boolean = false,
    ): RefreshOutcome = RefreshOutcome(
        results = syncResults(terms = terms, force = force),
        rounds = syncRounds(force = force),
    )

    /**
     * 同步选课结果。[terms] 为空时用 [resolveDefaultTerms]；显式给学期时**不受闸门约束**
     * （用户点了「加载某学期」就该立刻抓）。
     */
    suspend fun syncResults(terms: List<String> = emptyList(), force: Boolean = false): Result {
        if (!mutex.tryLock()) return Result.InProgress
        try {
            if (!force && terms.isEmpty() && recentlySynced()) return Result.Skipped

            val reason = ensureSession()
            if (reason != null) return Result.Failed(reason)
            val targetTerms = if (terms.isEmpty()) resolveDefaultTerms() else terms
            val fetched = mutableMapOf<String, List<CourseSelection>>()
            for (term in targetTerms) {
                fetched[term] = fetchResults(term)
            }
            fetched.forEach { (term, rows) -> repo.replaceTerm(term, rows) }
            prefs.setSelectionSyncMillis(nowMillis())
            return Result.Updated(terms = fetched.keys.toList(), rowCount = fetched.values.sumOf { it.size })
        } catch (e: Exception) {
            return Result.Failed(e.message ?: "选课结果同步失败")
        } finally {
            mutex.unlock()
        }
    }

    /** 只抓一个学期（选课页「更多学期」选中未缓存的学期时）。 */
    suspend fun syncTerm(term: String): Result = syncResults(terms = listOf(term), force = true)

    /** 同步选课轮次。 */
    suspend fun syncRounds(force: Boolean = false): RoundsResult {
        if (!mutex.tryLock()) return RoundsResult.InProgress
        try {
            if (!force && recentlyCheckedRounds()) return RoundsResult.Skipped
            val reason = ensureSession()
            if (reason != null) return RoundsResult.Failed(reason)
            val rounds = fetchRounds()
            val fetchedAt = nowMillis()
            roundsStore.save(
                SelectionRoundsStore.Snapshot(
                    fetchedAt = fetchedAt,
                    rounds = rounds.map(SelectionRoundsStore::rowOf),
                ),
            )
            prefs.setSelectionRoundsCheckMillis(fetchedAt)
            return RoundsResult.Updated(rounds = rounds, fetchedAt = fetchedAt)
        } catch (e: Exception) {
            return RoundsResult.Failed(e.message ?: "选课轮次同步失败")
        } finally {
            mutex.unlock()
        }
    }

    /** 快照里的轮次（页面与提醒共用；坏了等于空快照）。 */
    fun cachedRounds(): List<SelectionRound> =
        roundsStore.load().rounds.map(SelectionRoundsStore::rowToRound)

    /** 快照抓取时刻（epoch millis；0 = 从未抓到过），轮次卡「上次同步」脚注用。 */
    fun cachedFetchedAt(): Long = roundsStore.load().fetchedAt

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private suspend fun ensureSession(): String? = when (val ensured = cas.ensureValid()) {
        is CasEnsureResult.Ready -> null
        else -> ScholarProgressSync.describe(ensured)
    }

    /**
     * 缺省要抓的学期：教务当前学期 + 紧邻下一学期（选课常发生在学期末选下学期，
     * 光抓当前学期会漏掉「已选下学期」）。下一学期只在下拉里存在时才抓——
     * 教务没这个学期就别浪费一次请求。顺带把下拉全量存进偏好（「更多学期」用）。
     */
    private suspend fun resolveDefaultTerms(): List<String> {
        val shell = cas.fetchHtml(SelectionParser.SHELL_URL)
            ?: throw IllegalStateException("没取到选课结果页面（会话可能已失效）")
        val current = SelectionParser.shellTerm(shell)
            ?: throw IllegalStateException("选课结果页没有学期下拉（教务结构可能变了）")
        val options = SelectionParser.shellTerms(shell)
        if (options.isNotEmpty()) prefs.setSelectionTerms(options)
        val next = SelectionRounds.nextTerm(current)
        return listOfNotNull(current, next?.takeIf { term -> options.isEmpty() || term in options })
    }

    private suspend fun fetchResults(term: String): List<CourseSelection> {
        val rows = mutableListOf<CourseSelection>()
        var count = 0
        var page = 1
        do {
            val url = SelectionParser.listUrl(term, page)
                ?: throw IllegalStateException("学期号不合法：$term")
            val body = cas.fetchHtml(url)
                ?: throw IllegalStateException("没取到选课数据（会话可能已失效）")
            val parsed = SelectionParser.parseFetchJson(body)
            count = parsed.count
            rows += parsed.rows
            page++
        } while (rows.size < count && page <= MAX_PAGES)
        return rows
    }

    private suspend fun fetchRounds(): List<SelectionRound> {
        val body = cas.fetchHtml(SelectionParser.ROUNDS_URL)
            ?: throw IllegalStateException("没取到选课轮次（会话可能已失效）")
        return SelectionParser.parseRounds(body).rounds
    }

    private suspend fun recentlySynced(): Boolean {
        val last = prefs.selectionSyncMillis()
        return last > 0 && nowMillis() - last < MIN_REFRESH_MS
    }

    private suspend fun recentlyCheckedRounds(): Boolean {
        val last = prefs.selectionRoundsCheckMillis()
        return last > 0 && nowMillis() - last < MIN_REFRESH_MS
    }

    companion object {
        /**
         * 同一份数据两次成功同步的最小间隔。页面每次进入都会调刷新，没有这道闸门
         * 就是「进一次打一次教务」；30 分钟内的重复打开直接用缓存。
         */
        const val MIN_REFRESH_MS: Long = 30 * 60 * 1000L

        /** 翻页上限，同 `ExamSync.MAX_PAGES`：挡住 count 异常时不至于空转。 */
        const val MAX_PAGES = 20
    }
}
