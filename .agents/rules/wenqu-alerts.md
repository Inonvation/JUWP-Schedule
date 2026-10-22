# 问渠提醒 / 蹲点 / 预报名

作用域：改 `ui/reminder/Wenqu*`、`ui/wenqu/WenquGrab*`、`WenquPrereg*`、预报名闹钟时读。
核心接口、登录、搜索与页面见 `wenqu.md`；规格见 DESIGN §4.45。

## 新增志愿活动提醒（2026-10-08）

`ui/reminder/WenquActivityAlertReminder.kt`，**搭 `ScoreAlertReminder` 的周期任务（第五路）**，
不新开 WorkManager 任务；开关 / 间隔 / 检查时刻三个闸门都在本类内自判。

- 开关 `wenqu_activity_alert_enabled` **默认关**（通知是打扰型能力，同选课 / 调课口径）；
  间隔 `wenqu_activity_interval_hours` 默认 **24**，档位复用 `ScoreAlertDefaults.INTERVAL_CHOICES`
  （同一个 App 里两个「每 N 小时」给不同档位会让人以为哪个坏了）。
- **只报「招募中」**（`WenquRules.isNewRecruiting`）：平台会把补录的老活动插进列表
  （实测 id 最大的一条是「补录」且已结束），按 id 无差别报会把已结束的活动报成「新活动」。
- **首跑只建基线**（`wenqu_activity_last_seen_id` ≤ 0 时一律不算新增）：第一次抓到的全量
  是基线不是「新增」，否则用户一开开关就被几十条历史活动轰一遍。基线**只前进不后退**。
- **扫描一整页 100 条**（`WenquRepository.scanActivities`）：`volunteer/v2` **不保证按 id 排序**
  （实测前 21 条严格降序、之后跳回旧 id），只扫 20 条会漏掉夹在中间的新活动。
  它与 `activities()` 分开是**语义不同**：那个是「取一页」（进缓存、只给搜索兜底用），
  这个是「扫一遍看有没有新活动」（一次取够、**不写任何缓存**，2026-10-11 起）。
- **检查时刻只在成功时落**：未登录 / 网络 / 5xx 都不写，下一轮还试（同成绩/考试纪律）。
- **改开关 / 间隔走 `WenquActivityAlertReminder.onSettingsChanged`，不要用
  `ScoreAlertReminder.onSettingsChanged`**：后者会把五路的检查时刻一起清 0，于是用户只是
  动一下问渠的开关，成绩 / 考试 / 选课 / 调课四路就各打一次教务（2026-10-08 复查发现）。
  问渠自己的入口只清自己那一路，再 `ensurePeriodicWork` + `enqueueWenquCheck`
  （`ScoreAlertReminder.enqueueCheck(onlyChain = "wenqu")`，复用同一个 Worker 类名与工作名，
  不新开任务）。
- 通知走**免扰时段拦截**（事实型，`eventAt = null` → 时段结束补发）+ **收件箱留痕**
  （`InboxKind.WENQU_ACTIVITY`，键 `wenqu|activity|<本批最大 id>`，一次核对只留一条痕）。
  通知 id 1013 / 落点 requestCode 3013 / 渠道 `wenqu_activity`——**别与既有撞号**
  （1001/1004 上课、1002 作业、1005/1006 余额、1008/1009 成绩、1010 选课、1011 调课与 U净、
  3005–3012 各落点、1016/3015 盯的活动提醒、1017/3016 预报名、4008 预报名闹钟）。
- 设置页改开关 / 间隔后要调 `ScoreAlertReminder.onSettingsChanged`（清检查时刻 + 重排周期
  + 立刻评估一次），否则新规则当天不生效。

## 每周日 12:00「抢活动」提醒（2026-10-11）

用户口径：**学校很多志愿活动都是周日中午 12:00 发布、当场开抢**，所以要一条「提前 N 分钟」
的固定提醒。实现在 `ui/reminder/WenquWeeklyAlertReminder.kt`。

**与「新增志愿活动提醒」是两件事，别合并**：那条是**事后发现**（周期扫列表、依赖接口 + 网络），
这条是**事前蹲点**（发布时刻还没到，扫也扫不到新东西）——所以它**一条网络请求都不发**，
只有一条精确闹钟。

- 排法同 `SelectionAlertReminder`：主 = `setAlarmClock`（requestCode **4007**，不需要特殊权限）；
  兜 = 一次性 Work（闹钟落点 / 开机 / 覆盖安装 / 开关变更）+ 冷启动 `JuwApplication` 补排。
  **不搭周期任务**（不是「周期扫」那类，别往 `ScoreAlertReminder` 里塞）。
- 时刻算在 `WenquRules`：`nextWeeklyAlertPoint` / `lastWeeklyAlertPoint`（纯函数，JVM 测）。
  `publishAt` = 本周日 12:00（本地时区），`remindAt` = `publishAt` − 提前量（**1–10 分钟，
  默认 5**，`WEEKLY_ALERT_OFFSET_*`）。
- **已发键是「发布时刻」不是「提醒时刻」**（`wenqu_weekly_alert_last_fired_at`）：用户把提前量
  从 5 分钟改成 10 分钟，同一周不该再报一次。
- **迟到窗口 10 分钟**（`WEEKLY_ALERT_LATE_MS`，对齐免扰时段对时刻型提醒的过期口径）：
  过了就不补发——用户要的是「12:00 前就位」，12:30 才响只是噪音。
- **通知被系统整体关掉时不落键**（下次冷启动 / 开机还在窗口内就还能补）。这条**不入箱**
  （时刻型提醒不入箱——与选课那条不同，那边有收件箱兜底）。
- 通知走**免扰拦截**（时刻型，`eventAt` = 发布时刻）+ **直接震动**（`ReminderVibration`）。
  **不挂通用「通知强提醒」开关**：这条是用户为「抢」显式开启的、一周一次的能力，而 HyperOS 上
  第三方通知的振动开关默认是关的（2026-10-05 实测），不直接震等于静默。
- 响过之后调 `WenquRepository.invalidateActivities()`：把快照的**同步时刻清零**，
  下次进页立刻增量同步一次（**不是整份重抓**）——用户点通知进来时不该看到发布前的旧列表。
  **只清时刻、不发请求**——进页照旧是用户动作。
- 通知 id **1015** / 落点 requestCode **3014** / 渠道 `wenqu_weekly`；设置页改开关 / 提前量走
  `WenquWeeklyAlertReminder.onSettingsChanged`（**只重排自己的闹钟**，别碰
  `ScoreAlertReminder.onSettingsChanged`——那会顺带骚扰教务四路）。
- 开关 `wenqu_weekly_alert_enabled` **默认关**（打扰型能力，同选课 / 调课 / 新增活动提醒口径）。

## 蹲点模式（抢活动，2026-10-11）

用户口径：周日 12:00 发布 + 服务器卡，手动「刷新 → 找 → 点开 → 报名 → 确认」抢不过别人。
蹲点把链条压成「到点自动检测 → 点一下确认报名」。实现在
`ui/wenqu/WenquGrabScreen.kt` + `WenquGrabViewModel.kt` +
`WenquRepository.grabProbe`，参数集与判据在 `WenquRules`（纯函数，JVM 测）。
**与「每周日提醒」（事前叫醒）和「新增活动提醒」（周期发现）都不合并**。

**检测与请求纪律**（改之前先读，别顺手「优化」）：

- **默认只发一种请求**：每轮 1 个 `volunteer/v2` 第一页（`GRAB_PAGE_LIMIT = 30`、空关键词），
  不拉工时 / 星级、不整份重抓。成功后 `mergeNewest` 并进快照并推 `syncedAt`
  ——探测本身就是一次「只看最新」的同步，页面列表跟着新，不需要下拉。
  **例外**：盯了指定活动时每轮**再多 1 个** `volunteer/detail/{id}`（见「盯指定活动」）。
- **短超时（两档，2026-10-16）**：常规探测走 `probeApi`（6 秒）；
  **发布窗口内换 `burstApi`**（`GRAB_BURST_TIMEOUT_S = 3` 秒）——窗口里挂 6 秒少试好几轮。
  **别把共享那份 30 秒改小**（登录 / 详情 / 写操作靠它）。
- **串行 + 退避（2026-10-16 改）**：上一轮不回来不发下一轮；成功间隔 3 秒
  （`GRAB_POLL_INTERVAL_MS`），连续失败**窗口外**按 `grabNextDelayMs` 退避（6 / 9 / 12 秒
  封顶）+ **±20% 抖动**（`jitteredDelayMs`，防节奏指纹）。**窗口内失败也走 1 秒快重试**——
  原先失败照退避 6/9/12 秒，而 12:00 的拥堵恰好表现为超时，于是最该续命的时候反而最沉默。
  **发布窗口** = `[publishAt - GRAB_BURST_BEFORE_MS(20 秒), publishAt + GRAB_BURST_AFTER_MS
  (15 秒)]`，成功与失败都加密到 1 秒（`GRAB_POLL_INTERVAL_FAST_MS`）；前缘 2026-10-16 由
  5 秒放宽到 20 秒——平台**写接口会提前约 10 秒放行**（用户实测）。`publishAt` 由
  `grabPublishAt` 给：**盯了活动就用它的实际报名开始时刻**（`pinnedOpenAt`，周二 / 周三的
  定点开放也能吃到 1 秒档与 3 秒短超时，2026-10-19 全马活动就是周二 12:00）；没盯才退回
  「周日 12:00，非周日回 0」的旧口径。串行保证高峰时自然退化成服务器响应速度。
- **窗口（2026-10-16 重做）**：点「开始蹲点」**立即检测，不再待机到 11:59:50**（旧
  `grabDetectStartAt` 已删——它既挡不住「提前开放」，又让 11:50 前开蹲的窗口在 12:00 被掐断）。
  检测窗口默认自开蹲起算 10 分钟（`GRAB_WINDOW_MS`），**开始早于发布时刻时锚到「发布 +
  10 分钟」**（`grabWindowEndAt(detectStartedAt, publishAt)`）；`GRAB_MAX_CHECKS = 900`
  次安全阀（旧 300 会在 12:03 前后提前掐）。**离发布超过 5 分钟**（`GRAB_RELAXED_BEFORE_MS`）
  走 10 秒慢档（`GRAB_POLL_INTERVAL_RELAXED_MS`）——省请求的初衷保留，但 10 秒档一直在看，
  「提前开放」照样接得住。
- **命中 = `grabFirstMatch`**：在**整份快照**里找第一条「（`matchesKeyword` 匹配 **或** 是盯着的
  那条）+ `applyBlockReason` 为空」的。**不是只扫探测那一页**——2026-10-11 用户点验报「有招募中且
  匹配的活动却不弹」，因为多数招募活动不在最新 30 条里。同一条只弹一次（会话内
  `shownHitIds`），**弹过之后检测继续**（先弹到旧一批、12:00 新品出现还会再弹）；
  报名成功 / 手动停止 / 窗口到点才收工。
- **盯指定活动（2026-10-15；2026-10-16 改并发）**：落盘键 `wenqu_grab_pinned_id`（0 = 没盯）。
  盯了就在每轮**额外**拉那一条的详情（`WenquRepository.refreshIndexItem` →
  `fetchDetailUncached`，**绕过详情缓存**）并替换快照里同 id 那条——快照里**深层条目的名额
  会冻住**（`mergeNewest` 只刷新最新一页），所以**已满的老活动空出名额只有这一路看得见**。
  **与列表探测并发发**（`coroutineScope + async`）并走短超时档（Fast / Burst）——原先它串行
  在探测之后、还挂 30 秒超时，高峰期一个人就能把一轮拖到 30 秒以上。命中判定里它**优先于
  名字匹配**、且不看名字：已满 → 一直不命中，`balance_num` 转正才弹。失败静默——列表探测的
  失败已经计过退避，别算两次。**「盯」只对还会再放名额的状态开放**（入口见「页面侧」）；
  盯的活动转为终结态（结束 / 取消 / 报名截止 / 已报名）时**自动取消盯**
  （`WenquRules.pinFutile`：前台一轮发现即清 + 提示，后台检查静默清，2026-10-16）。
- **蹲点不自动提交**：命中只弹 `WenquGrabHitDialog`（确认报名 / 先看看 / 关闭），报名仍走
  `writeOnce` 那条单发写路径。自动提交**只发生在用户单独开启的「预报名」**里（见下节，
  2026-10-16 用户拍板）；蹲点这一路永远只弹确认框。
- 探测回 `isBlack` → 立即停 + 用平台原话说明（账号级限制，继续蹲没意义）。

**页面侧**：

- 目标活动名 `wenqu_grab_keyword`（预填 + 落盘）；**输入框随时可改**：检测中每轮读最新值，
  改了就改蹲新名字，清空即停（点验反馈定稿）。填好后列表里命中的活动**自动置前**并回顶。
- 「过滤已满」复用主页 `wenqu_hide_full` 落盘值（一处开关两边一致）；列表 = 同一份本地快照
  （点卡片开详情弹层，详情里仍可报名 / 取消）。
- **「盯着：<活动名> ✕」行**（2026-10-15）：`pinnedId > 0` 时出现在目标名输入框下面，✕ 取消盯。
  入口在**详情弹层**（主页与蹲点页的弹层都有；记录页不传 `onPin`），**只在「报名尚未开始 /
  名额已满」时出现**（`WenquRules.canPin`，2026-10-16 修——已结束 / 进行中 / 已报名 / 被限制
  等状态盯了也不会再有动静）；按钮文案随状态：还没开报名是「盯这条 · 开始报名时提醒我」，
  已满才是「盯这条 · 名额空出时提醒我」。**只盯了活动（名字空）时「开始蹲点」也可点**；
  检测中「名字空即停」的判据同样放行盯了活动的情况。
- 命中**震动 + 提示音**（`ReminderVibration` + `ReminderChime`，2026-10-15 加音——
  用户可能没盯着屏幕）；**弹窗开着时暂停探测**（不发请求）；蹲点期间
  **屏幕常亮**（`view.keepScreenOn`）。
- **离开页面（VM 销毁）= 停止**；**蹲点这条路不做后台 / 息屏轮询**（Android 限制 + MIUI
  冻结，且弹窗弹不出来）——需要「无人值守」的场景走**预报名**（见下节：精确闹钟 + 短时
  前台服务，那是单独拍板的另一条路），别把蹲点也接后台。
- **「已预报名：<时刻> 自动抢《名》 ✕」行**（2026-10-16）：`preregId > 0` 时出现在控制卡里，
  ✕ 取消（清落盘 + 撤闹钟 + 撤兜底 Work；在飞的一轮自查落盘后自行收手）。
- 顶栏用 `pinnedStatusBars()`（详情弹层有「去微信报名」出口，会拉起外部应用）。

**后台一路：盯的活动「可以报名了」提醒（2026-10-15；2026-10-16 改口径）**：盯了指定活动时，**搭
`WenquActivityAlertReminder` 那趟共享周期任务**（**不新开 WorkManager 任务**）——它每轮除了扫
新活动，还单独拉一次盯着那条的详情（`refreshIndexItem`），**可以报名了**（`applyBlockReason`
回空——名额空出 / 报名开放两种情形都算）就发通知 + 入箱（标题「可以报名了：<活动名>」），
同一条只报一次（`wenqu_grab_pinned_alerted_id`，重新盯 / 取消盯时清 0）。

- **盯的活动转为永远等不到的状态时静默取消盯**（2026-10-16，`WenquRules.pinFutile`：
  结束 / 取消 / 报名截止已过 / 已报名），并重排一次共享周期任务——盯是「五路全关也保留」
  的例外，例外没了就回到全关即撤。
- **不挂开关**（盯 = 用户的显式选择）、**不过免扰时段**（可以报名是当下要动的事，压到时段
  结束就没意义了）。**不自动报名**——通知只喊人，报名仍由用户点。
- **粒度 = 共享周期任务的间隔**：盯了活动时它被压到最小档 `GRAB_PIN_CHECK_INTERVAL_HOURS = 1`
  （小时制；WorkManager 下限虽是 15 分钟，但这是**共享**任务、还要顺手跑其它五路，再密就是让
  整个进程频繁醒）。**所以它只救「名额能挂一小时」的冷活动**——秒级热活动后台追不上，
  得靠前台蹲点。想再快只能上前台服务，那是另一件事。
- `ScoreAlertReminder.ensurePeriodicWork` 的「五路全关就取消」多一条例外：**盯了活动就保留**；
  `check` 的问渠那一路同理（开关关着但盯了活动也跑）。
- 通知 id **1016** / 落点 requestCode **3015** / 渠道**复用** `wenqu_activity`（别与 1013/1015、
  1017、3013/3014、3016 撞号）；**直接震动**（同每周日那条的理由：HyperOS 上第三方通知振动默认关）。
  入箱键 `wenqu|slot|<活动id>`。
- pin / unpin 之后调 `WenquActivityAlertReminder.onPinChanged(context)` 重排周期任务
  （**只重排、不立刻叫醒**）。

## 预报名（准点自动抢，2026-10-16；用户拍板）

用户口径：活动写着 12:00 开始报名，但平台**写接口会提前约 10 秒放行**（11:59:50 就能抢），
手点抢不过；要「选好活动、到点全自动」。**这是全 App 唯一允许自动提交的路径**——
只对用户点名的单条活动生效，其余写操作仍是「用户点一次发一次」。实现在
`ui/wenqu/WenquPreregReminder.kt`（调度 + 通知）+ `WenquPreregRunner.kt`（执行器）+
`WenquPreregService.kt`（短时前台服务），参数与判据在 `WenquRules`（JVM 测）。

**排程**：

- 入口在**活动详情弹层**（主页与蹲点页的弹层都有；记录页不传）：仅当
  `applyBlockReason == WenquRules.APPLY_NOT_STARTED`（「招募中 + 报名时间可解析 + 还没到」）
  时给「预报名 · 准点自动抢」按钮，点击过 `WenquPreregConfirmDialog`（二次确认写明活动名 /
  报名时间 / 自动提交 / 最多 30 发 / 可随时取消）；已排上显示「已预报名 · <时刻> 自动抢
  （点此取消）」状态行。
- 落盘三键 `wenqu_prereg_id / _title / _opens_at`（一起写、一起清，见 `DisplayPrefsStore`）；
  同一时间只支持一条。
- 排法同每周提醒：`setAlarmClock`（requestCode **4008**，触发 = 报名开始前 60 秒）+
  一次性兜底 Work + 开机 / 覆盖安装（`ReminderBootReceiver`）/ 冷启动（`JuwApplication`）
  补排。**遗漏处理**：补排时发现已过 `opens_at + 90 秒` → 作废 + 通知「未执行」。
- **平台时钟对齐（`WenquServerClock`）**：预报名专用客户端挂 `WenquServerClockInterceptor`，
  从响应 `Date` 头记「服务器 − 手机」偏移（超 10 分钟视为异常样本丢弃），试发窗口按服务器钟
  折算（`preregTryStartAt`）。

**执行（到点后）**：

- 闹钟落点 → `WenquPreregService`（**短时前台服务** type `shortService`，约 3 分钟额度；
  精确闹钟触发属于「允许从后台启动前台服务」的官方豁免——`restrictions-bg-start`，
  同骑行自动还车那条链）。起服务失败 → 一次性 Work 直接跑执行器（无进度通知）。
- 执行器时序：**T−60s** 预热 token → **并发校对**（列表第一页带 `isBlack` + 本条详情带
  状态 / 名额 / 报名时间；平台改过时间顺延重排并通知）→ **T−15s 起串行试发**（结果不明时
  只读核对 `preregAppliedNow`；平台明确回「尚未开始」时**不核对**——那一发确定没落库，
  核对只会白占一个来回；「已报名」当成功，已满 / 被限制 / 已下架当终结）→ 收工
  （`preregShouldStop`：≤`PREREG_MAX_ATTEMPTS = 30` 发、总时长 ≤`PREREG_RUN_MS = 150` 秒、
  开始后 ≤`PREREG_END_AFTER_MS = 90` 秒，三闸取先到）。
- 单发超时 `PREREG_ATTEMPT_TIMEOUT_S = 5` 秒（`preregApi`）；发与发之间
  `PREREG_ATTEMPT_GAP_MS = 1` 秒（2026-10-19 由 2 秒收到 1 秒：240 个名额的热门活动可能几秒
  报满，2 秒档在开放那一刻最坏会晚 2 秒多）。**写请求不自动重发**：网络类失败 → 只读核对；核对不上 →
  下一发；平台业务话术分类见 `WenquRules.classifyPreregReject`（认不出的按 Unknown 继续试、
  靠次数封顶，不硬认成终结）。
- **只读核对单独用 2 秒超时**（`PREREG_CHECK_TIMEOUT_S` / `preregCheckApi`，2026-10-19）：
  写请求超时后核对与写走同一条拥堵链路，挂 5 秒等于把下一发推后 3 秒；2 秒拿不到就交给
  下一发——下一发若已报上，平台回「已报名」，同样按成功收尾。
- **收工终检（2026-10-21）**：触顶收工前（非成功、非明确终结、发过试发）补最后一次**耐心**
  核对 `preregAppliedNowSlow`（`PREREG_FINAL_CHECK_TIMEOUT_S = 10` 秒，`preregFinalCheckApi`）
  ——终检没有「赶下一发」的时序压力，多熬几秒把高峰期的慢响应等回来。
  **背景**：2026-10-19 用户实测「脚本报没抢到，手动报名时平台回『已报名该活动，无法重复报名』」
  ——某发超时但服务端已落库，旧版触顶后没人回头看一眼，把已报上的误报成没抢到。
  终检三出口：Applied → 改报「报名成功」并注明救回；NotApplied → 确认没抢到；
  Unknown → 如实说「未能确认，请进详情页核对」，**不把不确定说成确定**。
- **手动报名 × 自动抢互锁（2026-10-21）**：执行器每轮试发前查 `WenquRepository.appliedInIndex(id)`
  （零请求读快照；用户在 App 内手动报名成功会经 `patchAfterApply` 就地置「已报名」），
  为真即按成功收工——脚本不对自己人抢。快照没有这条回 false：互锁是旁路，
  权威判定仍在平台（真报上了平台回「已报名」话术）。
- **拒绝话术分类扩面（2026-10-21）**：`classifyPreregReject` 的 AlreadyApplied 补
  `重复报名` / `无法重复` / `已参加`，NotStarted 补 `未到报名`——依据是用户实测的
  「已报名该活动，无法重复报名」原文（反编译产物核对：该话术是**服务端**的，
  小程序前端全文无此字样，App 按子串分类正确）。
- **不要用快照的 `balance_num` 提前判「名额已满」**：活动还没开放报名时平台可能把剩余名额写成 0，
  提前 abort 等于把还能抢的活动直接判死。到点真发，第一发回「名额已满」再按终结收工。
- 通知（channel `wenqu_prereg` / id **1017** / 落点 requestCode **3016**；重试 action 的
  PendingIntent requestCode **3017**）：执行中常驻进度（静默，服务持有；结束时结果就地替换、
  `STOP_FOREGROUND_DETACH` 保留，空跑则摘掉），**点击 = 打开 App 并直接弹仪表板**
  （`WenquPreregDashboardActivityJump.intent`：直指 MainActivity 的 NEW_TASK|CLEAR_TOP 跳板，
  不附 screen extra）；结果「报名成功 / 没抢到 / 未执行」**直接震动、不过免扰时段**
  （同名额空出提醒口径），「没抢到」的结果带一枚**重试** action（`startRetry`：
  前台重拉服务跑重试轮）。日志只有纯数字（attempts / elapsedMs / serverLateMs / offsetMs /
  recovered），供演练调参。
- **幂等与并发**：进程内 `AtomicBoolean` + 落盘双闸；用户取消（落盘被清）中途收手、不打扰；
  执行器提前太多被拉起（兜底 Work 早跑）直接空跑交还闹钟。

**重试轮（2026-10-21）**：

- 入口两处：仪表板终态失败的「重试（5 发）」按钮、结果通知的「重试」action，
  都走 `WenquPreregReminder.startRetry`（**前台**启动前台服务合规）+ `EXTRA_RETRY`。
- 预算独立：`PREREG_RETRY_MAX_ATTEMPTS = 5` 发、`PREREG_RETRY_RUN_MS = 30` 秒——
  主轮是「准点」的，重试轮是「事后补救」，名额可能已被抢空，不值得再花 30 发 / 150 秒。
- 复用同一执行器 / 幂等闸 / 通知链路（`running` AtomicBoolean 挡并发），收工同样走终检。
- 服务中断（`onTimeout` / 异常销毁）时若仪表板还在跑，`markInterrupted` 置
  「被系统中断」终态——面板看到实情，不是永远的「抢报中」。

**前台仪表板（2026-10-21）**：

- 状态单一数据源 = `WenquPreregRunner.dash`（`StateFlow<WenquPreregDashState>`，执行器唯一写方，
  `updateDash` 是同模块写口）：阶段（校对 / 等待 / 试发 / 终检 / 终态）· 已发次数 ·
  最近平台话术 · 终态文案。**通知与面板各读一份，终态文案只写一次**——
  结果通知与面板结果页天然同源。终态落盘 5 分钟后自动归 Idle（期间用户又排新的一轮则不动）。
- 前台判定 = `WenquAppForeground`（`Application.ActivityLifecycleCallbacks` 按 **started**
  计数，JuwApplication 冷启动 `install` 一次；**不引 lifecycle-process**，AndroidX 版本边界
  卡得紧，不为一个布尔值加依赖。用 started 不用 resumed：弹窗不改 resumed 计数）。
- 弹层 = `WenquPreregDashboardHost`（Compose Dialog，复用 `DialogWindowFadeEffect`），
  挂在 `JuwRoot` 与 `SettingsActivity` 各一份——执行期间**任何界面**都弹。
  自动弹：起跑时在前台 / 后台起跑后回前台（沿 false→true）。
  **用户关掉后本轮不再自动弹**（执行照常，进度走通知）。
- **折叠胶囊（2026-10-21 补）**：面板被关掉（或后台起跑还没弹）时，屏幕**顶部中央**常驻一枚
  「抢报中 · 第 N 发」胶囊（`DashCollapsedPill`，主色容器底 + 脉动圆点），**单击重开面板**；
  终态后胶囊改文案（报名成功 / 没抢到 / 已取消）停留 8 秒再消失。它**不依赖悬浮球**：
  问渠页等二级页是独立窗口，悬浮球按既有口径只在主窗口四个 Tab，但 Host 挂在两边根组合上，
  胶囊**全窗口可见**——问渠页里关掉弹窗后点胶囊即回，不用返回主界面；主窗口里它也是
  悬浮球之外的第二入口（球在显示设置面板打开时还会收起）。
- 重开入口四处：① 进度 / 结果通知点击；② 悬浮球单击（主窗口，预报名执行期间）；③
  **顶部折叠胶囊（任何窗口）**；④ 蹲点页「已预报名」行。
- 操作：**取消**（面板内二次确认 → `WenquPreregReminder.cancel`，执行器 `stillArmed`
  自查收手；面板如实提示「正在提交的那一发可能已落库，详情页会显示已报名」）、
  **手动报名**（`repo.applyActivity` 共享 30 秒客户端；成功 / 平台「已报名」话术都回填
  「最近返回」并点亮互锁）、**重试**（见上）。
