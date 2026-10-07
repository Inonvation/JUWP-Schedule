package edu.jxslu.schedule.ui.ai

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.ai.AiCredentialStore
import edu.jxslu.schedule.data.ai.AiScheduleClient
import edu.jxslu.schedule.data.repo.AiApplyHistoryStore
import edu.jxslu.schedule.data.repo.AiApplyRecord
import edu.jxslu.schedule.data.repo.AiImageStore
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.data.repo.toRow
import edu.jxslu.schedule.domain.AiScheduleConfig
import edu.jxslu.schedule.domain.AiSchedulePrompt
import edu.jxslu.schedule.domain.AiScheduleTool
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.domain.ScheduleOverride
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.ui.common.NoticeTone
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * AI 调课 · 单页工作单的状态容器（DESIGN §3.25 / §4.39 / §4.40，2026-10-07 v2 重构）。
 *
 * 一轮分析的完整生命周期收在一份 [AiWorkState] 里：`分析 → 停止 → 重新分析 →
 * 应用所选 → 撤销`。v2 的交互修复（相对首版单页工作单）：
 *
 * - **结果就绪不再折叠**（折叠只在应用成功后）；勾选是主战场，不许藏起来；
 * - **回执跨轮保留**：重新分析不再清 [AiWorkState.receipt]，撤销入口不丢
 *   （页面渲染成「上次应用」条）；防重入闸改为按本轮提案（`proposalSeq`）判定；
 * - 底栏动作由 [aiBarSpec] 纯函数唯一映射；**撤销不进底栏**，只跟回执行走。
 *
 * 红线不变——
 * - 模型的 tool_call 只是**提议**，落库必须过用户逐条勾选 + 显式应用；
 * - 写库走 [ScheduleRepository.applyLocalChanges]（与手动改课同语义，**不推基线**）；
 * - **无后台调用**：只有用户点「分析」才发请求；不自动重试、不自动追问。
 */
class AiChatViewModel(
    private val repo: ScheduleRepository,
    private val credentialStore: AiCredentialStore,
    private val client: AiScheduleClient,
    private val imageStore: AiImageStore,
    private val historyStore: AiApplyHistoryStore,
    private val todayProvider: () -> java.time.LocalDate = { java.time.LocalDate.now() },
) : ViewModel() {

    /**
     * 当前课表的**非考试**条目：既是要发给模型的清单，也是 `existingIndex` 的索引基准。
     * 两处必须同源同序，否则编号会错位。
     */
    val courses: StateFlow<List<Course>> = repo.courses
        .map { list -> list.filter { it.kind != CourseKind.Exam } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val semester: StateFlow<SemesterConfig?> = repo.semester
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 当前课表**已生效的日期安排**（`date_overrides`）。三处用：
     * ① 发给模型（让它知道哪些安排已经生效，同一通知重发不再重报）；
     * ② 解析时对等价的提议做确定性去重；
     * ③ 预览的「应用前」栏要画的就是「现状」——现状 = 课程行 + 这些覆盖。
     */
    val overrides: StateFlow<List<ScheduleOverride>> = repo.overrides
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val baseUrl: StateFlow<String> = repo.aiBaseUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, AiScheduleConfig.DEFAULT_BASE_URL)

    val model: StateFlow<String> = repo.aiModel
        .stateIn(viewModelScope, SharingStarted.Eagerly, AiScheduleConfig.DEFAULT_MODEL)

    val maskTeacher: StateFlow<Boolean> = repo.aiMaskTeacher
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val thinkingEffort: StateFlow<String> = repo.aiThinkingEffort
        .stateIn(viewModelScope, SharingStarted.Eagerly, AiScheduleClient.Effort.NONE.wire)

    val showReasoning: StateFlow<Boolean> = repo.aiShowReasoning
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val supportsImage: StateFlow<Boolean> = repo.aiSupportsImage
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val privacyAcknowledged: StateFlow<Boolean> = repo.aiPrivacyAcknowledged
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _hasApiKey = MutableStateFlow(credentialStore.readApiKey() != null)
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    private val _work = MutableStateFlow(AiWorkState())
    val work: StateFlow<AiWorkState> = _work.asStateFlow()

    /** 输入框草稿（通知正文）。 */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** 待发送的图片（[AiImageStore] 的文件名）。 */
    private val _attachments = MutableStateFlow<List<String>>(emptyList())
    val attachments: StateFlow<List<String>> = _attachments.asStateFlow()

    /** 首次隐私确认弹层。 */
    private val _privacyPrompt = MutableStateFlow(false)
    val privacyPrompt: StateFlow<Boolean> = _privacyPrompt.asStateFlow()

    /** 「当前模型未标记支持图片」的发送前拦截。 */
    private val _imagePrompt = MutableStateFlow(false)
    val imagePrompt: StateFlow<Boolean> = _imagePrompt.asStateFlow()

    /** 图片导入失败提示（一次性，UI 消费即清）。 */
    private val _imageNotice = MutableStateFlow<String?>(null)
    val imageNotice: StateFlow<String?> = _imageNotice.asStateFlow()

    /**
     * 一次性提示（应用 / 撤销的结果）。
     *
     * 落库的 launch **必须在 viewModelScope 里**：挂在 `rememberCoroutineScope` 上的话，
     * 点完立刻返回上一页会取消写入——Room 事务回滚，而用户什么都没看到。
     */
    private val _notice = MutableStateFlow<AiNotice?>(null)
    val notice: StateFlow<AiNotice?> = _notice.asStateFlow()

    fun consumeNotice() {
        _notice.value = null
    }

    /** 正在分析（通知卡的按钮禁用与状态词用）。 */
    val analyzing: StateFlow<Boolean> = _work
        .map { it.phase == AiPhase.ANALYZING }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 本会话内已确认过「仍然发送图片」，不再重复拦。 */
    private var imageSendAccepted = false

    /**
     * 本会话内隐私已确认。
     *
     * 不能只读 [privacyAcknowledged]：那是 DataStore 的流，写下去要下一次发射才可见，
     * 而「同意并分析」是**同一帧**里接着调 [analyze]——只看流会再次弹确认。
     */
    private var privacyAckLocal = false

    /** 撤销事务进行中（与 `applying` 同级的防重入闸；两个动作互斥）。 */
    private var undoing = false

    /** 会话代数：清空输入 +1，用来让在途的图片导入知道自己已经过期。 */
    private var sessionSeq = 0

    private var job: Job? = null

    init {
        // 已落盘的确认读进本地标记：不然本次进程内会白弹一次（流还没发射）。
        viewModelScope.launch {
            privacyAckLocal = repo.aiPrivacyAcknowledged.first()
        }
    }

    override fun onCleared() {
        // 退出页面也要清图片：会话级、不留痕。viewModelScope 此刻已取消，走 appScope。
        val store = imageStore
        Graph.appScope.launch { store.clear() }
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    fun setDraft(value: String) {
        _draft.value = value
    }

    fun consumeImageNotice() {
        _imageNotice.value = null
    }

    /**
     * 选/粘一张图：压缩转正后落盘，超出上限或失败都给一次性提示。
     * 上限按「已落盘 + 在途」一起数；导入完成后核对会话代数——用户可能
     * 在压缩途中点了「清空」，那时这张图属于上一场会话，不能再挂上来。
     */
    fun addImage(uri: Uri) {
        if (_attachments.value.size + pendingImports >= AiImageStore.MAX_IMAGES) {
            _imageNotice.value = "最多 ${AiImageStore.MAX_IMAGES} 张图片"
            return
        }
        pendingImports++
        val seq = sessionSeq
        viewModelScope.launch {
            // 取图失败只提示「没能读取」；协程取消必须穿透（页面销毁时别吞成静默失败）
            val imported = runCatching { imageStore.importUri(uri) }.getOrElse {
                if (it is CancellationException) throw it
                null
            }
            pendingImports--
            when {
                imported == null -> _imageNotice.value = "这张图片没能读取（已跳过）"
                seq != sessionSeq -> imageStore.fileFor(imported.fileName).delete()
                _attachments.value.size >= AiImageStore.MAX_IMAGES ->
                    imageStore.fileFor(imported.fileName).delete()

                else -> _attachments.update { it + imported.fileName }
            }
        }
    }

    fun removeImage(fileName: String) {
        _attachments.update { it - fileName }
        viewModelScope.launch { imageStore.fileFor(fileName).delete() }
    }

    /**
     * 清空**输入区**（通知卡的「清空」）：草稿与待发图片全清。
     *
     * v2 起不动结果区——提案与回执是另一块，各自有各自的出口
     * （结果区的出口是 [analyzeNew] / 撤销）。分析中禁用（UI 层拦截）。
     */
    fun clearInputs() {
        if (_work.value.phase == AiPhase.ANALYZING) return
        sessionSeq++
        _draft.value = ""
        _attachments.value = emptyList()
        viewModelScope.launch { imageStore.clear() }
    }

    /** 从配置页返回后刷新「是否已配 Key」（凭证存储不是响应式的，得手动重读）。 */
    fun refreshCredentials() {
        _hasApiKey.value = credentialStore.readApiKey() != null
    }

    fun acknowledgePrivacy() {
        privacyAckLocal = true
        _privacyPrompt.value = false
        viewModelScope.launch { repo.setAiPrivacyAcknowledged(true) }
    }

    fun dismissPrivacyPrompt() {
        _privacyPrompt.value = false
    }

    fun dismissImagePrompt() {
        _imagePrompt.value = false
    }

    // ------------------------------------------------------------------
    // 分析（底栏：分析 / 停止 / 重试 / 重新分析）
    // ------------------------------------------------------------------

    /** 底栏「分析 / 重新分析」：用当前草稿发起（或重发）一次分析。 */
    fun analyze() {
        val phase = _work.value.phase
        if (phase == AiPhase.ANALYZING || phase == AiPhase.APPLYING) return
        val text = _draft.value.trim()
        val images = _attachments.value
        if (text.isBlank() && images.isEmpty()) return
        if (!privacyAcknowledged.value && !privacyAckLocal) {
            _privacyPrompt.value = true
            return
        }
        if (images.isNotEmpty() && !supportsImage.value && !imageSendAccepted) {
            _imagePrompt.value = true
            return
        }
        startAnalyze(text, images)
    }

    /** 隐私确认通过后接着分析。 */
    fun confirmPrivacyAndAnalyze() {
        acknowledgePrivacy()
        analyze()
    }

    /** 「仍然发送」：本次会话内不再提示。 */
    fun confirmImageSend() {
        imageSendAccepted = true
        _imagePrompt.value = false
        analyze()
    }

    /** 停止分析：把当前这条收尾（已有部分结果就保留），socket 由 awaitClose cancel。 */
    fun stopAnalyze() {
        job?.cancel()
    }

    /**
     * 「去掉图片重发」：上游不吃图时的唯一出路。
     *
     * 输入区的截图**一并移除**——「本会话不再自动带通知截图」靠输入区诚实兑现
     * （列表里没有 = 不会再发）；藏一个过滤器（截图还挂着却不发）用户看不懂。
     */
    fun retryWithoutImages() {
        sessionSeq++
        val dropped = _attachments.value
        if (dropped.isNotEmpty()) {
            _attachments.value = emptyList()
            viewModelScope.launch { dropped.forEach { imageStore.fileFor(it).delete() } }
        }
        retry(withoutImages = true)
    }

    /** 重试：用最近一次分析原样重发（同文本同图；[withoutImages] 时只重发文字）。 */
    fun retry(withoutImages: Boolean = false) {
        val state = _work.value
        if (state.phase != AiPhase.FAILED) return
        if (state.noticeText.isBlank() && state.noticeImages.isEmpty()) return
        _work.update {
            it.copy(
                phase = AiPhase.ANALYZING,
                proposalSeq = it.proposalSeq + 1,
                // 「依据」行按**实际发出**的图集报数：去掉图片后别再写「+N 张截图」
                noticeImages = if (withoutImages) emptyList() else state.noticeImages,
                failure = "",
                failureIsImage = false,
                reasoning = "",
                content = "",
                result = null,
                selected = null,
                selectedOverrides = null,
                collapsed = false,
            )
        }
        runStream(
            text = state.noticeText,
            images = if (withoutImages) emptyList() else state.noticeImages,
        )
    }

    private fun startAnalyze(text: String, images: List<String>) {
        job?.cancel()
        // 通知文本与图片**保留在输入区**（改完再点「重新分析」= 重来）；不清空。
        // v2：更新而不是整体换 state——回执跨轮保留（撤销入口不丢）。
        // 不碰 `applying`：apply 事务有自己的出入口闸（phase = APPLYING 期间 analyze 进不来）。
        _work.update {
            it.copy(
                phase = AiPhase.ANALYZING,
                proposalSeq = it.proposalSeq + 1,
                noticeText = text,
                noticeImages = images,
                analyzedAt = "",
                reasoning = "",
                content = "",
                result = null,
                failure = "",
                failureIsImage = false,
                selected = null,
                selectedOverrides = null,
                undoSkipped = 0,
                collapsed = false,
            )
        }
        runStream(text = text, images = images)
    }

    /**
     * 底栏「分析新通知」（仅 [AiPhase.APPLIED]）：本轮结束，清掉结果区回待输入。
     *
     * 输入区与回执都不动——回执落成页面上的「上次应用」条（撤销仍可达）；
     * 下一份通知是清空输入区（「清空」）还是接着改，用户自己决定。
     */
    fun analyzeNew() {
        if (_work.value.phase != AiPhase.APPLIED) return
        job?.cancel()
        _work.update {
            it.copy(
                phase = AiPhase.IDLE,
                noticeText = "",
                noticeImages = emptyList(),
                analyzedAt = "",
                reasoning = "",
                content = "",
                result = null,
                failure = "",
                failureIsImage = false,
                selected = null,
                selectedOverrides = null,
                undoSkipped = 0,
                collapsed = false,
            )
        }
    }

    private fun runStream(text: String, images: List<String>) {
        job = viewModelScope.launch {
            val rows = courses.value
            val sem = semester.value
            // 已生效的日期安排：与课表同帧快照——prompt、解析去重、预览「应用前」共用同一份
            val existingOverrides = overrides.value
            val masked = maskedNames()
            val args = StringBuilder()
            val reasoning = StringBuilder()
            val content = StringBuilder()
            try {
                val url = baseUrl.value.trim()
                if (url.isBlank()) {
                    fail("请先在「配置」里填写服务地址")
                    return@launch
                }
                val key = credentialStore.readApiKey().orEmpty()
                if (key.isBlank()) {
                    fail("请先在「配置」里填写 API Key")
                    return@launch
                }
                val files = images.map { imageStore.fileFor(it) }.filter { it.isFile }
                client.chatStream(
                    baseUrl = url,
                    apiKey = key,
                    model = model.value.ifBlank { AiScheduleConfig.DEFAULT_MODEL },
                    system = AiSchedulePrompt.SYSTEM,
                    user = AiSchedulePrompt.userMessage(
                        notice = text.ifBlank { "（通知内容见图片）" },
                        courses = rows,
                        maskTeacher = maskTeacher.value,
                        semester = sem,
                        today = todayProvider(),
                        existingOverrides = existingOverrides,
                    ),
                    effort = AiScheduleClient.Effort.fromWire(thinkingEffort.value),
                    images = files,
                ).collect { event ->
                    when (event) {
                        is AiScheduleClient.StreamEvent.Reasoning -> {
                            reasoning.append(event.delta)
                            _work.update { it.copy(reasoning = reasoning.toString()) }
                        }

                        is AiScheduleClient.StreamEvent.Content -> {
                            content.append(event.delta)
                            _work.update { it.copy(content = content.toString()) }
                        }

                        is AiScheduleClient.StreamEvent.ToolArgs -> {
                            args.append(event.delta)
                            // 增量解析：已闭合的条目立刻出卡片（渲染侧同轮放行）。
                            val partial = AiScheduleTool.parsePartial(args.toString(), rows, sem, masked, existingOverrides)
                            _work.update { it.copy(result = partial) }
                        }
                    }
                }

                // 工具参数可能分片到达；没走工具调用时再从正文里兜底抽一段 JSON
                val arguments = args.toString().ifBlank {
                    AiScheduleTool.extractJson(content.toString()).orEmpty()
                }
                val parsed = if (arguments.isBlank()) {
                    null
                } else {
                    AiScheduleTool.parse(arguments, rows, sem, masked, existingOverrides)
                }
                if (parsed != null) {
                    // v2：结果就绪**不折叠**（勾选是主战场）；折叠只发生在应用成功后
                    _work.update {
                        it.copy(
                            phase = AiPhase.READY,
                            result = parsed,
                            content = "",
                            analyzedAt = now(),
                        )
                    }
                } else {
                    // 模型只回了散文：不空屏，原文放进 message，改动为空
                    val prose = content.toString().trim()
                    _work.update {
                        it.copy(
                            phase = AiPhase.READY,
                            content = "",
                            analyzedAt = now(),
                            result = AiScheduleTool.Result(
                                message = prose.ifBlank { "模型没有返回内容" },
                            ),
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    // 用户点了停止：有部分结果就留下（转 READY），没有就回 IDLE
                    _work.update {
                        if (it.result != null) {
                            it.copy(phase = AiPhase.READY, analyzedAt = now())
                        } else {
                            it.copy(phase = AiPhase.IDLE)
                        }
                    }
                    throw t
                }
                val isImage = t is AiScheduleClient.AiHttpException &&
                    AiScheduleClient.looksLikeImageRejection(t.code, t.detail)
                _work.update {
                    it.copy(
                        phase = AiPhase.FAILED,
                        failure = aiErrorText(t),
                        failureIsImage = isImage,
                    )
                }
            }
        }
    }

    /** 发给模型的脱敏占位名（「教师1」…）：解析时要把它们当「未提供」，不许写回库。 */
    private fun maskedNames(): Set<String> =
        if (maskTeacher.value) AiSchedulePrompt.maskedTeachers(courses.value).values.toSet()
        else emptySet()

    private fun fail(message: String) {
        _work.update { it.copy(phase = AiPhase.FAILED, failure = message) }
    }

    // ------------------------------------------------------------------
    // 勾选 / 应用 / 撤销 / 折叠
    // ------------------------------------------------------------------

    fun toggleChange(index: Int, checked: Boolean) {
        _work.update { work ->
            val next = work.effectiveSelection.toMutableSet().apply {
                if (checked) add(index) else remove(index)
            }
            work.copy(selected = next)
        }
    }

    fun toggleOverride(index: Int, checked: Boolean) {
        _work.update { work ->
            val next = work.effectiveOverrideSelection.toMutableSet().apply {
                if (checked) add(index) else remove(index)
            }
            work.copy(selectedOverrides = next)
        }
    }

    fun setCollapsed(collapsed: Boolean) {
        _work.update { it.copy(collapsed = collapsed) }
    }

    /**
     * 应用勾选的改动与覆盖。课表改动走 [ScheduleRepository.applyLocalChanges]——
     * 与手动改课同语义、指纹匹配不上的条目跳过而不是硬套，**不推调课基线**；
     * 课表改动与日期覆盖在**同一个事务**里落库。
     *
     * **一处都没落库**（全被指纹挡掉）不算应用：状态留在 READY 让用户改通知重来，
     * 用 [AiNothingApplied] 把条数报给 UI——照着折叠成「已应用 0 处」是误导。
     *
     * **防重入**：判据是「**本轮**提案是否已应用」——回执带 `proposalSeq`，
     * 与当前轮对上且未撤销才算重复。双击窗口 100ms 级，第二次进来若照跑，
     * 日期覆盖会裸插两行且回执被覆盖（撤销只删得掉一份）。
     *
     * 事务期间 `phase = APPLYING`：底栏变「应用中…」且不可点，analyze / retry 一并被挡
     * （否则「事务在飞时点重新分析」会让终态变成 READY+折叠，把 v2 修掉的毛病放回来）。
     *
     * 落库成功后向 [AiApplyHistoryStore] 记一条留痕（跨会话的「调课记录」页据此
     * 查看与撤销）；回执带着留痕 id，撤销时同步标记。
     */
    suspend fun apply(): Result<AiAppliedReceipt> {
        val work = _work.value
        if (work.applying || undoing) return Result.failure(IllegalStateException("正在处理，请稍候"))
        if (work.phase != AiPhase.READY && work.phase != AiPhase.UNDONE) {
            return Result.failure(IllegalStateException("当前没有待确认的提案"))
        }
        val existing = work.receipt
        if (existing != null && !existing.undone && existing.proposalSeq == work.proposalSeq) {
            return Result.failure(IllegalStateException("这份提案已经应用过了"))
        }
        if (work.selectedCount == 0) return Result.failure(IllegalStateException("没有勾选任何改动"))
        val entryPhase = work.phase
        _work.update { it.copy(applying = true, phase = AiPhase.APPLYING) }
        return try {
            val result = repo.applyLocalChanges(
                changes = work.changes,
                acceptedIndexes = work.effectiveSelection,
                overrides = work.overrides,
                acceptedOverrides = work.effectiveOverrideSelection,
            )
            if (result.report.applied == 0 && result.overrideIds.isEmpty()) {
                _work.update { it.copy(phase = entryPhase, applying = false) }
                return Result.failure(AiNothingApplied(result.report.skipped))
            }
            val record = AiApplyRecord(
                id = System.currentTimeMillis(),
                appliedCourses = result.report.applied,
                appliedOverrides = result.overrideIds.size,
                skipped = result.report.skipped,
                timetableId = result.timetableId,
                summary = aiHistorySummary(result.report.entries, result.overrideIds.size),
                entries = result.report.entries.map { it.toRow() },
                overrideIds = result.overrideIds,
            )
            historyStore.append(record)
            val receipt = AiAppliedReceipt(
                appliedCourses = result.report.applied,
                appliedOverrides = result.overrideIds.size,
                skipped = result.report.skipped,
                entries = result.report.entries,
                overrideIds = result.overrideIds,
                timetableId = result.timetableId,
                at = now(),
                proposalSeq = work.proposalSeq,
                historyId = record.id,
            )
            _work.update {
                it.copy(
                    receipt = receipt,
                    phase = AiPhase.APPLIED,
                    applying = false,
                    // 应用成功才折叠成摘要卡（用户口径：指令发出后可随时点开）
                    collapsed = true,
                )
            }
            Result.success(receipt)
        } catch (t: Throwable) {
            _work.update { it.copy(phase = entryPhase, applying = false) }
            // CancellationException 必须穿透：吞了它会让「组合被销毁」变成一次静默的失败，
            // 而事务可能已经提交（项目此前专门修过 10 处同款）
            if (t is CancellationException) throw t
            Result.failure(t)
        }
    }

    /**
     * 撤销一次应用：课表反向 + 删掉那次写入的覆盖行，同一个事务。
     * 按回执里记的 `timetableId` 落库，并过状态闸——应用后被用户手动改过的行不撤。
     *
     * v2：撤销**不要求当前阶段是 APPLIED**——回执跨轮保留，从「上次应用」条
     * 也能撤销（此时当前轮的阶段不动，就地生效）。撤销后回执留档（`undone = true`），
     * 不再可撤；留痕同步标记，「调课记录」页据此判断还能不能撤。
     *
     * **跨窗口一致性**：留痕可能已被「调课记录」页撤过（同一个 `historyId`）——
     * 先核对留痕，撤过就只同步会话状态，不再跑一次事务。
     *
     * **目标课表已删除**：拒绝并说清——反向写会向不存在的课表塞孤儿行
     * （任何界面都看不见，只能靠人工清库）。
     *
     * **防重入**：`undoing` 闸与 `applying` 互斥；双击的第二下被挡在门外
     * （否则第二遍事务会全数撞指纹闸，把「N 条你后来改过」的假报告写进回执行）。
     */
    suspend fun undo(): Result<Int> {
        val work = _work.value
        if (work.applying || undoing) return Result.failure(IllegalStateException("正在处理，请稍候"))
        val receipt = work.receipt ?: return Result.failure(IllegalStateException("还没有应用过"))
        if (receipt.undone) return Result.failure(IllegalStateException("已经撤销过了"))
        if (receipt.historyId != 0L && historyStore.find(receipt.historyId)?.undone == true) {
            syncReceiptUndone()
            return Result.failure(IllegalStateException("已经撤销过了"))
        }
        // 先扣闸再进第一个挂起点（timetableIds 会挂起）：否则双击的第二下会在
        // 闸位落下前挤进来，再跑一遍全数撞指纹闸的事务、把假 skipped 写进回执行
        undoing = true
        return try {
            if (receipt.timetableId !in repo.timetableIds()) {
                return Result.failure(IllegalStateException("原课表已删除，无法撤销"))
            }
            val skipped = repo.undoLocalChanges(
                entries = receipt.entries,
                overrideIds = receipt.overrideIds,
                timetableId = receipt.timetableId,
            )
            if (receipt.historyId != 0L) historyStore.markUndone(receipt.historyId)
            _work.update {
                it.copy(
                    receipt = receipt.copy(undone = true),
                    undoSkipped = skipped,
                    phase = if (it.phase == AiPhase.APPLIED) AiPhase.UNDONE else it.phase,
                    // 撤销后展开卡片：用户要看回执、也可能改完勾选重新应用
                    collapsed = if (it.phase == AiPhase.APPLIED) false else it.collapsed,
                )
            }
            Result.success(skipped)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Result.failure(t)
        } finally {
            undoing = false
        }
    }

    /**
     * 从「调课记录」页返回后同步：同一条留痕在那里被撤销过时，会话里的回执跟着变
     * （否则「撤销」按钮还亮着，再点只会撞上指纹闸报一堆「跳过」）。
     */
    fun refreshHistoryUndo() {
        val receipt = _work.value.receipt ?: return
        if (receipt.historyId == 0L || receipt.undone) return
        if (historyStore.find(receipt.historyId)?.undone == true) syncReceiptUndone()
    }

    /** 把「这条应用已被撤销」同步进会话状态（阶段不动，就地生效）。 */
    private fun syncReceiptUndone() {
        _work.update {
            it.copy(
                receipt = it.receipt?.copy(undone = true),
                phase = if (it.phase == AiPhase.APPLIED) AiPhase.UNDONE else it.phase,
                collapsed = if (it.phase == AiPhase.APPLIED) false else it.collapsed,
            )
        }
    }

    /** UI 入口：把落库放进 viewModelScope，结果用一次性提示回吐。 */
    fun applyFromUi() {
        viewModelScope.launch {
            apply()
                .onSuccess { receipt ->
                    val detail = buildString {
                        append("已应用 ").append(receipt.applied).append(" 处")
                        if (receipt.appliedOverrides > 0) {
                            append("（日期覆盖 ").append(receipt.appliedOverrides).append("）")
                        }
                        if (receipt.skipped > 0) {
                            append(" · ").append(receipt.skipped).append(" 条被跳过")
                        }
                    }
                    _notice.value = AiNotice(detail, NoticeTone.Success)
                }
                .onFailure { t ->
                    _notice.value = when (t) {
                        // 一处都没落库不算失败：提案留着，改改通知重新分析
                        is AiNothingApplied -> AiNotice("${t.message}，可重新分析", NoticeTone.Warning)
                        else -> AiNotice("应用失败：${t.message ?: "请稍后重试"}", NoticeTone.Error)
                    }
                }
        }
    }

    /** UI 入口：撤销同样放 viewModelScope。 */
    fun undoFromUi() {
        viewModelScope.launch {
            undo()
                .onSuccess { skipped ->
                    _notice.value = AiNotice(
                        if (skipped > 0) "已撤销；$skipped 条你后来改过，未动" else "已撤销，课表回到应用前",
                        NoticeTone.Success,
                    )
                }
                .onFailure { _notice.value = AiNotice("撤销失败：${it.message ?: "请稍后重试"}", NoticeTone.Error) }
        }
    }

    // ------------------------------------------------------------------

    /** 在途的图片导入数（与 [addImage] 一起构成上限判据）。 */
    private var pendingImports = 0

    private fun now(): String = LocalTime.now().format(TIME_FORMAT)

    class Factory(
        private val repo: ScheduleRepository,
        private val credentialStore: AiCredentialStore,
        private val client: AiScheduleClient,
        private val imageStore: AiImageStore,
        private val historyStore: AiApplyHistoryStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AiChatViewModel(repo, credentialStore, client, imageStore, historyStore) as T
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
    }
}
