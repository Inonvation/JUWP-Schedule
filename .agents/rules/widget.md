# 桌面小组件

作用域：改小组件渲染、尺寸分档、刷新机制、周网格时读。规格见 DESIGN §3.6。

## 三条目（按内容拆，2026-09-27）

- 选择器里三条**内容**条目：课表（`ScheduleWidgetReceiver`，单条目自适应）/ 校园卡
  （`CampusCardWidgetReceiver`，余额 + 点击出码）/ 电费（`PowerWidgetReceiver`，读数 +
  点击用电统计）。三条各自独立 receiver + provider XML——「不要再加按尺寸拆的 receiver」
  的老规矩指**尺寸变体**，按内容拆不违反；两条新条目自身也是单条目 + `SizeMode.Exact`，
  无尺寸档，maxResize 收在 4×2。改渲染前先读 DESIGN §3.6。
- **编排层唯一入口 `LifeWidgetSync`**（`ui/widget/LifeWidgetSync.kt`）：权威快照在
  DataStore（`life_widget_prefs`），Glance 状态只是渲染镜像。校园卡余额取数有
  **2 小时闸门**（`CampusBalanceGate`，纯函数）+ 失败不重试不落时刻（与余额提醒同口径），
  验证码接口（8002/8003）在这条链路上绝不触碰；**电费条目任何刷新路径都零网络**——
  只镜像 Room `power_readings` 最新读数，读数密度 = 打开 App 的密度（DESIGN §3.13），
  别让小组件变相轮询第三方平台。折合金额只走 `BalanceAlert.remainingYuan`。
- **码不预取**：校园卡小组件只是启动器，出码 / `FLAG_SECURE` / 亮度 / 扫码自动退出
  仍只在付款码页（DESIGN §3.10「只有一处出码」）；别往小组件里塞取码逻辑。
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
  两条新条目同样受「写状态 + `update()`」与「首帧捕获进组合」约束（各自的
  `*SnapshotStore` 照抄同一套，见 DESIGN §3.6「刷新策略」）。
- 边界闹钟 2026-09-24 起用 **`setAlarmClock` 精确闹钟**（此前 `setAndAllowWhileIdle` 被
  Redmi K70 推迟几分钟，用户报「上下课了小组件还不换」）——**别改回**非精确闹钟；
  无特殊权限，代价仅是触发时状态栏短暂显示闹钟图标（上课提醒同一手法）。

## 周网格列与高亮列

- 小组件周网格的列取 `ScheduleCalculator.visibleDays`，与课表页同一口径（不要 `day - 1`）；
  高亮列规则是「今天还有课 → 今天，否则明天」，改这条前先读 DESIGN §3.6 的「明日接棒」。

## 澎湃OS / MIUI 负一屏

- 澎湃OS / MIUI 的负一屏只收录「小米小部件」（需开放平台审核），**原生小组件进不去**；
  设置页已给出替代路径（负一屏搜索 / 日历同步），不要把它当 bug 修（DESIGN §3.6「负一屏」）。
