package edu.jxslu.schedule.data.repo

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一条被免扰时段拦下的通知的**落点描述符**（可序列化）。
 *
 * 三种落点对应通知链既有的三种 contentIntent 构建（见各 Reminder 文件）：
 * - [Kind.MAIN_PLAIN]：裸 `MainActivity`（上课提醒）；
 * - [Kind.MAIN_ROUTE]：`MainActivity + EXTRA_ROUTE`（余额 → 不带路由、落启动页；登录失效 → 账号与登录）；
 * - [Kind.SUBPAGE]：`subpageLaunchIntent(SubpageRequest)`（成绩/考试/选课/调课/U净/骑行/作业）。
 *
 * requestCode 逐条存档——PendingIntent 的身份是「requestCode + filterEquals」，
 * 补发时必须用原值重建，否则两条通知的落点互相改写（历史坑两轮，§3.9）。
 */
@Serializable
data class PendingLaunch(
    val kind: String,
    val requestCode: Int,
    /** [Kind.MAIN_ROUTE] 专用：`ROUTE_ME` 等（旧 `ROUTE_LIFE` 已随生活页删除）。 */
    val route: String? = null,
    /** [Kind.SUBPAGE] 专用：`SubpageScreen.name`。 */
    val screen: String? = null,
    val focusItemId: String? = null,
    val courseName: String? = null,
    val itemId: String? = null,
    /** 成绩/考试/选课/调课/U净 落点附带的 `NEW_TASK | CLEAR_TASK`。 */
    val clearTask: Boolean = false,
) {
    object Kind {
        const val MAIN_PLAIN = "MAIN_PLAIN"
        const val MAIN_ROUTE = "MAIN_ROUTE"
        const val SUBPAGE = "SUBPAGE"
    }

    /** upsert 键里可比较的形态。 */
    internal fun key(): String = listOf(
        kind, requestCode, route.orEmpty(), screen.orEmpty(),
        focusItemId.orEmpty(), courseName.orEmpty(), itemId.orEmpty(),
        clearTask.toString(),
    ).joinToString("|")
}

/**
 * 一条被免扰时段拦下、待补发的通知（[QuietHoursQueue] 的存储单元）。
 *
 * 为什么存结构化描述而不是 Notification 本体：`Parcel.marshall` 跨系统版本不稳定，
 * Notification 反解 contentIntent 不可靠；而本项目全部可拦截通知形态同构
 * （ic_stat_bell + BigTextStyle + autoCancel + category），结构化字段足以无损重建。
 * channel 由补发方按 [channelId] 重建（各 Reminder 的 ensureChannel 是幂等的）。
 */
@Serializable
data class PendingNotification(
    /** 幂等键 = `(tag, notificationId)`：同一条通知被重复拦（周期核对）时替换不堆叠。 */
    val tag: String? = null,
    val notificationId: Int = 0,
    val channelId: String = "",
    /** 原通知的 category（REMINDER / EVENT / ERROR），重建时 setCategory 回去。 */
    val category: String? = null,
    val title: String = "",
    /** 正文与 BigText 同文（原链路口径）。 */
    val text: String = "",
    /**
     * 时刻型提醒的事件时刻（上课时刻 / 截止时刻 / 免费时长结束 / 选课轮次事件）；
     * `null` = 事实型（成绩/考试/调课/余额/U净/登录失效），永不过期。
     * 补发时事件已过去超过 [EVENT_EXPIRY_MS] 的时刻型条目静默丢弃（2026-10-07 拍板）。
     */
    val eventAt: Long? = null,
    /** 入队时刻（仅留痕与排序；补发闹钟由 Gate 另排）。 */
    val enqueuedAt: Long = 0L,
    /**
     * 拦截时「通知强提醒」是否开着——只用作「这条提醒属于会震的三类」的标记；
     * 补发时**按补发时刻**的开关值重读再决定震不震（用户可能在免扰期间改开关）。
     */
    val vibrate: Boolean = false,
    val launch: PendingLaunch? = null,
) {
    /** upsert 幂等键（tag 为 null 时也要求可比较）。 */
    internal fun key(): String = "${tag.orEmpty()}|$notificationId"
}

/**
 * 免扰时段的通知推迟队列（DESIGN §3.7）：`filesDir` 一份 JSON，上限 [CAP] 条。
 *
 * 选型与 `AiApplyHistoryStore` / `ScheduleBaselineStore` 同口径：这层是通知的
 * 「待办暂存」，与业务表无外键关系，文件坏了按空处理（丢的是待补发通知，不是数据）。
 * 写入串行化用 Mutex——拦截可能来自多个 Worker/Receiver 上下文。
 */
class QuietHoursQueue(file: File) {

    constructor(context: Context) : this(File(context.filesDir, QUEUE_FILE))

    private val file: File = file

    private val writeLock = Mutex()

    // 构造时一次性小文件读（同 AiApplyHistoryStore 口径）
    private val _state = MutableStateFlow(load())
    val state: StateFlow<List<PendingNotification>> = _state.asStateFlow()

    /** 入队 / 替换（同幂等键覆盖，新条目在顶）。写盘落 IO。返回入队后的全表。 */
    suspend fun enqueue(entry: PendingNotification): List<PendingNotification> {
        write { upsertInto(it, entry) }
        return _state.value
    }

    /** 全表替换（补发完成后按 flushPlan 写回剩余条目）。 */
    suspend fun replaceAll(entries: List<PendingNotification>) {
        write { entries }
    }

    /**
     * 锁内原子移除给定幂等键的条目（flush 用）：移除判定与写入在同一把 Mutex 里，
     * 「读剩余 → 写回」之间新入队的条目不会被覆盖掉（那是整表 replaceAll 的竞态面）。
     * 返回移除的条目数（供日志）。
     */
    suspend fun removeAllKeys(keys: Set<String>): Int {
        var removed = 0
        write { list ->
            val (drop, keep) = list.partition { it.key() in keys }
            removed = drop.size
            keep
        }
        return removed
    }

    private suspend fun write(transform: (List<PendingNotification>) -> List<PendingNotification>) {
        writeLock.withLock {
            val list = transform(_state.value)
            _state.value = list
            withContext(Dispatchers.IO) {
                runCatching { file.writeText(encode(list)) }
                    // 写盘失败不打断链路（内存里仍正确），但必须留痕可查
                    .onFailure { Log.w(TAG, "免扰队列写盘失败：${it.message}") }
            }
        }
    }

    private fun load(): List<PendingNotification> =
        runCatching { decode(file.readText()) }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "QuietHoursQueue"

        /** 队列上限：拦截场景一天撑死几十条周期核对，30 足够，超出截最老。 */
        const val CAP = 30

        /**
         * 时刻型提醒的可补发窗口：事件已过去超过 10 分钟的，补发就是假信息
         * （「10 分钟后上课」在早上 7 点补发是误导），静默丢弃。
         */
        const val EVENT_EXPIRY_MS = 10 * 60 * 1000L

        private const val QUEUE_FILE = "quiet_hours_queue.json"

        private val json = Json { ignoreUnknownKeys = true }

        // ---- 纯逻辑（JVM 可测；落盘路径不进单测，同 UjingHistoryStore 口径） ----

        /** 新条目在顶；同幂等键替换（周期核对重拦同一条不堆叠）；超 [cap] 截最老。 */
        fun upsertInto(
            list: List<PendingNotification>,
            entry: PendingNotification,
            cap: Int = CAP,
        ): List<PendingNotification> =
            (listOf(entry) + list.filterNot { it.key() == entry.key() }).take(cap)

        /**
         * 补发计划（纯函数）：时刻型（eventAt 非空）且事件已过去超过 [EVENT_EXPIRY_MS]
         * 的丢弃，其余（含全部事实型）照发。返回待发列表与丢弃数（丢弃数供日志留痕）。
         */
        fun flushPlan(
            entries: List<PendingNotification>,
            now: Long,
            expiryMs: Long = EVENT_EXPIRY_MS,
        ): Pair<List<PendingNotification>, Int> {
            val (keep, expired) = entries.partition { e ->
                e.eventAt == null || e.eventAt + expiryMs > now
            }
            return keep to expired.size
        }

        /** 序列化（纯函数，round-trip 有单测）。 */
        fun encode(list: List<PendingNotification>): String =
            json.encodeToString(ListSerializer(PendingNotification.serializer()), list)

        /** 反序列化；坏数据抛给调用方兜（[load] 已兜成空表）。 */
        fun decode(raw: String): List<PendingNotification> =
            json.decodeFromString(ListSerializer(PendingNotification.serializer()), raw)
    }
}
