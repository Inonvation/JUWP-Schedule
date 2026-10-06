# DESIGN §4.11–§4.22 · 技术架构：课表周边功能

> 本文档是 [DESIGN.md](../DESIGN.md) 的拆分件。代码注释里的「DESIGN §x.y」章节号一字未改，索引见 DESIGN.md 的映射表。

## 4. 技术架构（续 · §4.11–§4.22）

### 4.11 调课（快捷操作，2026-09-18，参考拾光）

需求：同学临时换课/停课/补课（「周三的课这周挪到周五」），不必去改那门课的周次再另建一条。
参考拾光「我的-通用设置-快捷操作-调课」：**选「被调整日期」→「调整到日期」+ 模式 + 两侧预览 + 保存**。

与拾光一致的部分：按**日期**操作（不是按课块拖拽），用户只看得到「哪天上什么课」；
三种模式；多周课拆行（见下）。

拾光的存储是 `course_weeks` 关联表，多周课靠「克隆一条新 ID 的 Course + 只挂目标周」拆行。
本项目 `Course.weeks` 是内嵌列，等价映射为**改行 / 插行 / 删行**三种动作，**不动 Room 版本、不加迁移**。

#### 模式语义

设 A = 源日期（被调整日期）在该周的所有课，B = 目标日期在该周的所有课；`w`/`d` 为该日期的周次与星期。

| 模式 | 语义 | 数据动作 |
|------|------|----------|
| 合并 Merge | A 移到 B，两边都留 | A 中每门多周课：原行摘掉 `w`，**克隆一行**（同字段，`day=d`，`weeks={w}`）；单周课直接改 `day=d`（行不动） |
| 覆盖 Overwrite | B 清空后放 A | 先摘掉 B 中每门课的 `w`（`weeks` 因此为空则**整行删除**），再执行合并 |
| 交换 Exchange | A 与 B 互换 | 两侧先各自摘掉本侧的 `w`，再交叉执行（A→B 位、B→A 位） |

- **单周课直接改行**（`day=d`）不改 `weeks`：它是「只在这一周存在」的课，改行不会影响其它周。
- **多周课克隆**必须复制全部字段（`colorIndex`/`isCustomTime`/`customStartTime`/`customEndTime`/`kind`），
  否则调课后颜色与自定义时间会丢。
- **身份去重**：克隆前按 `name|startSection|endSection|teacher|kind`（即 `mergeKey` 去掉 `day`）检查目标日，
  已存在同身份课则**只摘源周、不插新行**——否则「调过去再调回来」会在同一格叠出两条一模一样的课。
  拾光无此检查，本项目补上。
- `weeks` 被摘空的行**整行删除**（拾光会留下孤儿关联；本项目 `weeks` 是必填列，留空行等于一课都看不到的死数据）。

#### 边界与约束

- 日期 →（周次, 星期）：`ScheduleCalculator.weekNumberOf`（**周一为首日**）；`SemesterConfig.firstDayOfWeek`
  目前不参与计算，与全项目其余处一致。
- 不可执行的情形（页面禁用执行按钮并给出原因）：源日期不在学期 1..`totalWeeks` 内、两日期相同、源日期无课。
- 预览列表按**全量课程**算，不受 `courseFilter`（理论/实验筛选）影响——筛选是看图偏好，
  不该让用户以为「被筛掉的课不会被动」。
- 预览的起止时刻走 `ScheduleCalculator.courseStartMinutes/courseEndMinutes`（自定义时间课以 custom 为准，
  AGENTS.md 硬性约定：**只有一条时刻口径**）。
- 执行后**停在本页**（两侧预览自动刷新，便于连续操作）+ Snackbar 汇报；
  覆盖/交换是破坏性操作，**保存前二次确认**（合并无破坏性，直接执行）。

#### 实现落位

| 层 | 文件 | 职责 |
|----|------|------|
| 领域 | `domain/CourseTweak.kt` | `TweakMode` / `TweakPlan` / `CourseTweaker.plan(...)` **纯函数**，无 Android 依赖，JVM 可测 |
| 数据 | `ScheduleRepository.tweakCourses(...)` | `withTransaction` 内：读全量课程 → planner → 删/改/插；返回 `TweakPlan` 供 UI 汇报 |
| UI | `ui/tweak/` | `CourseTweakViewModel`（日期 + 模式状态）+ `CourseTweakScreen`（本页） |
| 入口 | 我的 → 课表 → 调课 | `SubpageScreen.COURSE_TWEAK` 独立窗口 |

范围收窄：**不做跨课表调课**（拾光可选目标课表）。跨课表会把「克隆到另一张课表的配色/学期口径」一起卷进来，
本次不需要；后续要扩就是从「目标课表」下拉加起。也不做撤销与「快速删除课程」（拾光的另一个快捷操作）。

### 4.12 课表分享与日历同步（2026-09-18，P6）

入口：课表页顶栏**分享图标**（眼睛图标左侧）→ 分享弹层，三个去向。

#### 三种去向

| 去向 | 形态 | 说明 |
|------|------|------|
| 一键同步到手机日历 | `WRITE_CALENDAR`/`READ_CALENDAR` + ContentResolver | 写入系统日历 Provider，含课程名/教室/教师，提前提醒时长默认 20 分钟、可在「我的 → 日历同步」改 |
| 导出为 CSV（日历格式） | SAF 另存为（`text/csv`，UTF-8 + BOM） | **Google 日历导入格式**，可直接 calendar.google.com 导入 |
| 导出为 JSON | SAF 另存为（`application/json`） | 复用 `repo.exportJson()`，格式即 4.3，与其他设备互导 |

#### 事件展开口径（`domain/ScheduleExporter.kt`，纯 JVM）

- 一门课 × `course.weeks` 展开为具体日期事件：日期 = 学期周一 + (week−1) 周 + (day−1) 天
  （与 `WeekGrid.weekDate` 同算法，落在 domain 侧重写，不跨层引用 ui 包）；
  周次超出 `semester.totalWeeks` 的丢弃；学期未配置时 CSV/日历同步报错提示先配置学期
- 时刻走**唯一口径** `ScheduleCalculator.courseStartMinutes/courseEndMinutes`
  （自定义时间课自动生效），算不出的事件跳过；结果按日期 + 开始时间排序

#### CSV 格式（Google 日历导入）

表头 `Subject,Start Date,Start Time,End Date,End Time,All Day Event,Description,Location`；
日期 `M/D/YYYY`、时间 `h:mm AM/PM`（`Locale.US` 强制）；`All Day Event=FALSE`；
Description = `教师：xxx`，Location = 教室；含逗号/引号的字段按 RFC 4180 加引号；CRLF 行尾。

#### 日历同步策略

- 写入账户：**可见的可写日历优先**（`pickWritableCalendarId`，2026-09-24）。
  取「第一个可写」在部分设备上会落到隐藏账户——事件在日历 App 里不露脸、提醒也未必响；
  连一个可见的都没有才退到隐藏的可写日历；一个可写的都没有时提示先添加账户。
  `CALENDAR_ACCESS_LEVEL` 不在 selection 里用（部分厂商 Provider 对该列做 selection 会抛
  `IllegalArgumentException`），取回后在客户端筛（`CAL_ACCESS_CONTRIBUTOR`=500 起可写）
- **删除再写入**：先删本 App 认领的事件，再整表插入；教室/时间变更后重同步不留脏数据。
  代价：用户在系统日历里对旧事件的手工编辑会被丢弃，以 App 课表为准（接受）
- **归属标记（2026-09-24 改）**：写 `Events.CUSTOM_APP_PACKAGE` = 本应用包名（该列可写、
  日历 App 不展示）；删除条件 = `customAppPackage = ?` **OR** `description LIKE '%水贝贝课表同步%'`，
  后半条认领升级前写在正文里的历史事件，不认领就会在重同步后堆重复。
  debug 与 release 是两个包名，各自只认领自己写的事件。
  **事件正文只放 `教师：xxx`**（复用 `ScheduleExporter.teacherDescription` 唯一实现），
  无教师时不写该列。此前正文尾部那行「水贝贝课表同步」已去掉（用户要求只留关键信息）
- **考试事件（2026-09-30 钉明）**：考试以 `kind=Exam` + 自定义时刻的课程行随课表一起
  展开（全链路——仓库查询、`ScheduleExporter.expandEvents`、`CalendarSyncer`——都没有按
  kind 过滤），CSV 导出同一份展开。考试事件标题带「（考试）」后缀
  （`ScheduleExporter` 单处拼装），系统日历里区分考试与上课；`ScheduleExporterTest`
  有用例钉住「考试随课表展开 + 标题带标记 + 越界周丢弃」。已知局限：考试日期是从
  weeks+day+学期起点**重构**的（导入时的有损编码，原始日期不落库），学期起点改过或
  当初按估算周导入的考试，日历日期会跟着漂；考试周超出学期总周数的会被静默丢弃。
- 每个事件：标题=课程名（考试见上）、`EVENT_LOCATION`=教室、`AVAILABILITY=BUSY`，另插 `Reminders`
  一条提前提醒（时长见下）。`METHOD` 用 `METHOD_ALERT` 而不是 `METHOD_DEFAULT`
  （2026-09-24）：DEFAULT 的语义是「听账户默认值」，第三方写入没有账户默认值可依，
  写死 ALERT 语义才明确（平台文档说两者都会被处理）
- **提醒写入回读（2026-09-24）**：整批写完后按 `event_id IN (…)` 分块查一次 Reminders 表，
  数出没落库的条数 → `CalendarSyncResult.Success.reminderMissing`。非 0 时提示改口为
  「已同步 N 条课程到手机日历，其中 M 条没写上提醒，手机日历可能不提醒」
  （`CalendarSyncDefaults.syncSuccessMessage`，分享弹层与设置页共用）。
  此前单条提醒插入失败被 `runCatching` 吞掉、UI 一律报成功，问题看不见

#### 日历同步设置（我的 → 日历同步，2026-09-19，全局子页）

- 提前提醒时长：**全局设置**（DataStore `calendar_reminder_minutes`），默认 20；
  值域 0–120、步长 5（0 = 不提醒，跳过写 Reminders）。修改后**不自动重同步**，
  重新同步生效（删除重写语义下旧提醒随之更新）。常量与选项表单一来源
  `domain/CalendarSyncDefaults.kt`
- 一键删除已同步：设置页提供删除入口，只删本 App 认领的事件（确认弹窗，error 色），
  结果 Snackbar 汇报条数
- 同步/删除的日历权限仍由 UI 层运行时申请（与分享弹层同一模式）。
  **拒绝授权时的提示**（2026-09-19 修）：分享弹层在发起权限申请那一刻就先关掉
  （`startCalendarSync` 里统一关，不再只在「已授权」分支关）——`ModalBottomSheet` 是
  比页面高一层的独立窗口，不关它就盖住了「未授予日历权限，无法同步」这条提示，
  用户只看到弹层消失、不知道为什么不生效。
- 页面反馈（2026-09-19）：进页即展示就绪状态——随学期/课表数据自动算出可展开的
  事件数（`SyncReadiness`，学期未配/课表为空时禁用同步行并给引导文案，不等到点了才报错）；
  同步与删除期间整页忙碌（进度条 + 全部动作禁用 + VM 侧 `_busy` 去重防连点）；
  新增「日历权限」状态行（ON_RESUME 重读，未授予时点行发起授权；只有从「同步」行
  发起的授权才在授予后自动续跑同步）。

### 4.13 桌面小组件（2026-09-18，P6；2026-09-20 单条目自适应；2026-09-27 二条目改版；2026-09-28 开水两卡）

规格见 §3.6。技术落位：

| 项 | 值 |
|----|-----|
| 库 | `androidx.glance:glance-appwidget:1.2.0`（要求 compileSdk 35 / AGP 8.6+ / minSdk 23，本项目 35 / 8.7.3 / 26 均满足） |
| 刷新 | 边界闹钟（`setAlarmClock` 精确闹钟，2026-09-24 起，理由见 §3.6）+ WorkManager 15 分钟周期 + 冷启动/数据变更主动刷；后台刷新 = 写状态 + `widget.update()` 双步（光写状态不重绘），首帧快照由 `provideGlance` 直接捕获进组合。15 分钟 Worker 里追加 `LifeWidgetSync.onPeriodicTick`（电费本地镜像 + 校园卡闸门取数，2026-09-27）与 `WaterWidgetSync.onPeriodicTick`（开水两卡本地镜像 + 胖乖/趣智余额闸门取数，2026-09-28） |
| 尺寸 | **`SizeMode.Exact`**（2026-09-20）：宿主给多少 dp 就按多少算，`LocalSize` 量实测宽高后按 `WidgetScale` 分档（Compact / List / Week）；旧版是 `SizeMode.Responsive` 四档就近吸附 |
| 条目 | **四条（按内容拆，2026-09-27 / 2026-09-28）**：4 个 `GlanceAppWidgetReceiver`——课表（`ScheduleWidgetReceiver` + `widget_info.xml`）、合并卡（`CampusCardWidgetReceiver` + `widget_campus_info.xml`）、胖乖开水（`QiekjWaterWidgetReceiver` + `widget_water_qiekj.xml`）与趣智开水（`QzxyWaterWidgetReceiver` + `widget_water_qzxy.xml`），`updatePeriodMillis="0"`；开水两卡**固定 2×2**（`resizeMode="none"`），前两条可拖。删条目会让桌面上旧实例失效，需重新添加——原 `PowerWidgetReceiver` 当天拆出来又合并回去，若桌面上加过电费条目需手动删掉 |
| 状态源 | 课表：`domain/TodayState.kt` 的 `buildTodayState()`（由 `ui/today` 下移，今日页与 widget 共用）。校园卡 / 电费：`ui/widget/LifeWidgetModels.kt` 快照（DataStore `life_widget_prefs` 权威 + Room `power_readings` 最新读数镜像），编排 `LifeWidgetSync`；取数闸门 `CampusBalanceGate` 纯函数（`LifeWidgetModelsTest`）。开水两卡：`ui/widget/WaterWidgetModels.kt` 快照（DataStore `water_widget_prefs` 权威 + `QzxyWateringStore` 本地用水镜像），编排 `WaterWidgetSync`，2 小时闸门复用 `CampusBalanceGate`（`WaterWidgetModelsTest`） |
| 周网格 | `ui/widget/WidgetModel.kt` 的纯函数 builder（`buildWeekModel`）：列取 `ScheduleCalculator.visibleDays`、行取小节号、重叠同列只画一门、高亮列按「今天还有课 → 今天；否则明天」 |
| 边界计算 | `domain/TodayBoundary.kt` 的 `nextTodayBoundaryMinutes()`（纯 JVM，单测钉死） |
| 格式化 | `domain/TodayFormat.kt`（由 `TodayScreen.kt` 提出，`compactPosition` 一并下移）；校园卡 / 电费数字与时刻在 `ui/widget/LifeWidgetModels.kt` 的 `LifeWidgetFormat`（Locale.US） |
| 配色 | 复用 `ui/common/CourseUi.kt` 的 `courseColor()`（16 色粉彩）；进度条为 12 段分色（Glance 无 `fillMaxWidth(fraction)`）；胶囊/强调色（合并卡按钮、开水两卡胶囊与「用水中」大字）跟随「我的 → 通用设置」所选**主题配色**的浅色主色（`widgetAccentColor`，2026-09-28；此前固定品牌青 `#0F7C7C`），切换配色即时重渲染 |
| 数据读取 | `Graph.repository(appContext)` + `repo.ensureDefaults()`，全部 applicationContext，无跨进程 |
| 添加 | `AppWidgetManager.requestPinAppWidget`（API 26+）；不支持时降级为桌面长按引导 |
| 点击 | 课表：整卡 → 今日页、4×4 网格区 → 课表页；校园卡 → 付款码页（未开凭证 → 校园卡设置页）；电费 → 用电统计页；胖乖开水 / 趣智开水：胶囊「去开水」→ 页面并自动开水（`water_start` / `qzxy_start` + `WaterAutoStart` 一次性令牌）、整卡其余 → 页面（route extra 共 `pay_code` / `power_bill` / `campus_card` / `water` / `qzxy` / `water_start` / `qzxy_start`，消费后起 `SubpageActivity`） |

**依赖影响**（加 glance 时实测）：传递引入 `androidx.work:work-runtime`、`androidx.core:core-remoteviews`，
`androidx.compose.runtime` 由 BOM 的 1.7.4 抬到 1.7.8（同 1.7 线，`dependencyInsight` 复核通过，
`androidx.core` 仍解析到 1.15.0，未被抬到 1.17）。
HugeIcons 的 `isTransitive = false` 仍保留（防 `androidx.core` 被抬到 1.17，compileSdk 35 编不过）。

### 4.14 考试安排导入（2026-09-19，与实验课同构的 kind 扩展）

**来源与接口（2026-09-19 实测）**：菜单 `NEW_XSD_KSBM_WDKS_KSAPCX`（考试报名 → 我的考试 → 考试安排查询）。
壳页 `/jsxsd/xsks/xsksap_query`（学期下拉 `select#xnxqid`），数据接口 `/jsxsd/xsks/xsksap_list`
（**不带 `.do`**；带 `.do` 返回 no-open 页）。GET 参数 `xnxqid` + `xqlb`（空 = 全部）+ 分页
`pageNum/pageSize`；响应 `{code, count, data:[…]}`。字段与坑详见 `scripts/README.md` §5.4。
考试由教务**考前数周才录入**，平时 `count=0` 属正常空态，不是错误。

**建模决策：考试是 `CourseKind.Exam`，不是独立实体。** 复用 `Course` 既有字段表达考试，零 schema 迁移：

| 考试属性 | 承载字段 | 说明 |
|----------|----------|------|
| 考试日期 | `weeks`（单元素）+ `day` | 由学期配置 `startDate` 推算：`week = 日期所在教学周`、`day = 星期几`；超出学期范围无法定位 |
| 考试起止时刻 | `customStartTime` / `customEndTime`（`isCustomTime = true`） | 今日页时刻/倒计时口径**自动生效**（行为约定：自定义时间以 custom 字段为准） |
| 网格位置（节次） | `startSection` / `endSection` | 按 §3.5 作息表把时刻映射到**相交小节**（实测考试时段全部与节次边界对齐：08:30~09:55 → 1-2 节、15:45~17:10 → 7-8 节、19:00~20:25 → 9-10 节）；与任何小节都不相交时取最近节次兜底 |
| 考场 | `position` | 教师名一并保留；座位号/场次编号暂不入库（解析层保留字段，模型暂无归属） |

映射逻辑为纯 JVM（`domain/ExamMapper.kt`，单测钉死）。**无法定位的考试（日期超出学期范围或学期未配置开学日）在导入时跳过并在结果里说明**，不静默丢弃也不落错周。

**历史/未来学期的考试（2026-09-19 补充）**：导入的学期不必是当前配置的学期。定位分两级：
先按当前学期配置推算（精确口径不变）；失败（日期不在 `1..totalWeeks`）且学期号可识别
（`yyyy-yyyy-[1|2]`）时，按**估算开学日**兜底——教务不提供历史学期的起止日期（课表页快照实证
只有「第 N 周」无日期），只能按校历惯例估算：第 1 学期取「含 9 月 1 日那一周的周一」、
第 2 学期取「含 3 月 1 日那一周的周一」（如 `2025-2026-2` → 2026-02-23）。估算周次可能与
实际相差一两周，因此：确认弹窗必须注明「N 场按估算开学日（yyyy-MM-dd）定位，可能与实际
相差一两周」，由用户知情确认后落表；学期号无法识别时维持跳过。注意考试落在**该学期自己的**
周次轴上，在课表网格里与当前学期同号周共用一行——这是单学期配置模型的固有限制，靠弹窗
披露而不是静默混排。

**导入链路**：教务导入页（JwImportActivity）新增「考试安排」入口 → WebView 打开壳页 →
注入 JS `fetch('/jsxsd/xsks/xsksap_list?…')`（同源带 cookie，拿 JSON）→ Kotlin 侧
`ExamScheduleParser` 解析 → `ExamMapper` 转 `Course(kind = Exam)` → 复用 `ImportTargetDialogHost`
（带学期口径展示）→ `importParsedCourses`。确认弹窗默认**合并**（考试 mergeKey 含 kind，重复导入自动去重；
覆盖会连普通课程一起清空，不作默认）。导出的 JSON 课表（CourseExport）天然携带考试条目，互导不受影响。
**开在考试页（2026-09-30）**：`JwImportActivity.start(startAtExam = true)` 只改「登录后自动
落哪一页」（理论课表页 → `xsksap_query`），抽取/写库/确认弹窗全不变；考试页的导入卡与
考试变动通知的确认口走这条路。**不加这个参数**，窗口一落理论课表页就会自己跑起
`autoImportOnTheoryReady` 的一键导入（理论+实验），跟「导入考试安排」不是一回事。

**按目标课表定位（2026-09-19）**：多课表各有自己的开学日（§4.9），考试周次映射必须在确认弹窗
选定目标课表后按**目标课表的学期配置**重算——解析时用当前课表配置出的计数只作预览。
站在 A 课表把 2025-2026-2 的考试导进 B 课表时，只有按 B 的开学日定位才落得准；
重映射后实际写入数以完成提示为准（可能与预览差个别场）。

**卡片区分（实验 + 考试共用规格）**：类型标识从「右下角两个小字」升级为**右上角图标徽章**
（圆形半透明白底 + 深色 HugeIcons 线性图标：实验 = 烧瓶、考试 = 文档笔），网格卡与单节小卡分别缩放；
考试卡叠加**白色实线描边**（区别于普通课的虚线描边），在色块海里先靠形状差异扫出来，再靠图标确认类型。
颜色仍按课程名分配，不挪用类型语义。

### 4.15 成绩按学期存储（2026-09-19，我的 → 成绩查询）

**来源与接口（2026-09-19 实测）**：菜单 `NEW_XSD_XJCJ_WDCJ_KCCJCX`，表单页 `/jsxsd/kscj/cjcx_frm`，
数据接口 `/jsxsd/kscj/cjcx_list`（**不带 `.do`**），GET 参数 `kksj`（学期，空 = 全部学期）+
分页 `pageNum/pageSize`。成绩**不是课程**（没有排课语义），不进课表 `Course` 模型，
独立建表、独立 Repository、按学期整体存储。

| 项 | 决策 |
|----|------|
| Room | v3 → v4：新表 `scores`（`term` 学期、`name`、`credit`、`score`/`scoreStr`、`gradePoint`、`category` 等），`CREATE TABLE` 非 destructive；成绩**全局归属学生**，不挂 timetableId |
| 写入口径 | **按学期替换**：导入的学期先删后插，重复导入不产生重复行；未涉及的学期不动 |
| 导入 | 我的 → 成绩查询 → 页内「导入」→ 复用 JwImportActivity（成绩模式）→ WebView 打开 `cjcx_frm` → 注入 `fetch` 全部学期成绩 → 确认弹窗（列出学期与条数）→ 按学期替换入库。**成绩模式登录成功后自动跳 `cjcx_frm`**（2026-09-19 修正：此前与课表模式共用「跳理论课表页」的固定导航，成绩导入每次都要手动再点一次入口）；**落到 `cjcx_frm` 就自动跑一次导入**（2026-09-26，与课表模式的「落到理论课表页自动导入」同构：`autoImportOnScoreReady`，判 `JwUrls.isScoreQueryUrl`），不再要点「导入成绩」，按钮留作失败后的重试入口。成绩页不属于 `JwSchedulePage`，所以单独走 URL 判定而不是并进 `schedulePageKind`——并进去会让课表页那套适配样式与自动导入误套到成绩页。**2026-09-27 起另有自动导入（§4.29）**：首启配置完成与冷启动过闸门时走 OkHttp 直抓（复用同一份 `ScoreParser` 与按学期替换口径，不依赖 WebView），页内按钮留作会话失效时的后路 |
| 备份 | 课表数据页导出的 JSON 顶层携带可选 `scores` 段（§4.3），换机/重装随课表一起恢复；导入时成绩**整体替换**（先清后插），确认弹窗注明「N 个学期共 M 条成绩」；无 `scores` 段或为空数组则不动成绩 |
| 展示 | 成绩查询二级页：**按学期 / 按学年两种分组**（分段按钮切换，各自记忆选择），两种视图首位都有**「全部」**chip——汇总为全量聚合（总加权平均分 / 总绩点 / 总修得学分 / 总门数），列表带分组小节头；「全部」视图不提供删除。按学期：学期 chips（默认最新有数据学期）+ 学期汇总 + 课程卡列表。按学年：`2024-2025-1/2` 归并为学年 `2024-2025`，chips 带年级标签（**最早学年 = 大一**，依次大二/大三/…），汇总为学年内全部学期聚合，列表内按学期小节分组；无法解析学期号的学期各自成组、不带年级标签。汇总口径：课程数 / **修得学分**（数值分 ≥60 或等级制绩点 >0 的课程学分和，任选课照常计入）/ 加权平均分 / 平均绩点；**加权平均分与平均绩点默认不计任选课**（2026-09-19 用户拍板：通识任选课 + 专业任选课，判定 = 课程性质含「任选」，强智 `kcxzmc` 如「通识任选课」「专业任选课」；专业选修/限选等其它性质照常计入，修得学分不受影响），被排除门数在汇总注释行注明；汇总卡内提供**「任选课计入统计」开关**（默认关 = 排除，附注「默认排除：本校综测同样不计任选课」——作者学校的综测口径，公开仓库用户可自行打开；开启后两项平均恢复计入，注释行改显「已计入 N 门任选课」，DataStore 持久化）；汇总卡数值下方展示**计算公式**——加权平均分 = Σ(成绩×学分) ÷ Σ学分、平均绩点 = Σ(绩点×学分) ÷ Σ学分，有值才显示对应行，**数值旁标注「仅供参考」**（换算口径可能与教务正式单据有出入，2026-09-19 用户要求）；`pendingReview`（评教未完成被锁定）单独呈现且不进任何统计；等级制成绩只展示 `scoreStr`。列表**排序**（2026-09-19）：顶栏排序菜单三档——默认（入库顺序）/ 成绩从高到低 / 绩点从高到低，无数值分（等级制、待评教）排末尾；排序只作用于学期分组内部，不打乱学期/学年小节结构。排序档与按学期/按学年分组选择写入 DataStore（`score_sort_mode` / `score_group_by_year`），重进页面不重置 |
| 空态 | 无任何成绩时给「去导入」引导，不显示空表格 |

### 4.16 快捷方式（2026-09-19，P6）

**存储**：不进 Room。DataStore 两个键：`shortcuts_enabled`（bool，默认 true）、
`shortcuts_json`（整个列表一个 JSON，模式同 `view_prefs_json`）。
缺省或脏 JSON 一律回退内置预设列表——自己写自己的数据，读路径不抛异常。
读写路径都过 `migratePresets`，流带 `distinctUntilChanged`（DataStore 任何键的写入
都不惊动值没变的观察者）。

**模型**（`domain/Shortcuts.kt`，纯 JVM 可测）：
`ShortcutItem(id, name, uri, pkg, activity, presetIndex = -1, icon = "")`

- `presetIndex >= 0` 表示预设槽（0=拼多多 1=淘宝 2=菜鸟），图标与「重置」都按它派生；
- `icon` 只对自定义条目生效，存 UI 图标注册表的 key（`ui/common/ShortcutUi.kt`，14 项，
  空/未知 = 链接图标）；带默认值，加字段前的旧 JSON 照常解码；
- `migratePresets()`：**预设目标自动迁移**——存储槽位与 `PRESET_HISTORY` 某历史表的
  目标字段一致（= 用户没改过目标）就升级到当前预设（未改名整条换新、只改名保留名字），
  改过目标的槽位与自定义条目不动；不在存储记版本号，与所有历史值都不匹配即视为
  自定义，幂等、跨版本跳迁成立。**维护契约：改任何预设的目标/名称前，先把旧表追加进
  `PRESET_HISTORY`**（当前为空 = 发布态恒等）；
- `validate()`：名称必填；`uri` 与 `pkg(+activity)` 至少一项；activity 必须配包名；uri 必须含 `://`；
- `resolveLaunchPlan()` 把条目折算成三种拉起口径，今日页与设置页「测试」共用同一执行层：

| 口径 | 条件 | 执行 |
|------|------|------|
| `ExplicitComponent` | activity 非空 | `setClassName(pkg, activity)` 直达（菜鸟跳开屏广告就靠这个） |
| `UriView` | uri 非空 | `ACTION_VIEW` + `setPackage`；失败去包名重试一次（lightlife 兜底链） |
| `LaunchPackage` | 仅包名 | `getLaunchIntentForPackage` |

**执行层**（`ui/common/ShortcutLauncher.kt`）：Intent 构造收拢在 `ShortcutIntents`
（今日页拉起、设置页测试、桌面 Pin 三方共用，落点保证一致），异常统一翻译成用户可读串
（null = 成功）。**桌面 Pin**（`ShortcutPinner`）：`requestPinShortcut` + 同一意图，
预设钉品牌资源图，自定义钉首字圆底位图（Compose ImageVector 无法离屏渲染）。

**包可见性**：manifest `<queries>` = 三个预设包 + `pinduoduo` scheme +
**MAIN/LAUNCHER 意图**。三个 package 解决「已装误报未安装」；MAIN/LAUNCHER 让所有有
桌面入口的 App 可见——既是编辑表单「从已安装应用选择」的数据源，也让自定义
「只填包名」的快捷方式能拉起。无桌面入口的应用仍靠 try/catch 兜底。

**图标**：预设用品牌 webp（`ic_preset_pinduoduo/taobao/cainiao`，拼多多/淘宝两枚取自
lightlife，菜鸟自绘矢量）；自定义条目从注册表选 HugeIcons；不按名称哈希取图。

### 4.17 调课检测与首启自动导课表（**2026-10-04 重立**；旧版 2026-09-19 上线、09-24 下线）

> 历史：本功能 2026-09-19 上线（独立凭证配置 + WorkManager 后台自动登录），09-24 用户判定
> 「检出差异的价值不足以抵消配置与后台登录的成本」整体删除（Room v10 DROP 两张基线表）。
> 重立的前提是当年的两个成本项已不复存在：统一会话层（§4.27）免配置，OkHttp 直取链
> （§4.29/§4.33）成熟。基线改走 filesDir JSON，**不动 DB 版本**。

**首启自动导课表**（2026-10-04）：引导第 1 步统一认证登录成功后，`startBgSync` 在成绩/学业
之外加第三路——`ScheduleSync.importIfEmpty` 只在**目标课表为空**时直取理论+实验两页并覆盖写库
（空表上覆盖与合并等价，覆盖保证基线=导入内容），教材钩子同手动导入。课表非空一律让位
（Skipped）——**静默导入是一次性的首启便利，绝不周期性覆盖用户已编辑的课表**。三项全部
settle 后结果落盘（`onboarding_sync_outcome_json`），主界面在 `onboarding_seen` 后弹一次
「同步完成」结果弹窗（成功带查看入口、失败给手动导入出路），看过即置 consumed。startAtJw
（改密码重进）不是首启，不写结果不弹窗，完成页状态行足够。

**调课检测**（开关默认关，入口：课表 hub「调课检测」行 → 处置页 `SCHEDULE_CHANGES`）：

- **抓取**：`ScheduleSync.fetch`（OkHttp + `CasSession`）依次抓理论课表页与实验课表页，
  解析用 `QiangzhiScheduleParser.parseFromHtml` / `SyjxScheduleParser.parseFromHtml`（与
  WebView 注入**同一套 colspan/rowspan 算法**的备用路径），合成复用 `OneClickImport.combine`。
  学期以理论页 `select#xnxq01id` 选中项为准；带学期请求时校验「请求学期 = 返回学期」，
  不一致报错（同脚本纪律）。两页之间 `interRequestDelayMs()` 随机间隔。
- **基线**（`ScheduleBaselineStore`，filesDir JSON `schedule_baseline.json`）：语义是
  **「上次与教务达成一致时的课表状态」**，不是教务快照——用户手动改课只动 Room，基线不动。
  三个推进点：导入写库成功（`importParsedCourses` / `importJson` 收口，防新调用点绕过）、
  合并差异成功、检查首跑（老用户升级无基线，用第一次抓到的教务状态建立，不通知）。
  基线**允许存空列表**（没课的学期是合法状态），但只由成功路径写入；基线与待处置缓存
  都只存 Theory+Lab（教务课表页抓不到考试，留在基线里会把考试报成「教务删除」）。
  删课表/清空课程时基线与待处置缓存一并清。
- **差异**（`domain/ScheduleDiff`，纯 JVM）：指纹 = name|teacher|position|day|节次|周次|kind，
  **只含教务字段**——colorIndex/remark/自定义时间不参与，否则本机数据抖动报假差异。
  无序多重集合比较；同名同 kind 恰好一删一增配成「调整」（多删多增宁拆开不错配）。
  冲突判定：REMOVED/MODIFIED 在当前课表找不到指纹一致条目（用户改过）、ADDED 在当前
  课表已有同门课（用户加过）——冲突条目默认不勾，采纳与否由用户逐条决定。
- **周期检查**（`ScheduleSync.check` 搭 `ScoreAlertReminder` 周期任务**第四路**，检查间隔独立
  设置（`schedule_alert_interval_hours`，默认 24h——每次核对抓两张整页 HTML，低频起步），
  周期任务按四链最小档叫醒、各路用自己的间隔闸门，不新开 WorkManager 任务——这是与
  旧版后台自动登录的本质区别）：
  开关关不发请求；活跃课表 `Timetable.term` 为空不检查（无学期钥匙不猜）；实验页结构
  不可信时整体放弃（硬比对会把整批实验课报成删除）；理论 0 条+实验 0 条视为假期空态
  不动基线。**检查成功不推进基线**（与 `ExamSync` 相反）：基线只在合并/重新导入后推进。
- **重复打扰双闸**：差异指纹（`fingerprintOf`）与「已阅指纹」相同（用户忽略过）或与
  「待处置缓存」指纹相同（已提醒未处置）都不再发通知；教务再出新差异指纹才变。
- **处置**（`ScheduleChangesScreen`）：读待处置缓存（进页不重抓），逐条勾选 →
  `applyScheduleChanges` 事务内逐条删/改/增（指纹匹配不上就跳过不硬套；新增防 mergeKey
  重复；修改保留原行 id/配色/备注）→ 基线推进为应用后的实际课表 → 本次完整差异记已阅
  → 清缓存。未勾的差异客观上仍存在，下次检查以剩余差异重新报出。「忽略全部」= 写已阅
  指纹 + 清缓存，基线不动。
- **通知**：channel `schedule_alert`、id/tag/requestCode 1011/`schedule_alert`/3011（与选课的
  1010/3010 错开——requestCode 撞了会互相改写落点），点通知落处置页。**绝不写库**——后台静默改课表是红线，差异的每一条都要用户勾选确认。

单测：`ScheduleDiffTest`（配对/冲突/指纹）、`ScheduleSyncTermFromHtmlTest`（学期抽取）。


### 4.18 共享单车扫码（2026-09-20，P6；UI 规格见 §3.9）

`https://www.kvcoogo.com/ebike?id=<车号>` 的二维码在微信里命中运营方配置的
「扫普通链接二维码打开小程序」规则，完整 URL 以 `q` 参数透传给小程序页面，
小程序自行解析 id 加载对应车辆——**改 id 生成的二维码前缀不变、依然命中**。

- `domain/EbikeQr.kt`（纯 JVM；车号口径 2026-09-23 由「尾部 3 位」改为「完整车号」）：
  - `normalizeCarInput(raw: String): String` — 输入框原始文本 → 只留数字、上限 12 位。
    不再剥模板前缀：整条校园车号（`100000669`）原样留着，与旧版出码结果一致。
  - `resolveCarNum(input: String): String?` — 规整输入 → 完整车号。1~3 位补 `100000` 前缀
    （校园车队）；6~12 位按完整车号；其余（4~5 位、空串）返回 null。UI 层唯一口径。
  - `inputPrefix(input: String): String` — 输入还停在尾部（≤3 位）时返回 `100000`，
    否则返回空串（前缀提示自己消失，不会给出一个错的隐含前缀）。
  - `bikeUrl(carNum: String): String?` — 完整车号 → `https://www.kvcoogo.com/ebike?id=<车号>`；
    非 6~12 位数字返回 null（宁可拒掉也不出一张扫不开的码）。
  - `tailOf(carNum)` — 展示用后三位。
  - `qrMatrix(url: String): BitMatrix` — zxing 720px、容错 M、白边 1 模块。
  - `decodeRecent(json: String?)` / `encodeRecent(...)` — 最近车号列表的 JSON 序列化
    （倒序去重、上限 8），脏 JSON 回空列表；读旧数据时给纯 3 位条目补 `100000` 前缀。
- `domain/BikeNearby.kt`（纯 JVM，2026-09-23）：附近车辆接口的响应解析与后续处理——
  `NearbyBike` 模型、`parse(raw, centerLat, centerLng)`（宽容取值：数字/字符串两种写法都认，
  车号非数字、坐标越界一律丢弃；`errorCode != 0` 归为服务端错误；`carList` 缺失算结构不符，
  不当成"没车"）、`cluster(bikes)`（按 `givecarName` 分组，为空回落 4 位小数网格）、
  `reanchor`（换参照点重算距离并重排）、`samplePoints`（中心 + 八方位 700 米，补接口
  只给最近 20 辆的短板）、`distanceMeters`（本地 haversine，不信服务端 `distance` 字段，
  口径只有一处）、`formatDistance`、`correctSiteName`（停车点名纠错 + 纯数字占位名当没写）、
  状态推导（可用 / 电量低 / 不可用 / 离线）。
- `data/kqcx/KqcxBikeClient.kt`（2026-09-23）：一条 form POST（`lat` / `lng` / `deviceType=1`），
  裸 OkHttp，只做请求与 HTTP 状态归一，业务判断交给 `domain/BikeNearby`。
  接口地址、参数、响应字段与坐标基准见 §4.23。
- `domain/EbikeFreeRide.kt`（纯 JVM，2026-09-22；2026-09-23 / 2026-09-24 两次改口径）：
  免费时长倒计时的时刻与文案——`freeEndMillis`（起点 + 15 分钟）、`leadReminderAt` /
  `endReminderAt`（两个提醒点）、`nextReminderAt`（下一个未过点，供排精确闹钟）、
  `isLeadDue` / `isEndDue`（该不该发：提前量那条只要没结束就补发，结束那条有
  `END_WINDOW_MS` 的 5 分钟迟到窗口）、`leadDedupKey` / `endDedupKey`（去重键，带计时起点）、
  `remainingSeconds` / `progressFraction`（倒计时与进度条）、`formatRemaining`（`mm:ss`）、
  `coerceLead`（1~5 吸附）、`isActive`（有效期；起点 0 = 无计时）、
  `NotificationIds`（三条通知 id 与两个 channel id，**不与上课 / 作业提醒撞号**，单测锁住）、
  `COUNTDOWN_TITLE` / `COUNTDOWN_TEXT` / `LEAD_TITLE` / `leadText` / `END_TITLE` / `END_TEXT`（文案）。
- `data/prefs`：`ebikeCardEnabled`（今日页卡开关，默认开）、`ebikeUseMode`
  （使用方式，**默认小程序方式**；§3.9）、`ebikeAutoSave`
  （生成即存相册，**默认关**，用户拍板）、`ebikeRecentIds`（JSON 列表）经
  `ScheduleRepository.displayPrefs` 既有合并链透出；`ebikeBurnAfterScan`（扫完即焚，
  **默认开**）、`ebikePendingDelete`（待焚毁 key 集合，stringSet）为出码页私有
  **直接读写 store**，不进 DisplayPrefs 合并链（待删集合是高频翻搅的过程态，
  不该进 UI 向快照；未来若其它页面要读焚毁开关再接线）。
  地图页的 `ebike_location_asked`（定位权限只自动申请一次的标记，§3.9）同样直接读写
  store：它只在进页那一刻读一次，进 UI 向快照没有意义。
- `ui/ebike/`：`EbikeViewModel`（生成/保存/焚毁）、
  `RideScreen`（**唯一主页**：地图 + 可拖车辆面板 + 页面底部常驻动作区，三态由数据推导，
  见 §3.9）
  + `RidePanels.kt`（`RideActionArea` 动作区四态：找车默认 / 车辆卡 / 骑行仪表盘 / 结算卡
  ——上区升起、下区常驻；`RideBikePanel` 可拖面板：车辆列表 / 还车点；`RideCarNumberSheet`
  车号与生成乘车码；`RideFilterSheet` / `RideModeSheet` 弹层；共用小件）、
  `EbikeActions.kt`（跨页共用动作：页内提示、通知权限门、拉起微信、打开官方 App、免责）、
  `EbikeFreeRideReminder` / `EbikeFreeRideService` /
  `EbikeFreeRideNotifier`（免费时长提醒链路，见 §3.9）、
  `BikeMapViewModel` + `OsmMapView` + `BikeMarkerOverlay`（地图与车辆数据；车行「开锁」、
  骑行态的锁车 / 还车都从这里发起，**仅账号登录方式**）、
  `KvcxRideController` + `KvcxRideState` + `KvcxAction` + `KvcxReturnSummary`（**本机用车的唯一
  编排**：骑行状态与开锁 / 锁车 / 解锁继续骑 / 还车，全页共用，确认文案
  `kvcxConfirmDialog` 同在此文件；业务依赖 `KvcxRideSession`、
  副作用 `KvcxSideEffects` 都是接口，为的是 `KvcxRideControllerTest` 能假造）、
  `BikeLocator`（一次性定位 + 页面可见期间的连续定位流，§3.9）。
  选中车号不再需要转发层：车号直接填进抽屉的输入框（2026-09-29 起，旧版那条
  `EXTRA_PICKED_CAR_NUM` Activity Result 通道已删）。
  落相册走 MediaStore `Pictures/水贝贝`（API 29+ 免权限；26–28 需 `WRITE_EXTERNAL_STORAGE` 运行时申请）。
- 打开快趣出行（2026-09-21 首版；2026-09-23 收敛为一级）：只剩
  `getLaunchIntentForPackage("com.kvcoo.go")` → 桌面启动意图（启动页，导出无门槛）；
  未安装给一句 Snackbar。助手通道、`WRITE_SECURE_SETTINGS`、`KILL_BACKGROUND_PROCESSES`、
  `queries` 包可见性、未安装下载引导弹窗**全部删除**（理由见 §3.9）。
- 免费时长提醒 → App 通知 + 前台服务常驻倒计时（2026-09-22 首版通知；2026-09-23 改系统日历；
  **2026-09-24 改回 App 通知**，用户拍板——日历 App 发提醒时 App 自己没法展示倒计时）：
  `ui/ebike/EbikeFreeRideReminder.kt` 是调度中枢——`startRide` 清上一轮已发键、写
  `ebikeRideStartAt`、排下一个精确闹钟、起常驻服务；`endRide` 清起点与已发键、撤闹钟、
  停服务、清通知栏；`check` 幂等核对（发该发的提醒 → 重排闹钟 → 按需起停服务 → 收过期状态），
  由闹钟落点 / 进页 / 冷启动 / 周期核对任务共用（周期任务**只在计时期间存在**，15 分钟，
  计时结束即撤，平时零唤醒）。闹钟用 `AlarmManager.setAlarmClock`
  （系统级精确、到点唤醒设备、不需要任何特殊权限，与上课提醒同一手法；requestCode 4003，
  与上课的 4002 错开）。`ui/ebike/EbikeFreeRideService.kt` 是前台服务（`specialUse` 类型），
  只负责通知栏常驻倒计时——**亮屏时每秒**重发一次通知文案（`EbikeFreeRide.countdownText`）
  刷新剩余时间，**灭屏不刷**（协程阻塞等屏幕亮，点亮补刷一次），不用系统 chronometer
  （真机实测澎湃OS 面板静止时不重绘，数字不动）；
  到点提醒**不**由服务里的协程负责（屏幕关闭后 CPU 挂起，`delay` 会迟到几分钟）。
  通知构建与两个 channel 在 `ui/ebike/EbikeFreeRideNotifier.kt`（倒计时 LOW 无震动 / 提醒 HIGH
  **带震动**、category `EVENT`；带震动的提醒 channel 用新 id `_v2`，早期无震动的旧 id 由
  `ensureChannels` 清掉——channel 建出来后震动改不动，「删掉重建」也因 delete 异步而无效）。
  两条提醒除通知外还各发一次**直接震动**（`ReminderVibration.vibrate`，2026-09-30；
  **2026-10-06 起收在通用设置「通知强提醒」开关后面，默认关**，见 §3.3 该节）：
  渠道震动要 `VIBRATE` 权限才生效（manifest 此前一直没声明，用户报的「没有震动」就是它，
  已补），直接震动则连渠道被用户关掉都还能震——提醒关乎计费，宁可多一路信号。
  **横幅（heads-up）弹不弹由系统 / ROM 决定**：App 只能给 HIGH 渠道 + 不静音，
  设置弹层「提醒」区的「通知与悬浮」是唯一的补救入口（进系统通知设置开横幅 / 悬浮 + 震动）。
  prefs 键：`ebike_free_reminder_enabled` / `ebike_free_lead_minutes` / `ebike_ride_start_at` /
  `ebike_free_notified_keys`（stringSet，已发键）。
- 「精确倒计时」（微信通知校准）已整套删除（2026-09-30，用户要求）：`ui/ebike/WechatRentListener.kt`
  （`NotificationListenerService`）、`domain/WechatRentNotice`、设置弹层的开关与授权提示、
  `AppPermissions` 的通知使用权查 / 跳两函数、manifest 里的服务声明都没了，**别当漏项加回来**。
  `EbikeFreeRideService.start()` 的起停判据仍是**起点是否变化**（变了就重新 `onStartCommand`
  重建 tick）——换车重新计时靠它；**不要**退回「只看服务在不在跑」的幂等，那会让「上一轮没结束
  就换车」时倒计时不重置（2026-09-24 修的 bug）。换车时 `startRide` 还会清掉上一轮挂在
  通知栏的提醒。
  已删除（2026-09-24）：`data/calendar/EbikeCalendarEvents.kt`、`ebike_free_event_id`、
  `水贝贝骑行提醒` 日历标记、日历权限申请、一次性到期清理任务。
- 扫完即焚（2026-09-20）：`EbikeQrBitmaps.saveToGallery` 成功返回 `SaveResult.Saved`
  （附待焚毁 key：`m:` 前缀 MediaStore uri / `f:` 前缀文件路径，编码在 `EbikeQr` 纯 JVM 可测）；
  保存方（手动或自动）按 `ebikeBurnAfterScan` 把 key 并入 `ebikePendingDelete`；
  页面 ON_RESUME 触发 `EbikeViewModel.burnPending()`，逐 key `deletePending`
  （29+ `ContentResolver.delete` 自己的 uri，26–28 `File.delete`），成功才移出记录，
  失败保留下次重试；防重入 + 开关关闭时不删。
- 依赖：`com.google.zxing:core`（单 jar 无传递）、`org.osmdroid:osmdroid-android`
  （AAR，POM 无传递依赖；瓦片源与初始化三个坑见 §4.23）。
- 测试：`EbikeQrTest`（URL 拼装/校验/输入规整 `normalizeCarInput` 与 `resolveCarNum`/
  前缀提示/BitMatrix 参数/历史序列化 roundtrip 与旧 3 位数据的兼容读取/
  待焚毁 key 编码与解析/待焚毁集合合并上限）。
- 测试：`BikeNearbyTest`（响应解析容错/服务端错误码/空列表/坐标与车号非法丢弃/
  聚簇口径/距离计算与排序/状态推导/距离文案）。
- 测试：`EbikeFreeRideTest`（提醒点计算/下一个未过点/迟到窗口两档/去重键带起点/
  通知 id 不撞号/提前量边界与吸附/剩余秒数与进度条/倒计时文案/有效期判断/
  提醒文案按实际剩余）。

### 4.19 校园卡付款码（2026-09-20，P6；UI 规格见 §3.10，默认关闭）

新中新「慧新e校」移动平台（`https://yktwx.juwp.edu.cn/plat/`，前端 Vue2+Vant）的
学号登录可完全用 HTTP 复现：**登录密码走「安全键盘」，本质是一次字符替换**——服务端每次
下发 10 张数字字形图（base64 PNG，跨会话固定同一组、顺序随机）与等长的乱序字符集
（`numberKeyboard`），点第 i 个键发第 i 个字符；提交时拼 `密文$1$uuid`，服务端凭 uuid 还原。
传输层无任何密码学保护，防御目标是偷看而非抓包——**因此复现合法且可行，2026-09-20 实测通过**
（Python 侧两轮键盘会话字形 MD5 全命中、双射成立、登录 0.9s、取码 10 码/批、JWT
`logintype=snoKeyboard`）。

#### 接口与链路（`data/ykt/YktClient.kt`，裸 OkHttp）

| 步骤 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 1 | GET | `/berserker-secure/keyboard?type=Number&order=1` | 取 uuid + 乱序字符集 + 10 张字形图 |
| 2 | POST | `/berserker-auth/oauth/token` | OAuth2 password 模式，`Authorization: Basic mobile_service_platform:mobile_service_platform_secret`（前端硬编码，非机密） |
| 3 | GET | `/berserker-base/user` | 用户档案（本期不展示，仅登录校验用可选） |
| 4 | GET | `/berserker-app/ykt/tsm/codebarPayinfo` | 付款账户列表，取 `code=CARD` 的实体卡账户 |
| 5 | GET | `/berserker-app/ykt/tsm/batchGetBarCodeGet?account&payacc&paytype` | 一批 10 个 20 位付款码，`expires=17280` 秒/个 |

**余额与流水（2026-09-20 实测；数据源 = `/campus-card/` 独立前端应用「校园一卡通」，与 `/plat/` 同后端同 token）**：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/berserker-app/ykt/tsm/queryCard` | 卡列表 + 余额：`data.card[]` 每卡 `db_balance`/`unsettle_amount`/`elec_accamt`（均分）/`expdate`/`lostflag`/`freezeflag`。**余额口径 = `(db_balance + unsettle_amount) / 100`**（campus-card 前端同款换算） |
| GET | `/berserker-search/search/personal/turnover?size&current` | 消费流水（分页）：`data.records[]` + `data.total`/`data.pages`；**不带任何时间参数**（见下），时间倒序全量 |
| GET | `/berserker-search/statistics/turnover/count` | 全量汇总：`data.expenses`/`data.income`（分）；**带时间参数同样清零，不可用**（月汇总由客户端求和） |

- **时间参数实测矩阵（2026-09-20，该部署后端与 H5 前端版本不匹配）**：`timeFrom`/`timeTo`
  （campus-card H5 发的参数名）单独传被忽略、组合传（`YYYY-MM-DD`/`YYYYMMDD`/带时分秒/
  历史月/参数反传）**直接清零**；`dateFrom/dateTo`、`startDate/endDate`、`beginDate/endDate`、
  `time`（单日/月值）、`jnDate` 全部无效。**月过滤在客户端做**：无参全量拉（`size=100`
  被尊重，实测 726 条约 10 个月 / 8 页），VM 缓存后按 `jndatetimeStr` 前缀切月 + 求和；
  翻页直到覆盖所选月或拉完全量。

- 流水 `records[]` 字段（实测 30 个，UI 只取子集）：`tranamt`（分）、`typeFrom`（`"1"`=收入/
  `"2"`=支出）、`turnoverType`（类型名：消费/二维码支付/充值…）、`jndatetimeStr`（交易时间）、
  `remark`/`resume`（摘要）、`cardBalance`/`ebagamt`（**交易后余额快照**，分）、`locationName`
  （商户/终端）、`orderId`。
- 探测存档：`berserker-search` 服务在 `/plat/` 前端零出现，只存在于 `/campus-card/`
  应用（`frontInfo.getFrontConfig.campusCard` 外链）；该应用 chunk 请求必须带
  `Referer: …/campus-card/`（否则回退页），app.js 的 chunk hash 表随请求轮换（含 `31d6cfe0`
  空 chunk），静态扫描需多轮采样合并——复探时勿只抓一轮。

- 步骤 2 表单字段：`username / password / grant_type=password / scope=all / loginFrom=h5 /
  logintype=sno / device_token=h5 / synAccessSource=h5`（Content-Type 为
  `application/x-www-form-urlencoded`；`synAccessSource` 是前端 axios 拦截器追加的，漏了不报错但保持一致）。
- 步骤 3–5 鉴权头 `synjones-auth: bearer <token>`；所有响应可能带 UTF-8 BOM（`\uFEFF`），
  解析前剥离。错误码：HTTP 400 = 凭证错（`Bad credentials`，**与密文构造错误同貌**——先怀疑
  构造再怀疑密码）；`code 8001` = 学号绑多账号（只报错不自动选）；`8002/8003` = 触发图形
  验证码（**绝不重试**，引导浏览器登录一次）；401 = token 失效（重登一次）。

#### 字形映射（`domain/YktKeyboard.kt`，纯 JVM）

- `HASH2DIGIT`：**全量 32 位 MD5 → 数字**（2026-09-20 两次会话实测一致；8 位前缀在两张表
  碰撞时才会歧义，全量表零成本消除）。识别方法与失效重识别流程见 `docs/`（本地）
  或外部技术文档：alpha 合白底 → 裁包围盒 → 保比例归一 → 与 NimbusSans 字形 IoU 匹配，
  孔洞数与宽高比交叉复核。
- `buildMapping(images, chars)`：逐张查表组 `digit→char`；**未知哈希直接抛
  `YktProtocolException`**（平台换字体的信号，宁报错不猜）；结果必须是 0-9 双射，否则同上。
- `buildPasswordField(password, mapping, uuid)`：逐位替换 + `"$1$"` + uuid；
  入参含非数字字符抛 `IllegalArgumentException`（登录键盘只映射 0-9）。
- `looksLikeSampleInvariant(keyboard)`：协议自检——服务端响应自带 `password` 字段 =
  `numberKeyboard + "$1$" + uuid`，每次登录顺带校验，不一致抛协议漂移（零成本的改版探测器）。

#### 登录编排（`data/ykt/YktRepository.kt`）

- token **仅内存缓存**（`@Volatile`），进程存活期内复用；401 时重登一次。不落盘——
  token 有效期 6047999 秒（≈70 天），缓存意味着磁盘上多一份可直接消费的凭证，放弃。
  **2026-09-24（§4.27）**：改为落盘复用，口径见 §4.27「一卡通 token
  是否落盘」。落盘只省冷启动那一次登录，401 重登一次的口径保留。
- 无任何自动重试与退避：失败直接上抛分类异常（`YktException.Credential/NeedCaptcha/
  MultiAccount/Protocol/Network`），UI 层给对应文案。付款码取码**每次进页最多一批**。
- 前端把密码截断到 16 位（`slice(0,16)`）：6 位数字密码不受影响，构造时同样截断以对齐行为。

#### 模块落位

```
domain/YktKeyboard.kt      # 字形表 + 映射/密文构造 + 协议自检（纯 JVM，YktKeyboardTest）
domain/YktPayCode.kt       # QR + Code128 BitMatrix（纯 JVM）
data/ykt/YktCredentialStore.kt  # EncryptedSharedPreferences（ykt_credentials.xml），照 JwCredentialStore
data/ykt/YktModels.kt      # 响应模型（kotlinx，容错 msg/message）、BOM 剥离、错误码、
                           # YktCard（余额）/ YktTurnover（流水）/ 分页包装解析
data/ykt/YktClient.kt      # OkHttp 请求（Basic/synjones-auth 头，IO 调度，无日志拦截器）
data/ykt/YktRepository.kt  # 登录编排 + 内存 token + 取码 + 余额 + 流水；Graph.yktRepository() 单例
ui/campus/CampusCardSettingsScreen.kt  # 开关/凭证/文案/余额状态（照 TweakDetectScreen 交互）
ui/campus/PayCodeScreen.kt             # 三态取码页 + 余额行 + FLAG_SECURE + 亮度拉满
ui/campus/StatementScreen.kt           # 消费流水页（月汇总 + 月份跳转 + 日分组列表 + 类型筛选）
```

- `data/prefs`：只加 `campusCardEnabled`（bool，默认 false）经 `DisplayPrefs` 透出——
  **凭证状态不进 DataStore**（读 `YktCredentialStore.read() != null` 即可）。
- 渲染：`domain/YktPayCode.kt` 出 QR + Code128 BitMatrix（纯 JVM 可测），Bitmap 着色走
  `ui/common/CodeBitmaps`（与共享单车共用，不复制）。
- 依赖：无新增（`com.google.zxing:core:3.5.3` 已由 §4.18 引入；zxing 的
  `Code128Writer` 属 core 包）。

#### 余额展示与消费流水（2026-09-20 追加，B1–B6；本地统计库 L1–L5）

- **余额**：`queryCard` 取卡列表；付款码页顶部一行（「卡余额 ¥19.95 · 电费 ¥0.00」，多卡求和）
  + 设置页状态区；随取码同链路刷新，无额外登录成本。
- **流水页**（`SubpageScreen.CAMPUS_STATEMENT`；2026-09-26 排版与交互重构）：
  - **整页一个 `LazyColumn`**：汇总卡是列表的第一个 item，跟着滚走。旧版是 `Column` 包
    「汇总卡 + 列表」，汇总卡钉在屏幕顶不放，纵向吃掉约 300dp、列表只剩半屏。
  - 汇总卡（`AppCard`）= 月份导航（`MonthNavRow`：`‹ 2026 年 9 月 ⌄ ›` + 「本月」）
    + 当月支出/收入 + 支出构成 Top3（`AppRatioBar` 占比条，条长相对本组最大值）
    + 近 12 个月支出柱状（`AppBarChart`，**柱子可点**，点哪根切到哪个月）。
  - **月份可跳**：点标题开月份网格（`MonthPickerDialog`，按年分组），另加「本月」快捷——
    此前只有 `‹ ›` 两个文字按钮，翻十个月前要点十次。窗口 = `StatementViewModel.windowMonths()`
    （近 12 个月），柱状图、月份弹窗与「上一月」禁用边界共用这一份，别在 UI 里另算一遍。
  - 列表 = 按 `jndatetimeStr` 日期分组（今天/昨天/M月d日），**每组一张卡**（行间细线，
    2026-09-26）；行 = 类型名 + 「时刻 · 商户 · 摘要」/ 金额（支出 −、收入 +）。时刻并进副标题而不是
    单占一行，一屏多看一条；摘要在最后（「二维码=[40…]」这类超长串被省略号吃掉，
    不把前面的时刻与商户挤没）。
  - **类型筛选**（全部 / 消费 / 充值，`FilterChip`）：本地过滤当前月已加载的列表，
    不发请求、不改 DAO。
  - **充值行与详情显示交易账户**（2026-09-28 用户要求「账单要能看出充到哪」）：
    列表行只在**充值（income）行**的副标题里排「时刻 → 交易账户 → 商户 → 摘要」；
    详情不管收支都给「交易账户」一行（进来就是查这一笔的）。文案唯一来源
    `YktModels.accountLabelOf(accType, fromAccount)`：`###` → 「正式卡 241***」、
    `000` → 「电子账户 241***」、**认不出的类型返回 null 不显示**（显示错账户比不显示更糟）。
    消费行不显示它：一张卡一个值，天天躺着只会把商户名挤掉。
  - 行点击展开详情（时间/类型/商户/交易账户/订单号/余额快照）——底部弹层，不新开页。
  - **不含**搜索、标签管理。
- **本地流水库（L1–L5，Room v5 → v6）**：
  - 动机：接口只支持无时间参数全量倒序拉、服务端历史窗口约 10 个月——本地持久化后
    数据越攒越全、离线可查、进页秒开（先显本地，后台增量同步后刷新）。
  - 表 `ykt_turnovers`：`orderId` 主键（服务端订单号，天然去重）、`jndatetime`（交易时间
    epoch 毫秒）、`tranamtFen`、`income`、`turnoverType`、`remark`、`resume`、
    `balanceAfterFen`、`locationName`、`syncedAt`；**v15 加** `fromAccount`（交易卡号）与
    `accType`（`###` 正式卡 / `000` 电子账户）；索引 `jndatetime`。逐级 CREATE TABLE
    非 destructive（迁移纪律同 §4.17）。个人消费记录非凭证，**随云备份**（与课表/成绩同口径）。
    两列都可空、无 Kotlin 默认值 → 迁移就是一句 `ADD COLUMN x TEXT`（**别加 NOT NULL/DEFAULT**，
    多写一个字就是迁移校验崩溃）；历史行留 NULL，展示侧「认不出就不显示」。
  - **增量同步**（`YktTurnoverSyncer`）：时间倒序翻页，**遇到已入库 `orderId` 即停**；
    本地已有近月数据时通常 1 页（100 条）内完成；全量首同步约 8 页。`Dispatchers.IO`
    + upsert 幂等；功能关闭（清凭证）时**保留**本地流水（历史账不随开关消失）。
    **进页那条路受 `YktSyncGate` 的 10 分钟闸门管**（2026-09-24，见 §4.24「请求节流」）：
    用户主动刷新、到账核对、扫码消费检测传 `force = true` 穿透。
  - 流水页数据源 = **本地库优先**：进页先显 Room 数据（秒开、离线可看），后台同步后刷新；
    月份过滤/汇总全部 SQL 完成（`GROUP BY`）。
  - **统计**：汇总卡（支出/收入 + 支出构成 Top3）；年视图 = 近 12 个月支出柱状
    （`ui/common/Charts.kt` 的 `AppBarChart`；数据来自一条按月 `GROUP BY` 查询）。
    数据在本地，统计零网络。
- 流水属只读查询（`berserker-search` 查询服务），不碰资金变动接口；本地库只是流水副本，
  无任何写回服务端的操作。
- 入口：付款码页次行「消费流水」+ 设置页余额状态区同一行点击（不新增我的页行）。

#### 小优化（2026-09-20 第二批）

- **取码链路总超时**：`PayCodeViewModel.load` 包 `withTimeoutOrNull(30s)`（4 跳各自
  15/20s，最坏叠加 80s——宁可明确报超时也不无限转圈，口径同 §4.17 的 60s）。
- **开启即验证 CARD 账户**：`CampusCardViewModel.saveAndEnable` 登录验证成功后顺带
  `queryCard`，无卡账户 / 数据异常在设置页即暴露。
- **token 缓存单点**：`YktRepository.cachedToken` 为唯一持有者，`*WithToken` 系列不再
  要求调用方回传镜像——调用方只传凭证，token 生命周期完全归 repo。

#### 安全与红线

- 凭证存 EncryptedSharedPreferences 新文件 `ykt_credentials.xml`；`backup_rules.xml` /
  `data_extraction_rules.xml` 排除（本 App `allowBackup=true`，不排除会随云备份走）。
- 无 HttpLoggingInterceptor；任何异常路径不打印密码/token/付款码全文。
- 付款码页 `FLAG_SECURE`（防截屏 + 防最近任务缩略图）；亮度拉满离开即恢复。
- 只调只读接口（登录/档案/账户/取码/余额/流水查询）+ **充值下单/删单**（§4.19「充值」，
  仅用户主动触发，不自动充值）；**挂失、转账、改限额一律不做**
  （queryCard 响应里的 autotrans/modifyAcc 相关能力不触碰）。
- README 免责声明同步：新增「校园卡登录同样模拟客户端操作；付款码等同现金，泄露可能被盗刷」。
- **同一份凭证也用于缴费平台**（§4.24）：寝室电费的登录密码与一卡通查询密码是同一个，
  所以关闭一卡通（清除凭证）等于同时关掉电费读数——生活页据此把两处显示成同一个
  「未开启凭证」态，不做「有余额没电量」的半开状态。
  **2026-09-24 追加**：同一份凭证也是「余额提醒」（§3.13）的前提——两个提醒开关在
  关闭凭证时一并回落，且后台核对发现凭证缺失时静默跳过（不重试、不打接口）。

#### 测试清单（JVM）

- `YktKeyboardTest`：全量表形状（10 项、0-9 齐全）；`buildMapping` 正常/未知哈希抛错/
  重复字符非双射抛错；`buildPasswordField` 已知映射的构造与 16 位截断；非数字密码抛错；
  样板自检真/假两例。
- `YktModelsTest`（照 ExamMapperTest 注入 JSON 模式）：键盘响应解析（BOM 剥离）、
  账户列表取 CARD、取码响应（`barcode` 10 项 + expires）、错误码（400/8001/8002/8003）、
  queryCard 卡列表解析（分转元口径）、turnover 记录解析（typeFrom 语义/金额/日期容错）、
  分页包装（total/pages）。
- `YktTurnoverSyncerTest`（纯逻辑部分）：增量停止条件（遇已入库 orderId 即停 / 拉到空页 /
  全量拉完）、jndatetime 解析容错、月键切分。DAO/SQL 由 Room 编译期校验 +
  真机验收覆盖（本项目对 Room 层无 Robolectric 先例，保持口径）。

#### 阶段拆解

C1 文档 → C2 `YktKeyboard` + 测试 → C3 `data/ykt` 四件套 + Graph 注册 + 备份排除 →
C4 设置页 + 我的页入口 → C5 付款码页（渲染 + FLAG_SECURE）→ C6 今日页卡片 + 接线 →
C7 构建与单测全绿 + 真机验证（登录/取码需真机与真实凭证；Python 侧已全链路验证）。
B1 文档（余额+流水接口）→ B2 数据层 + 单测 → B3 付款码页余额行 → B4 流水子页 →
B5 设置页余额状态 → B6 构建全绿 + 真机验证。
B7–B9 小优化（总超时/CARD 预检/token 单点）→ L1 文档 → L2 Room v6 表 + DAO + 迁移 →
L3 增量同步器 + VM 接本地库 → L4 统计视图（月卡片 + 年柱状）→ L5 构建全绿 + 真机验证。

#### 充值（2026-09-21 追加，方案二：App 内下单 + 外部浏览器支付）

**实测链路**（`scripts/out/probe_ykt_*.py`，2026-09-21；凭证走环境变量不入库）：

1. 配置：`frontInfo.getFrontConfig` JSON → `recharge:"401"`（feeitemid）、
   `payment:"…/payment/"`（官方收银台独立 Vue 应用，**自己不创建订单**，从 URL query 注入
   `synjones-auth` 登录态 + `orderid`）。
2. 下单 = **HTML 表单同步提交**（非 JSON API）：`POST /charge/order/thirdOrder`
   （campus-card 充值页 chunk `d98c9872` 的 `confirm()` 原样还原），字段：
   `feeitemid=401`、`appid=56321`、`tranamt=<元，浮点，amount-input parseFloat 不乘100>`、
   `source=app`、`synjones-auth=bearer <token>`、`yktcard=<卡 account>`、
   `synAccessSource=h5`，外加签名三件套 `APP_ID/TIMESTAMP(yyyyMMddHHmmssSSS)/NONCE/SIGN_TYPE`。
3. **签名算法**（收银台 chunk `6affa2d0` 与充值页一致，`appid`/`SECRET_KEY` 均硬编码于
   公开前端 JS）：参数并集（业务字段 + `APP_ID=56321` + `TIMESTAMP` + `NONCE` + `SIGN_TYPE=SHA256`），
   **key 字典序**、跳过空值（`0` 保留）与 `SIGN/SECRET_KEY` 两键，拼 `k=v&`，
   末尾拼 `SECRET_KEY=<密钥>`，SHA256 十六进制**大写**。
4. 支付：收银台 `GET /charge/pay/getpayinfo?orderid=` 拉订单与 `payList` 渠道 →
   `POST /blade-pay/pay`（同一签名算法，`paystep:2`；CARD/ACCOUNT 账户余额渠道需支付密码
   `password="1$1$<6位>$1$<键盘uuid>"` 或短信验证码，密码键盘同 berserker-secure）。
   渠道矩阵：`CARD/CARDTSM/ACCOUNT/ACCOUNTTSM`（余额类）、`BCM`、**weixin=JSAPI**
   （`WeixinJSBridge.chooseWXPay`，**仅微信环境可用**）、**alipay=webUrl 跳转**（外部浏览器可用）。
   支付结果经 `wss://…/websocket/mobile_service_platform/bankcard_recharge/{orderid}` 推送；
   未支付订单收银台侧超时关闭，另有 `POST /charge/order/deleteOrder`。
5. **实测形态（2026-09-21，0.01 元真实下单 ×2 + 只读查单）**：`thirdOrder` 成功 = **302
   Location** 下发完整收银台 URL `/payment?orderid=<id>&token=<JWT>`（登录态参数名是
   `token` 不是 `synjones-auth`；App 不自己拼，直接打开 Location）。**`queryCard` 的
   `account` 是 6 位数字卡号（如 24xxxx），不是学号**——`yktcard` 必须用查询返回的
   account（首版误传学号导致「未找到要充值的卡账户」，已修）。查单 `getpayinfo`：
   `order.status=0`（待支付）、`payList` 单条 **CAMPUSCARD /「微信充值」**（payid=63，
   `nopassword=0` = 需 6 位消费密码，即食堂 POS 密码，只在官方页输入）——本校园
   微信充值即此渠道，无独立支付宝/微信 code。`deleteOrder` 实测 500（平台侧行为），
   未支付订单由收银台超时关闭（前端配置 delayTime=30 分钟），不扣款。客户端 OkHttp
   必须关闭自动重定向跟随才能拿到 302 Location。

**App 侧实现（方案二）**：

- `domain/YktRechargeSign.kt`（纯 JVM）：sign 算法 + `buildTimestamp`；单测覆盖排序/空值/大小写。
- `YktRepository.rechargeCreate(username, password, yuan)` → 卡校验（account=6 位卡号）→
  组表单 + SIGN + `thirdOrder`（302 Location）→ **`payDirect` 直拉微信**（等效收银台
  「立即付款」：`blade-pay/pay` 的 synjones-auth 走 **header**（表单字段报「未获取到用户
  信息」）→ checkmweb 中间页（**Referer 必须商户域名**）→ 抓 `weixin://wap/pay?…`）。
  返回 **`YktRechargeStart`**（订单 + 付款前基线：该卡余额与 6 位卡号）——
  基线与订单一起落库，到账判定重启后仍可用（2026-09-22）；任一环失败降级
  `Cashier`（服务端 302 下发的收银台 URL）。
  `rechargeCancel` → 删单（实测 500，平台行为，收银台 30 分钟超时关闭兜底）。
- UI（校园卡设置页余额卡内「充值」）：底部弹层输金额（预设 20/50/100/200，0.01–500 元校验）
  → 二次确认（**目标账户加粗** + 金额，2026-09-28 用户要求「充错账户是钱的事」：
  正式卡给 **6 位卡号**、电子账户给账户名；卡号取不到就不显示数字，宁缺勿错。
  卡号的选取口径与下单链路共用一份判断 `data/ykt/YktModels.rechargeTargetCard`
  = 第一张非挂失卡——两处各写一份的话最坏是「弹窗写 A 卡、钱进 B 卡」）→ 下单 →
  `WechatPay` 直接 `ACTION_VIEW` 拉微信支付（**跳过
  浏览器与收银台页**；实测免密，微信内生物/密码确认由微信侧完成）→ **到账状态机**
  （`ArrivalState`：余额卡内嵌「等待到账」行（5 秒轮询 × 30 分钟窗口，饭卡充值常见
  延迟数分钟至数十分钟）→ 检测到即弹「充值成功」对话框（金额+当前余额）；双判定 =
  **付款那张卡**的余额涨满订单金额（主，口径见 `domain/YktArrival.kt`）或增量流水出现
  晚于下单时刻的新记录（辅，覆盖余额视图滞后）；余额口径的基线随未确认充值落库，
  进程被杀重启后照常可用；超时给安抚提示「到账后消费流水自动显示」）。
  「支付完成，正在确认到账」弹窗**每笔充值只弹一次**（`pending_recharge_confirm_shown`），
  之后等待态只由「等待到账」行承载——进出页面会反复触发 `onHostResume`，
  不加这个标记就是反复拦人（2026-09-22 用户反馈）。
- 余额卡**进页即渲染**：余额未取到时「获取中…」占位（数据到达平滑填充，不跳动）。
- 红线：App 全程**不收集支付密码/银行卡**（免密渠道的支付确认在微信客户端内完成）；
  不自动充值；`weixin://` 拉起链接含一次性 prepay_id（约 5 分钟有效），不落盘不进日志；
  SIGN 密钥是前端公开常量（非逆向所得），平台改版即失效，报错兜底文案给「平台可能已改版」。

### 4.20 课程笔记·课件与作业（2026-09-21，P6；UI 规格见 §3.11）

两门功能共用一套「本地内容 + 自研渲染」骨架：笔记与作业都是挂在课程名上的 Markdown 文本
（图片以正文内联引用），差异只在作业多了截止日期与完成勾选。渲染**零新依赖**——自研
Markdown 子集解析与 LaTeX 子集排版，理由：项目至今零渲染/图片依赖，`--offline` 构建是
日常纪律（§4.6），且 APK 已 17.9MB 超 §5 的 15MB 目标；自研解析与排版是纯 JVM 逻辑，
能落进既有单测体系（对齐 `WeekGridLayout`／`YktKeyboard` 的既有做法）。

```
domain/Note.kt             # 笔记模型（纯）
domain/Homework.kt         # 作业模型（纯）
domain/Markdown.kt         # 块级/行内 AST + 解析器 + 摘要提取（纯 JVM，MarkdownParserTest）
domain/MarkdownEdit.kt     # 编辑器纯函数：回车续行 / 片段插入 / 选区包裹（MarkdownEditTest）
domain/MarkdownImages.kt   # 正文里 img: 引用的收集/插入/移除（纯）
domain/MathTex.kt          # TeX 子集 → 盒子树 + 布局（度量器接口注入，MathTexTest）
domain/HomeworkCenter.kt   # 未完成排序 / 汇总 / 截止文案（HomeworkCenterTest）
domain/ReminderPlanner.kt  # 既有文件扩展：作业提醒点与有效期（HomeworkReminderTest）
data/local/Entities/Daos   # Room v7：notes / homework 两表
data/repo/NoteRepository.kt / HomeworkRepository.kt / AttachmentStore.kt
ui/common/MarkdownView.kt / MathText.kt / MarkdownEditor.kt / ImageViewerDialog.kt
ui/notes/{NoteLibraryScreen, NoteCourseScreen, NoteDetailScreen}.kt
ui/homework/{HomeworkLibraryScreen, HomeworkCourseScreen, HomeworkDetailScreen,
             HomeworkTodoScreen, HomeworkTodayCard}.kt
```

#### 归属：为什么挂课程名，不挂 course.id / timetableId

课程行 id 不稳定：覆盖导入走 `replaceAllCourses`（清表重建）、调课会删行重建、
`restoreCourses` 用原 id 回滚——任何以 id 为外键的用户内容都会在这些路径上变孤儿。
课表切换（多课表 = 多学期）也不该让旧笔记"消失"。故归属键 = **课程名原样字符串**：
覆盖导入/调课/换课表/删课都不影响，笔记库按课程名分组天然成立。
**代价（明写）**：跨课表同名课程共享内容；课程改名后旧内容留在旧名下（仍可见可编辑，
不自动重绑）。这两条是有意取舍，不是漏项。

#### 存储（Room v6 → v7，非 destructive，照既有 ALTER/CREATE 纪律）

```sql
CREATE TABLE notes (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  courseName TEXT NOT NULL,   -- 归属键
  title TEXT NOT NULL,
  body TEXT NOT NULL,         -- Markdown 源码，图片以 img: 引用内联
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL);
CREATE INDEX index_notes_courseName ON notes(courseName);
CREATE INDEX index_notes_updatedAt  ON notes(updatedAt);
CREATE TABLE homework (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  courseName TEXT NOT NULL,
  detail TEXT NOT NULL,
  dueDate TEXT,               -- yyyy-MM-dd 可空；ISO 文本字典序 = 时间序（同 semester.startDate 惯例）
  done INTEGER NOT NULL DEFAULT 0,
  doneAt INTEGER,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL);
CREATE INDEX index_homework_courseName / _done / _dueDate
```

- **Room v8 → v9（2026-09-23）：`homework` 去 `title`**。SQLite 删列须重建表
  （建 `homework_new` → 搬数据 → 删旧表 → 改名 → 重建三索引）；旧 title 丢弃，
  列表行 / 通知 / 汇总文案一律用 `domain/homeworkDisplayTitle(detail)`——取正文第一行、
  剥 Markdown 前缀与包边、**图片引用整条剥掉（含外链）、剥完为空的图片行跳过**、
  空行跳过、全空兜底「未命名作业」。这是唯一的摘要口径，
  不要在 UI 里另写 `.lineSequence().first()`。
- 两表都**不带 timetableId**（归属口径见上），所以既有「按 timetableId 过滤」的课表数据
  纪律不适用于它们；`@Index` 声明必须与迁移里的 `CREATE INDEX` 逐字对齐（漏声明 = 迁移校验崩溃，AGENTS 已记）
- 两个仓库都是薄封装（仿 `ScoreRepository`）：`observeAll / observeForCourse / observeGroups`
  （投影：courseName + 计数 + 最近更新）/ `observePending` / `upsert` / `delete`；
  排序与文案全在 `domain/HomeworkCenter`，仓库不写业务判断

#### 附件（图片）：文件 + 正文引用，不建附件表

- 目录 `filesDir/notes_img/`，文件名 `时间戳_随机.jpg`；正文引用形如 `![](img:20260921_a1b2.jpg)`
- 选图走 `PickVisualMedia`（系统 Photo Picker / 退化为 SAF，**零新增权限**，项目已有 SAF 先例）；
  入库前压缩：长边 >1920 或 >1.5MB → 下采样 + JPEG 重编码，其余原样复制
- 清理：保存/删除时对比旧正文引用差集，删掉不再被引用的文件；冷启动 `sweep` 兜底扫两表
  全量正文引用（条目量小，IO 协程里做）
- 图片不进云备份排除名单：笔记是用户内容，随 Room 数据一并备份（凭证类排除规则不动）

#### 渲染子集（明写支持边界，超范围**原样显示源码**）

| 层 | 支持 | 明确不支持（原样展示为文本） |
|----|------|------|
| Markdown 块级 | `#`~`######`、段落、`-`/`*`/`+` 与 `1.` 列表（2 级嵌套）、`- [ ]`/`- [x]` 任务项、`>` 引用、``` 围栏代码块、`---` 分隔线、`$$…$$` 公式块 | 表格、HTML、脚注、引用式链接、定义列表 |
| Markdown 行内 | `**粗**` / `*斜*` / `~~删除~~` / `` `码` `` / `[文本](http…)` / `![alt](img:…)` / `$…$` / `\*` 转义 | 自动链接、内嵌 HTML、脚注引用 |
| LaTeX | `\frac \dfrac \tfrac`、`\sqrt[n]{}`、`^ _` 可嵌套、`\left…\right`、`\sum \prod \int \iint \oint \lim \max \min`（display 上下限 / inline 右侧）、希腊字母大小写、常用关系与算子符（`\pm \times \leq \geq \neq \approx \in \subset \cup \cap \to \Rightarrow \infty \partial \nabla \cdots` 等）、函数名正体（`\sin \ln \log \exp \det`…）、`\vec \bar \hat \tilde \dot`、`\text{中文}`、`\mathrm`、间距命令 | 矩阵/`cases`/`align` 环境、宏定义、TikZ |

- **失败回退**：任何未知命令或括号不配 → 该条公式整体以等宽源码显示（不吞内容、不崩、不半截渲染）
- **图片分块（2026-09-24 修 bug）**：独占一行的图片按**块级**画真图，其余情形（与文字同行）
  才退化成行内可点的「[图片]」标签。判据在 `domain/Markdown.splitParagraph`：
  一行里除空白文本外只有一张图片即算独占行。为什么需要它——单换行不构成新段落，
  「一次插两张图」与「图片紧跟文字后面」在 AST 里是同一个 `Paragraph`；
  旧渲染只认「整段唯一节点是图片」，两种情况都会掉进行内分支，用户看到一串
  蓝底下划线的「[图片]」文字而不是图（2026-09-24 用户报「作业预览无法预览图片」）。
- 排版比例单一来源：分子分母 0.85×、上下标 0.7×（三级起不再缩）、inline 0.95×、display 1.15×；
  公式按 (源码, 字号) 做内存 LRU 缓存；行内公式走 `AnnotatedString` inlineContent（垂直居中，
  与 MathJax 的基线对齐有已知偏差）

#### 提醒（`domain/ReminderPlanner` 扩展，规则见 §3.11）

- 纯函数新增：`homeworkReminderPlan(homework, now)` 求当前**有效**（触发时刻已到、未越次日 00:00）
  的提醒点、`nextHomeworkReminder(items, now)` 求下一个触发点（向后看 30 天）；
  与上课提醒共用「取更早者排同一个闹钟」的语义
- `ui/reminder/ClassReminder`：`scheduleNext` 取上课与作业两者**更早**的触发点排同一个闹钟
  （requestCode 不变）；核对 Worker 里课后补一段作业核对；开关与去重键各自独立
  （DataStore：`homework_reminder_enabled` 默认关、`homework_reminded_keys` 字符串集合留 50 条）
- 通知通道独立（`homework_deadline`，id 1002），点击进 `HOMEWORK_TODO`；作业写库后由 UI 触发重排

#### 测试清单（JVM）

| 测试类 | 覆盖 |
|--------|------|
| `MarkdownParserTest` | 块级/行内全分支、2 级嵌套列表、任务项、未闭合围栏、转义、中文与数字混排、摘要提取、段落图片分块（连图/文字接图/同行图/外链图/空白边） |
| `MarkdownEditTest` | 回车续行全分支（无序/有序递增/任务/引用/空项结束/代码块内/缩进保留）、片段包裹（有选区/无选区）、图片插入位置、`$` 自动配对 |
| `MathTexTest` | 解析（嵌套分数/根号/上下标/`\left…\right`/希腊/算子/函数/`\text`）、失败回退标记、布局尺寸（注入固定度量器） |
| `HomeworkCenterTest` | 未完成排序（逾期→今天→未来→无截止）、汇总计数与最近截止、截止文案（今天/明天/N 天后/已过期 N 天）、摘要剥图片（纯图片行跳过/行内引用剥掉/全图正文兜底） |
| `HomeworkReminderTest` | 提醒点与有效期窗口、越过即跳过、去重键、已完成/已删除/已逾期不发 |

#### 阶段拆解

X0 文档（本节 + §3.11 + §3.1/§3.3/§3.7 同步）→ X1 数据层（Room v7 + 仓库 + `HomeworkCenterTest`）
→ X2 渲染内核（Markdown 解析/渲染 + TeX 排版 + 编辑器纯函数与三个测试类）→ X3 笔记链路
（三屏 + 两个入口 + 图片链路）→ X4 作业链路（三屏 + 今日卡 + 作业中心）→ X5 提醒
（调度扩展 + 设置行 + 通道）→ X6 打磨验证（真机覆盖安装验迁移 + 观感）。

不做（v1 明写）：笔记/作业导进课表 JSON、桌面小组件展示、多端同步、富文本所见即所得、
笔记/课件分类标签（保持"源码 + 预览"双态与单一列表）。

### 4.21 课表页自定义背景图（2026-09-22，P6）

需求：课表页支持自定义背景图，可调不透明度等参数。
范围（2026-09-22 用户拍板）：**只铺课表页**、**只从本地相册选**、**全局共用一张**。

**存储**

| 项 | 取值 |
|----|------|
| 偏好字段 | `TimetablePrefs` 新增 `bgImageName` / `bgImageOpacity` / `bgImageDim` / `bgImageBlur` / `bgImageScale`，存 DataStore `view_prefs_json`（全局，换课表不换背景，见 §4.9） |
| 文件目录 | `filesDir/schedule_bg/`，同时只保留一张 |
| 命名 | `bg_yyyyMMdd_HHmmss_<4位hex>.jpg`（原图是 PNG 时保留 `.png`） |
| 压缩 | 长边 ≤ 1920、JPEG q88；原始就小的流式复制并保留 PNG 透明。与 §4.20 附件同口径，实现共用 `data/repo/ImageImport.kt` |
| 替换顺序 | 写新文件 → 写 prefs → 删旧文件（反过来会出现 prefs 指向已删文件） |
| 清理 | 冷启动扫一次目录，保留 prefs 指向的那个（`ScheduleBackgroundStore.sweep`） |

**不放 `notes_img/`**：`AttachmentStore.sweep` 按笔记正文引用差集删孤儿文件，背景图会被它当成孤儿删掉。

**渲染**（`ui/week/ScheduleBackground.kt`：`ScheduleBackgroundLayer` / `loadScheduleBackground` / `warmScheduleBackground`）

- 背景层由 **JuwApp 铺在外层 Scaffold 之下**（2026-09-22 §4.22 改，此前在 WeekScreen 内），铺满整个窗口：状态栏 → 屏幕底。WeekScreen 的 Scaffold 底色改透明让它透出来，顶栏是自绘 Row 且无底色，也自然露图。
- 缩放三档：`Fill`（`ContentScale.Crop`）/ `Fit`（留白露主题底色）/ `Tile`（Canvas `drawImageRect` 平铺）。
- 模糊 = 降采样纹理放大（解码目标长边随档位从 1920 降到 320，绘制时双线性插值）。**不用 `Modifier.blur`**：它的 RenderEffect 路径要 API 31，minSdk 26 下会分叉成两种观感。
- 首帧：解码在渲染层（`ScheduleBackgroundLayer` 的 `produceState`）里做，不拦首帧——预热没命中时网格先出、背景随后补上。冷启动由
  `JuwApplication` 读一次偏好后调 `warmScheduleBackground` 预热；缓存键含文件名与解码长边，预热与渲染必须走同一个 `targetLongSide` 才算命中
  （量屏幕长边两处各算一遍会差几个像素，缓存永远错过）。
- 文件丢失（清数据 / 恢复备份不完整）：`WeekScreen` 的 `LaunchedEffect` 校验存在性，缺失就经 VM 的 `clearBackgroundImage()` 清掉 prefs 字段并记一次日志，降级成无背景，不崩。
- 内存：1920×1080 的 ARGB_8888 约 8.3MB。单槽缓存只存当前这一张（笔记那套按字节计的 16MB LRU 装不下它，会把笔记图挤出去）。

**交互**

- 入口：课表页顶栏眼睛 → 显示设置覆盖面板新增第 5 组「背景」：预览行（缩略图 + 状态 + 「选择」）+ 三根滑块（图片不透明度 / 变暗遮罩 / 模糊）+ 缩放方式 chips + 「移除背景」。
- 选图走系统 Photo Picker（零权限），与 §4.20 同一口径；处理中按钮禁用并转「处理中…」。
- 结果提示走 `InlineNoticeRow`：覆盖面板画在 Scaffold 之上，Snackbar 落在它下面，用户看不见（同 §3.2 的行内变体口径）。
- 拖动滑块不重解码：解码 key 只含文件名与模糊档位，不透明度与遮罩是纯绘制参数。
- 与「格子不透明度」（50%–100%）不联动。图要透出来靠那一项，两者在面板里隔一组。

**不做（v1 明写）**：桌面小组件背景、今日页背景、每课表独立背景、网络图片、内置渐变与纯色生成。

**测试**：`ScheduleBackgroundTest`（参数夹取、模糊档位到解码尺寸的映射、文件名合法性）+ `TimetablePrefsDefaultsTest` 扩展（新字段默认值、老 JSON 回落、encode/decode 往返）。

### 4.22 沉浸式顶栏与悬浮导航栏（2026-09-22，P6）

需求：课表页背景图铺满整屏（此前顶部停在状态栏下缘），底栏再给一个「悬浮」的磨砂形态。
（用户原话「悬浮状态栏」，纠正为「悬浮导航栏」：顶部那条空白不是开关能解决的缺口，是 inset 归属问题。）

**根因：背景图到不了状态栏**

`MainActivity` 外层 Scaffold 用默认 `contentWindowInsets`（systemBars）垫了一个状态栏高度，
`NavHost` 又吃了 `Modifier.padding(padding)`，课表页的可用区从状态栏下缘才开始。图层再 `fillMaxSize` 也只能铺到这。

**方案：顶部 inset 改由各页自取（无条件），底栏悬浮做开关（默认关）**

| 位置 | 改动 |
|------|------|
| 外层 Scaffold | `contentWindowInsets = WindowInsets(0)`。顶部不再垫高；**底部让位仍由 bottomBar 高度提供**（与 insets 无关），所以底栏避让不受影响 |
| 今日 / 我的 | `TopAppBar(windowInsets = …)`：自己取回状态栏高度，视觉与改动前一致。**今日页用钉住版 `pinnedStatusBars()`**（2026-09-26）——它的快捷方式会拉起外部应用，实时 insets 在过渡期间会被瞬时归零 |
| 课表 | 自绘顶栏 `Row` 加 `windowInsetsPadding(WindowInsets.statusBars)`（课表页不拉起外部应用，保持实时值） |
| 背景层 | 由 JuwApp 铺在**外层 Scaffold 之下**（content 的第一个子节点），按 `currentRoute == WEEK` 只画课表 Tab。它铺满整个窗口，因此状态栏与底栏之下都是这张图 |
| 状态栏可读性 | 背景层顶部叠一层随主题的垂直渐变（`background` 60% → 透明，高 = 状态栏 + 40dp）。状态栏图标色由系统按深浅主题定，亮/暗图上都可能失去对比，这条是兜底 |
| 课表页底色 | WeekScreen 的 Scaffold `containerColor = Color.Transparent`（其余两页保持不透明底，所以图不会漏到今日/我的） |
| 悬浮导航栏（`DisplayPrefs.floatingNavBar`，默认关，**重启生效**） | 底栏换成**悬浮胶囊**（2026-09-22 用户按参考形态指定）：全圆角（`RoundedCornerShape(percent = 50)`）+ 阴影 8dp + 半透明 `surfaceContainer 82%`，两侧留 56dp、离手势条 12dp。选中态三重信号（2026-09-22 用户追加：原先只靠颜色，切页辨识度不够）：淡主色底块（`primary 12%`）+ 图标微放大（22dp × 1.18 ≈ 26dp，`graphicsLayer` 缩放不改布局）+ 颜色加深（`onSurfaceVariant` → `onSurface`），全部 180ms 渐变与页面转场同频。底色必须用 `surfaceContainer` 而不是 `surface`：后者与页面底色几乎同色，胶囊会读成「一条白色底栏」。**生效时机（2026-09-24 用户拍板）**：MainActivity 组合首帧前同步读一次 DataStore，把形态冻结进进程级 `floatingNavBarEffective`；运行中切开关只写偏好、当次会话不改 UI，通用设置页弹「已保存，重启应用后生效」。根因是悬浮/普通两套 Scaffold 几何完全不同（NavHost 是否吃底栏 padding、净空口径、底栏槽位高度），跟着 Flow 中途切换必然出现「普通底栏 → 跳悬浮」或反向的启动割裂，除非给两套几何都做瞬时过渡动画，代价不值 |
| 页面底部净空 | 悬浮形态下 `NavHost` **不再吃 Scaffold 的底栏 padding**，页面内容一直铺到窗口底、被胶囊压住一部分（真悬浮）；`LocalBottomBarClearance`（`ui/common`）带着净空值下发，页面的滚动内容把最后一项顶到胶囊上方。数值是解析式算的（胶囊 64dp + 12dp + 手势条），不测量组件——测量要等首帧，页面会先摆一次再往上跳 |
| 净空的消费方 | 课表网格与今日页 dock 自己加；**提示通道 `AppSnackbarHost` 在组件内统一加**（净空之上再叠 16dp 固定间距，见 §3.2）（页面底边就是窗口底之后，Snackbar 会落到胶囊与手势条底下，不能让每个调用点自己记着）。课表页的显示设置面板走**另一条路**：打开期间把底栏整个收起（`LocalBottomBarVisibleRequest`，见 §3.1），面板铺到屏幕底。新增贴住底边的表面时先确认它归这一栏里的哪一种 |
| 今日页 dock | 同时改成独立卡片（§3.3）：不再贴屏幕底边，与底栏留 12dp，折叠动画与底栏形态解耦 |
| 今日页 dock（悬浮态） | 卡片底部让位改用 `LocalBottomBarClearance`，正好落在胶囊上缘之上 |

**为什么背景层不能留在课表页里**：`Scaffold` 的 `Surface` 会给内容加 `clip`，图想「向下溢出」到底栏后面会被裁掉；
外层 Scaffold 的内容区本身就是整个窗口，放这里不需要任何溢出技巧。这条是实测踩出来的，别退回页内。

**「磨砂」的口径**：半透明底 + 身后图片透出（悬浮胶囊形态下配阴影，不再画顶缘细线）。
**不做 background blur**：Compose 没有 backdrop 模糊 API（`Modifier.blur` 模糊的是自身内容，不是身后内容），
要模拟得把背景图再画一份模糊副本，等于多一次整层 GPU 绘制；真机观感先按上面这套定，不够再加。

**不做（v1）**：状态栏 / 底栏的真实高斯模糊、底栏图标动效、按页独立的沉浸开关。

**测试**：这一段全是窗口 inset 与绘制，JVM 测不到；改动后真机逐页对拍（三个 Tab 顶栏不双倍空白、底栏不遮内容、背景图铺满到屏幕底）。

---
