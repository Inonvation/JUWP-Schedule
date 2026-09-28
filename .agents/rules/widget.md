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

## 开水两卡（胖乖开水 + 趣智开水，2026-09-28）

- 选择器里另两条**内容**条目：胖乖开水（`QiekjWaterWidgetReceiver`）与趣智开水
  （`QzxyWaterWidgetReceiver`）。都**固定 2×2**（provider XML `resizeMode="none"`，
  用户要求不给拖动）——「不要再加按尺寸拆的 receiver」的老规矩不变，这两条是按内容拆。
  机制仍是单条目 + `SizeMode.Exact`，「写状态 + `update()`」两步与首帧捕获原样适用。
- **编排层唯一入口 `WaterWidgetSync`**（`ui/widget/WaterWidgetSync.kt`）：权威快照在
  DataStore（`water_widget_prefs`），Glance 状态只是渲染镜像；登录态不落盘，构建快照
  时现算。纯逻辑（快照 / 文案 / codec）在 `WaterWidgetModels.kt`，单测
  `WaterWidgetModelsTest` 钉死。
- **渲染路径零网络**：胖乖余额由 `WaterViewModel.refreshBalance` 成功顺手推；趣智余额
  由 `QzxyViewModel.refreshAccount` 成功顺手推；后台 2 小时闸门（复用
  `CampusBalanceGate`——名字带 Campus 是历史沿革，语义是通用的小组件余额闸门）
  走 15 分钟 tick 与冷启动。**失败不重试、不落时刻**；趣智会话失效只当失败，
  **不代用户登出**。
- **趣智「用水中」是本地镜像**（`QzxyWateringStore`）：开阀 / 结算 / 手动标记 / 过期
  清理即时推（`QzxyViewModel.pushWaterWidget`）；超 1 小时视为残留不上桌面（与页面
  `applyWatering` 同一条过期规则，别在卡片另定阈值）。
- **点击分区（2026-09-28 用户拍板）**：胶囊「去开水」→ 进页面并**自动开水 / 开阀**
  （route `water_start` / `qzxy_start` + 一次性令牌 `WaterAutoStart`，`SubpageStack.kt`）；
  整卡其余位置只进页面（route `water` / `qzxy`）。实现要点：
  - 令牌是**进程内单槽、消费即清**，不进 `SubpageRequest`——窗口链恢复
    （`SubpageStack.pendingRestore`）重建页面时不得重触发开水，那会在用户不知情时
    开阀计费；
  - 自动开水的动作全在页面 VM 里（`WaterViewModel.requestAutoUnlock` /
    `QzxyViewModel.requestAutoOpen`），VM 内有一次防重入；未登录 / 流程进行中 /
    趣智已在用水都静默跳过。胖乖设备沿用「最近使用」口径（等 init 的设备请求回来
    选默认台，`devicesJob` 别改成每次重发）；趣智设备沿用 `openValveFromCard`
    （上次那台 → 唯一绑定那台 → 提示去选）；
  - 胖乖直达**绕过双击确认设置**——那颗开关管的是页面里的大按钮，桌面胶囊是更明确
    的主动手势（用户拍板）；
  - 胶囊的 `clickable` 盖住整卡那层（合并卡 PowerLine 同款 PendingIntent 覆盖手法），
    外圈 `padding` 垫出触控区，别把垫高层删掉。
- 点击路由：`MainActivity` 的 `EXTRA_ROUTE` 开水相关共 `water` / `qzxy` /
  `water_start` / `qzxy_start` 四个值，与付款码等同一套「先消费再启动」口径；
  给新小组件加落点前先 grep 现有 route 常量，别撞名。

## 澎湃OS / MIUI 负一屏

- 澎湃OS / MIUI 的负一屏只收录「小米小部件」（需开放平台审核），**原生小组件进不去**；
  设置页已给出替代路径（负一屏搜索 / 日历同步），不要把它当 bug 修（DESIGN §3.6「负一屏」）。
