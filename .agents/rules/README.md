# .agents/rules 索引

这里放**操作手册**：动某块代码前读什么、改哪里、怎么验、踩过什么坑。
路由表在 [`AGENTS.md`](../../AGENTS.md)「怎么用这份文件」，不在本文件重复。

## 写作约束

1. **只写操作**：入口文件、关键函数、验证命令、失败模式。产品决策放 `design/`，历史放 `docs/devlog.md`。
2. **不复制**：命令、版本号、工程实况引用 `AGENTS.md`；规格引用 `design/§x.y`；行为引用测试类名。
3. **一个事实一处**：同一口径只在本文件写一次，其他文件链接过来。发现两处不一致，先删一处再改代码。
4. **可验证**：每条「必须 / 禁止」后面尽量跟测试类名或一条命令；没有验证方式的口径不要写。
5. **短**：单文件超过 700 行先拆主题；新增文件同步 `AGENTS.md` 路由表。

## 领域词典（AI 最容易选错的）

| 概念 | 唯一实现 / 口径 | 不要用 |
|------|----------------|--------|
| 小节号 vs 大节号 | `Course.startSection/endSection` 是**小节号 1–11**；`BIG_SECTIONS` 只用于画间隔 | 把 `startSection=3` 当大节 |
| 寝室房号 | `PowerRoom.room` / `PowerReading.roomName` 是显示房号；`roomId` 是平台数字 id | 用 `roomId` 当房号显示 |
| 课块判重 | `ScheduleDiff.duplicateOf`（同 `mergeKey` + 周次有交集） | 只比 `mergeKey` |
| 「在学期内」 | `ScheduleCalculator.isWeekInTerm(config, week)` | `week in 1..totalWeeks` 散写 |
| 槽位日期 | `ScheduleCalculator.dateOf(...)`；`ScheduleOverrides.dateOf` / `WeekGrid.weekDate` 只是 facade | 再抄一份日期算法 |
| 周次文本 | `WeeksText.parse/format/label`；`ScheduleCalculator.formatWeeks` 是 facade | 自己拼 `1-16` |
| 充值签名 | `CampusPaySign` 是唯一实现；`YktRechargeSign` / `PowerPaySign` 只是 facade | 在 facade 里复制算法 |
| 教务 layui 外壳 | `JwLayuiJson.parseRoot`（ERR / no-open / 未登录 / 非 JSON / code） | 各解析器各写一套 |
| 教务学期下拉 | `JwHtml.selectedTerm(html, selectId, requireTermPattern)` | 整页搜 `<option selected>` |
| 调休 / 停课 | `ScheduleOverride` + `ScheduleOverrides.plan`；渲染层叠加，不写 `courses` 表 | 改课程周次模拟调休 |
| 一次性调课 | `CourseTweak` / `ScheduleUndo`（可撤销） | 当成 `ScheduleOverride` |
| 侧栏电费卡 | `LifeViewModel`（历史名）+ `PowerCardState` | 以为它还有生活页 / 流水段 |
| 校园卡完整设置 | `CampusCardViewModel` + `CampusCardSettingsScreen` | 把侧栏卡片状态塞进设置页 |
| 小组件「当前节」 | `currentSectionNumber` 课间归下一节；`ScheduleCalculator.currentSection` 只算正在上课 | 强行合并两者 |

## 已拆分的大文件（改前先看边界）

| 主题 | 主文件 | 拆出的部分 |
|------|--------|-----------|
| 显示偏好 | `data/prefs/DisplayPrefsStore.kt` | `DisplayPrefsModels.kt`（数据类）、`DisplayPrefsKeys.kt`（DataStore 键）、`DisplayPrefsStoreWenqu.kt`（问渠扩展） |
| 课表仓储 | `data/repo/ScheduleRepository.kt` | `ScheduleBackup.kt`（备份模型 / JSON 格式 / 校验） |
| 校园卡 | `ui/campus/CampusCardSettingsScreen.kt`（只展示） | `CampusCardViewModel.kt`（凭证 / 余额 / 到账 / 充值 / 提醒） |
| 问渠测试 | `domain/WenquRulesTest.kt`（核心规则） | `WenquGrabRulesTest.kt`（蹲点/预报名）、`WenquTestSupport.kt`（共享夹具） |

新增大块逻辑时按这个边界放：**展示不进 ViewModel，键不进业务类，备份模型不进仓储**。
扩展文件里的 `Flow` 属性用 `DisplayPrefsStore.cachedFlow(key)` 包一层——直接 `get() = data.map`
会让 Compose 每次重组拿到新 Flow、反复重订阅。

## 新增规则文件

1. 文件名用主题，不用编号：`login-session.md`、`import-jw.md`。
2. 首段写清「作用域」：什么任务该读、什么任务不用读。
3. 在 `AGENTS.md` 路由表加一行；不在这里维护第二份路由表。
