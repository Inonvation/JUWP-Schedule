package edu.jxslu.schedule.domain

import kotlinx.serialization.Serializable

/**
 * 宿舍报修进度（DESIGN §4.49）：学工 office 表单引擎里「个人提交记录」的只读镜像。
 *
 * 只挑用户需要看的字段落盘：宿舍/故障/描述、提交与完成时间、当前节点和流程节点。
 * **姓名、学号、院系、班级、手机号一律不解析、不落盘、不上屏**——它们在响应的
 * `aprvSummary` 里，解析时直接丢弃。
 */
@Serializable
data class DormRepairRecord(
    /** 学工侧这条提交记录的主键（列表接口的 `id` / `formUserId`）。 */
    val formUserId: Long,
    val formId: Long = 0,
    val title: String = "宿舍报修",
    /** 审批状态原文（`aprvStatusType`：待处理 / 已通过 / 已拒绝 / 已撤销 / 正在处理）。 */
    val approvalStatus: String = "",
    /** 审批状态 id（1 待处理 / 5 正在处理 / 2 已通过 / -1 已拒绝 / -2 已撤销）。 */
    val approvalStatusId: Int = 0,
    /** 报修表单里的「当前状态」字段（待处理 / 已派单 / 已完成）。 */
    val repairStatus: String = "",
    /** 报修表单里的「宿舍信息」，没有时退回「宿舍」。 */
    val dorm: String = "",
    /** 报修表单里的「故障类型」。 */
    val fault: String = "",
    /** 报修表单里的「详情描述」。 */
    val description: String = "",
    /** 提交时间（列表 `inserttime`，epoch millis）。 */
    val submittedAt: Long = 0,
    /** 最后更新时间（列表 `updatetime`，epoch millis）。 */
    val updatedAt: Long = 0,
    /** 服务端给的完成时间（表单「最终完成时间」，解析不出来为 null）。 */
    val finishedAt: Long? = null,
    /** 本机第一次看到这条记录已完成的时间；用于服务端没给完成时间时算 3 天保留期。 */
    val finishedSeenAt: Long? = null,
    val currentApprovers: List<String> = emptyList(),
    val flowNodes: List<DormRepairFlowNode> = emptyList(),
)

@Serializable
data class DormRepairFlowNode(
    val title: String,
    val nodeUniqueId: String = "",
    val approvers: List<String> = emptyList(),
    /** 当前停在这一节点（由列表的 `currentApproveUserList[].nodeUniqueId` 匹配得出）。 */
    val current: Boolean = false,
)

/**
 * 本机快照（`filesDir/dorm_repair.json`）。
 *
 * - [records] 只存列表接口最近 20 条，个人字段已在解析时丢掉；
 * - [dismissedIds] = 用户在详情页点过「完成并隐藏」的记录，今日页不再渲染；
 * - [notifiedIds] = 已经为它发过「已完成」通知/入箱的记录，防重复；
 * - [checkedOnce] / [tracking] 是「打开 App 时要不要查」的闸门：
 *   首装/升级后查一次建基线；只有用户用过报修（打开过报修页或有在途记录）才持续查，
 *   避免没用过这个功能的人每次冷启动都白打一次接口。
 */
@Serializable
data class DormRepairSnapshot(
    val fetchedAt: Long = 0,
    val records: List<DormRepairRecord> = emptyList(),
    val dismissedIds: List<Long> = emptyList(),
    val notifiedIds: List<Long> = emptyList(),
    val checkedOnce: Boolean = false,
    val tracking: Boolean = false,
    /**
     * 上次**自动**尝试 CAS 登录的时刻（epoch millis）。落盘是为了跨进程也守住
     * 「12 小时最多自动登录一次」——只在内存里的话，进程被杀后每次冷启动都会再登一次。
     */
    val lastAutoLoginAt: Long = 0,
)

/** 今日页卡片 / 详情页共用的纯规则（JVM 可测）。 */
object DormRepairRules {

    /** 完成后卡片保留 3 天（用户口径 2026-10-19）。 */
    const val RETAIN_FINISHED_MS: Long = 3L * 24 * 60 * 60 * 1000

    const val APPROVAL_PENDING = 1
    const val APPROVAL_PROCESSING = 5
    const val APPROVAL_APPROVED = 2
    const val APPROVAL_REJECTED = -1
    const val APPROVAL_REVOKED = -2

    const val REPAIR_DONE = "已完成"

    /**
     * 是否已结束：表单「当前状态」= 已完成，或审批走到终点（已通过 / 已拒绝 / 已撤销）。
     *
     * 审批通过（`2`）就是终点：流程最后一个节点是「学生核实是否完成修理」，走到那里
     * 说明维修已办完。表单字段可能来不及改成「已完成」（宿管口径），不能因此让卡片
     * 永远停在「已派单」；详情页会把两者都列出来，用户能看出差异。
     */
    fun isFinished(record: DormRepairRecord): Boolean =
        record.repairStatus == REPAIR_DONE ||
            record.approvalStatusId == APPROVAL_APPROVED ||
            record.approvalStatusId == APPROVAL_REJECTED ||
            record.approvalStatusId == APPROVAL_REVOKED

    fun finishedAtOf(record: DormRepairRecord): Long? =
        record.finishedAt ?: record.finishedSeenAt

    /** 今日页该不该渲染这条记录（隐藏 + 完成 3 天两道闸）。 */
    fun shouldShow(record: DormRepairRecord, snapshot: DormRepairSnapshot, now: Long): Boolean {
        if (record.formUserId in snapshot.dismissedIds) return false
        if (!isFinished(record)) return true
        val at = finishedAtOf(record) ?: snapshot.fetchedAt
        // 时间未知（0）时先显示，等下一次同步把时间补上，不凭 0 直接判定过期
        if (at <= 0) return true
        return now - at <= RETAIN_FINISHED_MS
    }

    fun visibleRecords(snapshot: DormRepairSnapshot, now: Long): List<DormRepairRecord> =
        snapshot.records.filter { shouldShow(it, snapshot, now) }

    /**
     * 今日页卡片展示哪一条：**有在途优先**（最新提交的那条），
     * 没有在途才退回最新一条已完成且未过 3 天的。
     */
    fun cardRecord(snapshot: DormRepairSnapshot, now: Long): DormRepairRecord? {
        val visible = visibleRecords(snapshot, now)
        visible.filterNot(::isFinished).maxByOrNull { it.submittedAt }?.let { return it }
        return visible.filter(::isFinished).maxByOrNull { finishedAtOf(it) ?: it.updatedAt }
    }

    fun find(snapshot: DormRepairSnapshot, formUserId: Long): DormRepairRecord? =
        snapshot.records.firstOrNull { it.formUserId == formUserId }

    /**
     * 卡片/详情页的状态主文案：拒绝/撤销优先，其次审批通过（= 流程终点），
     * 再次表单当前状态（待处理 / 已派单 / 已完成），最后审批原文。
     */
    fun statusLabel(record: DormRepairRecord): String = when {
        record.approvalStatusId == APPROVAL_REJECTED -> "已拒绝"
        record.approvalStatusId == APPROVAL_REVOKED -> "已撤销"
        record.approvalStatusId == APPROVAL_APPROVED -> "已完成"
        record.repairStatus.isNotBlank() -> record.repairStatus
        else -> approvalStatusLabel(record).ifBlank { "待处理" }
    }

    fun approvalStatusLabel(record: DormRepairRecord): String = when (record.approvalStatusId) {
        APPROVAL_PENDING -> "待处理"
        APPROVAL_PROCESSING -> "正在处理"
        APPROVAL_APPROVED -> "已通过"
        APPROVAL_REJECTED -> "已拒绝"
        APPROVAL_REVOKED -> "已撤销"
        else -> record.approvalStatus
    }

    fun currentNode(record: DormRepairRecord): DormRepairFlowNode? =
        record.flowNodes.firstOrNull { it.current }

    fun toneOf(record: DormRepairRecord): DormRepairTone = when {
        record.approvalStatusId == APPROVAL_REJECTED -> DormRepairTone.Error
        record.approvalStatusId == APPROVAL_REVOKED -> DormRepairTone.Neutral
        isFinished(record) -> DormRepairTone.Success
        else -> DormRepairTone.Primary
    }

    /**
     * 这次同步里「刚刚完成」的记录：**只有上次同一条还没结束、这次结束了**才算，
     * 首跑（上次没有同 id 记录）一律只建基线，不通知。
     * 已被 [DormRepairSnapshot.notifiedIds] 记过的也不再算。
     */
    fun newlyFinished(
        previous: DormRepairSnapshot,
        current: List<DormRepairRecord>,
    ): List<DormRepairRecord> = current.filter { record ->
        if (!isFinished(record)) return@filter false
        if (record.formUserId in previous.notifiedIds) return@filter false
        val old = find(previous, record.formUserId) ?: return@filter false
        !isFinished(old)
    }

    /** 合并一条新记录：已完成但服务端没给时间时，把「本机第一次看到完成」的时间钉住。 */
    fun mergeRecord(
        incoming: DormRepairRecord,
        previous: DormRepairRecord?,
        now: Long,
    ): DormRepairRecord {
        if (!isFinished(incoming)) return incoming.copy(finishedSeenAt = null)
        val seen = if (
            previous != null &&
            previous.formUserId == incoming.formUserId &&
            isFinished(previous)
        ) {
            previous.finishedSeenAt ?: now
        } else {
            now
        }
        return incoming.copy(finishedSeenAt = seen)
    }
}

/** 状态语气（UI 映射到 [edu.jxslu.schedule.ui.common.HeroTone]）。 */
enum class DormRepairTone { Primary, Neutral, Success, Error }
