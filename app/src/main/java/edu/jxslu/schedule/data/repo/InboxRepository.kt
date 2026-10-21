package edu.jxslu.schedule.data.repo

import android.util.Log
import edu.jxslu.schedule.data.local.InboxDao
import edu.jxslu.schedule.data.local.InboxMessageEntity
import edu.jxslu.schedule.domain.InboxKind
import edu.jxslu.schedule.domain.InboxRules
import edu.jxslu.schedule.domain.InboxRules.PostDecision
import kotlinx.coroutines.flow.Flow

/**
 * 收件箱仓库（DESIGN §4.38）：应用内重要事实的留痕层，Room 单表。
 *
 * **投递只发生在各业务链「数据已到手并完成对比」的结果点上**（考试/成绩在各自 Sync 的
 * 写库收口处、调课在 `ScheduleSync.check` 落待处置缓存处、选课轮次在提醒发出处、
 * 余额在 `BalanceAlertReminder` 判定处）——本类与收件箱链路不发任何网络请求。
 * 去重语义见 [InboxRules.onPost]：同键不重发、已删不复活、未删重投刷新并重新置未读。
 *
 * **收件箱是旁路，不反向打断业务链**：每个写操作内部兜底吞错（只留日志）——
 * 留痕失败不能让成绩写库、调课检查、余额核对这些主链路跟着失败。读操作不吞：
 * 页面层由 Compose 流承载错误态。
 *
 * 读取口径：未删消息最新在前，最多 [InboxRules.MAX_MESSAGES] 条；未读数是独立 Flow，
 * 驱动今日页「+」图标与「+」菜单行的角标。
 */
class InboxRepository(
    private val dao: InboxDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** 未读消息数（角标）。 */
    val unreadCount: Flow<Int> = dao.observeUnreadCount()

    /** 可见消息列表（未软删，最新在前，上限 [InboxRules.MAX_MESSAGES] 条）。 */
    fun messages(): Flow<List<InboxMessageEntity>> = dao.observeMessages(InboxRules.MAX_MESSAGES)

    /**
     * 投递一条消息。重复调用（同一轮 sync 撞上重试、状态型每日复核）按去重键落更新，
     * 不产生第二条；事件真变了（键不同）才以新消息出现。失败静默（旁路纪律）。
     *
     * [silent] = 静默留痕（2026-10-21）：插入即置已读、重投不重新置未读——**不产生
     * 未读红点**。给「账单 / 流水」类消息用（快趣骑行结算 / 胖乖·趣智用水结算 /
     * U净 洗衣完成）：它们是可回查的事实，但不是「需要你处理」的待办，点亮红点
     * 会把「成绩发布」那种真信号的分量稀释掉。非 silent 的默认行为不变。
     */
    suspend fun post(
        kind: InboxKind,
        dedupeKey: String,
        title: String,
        body: String,
        route: String? = null,
        focusItemId: String? = null,
        silent: Boolean = false,
    ) {
        runCatching {
            val now = nowMillis()
            when (val decision = InboxRules.onPost(dao.findByKey(dedupeKey)?.toDomain(), now)) {
                is PostDecision.Insert -> dao.insert(
                    InboxMessageEntity(
                        kind = kind.name,
                        dedupeKey = dedupeKey,
                        title = title,
                        body = body,
                        createdAt = now,
                        readAt = if (silent) now else null,
                        deletedAt = null,
                        route = route,
                        focusItemId = focusItemId,
                    ),
                )
                is PostDecision.Refresh ->
                    if (silent) {
                        // 静默重投只刷新内容，不重新置未读（保持已读状态）
                        dao.refreshSilent(decision.id, title, body, route, focusItemId, now)
                    } else {
                        dao.refreshAndReopen(decision.id, title, body, route, focusItemId, now)
                    }
                is PostDecision.RefreshDeleted ->
                    dao.refreshDeleted(decision.id, title, body, route, focusItemId, now)
            }
        }.onFailure { Log.w(TAG, "inbox post failed", it) }
    }

    /**
     * 状态消解：状态型消息（余额偏低）的对应状态解除时把那条消息整个移除。
     * 只在「成功取到数据且不再偏低」的路径上调用——取数失败绝不误删。失败静默。
     */
    suspend fun clearState(dedupeKey: String) {
        runCatching { dao.hardDeleteByKey(dedupeKey) }
            .onFailure { Log.w(TAG, "inbox clearState failed", it) }
    }

    /** 单条标已读（点行）。 */
    suspend fun markRead(id: Long) {
        runCatching { dao.markRead(id, nowMillis()) }
            .onFailure { Log.w(TAG, "inbox markRead failed", it) }
    }

    /** 一键已读。 */
    suspend fun markAllRead() {
        runCatching { dao.markAllRead(nowMillis()) }
            .onFailure { Log.w(TAG, "inbox markAllRead failed", it) }
    }

    /** 按来源标已读（调课差异处置完成后调用——那条事实已经了结，别再亮着）。 */
    suspend fun markKindRead(kind: InboxKind) {
        runCatching { dao.markKindRead(kind.name, nowMillis()) }
            .onFailure { Log.w(TAG, "inbox markKindRead failed", it) }
    }

    /** 批量软删（多选删除）；重投同键不复活，30 天后物理清除。 */
    suspend fun softDelete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        runCatching { dao.softDelete(ids.toList(), nowMillis()) }
            .onFailure { Log.w(TAG, "inbox softDelete failed", it) }
    }

    /**
     * 保留策略落库（[InboxRules.planPurge] 的三条规则）。冷启动跑一次，
     * 全表量级 = 保留上限 + 软删未到期的一小截，廉价。
     */
    suspend fun purge() {
        runCatching {
            val plan = InboxRules.planPurge(dao.all().map(InboxMessageEntity::toDomain), nowMillis())
            if (plan.ids.isNotEmpty()) dao.deleteByIds(plan.ids)
        }.onFailure { Log.w(TAG, "inbox purge failed", it) }
    }

    private companion object {
        const val TAG = "InboxRepo"
    }
}
