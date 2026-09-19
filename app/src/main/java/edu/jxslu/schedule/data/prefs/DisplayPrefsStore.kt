package edu.jxslu.schedule.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import edu.jxslu.schedule.domain.CalendarSyncDefaults
import edu.jxslu.schedule.domain.CourseFilter
import edu.jxslu.schedule.domain.ReminderDefaults
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.domain.TimetablePrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 显示偏好（对 UI 的合并视图）。
 *
 * 2026-09-19 起（DESIGN §4.9）**全部字段都是全局项**：视图偏好与主题一样存 DataStore
 * （`view_prefs_json`，同一 [TimetablePrefs] JSON 结构），不再按课表分开——换课表不换观感。
 * ScheduleRepository 把全局视图偏好组装成本类，WeekScreen 等调用点不感知存储位置。
 */
data class DisplayPrefs(
    /** 应用主题模式。System = 跟随系统深浅色。全局项。 */
    val themeMode: ThemeMode = ThemeMode.System,
    /** 动态取色（Material You）。全局项；false = 用回内置蓝绿方案。 */
    val dynamicColor: Boolean = true,
    /**
     * 触感反馈开关。全局项（交互手感不随课表变）。
     * 默认开：点击类操作给轻触感是系统应用的普遍预期，嫌吵的人再关。
     */
    val hapticsEnabled: Boolean = true,
    /**
     * 开水按钮需双击确认。全局项，默认开：一键出水误触代价高，
     * 双击挡单击误触；嫌麻烦可在「我的 → 胖乖生活」改回单击。
     */
    val waterRequireDoubleClick: Boolean = true,
    /**
     * 【遗留】周末显示单开关。语义 = [showSaturday] && [showSunday]，由 setter 保持同步。
     *
     * 根因：这里**不能**写成 `val showWeekend get() = ...` 的派生属性——
     * data class 主构造参数必须带 backing field，自定义 getter 会直接语法错误；
     * 且它本就是旧数据源与既有调用点读的字段，仍需真实存储。
     * 新代码请直接用两个独立开关。
     */
    val showWeekend: Boolean = true,
    /** 显示星期六。与 [showSunday] 独立。 */
    val showSaturday: Boolean = true,
    /** 显示星期日。与 [showSaturday] 独立。 */
    val showSunday: Boolean = true,
    /** 显示非本周课程（灰态）。默认关：只关心本周上什么。 */
    val showNonCurrentWeek: Boolean = false,
    /** 只看某类课程。默认全部：实验课和理论课都是要去上的课，不该被默认藏起来。 */
    val courseFilter: CourseFilter = CourseFilter.All,
    /**
     * 课名目标字号（dp）。null = 跟随系统字体设置（未拖过滑块）。
     * 取值范围与含义见 [edu.jxslu.schedule.domain.GridFont]。
     */
    val gridFontDp: Float? = null,
    /** 地点目标字号（dp）；null = 按课名倍率等比缩放。 */
    val gridRoomDp: Float? = null,
    /** 教师目标字号（dp）；null = 按课名倍率等比缩放。 */
    val gridTeacherDp: Float? = null,
    /** 时间轴（节次号 + 起止时间）目标字号（dp）；null = 跟随系统。独立于课名字号。 */
    val gridRailDp: Float? = null,
    /** 月份 / 日期表头目标字号（dp）；null = 跟随系统。独立于课名字号。 */
    val gridDateDp: Float? = null,
    /** 格子高度倍率，乘在自适应行高上；1.1f = 默认（比自适应高 10%）。范围见滑块（0.5–1.5）。 */
    val rowHeightScale: Float = 1.1f,
    /** 左侧时间轴栏宽（dp）。范围与默认值见 [TimetablePrefs] companion（单一来源）。 */
    val railWidthDp: Float = TimetablePrefs.DefaultRailWidthDp,
    /** 顶部表头（星期+日期）高度（dp）。参与自适应行高计算。 */
    val dayHeaderHeightDp: Float = TimetablePrefs.DefaultDayHeaderHeightDp,
    /** 格子圆角半径（dp）。默认 6f 与 DESIGN 3.2 的色块圆角一致。 */
    val cellRadiusDp: Float = 6f,
    /** 格子（色块）不透明度；下限由滑块保证，太低白字在浅底上不可读。 */
    val cellOpacity: Float = 1f,
    /** 格子文字水平居中。默认开；关 = WakeUp 式左对齐。 */
    val cellCenterH: Boolean = true,
    /** 格子文字竖直居中。默认开；关 = 顶部起排、教师沉底。 */
    val cellCenterV: Boolean = true,
    /** 色块内是否显示授课教师。 */
    val showTeacher: Boolean = true,
    /** 是否显示当前时刻线。 */
    val showNowLine: Boolean = true,
    /** 是否显示课程色块的虚线描边。默认关：部分场景观感发糊，WakeUp 式描边降为可选项。 */
    val showCellBorder: Boolean = false,
    /** 是否显示网格行分隔辅助线。 */
    val showGridLines: Boolean = true,
    /** 地点前是否显示「@」前缀。 */
    val showAtSign: Boolean = true,
    /** 点击课表空白格是否新建课程。默认关：横滑切周易误触，加课走导入弹层/课程编辑。 */
    val tapBlankToAdd: Boolean = false,
)

// preferencesDataStore 是属性委托，必须用 by；一个文件只能声明一份，重复实例化同一文件会崩溃。
private val Context.displayDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "juw_display")

/**
 * 全局偏好存储（DESIGN §4.9）。
 *
 * 2026-09-19 起显示偏好（原课表级视图偏好）也在这里：整体以 [TimetablePrefs] 的 JSON
 * 存 `view_prefs_json`（结构不变，只换位置），随 [viewPrefs]/[updateViewPrefs] 读写。
 * v3 时期搬进 `timetables.prefs_json` 的那份保留在库里但不再读写；
 * 一次性迁移（`view_prefs_globalized` 标记）把旧全局键或当前课表行的值搬进本键。
 */
class DisplayPrefsStore(private val context: Context) {

    /**
     * 全局显示偏好（原课表级）。JSON 解码失败退默认值：
     * 这是自己写自己的数据，真坏了也不该让读路径抛异常崩掉课表页。
     */
    val viewPrefs: Flow<TimetablePrefs> = context.displayDataStore.data.map { p ->
        p[KEY_VIEW_PREFS_JSON]?.let { TimetablePrefs.decode(it) } ?: TimetablePrefs()
    }

    /**
     * 统一写入口：读-改-写整个 JSON。同值跳写——滑块拖动时 onValueChange 每个采样点
     * 都会调一次，停在同吸附格的重复写直接跳过（结果与存储一致，不影响任何观察者）。
     */
    suspend fun updateViewPrefs(transform: (TimetablePrefs) -> TimetablePrefs) {
        context.displayDataStore.edit { p ->
            val current = p[KEY_VIEW_PREFS_JSON]?.let { TimetablePrefs.decode(it) } ?: TimetablePrefs()
            val next = transform(current)
            if (next != current) p[KEY_VIEW_PREFS_JSON] = next.encode()
        }
    }

    /** 迁移专用：直接落一份初始值（仅 `globalizeViewPrefs` 一次性调用）。 */
    suspend fun setInitialViewPrefs(value: TimetablePrefs) {
        context.displayDataStore.edit { it[KEY_VIEW_PREFS_JSON] = value.encode() }
    }

    /** 旧全局值/课表行值是否已搬进 `view_prefs_json`。 */
    suspend fun viewPrefsGlobalized(): Boolean =
        context.displayDataStore.data.first()[KEY_VIEW_PREFS_GLOBALIZED] ?: false

    suspend fun setViewPrefsGlobalized() {
        context.displayDataStore.edit { it[KEY_VIEW_PREFS_GLOBALIZED] = true }
    }

    /** 应用主题。全局项：换课表不换深浅色。 */
    val themeMode: Flow<ThemeMode> = context.displayDataStore.data.map { p ->
        themeModeFromName(p[KEY_THEME_MODE])
    }

    /** 触感反馈开关。全局项：交互手感属于设备级偏好，不随课表切换。 */
    val hapticsEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_HAPTICS_ENABLED] ?: true
    }

    /** 动态取色（Material You）。全局项，默认开；关 = 用回内置蓝绿方案。 */
    val dynamicColor: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_DYNAMIC_COLOR] ?: true
    }

    /** 开水双击确认。全局项，默认双击防误触。 */
    val waterRequireDoubleClick: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_WATER_REQUIRE_DOUBLE_CLICK] ?: true
    }

    /** 上课提醒开关（DESIGN §3.7）。全局项，默认关：通知是打扰型能力，用户显式开启。 */
    val reminderEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_REMINDER_ENABLED] ?: false
    }

    /** 上课提醒提前量（分钟）。全局项；读路径吸附到候选档，防线脏数据。 */
    val reminderLeadMinutes: Flow<Int> = context.displayDataStore.data.map { p ->
        ReminderDefaults.coerceLead(p[KEY_REMINDER_LEAD] ?: ReminderDefaults.DEFAULT_LEAD_MINUTES)
    }

    /**
     * 日历同步的提前提醒分钟数（DESIGN §4.12）。全局项，默认 20，0 = 不提醒。
     * 值域 0–120 / 步长 5 的口径单一来源是 [CalendarSyncDefaults]，读路径先夹取防线。
     */
    val calendarReminderMinutes: Flow<Int> = context.displayDataStore.data.map { p ->
        CalendarSyncDefaults.coerceReminderMinutes(
            p[KEY_CALENDAR_REMINDER_MINUTES] ?: CalendarSyncDefaults.DEFAULT_REMINDER_MINUTES,
        )
    }

    /** 当前课表。null = 未设置（用默认课表 1）。 */
    val currentTimetableId: Flow<Long?> = context.displayDataStore.data.map { p ->
        p[KEY_CURRENT_TIMETABLE]
    }

    /** 新建课表的默认配置源。null = 用内置默认配置。 */
    val defaultConfigSourceId: Flow<Long?> = context.displayDataStore.data.map { p ->
        p[KEY_DEFAULT_CONFIG_SOURCE]
    }

    suspend fun setThemeMode(value: ThemeMode) {
        context.displayDataStore.edit { it[KEY_THEME_MODE] = value.name }
    }

    suspend fun setHapticsEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_HAPTICS_ENABLED] = value }
    }

    suspend fun setDynamicColor(value: Boolean) {
        context.displayDataStore.edit { it[KEY_DYNAMIC_COLOR] = value }
    }

    suspend fun setWaterRequireDoubleClick(value: Boolean) {
        context.displayDataStore.edit { it[KEY_WATER_REQUIRE_DOUBLE_CLICK] = value }
    }

    /** 保存日历提醒分钟数（0 = 不提醒）；夹取到 0–120。 */
    suspend fun setCalendarReminderMinutes(value: Int) {
        context.displayDataStore.edit {
            it[KEY_CALENDAR_REMINDER_MINUTES] = CalendarSyncDefaults.coerceReminderMinutes(value)
        }
    }

    suspend fun setReminderEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_REMINDER_ENABLED] = value }
    }

    suspend fun setReminderLeadMinutes(value: Int) {
        context.displayDataStore.edit {
            it[KEY_REMINDER_LEAD] = ReminderDefaults.coerceLead(value)
        }
    }

    /**
     * 上课提醒的「已发键」（DESIGN §3.7）：闹钟与 15 分钟周期核对共用去重，
     * 同一节课同一天只发一次。null = 从未发过。
     */
    suspend fun reminderLastKey(): String? =
        context.displayDataStore.data.first()[KEY_REMINDER_LAST]

    suspend fun setReminderLastKey(key: String) {
        context.displayDataStore.edit { it[KEY_REMINDER_LAST] = key }
    }

    /** 小组件设置页的「首次进入引导」是否已弹过（DESIGN §3.6）。全局键。 */
    suspend fun widgetSetupSeen(): Boolean =
        context.displayDataStore.data.first()[KEY_WIDGET_SETUP_SEEN] ?: false

    suspend fun setWidgetSetupSeen() {
        context.displayDataStore.edit { it[KEY_WIDGET_SETUP_SEEN] = true }
    }

    suspend fun setCurrentTimetable(id: Long) {
        context.displayDataStore.edit { it[KEY_CURRENT_TIMETABLE] = id }
    }

    suspend fun setDefaultConfigSource(id: Long?) {
        context.displayDataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_DEFAULT_CONFIG_SOURCE) else prefs[KEY_DEFAULT_CONFIG_SOURCE] = id
        }
    }

    /** 作息表结构版本，用于判断是否需要把老的 5 条大节作息升级成 11 条小节。全局键。 */
    suspend fun slotSchemaVersion(): Int =
        context.displayDataStore.data.first()[KEY_SLOT_SCHEMA] ?: 0

    suspend fun setSlotSchemaVersion(version: Int) {
        context.displayDataStore.edit { it[KEY_SLOT_SCHEMA] = version }
    }

    /**
     * 【遗留】旧版全局的「用户改过作息」标记。
     * 仅在 v3 一次性迁移里被读取（映射为课表 1 的 slotsCustomized），此后不再使用。
     */
    suspend fun legacySlotCustomized(): Boolean =
        context.displayDataStore.data.first()[KEY_SLOT_CUSTOMIZED] ?: false

    /** 课表级偏好的旧全局值是否已搬迁到课表 1。 */
    suspend fun prefsMigrated(): Boolean =
        context.displayDataStore.data.first()[KEY_PREFS_MIGRATED] ?: false

    suspend fun setPrefsMigrated(value: Boolean) {
        context.displayDataStore.edit { it[KEY_PREFS_MIGRATED] = value }
    }

    /**
     * 【遗留】旧版全局的显示偏好。
     * 只在 `globalizeViewPrefs` 一次性迁移里被读取（v2 直升用户的数据源）；
     * 已搬迁过（v3 时期迁到课表行，或已进 `view_prefs_json`）返回 null，旧键不再可信。
     * 读路径夹取逻辑沿用旧实现（防旧数据超出收紧后的滑块范围让 Slider 崩）。
     */
    suspend fun legacyViewPrefs(): TimetablePrefs? {
        if (prefsMigrated()) return null
        val p = context.displayDataStore.data.first()
        val weekend = p[KEY_SHOW_WEEKEND] ?: true
        return TimetablePrefs(
            showWeekend = weekend,
            showSaturday = weekend,
            showSunday = weekend,
            // 旧键只有单开关，展开后即为终态，显式落标记免得再被 decode 的迁移逻辑重跑一遍。
            weekendSplitMigrated = true,
            showNonCurrentWeek = p[KEY_SHOW_NON_CURRENT] ?: false,
            courseFilter = courseFilterFromName(p[KEY_COURSE_FILTER]),
            gridFontDp = p[KEY_GRID_FONT_DP]
                ?: p[KEY_GRID_FONT_SCALE]?.let { (it * 11f).coerceIn(8f, 32f) },
            gridRoomDp = p[KEY_GRID_ROOM_DP],
            gridTeacherDp = p[KEY_GRID_TEACHER_DP],
            rowHeightScale = (p[KEY_ROW_HEIGHT_SCALE] ?: 1.1f).coerceIn(0.5f, 1.5f),
            cellRadiusDp = (p[KEY_CELL_RADIUS_DP] ?: 6f).coerceIn(0f, 12f),
            cellOpacity = (p[KEY_CELL_OPACITY] ?: 1f).coerceIn(0.5f, 1f),
            cellCenterH = p[KEY_CELL_CENTER_H] ?: true,
            cellCenterV = p[KEY_CELL_CENTER_V] ?: true,
            showTeacher = p[KEY_SHOW_TEACHER] ?: true,
            showNowLine = p[KEY_SHOW_NOW_LINE] ?: true,
            showCellBorder = p[KEY_SHOW_CELL_BORDER] ?: false,
            showGridLines = p[KEY_SHOW_GRID_LINES] ?: true,
        )
    }

    /** 未知值一律退回「跟随系统」：宁可让主题跟随系统，也不要因为脏数据变成不可控的深色。 */
    private fun themeModeFromName(name: String?): ThemeMode =
        ThemeMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: ThemeMode.System

    /** 未知值一律退回「全部」：宁可多显示，也不要因为脏数据把课藏没了。 */
    private fun courseFilterFromName(name: String?): CourseFilter =
        CourseFilter.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: CourseFilter.All

    private companion object {
        // ---- 全局项（现行有效） ----
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
        val KEY_WATER_REQUIRE_DOUBLE_CLICK = booleanPreferencesKey("water_require_double_click")
        val KEY_CALENDAR_REMINDER_MINUTES = intPreferencesKey("calendar_reminder_minutes")
        val KEY_REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val KEY_REMINDER_LEAD = intPreferencesKey("reminder_lead_minutes")
        val KEY_REMINDER_LAST = stringPreferencesKey("reminder_last_key")
        val KEY_CURRENT_TIMETABLE = longPreferencesKey("current_timetable_id")
        val KEY_DEFAULT_CONFIG_SOURCE = longPreferencesKey("default_config_source_id")
        val KEY_SLOT_SCHEMA = intPreferencesKey("slot_schema_version")
        val KEY_PREFS_MIGRATED = booleanPreferencesKey("timetable_prefs_migrated")
        val KEY_WIDGET_SETUP_SEEN = booleanPreferencesKey("widget_setup_seen")

        // ---- 全局显示偏好（2026-09-19 起；原课表级 prefs_json 的接棒者） ----
        val KEY_VIEW_PREFS_JSON = stringPreferencesKey("view_prefs_json")
        val KEY_VIEW_PREFS_GLOBALIZED = booleanPreferencesKey("view_prefs_globalized")

        // ---- 遗留（仅作 v3 一次性迁移的数据源，不再写入、迁移后不再读取） ----
        val KEY_SLOT_CUSTOMIZED = booleanPreferencesKey("slot_customized")
        val KEY_SHOW_WEEKEND = booleanPreferencesKey("show_weekend")
        val KEY_SHOW_NON_CURRENT = booleanPreferencesKey("show_non_current_week")
        val KEY_COURSE_FILTER = stringPreferencesKey("course_filter")
        val KEY_GRID_FONT_DP = floatPreferencesKey("grid_font_dp")
        val KEY_GRID_ROOM_DP = floatPreferencesKey("grid_room_dp")
        val KEY_GRID_TEACHER_DP = floatPreferencesKey("grid_teacher_dp")

        /** 已废弃：v1 的字号倍率（0.85–1.35），仅作读路径迁移来源，不再写入。 */
        val KEY_GRID_FONT_SCALE = floatPreferencesKey("grid_font_scale")
        val KEY_ROW_HEIGHT_SCALE = floatPreferencesKey("row_height_scale")
        val KEY_CELL_RADIUS_DP = floatPreferencesKey("cell_radius_dp")
        val KEY_CELL_OPACITY = floatPreferencesKey("cell_opacity")
        val KEY_CELL_CENTER_H = booleanPreferencesKey("cell_center_horizontal")
        val KEY_CELL_CENTER_V = booleanPreferencesKey("cell_center_vertical")
        val KEY_SHOW_TEACHER = booleanPreferencesKey("show_teacher")
        val KEY_SHOW_NOW_LINE = booleanPreferencesKey("show_now_line")
        val KEY_SHOW_CELL_BORDER = booleanPreferencesKey("show_cell_border")
        val KEY_SHOW_GRID_LINES = booleanPreferencesKey("show_grid_lines")
    }
}
