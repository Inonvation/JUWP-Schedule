package edu.jxslu.schedule.domain

/**
 * AI 调课「撤销」的反向规划（DESIGN §4.39，2026-10-06）。
 *
 * 写入时 `ScheduleRepository.applyChangeSet` 会把**每条真正落库的改动**记成一条
 * [AppliedEntry]；撤销就是把它们**倒序**反向执行。规划与执行分开：
 * [plan] 是纯函数（JVM 可测），仓储只负责按 [Action] 落库——
 * 这样「撤销到底该做什么」有单测钉住，不必起一个 Room 实例。
 *
 * 为什么倒序：同门课被连续改两次时（先 modify 再 remove），正序撤会把中间态当成终态。
 *
 * **状态闸**（2026-10-06 复核补）：[AppliedEntry] 里的 before/after 是**应用那一刻**的快照。
 * 应用之后用户完全可能又手动改了那门课——不加校验地按快照覆盖，会把用户后来的修改
 * **静默吞掉**（UI 只承诺「回到应用前」，用户不会预期自己的编辑一起没了）。
 * 所以每条都要拿**当前行**比一次指纹，对不上就跳过并把条数报给 UI。
 * 这与写路径「指纹匹配不上就跳过而不是硬套」是同一条纪律。
 */
object ScheduleUndo {

    /** 撤销一个 [AppliedEntry] 需要的动作。 */
    sealed interface Action {
        /** 删掉写入的那一行（对应 ADDED）。 */
        data class Delete(val id: Long) : Action

        /** 用这一行覆盖回去（对应 MODIFIED / REMOVED；带原 id，Room 会按 id 覆盖/插回）。 */
        data class Upsert(val course: Course) : Action
    }

    /**
     * 一次撤销的计划。[skipped] 是因为「行已被改动 / id 已被占用」而**放弃**的条数——
     * UI 要据此提示，不能让用户以为全撤回来了。
     */
    data class Plan(
        val actions: List<Action>,
        val skipped: Int,
    ) {
        val isEmpty: Boolean get() = actions.isEmpty()
    }

    /**
     * 把「实际落库的改动」翻译成反向动作，**倒序**，并逐条过状态闸。
     *
     * [current] 是**当前**该课表的全量课程（事务内读一次）。缺反向信息的脏条目直接跳过，
     * 不抛错——那是脏数据，撤销其余部分比整批失败更有用。
     */
    fun plan(entries: List<AppliedEntry>, current: List<Course>): Plan {
        val actions = mutableListOf<Action>()
        var skipped = 0
        entries.asReversed().forEach { entry ->
            val action = when (entry.kind) {
                ScheduleDiff.ChangeKind.ADDED -> {
                    val after = entry.after ?: return@forEach
                    val row = current.firstOrNull { it.id == after.id }
                    when {
                        // 已经被删掉了：无事可做，也不算跳过
                        row == null -> null
                        // 用户后来又改过它：撤了会把用户的修改一起删掉，放弃
                        ScheduleDiff.fingerprint(row) != ScheduleDiff.fingerprint(after) -> {
                            skipped++
                            null
                        }

                        else -> Action.Delete(after.id)
                    }
                }

                ScheduleDiff.ChangeKind.MODIFIED -> {
                    val before = entry.before ?: return@forEach
                    val after = entry.after
                    val row = current.firstOrNull { it.id == before.id }
                    when {
                        // 应用后又被改过（或已被删）：不再按快照硬覆盖
                        row == null || after == null ||
                            ScheduleDiff.fingerprint(row) != ScheduleDiff.fingerprint(after) -> {
                            skipped++
                            null
                        }

                        else -> Action.Upsert(before)
                    }
                }

                ScheduleDiff.ChangeKind.REMOVED -> {
                    val before = entry.before ?: return@forEach
                    when {
                        // id 被占用了（带 id 的恢复导入 / 又插了一行）：不覆盖别人
                        current.any { it.id == before.id } -> {
                            skipped++
                            null
                        }
                        // 用户已把同门课加回来（新 id）：再插一遍会出现两行同课。
                        // 与写路径 ADDED 的 mergeKey 防重同一把钥匙，别绕过
                        current.any { it.mergeKey() == before.mergeKey() } -> {
                            skipped++
                            null
                        }

                        else -> Action.Upsert(before)
                    }
                }
            }
            if (action != null) actions += action
        }
        return Plan(actions, skipped)
    }
}

/**
 * 一条**真正落库**的课表改动，带撤销所需的全部信息（DESIGN §4.39）。
 *
 * [before] 是改前那一行（MODIFIED / REMOVED 有；ADDED 为 null）；
 * [after] 是改后那一行（MODIFIED / ADDED 有；REMOVED 为 null），ADDED 时 `id` 是写入后的自增 id。
 */
data class AppliedEntry(
    val kind: ScheduleDiff.ChangeKind,
    val before: Course? = null,
    val after: Course? = null,
)
