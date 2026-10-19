package edu.jxslu.schedule.data.repo

import android.util.Log
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.data.session.CasEnsureResult
import edu.jxslu.schedule.data.session.CasSession
import edu.jxslu.schedule.data.xg.DormRepairParser
import edu.jxslu.schedule.data.xg.DormRepairWebClient
import edu.jxslu.schedule.data.xg.XgUrls
import edu.jxslu.schedule.domain.DormRepairRecord
import edu.jxslu.schedule.domain.DormRepairRules
import edu.jxslu.schedule.domain.DormRepairSnapshot
import edu.jxslu.schedule.domain.InboxKeys
import edu.jxslu.schedule.domain.InboxKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 宿舍报修进度同步（DESIGN §4.49）。
 *
 * 一条**只读**链路：CAS → 学工 SSO → `/office/` → `data/approve/apps/forms/fore/user/list`。
 * 只查自己提交过的单子，绝不调用提交 / 审批 / 退回接口。
 *
 * 会话两级（按成本从低到高）：
 * 1. 先用 [DormRepairWebClient] 读 WebView 现成的 office 会话——不登录，刚用过报修页时
 *    一定新鲜；提交后关窗/回前台这条主路径全靠它；
 * 2. 再回退 [CasSession]（存过统一认证账号、`session_oa` 落在共享 cookie jar 的 xgxt 桶）。
 *    自动检查的 CAS 登录受 12 小时窗口限制，避免每个冷启动都完整登一次统一认证。
 * 两条都不可用就静默 [CheckResult.Skipped]，不动旧快照。
 *
 * 纪律：
 * - 失败**宁旧勿空**：解析/网络失败不写快照，卡片继续显示上次数据；
 * - 只有「上次同一条还没结束、这次结束了」才入箱（首跑只建基线，见
 *   [DormRepairRules.newlyFinished]）；通知由 `DormRepairReminder` 发，本类只负责事实入箱；
 * - 只在回到前台 / 关闭报修窗口 / 手动刷新时跑，不做后台轮询。
 */
class DormRepairSync(
    private val cas: CasSession,
    private val store: DormRepairStore,
    private val inbox: InboxRepository,
    private val webClient: DormRepairWebClient = DormRepairWebClient(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    sealed interface CheckResult {
        data class Ok(
            val snapshot: DormRepairSnapshot,
            val justFinished: List<DormRepairRecord>,
        ) : CheckResult

        /** 没有可用的会话（没存账号、WebView 也没有 office 会话）。不写快照，下次再试。 */
        data object Skipped : CheckResult

        data class Failed(val message: String) : CheckResult
    }

    private val mutex = Mutex()

    /**
     * 上次真正发请求的时刻（含手动刷新）。「打开 App」= 每次回到前台，但 30 分钟内
     * 不重复打接口：用户切出去再切回来不算一次新的打开，避免无谓的 184KB 应答。
     */
    @Volatile
    private var lastAttemptAt = 0L

    /**
     * 手动刷新（详情页顶栏 / 今日页下拉）。串行执行，失败返回原因。
     * 手动动作允许走一次 CAS 登录（用户明确要最新进度）。
     */
    suspend fun refresh(): CheckResult = runCheck(allowLogin = true, allowAutoLogin = false)

    /**
     * 回到前台时检查一次。[DormRepairStore] 的 `checkedOnce` / `tracking` 是闸门：
     * 没用过报修的人只在首次安装/升级后查一次建基线，之后不再白打接口。
     *
     * 取数顺序：**先用 WebView 现成的 office 会话（最便宜）**；没有才考虑 CAS，
     * 且自动检查的 CAS 登录受 12 小时窗口限制，避免冷启动风控。
     */
    suspend fun checkOnAppOpen(): CheckResult {
        val at = now()
        if (at - lastAttemptAt < APP_OPEN_INTERVAL_MS) return CheckResult.Skipped
        val snapshot = store.snapshot.value
        if (!snapshot.tracking && snapshot.checkedOnce) return CheckResult.Skipped
        // 已有检查在跑就不排队（手动刷新才需要排队）
        if (!mutex.tryLock()) return CheckResult.Skipped
        // 只有「用过报修」的用户才允许自动 CAS 登录：没用过的人首查只用 WebView 会话，
        // 找不到就跳过——不能为了给一个从没用过报修的人建基线而完整登一次统一认证。
        val autoLoginDue =
            snapshot.tracking && at - snapshot.lastAutoLoginAt >= AUTO_LOGIN_INTERVAL_MS
        return try {
            lastAttemptAt = at
            withContext(Dispatchers.IO) {
                checkLocked(allowLogin = false, autoLoginDue = autoLoginDue)
            }
        } finally {
            mutex.unlock()
        }
    }

    /** 用户打开报修页：从这一刻起 App 打开检查会持续运行。 */
    suspend fun onRepairWindowOpened() {
        store.markTracking()
    }

    /** 报修页关闭（可能刚提交完）：等一下再查一次，让新记录赶回今日页卡片。 */
    suspend fun onRepairWindowClosed() {
        store.markTracking()
        delay(1_000)
        runCheck(allowLogin = false, allowAutoLogin = true)
    }

    private suspend fun runCheck(allowLogin: Boolean, allowAutoLogin: Boolean): CheckResult =
        mutex.withLock {
            lastAttemptAt = now()
            val snapshot = store.snapshot.value
            val autoLoginDue = allowAutoLogin &&
                snapshot.tracking &&
                now() - snapshot.lastAutoLoginAt >= AUTO_LOGIN_INTERVAL_MS
            withContext(Dispatchers.IO) { checkLocked(allowLogin, autoLoginDue) }
        }

    private suspend fun checkLocked(allowLogin: Boolean, autoLoginDue: Boolean): CheckResult {
        val before = store.snapshot.value
        val body = when (val fetched = fetchBody(allowLogin, autoLoginDue)) {
            is Fetch.Ok -> fetched.body
            Fetch.NoSession -> return CheckResult.Skipped
            is Fetch.Failed -> return CheckResult.Failed(fetched.message)
        }
        val parsed = try {
            DormRepairParser.parse(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CheckResult.Failed(e.message ?: "报修记录解析失败")
        }

        val at = now()
        val merged = parsed.map { incoming ->
            DormRepairRules.mergeRecord(
                incoming = incoming,
                previous = DormRepairRules.find(before, incoming.formUserId),
                now = at,
            )
        }
        val justFinished = DormRepairRules.newlyFinished(before, merged)
        val next = DormRepairSnapshot(
            fetchedAt = at,
            records = merged,
            dismissedIds = before.dismissedIds,
            notifiedIds = (before.notifiedIds + justFinished.map { it.formUserId }).distinct(),
            checkedOnce = true,
            tracking = merged.any { !DormRepairRules.isFinished(it) },
        )
        // 先入箱再落 notifiedIds：两者之间崩溃时宁可重投同一条消息（InboxRules 同键刷新），
        // 也不要先把「已通知」记下来、把消息永久丢掉。入箱是旁路，失败不回头影响同步。
        for (record in justFinished) {
            inbox.post(
                kind = InboxKind.DORM_REPAIR,
                dedupeKey = InboxKeys.DORM_REPAIR_PREFIX + record.formUserId,
                title = "宿舍报修已完成",
                body = completionText(record),
                route = SubpageScreen.DORM_REPAIR.name,
                focusItemId = record.formUserId.toString(),
            )
        }
        store.save(next)
        Log.i(TAG, "dorm repair -> ${merged.size} records, justFinished=${justFinished.size}")
        return CheckResult.Ok(store.snapshot.value, justFinished)
    }

    private suspend fun fetchBody(allowLogin: Boolean, autoLoginDue: Boolean): Fetch {
        // 1) 先试 WebView 现成的 office 会话：不登录、最便宜，刚用过报修页时一定新鲜。
        //    这也是自动检查的主路径——用户提交后关窗/回前台，卡片立刻能拿到新记录。
        val fallback = webClient.fetch()
        if (fallback is DormRepairWebClient.Result.Ok) return Fetch.Ok(fallback.body)

        // 2) 再考虑 CAS：手动刷新不设限；自动检查只在「进程里已有 CAS cookie
        //    （别的同步刚登过）」或「距上次自动登录 ≥ 12 小时」时才走。
        val canLogin = allowLogin || cas.hasCookies() || autoLoginDue
        val ensure = if (canLogin) {
            // 自动登录尝试先落盘（失败也算一次）：进程被杀后 12 小时窗口仍然有效，
            // 避免每次冷启动都为一条低频记录完整登一次统一认证。
            if (!allowLogin && autoLoginDue) store.markAutoLoginAttempt(now())
            runCatching { cas.ensureValid() }.getOrNull()
        } else {
            null
        }
        if (ensure is CasEnsureResult.Ready) {
            val viaCas = runCatching {
                val sso = cas.fetchHtml(XgUrls.SSO_LOGIN)
                val office = cas.fetchHtml(XgUrls.OFFICE)
                if (sso == null || office == null) {
                    null
                } else {
                    cas.postForm(
                        XgUrls.OFFICE_RECORD_LIST,
                        XgUrls.REPAIR.recordListForm(DormRepairParser.MAX_RECORDS),
                    )
                }
            }.getOrNull()
            // 只认 JSON：手登链路偶尔会回一张 CAS 登录页 HTML（200），那是「会话没建好」，
            // 不是数据；别把登录页丢给解析器。
            if (viaCas != null && viaCas.trimStart().startsWith("{")) return Fetch.Ok(viaCas)
        }

        return when (fallback) {
            is DormRepairWebClient.Result.Ok -> Fetch.Ok(fallback.body)
            DormRepairWebClient.Result.NoSession -> when {
                ensure == null -> Fetch.NoSession
                ensure is CasEnsureResult.Ready ->
                    Fetch.Failed("没取到学工报修数据（学工会话可能已失效）")
                else -> Fetch.Failed(describeCas(ensure))
            }
            is DormRepairWebClient.Result.Failed -> Fetch.Failed(fallback.message)
        }
    }

    private fun completionText(record: DormRepairRecord): String {
        val where = listOf(record.dorm, record.fault)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        return if (where.isBlank()) {
            "你的宿舍报修已办结，点按查看进度。"
        } else {
            "$where 已办结，点按查看进度。"
        }
    }

    private fun describeCas(result: CasEnsureResult?): String = when (result) {
        is CasEnsureResult.Ready -> ""
        CasEnsureResult.NoCredential -> "还没保存统一认证账号，可在「我的 → 教务账户」登录"
        CasEnsureResult.Suspended -> "密码连续错误，已暂停自动登录，请更新密码后重试"
        is CasEnsureResult.NeedsManualLogin -> result.message
        is CasEnsureResult.Failed -> result.message
        null -> "统一认证会话不可用"
    }

    private sealed interface Fetch {
        data class Ok(val body: String) : Fetch
        data object NoSession : Fetch
        data class Failed(val message: String) : Fetch
    }

    private companion object {
        const val TAG = "DormRepair"

        /** 回到前台时的最小请求间隔：30 分钟。改它等于改「多久可能弹一次完成通知」。 */
        const val APP_OPEN_INTERVAL_MS = 30L * 60 * 1000

        /** 自动检查最多多久主动登录一次 CAS：12 小时。手动刷新不受此限。 */
        const val AUTO_LOGIN_INTERVAL_MS = 12L * 60 * 60 * 1000
    }
}
