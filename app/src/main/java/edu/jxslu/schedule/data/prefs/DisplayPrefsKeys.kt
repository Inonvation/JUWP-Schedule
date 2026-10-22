package edu.jxslu.schedule.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

// DataStore 键集中一处：改键名 = 老用户偏好丢失，必须同时想迁移。

// preferencesDataStore 是属性委托，必须用 by；一个文件只能声明一份，重复实例化同一文件会崩溃。
internal val Context.displayDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "juw_display")
        // ---- 全局项（现行有效） ----
internal val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
internal val KEY_HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        /** 字体缩放锁定（「固定字体大小」）：缺省 = true（锁定）。 */
internal val KEY_FONT_SCALE_LOCKED = booleanPreferencesKey("font_scale_locked")
internal val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
internal val KEY_THEME_PALETTE = stringPreferencesKey("theme_palette")
internal val KEY_FLOATING_NAV_BAR = booleanPreferencesKey("floating_nav_bar")
        /** 百宝箱（DESIGN §4.47）：缺省 = true（今日页「+」里显示图片工具入口）。 */
internal val KEY_TOOLBOX_ENABLED = booleanPreferencesKey("toolbox_enabled")
        // 应用内悬浮球（DESIGN §3.1）：开关 + 归一化位置（缺省 = 右下角，见 floatingBallPosition）
internal val KEY_FLOATING_BALL_ENABLED = booleanPreferencesKey("floating_ball_enabled")
internal val KEY_FLOATING_BALL_X = floatPreferencesKey("floating_ball_x")
internal val KEY_FLOATING_BALL_Y = floatPreferencesKey("floating_ball_y")
internal val KEY_START_PAGE = stringPreferencesKey("start_page")
internal val KEY_WATER_REQUIRE_DOUBLE_CLICK = booleanPreferencesKey("water_require_double_click")
internal val KEY_CALENDAR_REMINDER_MINUTES = intPreferencesKey("calendar_reminder_minutes")
internal val KEY_REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
internal val KEY_REMINDER_LEAD = intPreferencesKey("reminder_lead_minutes")
internal val KEY_REMINDER_LAST = stringPreferencesKey("reminder_last_key")
internal val KEY_HOMEWORK_REMINDER_ENABLED = booleanPreferencesKey("homework_reminder_enabled")
internal val KEY_HOMEWORK_REMINDED_KEYS = stringSetPreferencesKey("homework_reminded_keys")
internal val KEY_EBIKE_FREE_REMINDER_ENABLED = booleanPreferencesKey("ebike_free_reminder_enabled")
internal val KEY_STRONG_REMINDER_VIBRATION = booleanPreferencesKey("strong_reminder_vibration")
internal val KEY_QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
internal val KEY_QUIET_HOURS_JSON = stringPreferencesKey("quiet_hours_json")
internal val KEY_AUTO_UPDATE_CHECK_ENABLED = booleanPreferencesKey("auto_update_check_enabled")
internal val KEY_IGNORED_UPDATE_VERSION = stringPreferencesKey("ignored_update_version")
        /** 教务 WebView 电脑版预览（DESIGN §4.4；语义见 [jwDesktopMode]）。 */
internal val KEY_JW_DESKTOP_MODE = booleanPreferencesKey("jw_desktop_mode")
internal val KEY_EBIKE_FREE_LEAD = intPreferencesKey("ebike_free_lead_minutes")
internal val KEY_EBIKE_RIDE_START_AT = longPreferencesKey("ebike_ride_start_at")
        // 用车满 15 分钟自动还车（DESIGN §3.9，2026-10-10）：开关 / 触发提前量（秒）/
        // 失败重试次数 / 「已执行」键（同一轮骑行只跑一次，见 EbikeAutoReturn.doneKey）。
internal val KEY_EBIKE_AUTO_RETURN_ENABLED = booleanPreferencesKey("ebike_auto_return_enabled")
internal val KEY_EBIKE_AUTO_RETURN_LEAD_SECONDS = intPreferencesKey("ebike_auto_return_lead_seconds")
internal val KEY_EBIKE_AUTO_RETURN_RETRIES = intPreferencesKey("ebike_auto_return_retries")
internal val KEY_EBIKE_AUTO_RETURN_DONE_KEY = stringPreferencesKey("ebike_auto_return_done_key")

        // 2026-09-24 提醒改回 App 通知：日历事件 id（ebike_free_event_id）随之停用，
        // 键定义一并删掉——旧设备上的残留值没人读，也不会自己消失，但无害。
internal val KEY_EBIKE_FREE_NOTIFIED_KEYS = stringSetPreferencesKey("ebike_free_notified_keys")

        /** 骑行卡券到期提醒的「已提醒键」集合（DESIGN §4.32，键见 [EbikeCardExpiry.dedupKey]）。 */
internal val KEY_EBIKE_CARD_EXPIRY_NOTIFIED = stringSetPreferencesKey("ebike_card_expiry_notified")

        /** 开锁前「没有可用骑行卡」提醒的今日静音键（YYYY-MM-DD，见 [ebikeNoCardNoticeDate]）。 */
internal val KEY_EBIKE_NO_CARD_NOTICE_DATE = stringPreferencesKey("ebike_no_card_notice_date")

        // 「精确倒计时」已删（2026-09-30）：开关与校准标记两键
        // （ebike_precise_countdown_enabled / ebike_precise_calibrated_at）随之停用，
        // 同上面日历事件 id 的口径——旧设备上的残留值没人读，无害。
internal val KEY_CURRENT_TIMETABLE = longPreferencesKey("current_timetable_id")
internal val KEY_DEFAULT_CONFIG_SOURCE = longPreferencesKey("default_config_source_id")
internal val KEY_SLOT_SCHEMA = intPreferencesKey("slot_schema_version")
internal val KEY_PREFS_MIGRATED = booleanPreferencesKey("timetable_prefs_migrated")
internal val KEY_WIDGET_SETUP_SEEN = booleanPreferencesKey("widget_setup_seen")
internal val KEY_SHORTCUTS_ENABLED = booleanPreferencesKey("shortcuts_enabled")
internal val KEY_WEATHER_ENABLED = booleanPreferencesKey("weather_enabled")
internal val KEY_EBIKE_USE_MODE = stringPreferencesKey("ebike_use_mode")
internal val KEY_EBIKE_AUTO_SAVE = booleanPreferencesKey("ebike_auto_save")
internal val KEY_EBIKE_BURN_AFTER_SCAN = booleanPreferencesKey("ebike_burn_after_scan")
internal val KEY_EBIKE_UNLOCK_CONFIRM = booleanPreferencesKey("ebike_unlock_confirm")
internal val KEY_EBIKE_LOCATION_ASKED = booleanPreferencesKey("ebike_location_asked")
internal val KEY_UJING_NOTICE_SEEN_AT = longPreferencesKey("ujing_notice_seen_at")
internal val KEY_EBIKE_MAP_ONLY_AVAILABLE = booleanPreferencesKey("ebike_map_only_available")
internal val KEY_EBIKE_MAP_ONLY_OUR_CAMPUS = booleanPreferencesKey("ebike_map_only_our_campus")
internal val KEY_EBIKE_VIEW_LAT = floatPreferencesKey("ebike_view_lat")
internal val KEY_EBIKE_VIEW_LNG = floatPreferencesKey("ebike_view_lng")
internal val KEY_EBIKE_VIEW_ZOOM = floatPreferencesKey("ebike_view_zoom")
internal val KEY_EBIKE_PANEL_HEIGHT_DP = floatPreferencesKey("ebike_panel_height_dp")
        /** 保存视野时的缩放范围，与瓦片源的 1~19 对齐。 */
        const val MIN_MAP_ZOOM = 1.0f
        const val MAX_MAP_ZOOM = 19.0f
internal val KEY_EBIKE_PENDING_DELETE = stringSetPreferencesKey("ebike_pending_delete")
internal val KEY_EBIKE_RECENT_IDS = stringPreferencesKey("ebike_recent_ids")
        /** 菜鸟批量查的最近提交（DESIGN §4.47）：JSON 数组，缺省 = 无历史。 */
internal val KEY_CAINIAO_QUERY_HISTORY = stringPreferencesKey("cainiao_query_history")
internal val KEY_CAMPUS_CARD_ENABLED = booleanPreferencesKey("campus_card_enabled")
        // 余额提醒（DESIGN §3.10 / §3.13）：两个来源各一套开关 / 阈值 / 检查间隔 /
        // 上次成功检查时刻 / 偏低已提醒标记。*_LAST_CHECK 是 2026-10-07 前的日期级旧键，
        // 只作 millis 键缺省时的读取回退，不再写入（无一次性迁移）。
internal val KEY_POWER_ALERT_ENABLED = booleanPreferencesKey("power_alert_enabled")
internal val KEY_POWER_ALERT_YUAN = intPreferencesKey("power_alert_yuan")
internal val KEY_POWER_ALERT_LAST_CHECK = stringPreferencesKey("power_alert_last_check")
internal val KEY_YKT_ALERT_ENABLED = booleanPreferencesKey("ykt_alert_enabled")
internal val KEY_YKT_ALERT_YUAN = intPreferencesKey("ykt_alert_yuan")
internal val KEY_YKT_ALERT_LAST_CHECK = stringPreferencesKey("ykt_alert_last_check")
        // 检查间隔（小时，两链各自独立）+ 已排周期档（0 = 没在排）+ 闸门与提醒标记
internal val KEY_POWER_ALERT_INTERVAL_HOURS = intPreferencesKey("power_alert_interval_hours")
internal val KEY_YKT_ALERT_INTERVAL_HOURS = intPreferencesKey("ykt_alert_interval_hours")
internal val KEY_POWER_ALERT_CHECK_MILLIS = longPreferencesKey("power_alert_check_millis")
internal val KEY_YKT_ALERT_CHECK_MILLIS = longPreferencesKey("ykt_alert_check_millis")
internal val KEY_POWER_ALERT_NOTIFIED = booleanPreferencesKey("power_alert_notified")
internal val KEY_YKT_ALERT_NOTIFIED = booleanPreferencesKey("ykt_alert_notified")
internal val KEY_BALANCE_PERIODIC_INTERVAL = intPreferencesKey("balance_periodic_interval")
internal val KEY_RECHARGE_DISCLAIMER_SUPPRESS_UNTIL = longPreferencesKey("recharge_disclaimer_suppress_until")
internal val KEY_RECHARGE_DISCLAIMER_SEEN_AT = longPreferencesKey("recharge_disclaimer_seen_at")
        // 首启声明的同意记录（DESIGN §3.16）：每份须知各一对「同意过的版本 / 确认时刻」。
        // 版本号用来判要不要重弹（文案改了 bump `FirstRunNotices.VERSION`），时刻用来展示与核对。
internal val KEY_USER_NOTICE_VERSION = intPreferencesKey("user_notice_version")
internal val KEY_USER_NOTICE_SEEN_AT = longPreferencesKey("user_notice_seen_at")
internal val KEY_DISCLAIMER_VERSION = intPreferencesKey("disclaimer_version")
internal val KEY_DISCLAIMER_SEEN_AT = longPreferencesKey("disclaimer_seen_at")
internal val KEY_PENDING_RECHARGE_FEN = longPreferencesKey("pending_recharge_fen")
internal val KEY_PENDING_RECHARGE_AT = longPreferencesKey("pending_recharge_at")
internal val KEY_PENDING_RECHARGE_BASE_FEN = longPreferencesKey("pending_recharge_base_fen")
internal val KEY_PENDING_RECHARGE_ACCOUNT = stringPreferencesKey("pending_recharge_account")
internal val KEY_PENDING_RECHARGE_TARGET = stringPreferencesKey("pending_recharge_target")
internal val KEY_PENDING_RECHARGE_WALLET_BASE_FEN = longPreferencesKey("pending_recharge_wallet_base_fen")
internal val KEY_PENDING_RECHARGE_CONFIRM_SHOWN = booleanPreferencesKey("pending_recharge_confirm_shown")
internal val KEY_SHORTCUTS_JSON = stringPreferencesKey("shortcuts_json")
internal val KEY_SCORE_INCLUDE_FREE_ELECTIVES = booleanPreferencesKey("score_include_free_electives")
internal val KEY_SCORE_GROUP_BY_YEAR = booleanPreferencesKey("score_group_by_year")
internal val KEY_SCORE_SORT_MODE = stringPreferencesKey("score_sort_mode")
internal val KEY_PROFILE_NAME = stringPreferencesKey("profile_name")
internal val KEY_PROFILE_CLASS = stringPreferencesKey("profile_class")
internal val KEY_PROFILE_STUDENT_ID = stringPreferencesKey("profile_student_id")
internal val KEY_PROFILE_COLLEGE = stringPreferencesKey("profile_college")
internal val KEY_PROFILE_MAJOR = stringPreferencesKey("profile_major")
internal val KEY_PROFILE_DURATION = stringPreferencesKey("profile_duration")
internal val KEY_PROFILE_SYNC_DATE = stringPreferencesKey("profile_sync_date")
internal val KEY_ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")
internal val KEY_SCHOLAR_PLAN_NAME = stringPreferencesKey("scholar_plan_name")
internal val KEY_SCHOLAR_SYNC_DATE = stringPreferencesKey("scholar_sync_date")
internal val KEY_CURRICULUM_PLAN_NAME = stringPreferencesKey("curriculum_plan_name")
internal val KEY_CURRICULUM_SYNC_DATE = stringPreferencesKey("curriculum_sync_date")
internal val KEY_SCORE_SYNC_DATE = stringPreferencesKey("score_sync_date")
internal val KEY_TEXTBOOK_SYNC_DATE = stringPreferencesKey("textbook_sync_date")
        // 抓成功过的学期号（含「成功但 0 条」）：见 textbookFetchedTerm
internal val KEY_TEXTBOOK_FETCHED_TERM = stringPreferencesKey("textbook_fetched_term")
        // 教材补抓的上次尝试时刻（2026-10-06）：挡「教材为空时每次冷启动都打教务」
internal val KEY_TEXTBOOK_BACKFILL_MILLIS = longPreferencesKey("textbook_backfill_millis")

        // 成绩/考试变动提醒（DESIGN §4.33）：两开关 + 间隔 + 各自上次成功检查时刻
internal val KEY_SCORE_ALERT_ENABLED = booleanPreferencesKey("score_alert_enabled")
internal val KEY_EXAM_ALERT_ENABLED = booleanPreferencesKey("exam_alert_enabled")
        // 成绩/考试变动提醒（DESIGN §4.33）：2026-10-04 起四链间隔各自独立，
        // KEY_ALERT_INTERVAL_HOURS 保留只为老用户读取回退，不再写入
internal val KEY_SCORE_INTERVAL_HOURS = intPreferencesKey("score_alert_interval_hours")
internal val KEY_EXAM_INTERVAL_HOURS = intPreferencesKey("exam_alert_interval_hours")
internal val KEY_SELECTION_INTERVAL_HOURS = intPreferencesKey("selection_alert_interval_hours")
internal val KEY_SCHEDULE_INTERVAL_HOURS = intPreferencesKey("schedule_alert_interval_hours")
internal val KEY_ALERT_INTERVAL_HOURS = intPreferencesKey("alert_interval_hours")
internal val KEY_SCORE_CHECK_MILLIS = longPreferencesKey("score_check_millis")
internal val KEY_EXAM_CHECK_MILLIS = longPreferencesKey("exam_check_millis")
internal val KEY_EXAM_SHELL_TERM = stringPreferencesKey("exam_shell_term")
internal val KEY_EXAM_SHELL_AT_MILLIS = longPreferencesKey("exam_shell_at_millis")
internal val KEY_ALERT_PERIODIC_INTERVAL = intPreferencesKey("alert_periodic_interval")

        // 选课（DESIGN §4.35）：上次同步时刻 + 学期下拉全量 + 轮次检查时刻 + 提醒开关与已发键
internal val KEY_SELECTION_SYNC_MILLIS = longPreferencesKey("selection_sync_millis")
internal val KEY_SELECTION_TERMS = stringPreferencesKey("selection_terms")
internal val KEY_SELECTION_SHELL_TERM = stringPreferencesKey("selection_shell_term")
internal val KEY_SELECTION_SHELL_AT_MILLIS = longPreferencesKey("selection_shell_at_millis")
internal val KEY_SELECTION_ROUNDS_CHECK_MILLIS = longPreferencesKey("selection_rounds_check_millis")
internal val KEY_SELECTION_ALERT_ENABLED = booleanPreferencesKey("selection_alert_enabled")
internal val KEY_SELECTION_REMINDED_KEYS = stringSetPreferencesKey("selection_reminded_keys")
internal val KEY_SELECTION_WISHES_JSON = stringPreferencesKey("selection_wishes_json")
        // 抢课三键（interval/disclaimer/auto_start）随自动抢课于 2026-10-08 v3 下线删除；
        // 设备上残留的旧键不再读写（DataStore 不清理孤儿键，无副作用）。

        // 调课检测（DESIGN §4.17，2026-10-04 重立）：提醒开关 + 检查时刻 + 已阅指纹 + 待处置差异
internal val KEY_SCHEDULE_ALERT_ENABLED = booleanPreferencesKey("schedule_alert_enabled")
internal val KEY_SCHEDULE_CHECK_MILLIS = longPreferencesKey("schedule_check_millis")
internal val KEY_SCHEDULE_IGNORED_FP = stringPreferencesKey("schedule_ignored_fingerprint")
internal val KEY_SCHEDULE_PENDING_JSON = stringPreferencesKey("schedule_pending_changes_json")

        // AI 调课（DESIGN §4.39，2026-10-06）：服务地址 / 模型名 / 教师名脱敏 / 首次隐私确认。
        // API Key **不在这里**——它是账号凭据，走 AiCredentialStore 加密存储（secure_ai.xml）。
internal val KEY_AI_BASE_URL = stringPreferencesKey("ai_base_url")
internal val KEY_AI_MODEL = stringPreferencesKey("ai_model")
internal val KEY_AI_MASK_TEACHER = booleanPreferencesKey("ai_mask_teacher")
internal val KEY_AI_PRIVACY_ACK = booleanPreferencesKey("ai_privacy_ack")
        // 思考档位（none/low/high/max）+ 是否显示思考过程（DESIGN §4.39）
internal val KEY_AI_THINKING_EFFORT = stringPreferencesKey("ai_thinking_effort")
internal val KEY_AI_SHOW_REASONING = booleanPreferencesKey("ai_show_reasoning")
        // 该模型是否支持图片识别（DESIGN §4.39）。**默认关**：模型名是用户手填的，
        // 上游 /models 也不给能力标记，探测不了——只能让用户显式声明，我们只做打勾建议。
internal val KEY_AI_SUPPORTS_IMAGE = booleanPreferencesKey("ai_supports_image")

        // 首启引导后台同步结果（课表/成绩/学业），主界面弹结果弹窗后消费
internal val KEY_ONBOARDING_SYNC_JSON = stringPreferencesKey("onboarding_sync_outcome_json")

        // ---- 全局显示偏好（2026-09-19 起；原课表级 prefs_json 的接棒者） ----
internal val KEY_VIEW_PREFS_JSON = stringPreferencesKey("view_prefs_json")
internal val KEY_VIEW_PREFS_GLOBALIZED = booleanPreferencesKey("view_prefs_globalized")

        // ---- 遗留（仅作 v3 一次性迁移的数据源，不再写入、迁移后不再读取） ----
internal val KEY_SLOT_CUSTOMIZED = booleanPreferencesKey("slot_customized")
internal val KEY_SHOW_WEEKEND = booleanPreferencesKey("show_weekend")
internal val KEY_SHOW_NON_CURRENT = booleanPreferencesKey("show_non_current_week")
internal val KEY_COURSE_FILTER = stringPreferencesKey("course_filter")
internal val KEY_GRID_FONT_DP = floatPreferencesKey("grid_font_dp")
internal val KEY_GRID_ROOM_DP = floatPreferencesKey("grid_room_dp")
internal val KEY_GRID_TEACHER_DP = floatPreferencesKey("grid_teacher_dp")

        /** 已废弃：v1 的字号倍率（0.85–1.35），仅作读路径迁移来源，不再写入。 */
internal val KEY_GRID_FONT_SCALE = floatPreferencesKey("grid_font_scale")
internal val KEY_ROW_HEIGHT_SCALE = floatPreferencesKey("row_height_scale")
internal val KEY_CELL_RADIUS_DP = floatPreferencesKey("cell_radius_dp")
internal val KEY_CELL_OPACITY = floatPreferencesKey("cell_opacity")
internal val KEY_CELL_CENTER_H = booleanPreferencesKey("cell_center_horizontal")
internal val KEY_CELL_CENTER_V = booleanPreferencesKey("cell_center_vertical")
internal val KEY_SHOW_TEACHER = booleanPreferencesKey("show_teacher")
internal val KEY_SHOW_NOW_LINE = booleanPreferencesKey("show_now_line")
internal val KEY_SHOW_CELL_BORDER = booleanPreferencesKey("show_cell_border")
internal val KEY_SHOW_GRID_LINES = booleanPreferencesKey("show_grid_lines")
