package edu.jxslu.schedule.ui.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.data.weather.WeatherRepository
import edu.jxslu.schedule.domain.WeatherSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 天气页 ViewModel（DESIGN §3.29）。
 *
 * 只做一件事：把 [WeatherRepository] 的快照搬进 UI 状态。缓存与刷新策略全在仓库
 * （30 分钟 TTL），本类不自己计时——进页调一次 [load]（传 force=false 走缓存），
 * 用户点重试 / 下拉才 force。
 *
 * 不并进今日页 ViewModel：两者是不同窗口（今日页在主 Activity、详情页在二级页），
 * 共用的只有仓库单例。
 */
class WeatherViewModel(private val repo: WeatherRepository) : ViewModel() {

    companion object {
        /**
         * 下拉刷新的最短驻留（与今日页同一口径，650ms）：请求命中得再快也不能让指示器
         * 一拉到顶就弹回去，那样读起来像「拉了个寂寞」。慢于这个值就按真实耗时走。
         */
        const val MIN_REFRESH_MILLIS = 650L
    }

    data class UiState(
        /** 首次加载（手里还没有任何数据）：页面铺骨架。 */
        val loading: Boolean = true,
        /** 下拉刷新中：只转顶部指示器，内容留在屏上（不退回骨架）。 */
        val refreshing: Boolean = false,
        val snapshot: WeatherSnapshot? = null,
        /** 本次快照的取数时刻（展示「更新于」用；仓库不往领域模型里塞缓存元数据）。 */
        val loadedAtMs: Long = 0L,
        /** 刷新失败但手里还有旧数据：页面顶部给一条弱提示，别把内容清掉。 */
        val stale: Boolean = false,
    )

    /**
     * 首帧直接铺**仓库里还在的缓存**（重新进页时通常都还在）：那种情况下不该再闪一次骨架，
     * 骨架只留给「真的还没有任何数据」。`peek` 是内存读，不阻塞、不发网络。
     */
    private val _state = MutableStateFlow(
        repo.peek()?.let { cached ->
            UiState(loading = false, snapshot = cached.snapshot, loadedAtMs = cached.atMs)
        } ?: UiState(),
    )
    val state: StateFlow<UiState> = _state

    private var job: Job? = null

    init {
        load(force = false)
    }

    /**
     * 进页 / 失败重试。[force] = true 时忽略 TTL 重新取数，false 时命中缓存就不发网络。
     * 手里已有数据时**静默更新**（不转指示器、不退回骨架），没有数据时才进加载态。
     */
    fun load(force: Boolean) = start(force = force, showIndicator = false)

    /** 下拉刷新：恒 force（忽略 TTL），且**只有这条路**才转顶部指示器。 */
    fun refresh() {
        if (_state.value.refreshing) return
        start(force = true, showIndicator = true)
    }

    private fun start(force: Boolean, showIndicator: Boolean) {
        val previous = _state.value
        job?.cancel()
        _state.value = previous.copy(
            loading = previous.snapshot == null,
            // 指示器只在用户真的下拉过、且屏上有内容时转（首次进页不该闪一个圈）
            refreshing = showIndicator && previous.snapshot != null,
        )
        job = viewModelScope.launch { fetch(force, dwell = showIndicator) }
    }

    private suspend fun fetch(force: Boolean, dwell: Boolean) {
        val startedAt = System.currentTimeMillis()
        val result = repo.load(force = force)
        // 指示器最短驻留：剩余时间补齐再收
        val elapsed = System.currentTimeMillis() - startedAt
        if (dwell && force && elapsed < MIN_REFRESH_MILLIS) delay(MIN_REFRESH_MILLIS - elapsed)
        val snapshot = result.snapshot
        _state.value = UiState(
            loading = false,
            refreshing = false,
            snapshot = snapshot,
            // 只有网络新取才盖新的「更新于」；沿用缓存时用那份数据自己的取数时刻
            loadedAtMs = if (result.fresh) System.currentTimeMillis() else result.atMs,
            // 主动刷了却没取到新数据 = 这份可能过期（首次加载的失败由空态接管）
            stale = force && snapshot != null && !result.fresh,
        )
    }

    class Factory(private val repo: WeatherRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WeatherViewModel(repo) as T
    }
}
