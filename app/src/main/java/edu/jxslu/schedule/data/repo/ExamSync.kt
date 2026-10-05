package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.data.jw.ExamScheduleParser
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.session.AutoSyncRules
import edu.jxslu.schedule.data.session.CasEnsureResult
import edu.jxslu.schedule.data.session.CasSession
import edu.jxslu.schedule.domain.ExamChangeDetector
import edu.jxslu.schedule.domain.ExamMapper.ExamEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/**
 * 考试安排自动检查（DESIGN §4.33）：考试变动提醒的数据链。
 *
 * 走 [CasSession.fetchHtml]（已存凭证 + OkHttp），不开窗口：缺省学期以教务壳页为准（同
 * `scripts/fetch_exams.py` 的做法），再 GET 数据接口 `/jsxsd/xsks/xsksap_list`
 * 翻页抓全量，解析用 [ExamScheduleParser.parseFetchJson]——与教务 WebView 导入
 * **同一份解析口径**，只是传输层换成 OkHttp（同 `ScoreSync` 与成绩页的关系）。
 *
 * **绝不写课程表**：检测出的变更只随 [Result.Updated] 交给提醒层发通知，
 * 点通知落教务导入页，用户确认后才写库——考试进课表只有手动导入一条路。
 *
 * 闸门与基线纪律：
 * - 只服务提醒：开关关着直接 Skipped，一行网络请求都不发；
 * - 间隔 + `exam_check_millis`（失败不写时刻，下个周期自动补查）；
 * - 壳页学期缓存（[AutoSyncRules.shellTermCacheUsable]）：TTL 内复用上次抓到的学期，
 *   省一个壳页 GET；force（手动更新按钮）永远真抓；
 * - 基线为空（首跑）或学期切换 = 只存基线**不通知**，防首跑/换学期整表刷屏；
 * - 撤考忽略（[ExamChangeDetector] 的口径），宁可少打扰。
 */
class ExamSync(
    private val cas: CasSession,
    private val prefs: DisplayPrefsStore,
    private val snapshotStore: ExamSnapshotStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** 抓取互斥（口径同 `ScoreSync`）：撞上了直接让位，不排队重抓一遍。 */
    private val mutex = Mutex()

    sealed interface Result {
        /** [changes] 是检测到的变更（可能为空 = 抓到了但没变动）；[term] 是教务实际返回的学期。 */
        data class Updated(
            val term: String,
            val count: Int,
            val changes: List<ExamChangeDetector.Change> = emptyList(),
        ) : Result

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
        val alertOn = prefs.examAlertEnabled.first()
        val intervalHours = prefs.examAlertIntervalHours.first()
        val now = nowMillis()
        if (!force) {
            if (!alertOn) return Result.Skipped
            if (!AutoSyncRules.shouldAttemptAt(prefs.examAlertCheckMillis(), intervalHours, now)) {
                return Result.Skipped
            }
        }

        when (val ensured = cas.ensureValid()) {
            is CasEnsureResult.Ready -> Unit
            else -> return Result.Failed(ScholarProgressSync.describe(ensured))
        }

        // 缺省学期以教务为准：优先用壳页学期缓存（TTL 内，省一次壳页 GET），
        // 过期 / 首跑 / force 才真抓壳页；抓不到不猜测
        val cachedTerm = prefs.examShellTerm()
        val term: String = if (cachedTerm != null &&
            AutoSyncRules.shellTermCacheUsable(cachedTerm, prefs.examShellTermAtMillis(), force, now)
        ) {
            cachedTerm
        } else {
            val shell = cas.fetchHtml(JwUrls.EXAM_QUERY)
                ?: return Result.Failed("没取到考试安排页面（会话可能已失效）")
            termFromShell(shell)
                ?.also { prefs.setExamShellTerm(it, nowMillis()) }
                ?: return Result.Failed("考试安排页没有学期下拉（教务结构可能变了）")
        }

        val exams = mutableListOf<ExamEntry>()
        var count = 0
        var page = 1
        do {
            if (page > 1) delay(AutoSyncRules.interRequestDelayMs())
            val body = cas.fetchHtml(listUrl(term, page))
                ?: return Result.Failed("没取到考试数据（会话可能已失效）")
            val parsed = try {
                ExamScheduleParser.parseFetchJson(body)
            } catch (e: CancellationException) {
                throw e // 协程被取消，不能吞成解析失败
            } catch (e: Exception) {
                return Result.Failed(e.message ?: "考试解析失败")
            }
            count = parsed.count
            exams += parsed.exams
            page++
        } while (exams.size < count && page <= MAX_PAGES)

        // 与上次基线比对。基线为空（首跑）或学期切换 = 只建基线不通知，防整表刷屏
        val snapshot = snapshotStore.load()
        val baselineUsable = snapshot.term == term && snapshot.exams.isNotEmpty()
        val changes = if (baselineUsable) {
            ExamChangeDetector.detect(snapshot.exams.map(ExamSnapshotStore::rowToEntry), exams)
        } else {
            emptyList()
        }

        snapshotStore.save(ExamSnapshotStore.Snapshot(term = term, exams = exams.map(ExamSnapshotStore::rowOf)))
        prefs.setExamAlertCheckMillis(nowMillis())
        return Result.Updated(term = term, count = exams.size, changes = changes)
    }

    companion object {
        /** 翻页上限，同 [ScoreSync.MAX_PAGES]：挡住 count 异常时不至于空转。 */
        const val MAX_PAGES = 20

        /**
         * 从壳页 HTML 抽学期下拉的当前选中项（`<option selected>` 的 value 或文本）。
         * 正则而不是 DOM：教务壳页结构简单，和脚本侧 BeautifulSoup 的口径一致；
         * 抽不到返回 null（调用方报失败，不猜学期）。
         */
        fun termFromShell(html: String): String? {
            val attr = Regex("""<option[^>]*selected[^>]*>""", RegexOption.IGNORE_CASE)
            for (tag in attr.findAll(html)) {
                // value 属性存在取 value；教务下拉的 value 与文本相同（2026-2027-1），文本兜底
                val value = Regex("""value\s*=\s*["']([^"']+)["']""").find(tag.value)
                    ?.groupValues?.get(1)?.trim()
                if (!value.isNullOrBlank()) return value
                // `<option selected>2026-2027-1</option>`：取闭合标签前的文本
                val after = html.substringAfter(tag.value, "").substringBefore("</option>").trim()
                if (after.isNotEmpty()) return after
            }
            return null
        }

        fun listUrl(term: String, page: Int): String =
            "${JwUrls.EXAM_LIST_API}?xnxqid=${java.net.URLEncoder.encode(term, "UTF-8")}&xqlb=&pageNum=$page&pageSize=${ExamScheduleParser.PAGE_SIZE}"
    }
}
