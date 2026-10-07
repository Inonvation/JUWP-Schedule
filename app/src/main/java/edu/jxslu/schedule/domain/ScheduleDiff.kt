package edu.jxslu.schedule.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 调课检测的纯逻辑（DESIGN §4.17，2026-10-04 重立）：「教务课表 vs 基线」的差异。
 *
 * 基线的语义是「上次与教务达成一致时的课表状态」（导入写库成功 / 合并成功时推进，
 * 见 `ScheduleRepository.refreshBaseline`），不是教务快照——用户手动改课不影响它，
 * 所以 `detect(基线, 教务)` 的结果纯粹是「教务那边变了什么」。
 *
 * 三类变更与配对规则：
 * - 指纹完全一致的条目两两抵消；
 * - 余集里同名同 kind 恰好「一删一增」的一对合并成 [ChangeKind.MODIFIED]——
 *   「大学物理：周三 3-4 节 → 周五 1-2 节」是一条调整而不是一删一增两条；
 *   多删多增一律不配对（同名多块课部分变动时，排序配对会错配，宁可拆开给人看）；
 * - 剩下的是纯 [ChangeKind.ADDED] / [ChangeKind.REMOVED]。
 *
 * 指纹只含教务提供的字段（name/teacher/position/day/节次/周次/kind）。
 * `colorIndex` / `remark` / 自定义时间是本机数据，教务不知道它们，参与比对会让
 * 每次检查都报假差异。
 */
object ScheduleDiff {

    enum class ChangeKind { ADDED, REMOVED, MODIFIED }

    /**
     * 一条课表差异。[MODIFIED] 时 [old] 是基线里的原条目、[new] 是教务的新条目；
     * [ADDED] 只填 [new]；[REMOVED] 只填 [old]。
     */
    data class Change(
        val kind: ChangeKind,
        val old: Course? = null,
        val new: Course? = null,
    )

    /**
     * 教务指纹：全部教务字段的稳定序列。**改任何参与字段都会换指纹**，所以它既是
     * 「条目相等」判据，也是已阅指纹的组成单元。
     */
    fun fingerprint(course: Course): String = listOf(
        course.name,
        course.teacher,
        course.position,
        course.day.toString(),
        course.startSection.toString(),
        course.endSection.toString(),
        course.weeks.sorted().joinToString(","),
        course.kind.name,
    ).joinToString("|")

    /** 配对用的身份键：同一门课的多个时段同属一组（时间挪动发生在组内）。 */
    fun identity(course: Course): String = course.name + "|" + course.kind.name

    /**
     * 基线 vs 教务的差异。无序多重集合比较：顺序抖动不产生假差异。
     * 双侧先滤掉考试条目——教务课表页抓不到考试，基线里却可能有（手动导进来的），
     * 不滤会把每一场考试都报成「教务删除」。
     */
    fun detect(baseline: List<Course>, remote: List<Course>): List<Change> {
        val base = baseline.filter { it.kind != CourseKind.Exam }
        val fromRemote = remote.filter { it.kind != CourseKind.Exam }
        val baseFps = base.map(::fingerprint).toHashSet()
        val remoteFps = fromRemote.map(::fingerprint).toHashSet()

        val removed = base.filter { fingerprint(it) !in remoteFps }
        val added = fromRemote.filter { fingerprint(it) !in baseFps }

        val changes = mutableListOf<Change>()
        val consumedAdded = mutableSetOf<Course>()
        // 同名同 kind 组内恰好一删一增 → 配成 MODIFIED。数量不等时不配对（会错配）。
        removed.groupBy(::identity).forEach { _, group ->
            if (group.size == 1) {
                val old = group.single()
                val mates = added.filter { identity(it) == identity(old) && it !in consumedAdded }
                if (mates.size == 1) {
                    consumedAdded += mates.single()
                    changes += Change(ChangeKind.MODIFIED, old = old, new = mates.single())
                    return@forEach
                }
                changes += Change(ChangeKind.REMOVED, old = old)
            } else {
                group.forEach { changes += Change(ChangeKind.REMOVED, old = it) }
            }
        }
        added.filter { it !in consumedAdded }
            .forEach { changes += Change(ChangeKind.ADDED, new = it) }

        return changes.sortedWith(
            compareBy(
                // 调整最关键（直接影响上课时间），其次删除，最后新增
                { when (it.kind) {
                    ChangeKind.MODIFIED -> 0
                    ChangeKind.REMOVED -> 1
                    ChangeKind.ADDED -> 2
                } },
                { (it.old ?: it.new)?.name.orEmpty() },
                { (it.old ?: it.new)?.day ?: 0 },
                { (it.old ?: it.new)?.startSection ?: 0 },
            ),
        )
    }

    /**
     * 一组差异的稳定指纹（已阅记录用）：把每条变更序列化后排序拼接。
     * 「忽略」写它，「合并」也写（本次展示过即算阅）；下次检查的指纹与之相同
     * 就不再重复提醒，教务再出新差异才会重新弹。
     */
    fun fingerprintOf(changes: List<Change>): String = changes.map { change ->
        when (change.kind) {
            ChangeKind.MODIFIED -> "M:" + fingerprint(change.old!!) + ">" + fingerprint(change.new!!)
            ChangeKind.ADDED -> "A:" + fingerprint(change.new!!)
            ChangeKind.REMOVED -> "R:" + fingerprint(change.old!!)
        }
    }.sorted().joinToString("\n")

    /**
     * 一条教务差异是否与用户的本地修改冲突。冲突的条目在处置页默认不勾——
     * 采纳它会覆盖用户自己改过的内容，由用户逐条裁决。
     *
     * 判据（基线里的条目在当前课表是否原样还在）：
     * - REMOVED / MODIFIED：当前课表里已经找不到指纹一致的条目 = 用户改过或删过它；
     * - ADDED：当前课表里已有同门课（同名同 kind）= 用户自己加过，再导一份会重复。
     *   能出现在 ADDED 里说明基线没有它（指纹抵消过了），所以当前课表里的同名条目
     *   必是用户手动加的。
     */
    fun isConflicted(change: Change, current: List<Course>): Boolean {
        val cur = current.filter { it.kind != CourseKind.Exam }
        return when (change.kind) {
            ChangeKind.REMOVED ->
                cur.none { fingerprint(it) == fingerprint(change.old!!) }

            ChangeKind.MODIFIED ->
                cur.none { fingerprint(it) == fingerprint(change.old!!) }

            ChangeKind.ADDED ->
                cur.any { identity(it) == identity(change.new!!) }
        }
    }

    /** 一批差异里有多少条与本地修改冲突（处置页汇总文案用）。 */
    fun countConflicts(changes: List<Change>, current: List<Course>): Int =
        changes.count { isConflicted(it, current) }

    /**
     * **预览用**（DESIGN §3.25 方案 B）：把勾选的改动套到课程列表上，返回「应用后」的列表，
     * **不落库**。匹配口径与 `ScheduleRepository.applyChangeSet` 完全一致（按指纹），
     * 匹配不上的条目跳过——预览必须如实反映「应用会发生什么」，不能比真应用更乐观。
     *
     * 本机字段（配色 / 备注）与写库同样保留原行；新增条目用负数 id 占位（预览不落库，
     * 真应用时才由 Room 分配 id 与配色）。
     */
    /**
     * 真应用时对 `new` 的收口：保留本机字段（id / 配色 / 备注），并按需清掉自定义时间。
     *
     * **预览与真应用必须共用这一份**（2026-10-06 复核发现）：两边各写一套时，
     * `preview` 保留了自定义时间而落库把它清了 —— 用户批准的预览与实际结果不一致。
     *
     * 清自定义时间的条件收窄成「**时间真的变了**」：只改教室 / 教师时，
     * 那门课的自定义上下课时间仍然有效，不该被顺手抹掉。
     */
    fun appliedCopy(new: Course, target: Course): Course {
        val timeChanged = new.day != target.day ||
            new.startSection != target.startSection ||
            new.endSection != target.endSection
        return new.copy(
            id = target.id,
            colorIndex = target.colorIndex,
            remark = target.remark,
            isCustomTime = if (timeChanged) false else target.isCustomTime,
            customStartTime = if (timeChanged) null else target.customStartTime,
            customEndTime = if (timeChanged) null else target.customEndTime,
        )
    }

    fun preview(
        courses: List<Course>,
        changes: List<Change>,
        accepted: Set<Int>,
    ): List<Course> {
        var current = courses
        accepted.sorted().forEach { index ->
            val change = changes.getOrNull(index) ?: return@forEach
            when (change.kind) {
                ChangeKind.REMOVED -> {
                    val old = change.old ?: return@forEach
                    val target = current.firstOrNull { fingerprint(it) == fingerprint(old) }
                        ?: return@forEach
                    current = current.filterNot { it.id == target.id }
                }

                ChangeKind.MODIFIED -> {
                    val old = change.old ?: return@forEach
                    val new = change.new ?: return@forEach
                    val target = current.firstOrNull { fingerprint(it) == fingerprint(old) }
                        ?: return@forEach
                    current = current.map {
                        if (it.id == target.id) appliedCopy(new, target) else it
                    }
                }

                ChangeKind.ADDED -> {
                    val course = change.new ?: return@forEach
                    if (current.any { it.mergeKey() == course.mergeKey() }) return@forEach
                    current = current + course.copy(id = Long.MIN_VALUE + index)
                }
            }
        }
        return current
    }
}

/**
 * [Course] 的可序列化影子：基线快照（filesDir JSON）、待处置差异（DataStore JSON）
 * 与 AI 调课的应用留痕（§4.39 `AiApplyHistoryStore`）共用这一套，字段一一对应、
 * 全带默认值——旧文件缺字段时降级而不是解码失败。
 */
@Serializable
data class ScheduleCourseRow(
    /**
     * 行 id：差异比对走 [ScheduleDiff.fingerprint]（不含 id），但 AI 调课留痕的**撤销**
     * 要按 id 删 / 覆盖（`ScheduleUndo.Action`），少了它就撤不回正确的行。
     */
    val id: Long = 0,
    val name: String = "",
    val teacher: String = "",
    val position: String = "",
    val day: Int = 0,
    val startSection: Int = 0,
    val endSection: Int = 0,
    val weeks: List<Int> = emptyList(),
    val kind: String = "theory",
    val colorIndex: Int = 0,
    val remark: String = "",
    /** 自定义时间三件套：本机字段、不进指纹，但撤销必须原样还原（否则撤完时间被清）。 */
    val isCustomTime: Boolean = false,
    val customStartTime: String? = null,
    val customEndTime: String? = null,
) {
    fun toCourse(): Course = Course(
        id = id,
        name = name,
        teacher = teacher,
        position = position,
        day = day,
        startSection = startSection,
        endSection = endSection,
        weeks = weeks.toSet(),
        isCustomTime = isCustomTime,
        customStartTime = customStartTime,
        customEndTime = customEndTime,
        colorIndex = colorIndex,
        kind = runCatching { CourseKind.valueOf(kind) }.getOrDefault(CourseKind.Theory),
        remark = remark,
    )

    companion object {
        fun of(course: Course) = ScheduleCourseRow(
            id = course.id,
            name = course.name,
            teacher = course.teacher,
            position = course.position,
            day = course.day,
            startSection = course.startSection,
            endSection = course.endSection,
            weeks = course.weeks.sorted(),
            kind = course.kind.name,
            colorIndex = course.colorIndex,
            remark = course.remark,
            isCustomTime = course.isCustomTime,
            customStartTime = course.customStartTime,
            customEndTime = course.customEndTime,
        )
    }
}

/** [ScheduleDiff.Change] 的可序列化形态。kind 取 [ScheduleDiff.ChangeKind] 的名字。 */
@Serializable
data class ScheduleChangeRow(
    val kind: String = "",
    val old: ScheduleCourseRow? = null,
    val new: ScheduleCourseRow? = null,
) {
    fun toChange(): ScheduleDiff.Change? {
        val k = runCatching { ScheduleDiff.ChangeKind.valueOf(kind) }.getOrNull() ?: return null
        return when (k) {
            ScheduleDiff.ChangeKind.MODIFIED -> {
                val o = old?.toCourse() ?: return null
                val n = new?.toCourse() ?: return null
                ScheduleDiff.Change(k, o, n)
            }
            ScheduleDiff.ChangeKind.ADDED -> ScheduleDiff.Change(k, new = new?.toCourse() ?: return null)
            ScheduleDiff.ChangeKind.REMOVED -> ScheduleDiff.Change(k, old = old?.toCourse() ?: return null)
        }
    }

    companion object {
        fun of(change: ScheduleDiff.Change) = ScheduleChangeRow(
            kind = change.kind.name,
            old = change.old?.let(ScheduleCourseRow::of),
            new = change.new?.let(ScheduleCourseRow::of),
        )
    }
}

/**
 * 待处置差异缓存（`ScheduleSync.check` 写入，处置页 / 课表 hub 入口行 / 提醒层读）。
 * [fingerprint] 是 [ScheduleDiff.fingerprintOf] 的结果——它同时是「已提醒」的记录，
 * 与已阅指纹或本缓存指纹相同的差异不再重复发通知。
 */
@Serializable
data class SchedulePendingChanges(
    val timetableId: Long = 0,
    val term: String = "",
    val changes: List<ScheduleChangeRow> = emptyList(),
    val fingerprint: String = "",
    val savedAt: Long = 0,
) {
    fun toChanges(): List<ScheduleDiff.Change> = changes.mapNotNull { it.toChange() }

    fun encode(): String = format.encodeToString(this)

    companion object {
        private val format = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(text: String): SchedulePendingChanges? =
            runCatching { format.decodeFromString<SchedulePendingChanges>(text) }.getOrNull()
    }
}
