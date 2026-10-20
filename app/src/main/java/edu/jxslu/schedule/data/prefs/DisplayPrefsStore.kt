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
import edu.jxslu.schedule.data.ai.AiScheduleClient
import edu.jxslu.schedule.domain.AiScheduleConfig
import edu.jxslu.schedule.domain.BgScale
import edu.jxslu.schedule.domain.BalanceAlert
import edu.jxslu.schedule.domain.BalanceAlertSource
import edu.jxslu.schedule.domain.BikeMapViewport
import edu.jxslu.schedule.domain.CainiaoQuery
import edu.jxslu.schedule.domain.CalendarSyncDefaults
import edu.jxslu.schedule.domain.CourseFilter
import edu.jxslu.schedule.domain.EbikeAutoReturn
import edu.jxslu.schedule.domain.EbikeCardExpiry
import edu.jxslu.schedule.domain.EbikeFreeRide
import edu.jxslu.schedule.domain.EbikeQr
import edu.jxslu.schedule.domain.EbikeUseMode
import edu.jxslu.schedule.domain.FirstRunNotice
import edu.jxslu.schedule.domain.FloatingBall
import edu.jxslu.schedule.domain.QuietWindow
import edu.jxslu.schedule.domain.QuietWindows
import edu.jxslu.schedule.domain.ScoreAlertDefaults
import edu.jxslu.schedule.domain.SelectionWish
import edu.jxslu.schedule.domain.SelectionWishes
import edu.jxslu.schedule.domain.ReminderDefaults
import edu.jxslu.schedule.domain.ScheduleAlertDefaults
import edu.jxslu.schedule.domain.ScoreSortMode
import edu.jxslu.schedule.domain.SchedulePendingChanges
import edu.jxslu.schedule.domain.WenquRules
import edu.jxslu.schedule.domain.ShortcutItem
import edu.jxslu.schedule.domain.ShortcutSettings
import edu.jxslu.schedule.domain.Shortcuts
import edu.jxslu.schedule.domain.StartPage
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.domain.ThemePalette
import edu.jxslu.schedule.domain.TimetablePrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

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
    /**
     * 动态取色（Material You）。全局项；false = 用回内置配色方案。
     * **默认关**（2026-10-20）：默认观感固定为晴空蓝，想跟壁纸的用户再打开。
     */
    val dynamicColor: Boolean = false,
    /** 内置主题配色（DESIGN §3.3）。全局项；动态取色开着时被忽略。默认晴空蓝。 */
    val themePalette: ThemePalette = ThemePalette.SkyBlue,
    /**
     * 悬浮导航栏（DESIGN §4.22）。全局项，**默认关**：底栏半透明磨砂，
     * 课表页背景图透到屏幕底部。关 = 保持原样（不透明底栏）。
     */
    val floatingNavBar: Boolean = false,
    /**
     * 百宝箱（DESIGN §4.47）。全局项，**默认开**：今日页顶栏「+」下拉里的
     * 「百宝箱」一行（图片水印 · 图片拼接 · 菜鸟批量查）。
     * 关 = 该行从「+」菜单消失，与其余功能开关同一语义（关闭后全部入口隐藏）。
     */
    val toolboxEnabled: Boolean = true,
    /**
     * 启动页（DESIGN §3.1 / §3.3）。全局项，**默认 [StartPage.Today]**：主窗口每次
     * 新建时落的那个 Tab。生活页关掉时「生活」这一项在设置里同步消失，存着的旧值
     * 由 [StartPage.effectivePage] 落回今日页（不写回存储，生活页开回来时旧选择还在）。
     * 生效时机同悬浮导航栏：启动期读死，**重启应用后生效**（理由见 DESIGN §3.3）。
     */
    val startPage: StartPage = StartPage.Today,
    /**
     * 触感反馈开关。全局项（交互手感不随课表变）。
     * 默认开：点击类操作给轻触感是系统应用的普遍预期，嫌吵的人再关。
     */
    val hapticsEnabled: Boolean = true,
    /**
     * 字体缩放锁定（「固定字体大小」）。全局项，**默认开**：App 内文字按固定 0.9×
     * 渲染，不随系统「字体大小」缩放，避免不同机器的字号档位把组件文字挤占。
     * 关 = 完全回到旧行为（全 App 跟随系统；课表网格另有收敛，见
     * [edu.jxslu.schedule.domain.GridFont]）。即时生效——由 Compose 根覆盖
     * `LocalDensity`，无重启要求（口径见 [edu.jxslu.schedule.domain.FontScalePolicy]）。
     */
    val fontScaleLocked: Boolean = true,
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
     * 课名目标字号（dp）。null = 跟随（未拖过滑块；锁定开启时 = 课名基准 × 0.9，
     * 关闭「固定字体大小」则跟随系统）。
     * 取值范围与含义见 [edu.jxslu.schedule.domain.GridFont]。
     */
    val gridFontDp: Float? = null,
    /** 地点目标字号（dp）；null = 按课名倍率等比缩放。 */
    val gridRoomDp: Float? = null,
    /** 教师目标字号（dp）；null = 按课名倍率等比缩放。 */
    val gridTeacherDp: Float? = null,
    /** 时间轴（节次号 + 起止时间）目标字号（dp）；null = 跟随（同课名口径）。独立于课名字号。 */
    val gridRailDp: Float? = null,
    /** 月份 / 日期表头目标字号（dp）；null = 跟随（同课名口径）。独立于课名字号。 */
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
    /**
     * 今日页趣智校园卡片显示开关（DESIGN §4.30）。**默认开**。
     * 与开水卡并排占一行：开 = 两卡各半行；关 = 胖乖生活独占整行（回到加本卡之前的样子）。
     */
    val qzxyCardEnabled: Boolean = true,
    /** 今日页共享单车卡显示开关（DESIGN §3.9）。默认开（用户要求入口常驻）。 */
    val ebikeCardEnabled: Boolean = true,
    /**
     * 快趣出行的使用方式（DESIGN §3.9 / §4.32）。**默认微信小程序方式**：
     * 账号登录方式要凭证、有计费后果与支付分授权门槛，只能由用户显式选择；
     * 小程序方式不需要任何凭证。能力判据的唯一来源见 [EbikeUseMode]。
     */
    val ebikeUseMode: EbikeUseMode = EbikeUseMode.Default,
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
     * 开锁前的二次确认弹窗（DESIGN §3.9 / §4.32）。**默认开**。
     *
     * 关掉 = 点「开锁」直接发指令（在开锁弹窗勾「不再提醒」也落这一项）。
     * 这是四道闸里**唯一允许用户自己关**的一道——用户对自己账号的授权，
     * 关掉不代表没有后果（一样计费、一样禁自动重试），所以另有设置开关可以反悔；
     * 定位前置、零自动重试、降级官方渠道三道闸与它无关，任何情况下都不动。
     */
    val ebikeUnlockConfirm: Boolean = true,
    /**
     * 最近生成的共享单车车号（**完整车号**，倒序去重，上限见 [EbikeQr.RECENT_LIMIT]）。
     * 2026-09-23 之前只存尾部 3 位，[EbikeQr.decodeRecent] 读旧数据时补前缀。
     */
    val ebikeRecentIds: List<String> = emptyList(),
    /**
     * 今日页校园卡付款码卡开关（DESIGN §3.10）。**默认关**：涉及凭证与资金等价物，
     * 用户显式开启。**2026-09-24 起今日页不再展示一卡通卡**（能力收进生活页，§3.13），
     * 值仅作历史偏好保留，UI 不再消费。
     */
    val campusCardEnabled: Boolean = false,
)

// 调课自动检测的 DetectSettings / DetectDefaults 与相关键、方法已随功能移除
// （2026-09-24）。DataStore 里的历史键（tweak_detect_*）不主动清理——
// 无人再读，留着无害；逐键 remove 反而要为「清偏好」单独加一次写盘。

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
    /**
     * 充值目标（DESIGN §3.10）：`<account>-000` = 电子账户；null = 正式卡。
     * 到账判定按目标分两路（卡余额 / 钱包余额），2026-09-24 补。
     */
    val targetAccount: String?,
    /** 充电子账户时：付款前目标钱包余额（分）；null = 未取到。 */
    val walletBalanceBeforeFen: Long?,
    /** 「正在确认到账」弹窗是否已提示过（每次充值只弹一次）。 */
    val confirmShown: Boolean,
)

/**
 * 首启声明的同意记录（DESIGN §3.16）。
 *
 * [version] 是**用户同意过的那一版文案**，不是"是否同意过"的布尔：文案改了把
 * `domain/FirstRunNotices.VERSION` +1，比对版本就能让用户重看新条款。
 * [atMs] = 确认时刻（epoch 毫秒），0 = 从未确认过；它同时是"关闭锁是否生效"的判据
 * （`domain/NoticeConsent.closeLockMs`）与「关于」页展示的凭据。
 */
data class NoticeConsentRecord(
    /** 已同意的文案版本；0 = 从未确认过。 */
    val version: Int,
    /** 确认时刻（epoch 毫秒）；0 = 从未确认过。 */
    val atMs: Long,
)

/**
 * 首启引导的后台同步结果（DESIGN §4.29 / §4.17）：课表、成绩、学业三项各自
 * 「试没试过 + 成没成功」。[tried] 与 [ok] 分开是因为课表只有「目标为空」才会试——
 * 试都没试就不该在弹窗里报失败。全部 settle 后由引导页 `startBgSync` 一次性写入；
 * 主界面弹结果弹窗后置 [consumed]（点「知道了」才消费——「查看」只跳页不清状态，
 * 用户可以回来接着看另一项）。
 */
@kotlinx.serialization.Serializable
data class OnboardingSyncOutcome(
    val finishedAt: Long = 0,
    /** 首启自动导课表：tried = 课表为空触发了抓取，ok = 写库成功。 */
    val scheduleTried: Boolean = false,
    val scheduleOk: Boolean = false,
    /** 写库条数（scheduleOk 时有意义）；教务本学期没课的情况不会走到这里（按失败报）。 */
    val scheduleCount: Int = 0,
    val scoreTried: Boolean = false,
    val scoreOk: Boolean = false,
    val scoreCount: Int = 0,
    val scholarTried: Boolean = false,
    val scholarOk: Boolean = false,
    /** 学业完成情况的课程明细条数（scholarOk 时有意义）。 */
    val scholarCount: Int = 0,
    /** 失败原因（对应项 ok = false 时弹窗里展示，帮用户判断是网络还是会话问题）。 */
    val scheduleFailReason: String? = null,
    val scoreFailReason: String? = null,
    val scholarFailReason: String? = null,
    /** 「知道了」按下后置 true（消费即清，不重复打扰）；「查看」跳页不改它。 */
    val consumed: Boolean = false,
) {
    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun decode(text: String): OnboardingSyncOutcome? =
            runCatching { json.decodeFromString<OnboardingSyncOutcome>(text) }.getOrNull()

        fun encode(value: OnboardingSyncOutcome): String =
            json.encodeToString(OnboardingSyncOutcome.serializer(), value)
    }
}

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

    /** 字体缩放锁定（「固定字体大小」）。全局项，默认开；见 [DisplayPrefs.fontScaleLocked]。 */
    val fontScaleLocked: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_FONT_SCALE_LOCKED] ?: true
    }

    /** 动态取色（Material You）。全局项，默认关；关 = 用回内置配色方案。 */
    val dynamicColor: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_DYNAMIC_COLOR] ?: false
    }

    /** 内置主题配色。全局项，默认晴空蓝；动态取色开着时忽略本项。 */
    val themePalette: Flow<ThemePalette> = context.displayDataStore.data.map { p ->
        ThemePalette.fromId(p[KEY_THEME_PALETTE])
    }

    /** 悬浮导航栏（DESIGN §4.22）。默认关：不透明底栏是既有观感，用户显式开启才改。 */
    val floatingNavBar: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_FLOATING_NAV_BAR] ?: false
    }

    /** 百宝箱开关（DESIGN §4.47）。默认开：今日页「+」下拉里的图片工具入口。 */
    val toolboxEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_TOOLBOX_ENABLED] ?: true
    }

    /** 应用内悬浮球开关（DESIGN §3.1）。全局项，**默认开**：主窗口四个 Tab 常驻的快捷入口。 */
    val floatingBallEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_FLOATING_BALL_ENABLED] ?: true
    }.distinctUntilChanged()

    /**
     * 悬浮球归一化位置（DESIGN §3.1）：x/y ∈ [0,1]，缺省 = 右下角。
     * 读路径夹取防线脏数据（含非有限值）；位置不吸附边缘，落在哪存哪。
     */
    val floatingBallPosition: Flow<FloatingBall.Position> = context.displayDataStore.data.map { p ->
        FloatingBall.clamp(
            FloatingBall.Position(
                x = p[KEY_FLOATING_BALL_X] ?: FloatingBall.DefaultPosition.x,
                y = p[KEY_FLOATING_BALL_Y] ?: FloatingBall.DefaultPosition.y,
            ),
        )
    }.distinctUntilChanged()

    /**
     * 启动页（DESIGN §3.3）。默认今日页；认不出的存储值一律退回今日页（[StartPage.fromName]）。
     */
    val startPage: Flow<StartPage> = context.displayDataStore.data.map { p ->
        StartPage.fromName(p[KEY_START_PAGE])
    }

    /**
     * 首启引导是否走完（DESIGN §3.16）。完成或跳过都算，默认 false。
     *
     * **只认这个键**，不认「有没有课表」——导入过课表又清空过的用户不该被重新引导一遍。
     */
    val onboardingSeen: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_ONBOARDING_SEEN] ?: false
    }

    suspend fun setOnboardingSeen() {
        context.displayDataStore.edit { it[KEY_ONBOARDING_SEEN] = true }
    }

    /** 「我的」账号条：姓名（教务学籍卡导入）。null/空 = 未导入过，退回学号显示。 */
    val profileName: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_PROFILE_NAME].orEmpty()
    }

    /** 「我的」账号条：班级（教务学籍卡导入）。缺失时账号条副行只剩学号。 */
    val profileClass: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_PROFILE_CLASS].orEmpty()
    }

    /**
     * 「我的」账号条：学号（教务学籍卡导入）。
     *
     * 存在的理由：学号原先只从凭证里取，于是「引导跳过教务、后来在导入页手登」的用户
     * 有会话却没凭证，卡片只能写「未登录」（2026-09-27 用户报）。学籍卡本来就有学号，
     * 顺手落库，身份区就有真值可用。
     */
    val profileStudentId: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_PROFILE_STUDENT_ID].orEmpty()
    }

    /** 学籍信息页：院系（教务学籍卡片）。 */
    val profileCollege: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_PROFILE_COLLEGE].orEmpty()
    }

    /** 学籍信息页：专业（教务学籍卡片）。 */
    val profileMajor: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_PROFILE_MAJOR].orEmpty()
    }

    /** 学籍信息页：学制（教务学籍卡片，原始文本如「4」）。 */
    val profileDuration: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_PROFILE_DURATION].orEmpty()
    }

    /** 学籍卡落库；各字段为空串时对应键不动，避免一次脏解析抹掉已有数据。 */
    suspend fun setProfile(
        name: String?,
        className: String?,
        studentId: String? = null,
        college: String? = null,
        major: String? = null,
        duration: String? = null,
    ) {
        context.displayDataStore.edit { p ->
            if (!name.isNullOrBlank()) p[KEY_PROFILE_NAME] = name.trim()
            if (!className.isNullOrBlank()) p[KEY_PROFILE_CLASS] = className.trim()
            if (!studentId.isNullOrBlank()) p[KEY_PROFILE_STUDENT_ID] = studentId.trim()
            if (!college.isNullOrBlank()) p[KEY_PROFILE_COLLEGE] = college.trim()
            if (!major.isNullOrBlank()) p[KEY_PROFILE_MAJOR] = major.trim()
            if (!duration.isNullOrBlank()) p[KEY_PROFILE_DURATION] = duration.trim()
        }
    }

    /**
     * 清除学籍卡缓存（「清除全部凭证」时用）：姓名 / 班级 / 学号与补抓闸门一起删。
     * 不清的话，凭证已经清空、账号卡还会显示上一个登录者的姓名与班级。
     */
    suspend fun clearProfile() {
        context.displayDataStore.edit { p ->
            p.remove(KEY_PROFILE_NAME)
            p.remove(KEY_PROFILE_CLASS)
            p.remove(KEY_PROFILE_STUDENT_ID)
            p.remove(KEY_PROFILE_COLLEGE)
            p.remove(KEY_PROFILE_MAJOR)
            p.remove(KEY_PROFILE_DURATION)
            p.remove(KEY_PROFILE_SYNC_DATE)
        }
    }

    /**
     * 「我的」页学籍卡补抓的日期闸门（DESIGN §3.3）。空 = 从没试过。
     *
     * 抓不到时不能每次进页都重试——那是白打教务。与余额提醒的 `balanceAlertCheckMillis`
     * 同款口径（失败不落，下一轮还有机会）。
     */
    val profileSyncDate: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_PROFILE_SYNC_DATE].orEmpty()
    }

    suspend fun setProfileSyncDate(dateKey: String) {
        context.displayDataStore.edit { it[KEY_PROFILE_SYNC_DATE] = dateKey }
    }

    /**
     * 学业完成情况的培养方案名（DESIGN §4.29），如「2024 xxx专业培养方案及教学计划」。
     *
     * 只作展示用；解析没拿到时留空，UI 不显示这一行——它不是判断"有没有数据"的依据，
     * 那个看 [ScholarProgressRepository.hasData]。
     */
    val scholarPlanName: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_SCHOLAR_PLAN_NAME].orEmpty()
    }

    suspend fun setScholarPlanName(value: String) {
        context.displayDataStore.edit { it[KEY_SCHOLAR_PLAN_NAME] = value.trim() }
    }

    /**
     * 学业完成情况的上次成功导入日期（ISO `2026-09-27`）。空 = 从没成功过。
     *
     * 与成绩的 [scoreSyncDate] 共用同一套闸门判定（`AutoSyncRules`）：这类数据一学期
     * 才动一次，每次冷启动都抓是白撞教务风控。
     */
    val scholarSyncDate: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_SCHOLAR_SYNC_DATE].orEmpty()
    }

    suspend fun setScholarSyncDate(dateKey: String) {
        context.displayDataStore.edit { it[KEY_SCHOLAR_SYNC_DATE] = dateKey }
    }

    /**
     * 培养方案的方案名（DESIGN §4.41），如「2022 xxx专业培养方案」。
     *
     * 只作展示用；解析没拿到时留空，UI 不显示这一行——它不是判断"有没有数据"的依据，
     * 那个看 `CurriculumRepository.hasData`。
     */
    val curriculumPlanName: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_CURRICULUM_PLAN_NAME].orEmpty()
    }

    suspend fun setCurriculumPlanName(value: String) {
        context.displayDataStore.edit { it[KEY_CURRICULUM_PLAN_NAME] = value.trim() }
    }

    /**
     * 培养方案的上次成功导入日期（ISO `2026-10-07`）。空 = 从没成功过。
     *
     * 与学业的 [scholarSyncDate] 共用 `AutoSyncRules.shouldAttempt` 的 7 天口径——
     * 培养方案几年才动一次，每次冷启动都抓是白撞教务风控。
     */
    val curriculumSyncDate: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_CURRICULUM_SYNC_DATE].orEmpty()
    }

    suspend fun setCurriculumSyncDate(dateKey: String) {
        context.displayDataStore.edit { it[KEY_CURRICULUM_SYNC_DATE] = dateKey }
    }

    /** 成绩的上次成功导入日期（ISO）。空 = 从没成功过。口径同 [scholarSyncDate]。 */
    val scoreSyncDate: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_SCORE_SYNC_DATE].orEmpty()
    }

    suspend fun setScoreSyncDate(dateKey: String) {
        context.displayDataStore.edit { it[KEY_SCORE_SYNC_DATE] = dateKey }
    }

    // ---- 成绩/考试变动提醒（DESIGN §4.33）----

    /** 成绩变动提醒开关。**默认开**（2026-10-06 用户拍板）；显式关过的用户落盘 false，不受默认值影响。 */
    val scoreAlertEnabled: Flow<Boolean> =
        context.displayDataStore.data.map { it[KEY_SCORE_ALERT_ENABLED] ?: true }

    suspend fun setScoreAlertEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_SCORE_ALERT_ENABLED] = value }
    }

    /** 考试变动提醒开关。**默认开**（同成绩，2026-10-06）。 */
    val examAlertEnabled: Flow<Boolean> =
        context.displayDataStore.data.map { it[KEY_EXAM_ALERT_ENABLED] ?: true }

    suspend fun setExamAlertEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_EXAM_ALERT_ENABLED] = value }
    }

    /**
     * 成绩变动提醒的检查间隔（小时）。**2026-10-04 起四条提醒链各自独立**（用户口径：
     * 成绩/考试/选课/调课分开设置），旧共用键 `KEY_ALERT_INTERVAL_HOURS` 只作老用户
     * 读取回退——新键没写过时沿用旧值，写过后永远优先，无需一次性迁移。
     */
    val scoreAlertIntervalHours: Flow<Int> = context.displayDataStore.data.map { p ->
        p[KEY_SCORE_INTERVAL_HOURS] ?: p[KEY_ALERT_INTERVAL_HOURS] ?: ScoreAlertDefaults.INTERVAL_DEFAULT
    }

    suspend fun setScoreAlertIntervalHours(value: Int) {
        context.displayDataStore.edit { p -> p[KEY_SCORE_INTERVAL_HOURS] = value }
    }

    /** 考试变动提醒的检查间隔（小时），独立于成绩（口径见 [scoreAlertIntervalHours]）。 */
    val examAlertIntervalHours: Flow<Int> = context.displayDataStore.data.map { p ->
        p[KEY_EXAM_INTERVAL_HOURS] ?: p[KEY_ALERT_INTERVAL_HOURS] ?: ScoreAlertDefaults.INTERVAL_DEFAULT
    }

    suspend fun setExamAlertIntervalHours(value: Int) {
        context.displayDataStore.edit { p -> p[KEY_EXAM_INTERVAL_HOURS] = value }
    }

    /** 选课轮次提醒的核对间隔（小时），独立设置；精确闹钟仍由轮次时刻驱动。 */
    val selectionAlertIntervalHours: Flow<Int> = context.displayDataStore.data.map { p ->
        p[KEY_SELECTION_INTERVAL_HOURS] ?: p[KEY_ALERT_INTERVAL_HOURS] ?: ScoreAlertDefaults.INTERVAL_DEFAULT
    }

    suspend fun setSelectionAlertIntervalHours(value: Int) {
        context.displayDataStore.edit { p -> p[KEY_SELECTION_INTERVAL_HOURS] = value }
    }

    /** 调课检测的检查间隔（小时），独立设置；默认低频档（每次核对抓两张课表页）。 */
    val scheduleAlertIntervalHours: Flow<Int> = context.displayDataStore.data.map { p ->
        p[KEY_SCHEDULE_INTERVAL_HOURS] ?: p[KEY_ALERT_INTERVAL_HOURS] ?: ScheduleAlertDefaults.INTERVAL_DEFAULT
    }

    suspend fun setScheduleAlertIntervalHours(value: Int) {
        context.displayDataStore.edit { p -> p[KEY_SCHEDULE_INTERVAL_HOURS] = value }
    }

    /** 成绩上次成功检查时刻（epoch millis）。0 = 从没成功过。失败不写。 */
    suspend fun scoreAlertCheckMillis(): Long =
        context.displayDataStore.data.first()[KEY_SCORE_CHECK_MILLIS] ?: 0L

    suspend fun setScoreAlertCheckMillis(millis: Long) {
        context.displayDataStore.edit { it[KEY_SCORE_CHECK_MILLIS] = millis }
    }

    /** 考试上次成功检查时刻（epoch millis）。0 = 从没成功过。失败不写。 */
    suspend fun examAlertCheckMillis(): Long =
        context.displayDataStore.data.first()[KEY_EXAM_CHECK_MILLIS] ?: 0L

    suspend fun setExamAlertCheckMillis(millis: Long) {
        context.displayDataStore.edit { it[KEY_EXAM_CHECK_MILLIS] = millis }
    }

    /**
     * 考试壳页上次抓到的学期 + 时刻（2026-10-04 风控优化，判据见
     * `AutoSyncRules.shellTermCacheUsable`）：TTL 内复用，省一次壳页 GET。
     * 只在真的抓到壳页之后写盘。
     */
    suspend fun examShellTerm(): String? =
        context.displayDataStore.data.first()[KEY_EXAM_SHELL_TERM]?.takeIf { it.isNotBlank() }

    /** 考试壳页学期抓到时刻。0 = 从没抓到过。 */
    suspend fun examShellTermAtMillis(): Long =
        context.displayDataStore.data.first()[KEY_EXAM_SHELL_AT_MILLIS] ?: 0L

    suspend fun setExamShellTerm(term: String, millis: Long) {
        context.displayDataStore.edit {
            it[KEY_EXAM_SHELL_TERM] = term
            it[KEY_EXAM_SHELL_AT_MILLIS] = millis
        }
    }

    // ---- 选课（DESIGN §4.35）----

    /** 选课记录上次成功同步时刻（epoch millis）。0 = 从没成功过。失败不写（同成绩/考试纪律）。 */
    suspend fun selectionSyncMillis(): Long =
        context.displayDataStore.data.first()[KEY_SELECTION_SYNC_MILLIS] ?: 0L

    suspend fun setSelectionSyncMillis(millis: Long) {
        context.displayDataStore.edit { it[KEY_SELECTION_SYNC_MILLIS] = millis }
    }

    /**
     * 教务选课日志页学期下拉的全量选项（选课页「更多学期」用；逗号分隔，空 = 还没抓到过）。
     * 存下来是为了列个学期清单不用再打一次教务。
     */
    suspend fun selectionTerms(): List<String> =
        context.displayDataStore.data.first()[KEY_SELECTION_TERMS].orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }

    suspend fun setSelectionTerms(terms: List<String>) {
        context.displayDataStore.edit { it[KEY_SELECTION_TERMS] = terms.joinToString(",") }
    }

    /**
     * 选课壳页上次抓到的「教务当前学期」+ 时刻（2026-10-04 风控优化，判据同考试壳页）：
     * TTL 内复用，省一次壳页 GET。学期翻年滞后半个窗口内选课记录本就不变，无实际影响。
     */
    suspend fun selectionShellTerm(): String? =
        context.displayDataStore.data.first()[KEY_SELECTION_SHELL_TERM]?.takeIf { it.isNotBlank() }

    /** 选课壳页学期抓到时刻。0 = 从没抓到过。 */
    suspend fun selectionShellTermAtMillis(): Long =
        context.displayDataStore.data.first()[KEY_SELECTION_SHELL_AT_MILLIS] ?: 0L

    suspend fun setSelectionShellTerm(term: String, millis: Long) {
        context.displayDataStore.edit {
            it[KEY_SELECTION_SHELL_TERM] = term
            it[KEY_SELECTION_SHELL_AT_MILLIS] = millis
        }
    }

    /** 选课轮次上次成功检查时刻（epoch millis）。0 = 从没成功过。失败不写。 */
    suspend fun selectionRoundsCheckMillis(): Long =
        context.displayDataStore.data.first()[KEY_SELECTION_ROUNDS_CHECK_MILLIS] ?: 0L

    suspend fun setSelectionRoundsCheckMillis(millis: Long) {
        context.displayDataStore.edit { it[KEY_SELECTION_ROUNDS_CHECK_MILLIS] = millis }
    }

    /** 选课轮次提醒开关。默认关（通知是打扰型能力，与成绩/考试提醒同一口径，用户显式开启）。 */
    val selectionAlertEnabled: Flow<Boolean> =
        context.displayDataStore.data.map { p -> p[KEY_SELECTION_ALERT_ENABLED] ?: false }

    suspend fun setSelectionAlertEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_SELECTION_ALERT_ENABLED] = value }
    }

    /**
     * 选课提醒「已发键」集合（`轮次id|start` / `轮次id|end`），闹钟与周期核对共用去重。
     * 键缺失/脏值回空集：最坏结果是重发一条通知，不该让核对链路抛异常。
     * 不做裁剪：一个学期只有几轮，累计是个位数，没有淘汰的必要。
     */
    suspend fun selectionRemindedKeys(): Set<String> =
        context.displayDataStore.data.first()[KEY_SELECTION_REMINDED_KEYS] ?: emptySet()

    suspend fun addSelectionRemindedKey(key: String) {
        context.displayDataStore.edit { p ->
            p[KEY_SELECTION_REMINDED_KEYS] = (p[KEY_SELECTION_REMINDED_KEYS] ?: emptySet()) + key
        }
    }

    // ---- 预选清单（DESIGN §3.20 / §4.36，2026-10-08 v3 起为「预购匹配」）----

    /**
     * 预选清单（选课期进选课中心时按它匹配、命中课程优先显示；选课仍由用户逐次确认）。
     * JSON 存储，口径同快捷方式：坏数据回空表，不崩在读路径上。
     */
    val selectionWishes: Flow<List<SelectionWish>> = context.displayDataStore.data.map { p ->
        p[KEY_SELECTION_WISHES_JSON]?.let { SelectionWishes.decode(it) } ?: emptyList()
    }

    suspend fun setSelectionWishes(wishes: List<SelectionWish>) {
        context.displayDataStore.edit { p ->
            p[KEY_SELECTION_WISHES_JSON] = SelectionWishes.encode(wishes)
        }
    }

    // ---- 调课检测（DESIGN §4.17，2026-10-04 重立）----

    /** 调课提醒开关。默认关（通知是打扰型能力，与成绩/考试/选课提醒同一口径）。 */
    val scheduleAlertEnabled: Flow<Boolean> =
        context.displayDataStore.data.map { p -> p[KEY_SCHEDULE_ALERT_ENABLED] ?: false }

    suspend fun setScheduleAlertEnabled(value: Boolean) {
        context.displayDataStore.edit { p -> p[KEY_SCHEDULE_ALERT_ENABLED] = value }
    }

    /** 调课上次成功检查时刻（epoch millis）。0 = 从没成功过。失败不写（同成绩/考试纪律）。 */
    suspend fun scheduleCheckMillis(): Long =
        context.displayDataStore.data.first()[KEY_SCHEDULE_CHECK_MILLIS] ?: 0L

    suspend fun setScheduleCheckMillis(millis: Long) {
        context.displayDataStore.edit { p -> p[KEY_SCHEDULE_CHECK_MILLIS] = millis }
    }

    /**
     * 「已阅」差异指纹（[ScheduleDiff.fingerprintOf] 的结果）。用户点了「忽略」或
     * 「合并」后写入——下次检查到相同指纹的差异不再重复提醒；教务再出新差异指纹
     * 才会变。基线推进（合并/重新导入）时清空（旧指纹失去意义）。
     */
    suspend fun scheduleIgnoredFingerprint(): String =
        context.displayDataStore.data.first()[KEY_SCHEDULE_IGNORED_FP].orEmpty()

    suspend fun setScheduleIgnoredFingerprint(value: String) {
        context.displayDataStore.edit { p -> p[KEY_SCHEDULE_IGNORED_FP] = value }
    }

    /**
     * 待处置差异缓存：检查到新差异时写入，处置页与课表 hub 的入口行读它，
     * 合并/忽略成功后清空。**它同时是「已提醒」的记录**——指纹与缓存相同的差异
     * 不再发第二条通知（用户还没处置，重发只是打扰）。
     */
    val schedulePendingChanges: Flow<SchedulePendingChanges?> =
        context.displayDataStore.data.map { p ->
            p[KEY_SCHEDULE_PENDING_JSON]?.takeIf { it.isNotBlank() }?.let(SchedulePendingChanges::decode)
        }

    suspend fun schedulePendingChanges(): SchedulePendingChanges? = schedulePendingChanges.first()

    suspend fun setSchedulePendingChanges(value: SchedulePendingChanges?) {
        context.displayDataStore.edit { p ->
            p[KEY_SCHEDULE_PENDING_JSON] = value?.encode().orEmpty()
        }
    }

    /**
     * 首启引导的后台同步结果（课表 / 成绩 / 学业三项各自 tried + ok）。
     * 引导页 `startBgSync` 在三项全部 settle 后写入；主界面读它弹「已导入」结果弹窗，
     * 弹过即置 consumed。null = 没有未消费的结果（没走过首启 / 已消费 / startAtJw 重进）。
     */
    val onboardingSyncOutcome: Flow<OnboardingSyncOutcome?> =
        context.displayDataStore.data.map { p ->
            p[KEY_ONBOARDING_SYNC_JSON]?.takeIf { it.isNotBlank() }?.let(OnboardingSyncOutcome::decode)
        }

    suspend fun setOnboardingSyncOutcome(value: OnboardingSyncOutcome?) {
        context.displayDataStore.edit { p ->
            p[KEY_ONBOARDING_SYNC_JSON] = value?.let(OnboardingSyncOutcome::encode).orEmpty()
        }
    }

    /**
     * 已排的周期核对间隔（小时，[ScoreAlertReminder] 用）。0 = 当前没有在排的周期任务。
     * 用途：WorkManager 2.7 没有 UPDATE 策略，周期任务只在「间隔档位变了」时才 REPLACE，
     * 否则 KEEP 保相位——这里记着「上次排的是几小时」。
     */
    suspend fun alertPeriodicInterval(): Int =
        context.displayDataStore.data.first()[KEY_ALERT_PERIODIC_INTERVAL] ?: 0

    suspend fun setAlertPeriodicInterval(value: Int) {
        context.displayDataStore.edit { it[KEY_ALERT_PERIODIC_INTERVAL] = value }
    }

    // ---- 问渠新增志愿活动提醒（DESIGN §4.45，2026-10-08）----

    /**
     * 新增志愿活动提醒开关。**默认关**——通知是打扰型能力（同选课 / 调课提醒口径；
     * 与「成绩/考试变动默认开」不同：那是用户要的事实，这是「可能有你感兴趣的东西」）。
     */
    val wenquActivityAlertEnabled: Flow<Boolean> =
        context.displayDataStore.data.map { p -> p[KEY_WENQU_ACTIVITY_ALERT_ENABLED] ?: false }

    suspend fun setWenquActivityAlertEnabled(value: Boolean) {
        context.displayDataStore.edit { p -> p[KEY_WENQU_ACTIVITY_ALERT_ENABLED] = value }
    }

    /** 问渠活动提醒的检查间隔（小时），默认 24（一天一次，第三方系统别打太勤）。 */
    val wenquActivityIntervalHours: Flow<Int> = context.displayDataStore.data.map { p ->
        p[KEY_WENQU_ACTIVITY_INTERVAL_HOURS] ?: WenquRules.ACTIVITY_ALERT_INTERVAL_DEFAULT
    }

    suspend fun setWenquActivityIntervalHours(value: Int) {
        context.displayDataStore.edit { p -> p[KEY_WENQU_ACTIVITY_INTERVAL_HOURS] = value }
    }

    /** 问渠活动上次成功检查时刻（epoch millis）。0 = 从没成功过。失败不写（同成绩/考试纪律）。 */
    suspend fun wenquActivityCheckMillis(): Long =
        context.displayDataStore.data.first()[KEY_WENQU_ACTIVITY_CHECK_MILLIS] ?: 0L

    suspend fun setWenquActivityCheckMillis(millis: Long) {
        context.displayDataStore.edit { it[KEY_WENQU_ACTIVITY_CHECK_MILLIS] = millis }
    }

    /**
     * 上次扫描见过的最大活动 id。**0 = 还没建基线**：首跑只把当前最大 id 记下来、不发提醒
     * （第一次抓到的全量是基线不是「新增」，见 [WenquRules.isNewRecruiting]）。
     */
    suspend fun wenquActivityLastSeenId(): Long =
        context.displayDataStore.data.first()[KEY_WENQU_ACTIVITY_LAST_SEEN_ID] ?: 0L

    suspend fun setWenquActivityLastSeenId(id: Long) {
        context.displayDataStore.edit { it[KEY_WENQU_ACTIVITY_LAST_SEEN_ID] = id }
    }

    /**
     * 盯着的活动**已经报过「名额空出」的那条 id**（2026-10-15）：同一条只提醒一次。
     * 重新盯一条 / 取消盯时清 0（见 `WenquGrabViewModel.pin` / `unpin`）。
     */
    suspend fun wenquGrabPinnedAlertedId(): Long =
        context.displayDataStore.data.first()[KEY_WENQU_GRAB_PINNED_ALERTED_ID] ?: 0L

    suspend fun setWenquGrabPinnedAlertedId(id: Long) {
        context.displayDataStore.edit { it[KEY_WENQU_GRAB_PINNED_ALERTED_ID] = id }
    }

    /**
     * 活动列表的状态筛选。**存 [WenquRules.FILTER_ALL_STORED] 表示「全部」**
     * （DataStore 没有可空 Int）——用户专门要过「隐藏已满」，每次进页都重置一遍会烦
     * （2026-10-08）。
     */
    val wenquStatusFilter: Flow<Int> = context.displayDataStore.data.map { p ->
        p[KEY_WENQU_STATUS_FILTER] ?: WenquRules.FILTER_ALL_STORED
    }

    suspend fun setWenquStatusFilter(value: Int) {
        context.displayDataStore.edit { p -> p[KEY_WENQU_STATUS_FILTER] = value }
    }

    /** 活动列表的「隐藏已满」开关，默认关（同页面默认值）。 */
    val wenquHideFull: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_WENQU_HIDE_FULL] ?: false
    }

    suspend fun setWenquHideFull(value: Boolean) {
        context.displayDataStore.edit { p -> p[KEY_WENQU_HIDE_FULL] = value }
    }

    /**
     * 「只看我报名的」（2026-10-11）。默认关——默认视图要能看见全部，
     * 用户想收窄时自己开（同「隐藏已满」的口径）。
     */
    val wenquAppliedOnly: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_WENQU_APPLIED_ONLY] ?: false
    }

    suspend fun setWenquAppliedOnly(value: Boolean) {
        context.displayDataStore.edit { p -> p[KEY_WENQU_APPLIED_ONLY] = value }
    }

    /**
     * 活动列表的排序档位（[WenquRules.Sort] 的 `name`）。默认「招募中优先」。
     * 存 **name 而不是序号**：以后往枚举中间插一档，老用户的设置不会错位。
     */
    val wenquSort: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_WENQU_SORT] ?: WenquRules.Sort.RecruitingFirst.name
    }

    suspend fun setWenquSort(value: String) {
        context.displayDataStore.edit { p -> p[KEY_WENQU_SORT] = value }
    }

    // ---- 问渠「每周日 12:00 抢活动」提醒（DESIGN §4.45，2026-10-11）----
    //
    // 与上面那条「检测到新活动就报」是两件事：那条是事后发现（周期扫列表），
    // 这条是**事前蹲点**（周日 12:00 前 N 分钟响一声），只有一条精确闹钟、不扫接口。

    /**
     * 每周提醒开关。**默认关**——通知是打扰型能力（同选课 / 调课 / 新增活动提醒口径）。
     */
    val wenquWeeklyAlertEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_WENQU_WEEKLY_ALERT_ENABLED] ?: false
    }

    suspend fun setWenquWeeklyAlertEnabled(value: Boolean) {
        context.displayDataStore.edit { p -> p[KEY_WENQU_WEEKLY_ALERT_ENABLED] = value }
    }

    /** 提前提醒量（分钟，1–10，默认 5）。 */
    val wenquWeeklyAlertOffsetMinutes: Flow<Int> = context.displayDataStore.data.map { p ->
        p[KEY_WENQU_WEEKLY_ALERT_OFFSET_MIN] ?: WenquRules.WEEKLY_ALERT_OFFSET_DEFAULT_MIN
    }

    suspend fun setWenquWeeklyAlertOffsetMinutes(value: Int) {
        context.displayDataStore.edit { p -> p[KEY_WENQU_WEEKLY_ALERT_OFFSET_MIN] = value }
    }

    /**
     * 上一次已提醒的**发布时刻**（周日 12:00，不是提醒时刻）——0 = 从没提醒过。
     *
     * 存发布时刻而不是提醒时刻：用户中途改提前量（5 分钟 → 10 分钟）时，
     * 同一周的发布键不变，不会因为改了设置又报一次。
     */
    suspend fun wenquWeeklyAlertLastFiredAt(): Long =
        context.displayDataStore.data.first()[KEY_WENQU_WEEKLY_ALERT_LAST_FIRED_AT] ?: 0L

    suspend fun setWenquWeeklyAlertLastFiredAt(publishAt: Long) {
        context.displayDataStore.edit { p -> p[KEY_WENQU_WEEKLY_ALERT_LAST_FIRED_AT] = publishAt }
    }

    /**
     * 蹲点页的目标活动名（DESIGN §4.45「蹲点模式」，2026-10-11）：预填用。
     * 空串 = 没预填（蹲点页只浏览列表，不弹检测）。**不上传**——匹配全在本地做，
     * 平台的 `keyword` 参数只有搜索兜底时才用。
     */
    val wenquGrabKeyword: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_WENQU_GRAB_KEYWORD] ?: ""
    }

    suspend fun setWenquGrabKeyword(value: String) {
        context.displayDataStore.edit { p -> p[KEY_WENQU_GRAB_KEYWORD] = value }
    }

    /**
     * 蹲点页**盯着的指定活动 id**（DESIGN §4.45「盯指定活动」，2026-10-15）：0 = 没盯。
     *
     * 与 [wenquGrabKeyword] 是两回事：名字匹配靠快照（深层条目的名额会冻住），
     * 盯一条具体的活动则**每轮单独拉它的详情**（实时名额），所以老活动也能盯。
     */
    val wenquGrabPinnedId: Flow<Long> = context.displayDataStore.data.map { p ->
        p[KEY_WENQU_GRAB_PINNED_ID] ?: 0L
    }

    suspend fun setWenquGrabPinnedId(value: Long) {
        context.displayDataStore.edit { p -> p[KEY_WENQU_GRAB_PINNED_ID] = value }
    }

    /**
     * 「预报名」瞄准的活动（DESIGN §4.45，2026-10-16；用户拍板）：id + 标题（展示用）+
     * 报名开始时刻（平台墙钟，epoch millis）。id = 0 = 没排。
     *
     * 与 [wenquGrabPinnedId] 是两回事：盯是「名额空出提醒」，预报名是「到点自动提交」。
     * 三个键一起写、一起清（[setWenquPrereg] / [clearWenquPrereg]），别只动一个。
     */
    val wenquPreregId: Flow<Long> = context.displayDataStore.data.map { p ->
        p[KEY_WENQU_PREREG_ID] ?: 0L
    }

    val wenquPreregTitle: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_WENQU_PREREG_TITLE].orEmpty()
    }

    val wenquPreregOpensAt: Flow<Long> = context.displayDataStore.data.map { p ->
        p[KEY_WENQU_PREREG_OPENS_AT] ?: 0L
    }

    suspend fun setWenquPrereg(id: Long, title: String, opensAt: Long) {
        context.displayDataStore.edit { p ->
            p[KEY_WENQU_PREREG_ID] = id
            p[KEY_WENQU_PREREG_TITLE] = title
            p[KEY_WENQU_PREREG_OPENS_AT] = opensAt
        }
    }

    suspend fun clearWenquPrereg() {
        context.displayDataStore.edit { p ->
            p.remove(KEY_WENQU_PREREG_ID)
            p.remove(KEY_WENQU_PREREG_TITLE)
            p.remove(KEY_WENQU_PREREG_OPENS_AT)
        }
    }

    /**
     * 教材的上次成功抓取日期（ISO）。空 = 从没成功过。
     * 教材跟着「导入课表」走（一学期一次），这里只做记录与展示，不做时间闸门。
     */
    val textbookSyncDate: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_TEXTBOOK_SYNC_DATE].orEmpty()
    }

    suspend fun setTextbookSyncDate(dateKey: String) {
        context.displayDataStore.edit { it[KEY_TEXTBOOK_SYNC_DATE] = dateKey }
    }

    /**
     * 教材冷启动补抓的上次**尝试**时刻（epoch millis，2026-10-06）。0 = 没试过。
     *
     * 与 [textbookSyncDate]（成功才写、只做记录）刻意不同：那道记录是「什么时候抓成功过」，
     * 这一道挡的是「该学期教材为空时别每次冷启动都打教务」——记尝试才挡得住。
     * 闸门判据在 `AutoSyncRules.textbookBackfillDue`。
     */
    suspend fun textbookBackfillAttempt(): Long =
        context.displayDataStore.data.first()[KEY_TEXTBOOK_BACKFILL_MILLIS] ?: 0L

    suspend fun setTextbookBackfillAttempt(millis: Long) {
        context.displayDataStore.edit { it[KEY_TEXTBOOK_BACKFILL_MILLIS] = millis }
    }

    /**
     * 教材**抓成功过**的学期号（2026-10-06 复核补）。空 = 没成功过。
     *
     * 为什么不能只看「该学期有没有教材」：下学期还没征订、或实习/毕设这类本来就没教材的
     * 学期，接口回的是**成功且 0 条**——那种情况教材表永远是空的，闸门就永远满足，
     * 变成每 6 小时打一次教务、永不停止。记下「抓过哪个学期」才收得住。
     * 换学期自动重新开闸（学期号不等即可再抓）。
     */
    suspend fun textbookFetchedTerm(): String =
        context.displayDataStore.data.first()[KEY_TEXTBOOK_FETCHED_TERM].orEmpty()

    suspend fun setTextbookFetchedTerm(term: String) {
        context.displayDataStore.edit { it[KEY_TEXTBOOK_FETCHED_TERM] = term }
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

    /** 共享单车免费时长提醒开关（DESIGN §3.9）。默认关：通知是打扰型能力，用户显式开启。 */
    val ebikeFreeReminderEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_FREE_REMINDER_ENABLED] ?: false
    }

    /**
     * 通知强提醒（DESIGN §3.3 通用设置 / §3.7）。全局项，**默认关**。
     *
     * 开 = 上课 / 作业截止 / 免费时长三类提醒发出时**直接震动**
     * （`ReminderVibration`，`Vibrator` 硬件调用不走通知渠道）——zen/免打扰的拦截面
     * 是「通知呈现」与铃声/通知类 usage，`USAGE_UNKNOWN` 的直接震动不在其中，
     * 所以能穿过免打扰与 HyperOS 的应用级通知策略；强打扰只能显式开启。
     * 关 = 提醒的震动只走渠道配置（可能被通知策略 / 免打扰 / 系统设置拦）。
     * **不进 [DisplayPrefs] 合并模型**——`baseDisplayPrefs` 的 combine 已满员
     * （AGENTS.md 口径），消费方各自收这条单流。
     */
    val strongReminderVibration: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_STRONG_REMINDER_VIBRATION] ?: false
    }

    // ---- 免扰时段（DESIGN §3.7，quiet hours）：两条独立单流，不进合并模型
    // （baseDisplayPrefs combine 已满员，口径同 [strongReminderVibration]）。

    /**
     * 免扰总开关，**默认开**：预置的夜间/午休时段是「睡觉别打扰」这类普遍作息，
     * 默认就该生效；不想要的用户显式关（成绩/考试变动提醒默认开的同一口径）。
     * 关 = 所有通知回到立即发送，时段配置保留不清。
     */
    val quietHoursEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_QUIET_HOURS_ENABLED] ?: true
    }

    /**
     * 免扰时段全表（含停用的窗口）。缺键 / 坏 JSON 回 [QuietWindows.DEFAULT]
     * ——预置时段「默认存在」，用户改过才落盘；存储损坏退预置比全放行安全。
     */
    val quietHoursWindows: Flow<List<QuietWindow>> = context.displayDataStore.data.map { p ->
        QuietWindows.decode(p[KEY_QUIET_HOURS_JSON])
    }

    // ---- 启动自动检查更新（DESIGN §3.3，2026-10-08）：开关 + 已忽略的版本号。
    // 独立单流（combine 已满员，口径同上）；消费方 = 冷启动检查链与关于页开关。

    /**
     * 启动自动检查更新开关，**默认开**：更新是用户要的事实、检查静默无打扰
     * （发现新版本才弹窗，且可「忽略此次更新」）；不想让 App 每次冷启动联网
     * 的用户显式关。关 = 冷启动检查链零网络空跑，手动「检查更新」不受影响。
     */
    val autoUpdateCheckEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_AUTO_UPDATE_CHECK_ENABLED] ?: true
    }

    /**
     * 「已忽略的版本号」（启动弹窗点「忽略此次更新」写入）。自动检查读到与清单
     * 版本相等就不再弹；出现更高版本恢复。**手动检查不读它**——关于页点
     * 「检查更新」永远能弹出。空 = 没有忽略。
     */
    suspend fun ignoredUpdateVersion(): String =
        context.displayDataStore.data.first()[KEY_IGNORED_UPDATE_VERSION].orEmpty()

    suspend fun setIgnoredUpdateVersion(value: String) {
        context.displayDataStore.edit { p -> p[KEY_IGNORED_UPDATE_VERSION] = value }
    }

    // ---- AI 调课（DESIGN §4.39，2026-10-06）：服务地址 / 模型名 / 脱敏 / 隐私确认。
    // API Key **不在 DataStore**——它是账号凭据，走 AiCredentialStore 加密存储。
    // 四条都单独成流，不进 baseDisplayPrefs 合并模型（combine 已满员）。

    /** 服务地址。默认 [AiScheduleConfig.DEFAULT_BASE_URL]；用户清空后读到空串，配置页要求重填。 */
    val aiBaseUrl: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_AI_BASE_URL] ?: AiScheduleConfig.DEFAULT_BASE_URL
    }

    /** 模型名。默认 [AiScheduleConfig.DEFAULT_MODEL]；可手输或从上游 `/models` 选。
     *  legacy 别名（`deepseek-v4-flash` 等已退役名）读取时迁到现行默认。 */
    val aiModel: Flow<String> = context.displayDataStore.data.map { p ->
        AiScheduleConfig.normalizeModel(p[KEY_AI_MODEL])
    }

    /** 教师名脱敏开关，默认**开**：发给第三方前把教师名换成「教师N」。 */
    val aiMaskTeacher: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_AI_MASK_TEACHER] ?: true
    }

    /** 首次隐私确认是否已完成（未确认前不允许发请求）。 */
    val aiPrivacyAcknowledged: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_AI_PRIVACY_ACK] ?: false
    }

    /** 思考档位（none / low / high / max）。默认 none：非思考模式才能强制命名工具调用。 */
    val aiThinkingEffort: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_AI_THINKING_EFFORT] ?: AiScheduleClient.Effort.NONE.wire
    }

    /** 是否在页面上显示模型的思考过程，默认关（思考文本长，默认收起）。 */
    val aiShowReasoning: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_AI_SHOW_REASONING] ?: false
    }

    /**
     * 该模型是否支持图片识别，**默认关**。
     *
     * 为什么不自动探测：模型名是用户手填的（DeepSeek 官方对话模型是否支持视觉没有实测过），
     * 上游 `/models` 只回 id 与名称、不给能力标记。所以只能显式声明，不自动开
     * （关键字预判 [AiScheduleConfig.looksVisionModel] 保留在 domain 供测试与未来复用，
     * 配置页的「看着像视觉模型」提示 2026-10-07 已随排版收口移除）。
     */
    val aiSupportsImage: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_AI_SUPPORTS_IMAGE] ?: false
    }

    /**
     * 教务 WebView 的「电脑版预览」开关（DESIGN §4.4，2026-10-08）。全局项，**默认开**。
     *
     * 开 = 教务业务页的 viewport 换成桌面宽度（[edu.jxslu.schedule.ui.jwvw.JwWebFit.DESKTOP_JS]，
     * 980px + 缩放到屏幕 + 双指可放大缩小）；关 = 维持页面自带的 `width=device-width`
     * （今天的挤压观感）。**登录 / SSO 页不受它影响**——那几页本来就没有 viewport meta，
     * 判据见 `JwWebFit.fitScript`。
     *
     * 默认开的理由：教务是桌面设计的系统，挤压是默认观感里的缺陷而不是选择；
     * 不想要的用户在窗口顶栏点一下就能切回（选择落盘，重开窗口照旧）。
     * 独立单流（`baseDisplayPrefs` 的 combine 已满员，口径同 [strongReminderVibration]）。
     */
    val jwDesktopMode: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_JW_DESKTOP_MODE] ?: true
    }

    /** 共享单车免费时长提醒提前量（分钟）。读路径吸附 1~5。 */
    val ebikeFreeLeadMinutes: Flow<Int> = context.displayDataStore.data.map { p ->
        EbikeFreeRide.coerceLead(p[KEY_EBIKE_FREE_LEAD] ?: EbikeFreeRide.DEFAULT_LEAD_MINUTES)
    }

    /** 本次骑行计时起点（epoch 毫秒）；0 = 无进行中计时。 */
    val ebikeRideStartAt: Flow<Long> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_RIDE_START_AT] ?: 0L
    }

    /**
     * 免费时长提醒的「已发键」集合（DESIGN §3.9）：键形如 `lead|<起点>` / `end|<起点>`，
     * 唯一实现在 [EbikeFreeRide.leadDedupKey] / [EbikeFreeRide.endDedupKey]。
     * 闹钟与周期核对共用，保证同一条提醒只发一次；换车重新计时时整集清空。
     */
    val ebikeFreeNotifiedKeys: Flow<Set<String>> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_FREE_NOTIFIED_KEYS] ?: emptySet()
    }

    /**
     * 骑行卡券到期提醒的「已提醒键」集合（DESIGN §4.32）：键形如 `card|<endTime 原文>`，
     * 唯一实现在 [EbikeCardExpiry.dedupKey]。同一张卡只提醒一次，换卡自然换键。
     */
    val ebikeCardExpiryNotifiedKeys: Flow<Set<String>> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_CARD_EXPIRY_NOTIFIED] ?: emptySet()
    }

    /**
     * 用车满 15 分钟自动还车开关（DESIGN §3.9，2026-10-10）。**默认关**：
     * 它会替用户执行有计费后果的写动作（自动还车），只能显式开启。
     */
    val ebikeAutoReturnEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_AUTO_RETURN_ENABLED] ?: false
    }

    /** 自动还车的触发提前量（秒，10~60 步长 5）。读路径吸附，防线脏数据。 */
    val ebikeAutoReturnLeadSeconds: Flow<Int> = context.displayDataStore.data.map { p ->
        EbikeAutoReturn.coerceLeadSeconds(
            p[KEY_EBIKE_AUTO_RETURN_LEAD_SECONDS] ?: EbikeAutoReturn.DEFAULT_LEAD_SECONDS,
        )
    }

    /** 自动还车的失败重试次数（1~3）。读路径吸附。 */
    val ebikeAutoReturnRetries: Flow<Int> = context.displayDataStore.data.map { p ->
        EbikeAutoReturn.coerceRetries(
            p[KEY_EBIKE_AUTO_RETURN_RETRIES] ?: EbikeAutoReturn.DEFAULT_RETRIES,
        )
    }

    /**
     * 自动还车的「已执行」键（唯一实现 [EbikeAutoReturn.doneKey]）。同一轮骑行只跑一次：
     * 成功、重试用尽、不该重试的失败都落这个键；换车（新起点）自然换键。
     */
    val ebikeAutoReturnDoneKey: Flow<String> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_AUTO_RETURN_DONE_KEY] ?: ""
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

    // ---- 余额提醒（DESIGN §3.10 / §3.13）----
    // 两个来源各一套「开关 + 阈值 + 上次成功检查日期」。不进 [DisplayPrefs] 合并视图：
    // 与上课/作业提醒同口径（走 repo 的独立流），也免得去动那个已满员的 combine。

    /** 寝室电费提醒开关。默认关：通知是打扰型能力，用户显式开启。 */
    val powerAlertEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_POWER_ALERT_ENABLED] ?: false
    }

    /** 寝室电费提醒阈值（元）。读路径吸附到候选档，防线脏数据。 */
    val powerAlertYuan: Flow<Int> = context.displayDataStore.data.map { p ->
        BalanceAlert.coercePowerYuan(p[KEY_POWER_ALERT_YUAN] ?: BalanceAlert.DEFAULT_POWER_YUAN)
    }

    /** 一卡通余额提醒开关。默认关。 */
    val yktAlertEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_YKT_ALERT_ENABLED] ?: false
    }

    /** 一卡通余额提醒阈值（元）。 */
    val yktAlertYuan: Flow<Int> = context.displayDataStore.data.map { p ->
        BalanceAlert.coerceYktYuan(p[KEY_YKT_ALERT_YUAN] ?: BalanceAlert.DEFAULT_YKT_YUAN)
    }

    /**
     * 检查间隔（小时）。**两个来源各自独立**（2026-10-07 用户拍板），读路径吸附到
     * 档位表（同阈值的防线口径）；余额链没有旧共用键的迁移史，默认值直取 [BalanceAlert]。
     */
    val powerAlertIntervalHours: Flow<Int> = context.displayDataStore.data.map { p ->
        BalanceAlert.coerceIntervalHours(
            p[KEY_POWER_ALERT_INTERVAL_HOURS] ?: BalanceAlert.INTERVAL_DEFAULT_HOURS,
        )
    }

    val yktAlertIntervalHours: Flow<Int> = context.displayDataStore.data.map { p ->
        BalanceAlert.coerceIntervalHours(
            p[KEY_YKT_ALERT_INTERVAL_HOURS] ?: BalanceAlert.INTERVAL_DEFAULT_HOURS,
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

    /** 今日页趣智校园卡片开关（DESIGN §4.30）。全局项，默认开。 */
    val qzxyCardEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_QZXY_CARD_ENABLED] ?: true
    }.distinctUntilChanged()

    /** 今日页共享单车卡开关（DESIGN §3.9）。全局项，默认开。 */
    val ebikeCardEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_CARD_ENABLED] ?: true
    }.distinctUntilChanged()

    /**
     * 今日页顶栏天气提示开关（DESIGN §3.29）。全局项，**默认关**：
     * 关着时一次网络请求都不发（取数调用点先判这个开关）。
     */
    val weatherEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_WEATHER_ENABLED] ?: false
    }.distinctUntilChanged()

    /**
     * 快趣出行的使用方式（DESIGN §3.9 / §4.32）。**默认 [EbikeUseMode.MiniProgram]**：
     * 账号登录方式要凭证、有计费后果，只能由用户显式选择。
     * 认不出的存储值一律退回默认档（口径在 [EbikeUseMode.fromId]）。
     */
    val ebikeUseMode: Flow<EbikeUseMode> = context.displayDataStore.data.map { p ->
        EbikeUseMode.fromId(p[KEY_EBIKE_USE_MODE])
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
     * 开锁前是否弹二次确认（DESIGN §3.9 / §4.32）。全局项，默认开；关掉后点「开锁」
     * 直接发指令。出口两个：开锁弹窗的「不再提醒」勾选、快趣出行设置 → 开锁与还车。
     */
    val ebikeUnlockConfirm: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_UNLOCK_CONFIRM] ?: true
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
     * U净 使用须知的最近确认时刻（DESIGN §4.37）。0 = 从未确认（首次进入要弹），
     * 距上次确认超过一周也会再弹一次（窗口口径在 `UjingViewModel`）。
     */
    val ujingNoticeSeenAt: Flow<Long> = context.displayDataStore.data.map { p ->
        p[KEY_UJING_NOTICE_SEEN_AT] ?: 0L
    }.distinctUntilChanged()

    /**
     * 附近单车地图「只看可用」筛选的记住开关（DESIGN §3.9）。
     *
     * 默认开（2026-09-24 用户拍板）：这个筛选本身就是想找能骑的车，列表与地图标记
     * 都按它过滤；关掉 = 看到离线与电量低的车。用户改过后落 DataStore，下次进页保持。
     */
    val ebikeMapOnlyAvailable: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_MAP_ONLY_AVAILABLE] ?: true
    }.distinctUntilChanged()

    /**
     * 附近单车地图「只看本校」筛选的记住开关（DESIGN §3.9）。
     *
     * 默认开（2026-09-27 用户拍板）：快趣出行同时服务隔壁江西师范大学，两校车辆
     * 坐标只隔一条马路，不筛的话师大校园的车会成片出现在地图上。
     * 筛选口径（车队归属 + 地理围栏双条件）在 [edu.jxslu.schedule.domain.BikeNearby.isOurCampusBike]。
     */
    val ebikeMapOnlyOurCampus: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_EBIKE_MAP_ONLY_OUR_CAMPUS] ?: true
    }.distinctUntilChanged()

    /**
     * 骑行页底部面板高度（dp，DESIGN §3.9）；null = 没拖过，用默认值。
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

    /**
     * 菜鸟批量查的最近提交（DESIGN §4.47）。键缺失/脏 JSON 回空列表——
     * 历史只是回填入口，坏了不该打扰页面。
     */
    val cainiaoQueryHistory: Flow<List<String>> = context.displayDataStore.data.map { p ->
        CainiaoQuery.decodeHistory(p[KEY_CAINIAO_QUERY_HISTORY])
    }.distinctUntilChanged()

    /** 今日页校园卡付款码卡开关（DESIGN §3.10）。全局项，**默认关**（涉及凭证与资金）。 */
    val campusCardEnabled: Flow<Boolean> = context.displayDataStore.data.map { p ->
        p[KEY_CAMPUS_CARD_ENABLED] ?: false
    }.distinctUntilChanged()

    /**
     * 充值免责声明的静默截止时刻（epoch 毫秒，DESIGN §3.13 / §4.19）。0 = 从没勾过。
     *
     * 勾选「一周内不再提醒」才写；不勾不写（过期值留着无害，[edu.jxslu.schedule.domain.RechargeDisclaimer.isSuppressed]
     * 判 false 照样弹）。电费与一卡通充值共用这一个键。
     */
    val rechargeDisclaimerSuppressUntil: Flow<Long> = context.displayDataStore.data.map { p ->
        p[KEY_RECHARGE_DISCLAIMER_SUPPRESS_UNTIL] ?: 0L
    }.distinctUntilChanged()

    suspend fun setRechargeDisclaimerSuppressUntil(value: Long) {
        context.displayDataStore.edit { it[KEY_RECHARGE_DISCLAIMER_SUPPRESS_UNTIL] = value }
    }

    /**
     * 充值免责声明的「已确认」时刻（epoch 毫秒）。0 = 从未确认过——首次弹出要锁
     * 5 秒才能关（`domain/RechargeDisclaimer.closeLockMs`），确认过（点「继续充值」
     * 或「取消」都算）之后任何一次弹出都立即可关。与静默键分开：确认≠勾选静默。
     */
    val rechargeDisclaimerSeenAt: Flow<Long> = context.displayDataStore.data.map { p ->
        p[KEY_RECHARGE_DISCLAIMER_SEEN_AT] ?: 0L
    }.distinctUntilChanged()

    suspend fun markRechargeDisclaimerSeen(atMs: Long = System.currentTimeMillis()) {
        context.displayDataStore.edit { it[KEY_RECHARGE_DISCLAIMER_SEEN_AT] = atMs }
    }

    /**
     * 首启声明的同意记录（DESIGN §3.16）：读某一份须知的「同意过哪一版 + 什么时候」。
     *
     * 读路径两处：首启引导决定还要不要弹（`domain/NoticeConsent.pendingNotices`），
     * 以及「我的 → 关于」显示"已确认/未确认"。写路径只有 [markNoticeConsented]。
     */
    fun noticeConsent(notice: FirstRunNotice): Flow<NoticeConsentRecord> =
        context.displayDataStore.data.map { p ->
            when (notice) {
                FirstRunNotice.UserNotice -> NoticeConsentRecord(
                    version = p[KEY_USER_NOTICE_VERSION] ?: 0,
                    atMs = p[KEY_USER_NOTICE_SEEN_AT] ?: 0L,
                )

                FirstRunNotice.Disclaimer -> NoticeConsentRecord(
                    version = p[KEY_DISCLAIMER_VERSION] ?: 0,
                    atMs = p[KEY_DISCLAIMER_SEEN_AT] ?: 0L,
                )
            }
        }.distinctUntilChanged()

    /**
     * 记下「已看过并确认」这一份须知。[version] 用当前的
     * `domain/FirstRunNotices.VERSION`——落的是**用户看到的那一版**，不是写死的最新版。
     *
     * 版本号与时刻一次 edit 写入：只写一个的话，"同意过 v1 但不知何时"与
     * "何时同意但不知哪版"都不足以支撑事后核对。
     */
    suspend fun markNoticeConsented(
        notice: FirstRunNotice,
        version: Int,
        atMs: Long = System.currentTimeMillis(),
    ) {
        context.displayDataStore.edit { p ->
            when (notice) {
                FirstRunNotice.UserNotice -> {
                    p[KEY_USER_NOTICE_VERSION] = version
                    p[KEY_USER_NOTICE_SEEN_AT] = atMs
                }

                FirstRunNotice.Disclaimer -> {
                    p[KEY_DISCLAIMER_VERSION] = version
                    p[KEY_DISCLAIMER_SEEN_AT] = atMs
                }
            }
        }
    }

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
                targetAccount = p[KEY_PENDING_RECHARGE_TARGET],
                walletBalanceBeforeFen = p[KEY_PENDING_RECHARGE_WALLET_BASE_FEN],
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
        /** 充值目标：`<account>-000` = 电子账户；null = 正式卡。 */
        targetAccount: String?,
        /** 充电子账户时：付款前目标钱包余额（分）；取不到传 null（退流水口径）。 */
        walletBalanceBeforeFen: Long?,
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
            if (targetAccount != null) {
                it[KEY_PENDING_RECHARGE_TARGET] = targetAccount
            } else {
                it.remove(KEY_PENDING_RECHARGE_TARGET)
            }
            if (walletBalanceBeforeFen != null) {
                it[KEY_PENDING_RECHARGE_WALLET_BASE_FEN] = walletBalanceBeforeFen
            } else {
                it.remove(KEY_PENDING_RECHARGE_WALLET_BASE_FEN)
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
            it.remove(KEY_PENDING_RECHARGE_TARGET)
            it.remove(KEY_PENDING_RECHARGE_WALLET_BASE_FEN)
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

    suspend fun setFontScaleLocked(value: Boolean) {
        context.displayDataStore.edit { it[KEY_FONT_SCALE_LOCKED] = value }
    }

    suspend fun setDynamicColor(value: Boolean) {
        context.displayDataStore.edit { it[KEY_DYNAMIC_COLOR] = value }
    }

    suspend fun setThemePalette(value: ThemePalette) {
        context.displayDataStore.edit { it[KEY_THEME_PALETTE] = value.id }
    }

    suspend fun setFloatingNavBar(value: Boolean) {
        context.displayDataStore.edit { it[KEY_FLOATING_NAV_BAR] = value }
    }

    suspend fun setToolboxEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_TOOLBOX_ENABLED] = value }
    }

    /** 应用内悬浮球开关（DESIGN §3.1）。 */
    suspend fun setFloatingBallEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_FLOATING_BALL_ENABLED] = value }
    }

    /** 悬浮球位置（DESIGN §3.1）。写入前夹取到 [0,1]。 */
    suspend fun setFloatingBallPosition(position: FloatingBall.Position) {
        val clamped = FloatingBall.clamp(position)
        context.displayDataStore.edit {
            it[KEY_FLOATING_BALL_X] = clamped.x
            it[KEY_FLOATING_BALL_Y] = clamped.y
        }
    }

    /** 启动页（DESIGN §3.3）。写的是用户的选择本身，不因为生活页此刻关着就改写它。 */
    suspend fun setStartPage(value: StartPage) {
        context.displayDataStore.edit { it[KEY_START_PAGE] = value.name }
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

    /** 共享单车免费时长提醒开关（DESIGN §3.9）。默认关：通知是打扰型能力，用户显式开启。 */
    suspend fun setEbikeFreeReminderEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_EBIKE_FREE_REMINDER_ENABLED] = value }
    }

    /** 通知强提醒开关（DESIGN §3.3 通用设置；语义见 [strongReminderVibration]）。 */
    suspend fun setStrongReminderVibration(value: Boolean) {
        context.displayDataStore.edit { it[KEY_STRONG_REMINDER_VIBRATION] = value }
    }

    /** 免扰总开关（语义见 [quietHoursEnabled]）。 */
    suspend fun setQuietHoursEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_QUIET_HOURS_ENABLED] = value }
    }

    /** 免扰时段全表（语义见 [quietHoursWindows]）。 */
    suspend fun setQuietHoursWindows(windows: List<QuietWindow>) {
        context.displayDataStore.edit { it[KEY_QUIET_HOURS_JSON] = QuietWindows.encode(windows) }
    }

    /** 启动自动检查更新开关（语义见 [autoUpdateCheckEnabled]）。 */
    suspend fun setAutoUpdateCheckEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_AUTO_UPDATE_CHECK_ENABLED] = value }
    }

    /** 教务 WebView 电脑版预览开关（语义见 [jwDesktopMode]）。 */
    suspend fun setJwDesktopMode(value: Boolean) {
        context.displayDataStore.edit { it[KEY_JW_DESKTOP_MODE] = value }
    }

    /** AI 调课服务地址（DESIGN §4.39）。存 trim 后的原文，允许空串（= 用户清空待重填）。 */
    suspend fun setAiBaseUrl(value: String) {
        context.displayDataStore.edit { it[KEY_AI_BASE_URL] = value.trim() }
    }

    /** AI 调课模型名（DESIGN §4.39）。 */
    suspend fun setAiModel(value: String) {
        context.displayDataStore.edit { it[KEY_AI_MODEL] = value.trim() }
    }

    /** AI 调课教师名脱敏开关（DESIGN §4.39）。 */
    suspend fun setAiMaskTeacher(value: Boolean) {
        context.displayDataStore.edit { it[KEY_AI_MASK_TEACHER] = value }
    }

    /** 首次隐私确认（DESIGN §4.39）。确认后不再弹。 */
    suspend fun setAiPrivacyAcknowledged(value: Boolean) {
        context.displayDataStore.edit { it[KEY_AI_PRIVACY_ACK] = value }
    }

    /** AI 调课思考档位（DESIGN §4.39）。传 [AiScheduleClient.Effort.wire]。 */
    suspend fun setAiThinkingEffort(value: String) {
        context.displayDataStore.edit {
            it[KEY_AI_THINKING_EFFORT] = AiScheduleClient.Effort.fromWire(value).wire
        }
    }

    /** 是否显示思考过程（DESIGN §4.39）。 */
    suspend fun setAiShowReasoning(value: Boolean) {
        context.displayDataStore.edit { it[KEY_AI_SHOW_REASONING] = value }
    }

    /** 该模型是否支持图片识别（DESIGN §4.39）。见 [aiSupportsImage] 的口径。 */
    suspend fun setAiSupportsImage(value: Boolean) {
        context.displayDataStore.edit { it[KEY_AI_SUPPORTS_IMAGE] = value }
    }

    /** 共享单车免费时长提醒提前量（分钟，1~5）。 */
    suspend fun setEbikeFreeLeadMinutes(value: Int) {
        context.displayDataStore.edit {
            it[KEY_EBIKE_FREE_LEAD] = EbikeFreeRide.coerceLead(value)
        }
    }

    /**
     * 本次骑行计时起点（epoch 毫秒）：点「打开微信扫一扫」写**点击 + [EbikeFreeRide.SCAN_DELAY_MS]**
     * （缓冲期内倒计时满格不走，2026-10-05 用户拍板）；本机开锁成功写开锁时刻。
     * 0 = 无进行中计时。**持久化**——进程被杀重启后状态条与兜底核对都靠它。
     */
    suspend fun setEbikeRideStartAt(value: Long) {
        context.displayDataStore.edit { it[KEY_EBIKE_RIDE_START_AT] = value }
    }

    /** 用车满 15 分钟自动还车开关（DESIGN §3.9；语义见 [ebikeAutoReturnEnabled]）。 */
    suspend fun setEbikeAutoReturnEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_EBIKE_AUTO_RETURN_ENABLED] = value }
    }

    /** 自动还车触发提前量（秒，10~60 步长 5；语义见 [ebikeAutoReturnLeadSeconds]）。 */
    suspend fun setEbikeAutoReturnLeadSeconds(value: Int) {
        context.displayDataStore.edit {
            it[KEY_EBIKE_AUTO_RETURN_LEAD_SECONDS] = EbikeAutoReturn.coerceLeadSeconds(value)
        }
    }

    /** 自动还车失败重试次数（1~3；语义见 [ebikeAutoReturnRetries]）。 */
    suspend fun setEbikeAutoReturnRetries(value: Int) {
        context.displayDataStore.edit {
            it[KEY_EBIKE_AUTO_RETURN_RETRIES] = EbikeAutoReturn.coerceRetries(value)
        }
    }

    /** 记下自动还车已对某轮骑行执行过（键由 [EbikeAutoReturn.doneKey] 生成）。 */
    suspend fun setEbikeAutoReturnDoneKey(value: String) {
        context.displayDataStore.edit { it[KEY_EBIKE_AUTO_RETURN_DONE_KEY] = value }
    }

    /**
     * 免费时长提醒已发键的统一写入口（DESIGN §3.9）：读-改-写整个集合，同值跳写
     * （口径同 [updateEbikePendingDelete]）。清空用 `{ emptySet() }`。
     */
    suspend fun updateEbikeFreeNotifiedKeys(transform: (Set<String>) -> Set<String>) {
        context.displayDataStore.edit { p ->
            val current = p[KEY_EBIKE_FREE_NOTIFIED_KEYS] ?: emptySet()
            val next = transform(current)
            if (next != current) {
                if (next.isEmpty()) {
                    p.remove(KEY_EBIKE_FREE_NOTIFIED_KEYS)
                } else {
                    p[KEY_EBIKE_FREE_NOTIFIED_KEYS] = next
                }
            }
        }
    }

    /**
     * 骑行卡券到期提醒已提醒键的统一写入口（DESIGN §4.32）：读-改-写整个集合，同值跳写
     * （口径同 [updateEbikeFreeNotifiedKeys]）。
     */
    suspend fun updateEbikeCardExpiryNotifiedKeys(transform: (Set<String>) -> Set<String>) {
        context.displayDataStore.edit { p ->
            val current = p[KEY_EBIKE_CARD_EXPIRY_NOTIFIED] ?: emptySet()
            val next = transform(current)
            if (next != current) {
                if (next.isEmpty()) {
                    p.remove(KEY_EBIKE_CARD_EXPIRY_NOTIFIED)
                } else {
                    p[KEY_EBIKE_CARD_EXPIRY_NOTIFIED] = next
                }
            }
        }
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

    /** 今日页趣智校园卡片开关（DESIGN §4.30）。 */
    suspend fun setQzxyCardEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_QZXY_CARD_ENABLED] = value }
    }

    /** 今日页共享单车卡开关（DESIGN §3.9）。 */
    suspend fun setEbikeCardEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_EBIKE_CARD_ENABLED] = value }
    }

    suspend fun setWeatherEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_WEATHER_ENABLED] = value }
    }

    /**
     * 快趣出行的使用方式（DESIGN §3.9 / §4.32）。写的是枚举的存储 id，
     * 读路径用 [EbikeUseMode.fromId] 还原（认不出一律回默认档）。
     */
    suspend fun setEbikeUseMode(value: EbikeUseMode) {
        context.displayDataStore.edit { it[KEY_EBIKE_USE_MODE] = value.id }
    }

    /** 今日页校园卡付款码卡开关（DESIGN §3.10）。 */
    suspend fun setCampusCardEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_CAMPUS_CARD_ENABLED] = value }
    }

    /** 寝室电费提醒开关（DESIGN §3.13）。 */
    suspend fun setPowerAlertEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_POWER_ALERT_ENABLED] = value }
    }

    /** 寝室电费提醒阈值（元）；夹取到候选表值域。 */
    suspend fun setPowerAlertYuan(value: Int) {
        context.displayDataStore.edit {
            it[KEY_POWER_ALERT_YUAN] = BalanceAlert.coercePowerYuan(value)
        }
    }

    /** 一卡通余额提醒开关（DESIGN §3.10）。 */
    suspend fun setYktAlertEnabled(value: Boolean) {
        context.displayDataStore.edit { it[KEY_YKT_ALERT_ENABLED] = value }
    }

    /** 一卡通余额提醒阈值（元）；夹取到候选表值域。 */
    suspend fun setYktAlertYuan(value: Int) {
        context.displayDataStore.edit {
            it[KEY_YKT_ALERT_YUAN] = BalanceAlert.coerceYktYuan(value)
        }
    }

    /**
     * 余额提醒的「上次**成功**检查时刻」（epoch millis，DESIGN §3.10 / §3.13）：
     * 0 = 从未成功过。取数失败**不落时刻**，所以闸门过期前还有补查机会。
     *
     * 它是小时级间隔的闸门（判据 `AutoSyncRules.shouldAttemptAt`，同成绩/考试链）。
     * 读取回退：millis 键没写过（2026-10-07 前的老用户）时看旧日期键——旧日期是
     * **今天** 就按今天零点算（升级当轮的检查频率仍受间隔闸门约束），否则按没查过；
     * 只读不写，下次成功检查自然落新键。旧键无一次性迁移、不再写入。
     */
    suspend fun balanceAlertCheckMillis(source: BalanceAlertSource): Long {
        val snapshot = context.displayDataStore.data.first()
        val millis = snapshot[balanceAlertCheckMillisKey(source)] ?: 0L
        if (millis > 0L) return millis
        val legacyDate = snapshot[alertLastCheckKey(source)] ?: return 0L
        return if (legacyDate == LocalDate.now().toString()) {
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } else 0L
    }

    suspend fun setBalanceAlertCheckMillis(source: BalanceAlertSource, millis: Long) {
        context.displayDataStore.edit { it[balanceAlertCheckMillisKey(source)] = millis }
    }

    /** 清掉某来源的检查时刻（开关 / 阈值 / 间隔变更时调用）：新规则立即重新评估一次。 */
    suspend fun clearBalanceAlertCheckMillis(source: BalanceAlertSource) {
        context.displayDataStore.edit { it.remove(balanceAlertCheckMillisKey(source)) }
    }

    /**
     * 「偏低已提醒、尚未回升」标记（2026-10-07 加，按来源分键）：余额持续偏低期间
     * 只提醒一次（同日多次核对不重复打扰），回升时随收件箱状态消解一并清除，
     * 再次下降才会重新提醒。改设置（开关 / 阈值 / 间隔）也会清——改了规则就该重新评估。
     */
    suspend fun balanceAlertNotified(source: BalanceAlertSource): Boolean =
        context.displayDataStore.data.first()[balanceAlertNotifiedKey(source)] ?: false

    suspend fun setBalanceAlertNotified(source: BalanceAlertSource, value: Boolean) {
        context.displayDataStore.edit { it[balanceAlertNotifiedKey(source)] = value }
    }

    suspend fun clearBalanceAlertNotified(source: BalanceAlertSource) {
        context.displayDataStore.edit { it.remove(balanceAlertNotifiedKey(source)) }
    }

    // ---- 余额提醒检查间隔（小时，两链各自独立，2026-10-07）----

    suspend fun setPowerAlertIntervalHours(value: Int) {
        context.displayDataStore.edit {
            it[KEY_POWER_ALERT_INTERVAL_HOURS] = BalanceAlert.coerceIntervalHours(value)
        }
    }

    suspend fun setYktAlertIntervalHours(value: Int) {
        context.displayDataStore.edit {
            it[KEY_YKT_ALERT_INTERVAL_HOURS] = BalanceAlert.coerceIntervalHours(value)
        }
    }

    /**
     * 已排的周期核对档位（小时，[edu.jxslu.schedule.ui.reminder.BalanceAlertReminder] 用）。
     * 0 = 当前没有在排的周期任务。用途同 `alertPeriodicInterval`：WorkManager 2.7 没有
     * UPDATE 策略，只在「间隔档位变了」时 REPLACE，否则 KEEP 保相位。
     */
    suspend fun balancePeriodicInterval(): Int =
        context.displayDataStore.data.first()[KEY_BALANCE_PERIODIC_INTERVAL] ?: 0

    suspend fun setBalancePeriodicInterval(value: Int) {
        context.displayDataStore.edit { it[KEY_BALANCE_PERIODIC_INTERVAL] = value }
    }

    private fun balanceAlertCheckMillisKey(source: BalanceAlertSource) = when (source) {
        BalanceAlertSource.Power -> KEY_POWER_ALERT_CHECK_MILLIS
        BalanceAlertSource.Ykt -> KEY_YKT_ALERT_CHECK_MILLIS
    }

    private fun balanceAlertNotifiedKey(source: BalanceAlertSource) = when (source) {
        BalanceAlertSource.Power -> KEY_POWER_ALERT_NOTIFIED
        BalanceAlertSource.Ykt -> KEY_YKT_ALERT_NOTIFIED
    }

    private fun alertLastCheckKey(source: BalanceAlertSource) = when (source) {
        BalanceAlertSource.Power -> KEY_POWER_ALERT_LAST_CHECK
        BalanceAlertSource.Ykt -> KEY_YKT_ALERT_LAST_CHECK
    }

    /** 共享单车出码自动存相册开关（DESIGN §3.9）。 */
    suspend fun setEbikeAutoSave(value: Boolean) {
        context.displayDataStore.edit { it[KEY_EBIKE_AUTO_SAVE] = value }
    }

    /** 扫完即焚开关（DESIGN §3.9）。 */
    suspend fun setEbikeBurnAfterScan(value: Boolean) {
        context.displayDataStore.edit { it[KEY_EBIKE_BURN_AFTER_SCAN] = value }
    }

    /** 开锁前是否弹二次确认（DESIGN §3.9 / §4.32）。 */
    suspend fun setEbikeUnlockConfirm(value: Boolean) {
        context.displayDataStore.edit { it[KEY_EBIKE_UNLOCK_CONFIRM] = value }
    }

    /** 标记「已自动申请过定位权限」（DESIGN §3.9），见 [ebikeLocationAsked]。 */
    suspend fun setEbikeLocationAsked(value: Boolean) {
        context.displayDataStore.edit { it[KEY_EBIKE_LOCATION_ASKED] = value }
    }

    /** 记录「已读 U净 使用须知」的时刻（DESIGN §4.37），见 [ujingNoticeSeenAt]。 */
    suspend fun setUjingNoticeSeenAt(millis: Long) {
        context.displayDataStore.edit { it[KEY_UJING_NOTICE_SEEN_AT] = millis }
    }

    /** 记住附近单车地图「只看可用」筛选（DESIGN §3.9），见 [ebikeMapOnlyAvailable]。 */
    suspend fun setEbikeMapOnlyAvailable(value: Boolean) {
        context.displayDataStore.edit { it[KEY_EBIKE_MAP_ONLY_AVAILABLE] = value }
    }

    /** 记住附近单车地图「只看本校」筛选（DESIGN §3.9），见 [ebikeMapOnlyOurCampus]。 */
    suspend fun setEbikeMapOnlyOurCampus(value: Boolean) {
        context.displayDataStore.edit { it[KEY_EBIKE_MAP_ONLY_OUR_CAMPUS] = value }
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

    /** 记住骑行页底部面板高度（DESIGN §3.9），见 [ebikePanelHeightDp]。 */
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

    /** 菜鸟批量查历史统一写入口（DESIGN §4.47）：读-改-写整个 JSON，同值跳写。 */
    suspend fun updateCainiaoQueryHistory(transform: (List<String>) -> List<String>) {
        context.displayDataStore.edit { p ->
            val current = CainiaoQuery.decodeHistory(p[KEY_CAINIAO_QUERY_HISTORY])
            val next = transform(current)
            if (next != current) p[KEY_CAINIAO_QUERY_HISTORY] = CainiaoQuery.encodeHistory(next)
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
        /** 字体缩放锁定（「固定字体大小」）：缺省 = true（锁定）。 */
        val KEY_FONT_SCALE_LOCKED = booleanPreferencesKey("font_scale_locked")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
        val KEY_THEME_PALETTE = stringPreferencesKey("theme_palette")
        val KEY_FLOATING_NAV_BAR = booleanPreferencesKey("floating_nav_bar")
        /** 百宝箱（DESIGN §4.47）：缺省 = true（今日页「+」里显示图片工具入口）。 */
        val KEY_TOOLBOX_ENABLED = booleanPreferencesKey("toolbox_enabled")
        // 应用内悬浮球（DESIGN §3.1）：开关 + 归一化位置（缺省 = 右下角，见 floatingBallPosition）
        val KEY_FLOATING_BALL_ENABLED = booleanPreferencesKey("floating_ball_enabled")
        val KEY_FLOATING_BALL_X = floatPreferencesKey("floating_ball_x")
        val KEY_FLOATING_BALL_Y = floatPreferencesKey("floating_ball_y")
        val KEY_START_PAGE = stringPreferencesKey("start_page")
        val KEY_WATER_REQUIRE_DOUBLE_CLICK = booleanPreferencesKey("water_require_double_click")
        val KEY_CALENDAR_REMINDER_MINUTES = intPreferencesKey("calendar_reminder_minutes")
        val KEY_REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val KEY_REMINDER_LEAD = intPreferencesKey("reminder_lead_minutes")
        val KEY_REMINDER_LAST = stringPreferencesKey("reminder_last_key")
        val KEY_HOMEWORK_REMINDER_ENABLED = booleanPreferencesKey("homework_reminder_enabled")
        val KEY_HOMEWORK_REMINDED_KEYS = stringSetPreferencesKey("homework_reminded_keys")
        val KEY_EBIKE_FREE_REMINDER_ENABLED = booleanPreferencesKey("ebike_free_reminder_enabled")
        val KEY_STRONG_REMINDER_VIBRATION = booleanPreferencesKey("strong_reminder_vibration")
        val KEY_QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        val KEY_QUIET_HOURS_JSON = stringPreferencesKey("quiet_hours_json")
        val KEY_AUTO_UPDATE_CHECK_ENABLED = booleanPreferencesKey("auto_update_check_enabled")
        val KEY_IGNORED_UPDATE_VERSION = stringPreferencesKey("ignored_update_version")
        /** 教务 WebView 电脑版预览（DESIGN §4.4；语义见 [jwDesktopMode]）。 */
        val KEY_JW_DESKTOP_MODE = booleanPreferencesKey("jw_desktop_mode")
        val KEY_EBIKE_FREE_LEAD = intPreferencesKey("ebike_free_lead_minutes")
        val KEY_EBIKE_RIDE_START_AT = longPreferencesKey("ebike_ride_start_at")
        // 用车满 15 分钟自动还车（DESIGN §3.9，2026-10-10）：开关 / 触发提前量（秒）/
        // 失败重试次数 / 「已执行」键（同一轮骑行只跑一次，见 EbikeAutoReturn.doneKey）。
        val KEY_EBIKE_AUTO_RETURN_ENABLED = booleanPreferencesKey("ebike_auto_return_enabled")
        val KEY_EBIKE_AUTO_RETURN_LEAD_SECONDS = intPreferencesKey("ebike_auto_return_lead_seconds")
        val KEY_EBIKE_AUTO_RETURN_RETRIES = intPreferencesKey("ebike_auto_return_retries")
        val KEY_EBIKE_AUTO_RETURN_DONE_KEY = stringPreferencesKey("ebike_auto_return_done_key")

        // 2026-09-24 提醒改回 App 通知：日历事件 id（ebike_free_event_id）随之停用，
        // 键定义一并删掉——旧设备上的残留值没人读，也不会自己消失，但无害。
        val KEY_EBIKE_FREE_NOTIFIED_KEYS = stringSetPreferencesKey("ebike_free_notified_keys")

        /** 骑行卡券到期提醒的「已提醒键」集合（DESIGN §4.32，键见 [EbikeCardExpiry.dedupKey]）。 */
        val KEY_EBIKE_CARD_EXPIRY_NOTIFIED = stringSetPreferencesKey("ebike_card_expiry_notified")

        // 「精确倒计时」已删（2026-09-30）：开关与校准标记两键
        // （ebike_precise_countdown_enabled / ebike_precise_calibrated_at）随之停用，
        // 同上面日历事件 id 的口径——旧设备上的残留值没人读，无害。
        val KEY_CURRENT_TIMETABLE = longPreferencesKey("current_timetable_id")
        val KEY_DEFAULT_CONFIG_SOURCE = longPreferencesKey("default_config_source_id")
        val KEY_SLOT_SCHEMA = intPreferencesKey("slot_schema_version")
        val KEY_PREFS_MIGRATED = booleanPreferencesKey("timetable_prefs_migrated")
        val KEY_WIDGET_SETUP_SEEN = booleanPreferencesKey("widget_setup_seen")
        val KEY_SHORTCUTS_ENABLED = booleanPreferencesKey("shortcuts_enabled")
        val KEY_WATER_CARD_ENABLED = booleanPreferencesKey("water_card_enabled")
        val KEY_QZXY_CARD_ENABLED = booleanPreferencesKey("qzxy_card_enabled")
        val KEY_EBIKE_CARD_ENABLED = booleanPreferencesKey("ebike_card_enabled")
        val KEY_WEATHER_ENABLED = booleanPreferencesKey("weather_enabled")
        val KEY_EBIKE_USE_MODE = stringPreferencesKey("ebike_use_mode")
        val KEY_EBIKE_AUTO_SAVE = booleanPreferencesKey("ebike_auto_save")
        val KEY_EBIKE_BURN_AFTER_SCAN = booleanPreferencesKey("ebike_burn_after_scan")
        val KEY_EBIKE_UNLOCK_CONFIRM = booleanPreferencesKey("ebike_unlock_confirm")
        val KEY_EBIKE_LOCATION_ASKED = booleanPreferencesKey("ebike_location_asked")
        val KEY_UJING_NOTICE_SEEN_AT = longPreferencesKey("ujing_notice_seen_at")
        val KEY_EBIKE_MAP_ONLY_AVAILABLE = booleanPreferencesKey("ebike_map_only_available")
        val KEY_EBIKE_MAP_ONLY_OUR_CAMPUS = booleanPreferencesKey("ebike_map_only_our_campus")
        val KEY_EBIKE_VIEW_LAT = floatPreferencesKey("ebike_view_lat")
        val KEY_EBIKE_VIEW_LNG = floatPreferencesKey("ebike_view_lng")
        val KEY_EBIKE_VIEW_ZOOM = floatPreferencesKey("ebike_view_zoom")
        val KEY_EBIKE_PANEL_HEIGHT_DP = floatPreferencesKey("ebike_panel_height_dp")
        /** 保存视野时的缩放范围，与瓦片源的 1~19 对齐。 */
        const val MIN_MAP_ZOOM = 1.0f
        const val MAX_MAP_ZOOM = 19.0f
        val KEY_EBIKE_PENDING_DELETE = stringSetPreferencesKey("ebike_pending_delete")
        val KEY_EBIKE_RECENT_IDS = stringPreferencesKey("ebike_recent_ids")
        /** 菜鸟批量查的最近提交（DESIGN §4.47）：JSON 数组，缺省 = 无历史。 */
        val KEY_CAINIAO_QUERY_HISTORY = stringPreferencesKey("cainiao_query_history")
        val KEY_CAMPUS_CARD_ENABLED = booleanPreferencesKey("campus_card_enabled")
        // 余额提醒（DESIGN §3.10 / §3.13）：两个来源各一套开关 / 阈值 / 检查间隔 /
        // 上次成功检查时刻 / 偏低已提醒标记。*_LAST_CHECK 是 2026-10-07 前的日期级旧键，
        // 只作 millis 键缺省时的读取回退，不再写入（无一次性迁移）。
        val KEY_POWER_ALERT_ENABLED = booleanPreferencesKey("power_alert_enabled")
        val KEY_POWER_ALERT_YUAN = intPreferencesKey("power_alert_yuan")
        val KEY_POWER_ALERT_LAST_CHECK = stringPreferencesKey("power_alert_last_check")
        val KEY_YKT_ALERT_ENABLED = booleanPreferencesKey("ykt_alert_enabled")
        val KEY_YKT_ALERT_YUAN = intPreferencesKey("ykt_alert_yuan")
        val KEY_YKT_ALERT_LAST_CHECK = stringPreferencesKey("ykt_alert_last_check")
        // 检查间隔（小时，两链各自独立）+ 已排周期档（0 = 没在排）+ 闸门与提醒标记
        val KEY_POWER_ALERT_INTERVAL_HOURS = intPreferencesKey("power_alert_interval_hours")
        val KEY_YKT_ALERT_INTERVAL_HOURS = intPreferencesKey("ykt_alert_interval_hours")
        val KEY_POWER_ALERT_CHECK_MILLIS = longPreferencesKey("power_alert_check_millis")
        val KEY_YKT_ALERT_CHECK_MILLIS = longPreferencesKey("ykt_alert_check_millis")
        val KEY_POWER_ALERT_NOTIFIED = booleanPreferencesKey("power_alert_notified")
        val KEY_YKT_ALERT_NOTIFIED = booleanPreferencesKey("ykt_alert_notified")
        val KEY_BALANCE_PERIODIC_INTERVAL = intPreferencesKey("balance_periodic_interval")
        val KEY_RECHARGE_DISCLAIMER_SUPPRESS_UNTIL = longPreferencesKey("recharge_disclaimer_suppress_until")
        val KEY_RECHARGE_DISCLAIMER_SEEN_AT = longPreferencesKey("recharge_disclaimer_seen_at")
        // 首启声明的同意记录（DESIGN §3.16）：每份须知各一对「同意过的版本 / 确认时刻」。
        // 版本号用来判要不要重弹（文案改了 bump `FirstRunNotices.VERSION`），时刻用来展示与核对。
        val KEY_USER_NOTICE_VERSION = intPreferencesKey("user_notice_version")
        val KEY_USER_NOTICE_SEEN_AT = longPreferencesKey("user_notice_seen_at")
        val KEY_DISCLAIMER_VERSION = intPreferencesKey("disclaimer_version")
        val KEY_DISCLAIMER_SEEN_AT = longPreferencesKey("disclaimer_seen_at")
        val KEY_PENDING_RECHARGE_FEN = longPreferencesKey("pending_recharge_fen")
        val KEY_PENDING_RECHARGE_AT = longPreferencesKey("pending_recharge_at")
        val KEY_PENDING_RECHARGE_BASE_FEN = longPreferencesKey("pending_recharge_base_fen")
        val KEY_PENDING_RECHARGE_ACCOUNT = stringPreferencesKey("pending_recharge_account")
        val KEY_PENDING_RECHARGE_TARGET = stringPreferencesKey("pending_recharge_target")
        val KEY_PENDING_RECHARGE_WALLET_BASE_FEN = longPreferencesKey("pending_recharge_wallet_base_fen")
        val KEY_PENDING_RECHARGE_CONFIRM_SHOWN = booleanPreferencesKey("pending_recharge_confirm_shown")
        val KEY_SHORTCUTS_JSON = stringPreferencesKey("shortcuts_json")
        val KEY_SCORE_INCLUDE_FREE_ELECTIVES = booleanPreferencesKey("score_include_free_electives")
        val KEY_SCORE_GROUP_BY_YEAR = booleanPreferencesKey("score_group_by_year")
        val KEY_SCORE_SORT_MODE = stringPreferencesKey("score_sort_mode")
        val KEY_PROFILE_NAME = stringPreferencesKey("profile_name")
        val KEY_PROFILE_CLASS = stringPreferencesKey("profile_class")
        val KEY_PROFILE_STUDENT_ID = stringPreferencesKey("profile_student_id")
        val KEY_PROFILE_COLLEGE = stringPreferencesKey("profile_college")
        val KEY_PROFILE_MAJOR = stringPreferencesKey("profile_major")
        val KEY_PROFILE_DURATION = stringPreferencesKey("profile_duration")
        val KEY_PROFILE_SYNC_DATE = stringPreferencesKey("profile_sync_date")
        val KEY_ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")
        val KEY_SCHOLAR_PLAN_NAME = stringPreferencesKey("scholar_plan_name")
        val KEY_SCHOLAR_SYNC_DATE = stringPreferencesKey("scholar_sync_date")
        val KEY_CURRICULUM_PLAN_NAME = stringPreferencesKey("curriculum_plan_name")
        val KEY_CURRICULUM_SYNC_DATE = stringPreferencesKey("curriculum_sync_date")
        val KEY_SCORE_SYNC_DATE = stringPreferencesKey("score_sync_date")
        val KEY_TEXTBOOK_SYNC_DATE = stringPreferencesKey("textbook_sync_date")
        // 抓成功过的学期号（含「成功但 0 条」）：见 textbookFetchedTerm
        val KEY_TEXTBOOK_FETCHED_TERM = stringPreferencesKey("textbook_fetched_term")
        // 教材补抓的上次尝试时刻（2026-10-06）：挡「教材为空时每次冷启动都打教务」
        val KEY_TEXTBOOK_BACKFILL_MILLIS = longPreferencesKey("textbook_backfill_millis")

        // 成绩/考试变动提醒（DESIGN §4.33）：两开关 + 间隔 + 各自上次成功检查时刻
        val KEY_SCORE_ALERT_ENABLED = booleanPreferencesKey("score_alert_enabled")
        val KEY_EXAM_ALERT_ENABLED = booleanPreferencesKey("exam_alert_enabled")
        // 成绩/考试变动提醒（DESIGN §4.33）：2026-10-04 起四链间隔各自独立，
        // KEY_ALERT_INTERVAL_HOURS 保留只为老用户读取回退，不再写入
        val KEY_SCORE_INTERVAL_HOURS = intPreferencesKey("score_alert_interval_hours")
        val KEY_EXAM_INTERVAL_HOURS = intPreferencesKey("exam_alert_interval_hours")
        val KEY_SELECTION_INTERVAL_HOURS = intPreferencesKey("selection_alert_interval_hours")
        val KEY_SCHEDULE_INTERVAL_HOURS = intPreferencesKey("schedule_alert_interval_hours")
        val KEY_ALERT_INTERVAL_HOURS = intPreferencesKey("alert_interval_hours")
        val KEY_SCORE_CHECK_MILLIS = longPreferencesKey("score_check_millis")
        val KEY_EXAM_CHECK_MILLIS = longPreferencesKey("exam_check_millis")
        val KEY_EXAM_SHELL_TERM = stringPreferencesKey("exam_shell_term")
        val KEY_EXAM_SHELL_AT_MILLIS = longPreferencesKey("exam_shell_at_millis")
        val KEY_ALERT_PERIODIC_INTERVAL = intPreferencesKey("alert_periodic_interval")

        // 选课（DESIGN §4.35）：上次同步时刻 + 学期下拉全量 + 轮次检查时刻 + 提醒开关与已发键
        val KEY_SELECTION_SYNC_MILLIS = longPreferencesKey("selection_sync_millis")
        val KEY_SELECTION_TERMS = stringPreferencesKey("selection_terms")
        val KEY_SELECTION_SHELL_TERM = stringPreferencesKey("selection_shell_term")
        val KEY_SELECTION_SHELL_AT_MILLIS = longPreferencesKey("selection_shell_at_millis")
        val KEY_SELECTION_ROUNDS_CHECK_MILLIS = longPreferencesKey("selection_rounds_check_millis")
        val KEY_SELECTION_ALERT_ENABLED = booleanPreferencesKey("selection_alert_enabled")
        val KEY_SELECTION_REMINDED_KEYS = stringSetPreferencesKey("selection_reminded_keys")
        val KEY_SELECTION_WISHES_JSON = stringPreferencesKey("selection_wishes_json")
        // 抢课三键（interval/disclaimer/auto_start）随自动抢课于 2026-10-08 v3 下线删除；
        // 设备上残留的旧键不再读写（DataStore 不清理孤儿键，无副作用）。

        // 调课检测（DESIGN §4.17，2026-10-04 重立）：提醒开关 + 检查时刻 + 已阅指纹 + 待处置差异
        val KEY_SCHEDULE_ALERT_ENABLED = booleanPreferencesKey("schedule_alert_enabled")
        val KEY_SCHEDULE_CHECK_MILLIS = longPreferencesKey("schedule_check_millis")
        val KEY_SCHEDULE_IGNORED_FP = stringPreferencesKey("schedule_ignored_fingerprint")
        val KEY_SCHEDULE_PENDING_JSON = stringPreferencesKey("schedule_pending_changes_json")

        // 问渠新增志愿活动提醒（DESIGN §4.45，2026-10-08）：开关 + 间隔 + 检查时刻 + 基线 id
        val KEY_WENQU_ACTIVITY_ALERT_ENABLED = booleanPreferencesKey("wenqu_activity_alert_enabled")
        val KEY_WENQU_ACTIVITY_INTERVAL_HOURS = intPreferencesKey("wenqu_activity_interval_hours")
        val KEY_WENQU_ACTIVITY_CHECK_MILLIS = longPreferencesKey("wenqu_activity_check_millis")
        val KEY_WENQU_ACTIVITY_LAST_SEEN_ID = longPreferencesKey("wenqu_activity_last_seen_id")
        // 活动列表的本地筛选（2026-10-08）：状态（-1 = 全部）+ 隐藏已满
        val KEY_WENQU_STATUS_FILTER = intPreferencesKey("wenqu_status_filter")
        val KEY_WENQU_HIDE_FULL = booleanPreferencesKey("wenqu_hide_full")
        // 只看我报名的 + 排序档位（2026-10-11）
        val KEY_WENQU_APPLIED_ONLY = booleanPreferencesKey("wenqu_applied_only")
        val KEY_WENQU_SORT = stringPreferencesKey("wenqu_sort")
        // 每周日 12:00「抢活动」提醒（2026-10-11）：开关 / 提前量 / 上次已提醒的发布时刻
        val KEY_WENQU_WEEKLY_ALERT_ENABLED = booleanPreferencesKey("wenqu_weekly_alert_enabled")
        val KEY_WENQU_WEEKLY_ALERT_OFFSET_MIN = intPreferencesKey("wenqu_weekly_alert_offset_min")
        val KEY_WENQU_WEEKLY_ALERT_LAST_FIRED_AT = longPreferencesKey("wenqu_weekly_alert_last_fired_at")
        // 蹲点模式的目标活动名（2026-10-11）：预填用，匹配在本地做
        val KEY_WENQU_GRAB_KEYWORD = stringPreferencesKey("wenqu_grab_keyword")
        // 蹲点盯着的指定活动 id（2026-10-15）：0 = 没盯；每轮单独拉它的详情拿实时名额
        val KEY_WENQU_GRAB_PINNED_ID = longPreferencesKey("wenqu_grab_pinned_id")
        // 盯着的活动「名额空出」已报过的那条 id（2026-10-15）：同一条只提醒一次
        val KEY_WENQU_GRAB_PINNED_ALERTED_ID = longPreferencesKey("wenqu_grab_pinned_alerted_id")
        // 预报名（2026-10-16）：瞄准的活动 id / 标题 / 报名开始时刻（id = 0 = 没排）
        val KEY_WENQU_PREREG_ID = longPreferencesKey("wenqu_prereg_id")
        val KEY_WENQU_PREREG_TITLE = stringPreferencesKey("wenqu_prereg_title")
        val KEY_WENQU_PREREG_OPENS_AT = longPreferencesKey("wenqu_prereg_opens_at")

        // AI 调课（DESIGN §4.39，2026-10-06）：服务地址 / 模型名 / 教师名脱敏 / 首次隐私确认。
        // API Key **不在这里**——它是账号凭据，走 AiCredentialStore 加密存储（secure_ai.xml）。
        val KEY_AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val KEY_AI_MODEL = stringPreferencesKey("ai_model")
        val KEY_AI_MASK_TEACHER = booleanPreferencesKey("ai_mask_teacher")
        val KEY_AI_PRIVACY_ACK = booleanPreferencesKey("ai_privacy_ack")
        // 思考档位（none/low/high/max）+ 是否显示思考过程（DESIGN §4.39）
        val KEY_AI_THINKING_EFFORT = stringPreferencesKey("ai_thinking_effort")
        val KEY_AI_SHOW_REASONING = booleanPreferencesKey("ai_show_reasoning")
        // 该模型是否支持图片识别（DESIGN §4.39）。**默认关**：模型名是用户手填的，
        // 上游 /models 也不给能力标记，探测不了——只能让用户显式声明，我们只做打勾建议。
        val KEY_AI_SUPPORTS_IMAGE = booleanPreferencesKey("ai_supports_image")

        // 首启引导后台同步结果（课表/成绩/学业），主界面弹结果弹窗后消费
        val KEY_ONBOARDING_SYNC_JSON = stringPreferencesKey("onboarding_sync_outcome_json")

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
