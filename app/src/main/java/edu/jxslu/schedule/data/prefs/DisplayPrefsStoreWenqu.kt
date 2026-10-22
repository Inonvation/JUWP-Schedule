package edu.jxslu.schedule.data.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import edu.jxslu.schedule.domain.WenquRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 问渠相关偏好（DESIGN §4.45）：从 [DisplayPrefsStore] 拆出，按功能聚在一处。 */

// 问渠新增志愿活动提醒（DESIGN §4.45，2026-10-08）：开关 + 间隔 + 检查时刻 + 基线 id
private val KEY_WENQU_ACTIVITY_ALERT_ENABLED = booleanPreferencesKey("wenqu_activity_alert_enabled")
private val KEY_WENQU_ACTIVITY_INTERVAL_HOURS = intPreferencesKey("wenqu_activity_interval_hours")
private val KEY_WENQU_ACTIVITY_CHECK_MILLIS = longPreferencesKey("wenqu_activity_check_millis")
private val KEY_WENQU_ACTIVITY_LAST_SEEN_ID = longPreferencesKey("wenqu_activity_last_seen_id")
// 活动列表的本地筛选（2026-10-08）：状态（-1 = 全部）+ 隐藏已满
private val KEY_WENQU_STATUS_FILTER = intPreferencesKey("wenqu_status_filter")
private val KEY_WENQU_HIDE_FULL = booleanPreferencesKey("wenqu_hide_full")
// 只看我报名的 + 排序档位（2026-10-11）
private val KEY_WENQU_APPLIED_ONLY = booleanPreferencesKey("wenqu_applied_only")
private val KEY_WENQU_SORT = stringPreferencesKey("wenqu_sort")
// 每周日 12:00「抢活动」提醒（2026-10-11）：开关 / 提前量 / 上次已提醒的发布时刻
private val KEY_WENQU_WEEKLY_ALERT_ENABLED = booleanPreferencesKey("wenqu_weekly_alert_enabled")
private val KEY_WENQU_WEEKLY_ALERT_OFFSET_MIN = intPreferencesKey("wenqu_weekly_alert_offset_min")
private val KEY_WENQU_WEEKLY_ALERT_LAST_FIRED_AT = longPreferencesKey("wenqu_weekly_alert_last_fired_at")
// 蹲点模式的目标活动名（2026-10-11）：预填用，匹配在本地做
private val KEY_WENQU_GRAB_KEYWORD = stringPreferencesKey("wenqu_grab_keyword")
// 蹲点盯着的指定活动 id（2026-10-15）：0 = 没盯；每轮单独拉它的详情拿实时名额
private val KEY_WENQU_GRAB_PINNED_ID = longPreferencesKey("wenqu_grab_pinned_id")
// 盯着的活动「名额空出」已报过的那条 id（2026-10-15）：同一条只提醒一次
private val KEY_WENQU_GRAB_PINNED_ALERTED_ID = longPreferencesKey("wenqu_grab_pinned_alerted_id")
// 预报名（2026-10-16）：瞄准的活动 id / 标题 / 报名开始时刻（id = 0 = 没排）
private val KEY_WENQU_PREREG_ID = longPreferencesKey("wenqu_prereg_id")
private val KEY_WENQU_PREREG_TITLE = stringPreferencesKey("wenqu_prereg_title")
private val KEY_WENQU_PREREG_OPENS_AT = longPreferencesKey("wenqu_prereg_opens_at")

// ---- 问渠新增志愿活动提醒（DESIGN §4.45，2026-10-08）----

/**
 * 新增志愿活动提醒开关。**默认关**——通知是打扰型能力（同选课 / 调课提醒口径；
 * 与「成绩/考试变动默认开」不同：那是用户要的事实，这是「可能有你感兴趣的东西」）。
 */
val DisplayPrefsStore.wenquActivityAlertEnabled: Flow<Boolean>
    get() = cachedFlow("wenquActivityAlertEnabled") { context.displayDataStore.data.map { p -> p[KEY_WENQU_ACTIVITY_ALERT_ENABLED] ?: false } }

suspend fun DisplayPrefsStore.setWenquActivityAlertEnabled(value: Boolean) {
    context.displayDataStore.edit { p -> p[KEY_WENQU_ACTIVITY_ALERT_ENABLED] = value }
}

/** 问渠活动提醒的检查间隔（小时），默认 24（一天一次，第三方系统别打太勤）。 */
val DisplayPrefsStore.wenquActivityIntervalHours: Flow<Int>
    get() = cachedFlow("wenquActivityIntervalHours") { context.displayDataStore.data.map { p ->
    p[KEY_WENQU_ACTIVITY_INTERVAL_HOURS] ?: WenquRules.ACTIVITY_ALERT_INTERVAL_DEFAULT
} }

suspend fun DisplayPrefsStore.setWenquActivityIntervalHours(value: Int) {
    context.displayDataStore.edit { p -> p[KEY_WENQU_ACTIVITY_INTERVAL_HOURS] = value }
}

/** 问渠活动上次成功检查时刻（epoch millis）。0 = 从没成功过。失败不写（同成绩/考试纪律）。 */
suspend fun DisplayPrefsStore.wenquActivityCheckMillis(): Long =
    context.displayDataStore.data.first()[KEY_WENQU_ACTIVITY_CHECK_MILLIS] ?: 0L

suspend fun DisplayPrefsStore.setWenquActivityCheckMillis(millis: Long) {
    context.displayDataStore.edit { it[KEY_WENQU_ACTIVITY_CHECK_MILLIS] = millis }
}

/**
 * 上次扫描见过的最大活动 id。**0 = 还没建基线**：首跑只把当前最大 id 记下来、不发提醒
 * （第一次抓到的全量是基线不是「新增」，见 [WenquRules.isNewRecruiting]）。
 */
suspend fun DisplayPrefsStore.wenquActivityLastSeenId(): Long =
    context.displayDataStore.data.first()[KEY_WENQU_ACTIVITY_LAST_SEEN_ID] ?: 0L

suspend fun DisplayPrefsStore.setWenquActivityLastSeenId(id: Long) {
    context.displayDataStore.edit { it[KEY_WENQU_ACTIVITY_LAST_SEEN_ID] = id }
}

/**
 * 盯着的活动**已经报过「名额空出」的那条 id**（2026-10-15）：同一条只提醒一次。
 * 重新盯一条 / 取消盯时清 0（见 `WenquGrabViewModel.pin` / `unpin`）。
 */
suspend fun DisplayPrefsStore.wenquGrabPinnedAlertedId(): Long =
    context.displayDataStore.data.first()[KEY_WENQU_GRAB_PINNED_ALERTED_ID] ?: 0L

suspend fun DisplayPrefsStore.setWenquGrabPinnedAlertedId(id: Long) {
    context.displayDataStore.edit { it[KEY_WENQU_GRAB_PINNED_ALERTED_ID] = id }
}

/**
 * 活动列表的状态筛选。**存 [WenquRules.FILTER_ALL_STORED] 表示「全部」**
 * （DataStore 没有可空 Int）——用户专门要过「隐藏已满」，每次进页都重置一遍会烦
 * （2026-10-08）。
 */
val DisplayPrefsStore.wenquStatusFilter: Flow<Int>
    get() = cachedFlow("wenquStatusFilter") { context.displayDataStore.data.map { p ->
    p[KEY_WENQU_STATUS_FILTER] ?: WenquRules.FILTER_ALL_STORED
} }

suspend fun DisplayPrefsStore.setWenquStatusFilter(value: Int) {
    context.displayDataStore.edit { p -> p[KEY_WENQU_STATUS_FILTER] = value }
}

/** 活动列表的「隐藏已满」开关，默认关（同页面默认值）。 */
val DisplayPrefsStore.wenquHideFull: Flow<Boolean>
    get() = cachedFlow("wenquHideFull") { context.displayDataStore.data.map { p ->
    p[KEY_WENQU_HIDE_FULL] ?: false
} }

suspend fun DisplayPrefsStore.setWenquHideFull(value: Boolean) {
    context.displayDataStore.edit { p -> p[KEY_WENQU_HIDE_FULL] = value }
}

/**
 * 「只看我报名的」（2026-10-11）。默认关——默认视图要能看见全部，
 * 用户想收窄时自己开（同「隐藏已满」的口径）。
 */
val DisplayPrefsStore.wenquAppliedOnly: Flow<Boolean>
    get() = cachedFlow("wenquAppliedOnly") { context.displayDataStore.data.map { p ->
    p[KEY_WENQU_APPLIED_ONLY] ?: false
} }

suspend fun DisplayPrefsStore.setWenquAppliedOnly(value: Boolean) {
    context.displayDataStore.edit { p -> p[KEY_WENQU_APPLIED_ONLY] = value }
}

/**
 * 活动列表的排序档位（[WenquRules.Sort] 的 `name`）。默认「招募中优先」。
 * 存 **name 而不是序号**：以后往枚举中间插一档，老用户的设置不会错位。
 */
val DisplayPrefsStore.wenquSort: Flow<String>
    get() = cachedFlow("wenquSort") { context.displayDataStore.data.map { p ->
    p[KEY_WENQU_SORT] ?: WenquRules.Sort.RecruitingFirst.name
} }

suspend fun DisplayPrefsStore.setWenquSort(value: String) {
    context.displayDataStore.edit { p -> p[KEY_WENQU_SORT] = value }
}

// ---- 问渠「每周日 12:00 抢活动」提醒（DESIGN §4.45，2026-10-11）----
//
// 与上面那条「检测到新活动就报」是两件事：那条是事后发现（周期扫列表），
// 这条是**事前蹲点**（周日 12:00 前 N 分钟响一声），只有一条精确闹钟、不扫接口。

/**
 * 每周提醒开关。**默认关**——通知是打扰型能力（同选课 / 调课 / 新增活动提醒口径）。
 */
val DisplayPrefsStore.wenquWeeklyAlertEnabled: Flow<Boolean>
    get() = cachedFlow("wenquWeeklyAlertEnabled") { context.displayDataStore.data.map { p ->
    p[KEY_WENQU_WEEKLY_ALERT_ENABLED] ?: false
} }

suspend fun DisplayPrefsStore.setWenquWeeklyAlertEnabled(value: Boolean) {
    context.displayDataStore.edit { p -> p[KEY_WENQU_WEEKLY_ALERT_ENABLED] = value }
}

/** 提前提醒量（分钟，1–10，默认 5）。 */
val DisplayPrefsStore.wenquWeeklyAlertOffsetMinutes: Flow<Int>
    get() = cachedFlow("wenquWeeklyAlertOffsetMinutes") { context.displayDataStore.data.map { p ->
    p[KEY_WENQU_WEEKLY_ALERT_OFFSET_MIN] ?: WenquRules.WEEKLY_ALERT_OFFSET_DEFAULT_MIN
} }

suspend fun DisplayPrefsStore.setWenquWeeklyAlertOffsetMinutes(value: Int) {
    context.displayDataStore.edit { p -> p[KEY_WENQU_WEEKLY_ALERT_OFFSET_MIN] = value }
}

/**
 * 上一次已提醒的**发布时刻**（周日 12:00，不是提醒时刻）——0 = 从没提醒过。
 *
 * 存发布时刻而不是提醒时刻：用户中途改提前量（5 分钟 → 10 分钟）时，
 * 同一周的发布键不变，不会因为改了设置又报一次。
 */
suspend fun DisplayPrefsStore.wenquWeeklyAlertLastFiredAt(): Long =
    context.displayDataStore.data.first()[KEY_WENQU_WEEKLY_ALERT_LAST_FIRED_AT] ?: 0L

suspend fun DisplayPrefsStore.setWenquWeeklyAlertLastFiredAt(publishAt: Long) {
    context.displayDataStore.edit { p -> p[KEY_WENQU_WEEKLY_ALERT_LAST_FIRED_AT] = publishAt }
}

/**
 * 蹲点页的目标活动名（DESIGN §4.45「蹲点模式」，2026-10-11）：预填用。
 * 空串 = 没预填（蹲点页只浏览列表，不弹检测）。**不上传**——匹配全在本地做，
 * 平台的 `keyword` 参数只有搜索兜底时才用。
 */
val DisplayPrefsStore.wenquGrabKeyword: Flow<String>
    get() = cachedFlow("wenquGrabKeyword") { context.displayDataStore.data.map { p ->
    p[KEY_WENQU_GRAB_KEYWORD] ?: ""
} }

suspend fun DisplayPrefsStore.setWenquGrabKeyword(value: String) {
    context.displayDataStore.edit { p -> p[KEY_WENQU_GRAB_KEYWORD] = value }
}

/**
 * 蹲点页**盯着的指定活动 id**（DESIGN §4.45「盯指定活动」，2026-10-15）：0 = 没盯。
 *
 * 与 [wenquGrabKeyword] 是两回事：名字匹配靠快照（深层条目的名额会冻住），
 * 盯一条具体的活动则**每轮单独拉它的详情**（实时名额），所以老活动也能盯。
 */
val DisplayPrefsStore.wenquGrabPinnedId: Flow<Long>
    get() = cachedFlow("wenquGrabPinnedId") { context.displayDataStore.data.map { p ->
    p[KEY_WENQU_GRAB_PINNED_ID] ?: 0L
} }

suspend fun DisplayPrefsStore.setWenquGrabPinnedId(value: Long) {
    context.displayDataStore.edit { p -> p[KEY_WENQU_GRAB_PINNED_ID] = value }
}

/**
 * 「预报名」瞄准的活动（DESIGN §4.45，2026-10-16；用户拍板）：id + 标题（展示用）+
 * 报名开始时刻（平台墙钟，epoch millis）。id = 0 = 没排。
 *
 * 与 [wenquGrabPinnedId] 是两回事：盯是「名额空出提醒」，预报名是「到点自动提交」。
 * 三个键一起写、一起清（[setWenquPrereg] / [clearWenquPrereg]），别只动一个。
 */
val DisplayPrefsStore.wenquPreregId: Flow<Long>
    get() = cachedFlow("wenquPreregId") { context.displayDataStore.data.map { p ->
    p[KEY_WENQU_PREREG_ID] ?: 0L
} }

val DisplayPrefsStore.wenquPreregTitle: Flow<String>
    get() = cachedFlow("wenquPreregTitle") { context.displayDataStore.data.map { p ->
    p[KEY_WENQU_PREREG_TITLE].orEmpty()
} }

val DisplayPrefsStore.wenquPreregOpensAt: Flow<Long>
    get() = cachedFlow("wenquPreregOpensAt") { context.displayDataStore.data.map { p ->
    p[KEY_WENQU_PREREG_OPENS_AT] ?: 0L
} }

suspend fun DisplayPrefsStore.setWenquPrereg(id: Long, title: String, opensAt: Long) {
    context.displayDataStore.edit { p ->
        p[KEY_WENQU_PREREG_ID] = id
        p[KEY_WENQU_PREREG_TITLE] = title
        p[KEY_WENQU_PREREG_OPENS_AT] = opensAt
    }
}

suspend fun DisplayPrefsStore.clearWenquPrereg() {
    context.displayDataStore.edit { p ->
        p.remove(KEY_WENQU_PREREG_ID)
        p.remove(KEY_WENQU_PREREG_TITLE)
        p.remove(KEY_WENQU_PREREG_OPENS_AT)
    }
}
