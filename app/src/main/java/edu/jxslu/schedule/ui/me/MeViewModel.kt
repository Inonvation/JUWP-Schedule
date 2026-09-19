package edu.jxslu.schedule.ui.me

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.prefs.DisplayPrefs
import edu.jxslu.schedule.data.repo.ImportPreview
import edu.jxslu.schedule.data.repo.ImportResult
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseFilter
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.TimeSlotRules
import edu.jxslu.schedule.domain.Timetable
import edu.jxslu.schedule.ui.common.ImportTarget
import edu.jxslu.schedule.ui.common.readTextFromUri
import edu.jxslu.schedule.ui.common.resolveImportTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class MeUiState(
    val loading: Boolean = true,
    val semester: SemesterConfig? = null,
    val courseCount: Int = 0,
    val currentWeek: Int = 0,
    /** 当前作息表，供课表设置子页展示与编辑 */
    val timeSlots: List<TimeSlot> = emptyList(),
    /** 主题模式，供设置页三选一 */
    val themeMode: ThemeMode = ThemeMode.System,
    /** 合并后的显示偏好（2026-09-19 起全部为全局项），供显示设置子页 */
    val displayPrefs: DisplayPrefs = DisplayPrefs(),
    /** 当前课表全量课程：显示设置页上半区实时预览用（已按 courseFilter 过滤） */
    val courses: List<Course> = emptyList(),
    /** 当前课表名与全部课表：设置页归属标注与导入目标弹窗用（DESIGN §4.9） */
    val timetableName: String = "",
    val timetables: List<Timetable> = emptyList(),
    val currentTimetableId: Long = 0L,
    val message: String? = null,
)

sealed interface OneShot {
    /** [undo] 非 null 时 UI 以「撤销」Snackbar 呈现，用户点撤销后执行。 */
    data class Message(val text: String, val undo: (suspend () -> Unit)? = null) : OneShot
    data class ConfirmImport(val preview: ImportPreview.Ok, val text: String) : OneShot
}

class MeViewModel(private val repo: ScheduleRepository) : ViewModel() {

    /** 六源合成：combine 无类型安全重载超过 5 个，课表配置先收进一个 data class。 */
    private val configFlow = combine(
        repo.semester,
        repo.courses,
        repo.timeSlots,
        repo.displayPrefs,
        repo.timetables,
    ) { semester, courses, timeSlots, prefs, timetables ->
        MeConfig(semester, courses, timeSlots, prefs, timetables)
    }

    val uiState: StateFlow<MeUiState> = combine(
        configFlow,
        repo.currentTimetableId,
    ) { config, currentTimetableId ->
        val week = config.semester
            ?.let { ScheduleCalculator.weekNumberOf(it, LocalDate.now()) } ?: 0
        MeUiState(
            loading = false,
            semester = config.semester,
            courseCount = config.courses.size,
            currentWeek = week,
            timeSlots = config.timeSlots,
            themeMode = config.prefs.themeMode,
            displayPrefs = config.prefs,
            courses = config.courses.filter { config.prefs.courseFilter.matches(it.kind) },
            timetableName = config.timetables.firstOrNull { it.id == currentTimetableId }?.name.orEmpty(),
            timetables = config.timetables,
            currentTimetableId = currentTimetableId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeUiState())

    private val _oneShot = MutableStateFlow<OneShot?>(null)
    val oneShot: StateFlow<OneShot?> = _oneShot

    private val _pendingImportText = MutableStateFlow<String?>(null)

    fun consumeOneShot() {
        _oneShot.value = null
    }

    fun saveSemester(startDate: String, totalWeeks: Int) {
        viewModelScope.launch {
            val ok = runCatching { ScheduleCalculator.parseDate(startDate) }.isSuccess
            if (!ok) {
                _oneShot.value = OneShot.Message("开学日期格式应为 yyyy-MM-dd，例如 2026-09-07")
                return@launch
            }
            val weeks = totalWeeks.coerceIn(1, 30)
            val old = uiState.value.semester ?: SemesterConfig(startDate, weeks, 1)
            repo.updateSemester(old.copy(startDate = startDate, totalWeeks = weeks))
            _oneShot.value = OneShot.Message("学期设置已保存")
        }
    }

    fun exportJson(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val json = repo.exportJson()
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("无法打开输出流")
            }.onSuccess {
                _oneShot.value = OneShot.Message("已导出课表 JSON")
            }.onFailure {
                _oneShot.value = OneShot.Message("导出失败：${it.message}")
            }
        }
    }

    fun importFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            val text = readTextFromUri(context, uri)
            if (text.isNullOrBlank()) {
                _oneShot.value = OneShot.Message("读取文件失败")
                return@launch
            }
            prepareImport(text)
        }
    }

    fun importFromClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrBlank()) {
            _oneShot.value = OneShot.Message("剪贴板为空")
            return
        }
        viewModelScope.launch { prepareImport(text) }
    }

    private suspend fun prepareImport(text: String) {
        when (val preview = repo.previewImport(text)) {
            is ImportPreview.Error -> _oneShot.value = OneShot.Message(preview.message)
            is ImportPreview.Ok -> {
                _pendingImportText.value = text
                _oneShot.value = OneShot.ConfirmImport(preview, text)
            }
        }
    }

    /**
     * 按弹窗选定的目标导入（DESIGN §4.9）：目标可为已有课表，也可新建
     * （新建时配置取默认配置源）；导入后切到目标课表让用户直接看到结果。
     */
    fun confirmImport(target: ImportTarget, merge: Boolean) {
        val text = _pendingImportText.value ?: return
        _pendingImportText.value = null
        _oneShot.value = null
        viewModelScope.launch {
            val targetId = resolveImportTarget(repo, target)
            when (val r = repo.importJson(text, merge, targetId)) {
                is ImportResult.Success -> {
                    repo.setCurrentTimetable(targetId)
                    _oneShot.value = OneShot.Message(
                        if (merge) "合并完成：新增 ${r.added} / 文件共 ${r.total}"
                        else "已覆盖导入 ${r.total} 门课",
                    )
                }
                is ImportResult.Failure ->
                    _oneShot.value = OneShot.Message(r.message)
            }
        }
    }

    fun cancelPendingImport() {
        _pendingImportText.value = null
        consumeOneShot()
    }

    fun clearCourses() {
        viewModelScope.launch {
            // 快照先行：清空后 Snackbar 里给「撤销」，恢复按原 id/颜色逐门写回
            val snapshot = repo.courses.first()
            repo.clearCourses()
            _oneShot.value = OneShot.Message(
                if (snapshot.isEmpty()) "课表已经是空的" else "已清空 ${snapshot.size} 门课程",
                undo = snapshot.takeIf { it.isNotEmpty() }?.let { list ->
                    { repo.restoreCourses(list) }
                },
            )
        }
    }

    /**
     * 保存作息表。
     * 校验不通过只提示、不写入：脏作息不会让页面崩，只会让课表安静地显示错的时间。
     */
    fun saveTimeSlots(slots: List<TimeSlot>) {
        viewModelScope.launch {
            val err = TimeSlotRules.validate(slots)
            if (err != null) {
                _oneShot.value = OneShot.Message(err)
                return@launch
            }
            repo.saveTimeSlots(slots)
            _oneShot.value = OneShot.Message("作息表已保存")
        }
    }

    fun resetTimeSlots() {
        viewModelScope.launch {
            repo.resetTimeSlotsToDefault()
            _oneShot.value = OneShot.Message("已恢复默认作息")
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repo.setThemeMode(mode) }
    }

    /** 触感反馈开关（全局，写入 DataStore）。 */
    fun setHapticsEnabled(value: Boolean) {
        viewModelScope.launch { repo.setHapticsEnabled(value) }
    }

    /** 动态取色开关（全局，Material You）。 */
    fun setDynamicColor(value: Boolean) {
        viewModelScope.launch { repo.setDynamicColor(value) }
    }

    /** 开水双击确认（全局；默认双击防误触）。 */
    fun setWaterRequireDoubleClick(value: Boolean) {
        viewModelScope.launch { repo.setWaterRequireDoubleClick(value) }
    }

    // ---- 显示设置子页写入口（2026-09-19 起全部写全局，见 DESIGN §4.9 / §3.3） ----

    fun setShowSaturday(value: Boolean) = viewModelScope.launch { repo.setShowSaturday(value) }

    fun setShowSunday(value: Boolean) = viewModelScope.launch { repo.setShowSunday(value) }

    fun setShowAtSign(value: Boolean) = viewModelScope.launch { repo.setShowAtSign(value) }

    fun setTapBlankToAdd(value: Boolean) = viewModelScope.launch { repo.setTapBlankToAdd(value) }

    /** 兼容入口：老调用点一次改两天。 */
    fun setShowWeekend(value: Boolean) = viewModelScope.launch { repo.setShowWeekend(value) }

    fun setShowNonCurrentWeek(value: Boolean) =
        viewModelScope.launch { repo.setShowNonCurrentWeek(value) }

    fun setCourseFilter(value: CourseFilter) = viewModelScope.launch { repo.setCourseFilter(value) }

    fun setGridFontDp(value: Float?) = viewModelScope.launch { repo.setGridFontDp(value) }

    fun setGridRoomDp(value: Float?) = viewModelScope.launch { repo.setGridRoomDp(value) }

    fun setGridTeacherDp(value: Float?) = viewModelScope.launch { repo.setGridTeacherDp(value) }

    fun setGridRailDp(value: Float?) = viewModelScope.launch { repo.setGridRailDp(value) }

    fun setGridDateDp(value: Float?) = viewModelScope.launch { repo.setGridDateDp(value) }

    fun setRowHeightScale(value: Float) = viewModelScope.launch { repo.setRowHeightScale(value) }

    fun setRailWidthDp(value: Float) = viewModelScope.launch { repo.setRailWidthDp(value) }

    fun setDayHeaderHeightDp(value: Float) = viewModelScope.launch { repo.setDayHeaderHeightDp(value) }

    fun setCellRadiusDp(value: Float) = viewModelScope.launch { repo.setCellRadiusDp(value) }

    fun setCellOpacity(value: Float) = viewModelScope.launch { repo.setCellOpacity(value) }

    fun setCellCenterH(value: Boolean) = viewModelScope.launch { repo.setCellCenterH(value) }

    fun setCellCenterV(value: Boolean) = viewModelScope.launch { repo.setCellCenterV(value) }

    fun setShowTeacher(value: Boolean) = viewModelScope.launch { repo.setShowTeacher(value) }

    fun setShowNowLine(value: Boolean) = viewModelScope.launch { repo.setShowNowLine(value) }

    fun setShowCellBorder(value: Boolean) = viewModelScope.launch { repo.setShowCellBorder(value) }

    fun setShowGridLines(value: Boolean) = viewModelScope.launch { repo.setShowGridLines(value) }

    class Factory(private val repo: ScheduleRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MeViewModel(repo) as T
    }
}

/** [MeViewModel.uiState] 的配置切片：combine 类型安全重载最多 5 个参数。 */
private data class MeConfig(
    val semester: SemesterConfig?,
    val courses: List<Course>,
    val timeSlots: List<TimeSlot>,
    val prefs: DisplayPrefs,
    val timetables: List<Timetable>,
)

fun meFactory(context: Context) = MeViewModel.Factory(Graph.repository(context))
