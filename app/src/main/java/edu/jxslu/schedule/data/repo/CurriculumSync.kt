package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.data.jw.CurriculumParser
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.session.AutoSyncRules
import edu.jxslu.schedule.data.session.CasEnsureResult
import edu.jxslu.schedule.data.session.CasSession
import edu.jxslu.schedule.domain.CurriculumRules
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/**
 * 培养方案抓取（DESIGN §4.41）。
 *
 * 走 [CasSession.fetchHtml]（OkHttp + 已存凭证），**不开 WebView**：单页抓取，
 * 与学业完成情况同一条链路。首启引导登录成功、冷启动过闸门、页面里点「重新导入」，
 * 三个入口最终都落到 [sync]。
 *
 * 纪律与 [ScholarProgressSync] 同一套（少「多页要么全要要么全丢」一条，这里就一页）：
 * 1. **解析结果先校验再写库**：教务改版时宁可这次不更新，也不能拿残值覆盖老数据；
 * 2. **失败静默**（对调用方是返回值不是异常）：自动导入是后台行为，不该弹窗打断用户；
 * 3. **成功才记日期**：失败记日期等于把闸门顶掉 7 天，一次网络抖动换一周不刷新；
 * 4. **mutex 让位**：撞上正在跑的同步直接回 [Result.InProgress]，不排队白打一轮。
 */
class CurriculumSync(
    private val cas: CasSession,
    private val repo: CurriculumRepository,
    private val prefs: DisplayPrefsStore,
    private val today: () -> LocalDate = LocalDate::now,
) {

    /** 抓取互斥（见 [Result.InProgress]）。实例是单例，所以这道锁覆盖全进程的触发点。 */
    private val mutex = Mutex()

    sealed interface Result {
        /** 真的抓到了并写进库。[courses] 是落库条数。 */
        data class Updated(val courses: Int) : Result

        /** 闸门没过（还在刷新间隔内），这次什么都没做。 */
        data object Skipped : Result

        /** 已经有一次抓取在跑，这次直接让位。 */
        data object InProgress : Result

        /** 没抓到。原因给用户看，但自动导入路径不会弹它。 */
        data class Failed(val reason: String) : Result
    }

    /** @param force 用户主动点「重新导入」时传 true，绕过 7 天闸门。 */
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
            val last = prefs.curriculumSyncDate.first()
            if (!AutoSyncRules.shouldAttempt(hasData, last, today())) return Result.Skipped
        }

        when (val ensured = cas.ensureValid()) {
            is CasEnsureResult.Ready -> Unit
            // 会话层的失败翻译与学业完成情况共用一个口径（只说下一步该做什么）
            else -> return Result.Failed(ScholarProgressSync.describe(ensured))
        }

        val html = cas.fetchHtml(JwUrls.CURRICULUM)
            ?: return Result.Failed("没取到页面（会话可能已失效）")
        val parsed = CurriculumParser.parse(html)
            ?: return Result.Failed("页面结构可能已变，本次未更新")
        CurriculumRules.validate(parsed)?.let { return Result.Failed(it) }

        repo.replaceAll(parsed.courses)
        if (parsed.planName.isNotBlank()) prefs.setCurriculumPlanName(parsed.planName)
        // 日期在写库成功之后才落：失败落日期会把闸门顶掉 7 天
        prefs.setCurriculumSyncDate(today().toString())
        return Result.Updated(courses = parsed.courses.size)
    }
}
