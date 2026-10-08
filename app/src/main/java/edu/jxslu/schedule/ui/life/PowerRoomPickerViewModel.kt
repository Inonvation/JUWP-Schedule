package edu.jxslu.schedule.ui.life

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.power.PowerException
import edu.jxslu.schedule.data.power.PowerMeter
import edu.jxslu.schedule.data.power.PowerModels
import edu.jxslu.schedule.data.power.PowerRepository
import edu.jxslu.schedule.data.power.PowerSceneKey
import edu.jxslu.schedule.data.power.PowerSceneLevel
import edu.jxslu.schedule.data.power.PowerSceneOption
import edu.jxslu.schedule.data.ykt.YktCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「选择寝室」弹层的状态机（DESIGN §4.24「未设置寝室」，2026-10-08）。
 *
 * 链路 = 官方 H5 同款三步（协议与实测见 [PowerRepository.sceneRoot] 一节）：
 * 层级表（校区 → 楼栋 → 房间）→ 逐级候选 → 选到房间后读表预览（**不落库**）
 * → 用户确认后 [PowerRepository.bindScene] 写一次。全部由用户动作驱动：
 * 不开缓存、不轮询、失败不重试（错误留在弹层里，点「重试」才再发）。
 *
 * 弹层是宿主（生活页 / 校园卡设置页）里挂的常驻组件，[start] 会在每次打开时重置。
 */
class PowerRoomPickerViewModel(
    private val powerRepo: PowerRepository,
    private val credentialStore: YktCredentialStore,
) : ViewModel() {

    data class UiState(
        /** 层级表（校区 → 楼栋 → 房间）；空 = 还没加载出来。 */
        val levels: List<PowerSceneLevel> = emptyList(),
        /** 正在选的层（0 起，与 [levels] 下标对齐）。 */
        val step: Int = 0,
        /** 当前层候选。 */
        val options: List<PowerSceneOption> = emptyList(),
        /** 已选定（下标 = 层号；未选为 null）。 */
        val chosen: List<PowerSceneOption?> = emptyList(),
        /** 层级/候选/预览任一在途。 */
        val busy: Boolean = false,
        /** 房间已选后的读表预览（null = 还没回来 / 还没到最底层）。 */
        val preview: PowerMeter? = null,
        /** 确认绑定在途（按钮置灰防重入；写接口只在用户点确认后发一次）。 */
        val binding: Boolean = false,
        val error: String? = null,
        /** 上一次失败发生在「确认绑定」这一步：重试按钮据此重新发写请求而不是重拉预览。 */
        val bindFailed: Boolean = false,
        /** 绑定成功：弹层据此收尾（宿主刷新卡面 + 提示）。 */
        val done: Boolean = false,
    ) {
        /** 已到最底层（正在挑的就是房间）。 */
        val atRoomLevel: Boolean get() = levels.isNotEmpty() && step == levels.lastIndex

        /** 选定的房间（预览/确认/成功文案都用它）；还没选到为 null。 */
        val pickedRoom: PowerSceneOption? get() = if (atRoomLevel) chosen.getOrNull(step) else null

        /** 已选的层级面包屑（“校区名 · 楼栋名”），当前层不重复展示。 */
        val breadcrumb: List<String>
            get() = levels.indices
                .take(step)
                .mapNotNull { chosen.getOrNull(it)?.name }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * 会话闸（2026-10-08 复查轮）：弹层是常驻组件、[start] 每次打开都整体重置，但
     * `viewModelScope` 里的旧协程**不会被自动取消**——快关快开时上一轮在途的
     * `loadOptions`/`bind` 完成后会把旧候选 / 旧 `done=true` 合进新一轮状态
     * （表现为闪现旧列表、甚至弹层自动关掉并报「已设置」）。每次 [start] 递增
     * 会话号，各协程回调前先比对，不等号的直接放弃落状态。
     */
    private var session = 0L

    /** 每次打开弹层调用：重置并拉层级表。 */
    fun start() {
        val mySession = ++session
        _state.value = UiState(busy = true)
        viewModelScope.launch {
            val credentials = credentialStore.read()
            if (mySession != session) return@launch
            if (credentials == null) {
                _state.update { it.copy(busy = false, error = NO_CREDENTIALS) }
                return@launch
            }
            try {
                val root = powerRepo.sceneRoot(credentials.username, credentials.password)
                if (mySession != session) return@launch
                if (root.levels.isEmpty()) {
                    throw PowerException.Protocol("场景层级为空（平台可能已改版）")
                }
                _state.update {
                    it.copy(
                        levels = root.levels,
                        step = 0,
                        options = root.options,
                        chosen = List(root.levels.size) { null },
                        busy = false,
                        error = null,
                    )
                }
                autoSelectSingleOption()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mySession != session) return@launch
                _state.update { it.copy(busy = false, error = e.message ?: "场景层级加载失败") }
            }
        }
    }

    /** 选中当前层的一项：非最底层继续拉下一层候选，最底层进读表预览。 */
    fun selectOption(option: PowerSceneOption) {
        val s = _state.value
        if (s.busy || s.binding || s.done) return
        if (s.step !in s.levels.indices) return
        val chosen = applySceneChoice(s.chosen, s.step, option)
        if (s.step == s.levels.lastIndex) {
            _state.update {
                it.copy(chosen = chosen, preview = null, busy = true, error = null, bindFailed = false)
            }
            loadPreview()
        } else {
            val next = s.step + 1
            _state.update {
                it.copy(
                    chosen = chosen,
                    step = next,
                    options = emptyList(),
                    busy = true,
                    error = null,
                    bindFailed = false,
                )
            }
            loadOptions(next)
        }
    }

    /**
     * 返回上一层（宿主按住返回键 / 面包屑旁返回时调用）。
     * 返回 true = 已处理（留在弹层）；false = 已在第一层，宿主自行收弹层。
     */
    fun back(): Boolean {
        val s = _state.value
        if (s.busy || s.binding || s.done) return true
        if (s.levels.isEmpty() || s.step == 0) return false
        val target = s.step - 1
        _state.update {
            it.copy(
                step = target,
                options = emptyList(),
                preview = null,
                busy = true,
                error = null,
                bindFailed = false,
            )
        }
        loadOptions(target)
        return true
    }

    /** 失败后的手动重试（唯一的重试入口；不自动重发）。 */
    fun retry() {
        val s = _state.value
        if (s.busy || s.binding) return
        when {
            s.levels.isEmpty() -> start()
            // 绑定失败的重试 = 重新发一次写请求（仍是用户主动点击触发）
            s.bindFailed -> bind()

            s.atRoomLevel && s.chosen.getOrNull(s.step) != null -> {
                _state.update { it.copy(busy = true, error = null, bindFailed = false) }
                loadPreview()
            }

            else -> {
                _state.update { it.copy(busy = true, error = null, bindFailed = false) }
                loadOptions(s.step)
            }
        }
    }

    /** 拉某一层的候选；带单选项自动推进（如校区只有一所时不要求多点一下）。 */
    private fun loadOptions(step: Int) {
        val mySession = session
        viewModelScope.launch {
            val credentials = credentialStore.read()
            if (mySession != session) return@launch
            if (credentials == null) {
                _state.update { it.copy(busy = false, error = NO_CREDENTIALS) }
                return@launch
            }
            try {
                val chosen = chosenMapBefore(step)
                val options = powerRepo.sceneOptions(credentials.username, credentials.password, step, chosen)
                if (mySession != session) return@launch
                _state.update { it.copy(options = options, busy = false, error = null) }
                autoSelectSingleOption()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mySession != session) return@launch
                _state.update { it.copy(busy = false, error = e.message ?: "候选加载失败") }
            }
        }
    }

    /** 读表预览（不落库）：确认「这个房间是不是你的、还剩多少电」。 */
    private fun loadPreview() {
        val mySession = session
        viewModelScope.launch {
            val credentials = credentialStore.read()
            if (mySession != session) return@launch
            if (credentials == null) {
                _state.update { it.copy(busy = false, error = NO_CREDENTIALS) }
                return@launch
            }
            val scene = sceneKeys()
            if (scene == null) {
                _state.update { it.copy(busy = false, error = "房间还没选好") }
                return@launch
            }
            try {
                val meter = powerRepo.readMeterForScene(credentials.username, credentials.password, scene)
                if (mySession != session) return@launch
                _state.update { it.copy(preview = meter, busy = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mySession != session) return@launch
                _state.update { it.copy(busy = false, error = e.message ?: "读表失败") }
            }
        }
    }

    /**
     * 用户点「确认绑定」后的最后一步（写）：`sceneBind/add` 发一次。
     * 由弹层里的二次确认弹窗承接（这里只负责发请求与状态）。
     */
    fun bind() {
        val s = _state.value
        if (s.binding || s.done || s.busy) return
        // 选齐判断与 sceneKeys 同一口径；没选齐（理论到不了这里）直接不发
        if (sceneKeys() == null) return
        val picks = s.levels.indices.mapNotNull { i -> s.chosen.getOrNull(i)?.let { s.levels[i] to it } }
        if (picks.size != s.levels.size) return
        val sceneinfo = PowerModels.sceneinfoOf(picks)
        val credentials = credentialStore.read()
        if (credentials == null) {
            _state.update { it.copy(error = NO_CREDENTIALS) }
            return
        }
        _state.update { it.copy(binding = true, error = null) }
        val mySession = session
        viewModelScope.launch {
            try {
                powerRepo.bindScene(credentials.username, credentials.password, sceneinfo)
                if (mySession != session) return@launch
                _state.update { it.copy(binding = false, done = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PowerException) {
                if (mySession != session) return@launch
                _state.update { it.copy(binding = false, error = e.message ?: "设置失败", bindFailed = true) }
            } catch (e: Exception) {
                if (mySession != session) return@launch
                _state.update {
                    it.copy(binding = false, error = "设置失败：${e.message ?: "未知错误"}", bindFailed = true)
                }
            }
        }
    }

    /** 当前已选齐的 `code → id`（第 [before] 层之前）。 */
    private fun chosenMapBefore(before: Int): Map<String, String> =
        _state.value.levels.indices
            .take(before)
            .mapNotNull { i ->
                val level = _state.value.levels.getOrNull(i)
                val option = _state.value.chosen.getOrNull(i)
                if (level != null && option != null) level.code to option.value else null
            }
            .toMap()

    /** 全部层已选定时的场景键列表（[PowerSceneKey]）；没选齐给 null。 */
    private fun sceneKeys(): List<PowerSceneKey>? {
        val s = _state.value
        if (s.levels.isEmpty()) return null
        return s.levels.indices.map { i ->
            val option = s.chosen.getOrNull(i) ?: return null
            PowerSceneKey(code = s.levels[i].code, id = option.value, name = option.name)
        }
    }

    /** 候选只有一项时自动选中并推进（层级配置里校区唯一等场景）。 */
    private fun autoSelectSingleOption() {
        val s = _state.value
        if (s.busy || s.binding || s.done) return
        if (s.options.size == 1 && s.chosen.getOrNull(s.step) == null) {
            selectOption(s.options.first())
        }
    }

    companion object {
        private const val NO_CREDENTIALS = "请先在「我的 → 校园卡」填入学号与查询密码"

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PowerRoomPickerViewModel(
                Graph.powerRepository(context.applicationContext),
                Graph.yktCredentialStore(context.applicationContext),
            ) as T
        }
    }
}

/**
 * 级联选择的一步（纯函数，`PowerSceneSelectionTest` 钉住）：写入 [step] 层的选择，
 * 并把更深层的旧选择清掉——改选楼栋后，上一个楼栋选过的房间不能残留
 * （残留会让「房间」层直接显示旧预览而不是候选列表；2026-10-08 复查轮修）。
 */
internal fun applySceneChoice(
    chosen: List<PowerSceneOption?>,
    step: Int,
    option: PowerSceneOption,
): List<PowerSceneOption?> {
    val next = chosen.toMutableList()
    next[step] = option
    for (i in step + 1 until next.size) next[i] = null
    return next
}
