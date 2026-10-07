package edu.jxslu.schedule.data.repo

import android.content.Context
import android.util.Log
import edu.jxslu.schedule.domain.AppliedEntry
import edu.jxslu.schedule.domain.ScheduleCourseRow
import edu.jxslu.schedule.domain.ScheduleDiff
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * AI 调课的应用留痕（DESIGN §4.39，2026-10-07）：每次**成功落库**记一条，`filesDir`
 * 一份 JSON（新记录在顶、上限 [CAP] 条），供「调课记录」页查看与**撤销**。
 *
 * 为什么不是 Room：这层只是「用户做过什么」的旁路账本——语义与收件箱（留痕层）同类，
 * 与业务表没有外键关系；文件坏了按空处理，不影响主链路。选型与 `ScheduleBaselineStore`
 * 一致（filesDir + kotlinx JSON），数据是用户自己课表的派生记录，不加密、随备份走。
 *
 * **撤销是栈式的**：只能撤「最新一条未撤销」的记录（`latestUndoable`）。撤掉它之后，
 * 上一条才轮到可撤——调课是一串累积改动，倒着剥才是安全语义；跳着撤会让后来的改动
 * 压在旧快照上，产生半截还原。状态闸（`ScheduleUndo.plan` 逐条比指纹、对不上就跳过）
 * 仍然兜底：应用之后用户手动改过的行不会被静默吞掉。
 *
 * 记录里的 [AiApplyRecord.entries] 用 [ScheduleCourseRow]（带 id 与自定义时间）存档，
 * 反序列化后能原样重建 `AppliedEntry` 走同一条撤销事务。
 */
class AiApplyHistoryStore(context: Context) {

    private val file = File(context.filesDir, HISTORY_FILE)

    /** 写入串行化：主页与「调课记录」页是两个窗口，并发写不能互相覆盖。 */
    private val writeLock = Mutex()

    // 初始读是一次性小文件（同 ScheduleBaselineStore / UjingHistoryStore 的构造口径）
    private val _state = MutableStateFlow(load())
    val state: StateFlow<List<AiApplyRecord>> = _state.asStateFlow()

    /** 记一条应用（新记录在顶）。写盘落 IO 线程。 */
    suspend fun append(record: AiApplyRecord) {
        write { upsertInto(it, record) }
    }

    /** 标记一条记录已撤销（撤销动作完成后调用；找不到是空操作）。写盘落 IO 线程。 */
    suspend fun markUndone(id: Long) {
        write { markUndoneIn(it, id) }
    }

    /** 按 id 找一条（会话里的回执要核对它的撤销状态）。 */
    fun find(id: Long): AiApplyRecord? = _state.value.firstOrNull { it.id == id }

    private suspend fun write(transform: (List<AiApplyRecord>) -> List<AiApplyRecord>) {
        writeLock.withLock {
            val list = transform(_state.value)
            _state.value = list
            withContext(Dispatchers.IO) {
                runCatching { file.writeText(encode(list)) }
                    // 写盘失败不打断用户操作（内存里仍然可见），但必须留痕可查——
                    // 否则「重启后记录消失」会变成无头案
                    .onFailure { Log.w(TAG, "留痕写盘失败：${it.message}") }
            }
        }
    }

    private fun load(): List<AiApplyRecord> =
        runCatching { decode(file.readText()) }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "AiApplyHistory"

        /** 留痕上限：50 条（与 U净 历史账单同量级），超出截掉最老的。 */
        const val CAP = 50

        private const val HISTORY_FILE = "ai_apply_history.json"

        private val json = Json { ignoreUnknownKeys = true }

        // ---- 纯逻辑（JVM 可测；落盘路径不进单测，同 UjingHistoryStore 口径） ----

        /** 新记录在顶；同 id 去重（不吃掉旧条目）。 */
        fun upsertInto(list: List<AiApplyRecord>, record: AiApplyRecord, cap: Int = CAP): List<AiApplyRecord> =
            (listOf(record) + list.filterNot { it.id == record.id }).take(cap)

        /** 标记已撤销（幂等）。 */
        fun markUndoneIn(list: List<AiApplyRecord>, id: Long): List<AiApplyRecord> =
            list.map { if (it.id == id && !it.undone) it.copy(undone = true) else it }

        /**
         * 可撤销的记录 = 最新一条**未撤销**（列表新在前）。
         *
         * 不存在「跳着撤」：更早的记录要等它上面的都撤掉之后才轮到。
         * [aliveTimetableIds] 非空时跳过**课表已被删除**的记录——它们永远撤不动，
         * 但不该把栈堵死（用户还得能撤更早的记录）；`null` = 未知（不跳过，兜底旧行为）。
         */
        fun latestUndoable(
            list: List<AiApplyRecord>,
            aliveTimetableIds: Set<Long>? = null,
        ): AiApplyRecord? = list.firstOrNull {
            !it.undone && (aliveTimetableIds == null || it.timetableId in aliveTimetableIds)
        }

        /** 序列化（纯函数，round-trip 有单测）。 */
        fun encode(list: List<AiApplyRecord>): String = json.encodeToString(list)

        /** 反序列化；坏数据抛给调用方兜（[load] 已兜成空表）。 */
        fun decode(raw: String): List<AiApplyRecord> = json.decodeFromString(raw)
    }
}

/** 一次应用的留痕。时间显示由 UI 从 [id]（epoch millis）格式化，不存字符串。 */
@Serializable
data class AiApplyRecord(
    val id: Long = 0L,
    val appliedCourses: Int = 0,
    val appliedOverrides: Int = 0,
    val skipped: Int = 0,
    /** 落库时的课表 id：撤销要按它落，不能按「撤销那一刻的当前课表」。 */
    val timetableId: Long = 0L,
    /** 一行摘要（受影响的课程名），列表里显示。 */
    val summary: String = "",
    val entries: List<AiAppliedEntryRow> = emptyList(),
    val overrideIds: List<Long> = emptyList(),
    val undone: Boolean = false,
) {
    /** 合计处数（课表改动 + 日期覆盖）。 */
    val applied: Int get() = appliedCourses + appliedOverrides
}

/** [AppliedEntry] 的可序列化形态（before / after 走 [ScheduleCourseRow]，带 id）。 */
@Serializable
data class AiAppliedEntryRow(
    val kind: String = "",
    val before: ScheduleCourseRow? = null,
    val after: ScheduleCourseRow? = null,
)

/** 落库前：域对象 → 存档行。 */
internal fun AppliedEntry.toRow() = AiAppliedEntryRow(
    kind = kind.name,
    before = before?.let(ScheduleCourseRow::of),
    after = after?.let(ScheduleCourseRow::of),
)

/** 撤销前：存档行 → 域对象；kind 认不出（脏数据）时返回 null，调用方跳过该条。 */
internal fun AiAppliedEntryRow.toEntry(): AppliedEntry? {
    val k = runCatching { ScheduleDiff.ChangeKind.valueOf(kind) }.getOrNull() ?: return null
    return AppliedEntry(
        kind = k,
        before = before?.toCourse(),
        after = after?.toCourse(),
    )
}
