package edu.jxslu.schedule.data.repo

import android.util.Log
import edu.jxslu.schedule.data.jw.InternshipBaseParser
import edu.jxslu.schedule.data.session.CasEnsureResult
import edu.jxslu.schedule.data.session.CasSession
import edu.jxslu.schedule.domain.InternshipBase
import edu.jxslu.schedule.domain.InternshipCacheRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * 实习基地装载（DESIGN §4.43）：OkHttp 直取 + **12 小时快照缓存**，**不落 Room**——
 * 低频参考数据（学校级清单，短时间几乎不变），进页默认零网络：新鲜期内直接用
 * [InternshipSnapshotStore] 的快照，过期才抓一次；顶栏「刷新」手动强刷。
 *
 * **单次抓取、不做翻页**：教务该接口实测忽略一切分页参数（记录见
 * [InternshipBaseParser.listUrl]），固定只回前 10 条而 `count` 声明更多——多翻只会拿回
 * 同一页、合成重复行。`Loaded.declaredCount` 把该差额带给界面如实注明；
 * 哪天教务修好翻页，解析层已按 qzTable 口径带号，只需在这里加回翻页循环。
 *
 * 与其它 Sync 同一套纪律：互斥让位、失败静默（返回值不抛）、更新失败时**宁旧勿空**
 * （抓过就有旧快照带 `updateFailed` 标记展示，从没抓到才报错）、日志只记条数不记字段。
 * **整个读写收口在 [Dispatchers.IO]**：页面从 `LaunchedEffect`（主线程）直接调这里，
 * 快照文件与凭证闸门都是阻塞 IO，不能压在主线程上（2026-10-07 复查修）。
 */
class InternshipBasesLoader(
    private val cas: CasSession,
    private val store: InternshipSnapshotStore,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()

    sealed interface Result {
        /**
         * [declaredCount] 是教务声明的总数（-1 = 未声明）；大于 [bases] 条数时界面要注明。
         * [fromCache] = 数据来自本机快照（本轮未打网络）；[updateFailed] = 想更新但失败、
         * 退回快照展示（界面要给「显示的是上次数据」的提示）。
         */
        data class Loaded(
            val bases: List<InternshipBase>,
            val declaredCount: Int,
            val fromCache: Boolean,
            val updateFailed: Boolean,
        ) : Result

        data object InProgress : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun load(force: Boolean = false): Result {
        if (!mutex.tryLock()) return Result.InProgress
        try {
            val result = withContext(Dispatchers.IO) { loadLocked(force) }
            Log.i(
                TAG,
                "internship bases -> " + when (result) {
                    is Result.Loaded ->
                        "loaded ${result.bases.size}/${result.declaredCount}" +
                            (if (result.fromCache) " (cache)" else "") +
                            (if (result.updateFailed) " (update-failed, stale shown)" else "")
                    Result.InProgress -> "in-progress"
                    is Result.Failed -> "failed: ${result.reason}"
                },
            )
            return result
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun loadLocked(force: Boolean): Result {
        val snapshot = store.load()
        // 新鲜期内直接用快照（手动强刷除外）：进页默认零网络
        if (InternshipCacheRules.shouldUseCache(force, snapshot.fetchedAt, now())) {
            return Result.Loaded(
                bases = snapshot.bases.map(InternshipSnapshotStore::rowToBase),
                declaredCount = snapshot.declaredCount,
                fromCache = true,
                updateFailed = false,
            )
        }
        return fetchAndSave(snapshot)
    }

    private suspend fun fetchAndSave(snapshot: InternshipSnapshotStore.Snapshot): Result {
        when (val ensured = cas.ensureValid()) {
            is CasEnsureResult.Ready -> Unit
            // 这页没有「导入」动作，不要说成「即可自动导入」（describe 的通用文案不适用）；
            // 需要用户去登录的动作不可用旧数据糊弄，直接报错
            is CasEnsureResult.NoCredential -> return Result.Failed(
                "还没保存统一认证账号，去「我的 → 教务账户」登录一次即可查看",
            )
            else -> return Result.Failed(ScholarProgressSync.describe(ensured))
        }
        val body = cas.fetchHtml(InternshipBaseParser.listUrl(page = 1))
            ?: return staleOrFail(snapshot, "没取到实习基地数据（会话失效或网络不通）")
        val parsed = try {
            InternshipBaseParser.parsePage(body)
        } catch (e: CancellationException) {
            throw e // 协程被取消，不能吞成解析失败
        } catch (e: Exception) {
            return staleOrFail(snapshot, e.message ?: "实习基地解析失败")
        }
        if (parsed.bases.isEmpty() && parsed.count > 0) {
            // count>0 却一行都没收下：更可能是页面结构变了（缺名称的行被逐行丢弃），
            // 不能当「教务暂无数据」吞掉——按更新失败处理（宁旧勿空）
            return staleOrFail(snapshot, "实习基地条目缺少名称，教务页面结构可能已变")
        }
        // 空结果也写快照：count≤0 的「空」是教务的权威状态，存下来页面才会如实显示
        // （先例：SelectionRoundsStore 的「轮次快照允许存空」；写失败由 store 自己吞错）
        store.save(
            InternshipSnapshotStore.Snapshot(
                fetchedAt = now(),
                declaredCount = parsed.count,
                bases = parsed.bases.map(InternshipSnapshotStore::rowOf),
            ),
        )
        return Result.Loaded(
            bases = parsed.bases,
            declaredCount = parsed.count,
            fromCache = false,
            updateFailed = false,
        )
    }

    /** 更新失败时「宁旧勿空」：抓过（`fetchedAt > 0`）就带标记展示旧快照，从没抓到才报错。 */
    private fun staleOrFail(snapshot: InternshipSnapshotStore.Snapshot, reason: String): Result =
        if (snapshot.fetchedAt > 0) {
            Result.Loaded(
                bases = snapshot.bases.map(InternshipSnapshotStore::rowToBase),
                declaredCount = snapshot.declaredCount,
                fromCache = true,
                updateFailed = true,
            )
        } else {
            Result.Failed(reason)
        }

    private companion object {
        const val TAG = "InternshipBases"
    }
}
