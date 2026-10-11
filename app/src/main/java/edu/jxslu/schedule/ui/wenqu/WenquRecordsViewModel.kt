package edu.jxslu.schedule.ui.wenqu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.data.wenqu.WenquActivity
import edu.jxslu.schedule.data.wenqu.WenquRepository
import edu.jxslu.schedule.data.wenqu.WenquVolunteerRecord
import edu.jxslu.schedule.data.wenqu.asActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「我的志愿服务记录」页状态机（DESIGN §3.28）。
 *
 * 与问渠主页各持一份状态、各自独立：**同一份记录数据来自仓库的同一份缓存**
 * （[WenquRepository.volunteer]），所以从主页点进来不会再多打一次接口。
 *
 * 详情走 [WenquRepository.activityDetail]（记录的 `id` 就是活动 id，见
 * [WenquVolunteerRecord.asActivity]）；详情失败**保留记录本身的数据**，弹层不空掉。
 */
class WenquRecordsViewModel(private val repo: WenquRepository) : ViewModel() {

    data class UiState(
        val records: List<WenquVolunteerRecord> = emptyList(),
        /** 首屏取数中（页面骨架）。 */
        val loading: Boolean = true,
        /** 顶栏刷新进行中。 */
        val refreshing: Boolean = false,
        /** 弹层里的那条记录（非 null = 弹层开着）。 */
        val opened: WenquVolunteerRecord? = null,
        /** 详情接口回来的活动（未回来时用 [opened] 自己顶着）。 */
        val detail: WenquActivity? = null,
        val detailLoading: Boolean = false,
        /**
         * 详情**取不到**（平台回 `detail: null`，或网络失败）。
         * 不是错误——弹层里已经有记录本身的信息，只在弹层内挂一行说明，
         * **不飘白色提醒**（2026-10-08 用户报）。
         */
        val detailUnavailable: Boolean = false,
        /** 取消报名进行中（弹层里的按钮转圈 + 防连点）。 */
        val applying: Boolean = false,
        /** 取消失败的原因（平台原话），留在弹层里——页面级提示会被弹层盖住。 */
        val detailActionError: String? = null,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load(force = false)
    }

    /**
     * 取记录。**非 force 时走仓库的 30 分钟缓存**——从问渠主页点进来通常一个请求都不发。
     * 失败**不清旧数据**（宁旧勿空）。
     */
    fun load(force: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.records.isEmpty(), refreshing = force) }
            try {
                val volunteer = repo.volunteer(force)
                _state.update {
                    it.copy(loading = false, refreshing = false, records = volunteer.list)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, refreshing = false, message = e.message ?: "取数失败")
                }
            }
        }
    }

    /**
     * 打开某条记录的详情：记录本身先顶着（弹层立刻有内容），详情回来再补齐。
     * 详情取不到**不报错**（弹层里已经有记录那条的信息），只在弹层内挂一行说明。
     * 自主（DIY）类记录没有关联活动（id = 0），本地字段已经够看，不发注定失败的请求。
     */
    fun openDetail(record: WenquVolunteerRecord) {
        _state.update {
            it.copy(
                opened = record,
                detail = null,
                detailLoading = record.id > 0L,
                detailUnavailable = record.id <= 0L,
                detailActionError = null,
            )
        }
        if (record.id <= 0L) return
        viewModelScope.launch {
            try {
                val full = repo.activityDetail(record.id)
                _state.update {
                    if (it.opened?.id != record.id) {
                        it
                    } else {
                        it.copy(
                            detail = full,
                            detailLoading = false,
                            detailUnavailable = full == null,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 静默：弹层里已经有记录本身的信息，不该再飘一个白色提醒
                _state.update {
                    if (it.opened?.id == record.id) {
                        it.copy(detailLoading = false, detailUnavailable = true)
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun closeDetail() {
        _state.update {
            it.copy(
                opened = null,
                detail = null,
                detailLoading = false,
                detailUnavailable = false,
                detailActionError = null,
            )
        }
    }

    /**
     * **取消报名**（DESIGN §4.45）。
     *
     * 记录视角的取消入口以平台给的 `canCancel` 为准（只有 `volunteer/my` 回这个字段），
     * 页面侧已经据此决定要不要给按钮；这里只负责发请求。
     *
     * 成功：关弹层 + 页面提示 + 记录列表刷新（那条报名从平台移除了，得重新取）。
     * 失败：原因留在弹层里。
     */
    fun cancelApply() {
        val record = _state.value.opened ?: return
        if (record.id <= 0L || _state.value.applying) return
        // **同步扣闸**（同 WenquViewModel.applyActivity）：连点两下时第二下进不来
        _state.update { it.copy(applying = true, detailActionError = null) }
        viewModelScope.launch {
            try {
                repo.cancelApply(record.id, record.applyId)
                _state.update {
                    it.copy(
                        applying = false,
                        opened = null,
                        detail = null,
                        detailLoading = false,
                        message = "已取消报名",
                    )
                }
                // 报名没了 → 记录列表要重取（走仓库的 force，别被 30 分钟缓存挡回去）
                load(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(applying = false, detailActionError = e.message ?: "取消失败")
                }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    class Factory(private val repo: WenquRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WenquRecordsViewModel(repo) as T
    }
}

/** 记录视角的展示数据：详情接口回来且 id 对得上就用它，否则用记录本身（[asActivity]）。 */
internal fun WenquRecordsViewModel.UiState.detailActivity(): WenquActivity? {
    val record = opened ?: return null
    return detail?.takeIf { it.id == record.id } ?: record.asActivity()
}
