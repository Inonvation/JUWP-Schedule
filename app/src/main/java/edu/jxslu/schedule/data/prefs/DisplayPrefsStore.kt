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
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import edu.jxslu.schedule.domain.BgScale
import edu.jxslu.schedule.domain.BikeMapViewport
import edu.jxslu.schedule.domain.CalendarSyncDefaults
import edu.jxslu.schedule.domain.CourseFilter
import edu.jxslu.schedule.domain.DetectFailurePolicy
import edu.jxslu.schedule.domain.EbikeFreeRide
import edu.jxslu.schedule.domain.EbikeQr
import edu.jxslu.schedule.domain.ReminderDefaults
import edu.jxslu.schedule.domain.ScoreSortMode
import edu.jxslu.schedule.domain.ShortcutItem
import edu.jxslu.schedule.domain.ShortcutSettings
import edu.jxslu.schedule.domain.Shortcuts
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.domain.TimetablePrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 显示偏好（对 UI 的合并视图）。
 *
 * 2026-09-19 起（DESIGN §4.9）**全部字段都是全局项**：视图偏好与主题一样存 DataStore
 * （`view_prefs_json`，同一 [TimetablePrefs] JSON 结构），不再按课表分开——换课表不换观感。
 * ScheduleRepository 把全局视图偏好组装成本类，WeekScreen 等调用点不感知存储位置。
 */
/** 上课提醒的「已发键」类别（DESIGN §3.7）：提前量通知与上课开始通知各自独立去重。 */
enum class ReminderKeyKind(val prefix: String) {
    /** 提前量通知（上课前 N 分钟）。 */
    Before("before|"),

    /** 上课开始通知（上课时刻）。 */
    Start("start|"),
}

data class DisplayPrefs(
    /** 应用主题模式。System = 跟随系统深浅色。全局项。 */
    val themeMode: ThemeMode = ThemeMode.System,
    /** 动态取色（Material You）。全局项；false = 用回内置蓝绿方案。 */
    val dynamicColor: Boolean = true,
    /**
     * 悬浮导航栏（DESIGN §4.22）。全局项，**默认关**：底栏半透明磨砂，
     * 课表页背景图透到屏幕底部。关 = 保持原样（不透明底栏）。
     */
    val floatingNavBar: Boolean = false,
    /**
     * 生活页（DESIGN §3.13）。全局项，**默认开**：底栏第三项「生活」（课表右侧），
     * 承载一卡通余额/付款码、寝室电费、充值入口与最近流水。
     * 关 = 底栏回到 3 项（今日 · 课表 · 我的），二级页入口不受影响。
     */
    val lifeTabEnabled: Boolean = true,
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
    /** 课表页背景图文件名（DESIGN §4.21）；null = 无背景。文件在 `filesDir/schedule_bg/`。 */
    val bgImageName: String? = null,
    /** 背景图自身不透明度。下限见 [TimetablePrefs.MinBgImageOpacity]。 */
    val bgImageOpacity: Float = 1f,
    /** 背景图压暗遮罩强度；上限见 [TimetablePrefs.MaxBgImageDim]。 */
    val bgImageDim: Float = 0f,
    /** 背景图模糊强度（0–1），映射到解码降采样档位。 */
    val bgImageBlur: Float = 0f,
    /** 背景图缩放方式（填充 / 适应 / 平铺）。 */
    val bgImageScale: BgScale = BgScale.Fill,
    /** 今日页开水卡片显示开关（DESIGN §3.3 底部固定区）。默认开；关 = 不展示（含未登录态）。 */
    val waterCardEnabled: Boolean = true,
    /** 今日页共享单车卡显示开关（DESIGN §3.9）。默认开（用户要求入口常驻）。 */
    val ebikeCardEnabled: Boolean = true,
    /**
     * 共享单车二维码生成后自动存相册（DESIGN §3.9）。**默认关**（用户拍板）——
     * 相册里只留用户真的要的码，开了才会每次生成即落盘。
     */
    val ebikeAutoSave: Boolean = false,
    /**
     * 扫完即焚（DESIGN §3.9）：保存过的码在用户回到 App 后自动从相册删除。
     * **默认开**——保存码本就是"扫码"的一次性耗材，不留痕是预期行为；
     * 关掉 = 完全回到旧语义（已记录的待删项保留不动，不再新增、也不删除）。
     */
    val ebikeBurnAfterScan: Boolean = true,
    /**
     * 最近生成的共享单车车号（**完整车号**，倒序去重，上限见 [EbikeQr.RECENT_LIMIT]）。
     * 2026-09-23 之前只存尾部 3 位，[EbikeQr.decodeRecent] 读旧数据时补前缀。
     */
    val ebikeRecentIds: List<String> = emptyList(),
    /**
     * 今日页校园卡付款码卡开关（DESIGN §3.10）。**默认关**：涉及凭证与资金等价物，
     * 用户显式开启；关 = 整卡不占位（即「不在今日页显示」）。凭证本身不在这里——
     * 存 `ykt_credentials.xml`（EncryptedSharedPreferences），有没有凭证读 store 即知。
     */
    val campusCardEnabled: Boolean = false,
    /**
     * 今日页底部抽屉是否展开（DESIGN §3.3，2026-09-22 加）：快捷方式网格、快趣出行码、
     * 水宝宝一卡通、胖乖生活开水**整块**折进一行把手之下。
     *
     * **默认展开**：与加抽屉之前的表现一致（这些入口常驻可见）——用户开着某个开关
     * 本就是要用它，默认收起等于把入口藏起来。
     * 用户点一次收起即记忆，之后进今日页都是收起态。
     */
    val todayDockExpanded: Boolean = true,
)

/**
 * 调课自动检测的配置与运行状态（DESIGN §4.17）。功能默认关闭。
 * 凭证本身不在这里——存 `jw_credentials.xml`（EncryptedSharedPreferences），
 * 这里只存「有没有可用的凭证态」无关的配置与上次运行结果。
 */
data class DetectSettings(
    /** 总开关；关闭 = 不检测、气泡与周期任务一并停。 */
    val enabled: Boolean = false,
    /** 检测周期（小时）。档位见 [DetectDefaults.PERIOD_HOURS]，默认 24 = 每天一次。 */
    val periodHours: Int = 24,
    /** 上次成功完成检测（含建基线）的时刻；0 = 从未。 */
    val lastCheckedAt: Long = 0L,
    /** 上次失败的说明；空串 = 无。 */
    val lastError: String = "",
    /** 连续凭证失败计数（登录成功即清零）；达上限触发 [disabled]。 */
    val credentialFailures: Int = 0,
    /** 连续凭证失败触发的自动停用；用户重新开启时清零。 */
    val disabled: Boolean = false,
)

/** 检测周期档位与文案的单一来源（DESIGN §4.17：每天 / 每 3 天 / 每周）。 */
object DetectDefaults {
    val PERIOD_HOURS = listOf(24, 72, 168)

    fun label(hours: Int): String = when (hours) {
        24 -> "每天"
        72 -> "每 3 天"
        168 -> "每周"
        else -> "每 $hours 小时"
    }
}

// preferencesDataStore 是属性委托，必须用 by；一个文件只能声明一份，重复实例化同一文件会崩溃。
private val Context.displayDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "juw_display")

/**
 * 未确认充值记录（DESIGN §4.19「充值」）。
 *
 * 持久化的理由是到账轮询只在内存：微信支付期间进程大概率被系统回收
 * （MIUI 激进省电），重启后要靠这条记录恢复等待态并立即补检。
 * [balanceBeforeFen]/[cardAccount] 一并落库，重启后余额口径仍然可用；
 * 旧版本只存金额与时刻，这两项读出来是 null，退化为流水口径。
 */
data class PendingRecharge(
    /** 下单金额（分）。 */
    val orderFen: Long,
    /** 下单时刻（epoch 毫秒）。 */
    val startedAt: Long,
    /** 付款前该卡余额（分）；缺失为 null。 */
    val balanceBeforeFen: Long?,
    /** 付款卡账户（6 位卡号）；缺失为 null。 */
    val cardAccount: String?,
    /** 「正在确认到账」弹窗是否已提示过（每次充值只弹一次）。 */
    val confirmShown: Boolean,
)

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

    /** 悬浮导航栏（DESIGN §4.22）。默认关：不透明底栏是既有观感，用户显式开启才改。 */
    val floatingNavBar: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_FLOATING_NAV_BAR] ?: false
    }

    /** 生活页开关（DESIGN §3.13）。默认开：新 Tab 直接可用，嫌底栏挤的人再关。 */
    val lifeTabEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_LIFE_TAB_ENABLED] ?: true
    }

    /** 「我的」账号条：姓名（教务学籍卡导入）。null/空 = 未导入过，退回学号显示。 */
    val profileName: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_PROFILE_NAME].orEmpty()
    }

    /** 「我的」账号条：班级（教务学籍卡导入）。缺失时账号条副行只剩学号。 */
    val profileClass: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_PROFILE_CLASS].orEmpty()
    }

    /** 学籍卡落库；姓名/班级为空串时各键不动，避免一次脏解析抹掉已有数据。 */
    suspend fun setProfile(name: String?, className: String?) {
        context.displayDataStore.edit { p ->
            if (!name.isNullOrBlank()) p[KEY_PROFILE_NAME] = name.trim()
            if (!className.isNullOrBlank()) p[KEY_PROFILE_CLASS] = className.trim()
        }
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
     * 作业截止提醒开关（DESIGN §3.11 / §3.7 设置表）。全局项，**默认关**：
     * 与 [reminderEnabled] 同口径——通知是打扰型能力，用户显式开启；两个开关互相独立。
     */
    val homeworkReminderEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_HOMEWORK_REMINDER_ENABLED] ?: false
    }

    /** 共享单车免费时长日历提醒开关（DESIGN §3.9）。默认关：往用户日历里写东西属打扰型能力。 */
    val ebikeFreeReminderEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_FREE_REMINDER_ENABLED] ?: false
    }

    /** 共享单车免费时长提醒提前量（分钟）。读路径吸附 1~5。 */
    val ebikeFreeLeadMinutes: Flow<Int> = context.displayDataStore.data.map { p ->
        EbikeFreeRide.coerceLead(p[KEY_EBIKE_FREE_LEAD] ?: EbikeFreeRide.DEFAULT_LEAD_MINUTES)
    }

    /** 本次骑行计时起点（epoch 毫秒）；0 = 无进行中计时。 */
    val ebikeRideStartAt: Flow<Long> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_RIDE_START_AT] ?: 0L
    }

    /** 当前骑行在系统日历里的事件 id（DESIGN §3.9）；0 = 没有在案事件。 */
    val ebikeFreeEventId: Flow<Long> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_FREE_EVENT_ID] ?: 0L
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

    /** 今日页快捷方式开关（DESIGN §3.8）。全局项，默认开：这是展示型入口，不打扰人。 */
    val shortcutsEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_SHORTCUTS_ENABLED] ?: true
    }.distinctUntilChanged()

    /**
     * 今日页开水卡片开关（DESIGN §3.3 底部固定区）。全局项，默认开：
     * 卡片含未登录态（登录引导入口），关掉 = 用户明确不要这个常驻位。
     */
    val waterCardEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_WATER_CARD_ENABLED] ?: true
    }.distinctUntilChanged()

    /** 今日页共享单车卡开关（DESIGN §3.9）。全局项，默认开。 */
    val ebikeCardEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_CARD_ENABLED] ?: true
    }.distinctUntilChanged()

    /** 共享单车出码后自动存相册（DESIGN §3.9）。全局项，默认关（用户拍板）。 */
    val ebikeAutoSave: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_AUTO_SAVE] ?: false
    }.distinctUntilChanged()

    /** 扫完即焚开关（DESIGN §3.9）。全局项，默认开。 */
    val ebikeBurnAfterScan: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_BURN_AFTER_SCAN] ?: true
    }.distinctUntilChanged()

    /**
     * 是否已经自动申请过定位权限（DESIGN §3.9）。
     *
     * 进单车地图页时没授权就申请一次；这个标记保证**只自动申请一次**。
     * 系统在用户拒绝两次后就静默拒绝，不看标记的话每次进页面都会白申请一次、
     * 再弹一句"已拒绝"，那就成了骚扰。之后要走定位只能靠用户主动点按钮。
     */
    val ebikeLocationAsked: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_LOCATION_ASKED] ?: false
    }.distinctUntilChanged()

    /**
     * 附近单车地图的底部面板高度（dp，DESIGN §3.9）；null = 没拖过，用默认值。
     *
     * 只做范围校验，窗口缩放导致的"放不下"由渲染时再夹一道，不覆写用户拖出来的值。
     */
    val ebikePanelHeightDp: Flow<Float?> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_PANEL_HEIGHT_DP]?.takeIf { it.isFinite() && it > 0f }
    }.distinctUntilChanged()

    /**
     * 上次查看的附近单车地图视野（DESIGN §3.9）；null = 还没存过。
     *
     * 存的是**最近一次查询的中心与缩放**：没给定位权限的人下次进页面直接从这儿开局，
     * 不用每次都回到校园中心重拖一遍。脏值（越界、非有限）当没存过，
     * 免得一个坏偏好把地图甩到地图外的某处。
     */
    val ebikeMapViewport: Flow<BikeMapViewport?> = context.displayDataStore.data.map { p ->
        val lat = p[KEY_EBIKE_VIEW_LAT]?.toDouble() ?: return@map null
        val lng = p[KEY_EBIKE_VIEW_LNG]?.toDouble() ?: return@map null
        val zoom = p[KEY_EBIKE_VIEW_ZOOM]?.toDouble() ?: return@map null
        if (!lat.isFinite() || !lng.isFinite() || !zoom.isFinite()) return@map null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@map null
        if (zoom !in MIN_MAP_ZOOM.toDouble()..MAX_MAP_ZOOM.toDouble()) return@map null
        BikeMapViewport(lat, lng, zoom)
    }.distinctUntilChanged()

    /** 扫完即焚的待删记录（本功能保存的二维码 key 集合，DESIGN §3.9）。 */
    val ebikePendingDelete: Flow<Set<String>> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_PENDING_DELETE] ?: emptySet()
    }

    /**
     * 最近共享单车车号（DESIGN §3.9）。键缺失/脏 JSON 回空列表——
     * 历史只是回填入口，坏了不该打扰任何下游。
     */
    val ebikeRecentIds: Flow<List<String>> = context.displayDataStore.data.map { p ->
        EbikeQr.decodeRecent(p[KEY_EBIKE_RECENT_IDS])
    }.distinctUntilChanged()

    /** 今日页校园卡付款码卡开关（DESIGN §3.10）。全局项，**默认关**（涉及凭证与资金）。 */
    val campusCardEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_CAMPUS_CARD_ENABLED] ?: false
    }.distinctUntilChanged()

    /** 今日页底部抽屉展开态（DESIGN §3.3）。全局项，默认展开。 */
    val todayDockExpanded: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_TODAY_DOCK_EXPANDED] ?: true
    }.distinctUntilChanged()

    /**
     * 未确认充值（DESIGN §4.19「充值」）：见 [PendingRecharge]。
     * 到账/超时/取消时清除；金额与卡号后四位之外的信息都非敏感数据。
     */
    val pendingRecharge: Flow<PendingRecharge?> = context.displayDataStore.data.map { p ->
        val fen = p[KEY_PENDING_RECHARGE_FEN]
        val at = p[KEY_PENDING_RECHARGE_AT]
        if (fen == null || at == null) {
            null
        } else {
            PendingRecharge(
                orderFen = fen,
                startedAt = at,
                balanceBeforeFen = p[KEY_PENDING_RECHARGE_BASE_FEN],
                cardAccount = p[KEY_PENDING_RECHARGE_ACCOUNT],
                confirmShown = p[KEY_PENDING_RECHARGE_CONFIRM_SHOWN] ?: false,
            )
        }
    }.distinctUntilChanged()

    suspend fun setPendingRecharge(
        fen: Long,
        at: Long,
        /** 付款前该卡余额（分）；取不到传 null（只走流水口径判定到账）。 */
        balanceBeforeFen: Long?,
        /** 付款卡账户（6 位卡号）。 */
        cardAccount: String?,
    ) {
        context.displayDataStore.edit {
            it[KEY_PENDING_RECHARGE_FEN] = fen
            it[KEY_PENDING_RECHARGE_AT] = at
            if (balanceBeforeFen != null) {
                it[KEY_PENDING_RECHARGE_BASE_FEN] = balanceBeforeFen
            } else {
                it.remove(KEY_PENDING_RECHARGE_BASE_FEN)
            }
            if (cardAccount != null) {
                it[KEY_PENDING_RECHARGE_ACCOUNT] = cardAccount
            } else {
                it.remove(KEY_PENDING_RECHARGE_ACCOUNT)
            }
            it[KEY_PENDING_RECHARGE_CONFIRM_SHOWN] = false
        }
    }

    /**
     * 标记「正在确认到账」弹窗已提示过。每次充值只弹一次：用户关掉之后再进出
     * 校园卡页面不该反复被同一个弹窗拦住（2026-09-22 用户反馈）。
     */
    suspend fun markPendingRechargeConfirmShown() {
        context.displayDataStore.edit { it[KEY_PENDING_RECHARGE_CONFIRM_SHOWN] = true }
    }

    suspend fun clearPendingRecharge() {
        context.displayDataStore.edit {
            it.remove(KEY_PENDING_RECHARGE_FEN)
            it.remove(KEY_PENDING_RECHARGE_AT)
            it.remove(KEY_PENDING_RECHARGE_BASE_FEN)
            it.remove(KEY_PENDING_RECHARGE_ACCOUNT)
            it.remove(KEY_PENDING_RECHARGE_CONFIRM_SHOWN)
        }
    }

    /**
     * 快捷方式条目列表。键缺失或脏 JSON 回退内置预设（口径见 [Shortcuts.decode]）；
     * 读路径顺带做预设目标迁移（DESIGN §4.16：历史原值 → 当前预设，自定义不动）。
     * distinctUntilChanged：DataStore 任何键的写入都会重发这里，值没变就不该打扰下游。
     */
    val shortcuts: Flow<List<ShortcutItem>> = context.displayDataStore.data.map { p ->
        Shortcuts.migratePresets(
            p[KEY_SHORTCUTS_JSON]?.let { Shortcuts.decode(it) } ?: Shortcuts.PRESET_SHORTCUTS,
        )
    }.distinctUntilChanged()

    /** 开关 + 条目二合一快照：今日页与设置页各订阅一次即可。 */
    val shortcutSettings: Flow<ShortcutSettings> =
        combine(shortcutsEnabled, shortcuts, ::ShortcutSettings)

    /**
     * 成绩页统计口径：任选课是否计入加权平均分/平均绩点（DESIGN §4.15）。
     * 默认关 = 排除（作者学校综测同样不计任选课）；公开仓库用户可自行打开。
     */
    val scoreIncludeFreeElectives: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_SCORE_INCLUDE_FREE_ELECTIVES] ?: false
    }

    /** 成绩页分组模式：true = 按学年。全局项，重进页面不重置。 */
    val scoreGroupByYear: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_SCORE_GROUP_BY_YEAR] ?: false
    }

    /** 成绩页排序档。脏值一律回退默认顺序：宁可回到入库顺序，也不要因脏数据排错。 */
    val scoreSortMode: Flow<ScoreSortMode> = context.displayDataStore.data.map { p ->
        ScoreSortMode.entries.firstOrNull { it.name.equals(p[KEY_SCORE_SORT_MODE], ignoreCase = true) }
            ?: ScoreSortMode.Default
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

    suspend fun setFloatingNavBar(value: Boolean) {
        context.displayDataStore.edit { it[KEY_FLOATING_NAV_BAR] = value }
    }

    suspend fun setLifeTabEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_LIFE_TAB_ENABLED] = value }
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

    /** 作业截止提醒开关（DESIGN §3.11）。 */
    suspend fun setHomeworkReminderEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_HOMEWORK_REMINDER_ENABLED] = value }
    }

    /** 共享单车免费时长日历提醒开关（DESIGN §3.9）。默认关：往用户日历里写东西属打扰型能力。 */
    suspend fun setEbikeFreeReminderEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_EBIKE_FREE_REMINDER_ENABLED] = value }
    }

    /** 共享单车免费时长提醒提前量（分钟，1~5）。 */
    suspend fun setEbikeFreeLeadMinutes(value: Int) {
        context.displayDataStore.edit {
            it[KEY_EBIKE_FREE_LEAD] = EbikeFreeRide.coerceLead(value)
        }
    }

    /**
     * 本次骑行计时起点（epoch 毫秒）：点「打开微信扫一扫」即写。
     * 0 = 无进行中计时。**持久化**——进程被杀重启后状态条与兜底核对都靠它。
     */
    suspend fun setEbikeRideStartAt(value: Long) {
        context.displayDataStore.edit { it[KEY_EBIKE_RIDE_START_AT] = value }
    }

    /** 当前骑行在系统日历里的事件 id（0 = 没有在案事件）。 */
    suspend fun setEbikeFreeEventId(value: Long) {
        context.displayDataStore.edit { it[KEY_EBIKE_FREE_EVENT_ID] = value }
    }

    suspend fun setReminderLeadMinutes(value: Int) {
        context.displayDataStore.edit {
            it[KEY_REMINDER_LEAD] = ReminderDefaults.coerceLead(value)
        }
    }

    suspend fun setShortcutsEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_SHORTCUTS_ENABLED] = value }
    }

    /** 今日页开水卡片开关（DESIGN §3.3）。 */
    suspend fun setWaterCardEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_WATER_CARD_ENABLED] = value }
    }

    /** 今日页共享单车卡开关（DESIGN §3.9）。 */
    suspend fun setEbikeCardEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_EBIKE_CARD_ENABLED] = value }
    }

    /** 今日页校园卡付款码卡开关（DESIGN §3.10）。 */
    suspend fun setCampusCardEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_CAMPUS_CARD_ENABLED] = value }
    }

    /** 今日页底部抽屉展开态（DESIGN §3.3）。 */
    suspend fun setTodayDockExpanded(value: Boolean) {
        context.displayDataStore.edit { it[KEY_TODAY_DOCK_EXPANDED] = value }
    }

    /** 共享单车出码自动存相册开关（DESIGN §3.9）。 */
    suspend fun setEbikeAutoSave(value: Boolean) {
        context.displayDataStore.edit { it[KEY_EBIKE_AUTO_SAVE] = value }
    }

    /** 扫完即焚开关（DESIGN §3.9）。 */
    suspend fun setEbikeBurnAfterScan(value: Boolean) {
        context.displayDataStore.edit { it[KEY_EBIKE_BURN_AFTER_SCAN] = value }
    }

    /** 标记「已自动申请过定位权限」（DESIGN §3.9），见 [ebikeLocationAsked]。 */
    suspend fun setEbikeLocationAsked(value: Boolean) {
        context.displayDataStore.edit { it[KEY_EBIKE_LOCATION_ASKED] = value }
    }

    /** 记住附近单车地图的最后视野（DESIGN §3.9），见 [ebikeMapViewport]。 */
    suspend fun setEbikeMapViewport(lat: Double, lng: Double, zoom: Double) {
        if (!lat.isFinite() || !lng.isFinite() || !zoom.isFinite()) return
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return
        context.displayDataStore.edit {
            it[KEY_EBIKE_VIEW_LAT] = lat.toFloat()
            it[KEY_EBIKE_VIEW_LNG] = lng.toFloat()
            it[KEY_EBIKE_VIEW_ZOOM] = zoom.toFloat().coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
        }
    }

    /** 记住底部面板高度（DESIGN §3.9），见 [ebikePanelHeightDp]。 */
    suspend fun setEbikePanelHeightDp(value: Float) {
        if (!value.isFinite() || value <= 0f) return
        context.displayDataStore.edit { it[KEY_EBIKE_PANEL_HEIGHT_DP] = value }
    }

    /**
     * 待焚毁记录统一写入口（DESIGN §3.9）：读-改-写整个集合，同值跳写。
     * 记录是"承诺焚毁"的凭据，写失败最多留一张孤儿图，不影响出码本身。
     */
    suspend fun updateEbikePendingDelete(transform: (Set<String>) -> Set<String>) {
        context.displayDataStore.edit { p ->
            val current = p[KEY_EBIKE_PENDING_DELETE] ?: emptySet()
            val next = transform(current)
            if (next != current) {
                if (next.isEmpty()) p.remove(KEY_EBIKE_PENDING_DELETE) else p[KEY_EBIKE_PENDING_DELETE] = next
            }
        }
    }

    /**
     * 最近共享单车车号统一写入口（DESIGN §3.9）：读-改-写整个 JSON，同值跳写
     * （口径同 [updateShortcuts]）。历史是锦上添花的回填数据，写失败不影响出码本身。
     */
    suspend fun updateEbikeRecentIds(transform: (List<String>) -> List<String>) {
        context.displayDataStore.edit { p ->
            val current = EbikeQr.decodeRecent(p[KEY_EBIKE_RECENT_IDS])
            val next = transform(current)
            if (next != current) p[KEY_EBIKE_RECENT_IDS] = EbikeQr.encodeRecent(next)
        }
    }

    /** 成绩页统计口径开关（DESIGN §4.15）。 */
    suspend fun setScoreIncludeFreeElectives(value: Boolean) {
        context.displayDataStore.edit { it[KEY_SCORE_INCLUDE_FREE_ELECTIVES] = value }
    }

    suspend fun setScoreGroupByYear(value: Boolean) {
        context.displayDataStore.edit { it[KEY_SCORE_GROUP_BY_YEAR] = value }
    }

    suspend fun setScoreSortMode(value: ScoreSortMode) {
        context.displayDataStore.edit { it[KEY_SCORE_SORT_MODE] = value.name }
    }

    // ---- 调课自动检测（DESIGN §4.17） ----

    val detectSettings: Flow<DetectSettings> = context.displayDataStore.data.map { p ->
        DetectSettings(
            enabled = p[KEY_DETECT_ENABLED] ?: false,
            periodHours = p[KEY_DETECT_PERIOD_HOURS] ?: 24,
            lastCheckedAt = p[KEY_DETECT_LAST_CHECKED_AT] ?: 0L,
            lastError = p[KEY_DETECT_LAST_ERROR] ?: "",
            credentialFailures = p[KEY_DETECT_CREDENTIAL_FAILURES] ?: 0,
            disabled = p[KEY_DETECT_DISABLED] ?: false,
        )
    }

    /** 开关总入口：重新开启时清掉自动停用标记与失败计数（DESIGN §4.17）。 */
    suspend fun setDetectEnabled(value: Boolean) {
        context.displayDataStore.edit {
            it[KEY_DETECT_ENABLED] = value
            if (value) {
                it[KEY_DETECT_DISABLED] = false
                it[KEY_DETECT_CREDENTIAL_FAILURES] = 0
            }
        }
    }

    suspend fun setDetectPeriodHours(hours: Int) {
        context.displayDataStore.edit {
            it[KEY_DETECT_PERIOD_HOURS] = DetectDefaults.PERIOD_HOURS
                .minByOrNull { h -> kotlin.math.abs(h - hours) } ?: 24
        }
    }

    /** 检测走完一次（建基线 / 无差异 / 出报告都算）：时间落库、错误与失败计数清零。 */
    suspend fun recordDetectSuccess(at: Long) {
        context.displayDataStore.edit {
            it[KEY_DETECT_LAST_CHECKED_AT] = at
            it[KEY_DETECT_LAST_ERROR] = ""
            it[KEY_DETECT_CREDENTIAL_FAILURES] = 0
        }
    }

    /** 检测完成了但有需要用户知情的事（如教务换学期）：时间推进，说明落 [lastError] 位展示。 */
    suspend fun recordDetectNotice(at: Long, message: String) {
        context.displayDataStore.edit {
            it[KEY_DETECT_LAST_CHECKED_AT] = at
            it[KEY_DETECT_LAST_ERROR] = message.take(200)
            it[KEY_DETECT_CREDENTIAL_FAILURES] = 0
        }
    }

    /**
     * 检测失败落库。[credentialFailed] 为 true 时累加连续凭证失败计数，
     * 达到 `DetectFailurePolicy.MAX_CREDENTIAL_FAILURES` 自动停用（防触发验证码锁号）。
     */
    suspend fun recordDetectFailure(message: String, credentialFailed: Boolean) {
        context.displayDataStore.edit {
            it[KEY_DETECT_LAST_ERROR] = message.take(200)
            if (credentialFailed) {
                val next = (it[KEY_DETECT_CREDENTIAL_FAILURES] ?: 0) + 1
                it[KEY_DETECT_CREDENTIAL_FAILURES] = next
                if (DetectFailurePolicy.shouldDisableAfterCredentialFailure(next)) {
                    it[KEY_DETECT_DISABLED] = true
                    it[KEY_DETECT_ENABLED] = false
                }
            }
        }
    }

    /**
     * 快捷方式统一写入口：读-改-写整个 JSON，同值跳写（口径同 [updateViewPrefs]）。
     * 编辑表单的保存/重置/调序都汇到这一个口，存储格式不外泄。
     * 基底走同一套预设迁移：第一次写入就把未升级的预设槽落成当前值。
     */
    suspend fun updateShortcuts(transform: (List<ShortcutItem>) -> List<ShortcutItem>) {
        context.displayDataStore.edit { p ->
            val current = Shortcuts.migratePresets(
                p[KEY_SHORTCUTS_JSON]?.let { Shortcuts.decode(it) } ?: Shortcuts.PRESET_SHORTCUTS,
            )
            val next = transform(current)
            if (next != current) p[KEY_SHORTCUTS_JSON] = Shortcuts.encode(next)
        }
    }

    /**
     * 上课提醒的「已发键」（DESIGN §3.7）：闹钟与 15 分钟周期核对共用去重。
     * 同一节课同一天有**两个独立通知点**（提前量「上课前 N 分钟」与上课时刻
     * 「开始上课」），按前缀分开去重：`before|日期|课id|起始分钟` /
     * `start|日期|课id|起始分钟`。旧版无前缀的单键在读路径迁移为
     * `before|旧值`（旧键只可能来自提前量提醒，上课开始点是 2026-09-22 新增）。
     * null = 该类从未发过。
     */
    suspend fun reminderLastKey(kind: ReminderKeyKind): String? {
        val raw = context.displayDataStore.data.first()[KEY_REMINDER_LAST] ?: return null
        val migrated = if (raw.startsWith(KEY_BEFORE) || raw.startsWith(KEY_START)) {
            raw
        } else {
            "${ReminderKeyKind.Before.prefix}$raw"
        }
        return if (migrated.startsWith(kind.prefix)) migrated.removePrefix(kind.prefix) else null
    }

    suspend fun setReminderLastKey(kind: ReminderKeyKind, key: String) {
        context.displayDataStore.edit { it[KEY_REMINDER_LAST] = "${kind.prefix}$key" }
    }

    /**
     * 作业截止提醒的「已发键」集合（DESIGN §3.11）。键 = `作业id|提醒点日期`（如 `12|2026-09-21`），
     * 闹钟与 15 分钟周期核对共用去重；与上课提醒的单一「已发键」不同，这里是集合
     * （多条作业各有一个提醒点，且两个提醒点日期不同）。键缺失/脏值回空集：
     * 最坏结果是重发一条通知，不该让核对链路抛异常。
     */
    suspend fun homeworkRemindedKeys(): Set<String> =
        context.displayDataStore.data.first()[KEY_HOMEWORK_REMINDED_KEYS] ?: emptySet()

    /**
     * 记下一个已发键（DESIGN §3.11），并把集合裁剪到最近 [HOMEWORK_REMINDED_LIMIT] 条。
     *
     * 裁剪策略：键里带着 ISO 日期（`yyyy-MM-dd`），同一时区下**字典序 = 时间序**，
     * 故按「提醒点日期」降序、同日期再按整键降序排，保留前 50 条——
     * 淘汰的只会是最久以前的提醒点，而它们的窗口早已关闭（越过 00:00 不补发），
     * 即使被淘汰也不会重复发。
     */
    suspend fun addHomeworkRemindedKey(key: String) {
        context.displayDataStore.edit { p ->
            val current = p[KEY_HOMEWORK_REMINDED_KEYS] ?: emptySet()
            val next = trimHomeworkRemindedKeys(current + key)
            if (next != current) p[KEY_HOMEWORK_REMINDED_KEYS] = next
        }
    }

    /**
     * 只保留最新的 [HOMEWORK_REMINDED_LIMIT] 条键（写路径唯一裁剪点，口径见 [addHomeworkRemindedKey]）。
     * 键结构异常（无 `|`）时按整键参与排序——不崩、不吞，最多排位不准。
     */
    private fun trimHomeworkRemindedKeys(keys: Set<String>): Set<String> =
        keys.sortedWith(
            compareByDescending<String> { it.substringAfterLast('|') }.thenByDescending { it },
        ).take(HOMEWORK_REMINDED_LIMIT).toSet()

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
        /** 作业截止提醒「已发键」保留条数（DESIGN §3.11：保留最近 50 条）。 */
        const val HOMEWORK_REMINDED_LIMIT = 50

        // 上课提醒的键迁移里也用（见 reminderLastKey 的 KDoc）
        const val KEY_BEFORE = "before|"
        const val KEY_START = "start|"

        // ---- 全局项（现行有效） ----
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
        val KEY_FLOATING_NAV_BAR = booleanPreferencesKey("floating_nav_bar")
        val KEY_LIFE_TAB_ENABLED = booleanPreferencesKey("life_tab_enabled")
        val KEY_WATER_REQUIRE_DOUBLE_CLICK = booleanPreferencesKey("water_require_double_click")
        val KEY_CALENDAR_REMINDER_MINUTES = intPreferencesKey("calendar_reminder_minutes")
        val KEY_REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val KEY_REMINDER_LEAD = intPreferencesKey("reminder_lead_minutes")
        val KEY_REMINDER_LAST = stringPreferencesKey("reminder_last_key")
        val KEY_HOMEWORK_REMINDER_ENABLED = booleanPreferencesKey("homework_reminder_enabled")
        val KEY_HOMEWORK_REMINDED_KEYS = stringSetPreferencesKey("homework_reminded_keys")
        val KEY_EBIKE_FREE_REMINDER_ENABLED = booleanPreferencesKey("ebike_free_reminder_enabled")
        val KEY_EBIKE_FREE_LEAD = intPreferencesKey("ebike_free_lead_minutes")
        val KEY_EBIKE_RIDE_START_AT = longPreferencesKey("ebike_ride_start_at")
        val KEY_EBIKE_FREE_EVENT_ID = longPreferencesKey("ebike_free_event_id")
        val KEY_CURRENT_TIMETABLE = longPreferencesKey("current_timetable_id")
        val KEY_DEFAULT_CONFIG_SOURCE = longPreferencesKey("default_config_source_id")
        val KEY_SLOT_SCHEMA = intPreferencesKey("slot_schema_version")
        val KEY_PREFS_MIGRATED = booleanPreferencesKey("timetable_prefs_migrated")
        val KEY_WIDGET_SETUP_SEEN = booleanPreferencesKey("widget_setup_seen")
        val KEY_SHORTCUTS_ENABLED = booleanPreferencesKey("shortcuts_enabled")
        val KEY_WATER_CARD_ENABLED = booleanPreferencesKey("water_card_enabled")
        val KEY_EBIKE_CARD_ENABLED = booleanPreferencesKey("ebike_card_enabled")
        val KEY_EBIKE_AUTO_SAVE = booleanPreferencesKey("ebike_auto_save")
        val KEY_EBIKE_BURN_AFTER_SCAN = booleanPreferencesKey("ebike_burn_after_scan")
        val KEY_EBIKE_LOCATION_ASKED = booleanPreferencesKey("ebike_location_asked")
        val KEY_EBIKE_VIEW_LAT = floatPreferencesKey("ebike_view_lat")
        val KEY_EBIKE_VIEW_LNG = floatPreferencesKey("ebike_view_lng")
        val KEY_EBIKE_VIEW_ZOOM = floatPreferencesKey("ebike_view_zoom")
        val KEY_EBIKE_PANEL_HEIGHT_DP = floatPreferencesKey("ebike_panel_height_dp")

        /** 保存视野时的缩放范围，与瓦片源的 1~19 对齐。 */
        const val MIN_MAP_ZOOM = 1.0f
        const val MAX_MAP_ZOOM = 19.0f
        val KEY_EBIKE_PENDING_DELETE = stringSetPreferencesKey("ebike_pending_delete")
        val KEY_EBIKE_RECENT_IDS = stringPreferencesKey("ebike_recent_ids")
        val KEY_CAMPUS_CARD_ENABLED = booleanPreferencesKey("campus_card_enabled")
        val KEY_TODAY_DOCK_EXPANDED = booleanPreferencesKey("today_dock_expanded")
        val KEY_PENDING_RECHARGE_FEN = longPreferencesKey("pending_recharge_fen")
        val KEY_PENDING_RECHARGE_AT = longPreferencesKey("pending_recharge_at")
        val KEY_PENDING_RECHARGE_BASE_FEN = longPreferencesKey("pending_recharge_base_fen")
        val KEY_PENDING_RECHARGE_ACCOUNT = stringPreferencesKey("pending_recharge_account")
        val KEY_PENDING_RECHARGE_CONFIRM_SHOWN = booleanPreferencesKey("pending_recharge_confirm_shown")
        val KEY_SHORTCUTS_JSON = stringPreferencesKey("shortcuts_json")
        val KEY_SCORE_INCLUDE_FREE_ELECTIVES = booleanPreferencesKey("score_include_free_electives")
        val KEY_SCORE_GROUP_BY_YEAR = booleanPreferencesKey("score_group_by_year")
        val KEY_SCORE_SORT_MODE = stringPreferencesKey("score_sort_mode")
        val KEY_PROFILE_NAME = stringPreferencesKey("profile_name")
        val KEY_PROFILE_CLASS = stringPreferencesKey("profile_class")

        // ---- 调课自动检测（DESIGN §4.17；凭证不在这里，见 JwCredentialStore） ----
        val KEY_DETECT_ENABLED = booleanPreferencesKey("tweak_detect_enabled")
        val KEY_DETECT_PERIOD_HOURS = intPreferencesKey("tweak_detect_period_hours")
        val KEY_DETECT_LAST_CHECKED_AT = longPreferencesKey("tweak_detect_last_checked_at")
        val KEY_DETECT_LAST_ERROR = stringPreferencesKey("tweak_detect_last_error")
        val KEY_DETECT_CREDENTIAL_FAILURES = intPreferencesKey("tweak_detect_credential_failures")
        val KEY_DETECT_DISABLED = booleanPreferencesKey("tweak_detect_disabled")

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
