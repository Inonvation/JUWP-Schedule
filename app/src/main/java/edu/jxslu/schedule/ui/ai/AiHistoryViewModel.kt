package edu.jxslu.schedule.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.data.repo.AiApplyHistoryStore
import edu.jxslu.schedule.data.repo.AiApplyRecord
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.data.repo.toEntry
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「调课记录」页的状态容器（DESIGN §3.25 / §4.39，2026-10-07）。
 *
 * 数据 = [AiApplyHistoryStore]（filesDir JSON，跨会话）——本页是**跨会话**的撤销入口：
 * AI 调课主页面的「撤销」只覆盖本次会话的回执，杀进程就没了；记录页补上长效留痕。
 *
 * **撤销是栈式的**：只允许撤「最新一条未撤销」的记录。撤掉它之后上一条才轮到可撤——
 * 调课是一串累积改动，倒着剥才是安全语义；跳着撤会让后来的改动压在旧快照上，
 * 产生半截还原。状态闸（逐条比指纹、对不上就跳过）继续兜底，跳过条数如实报出。
 *
 * **课表已删除的记录**：永远撤不动（反向写会向不存在的课表塞孤儿行），但也**不挡栈**
 * ——`latestUndoable` 跳过它们，UI 行上标「课表已删除」，用户照样能撤更早的记录。
 */
class AiHistoryViewModel(
    private val repo: ScheduleRepository,
    private val historyStore: AiApplyHistoryStore,
) : ViewModel() {

    val records: StateFlow<List<AiApplyRecord>> = historyStore.state

    /** 现存课表 id；`null` = 首帧未到（此时不启用「已删课表」判定，兜底旧行为）。 */
    val aliveTimetableIds: StateFlow<Set<Long>?> = repo.timetables
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _notice = MutableStateFlow<AiNotice?>(null)
    val notice: StateFlow<AiNotice?> = _notice.asStateFlow()

    /** 撤销事务进行中：双击的第二下被挡在门外（第二遍只会撞指纹闸，写出假报告）。 */
    private var undoing = false

    fun consumeNotice() {
        _notice.value = null
    }

    /** UI 入口：撤销放进 viewModelScope（点完立刻返回也与主页面的写入口径一致）。 */
    fun undoFromUi(record: AiApplyRecord) {
        viewModelScope.launch {
            undo(record)
                .onSuccess { skipped ->
                    _notice.value = AiNotice(
                        if (skipped > 0) "已撤销；$skipped 条你后来改过，未动" else "已撤销，课表回到这次应用之前",
                        NoticeTone.Success,
                    )
                }
                .onFailure { _notice.value = AiNotice("撤销失败：${it.message ?: "请稍后重试"}", NoticeTone.Error) }
        }
    }

    private suspend fun undo(record: AiApplyRecord): Result<Int> {
        if (record.undone) return Result.failure(IllegalStateException("这条记录已经撤销过了"))
        if (undoing) return Result.failure(IllegalStateException("正在处理，请稍候"))
        // 先扣闸再进第一个挂起点：双击的第二下必须在闸位落下前被挡住
        undoing = true
        return try {
            // 用**实时**的现存课表集合判栈（不是屏幕快照）：别的窗口刚删了课表也能及时避开
            val alive = repo.timetableIds()
            if (record.timetableId !in alive) {
                return Result.failure(IllegalStateException("这条记录所属的课表已删除，无法撤销"))
            }
            val latest = AiApplyHistoryStore.latestUndoable(historyStore.state.value, alive)
            if (latest?.id != record.id) return Result.failure(IllegalStateException("只能撤销最近一次应用"))
            // 存档行 → 域对象：kind 认不出的脏条目跳过（与写路径「逐条丢弃」同口径）
            val entries = record.entries.mapNotNull { it.toEntry() }
            val skipped = repo.undoLocalChanges(
                entries = entries,
                overrideIds = record.overrideIds,
                timetableId = record.timetableId,
            )
            historyStore.markUndone(record.id)
            Result.success(skipped)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Result.failure(t)
        } finally {
            undoing = false
        }
    }

    class Factory(
        private val repo: ScheduleRepository,
        private val historyStore: AiApplyHistoryStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AiHistoryViewModel(repo, historyStore) as T
    }
}
