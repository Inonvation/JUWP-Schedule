package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.jw.OneClickImport
import edu.jxslu.schedule.data.jw.OneClickResult
import edu.jxslu.schedule.data.jw.ImportSource
import edu.jxslu.schedule.data.jw.ImportParseResult
import edu.jxslu.schedule.data.jw.QiangzhiScheduleParser
import edu.jxslu.schedule.data.jw.SyjxScheduleParser
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.session.AutoSyncRules
import edu.jxslu.schedule.data.session.CasEnsureResult
import edu.jxslu.schedule.data.session.CasSession
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.domain.InboxKind
import edu.jxslu.schedule.domain.InboxKeys
import edu.jxslu.schedule.domain.ScheduleChangeRow
import edu.jxslu.schedule.domain.ScheduleCourseRow
import edu.jxslu.schedule.domain.ScheduleDiff
import edu.jxslu.schedule.domain.SchedulePendingChanges
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException

/**
 * 课表直取与调课检查（DESIGN §4.17，2026-10-04 重立）。
 *
 * 走 [CasSession.fetchHtml]（已存凭证 + OkHttp）依次抓学期理论课表与实验课表两页，
 * 解析用 [QiangzhiScheduleParser.parseFromHtml] / [SyjxScheduleParser.parseFromHtml]——
 * 与教务 WebView 导入**同一套判定算法**（当年为单测写的备用路径），只是传输层换成
 * OkHttp（同 `ScoreSync` 与成绩页的关系）。合成口径直接复用 [OneClickImport.combine]，
 * 产物与 WebView 一键导入同型（`OneClickResult`）。
 *
 * 三个入口、两种用途：
 * - [fetch]：纯读。理论页学期以页面学期下拉为准；请求带了学期时校验
 *   「请求学期 = 返回学期」，不一致直接报错（同 `scripts/fetch_courses.py` 纪律——
 *   拿错学期的数据当基线会刷一屏假差异）；
 * - [importIfEmpty]：**只服务首启**（DESIGN §4.29 同型：登录成功后后台直接导入）。
 *   目标课表非空一律跳过——绝不周期性覆盖用户已编辑的课表；写库走
 *   `ScheduleRepository.importParsedCourses`，基线与教材抓取由既有收口带出；
 * - [check]：调课检查。抓最新教务课表与基线比对，差异落「待处置缓存」并交给
 *   提醒层发通知。基线**不在检查后更新**（这点与 `ExamSync` 相反）：调课基线的
 *   语义是「上次与用户达成一致的状态」，只有合并成功 / 重新导入才推进；
 *   重复打扰由「已阅指纹 + 待处置指纹」双闸挡住。
 *
 * 闸门纪律同成绩/考试：开关关着不发请求、间隔内不抓、失败静默且不落检查时刻、
 * 失败不重试（防撞风控）；两页之间 [AutoSyncRules.interRequestDelayMs] 随机间隔。
 */
class ScheduleSync(
    private val cas: CasSession,
    private val repo: ScheduleRepository,
    private val prefs: DisplayPrefsStore,
    /** 收件箱留痕（DESIGN §4.38）：新差异落待处置缓存的同时入箱。 */
    private val inbox: InboxRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    sealed interface FetchResult {
        /**
         * [result] 与 WebView 一键导入同型；[theoryReliable] / [labReliable] 是两页的
         * 结构可信度（页面骨架在不在）。导入只要求理论页可靠（实验 0 条不是失败），
         * 调课检查要求两页都可靠——实验页结构变了却当「没课」，会把整批实验课报成
         * 「教务删除」。
         */
        data class Ok(
            val result: OneClickResult,
            val term: String,
            val theoryReliable: Boolean,
            val labReliable: Boolean,
        ) : FetchResult

        data class Failed(val reason: String) : FetchResult
    }

    sealed interface AutoImportResult {
        /**
         * [count] 是写库条数，[term] 是数据学期（空 = 页面没给）。
         * [timetableId] 是**实际写入的目标课表**——开学日校准等钩子按它落库，别让调用方用
         * 「apply 时刻的当前课表」近似（中间隔着抓日历的一跳，用户切了课表会写错表）。
         */
        data class Imported(val count: Int, val term: String, val timetableId: Long) : AutoImportResult

        /** 目标课表非空。首启之外的情形一律让位，绝不静默覆盖。 */
        data object Skipped : AutoImportResult

        data class Failed(val reason: String) : AutoImportResult
    }

    sealed interface CheckResult {
        /**
         * [changes] 是需要用户处置的差异（已滤掉「已阅」与「已提醒过未处置」的重复），
         * 为空 = 没动静或只是重复检查。[repeated] = 差异与上次提醒的相同（未处置/已忽略），
         * 提醒层据此不再发通知。
         */
        data class Ok(
            val term: String,
            val changes: List<ScheduleDiff.Change>,
            val repeated: Boolean,
        ) : CheckResult

        data object Skipped : CheckResult

        data class Failed(val reason: String) : CheckResult
    }

    /**
     * 直取两张课表页并解析。[term] 非空时带 `xnxq01id` 请求并校验返回学期；
     * 空 = 教务默认学期（返回页面学期下拉的选中项）。
     */
    suspend fun fetch(term: String? = null): FetchResult {
        when (val ensured = cas.ensureValid()) {
            is CasEnsureResult.Ready -> Unit
            else -> return FetchResult.Failed(ScholarProgressSync.describe(ensured))
        }

        val safeTerm = term?.takeIf { JwUrls.TERM_PATTERN.matches(it) }
        val theoryUrl = if (safeTerm != null) "${JwUrls.SCHEDULE_LIST}&xnxq01id=$safeTerm" else JwUrls.SCHEDULE_LIST
        val theoryHtml = cas.fetchHtml(theoryUrl)
            ?: return FetchResult.Failed("没取到理论课表页（会话可能已失效）")
        val pageTerm = termFromHtml(theoryHtml)
            ?: return FetchResult.Failed("课表页没有学期下拉（教务结构可能变了）")
        if (safeTerm != null && pageTerm != safeTerm) {
            return FetchResult.Failed("教务返回学期（$pageTerm）与请求学期（$safeTerm）不一致")
        }

        val theoryParsed = try {
            QiangzhiScheduleParser.parseFromHtml(theoryHtml)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FetchResult.Failed("理论课表解析失败：${e.message}")
        }
        val theoryReliable = theoryParsed is ImportParseResult.Success || "kbDataTd" in theoryHtml
        if (!theoryReliable) {
            return FetchResult.Failed("理论课表页未识别到课表网格（页面结构可能已变化）")
        }

        delay(AutoSyncRules.interRequestDelayMs())

        val labHtml = cas.fetchHtml(JwUrls.labScheduleUrl(pageTerm))
            ?: return FetchResult.Failed("没取到实验课表页（会话可能已失效）")
        // 实验页同样带学期下拉，必须与理论页一致（2026-10-07 复核补）：教务若忽略
        // 实验页的学期参数、按自己的默认学期渲染，直取会把两个学期的课混在一起比对。
        labTermError(pageTerm, labHtml)?.let { return FetchResult.Failed(it) }
        val labParsed = try {
            SyjxScheduleParser.parseFromHtml(labHtml)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FetchResult.Failed("实验课表解析失败：${e.message}")
        }
        val labReliable = labParsed is ImportParseResult.Success || LAB_TABLE_MARK in labHtml

        val combined = OneClickImport.combine(
            ImportSource(
                label = OneClickImport.THEORY_LABEL,
                courses = (theoryParsed as? ImportParseResult.Success)?.courses.orEmpty(),
                term = pageTerm,
                recognized = true,
            ),
            ImportSource(
                label = OneClickImport.LAB_LABEL,
                courses = (labParsed as? ImportParseResult.Success)?.courses.orEmpty(),
                term = pageTerm,
                recognized = labReliable,
            ),
        )
        return FetchResult.Ok(result = combined, term = pageTerm, theoryReliable = true, labReliable = labReliable)
    }

    /**
     * 首启自动导入：目标课表**为空**才抓并覆盖写库（空表上覆盖与合并等价，
     * 覆盖保证「基线 = 导入内容」）。课表非空一律 [AutoImportResult.Skipped]。
     * 教材抓取由调用方在成功后启动（同手动导入的触发点唯一纪律）。
     */
    suspend fun importIfEmpty(timetableId: Long? = null): AutoImportResult {
        val ttId = timetableId ?: repo.currentTimetableId.first()
        if (repo.timetableCourseCount(ttId) > 0) return AutoImportResult.Skipped

        val fetchResult = fetch()
        val ok = fetchResult as? FetchResult.Ok ?: return AutoImportResult.Failed((fetchResult as FetchResult.Failed).reason)
        // blocked = 两张表都没解析到课程（识别失败或教务真没课）。学期中途不该发生，
        // 首启导入按失败报告，让用户手动导一遍核对
        if (ok.result.blocked != null) return AutoImportResult.Failed(ok.result.blocked)
        // 实验页结构不可信（2026-10-07 复核补）：静默导入会把实验课整批漏掉而用户看不到
        // 分项条数，宁可报失败让用户手动导入一次核对——手动弹窗有「实验课表 0 条 /
        // 未识别到课表」的分项提示，这里没有。
        if (!ok.labReliable) {
            return AutoImportResult.Failed("实验课表页未识别到课表（页面结构可能已变化），请手动导入一次核对")
        }

        val count = repo.importParsedCourses(
            courses = ok.result.courses,
            merge = false,
            timetableId = ttId,
            term = ok.term,
        )
        return AutoImportResult.Imported(count = count, term = ok.term, timetableId = ttId)
    }

    /**
     * 调课检查：抓最新教务课表与基线比对。差异非空时落「待处置缓存」并随
     * [CheckResult.Ok] 交提醒层；指纹与已阅记录或待处置缓存相同的差异不重复上报。
     */
    suspend fun check(force: Boolean = false): CheckResult {
        if (!force) {
            if (!prefs.scheduleAlertEnabled.first()) return CheckResult.Skipped
            if (!AutoSyncRules.shouldAttemptAt(
                    prefs.scheduleCheckMillis(),
                    prefs.scheduleAlertIntervalHours.first(),
                    nowMillis(),
                )
            ) {
                return CheckResult.Skipped
            }
        }

        val ttId = repo.currentTimetableId.first()
        val timetable = repo.timetables.first().firstOrNull { it.id == ttId }
            ?: return CheckResult.Skipped
        // 学期是唯一钥匙（抓取 URL 与基线对齐都靠它）：旧课表没有学期号就不检查，不猜
        val term = timetable.term?.takeIf { JwUrls.TERM_PATTERN.matches(it) }
            ?: return CheckResult.Skipped

        val fetchResult = fetch(term)
        val ok = fetchResult as? FetchResult.Ok ?: return CheckResult.Failed((fetchResult as FetchResult.Failed).reason)
        if (!ok.labReliable) {
            // 实验页结构不可信：硬着头皮比对会把整批实验课报成「教务删除」
            return CheckResult.Failed("实验课表页未识别到课表（页面结构可能已变化）")
        }
        if (ok.result.blocked != null) {
            // 理论页识别成功但 0 条 + 实验 0 条：可能真没课（假期），也可能数据异常。
            // 不动基线也不报差异，等下个周期再看
            return CheckResult.Ok(term = term, changes = emptyList(), repeated = false)
        }

        val baseline = repo.loadScheduleBaseline()
        if (baseline.timetableId != ttId || baseline.term != term) {
            // 首跑 / 换课表 / 换学期：只建基线不通知，防整表刷屏（同 ExamSync 闸门）
            repo.saveScheduleBaseline(ttId, term, ok.result.courses)
            prefs.setScheduleCheckMillis(nowMillis())
            return CheckResult.Ok(term = term, changes = emptyList(), repeated = false)
        }
        // 整类课解析为 0 而基线里有该类课 = 解析器与教务页面脱节的可疑信号，
        // 硬比对会把整类课报成「教务删除」——这轮放弃，不落检查时刻（下个周期自动重试）
        vanishingKindError(ok.result.courses, baseline.courses)?.let { return CheckResult.Failed(it) }
        val changes = ScheduleDiff.detect(baseline.courses.map { it.toCourse() }, ok.result.courses)
        prefs.setScheduleCheckMillis(nowMillis())

        if (changes.isEmpty()) {
            // 无差异 = 待处置缓存里那批差异已不存在（教务改回 / 基线刚被重新导入推进过）。
            // 缓存是派生数据，留着只会让处置页一直挂着已经不存在的变动，而「合并」会把
            // 它们照指纹套到当前课表上（静默删课）——2026-10-07 用户报障的「重新导入后
            // 仍显示有差异 / 与教务对不上」有一半是这里。只清属于本次检查课表的缓存。
            if (prefs.schedulePendingChanges()?.timetableId == ttId) {
                prefs.setSchedulePendingChanges(null)
            }
            return CheckResult.Ok(term = term, changes = emptyList(), repeated = false)
        }
        val fingerprint = ScheduleDiff.fingerprintOf(changes)
        if (fingerprint == prefs.scheduleIgnoredFingerprint() ||
            fingerprint == prefs.schedulePendingChanges()?.fingerprint
        ) {
            // 已忽略 / 已提醒过还没处置：不再重复打扰，待处置缓存原样保留
            return CheckResult.Ok(term = term, changes = emptyList(), repeated = true)
        }
        prefs.setSchedulePendingChanges(
            SchedulePendingChanges(
                timetableId = ttId,
                term = term,
                changes = changes.map { ScheduleChangeRow.of(it) },
                fingerprint = fingerprint,
                savedAt = nowMillis(),
            ),
        )
        // 收件箱留痕（DESIGN §4.38）：去重键 = 差异指纹，与双闸同源——同一批差异
        // 重投只会刷新那一条；合并/忽略后处置页会把这类消息标为已读。
        inbox.post(
            kind = InboxKind.SCHEDULE_CHANGE,
            dedupeKey = InboxKeys.SCHEDULE_PREFIX + fingerprint,
            title = "课程安排有变动",
            body = "教务课表检测到 ${changes.size} 处差异，逐条确认后合并或忽略。",
            route = SubpageScreen.SCHEDULE_CHANGES.name,
        )
        return CheckResult.Ok(term = term, changes = changes, repeated = false)
    }

    companion object {
        /**
         * 实验课表页的骨架标记（与 `SyjxScheduleParser.EXTRACT_JS` 的 container 判据同源）：
         * 页面渲染了周次表骨架 = 「这张表在」，只是本学期没有课块。
         */
        private const val LAB_TABLE_MARK = "qz-weeklyTable"

        /**
         * 从理论课表页 HTML 抽 `select#xnxq01id` 的当前选中项。
         * 先定位到该 select 块再找 selected option——页面可能有多个下拉，
         * 整页搜 `<option selected>` 会串（同 [ExamSync.termFromShell] 的教训）。
         * value 与文本兜底各一次，抽不到返回 null（调用方报失败，不猜学期）。
         */
        internal fun termFromHtml(html: String): String? {
            val block = Regex("(?is)<select[^>]*\\bid\\s*=\\s*[\"']xnxq01id[\"'][^>]*>[\\s\\S]*?</select>")
                .find(html)?.value
                ?: return null
            val selectedTag = Regex("(?is)<option[^>]*selected[^>]*>").find(block) ?: return null
            val value = Regex("""value\s*=\s*["']([^"']+)["']""")
                .find(selectedTag.value)?.groupValues?.get(1)?.trim()
            if (!value.isNullOrBlank() && JwUrls.TERM_PATTERN.matches(value)) return value
            val after = block.substringAfter(selectedTag.value, "").substringBefore("</option>").trim()
            return after.takeIf { it.isNotEmpty() && JwUrls.TERM_PATTERN.matches(it) }
        }

        /**
         * 实验课表的学期校验（2026-10-07 复核补）：实验页也带 `select#xnxq01id`
         * （实测 56 个选项、选中项即服务端渲染的学期），必须与理论页一致——教务若忽略
         * 学期参数、按自己的默认学期渲染实验页，直取就会把两个学期的课混为一批。
         * 抽不到下拉同样视为不可信（结构变了就别猜）。返回 null = 校验通过。
         */
        internal fun labTermError(theoryTerm: String, labHtml: String): String? {
            val labTerm = termFromHtml(labHtml)
                ?: return "实验课表页没有学期下拉（教务结构可能已变化）"
            return if (labTerm == theoryTerm) null
            else "实验课表页返回学期（$labTerm）与理论课表页（$theoryTerm）不一致"
        }

        /**
         * 「整类课解析为 0」的可疑判定（2026-10-07 复核补）：教务页骨架都在（否则
         * fetch 已经失败），但理论 / 实验有一整类课解析为 0 条，而**基线里该类课有货**
         * ——大概率是解析器与页面脱节（改版 / 字段改名）或教务整表异常，硬比对会把
         * 整类课报成「教务删除」，勾选合并就是批量删课。返回人话描述（给提醒层与
         * logcat），正常时 null。
         *
         * 只守「有 → 零」这一侧：基线没有、教务新有的课（0 → N）是正常新增，照报。
         */
        internal fun vanishingKindError(
            remote: List<Course>,
            baselineRows: List<ScheduleCourseRow>,
        ): String? {
            val baseTheory = baselineRows.count { it.kind == CourseKind.Theory.name }
            val baseLab = baselineRows.count { it.kind == CourseKind.Lab.name }
            val remoteTheory = remote.count { it.kind == CourseKind.Theory }
            val remoteLab = remote.count { it.kind == CourseKind.Lab }
            val gone = buildList {
                if (baseTheory > 0 && remoteTheory == 0) add("理论课 ${baseTheory}→0")
                if (baseLab > 0 && remoteLab == 0) add("实验课 ${baseLab}→0")
            }
            if (gone.isEmpty()) return null
            return "教务课表解析异常（${gone.joinToString("、")}），本轮放弃比对，下个周期自动重试"
        }
    }
}
