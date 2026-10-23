package edu.jxslu.schedule.data.prefs

import androidx.datastore.core.DataStore
import edu.jxslu.schedule.domain.BgScale
import edu.jxslu.schedule.domain.CourseFilter
import edu.jxslu.schedule.domain.EbikeQr
import edu.jxslu.schedule.domain.EbikeUseMode
import edu.jxslu.schedule.domain.StartPage
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.domain.ThemePalette
import edu.jxslu.schedule.domain.TimetablePrefs

/** 上课提醒的「已发键」类别（DESIGN §3.7）：提前量通知与上课开始通知各自独立去重。 */
enum class ReminderKeyKind(val prefix: String) {
    /** 提前量通知（上课前 N 分钟）。 */
    Before("before|"),

    /** 上课开始通知（上课时刻）。 */
    Start("start|"),
}

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
     * 新建时落的那个 Tab。旧版「生活 / 我的」不再是 Tab，存着的旧值由
     * [StartPage.effectivePage] 落回今日页（不写回存储）。
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
     * 胖乖「打开软件自动签到」。全局项，**默认关**。
     *
     * 只做每日签到，不碰任务/领奖；开启后每天首次回到前台检查一次，
     * 已签到就不再发写请求。平台规则与账号风险自担。
     */
    val qiekjAutoSignIn: Boolean = false,
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
