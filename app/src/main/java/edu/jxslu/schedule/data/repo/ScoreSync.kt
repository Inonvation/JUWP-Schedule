package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.data.jw.ScoreParser
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.session.AutoSyncRules
import edu.jxslu.schedule.data.session.CasEnsureResult
import edu.jxslu.schedule.data.session.CasSession
import edu.jxslu.schedule.domain.InboxKind
import edu.jxslu.schedule.domain.InboxKeys
import edu.jxslu.schedule.domain.ScoreChangeDetector
import edu.jxslu.schedule.domain.ScoreRecord
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
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
 * 闸门（DESIGN §4.33）：成绩提醒开着时走**小时级**间隔（`score_check_millis`），
 * 关着时维持原有的 7 天日期闸门（`scoreSyncDate`）——关提醒后自动导入节奏不变。
 * 写库前用 [ScoreChangeDetector] 算一遍变更，随 [Result.Updated] 交给提醒层决定要不要通知；
 * 本类不发通知、不知道通知的存在。
 *
 * 与 [ScholarProgressSync] 同一套闸门纪律：首次立即抓、成功才记日期、失败静默。
 * 写库是**按学期替换**，未涉及的学期不动（同成绩导入的既有口径）。
 */
class ScoreSync(
    private val cas: CasSession,
    private val repo: ScoreRepository,
    private val prefs: DisplayPrefsStore,
    /** 收件箱留痕（DESIGN §4.38）：出分/改分事实入箱，与成绩提醒开关无关。 */
    private val inbox: InboxRepository,
    private val today: () -> LocalDate = LocalDate::now,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** 抓取互斥（口径同 `ScholarProgressSync`）：撞上了直接让位，不排队重抓一遍。 */
    private val mutex = Mutex()

    sealed interface Result {
        /**
         * [terms] 是写入的学期数，[records] 是条数；[changes] 是写库前比对的变更明细
         * （2026-10-06 起恒计算，与提醒开关解耦——收件箱要独立收出分/改分事实），
         * [firstImport] 表示旧库为空（首装/清库后的首次导入，调用方据此**不发通知**——
         * 第一次拿到的东西不算「变动」）。
         */
        data class Updated(
            val terms: Int,
            val records: Int,
            val changes: List<ScoreChangeDetector.Change> = emptyList(),
            val firstImport: Boolean = false,
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
        val alertOn = prefs.scoreAlertEnabled.first()
        val intervalHours = prefs.scoreAlertIntervalHours.first()
        val now = nowMillis()
        if (!force) {
            if (alertOn) {
                val last = prefs.scoreAlertCheckMillis()
                if (!AutoSyncRules.shouldAttemptAt(last, intervalHours, now)) return Result.Skipped
            } else {
                val hasData = repo.hasData()
                val last = prefs.scoreSyncDate.first()
                if (!AutoSyncRules.shouldAttempt(hasData, last, today())) return Result.Skipped
            }
        }

        when (val ensured = cas.ensureValid()) {
            is CasEnsureResult.Ready -> Unit
            else -> return Result.Failed(ScholarProgressSync.describe(ensured))
        }

        val records = mutableListOf<ScoreRecord>()
        var count = 0
        var page = 1
        do {
            if (page > 1) delay(AutoSyncRules.interRequestDelayMs())
            // 学期传空 = 全部学期，与成绩页导入同一个口径
            val body = cas.fetchHtml(ScoreParser.listUrl(term = "", page = page))
                ?: return Result.Failed("没取到成绩数据（会话可能已失效）")
            val parsed = try {
                ScoreParser.parseFetchJson(body)
            } catch (e: CancellationException) {
                throw e // 协程被取消（页面离开/作用域撤销），不能吞成解析失败
            } catch (e: Exception) {
                return Result.Failed(e.message ?: "成绩解析失败")
            }
            count = parsed.count
            records += parsed.records
            page++
        } while (records.size < count && page <= MAX_PAGES)

        if (records.isEmpty()) return Result.Failed("教务没有返回任何成绩")

        // 写库前快照 + 变更检测：替换是盲的，旧值过了这村就没了。
        // 检测与提醒开关解耦（2026-10-06）：纯内存比对零成本，收件箱要独立收
        // 出分/改分事实；`alertOn` 只决定发不发系统通知（在提醒层判）。
        val old = repo.getAll()
        val firstImport = old.isEmpty()
        val changes = ScoreChangeDetector.detect(old, records)

        val grouped = records.groupBy { it.term }
        grouped.forEach { (term, list) -> repo.replaceTerm(term, list) }
        prefs.setScoreSyncDate(today().toString())
        if (alertOn) prefs.setScoreAlertCheckMillis(nowMillis())
        // 收件箱留痕（DESIGN §4.38）：首导是基线不是「变动」，与通知层同一条豁免。
        // 分数参与去重键——改分（85→90）是新事实；同一分数反复 sync 同键只刷新。
        if (!firstImport && changes.isNotEmpty()) {
            for (change in changes) {
                inbox.post(
                    kind = InboxKind.SCORE,
                    dedupeKey = InboxKeys.SCORE_PREFIX + change.term + "|" + change.name + "|" + change.scoreStr,
                    title = if (change.kind == ScoreChangeDetector.Kind.PUBLISHED) "成绩发布" else "成绩变更",
                    body = "《${change.name}》（${change.term}）分数 ${change.scoreStr}",
                    route = SubpageScreen.SCORES.name,
                )
            }
        }
        return Result.Updated(terms = grouped.size, records = records.size, changes = changes, firstImport = firstImport)
    }

    companion object {
        /** 翻页上限，与成绩页导入一致：挡住 count 异常时不至于空转。 */
        const val MAX_PAGES = 20
    }
}
