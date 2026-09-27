package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.jw.ScholarProgressParser
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.session.AutoSyncRules
import edu.jxslu.schedule.data.session.CasEnsureResult
import edu.jxslu.schedule.data.session.CasSession
import edu.jxslu.schedule.domain.ScholarCourse
import edu.jxslu.schedule.domain.ScholarDimension
import edu.jxslu.schedule.domain.ScholarGroup
import edu.jxslu.schedule.domain.ScholarProgressRules
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/**
 * 学业完成情况抓取（DESIGN §4.29）。
 *
 * 走 [CasSession.fetchHtml]（OkHttp + 已存凭证），**不开 WebView**：这条链路在会话层
 * 建好之后就是一个普通的带 cookie GET，学籍卡补抓（[ProfileSync]）已经这么用了。
 * 首启引导登录成功、冷启动过闸门、页面里点「重新导入」，三个入口最终都落到 [sync]。
 *
 * 五条纪律：
 * 1. **四页要么全要、要么全丢**：四个维度出自同一次教务快照，只写一半会留下
 *    「课程体系是新的、课程性质是旧的」这种自相矛盾的状态；
 * 2. **解析结果先校验再写库**：教务改版时宁可这次不更新，也不能拿残值覆盖老数据；
 * 3. **失败静默**（对调用方是返回值不是异常）：自动导入是后台行为，不该弹窗打断用户；
 * 4. **成功才记日期**：失败记日期等于把闸门顶掉 7 天，一次网络抖动换一周不刷新；
 * 5. **串行抓**：教务对并发请求的态度未知，四个页面串行也就几秒，不值得冒险。
 */
class ScholarProgressSync(
    private val cas: CasSession,
    private val repo: ScholarProgressRepository,
    private val prefs: DisplayPrefsStore,
    private val today: () -> LocalDate = LocalDate::now,
) {

    /** 抓取互斥（见 [Result.InProgress]）。实例是单例，所以这道锁覆盖全进程的触发点。 */
    private val mutex = Mutex()

    sealed interface Result {
        /** 真的抓到了并写进库。[groups]/[courses] 是落库条数。 */
        data class Updated(val groups: Int, val courses: Int) : Result

        /** 闸门没过（还在刷新间隔内），这次什么都没做。 */
        data object Skipped : Result

        /**
         * 已经有一次抓取在跑，这次直接让位。
         *
         * 用 `tryLock` 而不是等锁：等锁意味着两个触发点串行各抓一遍（四个页面白打两轮），
         * 而它们要的是同一份数据。让位之后那一方抓完就够两处用了。
         * 实际能撞上的场景是冷启动与引导页登录成功挤在一起——同一页面的重复点击
         * 已经被 UI 的 `busy` 挡住。
         */
        data object InProgress : Result

        /** 没抓到。原因给用户看，但自动导入路径不会弹它。 */
        data class Failed(val reason: String) : Result
    }

    /**
     * @param force 用户主动点「重新导入」时传 true，绕过 7 天闸门。
     * @param onProgress 四个维度的抓取进度 `(已完成数, 总数)`，在**调用方协程的上下文**里回调
     *   （内部不切线程，UI 那边是主线程所以能直接写 state；冷启动那两处不传即可）。
     */
    suspend fun sync(
        force: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        if (!mutex.tryLock()) return Result.InProgress
        try {
            return syncLocked(force, onProgress)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun syncLocked(
        force: Boolean,
        onProgress: (Int, Int) -> Unit,
    ): Result {
        if (!force) {
            val hasData = repo.hasData()
            val last = prefs.scholarSyncDate.first()
            if (!AutoSyncRules.shouldAttempt(hasData, last, today())) return Result.Skipped
        }

        when (val ensured = cas.ensureValid()) {
            is CasEnsureResult.Ready -> Unit
            else -> return Result.Failed(describe(ensured))
        }

        val dimensions = ScholarDimension.entries
        val groups = mutableListOf<ScholarGroup>()
        val courses = mutableListOf<ScholarCourse>()
        var planName = ""

        dimensions.forEachIndexed { index, dimension ->
            // 报「正在抓第几个」而不是「抓完几个」：抓一个 190KB 的页面要几秒，
            // 进度条停在 0/4 上不动比没有进度条更让人怀疑是不是卡了
            onProgress(index + 1, dimensions.size)
            val html = cas.fetchHtml(JwUrls.scholarUrl(dimension))
                ?: return Result.Failed("${dimension.label}：没取到页面（会话可能已失效）")
            val parsed = ScholarProgressParser.parse(dimension, html)
                ?: return Result.Failed("${dimension.label}：页面结构可能已变，本次未更新")
            ScholarProgressRules.validate(parsed)?.let {
                return Result.Failed("${dimension.label}：$it")
            }
            if (dimension == ScholarDimension.System) {
                ScholarProgressRules.validateCreditTotal(parsed)?.let {
                    return Result.Failed("${dimension.label}：$it")
                }
                planName = parsed.planName
            }
            groups += parsed.groups
            courses += parsed.courses
        }

        repo.replaceAll(groups, courses)
        if (planName.isNotBlank()) prefs.setScholarPlanName(planName)
        // 日期在写库成功之后才落：失败落日期会把闸门顶掉 7 天
        prefs.setScholarSyncDate(today().toString())
        return Result.Updated(groups = groups.size, courses = courses.size)
    }

    companion object {
        /** 把会话层的失败翻译成给用户看的一句话。口径与 `ProfileSync` 一致：只说下一步该做什么。 */
        fun describe(result: CasEnsureResult): String = when (result) {
            is CasEnsureResult.Ready -> ""
            CasEnsureResult.NoCredential ->
                "还没保存统一认证账号，去「我的 → 教务账户」登录一次即可自动导入"
            CasEnsureResult.Suspended -> "密码连续错误，已暂停自动登录，请更新密码后重试"
            is CasEnsureResult.NeedsManualLogin -> result.message
            is CasEnsureResult.Failed -> result.message
        }
    }
}
