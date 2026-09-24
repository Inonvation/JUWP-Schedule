# 桌面小组件

作用域：改小组件渲染、尺寸分档、刷新机制、周网格时读。规格见 DESIGN §3.6。

## 单条目 + SizeMode.Exact 自适应

- 小组件是**单条目 + `SizeMode.Exact` 自适应**（2026-09-20 起，旧三档条目已删）：尺寸由
  `WidgetMetrics`（实测 dp）分档 Compact / List / Week，**不要再加按尺寸拆的 receiver 或
  `widget_info_*`**（旧版三条目内容重复，用户明确要求合并）。改渲染前先读 DESIGN §3.6；
  刷新机制（边界闹钟 + WorkManager + 冷启动）与「写状态 + `update()`」双步**不许动**
  （理由见 `ScheduleWidget` 类 KDoc：Glance 会话的两条硬约束是不可绕过的）。
  边界闹钟 2026-09-24 起用 **`setAlarmClock` 精确闹钟**（此前 `setAndAllowWhileIdle` 被
  Redmi K70 推迟几分钟，用户报「上下课了小组件还不换」）——**别改回**非精确闹钟；
  无特殊权限，代价仅是触发时状态栏短暂显示闹钟图标（上课提醒同一手法）。

## 周网格列与高亮列

- 小组件周网格的列取 `ScheduleCalculator.visibleDays`，与课表页同一口径（不要 `day - 1`）；
  高亮列规则是「今天还有课 → 今天，否则明天」，改这条前先读 DESIGN §3.6 的「明日接棒」。

## 澎湃OS / MIUI 负一屏

- 澎湃OS / MIUI 的负一屏只收录「小米小部件」（需开放平台审核），**原生小组件进不去**；
  设置页已给出替代路径（负一屏搜索 / 日历同步），不要把它当 bug 修（DESIGN §3.6「负一屏」）。
