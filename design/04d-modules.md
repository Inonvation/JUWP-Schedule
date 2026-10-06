# DESIGN §4.33–§4.38 · 技术架构：提醒 · 校历 · 选课 · U净 · 收件箱

> 本文档是 [DESIGN.md](../DESIGN.md) 的拆分件。代码注释里的「DESIGN §x.y」章节号一字未改，索引见 DESIGN.md 的映射表。

## 4. 技术架构（续 · §4.33–§4.38）

### 4.33 成绩/考试变动提醒（2026-09-30，P6；成绩侧设置入口在成绩页齿轮、考试侧在考试页）

期末成绩一门一门出的场景：用户开着提醒，教务一出分就收到通知。考试同理（发布、
时间/考场调整）。**设置入口 2026-09-30 拆成两处**（同日把设置项里的「立即检查」按钮
删掉，用户口径：自动检查就够，不要手动触发——考试页顶栏的「更新」不属于这一条，
它是**列表数据**的刷新，口径同成绩页「从教务导入」、学业页「重新导入」）：

- **成绩变动提醒**在「我的 → 学习 → 成绩查询 → 齿轮（成绩设置）」——成绩页的展示
  （分组/排序）、统计口径（任选课）与出分提醒单独成页 `ScoreSettingsScreen`
  （`SubpageScreen.SCORE_SETTINGS`）；汇总卡里的任选课开关**随即删除**（口径开关只此
  一处，卡上 note 行随 `excludedCount` 动态展示当前口径生效中，另留一行
  「统计口径在成绩设置里调整」的去向说明）；成绩页空态为「空态 + 两张同语言入口卡」
  （导出带章成绩单 / 出分提醒），出分提醒卡整卡点进成绩设置；
- **考试变动提醒**在「我的 → 学习 → 成绩与考试 → 考试安排」——同日（2026-09-30）的
  第三处落点：初版内联在课表 hub（`ExamAlertRows`，已删）→ 收成课表 hub 入口行 →
  **傍晚挪进学习 hub**（用户口径：考试跟成绩一起看，两处都是教务给的学习数据）。
  页为独立考试页 `ExamScreen`（`SubpageScreen.EXAMS`，`ui/exam/ExamScreen.kt`），页内三块：
  ① 页首提醒卡（开关 + 自动检查间隔）；② 考试安排列表——数据取 `ExamSnapshotStore`
  基线（**与提醒同源**，不要求先把考试导进课表），一条一卡：左侧课程名 + 时刻 +
  教师/考场/座位，右侧「几月几日 + 周几」大号日期；**待考的按日期升序在前、
  已结束的整块倒序在后并置灰，两块之间一行「已结束」**，列表头是「学期 · 共 N 场 +
  上次成功检查时间」；③ 页尾「导入考试安排到课表」卡——开 **考试安排查询页**的导入窗口
  （`JwImportActivity.start(startAtExam = true)`，§4.14）。**这个参数不能省**：
  课表模式的窗口登录后固定落理论课表页，一落就触发 `autoImportOnTheoryReady` 的
  「一键导入课表」（理论+实验），与这张卡写的话不是一回事（2026-09-30 用户实测发现）。
  落在考试页后 `autoImportOnExamReady` 自动抓一轮（§4.14）：有安排直接弹识别结果，
  没安排弹「暂时没有考试安排」，所以这张卡点下去就是一条龙，不用再点导入按钮。
  考试进课表仍只有手动导入一条路，§4.14 红线不变。
  顶栏「更新」= 强制跑一次 `ExamSync.sync(force = true)`（OkHttp 直取，不写课表），
  本次探测到的变动在对应卡上标「新增 / 调整」——身份键与 `ExamChangeDetector.keyOf`
  共用一份口径，不各写一个；失败原因（未保存凭证 / 会话失效 / 教务结构变了）原样进
  提示条，失败不落检查时刻。
- 检查间隔 **2026-10-04 起两处各自独立**（`score_alert_interval_hours` /
  `exam_alert_interval_hours`，旧共用键只作老用户读取回退），一处改动不再牵动另一处；
  周期任务取四链最小档叫醒，各路用自己的间隔闸门。

#### 数据链（三条，全部静默，失败不重试）

| 链 | 实现 | 存储 | 变更判据 |
|----|------|------|----------|
| 成绩 | `ScoreSync`（§4.29 既有的自动导入，本节扩其闸门） | Room `scores`（按学期替换，不变） | `ScoreChangeDetector`：写库前对旧库快照 diff。出分 = 旧无分新有分（含待评教解锁、新记录带分）；改分 = 两侧有分但值/串不同；撤录忽略 |
| 考试 | `ExamSync`（新）：OkHttp 直取壳页 `xsksap_query`（正则抽学期下拉选中项，抽不到即失败**不猜学期**；2026-10-04 起带 12 小时 TTL 壳页缓存，判据 `AutoSyncRules.shellTermCacheUsable`，非 force 复用上次学期，URL 常量已收口到 `JwUrls`）+ `xsksap_list` 翻页（页间随机小间隔），解析复用 `ExamScheduleParser.parseFetchJson` | `ExamSnapshotStore`（filesDir `exam_snapshot.json`，学期 + 条目最小字段；损坏当空；空结果不落盘） | `ExamChangeDetector`：身份键 = 课程号（空则课名）+ 日期；调整 = 同键起止时刻/考场/校区/座位任一变；撤考忽略；日期缺失的行跳过（键会漂移成「每次都是新的」） |
| 通知 | `ScoreAlertReminder`（`ui/reminder/`）+ `ScoreAlertCheckWorker` | DataStore（见下） | 只按变更列表发；无变更静默 |

红线三条：**考试绝不写课程表**——点通知落 `JwImportActivity`（Schedule 模式），
用户在导入页确认后才写库，考试进课表只有手动导入一条路；**解析口径与 WebView 导入
完全共用**（同一份 Parser，只是传输层换 OkHttp，同 `ScoreSync` 与成绩页的关系）；
**失败不写任何时刻**（检查时刻 = 成功才落，失败下个周期自动补查）。

#### 闸门与设置（`ScoreAlertDefaults` 单一口径）

- 开关两个：`score_alert_enabled` / `exam_alert_enabled`，**默认开**（2026-10-06 用户拍板；
  显式关过的用户落盘 false 不受影响，无需迁移），各自独立；
- 间隔档位：1 / 3 / 6 / 12 / 24 小时，默认 6（`ScoreAlertDefaults.coerceIntervalHours`
  兜非法值；2026-10-04 起成绩/考试/选课/调课四链各自一个键，见 §4.17）；提醒开着时 `ScoreSync` 走**小时级**毫秒闸门（`score_check_millis`），
  关着维持原 7 天日期闸门（`scoreSyncDate`）——关提醒后自动导入节奏不变（§4.29 不受影响）；
  `ExamSync` 只服务提醒（开关关 = Skipped，零请求），闸门 `exam_check_millis`；
- **冷启动自动查一次**（用户要求的口径）：`JuwApplication` 既有的成绩自动导入链之后
  追加 `examSync.sync()`；各链内部被开关与间隔闸门挡住，不满足就是零网络空跑；
- 周期核对 WorkManager `score_alert_periodic`：间隔 = 档位；**两开关全关撤销任务**；
  幂等 = 「已排周期记 DataStore `alert_periodic_interval`，档位没变 KEEP、变了 REPLACE」
  （WorkManager 2.7 没有 UPDATE 策略）；设置变更 `onSettingsChanged` = 重排周期 +
  one-shot 立即评估（**不清检查时刻**——首跑本来就要建基线，不需要额外清）；
- Worker 类名 `ScoreAlertCheckWorker` 与工作名一经发布不可改（KEEP/REPLACE 按类名
  实例化，同 `BalanceAlertCheckWorker` 的坑位说明）。

#### 通知与「首跑静默」

渠道 `score_alert`「成绩与考试提醒」DEFAULT（出分不是横幅级的事）；成绩通知 id/tag/
requestCode = 1008 / `score_alert` / 3008，考试 = 1009 / `exam_alert` / 3009
（requestCode 与 id 解耦，同 §3.13 的 3005/3006 纪律）。成绩通知正文 = 「课程名：分数」
逐行列出（>4 门折叠「等 N 门」），点击 `subpageLaunchIntent(SCORES)` 落成绩页；
考试通知正文 = 「新增/调整：课程 日期 时刻 考场」，点击 `subpageLaunchIntent(EXAMS)` 落
**考试页**（2026-09-30 由直达 `JwImportActivity` 改过来：通知只是引子，看完那条考试
再决定导不导入，导入入口就在同页页尾；写库仍要过导入页的确认弹窗，红线不变）。

**首跑/基线纪律（防刷屏）**：首次开启或换学期，第一次抓到的全量是**基线不是变动**——
`ScoreSync` 用 `firstImport`（旧库为空）标记、`ExamSync` 用「快照为空或学期切换」标记，
两种情况都只落库/存快照**不发通知**。之后才只有真变动才响。**没有「立即检查」按钮**
（2026-09-30 用户删）：开关/间隔变更本身就会触发一次即时评估（`onSettingsChanged`
的 one-shot），设置项里不再留手动触发入口；想当场看最新，成绩页走「从教务导入」、
考试页走顶栏「更新」——两者都是**数据页的刷新**，不是设置项里的按钮。
考试页那次「更新」还有个副作用要知情：`ExamSync.sync(force = true)` 会顺手把新基线落盘，
本次探测到的变动就不再由周期任务重复通知（用户已经看见卡上的「新增/调整」标）。

#### 已知边界

- 提醒依赖「我的」页已存教务凭证（静默续登），凭证失效时链路静默失败、
  `LoginStateNotifier`（§3.16）会另行提醒登录状态；
- 考试「改期」（日期变了）在检测里表现为「旧键消失 + 新键出现 = 新增」一条
  （撤考不报），通知文案会列出新日期，可接受；
- 成绩「按学期替换」对**旧侧有、新侧无**的记录不通知（成绩不会平白消失，
  多半是教务调整，宁可少打扰）；
- 已验：`ScoreChangeDetectorTest`（10 例）/ `ExamChangeDetectorTest`（8 例）/
  `ExamSyncTermFromShellTest`（5 例）/ `ScoreAlertDefaultsTest`（4 例）/
  `AutoSyncRulesTest` 扩毫秒闸门（5 例）/ `ScheduleExporterTest` 扩考试事件
  （3 例），全量 **1168 例 0 失败** + `assembleDebug` 通过（2026-09-30 考试页落地后重跑）。
  **考试页（模拟器冒烟，2026-09-30）**：占位基线（假课程名，验完即删）+ `uiautomator dump`
  逐条核对——学习 hub「成绩与考试 → 考试安排」入口 → 考试页（换入口后复验过一次，
  课表 hub 里那条已删干净）；提醒卡开关联动间隔行可点；列表头、待考升序、
  「已结束」分组与置灰、日期「几月几日 + 周几」；顶栏「更新」在无凭证时落提示条
  「还没保存统一认证账号，去「我的 → 教务账户」登录一次即可自动导入」；导入卡跳教务导入页。
  **真机点验由用户执行**（模拟器测不了）：真实凭证下的「更新」拿到教务那份列表、
  真实发布/调整时的「新增/调整」标与通知落点、开关与权限弹窗、间隔轮选、
  成绩设置页与考试页两处入口、日历/CSV 里考试标题带「（考试）」。

---

### 4.34 校历（仓库镜像，2026-09-30，P6；UI 见 §3.3 校园服务）

**背景与决策**：校历在学校侧不是结构化数据——智慧校园平台 `m.juwp.edu.cn/wxi/calendar/list`
里就是一张图片，且「发现新图」的链路（应用中心 `appcenter/list` → 校历项的 sibeApp 入口）
绑微信签发的一次性 JWT（带出口 IP 校验），App 端无法自主走完；图片直链
`/wxi/resource/showImg` 虽然免登录，但 path 每学年变（`/image/20260830/<hash>.png`），
硬编码活不过一次学年更替。§2.2 旧条目「校历（需额外教务接口）」就此作废，
**改为免登录的仓库镜像**：开发者每年把新校历图放进公开仓库，App 拉取展示。

**数据源三层**（优先级从高到低）：

| 层 | 内容 | 时机 |
|----|------|------|
| 仓库镜像 | `docs-public/calendar/index.json`（`{"year","file"}`）+ 校历图，raw.githubusercontent.com 直链 | 冷启动静默刷（7 天闸门 `AutoSyncRules.shouldAttemptAt`）+ 页内刷新按钮（force） |
| 本地缓存 | `filesDir/campus_calendar/calendar_<year>.<ext>`（原子落盘：临时文件写完改名；下载后 `inJustDecodeBounds` 探测，坏的当没下） | 有缓存就用，学年没变不重复下载 |
| 内置兜底 | `res/drawable-nodpi/ic_campus_calendar.jpg`（1375×1020，约 400KB） | 首装/无网/镜像挂了——永不空屏 |

**规则与校验**（`domain/CampusCalendarRules`，纯 JVM）：index.json 大小上限 4KB；
学年格式 `\d{4}-\d{4}` 且后段 = 前段 + 1；文件名白名单 `[a-z0-9_-]+\.(png|jpg)`
（挡路径穿越与怪文件）；测试钉住脏 JSON 拒收 / 白名单 / 闸门边界
（`CampusCalendarRulesTest` 11 例）。

**UI**（`ui/campus/CampusCalendarScreen`）：进页即整图（满宽按真实比例，图源 = 已缓存镜像图
→ 内置兜底图），点图开全屏看图（复用 `ImageViewerDialog` + 新 `ImageSource.CampusCalendar`
分支；**兜底态也照开**——分支解不到文件回退资源图，点击放大永远可用），双指 1f–5f 缩放 +
拖动。页尾一行「校历内容以学校官方发布为准（非官方整理，可能滞后）」+ 图源说明
（镜像没下到时只说内置图归属学年，不编造）。刷新失败 snackbar 一条，其余静默
（校历是锦上添花的信息，不打扰）。

**年度更新流程**（开发者，约 2 分钟）：微信/PC 微信打开校历拿新图 → 存
`docs-public/calendar/` → 改 `index.json` 的 `year` 与 `file` → push。
所有用户下次冷启动自动换图，**无需发版**。历史图在仓库里天然留存。

**明确不做**：不引入图片库（一张图无 LRU 必要）；不做历史校历切换（仓库可看）；
不做「在浏览器打开」（无独立官方 URL 可分享）；不碰教务/微信 token 链路。

**已验证**：`CampusCalendarRulesTest` 11 例 + 全量 118 类 1164 例 0 失败 +
`assembleDebug`；模拟器冒烟（2026-09-30，juwptest）：校园服务入口 → 校历页
（内置兜底图渲染、图源说明与免责一行）→ 点图全屏 → 关闭，全通过；真机点验待用户执行
（真机重点：冷启动联网后镜像图下载、跨学年自动换图需下学年验证）。

---

### 4.35 选课（选课日志与轮次，2026-09-30，P6；UI 见 §3.19）

**数据源**（2026-09-30 实测，**都不带 .do**，layui JSON `{code,count,data}`）：

| 链 | 请求 | 说明 |
|----|------|------|
| 选课日志壳页 | `GET /jsxsd/xkgl/loadXsxkjgList?lx=xkrz` | 学期下拉（`select#xnxqid`）在这里；选中项 = 教务当前学期 |
| 选课日志数据 | `GET /jsxsd/xkgl/loadXsxkjgList?lx=xkrz&type=list&xnxqid=<学期>&pageNum=&pageSize=` | 字段：`kc_mc` 课程名 / `kch` 编号 / `xm` 教师 / `zxs` 总学时 / `xf` 学分 / `kclb_mc` 属性 / `kcxz_mc` 性质 / `ktmc` 教学班 / `yx_mc` 学院 / `sksj` 上课时间（`<br>` 多行）/ `skdd` 地点 / `shzt`·`yy` 审核 |
| 选课轮次 | `GET /jsxsd/xsxk/xklc_list_data` | 字段名来自页面表格定义（非选课期无样本）：`xqmc` 学期 / `xklc_mc` 名称 / `xksj` 选课时间 / `jx0502zbid` 轮次 id / `yxzt=='1'` 可预览。**`code` 是字符串 `"0"`**（日志接口是数字） |

实测：2026-2027-1 有 12 条、2025-2026-2 与 2024-2025-1 各 16 条；轮次与预选在非选课期
`count=0` 属正常。另探测到「退课日志」（`lx=tkrz&cxsj=tkjg`）与「学生预选管理/查询」
（`/jsxsd/xkgl/xsyxgl?type=list`、`/jsxsd/xkgl/loadYxxkList`）同构可用，本期不做
（预选当前无数据、查询页学期下拉为空）。

**落库与同步**：
- Room **v17** 新表 `course_selections`（实体 `CourseSelectionEntity`，索引 `term`），
  按学期整体替换（`SelectionRepository.replaceTerm`，同 `scores` 口径）。
- `SelectionSync`（OkHttp + `CasSession`，不开窗口）：先 GET 壳页读选中学期（不猜学期；
  2026-10-04 起带 12 小时 TTL 壳页缓存，判据 `AutoSyncRules.shellTermCacheUsable` 与考试共用，
  非 force 复用上次抓到的学期，省一次壳页 GET），
  抓「当前学期 + 紧邻下一学期」（选课多发生在学期末选下学期，光抓当前学期会漏掉
  「已选下学期」）；**先全部抓到内存、再一次性落库**——中途失败不留半套；
  成功才落 `selection_sync_millis`（失败不写、不重试，同成绩/考试纪律）。
- 轮次落 `SelectionRoundsStore`（`filesDir/selection_rounds.json` 快照，`ExamSnapshotStore` 同款，
  但**允许存空**——`count=0` 是权威状态，空快照才会让页面如实显示、让过期提醒被撤销）。

**解析**（`data/jw/SelectionParser.kt`，fixture = 实抓样本）：`code` 用 `content` 读
（数字/字符串两种形态都认）；`sksj`/`skdd` 的 `<br>` 转 `\n`；`shellTerm`/`shellTerms`
按 `select#xnxqid` 收窄（该页有多个 select）。测试 `SelectionParserTest` 11 例。

**提醒**（`ui/reminder/SelectionAlertReminder.kt`）：
- 提醒点 = 轮次开始前 30 分钟（`START_LEAD_MS`）+ 截止前 6 小时（`END_LEAD_MS`）；
  提前量已过但事件未结束的点**钳到当下**发；事件已结束的点不生成
  （`SelectionRounds.reminderPoints`，纯 JVM，`SelectionRoundsTest` 12 例）。
- 调度同上课/骑行提醒：`AlarmManager.setAlarmClock` 单点精确闹钟（到点落
  `SelectionAlertReceiver` → 一次性 Work 核对）+ 冷启动重排 + 开机补排
  （`ReminderBootReceiver` 顺带叫它）+ 周期核对搭 `ScoreAlertReminder` 的周期任务
  （核对间隔 2026-10-04 起独立设置 `selection_alert_interval_hours`，轮次提醒本身仍由
  轮次时刻的精确闹钟驱动）。
- 已发键（`轮次id|start` / `轮次id|end`）落 DataStore，闹钟与周期核对共用去重；
  发不出去不落键（同作业提醒）。通知 channel `selection_alert`，id 1010 / requestCode 3010
  （与既有通知互不覆盖）。
- **时间解析不了就不排提醒**：`xksj` 的真实格式要等选课期才有样本，`parseTimeRange` 容错支持
  「日期+时刻对 / 仅日期对 / 单个时刻」，解析失败回退原文展示。

**进入选课**：`JwImportMode.Selection`（`JwImportScreen` 的第三个模式）——登录后自动落
`/jsxsd/xsxk/xklc_list`，底部只有一个「选课中心」入口、**没有导入按钮**。2026-10-01 起该
WebView 模式只是**备用入口**（选课页「教务网页」行 + 应用内中心接口未接入时的逃生门）；
选课/退课主路径在应用内选课中心（§3.22），WebView 里仍由用户自己操作，App 不注入。

**轮次直达**（2026-10-01；同日收口后语义变化）：选课页轮次行现在直达**应用内选课中心**
（轮次 id 走二级页 `focusItemId` 通道，§3.22）；带轮次 id 开教务 WebView
（`newXsxkzx?jx0502zbid=<id>&isallsc=`，轮次页 JS `jrxk` 的落点；`JwUrls.selectionRoundUrl`
拼装，id 过 `ROUND_ID_PATTERN`（字母数字下划线短横 1–64 位）白名单，不合法退回列表；
`JwImportActivity.start(..., selectionRoundId =)` → `JwImportScreen.autoNavTarget`）
只剩应用内中心「打开教务页面（备用）」一处调用。
未实测：教务从列表进选课时先 POST `mzlist.do`（免责声明检查）再跳，直接进可能与那一步有关，
真进不去就退回列表（窗口底部的「选课中心」按钮）。预览页 `yxxsxk_index?jx0502zbid=` 同法可拼。

**自动落页的触发点**（2026-10-01 修「进入选课中心卡加载中」）：见 §4.4.1——落页触发用
`onPageCommitVisible` 优先、`onPageFinished` 兜底，配 10 秒看门狗，任何路径不悬在 Loading。

**红线**：`/jsxsd/xkgl/Xsxkjg_tk.do`（申请退课，POST，`lx=tk`/`lx=ww`）与
`/jsxsd/xsxk/mzlist.do`（免责声明查询，POST）**绝不触碰**；探测脚本与 App 全链路只读。

**脚本**：`scripts/fetch_selections.py`（第 9 个正式脚本）——同一组接口的参考实现与
fixture 来源，默认抓「当前学期 + 下一学期」并输出 `out/selections.json`（含轮次原文）。

**已验证**：`SelectionParserTest` 11 例 + `SelectionRoundsTest` 12 例 +
`fetch_selections.py` 实抓通过（2026-2027-1 12 条）+ 全量测试与 `assembleDebug` 通过；
**轮次展示与提醒要等选课期才能真机复验**（`xksj` 格式、进选课页、提醒到点）——
这是本功能唯一的未验证面。

**明确不做**：学生预选（接口在、当前无数据）；退课日志展示；把选课记录写进课表；
自动退课（写操作边界见 §4.36「只加课」）。

---

### 4.36 应用内选课与抢课（2026-10-01 规划，用户已拍板；窗口期联调）

**背景**：用户要求把「WebView 只读浏览器」升级为应用内选课——自动发现轮次、抓课程列表、
筛选、**预选清单**（§3.20 已实现）、窗口一开**自动抢课**。已拍板：写操作**只做加课**，
退课仍走 WebView；下一次选课窗口在**下个学期**，课程列表与提交接口只能到时实测。

**分阶段**：

| 阶段 | 内容 | 状态 |
|------|------|------|
| A | 修自动落页卡死（§4.4.1）+ 轮次直达 + 预选清单（§3.20） | **已实现**（2026-10-01） |
| B | 抢课引擎骨架：策略 + 引擎 + 面板 + 前台服务 + 通知 + 免责声明（见下） | **已实现（骨架）**（2026-10-01） |
| C | 窗口期联调：一次性探针脚本定位列表/提交/退课接口 → `SelectionCenterParser` + 真实 `SelectionCenterClient`（OkHttp + `CasSession`）→ **字段按实测校正**（列表 UI 骨架已在 B 落地，§3.22）+ 冲突标记 + 手动选课/退课实测 → 抢课接通；补「到点自动开始」开关（`setAlarmClock`，默认关）与服务设备冒烟 | 下个学期 |
| D | 文档与红线改写（含本文） | 随 C 收口 |

**B 阶段实现（2026-10-01）**：

| 件 | 说明 |
|----|------|
| `domain/SelectionCourse` | 候选课程的最小集（`id` 只当不透明主键、容量类字段一律可空——真实字段等窗口期实测） |
| `domain/SelectionGrabPolicy` | 节流/退避/停止条件（纯 JVM）：默认间隔 10s（档位 5/10/20/30/60s）、失败线性退避封顶 60s、连续失败 5 次自动停、单场上限 2 小时；「全部命中」先于「轮次截止」判定 |
| `data/repo/SelectionCenterClient` | 引擎唯一依赖的抽象（`fetchCourses` / `submit` + `SelectionSubmitResult` 三态）；`UnconfiguredSelectionCenterClient` 明说「接口未接入」，**替换点只有 `Graph.selectionCenterClient` 一处** |
| `data/repo/SelectionGrabber` | 会话循环：拉列表 → 按清单匹配 → 逐个提交 → 等间隔；**两种失败口径**：拉取/传输失败计连续失败并退避，提交被拒（名额满/冲突）**不计**、只记日志继续轮询（名额满是抢课期的常态，兜底是轮次截止与时长上限） |
| `data/repo/SelectionGrabStore` | 会话的进程级状态（StateFlow：运行态/进度/最近日志），服务写、面板读；只活内存（进程没了会话就结束了） |
| `ui/selection/SelectionGrabService` + `SelectionGrabNotifier` | 前台服务（`specialUse`，照 `EbikeFreeRideService`）：常驻进度通知（LOW）+「停止」动作 + 收场通知（HIGH）；通知 id 1020/1021、落点 requestCode 3020/3021（与既有 1001–1010 / 3008–3010 / 4002–4004 错开）；一次只跑一场会话，取消路径与正常结束走同一个收尾出口 |
| `ui/selection/SelectionGrabScreen` | 抢课面板（§3.21）：状态卡（轮次 / 目标 / 进度 / 开始-停止）+ 设置（检查间隔 · 须知）+ 日志；「开始」要求**接口已接入 + 有进行中的轮次 + 清单非空 + 未在跑**，任一不满足都给出具体原因 |
| `ui/selection/SelectionCenterScreen` | **应用内选课中心（§3.22）**：状态卡（目标轮次 / 汇总 / 教务原话透传 / 备用入口）+ 搜索筛选（关键词·只看有余额·只看已选·属性·排序）+ 课程卡（余量标签 / 选课-退课逐次确认）；数据源 = `SelectionCenterClient`，未接入时整页说明 |

**红线三条**（B 起生效）：① 写操作**只提交用户清单/点选的课程，只加课不自动退课**；
② 不并发、固定间隔（用户可调）、会话有上限、连续失败自动停止；③ 遇验证码/异常页面
立即停止并通知，**不绕过**教务的任何限制。

**风险声明**（已写进 App 内「抢课须知」，首次进面板必须确认，3 秒阅读锁）：这是本 App 首次
对教务做写操作；自动化选课是否合规由用户自行判断，被教务风控的后果自负；抢课不保证成功
（余量与并发由教务决定），App 只保证按清单、有节制地提交并如实报告结果。

**未验证面（窗口期一并验）**：课程列表与提交接口本身（C 阶段实测）；服务的设备冒烟——
组件按规矩非导出（adb 起不了），而面板入口要求「有进行中的轮次」，非选课期无法从 UI 触发，
故 B 阶段只到「编译 + 单测 + 合并 manifest 核对（服务声明与 `FOREGROUND_SERVICE_SPECIAL_USE`
均在）」为止。

---

### 4.37 U净 洗衣房（美的校园洗衣，2026-10-01，P1+P2 交付；UI 见 §3.23）

**背景**：宿舍洗衣机为美的「U净」平台（美的集团 / 无锡小净共享网络）。官方**无开放 API**，
协议事实来自社区多份独立逆向成果交叉核对：`baijuqi/ujing-mini`（2026-09-22 实机端到端验证，
含 BLE 启动）；`abcde2333/NcepuJw`（Kotlin/Compose，在用；**GPL-3.0——仅参考协议事实，
未复制代码**）；`funcfang/U-Clean-Reserve`、`Huoyuuu/ujing-laundry`（登录 / 查询端点佐证）。
时效（2026-09-30 核）：至少 4 个独立项目近期活跃，端点自 2024 年未变。

**网关与指纹**：`https://phoenix.ujing.online/api/v1`，壳 `{code, message, data}`（`code == 0`
成功；`401 / -99` = 会话过期）。取码 / 登录组 `x-app-code: ZI`（2.4.3）、业务组 `BI`（2.4.2）
+ `weex-version: 1.1.30`；**无客户端签名**（无需逆向任何加密函数）。服务端对 code 宽容
（ZA/BA 亦被验证可用）。收紧校验时先动 `UjingApiConfig`（重新抓包对齐）。

**模块落位**：

```
domain/UjingState.kt             纯逻辑：fen2yuan / scanBadge（空闲·使用中·故障·离线）/
                                 moduleTypeLabel（P3 通道判据）/ 状态映射 · 启停取消窗口 ·
                                 受理判定（0 / 1703+0）/ 倒计时文案 / boardLine——JVM 直测
data/ujing/UjingApiConfig.kt     网关与两组指纹（版本号全在这里）
data/ujing/UjingApi.kt           裸 OkHttp：send 剥壳、两组头按调用点显式切换；无日志拦截器
data/ujing/UjingModels.kt        壳 / DTO / 宽容数值与布尔 / 会话失效判定（401·-99·文案兜底）
data/ujing/UjingSessionStore.kt  secure_ujing（加密 + Keystore 自愈；两套备份规则已排除）
data/ujing/UjingHouseStore.kt    收藏洗衣房（普通 prefs：storeId|名称）
data/ujing/UjingOrderStore.kt    在案订单快照（普通 prefs + StateFlow 双写，QzxyWateringStore
                                 模式；**订单号必须跨进程存活**——重启丢 orderId 服务端就
                                 无从签发指令，社区实测教训。快照另带 createdAt（下单时刻，
                                 支付窗口倒计时基准）与 durationSeconds（模式总时长，
                                 洗涤进度条分母），两者只在下单时写、刷新原样保留）
data/ujing/UjingAlipay.kt        官方支付宝 SDK 薄封装：PayTask.payV2 → 四态（9000/8000/6001/其余，
                                 8000 确认中必须对表后定文案）；
                                 orderInfo 服务端签发，App 不经手资金
data/ujing/UjingRepository.kt    编排：登录 / 看板 / 扫码 / 套餐 / 下单 / 支付参数 / 详情 /
                                 取消 / 云端启动；loggedIn 流与 markExpired（清 token 留手机号）；
                                 下单 / 刷新 / 取消各 upsert 一笔历史账单（取消标注 60）
data/ujing/UjingDraftStore.kt    扫码下单草稿（**进程内，故意不落盘**）：主页与下单页两个
                                 独立窗口之间的扫码结果与所选档位交接；submit → 主页取走执行
data/ujing/UjingHistoryStore.kt  历史账单（prefs JSON ≤50 笔 + StateFlow，upsert 纯函数可 JVM 测）
ui/ujing/UjingScreen|ViewModel   主页与状态：看板并发限 4（Semaphore）、60s 冷却双保险、
                                 使用须知窗口（3 秒锁 + 一周静默）、订单卡（本地秒级倒计时 +
                                 页面可见 15s 轮询）、扫码成功开草稿发导航事件；
                                 **下单 / 支付的执行点在这里**（下单页交回选择后走同一条链）；
                                 loggedIn 收集器统一接「登录成功补数据」（订单对表 + 看板重拉）
ui/ujing/UjingOrderScreen        下单页：档位单选（AppCard 高亮即选中）· 水温 / 投放 chips ·
                                 贴底动作条（摘要 + 下单）· 确认弹层；确认 = submit + 关窗；
                                 草稿丢失（进程回收后重进）给一句话引导返回
ui/ujing/UjingHistoryScreen      历史账单列表（最近落定排序 · 状态文案 · 金额）+ 清空（二次确认）
ui/reminder/UjingDoneReminder    洗衣完成提醒：setAlarmClock 单点 + Worker 查单（50 → 发通知；
                                 40 → 按新剩余重排；其余撤销），渠道 ujing_done，
                                 Receiver/Worker 类名纪律与选课提醒同款
```

**端点（P1+P2 用到）**：`captcha` → `login`（→ JWT）→ `stores/near`（经度拼写是 **`lont`**）
→ `devices/reserve?storeId=` → `devices/scanWasherCode`（**二维码原文直传**）→
`app/washer/devices/program/info?deviceId=`（storeId / 套餐 / 下单开关由服务端动态下发，
声明开关有 `isWashTemperatureEnable` / `isForceDetergent` / `isForceDisinfectant`）→
`orders/create`（body：type / deviceTypeId / deviceId / deviceWashModelId / storeId 为基础
五字段，**实测可过**；可选字段按「服务端声明需要才发」——`washTemperatureId`（机型开放水温
且非烘干，枚举 1 常温 / 2=30℃ / 3=40℃ / 4=60℃）、`wp_detergentGearId` / `wp_disinfectantGearId`
（**投放加购**：非烘干机，用户在扫码结果卡勾选「洗衣液 / 消毒液」，默认按机型强制投放声明
勾好，勾了才发标准档 1 / 4——档位固定不可选，其余档位的价格语义没实样；加购费用以支付宝
实付为准）、`dryTime`（仅烘干机，协议口径 = 模式时长 / 10））→
`payment/arguments?channel=alipay`（→ `payInfo.orderInfo` 给 SDK）→
`orders/{id}/detail?additional=price`（status 为**字符串**；remainTime 秒；`additional=price`
照抄官方抓包形态）→ `app/payment/{id}/lastPayStatus` → `orders/{id}/cancel` →
`orders/{id}/control/start`。**实测（2026-10-01）**：匿名
`scanWasherCode` 返回 `code=401 "JWT token is missing"`（三种头组合一致）——只读也必须
登录，**不存在免登路径**。蓝牙机型的 BLE 协议（Nordic / Cypress 通道、三个坑）在
`docs/ujing-plan.md`（本地），P3 实现时搬进本节。

**红线**（与 `.agents/rules/ujing.md` 同口）：不绕过支付、只操作本人有使用权的设备；写操作
零自动重试 + 二次确认；登录节制（高频登录会被服务端限制数十分钟，社区实测）；不后台轮询
（订单轮询限"页面可见 + 15s"）；token 只进 `secure_ujing`、绝不进日志；**启动前必须先查订单
状态**（服务端对已运行订单返回 `{}` 静默拒绝，status ∈ {40,50} 直接禁启动按钮）；已有在案
订单不允许叠单；不复制 GPL/AGPL 参考项目的代码。

**已验证（2026-10-01，P1+P2）**：`UjingStateTest` 13 例 + `UjingApiParseTest` 10 例 +
`UjingOrderStateTest` 6 例 + `UjingOrderParseTest` 8 例；全量 **1262 例 0 失败**；
`assembleDebug` 通过并装机（debug 包、无线调试真机）。**真机点验待用户执行**：
P1——登录（短信）→ 添加洗衣房 → 看板出数 → 扫码识别（顺看「通信模块」行）；
P2——找一台空闲机器走 下单 → 支付宝付款 → 订单卡倒计时 →（云端机型）启动；
体验补——开放水温的机器出温度 chips、洗涤中进度条、洗好通知（可锁屏等它到点）。
遗留待实测：② JWT 有效期（登录后 base64 解 `exp`）；③ 学校机型 `moduleType`
（1/5 = 纯蓝牙 → P3 走 BLE；0/2/3/4/7 → 云端启动已通）。

---

### 4.38 收件箱（2026-10-06，P6；UI 见 §3.24）

应用内重要事实的留痕层。**定位：旁路，不反向打断业务链**——投递只发生在各业务链
「数据已到手并完成对比」的结果点上，收件箱链路自己不发任何网络请求；写操作内部吞错
（只留日志），留痕失败不能让成绩写库、调课检查、余额核对跟着失败。

**数据**：Room v18 新表 `inbox_messages`（迁移 `MIGRATION_17_18`：可空列不带 NOT NULL
不带 DEFAULT、`dedupeKey` 唯一索引与实体 `@Index` 逐字对齐）。`InboxRules`（纯逻辑，
`InboxRulesTest` 钉住）管投递决策与保留策略：

- **去重键**（`dedupeKey` 唯一索引 + upsert，同键重复投递只留一行）：
  考试 = `exam|` + `ExamChangeDetector.keyOf`（courseNo|date，时间/考场再变 = 内容刷新）；
  成绩 = `score|term|name|新分数`（分数参与键，改分是新事实）；
  调课 = `schedule|` + 差异指纹（与「已阅指纹 + 待处置指纹」双闸同源）；
  轮次 = `selection|` + 提醒键（`轮次id|start/end`，与已发键同源）；
  余额 = 每来源固定一条（`balance|power|low` / `balance|ykt|low`）。
- **投递三态**：无键插入（未读）；未删重投刷新内容并**重新置未读**（状态型靠它实现
  「已读后次日仍低 → 重新未读」，频率由各链每日闸门限制）；**已删重投只刷新不复活**
  （用户删掉就是不想再看，事件真变了键不同才会以新消息出现）。
- **状态消解**：余额回升（成功取到数且不再偏低）→ 整条移除；取数失败绝不误删。
- **保留策略**（冷启动 purge 一次）：软删 30 天清除 / 已读 90 天清除 /
  未删总量 200 条保最新；列表查询同 LIMIT 200。

**五个挂点**（与系统通知互不依赖，通知开关只管通知）：

| 挂点 | 说明 |
|------|------|
| `ExamSync` 落快照后 | 检测出的每条变更入箱（手动「更新」与周期检查同源，谁先发现都入） |
| `ScoreSync` 写库后 | 出分/改分入箱；首导豁免（基线不是变动）。**changes 恒计算**（2026-10-06 与 `scoreAlertEnabled` 解耦：纯内存比对零成本，`alertOn` 只在提醒层管通知） |
| `ScheduleSync.check` 落待处置缓存处 | 差异入箱；处置页合并/忽略完成后 `markKindRead(SCHEDULE_CHANGE)` 标已读 |
| `SelectionAlertReminder` 核对到期点处 | 与通知共用同一份文案（`reminderCopy`，两处各写迟早漂）。已发键在「通知真发出」或「通知被系统整体关闭」时都落（后者事实已进收件箱，且不落键会让重排闹钟把这个已过去的点反复排成立即触发的空转）；只有「通知开着但发送异常」留给下一轮 |
| `BalanceAlertReminder.checkPower/checkYkt` | 低于阈值入箱（状态型）、回升消解；正文与系统通知同一份 |

**通知总开关的口径（2026-10-06 修正）**：`ScoreAlertReminder.check` 与
`BalanceAlertReminder.check` 原先在入口处 `areNotificationsEnabled()` 早退——通知全关
连核对都不跑，收件箱跟着失效，而这批用户恰恰最依赖收件箱。现在核对照跑、入箱照投，
通知开关只拦「发通知」这一个动作。

**范围边界**：时刻型提醒（上课/作业截止/洗衣完成/免费时长/抢课进度）**不入箱**——
过期即无用，留痕只堆垃圾；收件箱只收「可回查的事实」。教务通知公告没有抓取链路，
不收。本机 Room 存储、不云同步、随重装清零。开关语义：各链自己的运行开关关了 →
链不跑 → 不入箱；链在跑但系统通知关了 → 照常入箱（这正是收件箱相对通知的增量价值）。

---
