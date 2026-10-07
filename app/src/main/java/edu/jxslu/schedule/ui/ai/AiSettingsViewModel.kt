package edu.jxslu.schedule.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.data.ai.AiCredentialStore
import edu.jxslu.schedule.data.ai.AiScheduleClient
import edu.jxslu.schedule.data.repo.ScheduleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * AI 服务配置页（DESIGN §4.39）的状态容器。
 *
 * 地址 / 模型 / 脱敏走 DataStore（经 Repository 单流）；**API Key 走
 * [AiCredentialStore] 加密存储**，不进 DataStore。首帧真值同 MeViewModel 的
 * 模式（`runBlocking { first() }`），避免开关先按默认渲染再跳一次。
 */
class AiSettingsViewModel(
    private val repo: ScheduleRepository,
    private val credentialStore: AiCredentialStore,
    private val client: AiScheduleClient,
) : ViewModel() {

    val baseUrl: StateFlow<String> = repo.aiBaseUrl.stateIn(
        viewModelScope, SharingStarted.Eagerly, runBlocking { repo.aiBaseUrl.first() },
    )

    val model: StateFlow<String> = repo.aiModel.stateIn(
        viewModelScope, SharingStarted.Eagerly, runBlocking { repo.aiModel.first() },
    )

    val maskTeacher: StateFlow<Boolean> = repo.aiMaskTeacher.stateIn(
        viewModelScope, SharingStarted.Eagerly, runBlocking { repo.aiMaskTeacher.first() },
    )

    /** 思考档位（none / low / high / max）。 */
    val thinkingEffort: StateFlow<String> = repo.aiThinkingEffort.stateIn(
        viewModelScope, SharingStarted.Eagerly, runBlocking { repo.aiThinkingEffort.first() },
    )

    /** 是否在 AI 调课页显示思考过程。 */
    val showReasoning: StateFlow<Boolean> = repo.aiShowReasoning.stateIn(
        viewModelScope, SharingStarted.Eagerly, runBlocking { repo.aiShowReasoning.first() },
    )

    /**
     * 该模型是否支持图片识别（DESIGN §4.39），默认关。
     *
     * 探测不了（模型名用户手填、上游 `/models` 不给能力），只能显式声明。
     * [AiScheduleConfig.looksVisionModel] 只用于在配置页提示「看着像视觉模型」。
     */
    val supportsImage: StateFlow<Boolean> = repo.aiSupportsImage.stateIn(
        viewModelScope, SharingStarted.Eagerly, runBlocking { repo.aiSupportsImage.first() },
    )

    private val _apiKey = MutableStateFlow(credentialStore.readApiKey())
    val apiKey: StateFlow<String?> = _apiKey.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun setBaseUrl(value: String) {
        viewModelScope.launch { repo.setAiBaseUrl(value) }
    }

    fun setModel(value: String) {
        viewModelScope.launch { repo.setAiModel(value) }
    }

    fun setMaskTeacher(value: Boolean) {
        viewModelScope.launch { repo.setAiMaskTeacher(value) }
    }

    fun setThinkingEffort(value: String) {
        viewModelScope.launch { repo.setAiThinkingEffort(value) }
    }

    fun setShowReasoning(value: Boolean) {
        viewModelScope.launch { repo.setAiShowReasoning(value) }
    }

    fun setSupportsImage(value: Boolean) {
        viewModelScope.launch { repo.setAiSupportsImage(value) }
    }

    fun saveApiKey(value: String) {
        credentialStore.saveApiKey(value)
        _apiKey.value = credentialStore.readApiKey()
    }

    fun clearApiKey() {
        credentialStore.clear()
        _apiKey.value = null
    }

    /**
     * 探测服务：拉一次 `/models`——既验证地址与 Key 是否可用，也顺便给出模型可选项。
     * 失败不重试，错误分类交给 [aiErrorText]。
     */
    suspend fun probe(): Result<List<String>> {
        val url = baseUrl.value.trim()
        val key = _apiKey.value?.trim().orEmpty()
        if (url.isBlank()) return Result.failure(IllegalStateException("请先填写服务地址"))
        if (key.isBlank()) return Result.failure(IllegalStateException("请先填写 API Key"))
        _busy.value = true
        return try {
            Result.success(withContext(Dispatchers.IO) { client.listModels(url, key) })
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Result.failure(t)
        } finally {
            _busy.value = false
        }
    }

    class Factory(
        private val repo: ScheduleRepository,
        private val credentialStore: AiCredentialStore,
        private val client: AiScheduleClient,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AiSettingsViewModel(repo, credentialStore, client) as T
    }
}
