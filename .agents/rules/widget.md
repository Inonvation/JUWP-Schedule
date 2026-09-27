# 桌面小组件

作用域：改小组件渲染、尺寸分档、刷新机制、周网格时读。规格见 DESIGN §3.6。

## 两条目（课表 + 校园卡 · 电费合并卡，2026-09-27）

- 选择器里两条**内容**条目：课表（`ScheduleWidgetReceiver`，单条目自适应）与
  「校园卡 · 电费」合并卡（`CampusCardWidgetReceiver`，主区余额大字 + 副行电费小字）。
  各自独立 receiver + provider XML——「不要再加按尺寸拆的 receiver」的老规矩指**尺寸
  变体**，按内容拆不违反；两条自身都是单条目 + `SizeMode.Exact`，无尺寸档，maxResize
  收在 4×2。改渲染前先读 DESIGN §3.6。
- **合并卡当天先从一条拆成三条、再合并回一条**（拆开后 2 格宽的卡片只放得下一块信息，
  桌面看着空）。原 `PowerWidgetReceiver` 已删：桌面上加过电费条目的话会变成失效占位，
  用户手动删掉即可，别为它做兼容层。
- **点击分区**：整卡 → 付款码页（无凭证 → 校园卡设置页），电费副行 → 用电统计页。
  靠 Glance 的 `PendingIntent` 覆盖实现（子元素 clickable 盖住父级，与课表条目周网格
  同一手法），副行外面套 `Box` 垫到 23dp 高；改这块别把整卡那层删掉。
- **窄档排版**：真机实测 2 格宽只有 150dp、2 格高 178dp，卡片是竖条。宽度 < 200dp
  （`isNarrowWidth`）走窄档：内容块之间三处间隔都给弹性（`WidgetGap`），剩余高度三等分、
  内容铺满卡片——先做成整块居中，上下各留一大段空白，被用户否掉；
  副行文案也分档（`LifeWidgetFormat.powerLineText`）。别把宽档那套「固定间距 + 按钮沉底」
  原样搬到窄档。
- **编排层唯一入口 `LifeWidgetSync`**（`ui/widget/LifeWidgetSync.kt`）：权威快照在
  DataStore（`life_widget_prefs`），Glance 状态只是渲染镜像；快照 `LifeCardSnapshot`
  一次带校园卡 + 电费两份数据。校园卡余额取数有 **2 小时闸门**（`CampusBalanceGate`，
  纯函数）+ 失败不重试不落时刻（与余额提醒同口径），验证码接口（8002/8003）在这条链路
  上绝不触碰；**电费副行任何刷新路径都零网络**——只镜像 Room `power_readings` 最新读数，
  读数密度 = 打开 App 的密度（DESIGN §3.13），别让小组件变相轮询第三方平台。折合金额
  只走 `BalanceAlert.remainingYuan`。
- **码不预取**：合并卡只是启动器，出码 / `FLAG_SECURE` / 亮度 / 扫码自动退出仍只在
  付款码页（DESIGN §3.10「只有一处出码」）；别往小组件里塞取码逻辑。
- 推送点 = App 内成功取数的地方顺手推（付款码页 `PayCodeViewModel` / 生活页
  `LifeViewModel` / 缴费账单页 / 余额提醒 `BalanceAlertReminder`），外加 15 分钟
  Worker tick（`WidgetRefreshWorker` → `LifeWidgetSync.onPeriodicTick`）与冷启动
  （`JuwApplication` → `onColdStart`）。给新取数路径接桌面镜像时从这几处挑，别新起调度。

## 单条目 + SizeMode.Exact 自适应（课表条目）

- 课表小组件是**单条目 + `SizeMode.Exact` 自适应**（2026-09-20 起，旧三档条目已删）：
  尺寸由 `WidgetMetrics`（实测 dp）分档 Compact / List / Week，**不要再加按尺寸拆的
  receiver 或 `widget_info_*`**（旧版三条目内容重复，用户明确要求合并）。改渲染前先读
  DESIGN §3.6；刷新机制（边界闹钟 + WorkManager + 冷启动）与「写状态 + `update()`」
  双步**不许动**（理由见 `ScheduleWidget` 类 KDoc：Glance 会话的两条硬约束是不可绕过的）。
  合并卡条目同样受「写状态 + `update()`」与「首帧捕获进组合」约束
  （`LifeCardSnapshotStore` 照抄同一套，见 DESIGN §3.6「刷新策略」）。
- 边界闹钟 2026-09-24 起用 **`setAlarmClock` 精确闹钟**（此前 `setAndAllowWhileIdle` 被
  Redmi K70 推迟几分钟，用户报「上下课了小组件还不换」）——**别改回**非精确闹钟；
  无特殊权限，代价仅是触发时状态栏短暂显示闹钟图标（上课提醒同一手法）。

## 周网格列与高亮列

- 小组件周网格的列取 `ScheduleCalculator.visibleDays`，与课表页同一口径（不要 `day - 1`）；
  高亮列规则是「今天还有课 → 今天，否则明天」，改这条前先读 DESIGN §3.6 的「明日接棒」。

## 澎湃OS / MIUI 负一屏

- 澎湃OS / MIUI 的负一屏只收录「小米小部件」（需开放平台审核），**原生小组件进不去**；
  设置页已给出替代路径（负一屏搜索 / 日历同步），不要把它当 bug 修（DESIGN §3.6「负一屏」）。
