package edu.jxslu.schedule.data.repo

import android.content.Context
import edu.jxslu.schedule.domain.DormRepairSnapshot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 宿舍报修本机快照（DESIGN §4.49）：`filesDir/dorm_repair.json` 一份 JSON。
 *
 * 不建 Room 表：这是「单条在途记录 + 最近 20 条历史」的只读镜像，没有查询/关联需求；
 * 与 `ExamSnapshotStore` / `InternshipSnapshotStore` 同一套选型。文件里只有宿舍号、
 * 故障类型、描述与时间，不存姓名/学号/手机号（解析阶段已丢）。
 *
 * 构造时同步读一次（文件很小，上限 20 条），之后所有写走 [Dispatchers.IO] + [writeMutex]。
 */
class DormRepairStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, SNAPSHOT_FILE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val writeMutex = Mutex()
    private val _snapshot = MutableStateFlow(read())

    val snapshot: StateFlow<DormRepairSnapshot> = _snapshot

    /**
     * 解析结果 + 最近 20 条记录；**UI 侧字段以当前内存为准合并**：
     * 网络请求在飞的时候用户可能刚点过「完成并隐藏」，直接用请求开始时的快照覆盖会把隐藏弄丢。
     * `tracking` 只增不减——用户用过报修一次，之后的 App 打开检查就长期保留（用户选择的能力）。
     */
    suspend fun save(fetched: DormRepairSnapshot) = writeMutex.withLock {
        val current = _snapshot.value
        val next = fetched.copy(
            dismissedIds = (current.dismissedIds + fetched.dismissedIds)
                .distinct().takeLast(MAX_DISMISSED),
            notifiedIds = (current.notifiedIds + fetched.notifiedIds)
                .distinct().takeLast(MAX_NOTIFIED),
            checkedOnce = current.checkedOnce || fetched.checkedOnce,
            tracking = current.tracking || fetched.tracking,
            lastAutoLoginAt = maxOf(current.lastAutoLoginAt, fetched.lastAutoLoginAt),
        )
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(next)) }
        }
        _snapshot.value = next
    }

    /** 用户打开过报修页：之后的 App 打开检查才可以真正发请求。 */
    suspend fun markTracking() = update { it.copy(tracking = true, checkedOnce = true) }

    /**
     * 记一次**自动** CAS 登录尝试的时刻。落盘后跨进程也守住 12 小时窗口；
     * 失败也算一次（下一次自动登录要等窗口过去），手动刷新不受影响。
     */
    suspend fun markAutoLoginAttempt(at: Long) = update { it.copy(lastAutoLoginAt = at) }

    /**
     * 退出学校统一认证时清空：换账号后不能把上一位用户的报修记录留在今日页。
     * 直接删文件；删不掉也把内存清空，至少本进程不再显示。
     */
    suspend fun clear() = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { file.delete() }
        }
        _snapshot.value = DormRepairSnapshot()
    }

    /** 详情页「完成并隐藏」：只影响本机卡片，不改学工状态。 */
    suspend fun dismiss(formUserId: Long) = update { current ->
        current.copy(
            dismissedIds = (current.dismissedIds + formUserId).distinct().takeLast(MAX_DISMISSED),
        )
    }

    private suspend fun update(transform: (DormRepairSnapshot) -> DormRepairSnapshot) =
        writeMutex.withLock {
            val next = transform(_snapshot.value)
            withContext(Dispatchers.IO) {
                runCatching { file.writeText(json.encodeToString(next)) }
            }
            _snapshot.value = next
        }

    private fun read(): DormRepairSnapshot = runCatching {
        if (!file.exists()) return@runCatching DormRepairSnapshot()
        json.decodeFromString<DormRepairSnapshot>(file.readText())
    }.getOrDefault(DormRepairSnapshot())

    private companion object {
        const val SNAPSHOT_FILE = "dorm_repair.json"
        const val MAX_DISMISSED = 50
        const val MAX_NOTIFIED = 50
    }
}
