# DESIGN §4.33–§4.44 · 技术架构：提醒 · 校历 · 选课 · U净 · 收件箱 · AI 调课 · 教学日历/实习基地/评教

> 本文档是 [DESIGN.md](../DESIGN.md) 的拆分件。代码注释里的「DESIGN §x.y」章节号一字未改，索引见 DESIGN.md 的映射表。

## 4. 技术架构（续 · §4.33–§4.46）

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
- **考试变动提醒**在「我的 → 学习 → 学业数据 → 考试安排」（原「成绩与考试」节，2026-10-07 并入「学业数据」）——同日（2026-09-30）的
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
  逐条核对——学习 hub「学业数据 → 考试安排」入口 → 考试页（换入口后复验过一次，
  课表 hub 里那条已删干净）；提醒卡开关联动间隔行可点；列表头、待考升序、
  「已结束」分组与置灰、日期「几月几日 + 周几」；顶栏「更新」在无凭证时落提示条
  「还没保存统一认证账号，去「我的 → 教务账户」登录一次即可自动导入」；导入卡跳教务导入页。
  **真机点验由用户执行**（模拟器测不了）：真实凭证下的「更新」拿到教务那份列表、
  真实发布/调整时的「新增/调整」标与通知落点、开关与权限弹窗、间隔轮选、
  成绩设置页与考试页两处入口、日历/CSV 里考试标题带「（考试）」。

---

### 4.34 校历（App 直取教务处官网，2026-09-30 立 / 2026-10-07 改主源；UI 见 §3.3 校园服务）

**背景与决策**：校历在学校侧不是结构化数据——智慧校园平台 `m.juwp.edu.cn/wxi/calendar/list`
里就是一张图片，且「发现新图」的链路（应用中心 `appcenter/list` → 校历项的 sibeApp 入口）
绑微信签发的一次性 JWT（带出口 IP 校验），App 端无法自主走完；图片直链
`/wxi/resource/showImg` 虽然免登录，但 path 每学年变（`/image/20260830/<hash>.png`），
硬编码活不过一次学年更替。§2.2 旧条目「校历（需额外教务接口）」就此作废，
**改为免登录的仓库镜像**：开发者每年把新校历图放进公开仓库，App 拉取展示。
2026-10-07 复核并简化：学校侧三条入口里只有**教务处官网 `jw.juwp.edu.cn` 通知公告**
免登录公网可取（高清原图 3438×2552）；PC 门户的 `/calendar_portal/getCalendar` 实测是
空的个人日历；强智教务另有「教学周历查看」（`/jsxsd/jxzl/jxzl_query`，周次 × 日期表，
需登录、非海报内容）——此处皆不采用（该表 2026-10-07 起改用于「导入课表后校准开学日」，
见 §4.42）。**定案：App 直接读教务处官网为主源**（免登录、免维护），
仓库镜像降为兜底，PC 脚本 `sync_calendar.py` 退居应急工具（见文末「更新流程」）。

**数据源四层**（优先级从高到低）：

| 层 | 内容 | 时机 |
|----|------|------|
| 学校官网（主源） | `jw.juwp.edu.cn` 通知公告「20XX-20XX学年校历」文章的高清原图（免登录；`domain/CampusCalendarSchoolRules` 解析） | 冷启动静默刷（7 天闸门 `AutoSyncRules.shouldAttemptAt`）+ 页内刷新按钮（force） |
| 仓库镜像（兜底） | `docs-public/calendar/index.json`（`{"year","file"}`）+ 校历图，多源直链（`domain/RepoMirror`：gh-proxy 加速 → jsdelivr → raw 兜底）；**已停日常维护** | 官网不可达时逐源尝试 |
| 本地缓存 | `filesDir/campus_calendar/calendar_<year>.<ext>`（原子落盘：临时文件写完改名；下载后 `inJustDecodeBounds` 探测，坏的当没下） | 有缓存就用，学年 + 文件名没变不重下（`matchesLocal`） |
| 内置兜底 | `res/drawable-nodpi/ic_campus_calendar.jpg`（1375×1020，约 400KB） | 首装/无网/全部源挂了——永不空屏 |

**规则与校验**（`domain/CampusCalendarRules`，纯 JVM）：index.json 大小上限 4KB；
学年格式 `\d{4}-\d{4}` 且后段 = 前段 + 1；文件名白名单 `[a-z0-9_-]+\.(png|jpg)`
（挡路径穿越与怪文件）；测试钉住脏 JSON 拒收 / 白名单 / 闸门边界
（`CampusCalendarRulesTest` 12 例）。2026-10-06 起走 `RepoMirror` 多源（gh-proxy 加速 →
jsdelivr → raw 兜底）逐源尝试：原实现单源直连 raw（ref 写 `master`，raw 侧按默认分支
旧名兼容解析、GitHub API 不认），无代理环境经常不可达——可达性全看网络。官网取数规则
独立在 `domain/CampusCalendarSchoolRules`（纯 JVM：通知公告列表解析 / 文章取图 `orisrc` /
文件名推导；`CampusCalendarSchoolRulesTest` 5 例）。

**UI**（`ui/campus/CampusCalendarScreen`）：进页即整图（满宽按真实比例，图源 = 已缓存校历图
→ 内置兜底图），点图开全屏看图（复用 `ImageViewerDialog` + 新 `ImageSource.CampusCalendar`
分支；**兜底态也照开**——分支解不到文件回退资源图，点击放大永远可用），双指 1f–5f 缩放 +
拖动。页尾一行「校历内容以学校官方发布为准（非官方整理，可能滞后）」+ 图源说明
（图没下到时只说内置图归属学年，不编造）。手动刷新补进度与结果反馈（进度圈 +
「已更新 / 已是最新 / 失败」提示；成功不再全静默——2026-10-06 用户报「点刷新没有
反应」）。冷启动仍静默（校历是锦上添花的信息，不打扰）。

**更新流程（2026-10-07 定案：用户端零维护）**：各端刷新时 App **直取教务处官网**——
新学年公告一发，各端在下一个刷新周期自动换图，**无任何维护动作**（不用跑脚本、不用
发版、不用计划任务）。应急通道：万一官网改版导致 App 解析失效，跑一次
`scripts/sync_calendar.py --from-file 图 --year 20XX-20XX`（或 `--dry-run` 先看）把
镜像顶上去——镜像兜底链路仍在，各端会经它换图；解析修复随下一次发版。历史图在仓库里
天然留存（镜像停更不影响主链）。

**明确不做**：不引入图片库（一张图无 LRU 必要）；不做历史校历切换（仓库可看）；
不做「在浏览器打开」（无独立官方 URL 可分享）；不碰教务/微信 token 链路。

**已验证**：`CampusCalendarRulesTest` 12 例 + `CampusCalendarSchoolRulesTest` 5 例 +
全量 1464 例 0 失败 + `assembleDebug`；模拟器冒烟（2026-09-30，juwptest）：校园服务入口
→ 校历页（内置兜底图渲染、图源说明与免责一行）→ 点图全屏 → 关闭，全通过；真机点验
待用户执行（刷新直取官网、跨学年自动换图需下学年验证）。

---

### 4.35 选课（选课日志与轮次，2026-09-30，P6；UI 见 §3.19）

**数据源**（2026-09-30 实测，**都不带 .do**，layui JSON `{code,count,data}`）：

| 链 | 请求 | 说明 |
|----|------|------|
| 选课日志壳页 | `GET /jsxsd/xkgl/loadXsxkjgList?lx=xkrz` | 学期下拉（`select#xnxqid`）在这里；选中项 = 教务当前学期 |
| 选课日志数据 | `GET /jsxsd/xkgl/loadXsxkjgList?lx=xkrz&type=list&xnxqid=<学期>&pageNum=&pageSize=` | 字段：`kc_mc` 课程名 / `kch` 编号 / `xm` 教师 / `zxs` 总学时 / `xf` 学分 / `kclb_mc` 属性 / `kcxz_mc` 性质 / `ktmc` 教学班 / `yx_mc` 学院 / `sksj` 上课时间（`<br>` 多行）/ `skdd` 地点 / `shzt`·`yy` 审核 |
| 选课轮次 | `GET /jsxsd/xsxk/xklc_list_data` | 字段名同页面表格定义（2026-10-08 窗口期已实抓）：`xqmc` 学期 / `xklc_mc` 名称 / `xksj` 选课时间 / `jx0502zbid` 轮次 id / `yxzt=='1'` 可预览；真行还带 `xkkssj`（开始）/ `xkjzsj`（截止）/ `sfxkxm` / `xkzt` 等列。**`code` 是字符串 `"0"`**（日志接口是数字） |

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
- **时间解析不了就不排提醒**：`xksj` 真实格式已实测 = `2026-10-08 10:00~2026-10-12 23:59`
  （`~` 无空格；夹具在 `SelectionRoundsTest`），`parseTimeRange` 容错支持
  「日期+时刻对 / 仅日期对 / 单个时刻」，解析失败回退原文展示、跳过提醒。

**进入选课**：`JwImportMode.Selection`（`JwImportScreen` 的第三个模式）——登录后自动落
`/jsxsd/xsxk/xklc_list`，底部只有一个「选课中心」入口、**没有导入按钮**。2026-10-01 起该
WebView 模式只是**备用入口**（选课页「教务网页」行；应用内中心接入后不再兼作逃生门）；
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

**已验证**：`SelectionParserTest`（含 2026-10-08 实抓的轮次全字段夹具）+ `SelectionRoundsTest`
（含 `xksj` 真格式 `2026-10-08 10:00~2026-10-12 23:59` 的夹具）+ `fetch_selections.py` 实抓
通过（2026-2027-1 12 条）+ 全量测试与 `assembleDebug` 通过。**`xksj` 格式与进选课页
2026-10-08 窗口期已实测**。

**仍未验证**（AGENTS.md 待点验表跟踪）：轮次提醒到点的真机复验；`JwImportMode.Selection`
的 WebView 备用入口在窗口期的表现。

**明确不做**：学生预选（接口在、当前无数据）；退课日志展示；把选课记录写进课表；
自动退课（写操作边界见 §4.36「只加课」）。

---

### 4.36 应用内选课中心与预购匹配（2026-10-01 规划；2026-10-08 窗口期落地；同日 v2 浏览筛选、v3 去自动抢课）

**背景与三次拍板**：用户要求把「WebView 只读浏览器」升级为应用内选课。窗口期
（2026-2027-1 学期自主选课，2026-10-08 10:00 ~ 10-12 23:59）实测接入教务接口，
三轮口径收敛：
1. **v1（C 阶段）**：真身 client + 解析器 + 写路径纪律落地；列表要求关键词（空查询回 0 行）。
2. **v2（同窗口期二轮）**：**进页即浏览**（空条件注入 `kch=0`）+ 筛选面对齐教务列表页
   （课程/教师/类别/星期/节次/三开关）+「加载更多」+ 卡片字段对齐教务列。
3. **v3（同日，用户拍板）**：**自动抢课整条链下线**——自动化写操作的风险与未知太大
   （教务风控、并发语义、失败重试边界都兜不住）。预选清单改「**预购匹配**」（§3.20）：
   命中课程优先显示，**选课 / 退课永远由用户逐次确认**。下线清单与理由见 §3.21。

**分阶段**：

| 阶段 | 内容 | 状态 |
|------|------|------|
| A | 修自动落页卡死（§4.4.1）+ 轮次直达 + 预选清单（§3.20） | **已实现**（2026-10-01） |
| B | 抢课引擎骨架（策略 + 引擎 + 面板 + 前台服务 + 通知 + 免责声明） | **已下线**（2026-10-08 v3；§3.21 下线记录） |
| C | 窗口期联调：探针定位列表/提交/退课接口 → `SelectionCenterParser` + 真实 `SelectionCenterHttpClient` → 字段按实测校正 | **已实现**（2026-10-08）；真实提交/退课的返回语义待真机点验 |
| D | 文档与红线改写（含本文） | **随 C/v3 收口**（2026-10-08） |

**红线（v3 收窄后，四条）**：① **写操作只剩用户逐次确认的加课与退课**——没有自动提交、
没有后台轮询、没有到点自动执行；② 遇验证码 / 异常页面立即停手，**不绕过**教务的任何限制
（验证码开关 `sfyzmxk` 为 `"1"` 直接拒绝）；③ **写请求不自动重登重发**——会话被顶就停手
让用户重登（防重复提交），读路径才允许重登重试一次；④ `/jsxsd/xkgl/Xsxkjg_tk.do`
（申请退课，老入口）与 `/jsxsd/xsxk/mzlist.do`（免责声明）任何路径都不碰；脚本永远只读
（探针也不例外）。

**C 阶段实现（2026-10-08，窗口期实测）**：

| 件 | 说明 |
|----|------|
| `data/jw/SelectionCenterParser` | 列表/提交/退课应答解析（夹具 = 实抓样本）：列表 = DataTables 形态 `{aaData, iTotalRecords…}`；提交/退课 = `{success, message…}` 三态（`success` + message 含「还有」= 冲突未决，按未成功报、**不自动走冲突处置**）；「当前不在选课时间范围内」提示页、强智 404 页、no-open、登录页都转可读错误；课程主键 = `jx0404id\|jx02id\|cfbs` 拼串；验证码开关 `sfyzmxk` 读取 |
| `data/repo/SelectionCenterHttpClient` | 真身（替换点仍是 `Graph.selectionCenterClient` 一处）：列表 = POST `xsxkkc/xsxkBxqjhxk`（**旧版 DataTables 参数，键集是契约——缺了回 404 页**）；提交 = GET `bxqjhxkOper`、退课 = GET `xstkOper`（都照教务页面 JS 口径，空 cfbs 拼字面量 `null`） |
| 会话层 POST 分工 | `CasSession.postForm`（读语义：会话被顶可重登重试一次）与 `CasSession.fetchForAction`（写语义：**不自动重登重发**）——两条纪律别并成一条 |
| `domain/SelectionCourse`（v3 起独立文件） | 字段按实测校正：`courseNo`（kch）/ `remainingSeats`（**syrs 是权威余量**，与 `pkrs−xkrs` 不恒等）/ `conflictNote`（ctsm）/ v2 补 `courseType`·`groupName`·`campus`·`teachMode`（行内 `kctype` 给的就是中文名）；`id` 保持不透明拼串 |
| 列表查询 v2 | 查询面全接入 `SelectionQuery`（关键词/教师/星期/节次/类别/三开关，**服务端 AND 叠加**）；**「先进轮次」是会话前提**——列表请求前会话必须 GET 过 `newXsxkzx`，不进则 404，`fetchPageRetrying` 撞 404 **重进一次自愈**；空条件注入**浏览全量 `kch=0`**（编号全以 0 开头；数字扫描并集 48/48 覆盖，星期扫描会漏无排课课程），「进页即浏览」由此成立 |
| 开关语义实测（v2，决定默认值） | `sfct=true` 把所有带 `ctsm` 的行滤掉——**连「该课堂已选」的已选行一起**（48→15）→ App 默认 `hideConflict=false`（已选课要留在列表里可退；教务页面默认勾选，不跟）；`sfym=true` 滤满员（48→46）默认关；`sfxx` 默认 true 保持既有口径 |
| `SelectionCenterClient`（v2/v3） | `fetchPage` = 界面口径的单页拉取（`SelectionQuery` + `start`/`pageSize`，不合并、不自主翻页）；`fetchCourses` = **预选命中口径**（多关键词逐个查、分页合并去重——选课中心按清单逐条调用，教师关键词本地复核）。`UnconfiguredSelectionCenterClient` 明说「接口未接入」 |
| `domain/SelectionWishHits`（v3） | 预选命中归组（纯 JVM）：教师关键词本地复核（服务端只按课名/编号查）、同一门课归**优先级最高**的那条预选、分组按优先级降序稳定排序；`ids`/`count` 供主列表打标与区块标题 |
| `ui/selection/SelectionCenterScreen`（v2/v3） | 进页即浏览 + 筛选表单（改动不发请求，点「查询」才查）+「加载更多」+ **预选命中区块置顶**（命中卡带徽标 + 归属行；主列表同打标不挪走；匹配失败只在该区块提示 + 重试）+ 卡片字段对齐教务列；本地筛选只剩 只看已选 + 课程属性 |
| `ui/selection/SelectionConfirmDialog`（v3） | 选课/退课共用的二次确认弹窗：课程名 `headlineSmall` 主视觉；高亮块给**开设校区**与**时间冲突**（有冲突 error 色原文 / 无冲突中性；退课换「当前状态：已选」）；其余次级小字；可滚不裁字、按钮 ≥44dp；接 `DialogWindowFadeEffect` |
| `ui/selection/SelectionWishesScreen`（v3） | 预购语义文案 + 进页按清单逐条匹配（每条 1 请求、串行间隔），卡上「本轮已开 N 门」+「去选课中心确认」出口；防重复对照（已选/课表已有）不变 |
| `SelectionFilter` 收窄（v2） | 关键词/余量/类别都迁去服务端（`SelectionQuery`），本地只剩 `onlySelected` + `attribute`；`SelectionCourses.filter` 与测试同步收窄 |

**未验证面（真机点验清单在 AGENTS.md 待点验表）**：真实提交 / 退课的返回语义（映射按页面
JS + 实测样本建，**第一次真实提交才能完全校准**——联调期收教务原话回来对齐）；v2 筛选与
浏览的真机观感（三个开关默认值、节次校验文案、加载更多——本数据一页装得下、第二页路径
未见过真样）；v3 预选命中的请求节奏（清单条数 × 1 请求，进页与手动刷新各一次）与命中
区块/确认弹窗的排版观感。

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
                                 受理判定（0 / 1703+0）/ 倒计时文案 / boardLine · dryerLine
                                 （看板聚合，共用 lineFor）/ isDryer（烘干机判定）/
                                 liveRemainSeconds（本地倒计时换算）——JVM 直测
data/ujing/UjingApiConfig.kt     网关与两组指纹（版本号全在这里）
data/ujing/UjingApi.kt           裸 OkHttp：send 剥壳、两组头按调用点显式切换；无日志拦截器
data/ujing/UjingModels.kt        壳 / DTO / 宽容数值与布尔 / 会话失效判定（401·-99·文案兜底）
data/ujing/UjingSessionStore.kt  secure_ujing（加密 + Keystore 自愈；两套备份规则已排除）
data/ujing/UjingHouseStore.kt    收藏洗衣房（普通 prefs：storeId|名称）
data/ujing/UjingOrderStore.kt    在案订单快照（普通 prefs + StateFlow 双写，QzxyWateringStore
                                 模式；**订单号必须跨进程存活**——重启丢 orderId 服务端就
                                 无从签发指令，社区实测教训。快照另带 createdAt（下单时刻，
                                 支付窗口倒计时基准）、durationSeconds（模式总时长，
                                 洗涤进度条分母）与 statusRemark（服务端状态文案，展示优先；
                                 旧快照无此字段读成空串），三者只在下单/刷新时写）
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
                                 **下单 / 支付的执行点在这里**（下单页交回选择后走同一条链；
                                 confirmPreparedOrder 只在快照**未终结**时才拦，先对服务端再决定，
                                 2026-10-16）；看板每行洗衣机 + 烘干机两条；UjingOrderStatusLine
                                 （状态 + 洗涤剩余，生活页弹窗复用）；loggedIn 收集器统一接
                                 「登录成功补数据」（订单对表 + 看板重拉）
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
状态**（服务端对已运行订单返回 `{}` 静默拒绝，status ∈ {40,50} 直接禁启动按钮）；**未终结**的
在案订单不允许叠单（终结态快照不挡新单：确认前先对一次服务端，2026-10-16）；不复制
GPL/AGPL 参考项目的代码。

**已验证（2026-10-01，P1+P2）**：`UjingStateTest` 13 例 + `UjingApiParseTest` 10 例 +
`UjingOrderStateTest` 6 例 + `UjingOrderParseTest` 8 例；全量 **1262 例 0 失败**；
`assembleDebug` 通过并装机（debug 包、无线调试真机）。**真机点验待用户执行**：
P1——登录（短信）→ 添加洗衣房 → 看板出数 → 扫码识别（顺看「通信模块」行）；
P2——找一台空闲机器走 下单 → 支付宝付款 → 订单卡倒计时 →（云端机型）启动；
体验补——开放水温的机器出温度 chips、洗涤中进度条、洗好通知（可锁屏等它到点）。
遗留待实测：② JWT 有效期（登录后 base64 解 `exp`）；③ 学校机型 `moduleType`
（1/5 = 纯蓝牙 → P3 走 BLE；0/2/3/4/7 → 云端启动已通）。

**2026-10-16 优化**（四项 + 同日收口）：① **终结态订单不挡新单**——`confirmPreparedOrder` 只在快照
未终结时才拦，有非终结快照先 `refreshOrder` 对一次（服务端说结束了就放行，刷新失败保守拦住）；
② 看板每行在洗衣机之外再出**烘干机空闲行**（`UjingState.dryerLine`）；③ 生活页「U净 · 洗衣房」卡的
底部弹窗顶部显示**在案订单状态 + 洗涤剩余**（`UjingOrderStatusLine`；订单对表挪到打开弹窗那一下、
10 秒节流）；④ **烘干机**：`UjingState.isDryer`（type==2 为准，deviceTypeId / 机型名兜底）判定，
下单页标题「烘干时长」、档位以时长为主，**水温 / 投放整块不出**。收口：⑤ `detail.statusRemark`
存进快照、展示走 `UjingState.statusLabel`（服务端文案优先、本地表兜底）；⑥ 进页对表改静默；
⑦ `devices/reserve` 也整包落盘（`ujing_debug/devices_reserve_<storeId>.json`，debug 专用）。
单测：`UjingStateTest` 20 例 + `UjingOrderStateTest` 8 例，在 HEAD（2.9.0）干净副本上
`testDebugUnitTest` + `assembleDebug` 通过（全量 1918 例 0 失败）。

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

**六个挂点**（与系统通知互不依赖，通知开关只管通知）：

| 挂点 | 说明 |
|------|------|
| `ExamSync` 落快照后 | 检测出的每条变更入箱（手动「更新」与周期检查同源，谁先发现都入） |
| `ScoreSync` 写库后 | 出分/改分入箱；首导豁免（基线不是变动）。**changes 恒计算**（2026-10-06 与 `scoreAlertEnabled` 解耦：纯内存比对零成本，`alertOn` 只在提醒层管通知） |
| `ScheduleSync.check` 落待处置缓存处 | 差异入箱；处置页合并/忽略完成后 `markKindRead(SCHEDULE_CHANGE)` 标已读 |
| `SelectionAlertReminder` 核对到期点处 | 与通知共用同一份文案（`reminderCopy`，两处各写迟早漂）。已发键在「通知真发出」或「通知被系统整体关闭」时都落（后者事实已进收件箱，且不落键会让重排闹钟把这个已过去的点反复排成立即触发的空转）；只有「通知开着但发送异常」留给下一轮 |
| `BalanceAlertReminder.checkPower/checkYkt` | 低于阈值入箱（状态型）、回升消解；正文与系统通知同一份 |
| `WenquActivityAlertReminder.check` 发现新活动处（2026-10-08） | 键 `wenqu\|activity\|<本批最大 id>`：一次核对只留一条痕，之后再出现更新的活动才算新事实。通知被 ROM 吞掉时它是唯一兜底 |

**通知总开关的口径（2026-10-06 修正）**：`ScoreAlertReminder.check` 与
`BalanceAlertReminder.check` 原先在入口处 `areNotificationsEnabled()` 早退——通知全关
连核对都不跑，收件箱跟着失效，而这批用户恰恰最依赖收件箱。现在核对照跑、入箱照投，
通知开关只拦「发通知」这一个动作。

**范围边界**：时刻型提醒（上课/作业截止/洗衣完成/免费时长/抢课进度）**不入箱**——
过期即无用，留痕只堆垃圾；收件箱只收「可回查的事实」。教务通知公告没有抓取链路，
不收。本机 Room 存储、不云同步、随重装清零。开关语义：各链自己的运行开关关了 →
链不跑 → 不入箱；链在跑但系统通知关了 → 照常入箱（这正是收件箱相对通知的增量价值）。

---

### 4.39 AI 调课（2026-10-06，P6；UI 见 §3.25）

用户自备 OpenAI 兼容服务的「调课通知 → 结构化改动 → 勾选落库」链路，**单页工作单**
（不做对话，2026-10-07 口径）。红线与口径见 `.agents/rules/ai-schedule.md`，这里只列结构。

> **参考来源的许可证边界**：界面形态参考了 rikkahub，但它是 **AGPL-3.0**、本项目是 MIT。
> 只借鉴交互形态与结构思路，**代码一律自己写**（与项目对 NcepuJw 的既有口径一致）。

**分层**：

| 件 | 职责 |
|----|------|
| `domain/AiSchedulePrompt` | system 提示（**禁客套**）+ **编号清单**（1 起始）+ 学期日历 + 教师名脱敏 + `overridesBrief`（已生效的日期安排）。纯 JVM |
| `domain/AiScheduleTool` | 工具 JSON Schema（`PARAMETERS_JSON`，含 `readback`）+ `tool_calls[].function.arguments` → `ScheduleDiff.Change` + `parsePartial`（流式增量）。纯 JVM |
| `domain/AiScheduleConfig` | 默认地址 / 默认模型 / 备选模型 / `looksVisionModel`（视觉模型**关键字建议**） |
| `domain/ScheduleUndo` | 撤销的反向规划（纯函数，JVM 可测）+ `AppliedEntry` |
| `data/ai/AiScheduleClient` | `/chat/completions`（流式，支持 `images` → 多模态 `content` 数组）与 `/models`；**无日志拦截器** |
| `data/ai/AiCredentialStore` | API Key 加密存储（`secure_ai`） |
| `data/repo/AiImageStore` | AI 图片（`filesDir/ai_img/`，**会话级**，口径 1600 / q80 / 强制 JPEG / 上限 4 张） |
| `data/repo/AiApplyHistoryStore` | AI 调课应用留痕（`filesDir/ai_apply_history.json`，CAP 50、新在前；栈式撤销判据 `latestUndoable`；存档行 = `ScheduleCourseRow`，带行 id 与自定义时间） |
| `ui/ai/AiChatViewModel` + `AiChatScreen` + `AiNoticeCard` + `AiProposalCard` | 单页工作单主页面（2026-10-07 v2：通知卡 + 上次应用条 + 结果区 + 贴底动作条；壳与两张卡拆三个文件） |
| `ui/ai/AiChatModels` | 工作单状态（`AiWorkState` / `AiPhase` / `AiAppliedReceipt` / `aiBarSpec` / `noticeDirty` / `aiHistorySummary`；回执跨轮保留，`proposalSeq` 判归属，`historyId` 对留痕） |
| `ui/ai/AiHistoryViewModel` + `AiHistoryScreen` | 「调课记录」页（`SubpageScreen.AI_HISTORY`）：跨会话撤销，只允许撤最新一条未撤销 |
| `ui/ai/AiThumb` | AI 图片缩略图 + LRU 缓存；全屏看大图走 `ImageViewerDialog`（`ImageSource.AiNotice`） |
| `ui/ai/SchedulePreviewGrid` | 预览网格（纯渲染；`changedCellSet` + `previewWeeksOf` 两个伴生纯函数） |
| `ui/ai/AiSettingsViewModel` + `AiSettingsScreen` | 配置页（含「能力 · 支持图片识别」） |

**工具调用**：工具名 `propose_schedule_changes`，请求体带 `tools` +
**指定函数名的 `tool_choice`**（强制调用）。`modify` / `remove` 只认编号
（`existingIndex`），`old` 由 App 从本机课表取——**不采信模型回抄的旧字段**，
否则 `ScheduleDiff.fingerprint` 匹配不上就静默跳过。编号基准与发给模型的清单同源同序
（当前课表的**非考试**条目）。`readback` 是**给 UI 用**的字段，不参与落库；
`suggestions` / 追问链（`previousJson`、`requirement` 诉求段）已随对话式**整链删除**
（2026-10-07 简化）。

**DeepSeek 专有**：`thinking` 默认开，而指定函数名的 `tool_choice` 在思考模式下返回
**400**——档位关闭时请求体带 `thinking: {"type": "disabled"}` + 指定函数名；档位开启时
退 `tool_choice: auto` + prompt 要求调工具 + `extractJson` 正文兜底。这两个字段
**只对 `baseUrl` 含 `deepseek` 的端点发**（其它 OpenAI 兼容服务不认识该字段）。
默认 `baseUrl` `https://api.deepseek.com/v1`、默认模型 `deepseek-flash`（现行官方名 = V4.1-Flash，2026-09-10 发布、官方多模态；`deepseek-v4-flash` 是 legacy 别名，落盘旧值读取时迁回现行默认），均可改。

**解析防御**：模型幻觉逐条丢弃而不是整批失败——越界编号 / 缺必填字段 / 非法取值 /
修改前后指纹一致 / 与已生效的日期安排等价 → 进 `Result.rejected`；模型没走工具调用时
先从正文 `extractJson` 抽配对完整的 JSON 再解析，再不行把原文放进 `message`，**永不空屏**。
上限：节次 1–11、周次 1–30、星期 1–7。

**落库**：`ScheduleRepository.applyLocalChanges`——2026-10-06 从 `applyScheduleChanges`
拆出**唯一写实现** `applyChangeSet`（事务内逐条删/改/增，不碰基线），
`applyScheduleChanges` = 它 + `refreshBaselineFromDb`（调课检测合并语义，行为不变）；
`applyLocalChanges` = 它 + 同事务内写日期覆盖，返回 `LocalApplyResult(report, overrideIds)`
（**不推基线、不清已阅指纹**）。每条真正落库的改动记成 `AppliedEntry`（`before` / `after`），
撤销走 `ScheduleUndo.plan` 的**倒序**反向事务（`undoLocalChanges`，课表 + 覆盖同一事务）。

**图片**（2026-10-06）：选图 / 剪贴板 → `AiImageStore`（`ImageImport` 加 `forceJpeg`
与 **EXIF 方向转正**——后者一并修好了笔记附件与课表背景图的横拍问题）→ 发送时 base64 成
`data:image/jpeg;base64,…` → `content` 升成 `[{type:"text"},{type:"image_url"}]`
（纯文本时仍是字符串）。能力判定探测不了（模型名用户手填、上游不给标记），
所以是**默认关**的显式开关 `ai_supports_image` + `looksVisionModel` 打勾建议。

**凭证与隐私**：API Key 落 `secure_ai`（EncryptedSharedPreferences + Keystore 自愈），
不进 DataStore；`secure_ai.xml` 已登记进 `backup_rules.xml` 与 `data_extraction_rules.xml`
的 cloud / device-transfer 三段。首次使用先弹隐私确认（`ai_privacy_ack`，声明类
`AlertDialog`）；教师名脱敏默认开。**会话与图片都不落盘**。

**偏好键**（DataStore，各自单流、不进 `baseDisplayPrefs`）：`ai_base_url` / `ai_model` /
`ai_mask_teacher` / `ai_privacy_ack` / `ai_thinking_effort` / `ai_show_reasoning` /
`ai_supports_image`。

**测试**：`AiScheduleToolTest` 17 例（编号越界、缺字段、非法取值、前后一致、未知类型、
非 JSON 整批拒、`extractJson`、overrides 解析与被拒）+ `AiSchedulePromptTest` 8 例
+ `ScheduleOverridesTest` 18 例 + `ScheduleDiffPreviewTest` 6 例 +
**`AiScheduleChatTest` 26 例**（readback、流式不取半截、视觉预判、图片类失败判定、
schema 声明、勾选默认全勾与撤销态、空串字段当未提供、已生效安排的去重与 prompt 段）+
**`ScheduleUndoTest` 11 例**（倒序、逐类型反向、脏条目跳过、状态闸、REMOVED 的 mergeKey 闸）+
**`AiWorkflowTest` 7 例**（底栏动作映射、空结果换主按钮、FAILED 次按钮、
`noticeDirty`、`previewWeeksOf`、回执跨轮归属、记录摘要）+ **`AiApplyHistoryStoreTest`
8 例**（列表维护 / 栈式判据 / 已删课表不挡栈 / JSON 往返保真：行 id 与自定义时间不许丢）。

**2026-10-06 二轮**（用户实测「校历调休通知答出『不做改动』」）：见 §4.40。
**2026-10-06 三轮**（用户实测「不是流式」「看不出课表会变成什么样」「思考顶掉结果」）：
改对话式（§3.25）、`parsePartial` 增量解析、结果卡 + 迷你课表、思考限高、
点表头看覆盖详情。

**2026-10-06 四轮**（三路独立复查 + 逐条修）：改动集中在「承诺了却没接线 / 会静默毁数据」
两类，逐条口径见 `.agents/rules/ai-schedule.md`「复核修复」节。结构上的净增：

- `AiTurn`（`ui/ai/AiChatModels.kt`）：本轮请求快照（文本 / 图片 / previous / 是否追问）。
  重试原样复用并**原位替换**，不再靠列表位置反推。
- `AiScheduleTool.parse/parsePartial` 收 `maskedTeacherNames`：等于脱敏占位名的 teacher
  当未提供（**不许回写本机真实姓名**）；周次上限只约束模型给的值，本机旧值原样保留。
- `ScheduleDiff.appliedCopy(new, target)`：预览与真应用**共用同一份收口**，清自定义时间的
  条件收窄成「时间真的变了」。
- `ScheduleUndo.plan(entries, current)` 加**状态闸**：应用后被用户改过的行不撤，条数报给 UI；
  撤销按回执里记的 `timetableId` 落库。
- `AiScheduleClient.chatStream` 改 `callbackFlow` + `enqueue`：`awaitClose` 里 `call.cancel()`，
  点「停止」能立刻关 socket；同时拦三类「静默成功」（非 SSE 响应体 / 流中 error 块 /
  `finish_reason` 截断），`tool_calls` 只认 0 号且名字对得上，`callTimeout` 放宽到 600s。
- `ai_img/` 与各 store 的 `.fallback.xml` 进备份排除；`AiImageStore` 加 `Mutex` 串行化，
  `AiChatViewModel.onCleared` 补第三条清理路径。

**2026-10-07 v2 重构**（用户反馈「太乱、不统一、排版丑、交互怪」）：形态仍是单页工作单，
做的是收口与修根——视觉全部走项目统一件（`AppCard` / M3 按钮与分段控件 / 16dp 横距 /
一套徽章色映射 / 软底块 / `AlertDialog` 声明弹层 / `ImageViewerDialog` 看图）；交互修复
四个根因：① 结果就绪**不再自动折叠**（首版把勾选主战场藏了；折叠只在 APPLIED）；
② 补「重新分析」次按钮（首版 READY 只有「应用所选」，重来回路是断的）；③ **回执跨轮
保留**（`proposalSeq` 判归属，撤销入口不随新分析消失）；④ 去重去假动作（清空 / 重试 /
撤销各留一处；删「微调」假按钮与「已发送的通知」双份展示；预览改全宽单栏前/后切换，
周次 = 改动涉及周 ∪ 覆盖周）。`ui/ai` 拆成 Screen / NoticeCard / ProposalCard 三件；
配置页「能力」区文案墙收短。

**2026-10-07 记录与动效补轮**（用户实测反馈「新增历史记录及撤销」「配置说明啰嗦」
「结果卡突然出现、思考展开没动画」）：① **「调课记录」页**（`SubpageScreen.AI_HISTORY`，
AI 页顶栏历史图标进）——每次**真正落库**向 `AiApplyHistoryStore` 追加留痕（filesDir JSON，
CAP 50，新在前），**栈式撤销**：只允许撤最新一条未撤销（`latestUndoable`，UI 与 VM
双处校验）；`ScheduleCourseRow` 补 `id` 与自定义时间三件套（**不进差异指纹**，撤销按 id
删 / 覆盖并原样还原）；主页回执带 `historyId`，与留痕互相同步（`refreshHistoryUndo`
在 resume 时核对）。② **动效补齐**：结果区逐段淡入展开（骨架 / 统计 / 明细 / 预览，
`AnimatedVisibility` 统一时序）、折叠摘要 ↔ 展开体、思考过程展开、回执条出现、失败卡 ↔
提案卡 Crossfade。③ 细节：**一处都没落库的应用不算成功**（留待确认 + `AiNothingApplied`
报条数，不再折叠成「已应用 0 处」）；配置页文案收短；勾选行整卡可点（共享
`ScheduleChangeCard` 加 `onRowToggle`，调课检测页同享）；AI 页提示条垫开贴底动作条
（实测高度）。

**2026-10-07 复查轮**（两路独立复查后逐条修；复核依据 = 用户「审查代码及功能完整性、
健壮性」）：① **课表删除后的撤销防护**（两面）——`undoApplied` 入口对已删课表整批按
跳过兜底（反向写会向不存在的 timetableId 塞孤儿行）；两处 VM 撤销前用 `repo.timetableIds()`
先拒绝并说清；`latestUndoable(list, aliveIds)` 让已删课表的记录**不挡栈**（行上标
「课表已删除」）。② **APPLYING 相位接线**——apply 进事务即 `phase = APPLYING`
（底栏「应用中…」不可点），`analyze/retry` 被相位挡住；出口恢复 READY/APPLIED。
③ **撤销防重入**：`undoing` 闸（**先扣闸再进第一个挂起点**，否则双击第二下挤进来
再跑一遍事务、把假 skipped 写进回执行）；两处 VM 同改。④ **`imagesDisabled` 死代码
收编**：「去掉图片重发」改为把输入区截图一并移除（本会话不再自动带）。⑤ **REMOVED
撤销补 `mergeKey` 闸**（用户把课加回来后不再插成两行，与写路径 ADDED 同钥匙）。
⑥ 细节：模型回 `""` 的 position 当未提供（原先会静默清地点）；失败卡补「已解析 N 处」
一行；统计条改 `FlowRow` 防大字体裁 chip；折叠/展开退场期交互禁点（防「弹回」）；
命中区补齐 44dp；留痕写盘失败落日志；`addImage` 补 CE 穿透。

**2026-10-07 复查二轮**（用户报「同一通知应用完再发，仍然报差异；『应用前』还是调课前的」）：
根因三件、逐条修——① **模型看不见已生效的日期安排**（课表清单只有课程行）→ `userMessage`
补【已生效的日期安排】段（`AiSchedulePrompt.overridesBrief`，空写「无」）+ `parse/parsePartial`
对等价提议确定性去重（`ScheduleOverrides.sameEffect`，进 `rejected` 注明「已生效，无需重复」，
**不静默吞**）+ `addOverrides` 写入侧幂等——三处同一判据；② **预览两栏都没算已生效覆盖**
（「应用前」看着像调课前）→「应用前」= 课程行 + 已生效安排、「应用后」= 现状 + 本轮勾选
（同一天本轮为准）；③ **同槽位胜负被 DAO 排序破坏**（`fromDate` 排序让「后应用的赢」变成
「日期晚的赢」）→ 改 `ORDER BY id ASC`（写入顺序），撤销删行后旧覆盖自动复活。

**2026-10-07 简化轮**（用户质疑「会不会想得太复杂」后的需求定位复盘；规则新增
「三层防线总览」节）——形态与红线不变，做的是**减脂**：① 删对话式时代的两件死能力——
`suggestions`（schema / `sanitizeSuggestions` / `Result` 字段 / SYSTEM 文案 / 测试整链）
与追问链（`previousJson` / `userMessage` 的 `previous` / SYSTEM「追问轮」段 /
`requirement` 诉求段）；`userMessage` 收成**只放事实四段**（课表清单 / 学期日历 /
已生效的日期安排 / 调课通知）；`courseJson` / `indexOf` 两个无调用者的私有件一并清。
② 需求定位写死：模型是翻译器、App 是编辑器、用户是审批人；**不采纳**「模型直接输出
整份课表、App 做 diff」——复述未改动的行必然产生假差异/静默删除，引用式协议保留。
判定新功能加不加，看是否落在三条公理（模型不可信 / 双语言换算 / 写入需确认可撤销）内。

### 4.40 日期覆盖：调休 / 停课（2026-10-06；UI 见 §3.25）

课表是「周次 × 星期」两维的，学校的放假 / 调休通知却按**具体日期**发
（「9月20日(星期日)按照9月28日(第五周周一)的教学安排进行」「9月25日-10月7日放假，
教学活动暂停」）。这两类事实用 `Course` 的 `weeks` / `day` 表达不了：把课挪走只能切碎
`weeks`（破坏性、不可撤销），停课更是无处可写。日期覆盖就是补这一维。

**模型**（`domain/ScheduleOverride.kt`，纯逻辑 JVM 可测）：

```kotlin
data class ScheduleOverride(fromDate, toDate, kind, sourceWeek?, sourceWeekday?, note)
enum class OverrideKind { FOLLOW, SUSPENDED }
```

- `SUSPENDED`：日期区间内没有课；`FOLLOW`：该日期上**另一个 (周次, 星期) 槽位**的课（单日）。
- `ScheduleOverrides.plan(overrides, semester)` 摊平成 `Slot(week, day) → Directive`
  （`Suspended` / `Follow(week, weekday)`）。键统一是 (周次, 星期)，因为网格、今日页、
  小组件、提醒全部按它索引；日期 → 槽位走 `slotOf`（周一为首日，与 `weekNumberOf` 同口径），
  槽位 → 日期走 `dateOf`（`ui/week/WeekGrid.weekDate` 的 domain 版，新调用方一律用这个）。
- 学期缺失 / 日期不合法 / 周次落在学期外 / follow 缺源槽位 → **跳过**（宁可少覆盖）。
  `MAX_RANGE_DAYS = 400` 防脏区间把摊平循环拖死。

**存储**：Room v19 新表 `date_overrides`（`timetableId` / `fromDate` / `toDate` / `kind` /
`sourceWeek?` / `sourceWeekday?` / `note`，`@Index("timetableId")`）。`note` **特意不给
Kotlin 默认值**——给了就必须在迁移建表里写 `DEFAULT ''`，不给就写裸 `TEXT NOT NULL`
（v7→v8 / v11→v12 / v13→v14 三处老坑的绕法）。删课表与清空课程时同步清覆盖。

**渲染接入（唯一入口）**：`ScheduleCalculator.coursesOnDay(courses, week, day, plan)` 是
「(周次, 星期) → 课程」的**唯一入口**，覆盖只在这里生效：

| 消费方 | 接入方式 |
|--------|----------|
| 今日页 / 小组件「今天·明天」 | `TodayState.buildTodayState(..., overrides)`，多 `todaySuspended` / `followedFrom` / `tomorrowSuspended` |
| 周次网格 | `ScheduleCalculator.weekColumns(courses, week, visibleDays, plan)` 逐列取课，`day` = **列**（借来的课画在目标列上）；表头第二行恒为「日期 + 单字标记」（`10/6假` / `10/6补`），**日期不被覆盖态顶掉**、今日强调优先——见 §3.3「星期表头」 |
| 桌面小组件迷你网格 | `buildWidgetWeek(..., semester, overrides)` 逐列走 `coursesOnDay` |
| 上课提醒 / 小组件边界闹钟 | `ReminderPlanner` 四个公开函数与 `nextTodayBoundaryMinutes` 各加 `overrides` |
| 日历导出 | `ScheduleExporter.expandEvents(..., overrides)`：被覆盖的槽位不按常规导出，补课另起一路导到目标日期 |

**FOLLOW 不递归**：借来的那天取源槽位的**原始**课，源槽位自己停课不影响它——
「第 5 周周一停课 + 第 4 周周日上第 5 周周一的课」是两件事，互相抵消就全空了。

**测试**：`ScheduleOverridesTest` 18 例（日期↔槽位互逆、区间摊平、补课借课、停课为空、
不递归、非法与学期外跳过、`coursesOnDay` 无覆盖时行为不变、文案、`sameEffect` 等价判定）。

**写入顺序 = 胜负顺序（2026-10-07 修）**：同一槽位出现多条覆盖时，`plan` 摊平是
「后写的覆盖前面的」，因此 `DateOverrideDao` 两个查询都必须 `ORDER BY id ASC`
（**别改回 `fromDate`**——按日期排会让后应用的更早 / 更宽区间输给旧覆盖，胜负语义反转）。
撤销删掉新行后旧覆盖自动复活，这套「覆盖 + 栈式撤销」才闭合。

---

### 4.41 培养方案课程设置总表（2026-10-07，P6；UI 见 §3.26）

教务「培养方案」明细页：`GET /jsxsd/pyfa/topyfamx`（无参数；服务端渲染整页 HTML，
由会话决定学生与方案）。手机端 `jiaowuh5`「标准版」的「课程设置总表」是同一份数据的
展示层——**不要走它**（443 直连不通、数据接口在登录后），PC 端带账密会话直取即可。

**页面解析**（`CurriculumParser`，与 scripts 侧 `fetch_curriculum.py` 互证过同一条口径）：

- 方案名在页首「一、培养目标」区（`p.item-area-content-inner`），**在「三、课程设置
  总表」切片之外**，必须全页搜；兜底 `span.header-item-title`（带「培养方案及教学计划」
  模板后缀）。
- 正文从「三、课程设置总表」起：体系块（`div.tixi-cell-text`：通识限选课 / 通识必修课 /
  学科基础课 / 专业课）与课程行（`div.son-table-tr`）按**文档位置归并**——两串节点
  各自 findAll 会丢归属。
- 列（逐列）：体系 / 选课组 / 课程编号 / 课程名称 / 课程性质 / 课程属性 / 学分 /
  理论·实践·讲座·实验·总学时 / 开设学期。
- **坑**：学时 5 个子列的 DOM class 全同（`table-td-cell cols-9-2-1`），只能按**行内
  出现顺序**区分（理论/实践/讲座/实验/总），按 class 名找必然错位；值可能是「2(周)」
  文本，原样保留字符串，不做数字解析。
- 没有课程名的行（汇总/空行）丢弃；开设学期解析失败归 0（UI 归入「未标注」）。
- 登录失效（`用户没有登录` / `请先登录系统`）与结构变化都返回 null →
  `CurriculumSync` 回「本次未更新」。

**存储**：Room v20 新表 `curriculum_courses`（`term` / `sortOrder` / `courseNo` / `name` /
`kind` / `attr` / `credit?` / 五格学时 / `tixi`）。全局归属学生、**不挂 timetableId**
（同 `scholar_courses` 的口径）；写入口径 = 整体替换（一次抓取出自同一份教务快照）。
可空列只有 `credit`（`REAL`），其余 `TEXT NOT NULL`；无 `@Index`，迁移不建索引。
方案名与同步日期存 DataStore（`curriculum_plan_name` / `curriculum_sync_date`），
同行 `scholar_plan_name` 模式。

**同步（`CurriculumSync`）**：走 `CasSession.fetchHtml`（OkHttp + 已存凭证），不开 WebView。
单页抓取；纪律同 `ScholarProgressSync`（§4.29）：mutex 让位（InProgress）、
`AutoSyncRules.shouldAttempt` 7 天闸门、解析后先 `CurriculumRules.validate` 再写库
（挡住半张表的残值覆盖）、**失败静默**、成功才记日期。触发点三处：首启引导登录成功
（force，静默、**不进**「同步完成」结果弹窗）、冷启动链（成绩→考试→学业→**培养方案**→
教材补抓，各隔随机小间隔）、页面进页自动（过闸门）+ 顶栏手动刷新。不做变更提醒、
不参与周期任务链。

**备份**（`CourseExport.curriculumCourses`）：换机/重装随课表一起恢复；DTO 字段全带
默认值，旧版 App 忽略该段、新版读旧文件缺省为空；导入侧「非空才整体替换、缺课程名的行
整体拒绝」，预览弹窗与导入结果文案各带一条计数。方案名不进备份（展示性字段，恢复后
下次抓取即补）。

**测试**：`CurriculumParserTest` 8 例（字段落位、学时顺序、「2(周)」、体系切换、汇总行
丢弃、未登录/结构变 null、非法学期）、`CurriculumRulesTest` 5 例（学年分组、标签、
学分合计、validate）、`ImportJsonShapeTest` +3 例（备份段往返 / 缺字段降级 / 旧文件空段）。

---

### 4.42 教学日历 → 开学日自动设置（2026-10-07，P6）

**数据源**：教务「教学日历查询」`GET /jsxsd/jxzl/jxzl_query?xnxq01id=<学期>`——服务端渲染
单表（快照 `scripts/out/jxzl.html`），`xnxq01id` **学期参数真实生效**（下拉列 24 个学期，
不带参按教务当前学期渲染）。表 = 表头 + 第 1..25 周 + 页脚「周历编制 | <学期>」，
周行 9 格：周次标签 + 周一..周五（**裸日号**，如 `31`，跨月也不带月份）+ 周六/周日
（`MM月DD日`）+ 备注——**全表没有年份**。

**解析**（`data/jw/TeachingCalendarParser.kt` + `domain/TeachingCalendarRules.kt`）：
① 登录页标记 → null；② 响应学期（`select#xnxq01id` 选中项，页脚「周历编制」兜底）
**必须等于请求学期**，对不上 → null（不猜学期）；③ 周一 = 周六格 `MM月DD日` − 5 天，
年份由学期号推导（`-1` 落起始年 7–12 月、`-2` 落次年年 1–6 月），且周六格必须真是周六；
④ 与周一/周二格的裸日号交叉校验，不齐 → null。任一不过宁可不动开学日。
数据行的属性是单引号（`<tr class='qz-weeklyTable-tr'>`，模板产物），表头行是双引号——
正则只按 `<tr[^>]*>` 收窄，不假设引号形态。

**写入口**（`data/repo/TeachingCalendarSync.kt`）：教务导入课表**写库成功后**触发——
与教材同两个触发点（WebView 一键导入 `JwImportScreen`、首启自动导课表 `OnboardingScreen`）；
`ensureValid → fetchHtml → parse` 后把第 1 周周一写进**目标课表**的
`semester_config.startDate`（`repo.updateSemesterFor`；没配过学期的课表以
`DefaultData.defaultSemester` 为底）。四条纪律：没学期钥匙不抓；学期必须自证；
**失败静默**（只记 `Log.i`——开学日原本就有值，失败是维持现状不是数据丢失，故不补
失败气泡）；**值没变不写库**、也不弹气泡。用户可见的只有变化时那一条主界面气泡
「开学日已按教务教学日历设为 yyyy-MM-dd」。

**口径**：「教务为准」——每次导入都重设；用户之后仍可在课表设置（§3.3）手改，
下一次导入会再校准。**不动总周数**（课程侧口径与日历 25 周的编制口径可能不一，不顺手改）。

**测试**：`TeachingCalendarParserTest` 10 例（秋/春学期年份推导、登录页、响应学期不符、
选择器缺失时页脚兜底、缺周末日期、交叉校验不过、周六格不是周六、非法学期号、缺第 1 周行）。

### 4.43 实习基地查看（2026-10-07，P6；UI 见 §3.27）

**数据源**：教务「实习基地查看」——壳页 `/jsxsd/sxsxgl/sxjdcx.do` 只是 layui 前端壳，
数据接口 `GET /jsxsd/sxsxgl/sxjd.do`（layui JSON：`msg/code/count/data[]`）。
**该接口忽略一切分页参数**（2026-10-07 实测：`page` / `pageNum` / `pageSize` / `limit`
全形态——GET / POST / 大小写混排 / 浏览器请求头——都返回**同一页前 10 条**，而 `count`
声明 17；同会话下成绩接口 `cjcx_list` 的 `pageNum=2` 翻页正常（10 → 6 条），说明不是
参数名问题、是该接口固定只回首页）。所以**单次抓取、不做翻页**，拿到什么展示什么，
差额在界面如实注明（「教务接口当前只返回前 N 个（标注共 M 个）」）；URL 仍按教务
qzTable 的口径带 `pageNum` / `pageSize`——哪天教务修好翻页，加回循环即可。
字段 = 展示表格的 14 列（编号 / 名称 / 类别 / 所属单位 / 负责人 / 地址 / 电话 /
建成时间 / 挂牌时限 / 容纳人数 / 校内联系人 / 校内电话 / 所属院系 / 简介）+ 内部 id。
纯只读接口，无写操作。

**链路**：`InternshipBaseParser`（三道检查照 `TextbookParser`；缺「基地名称」的行丢弃；
JSON null 按空串收——教务对空值可能回 null 而不是缺键；`data` 为 null 不抛）→
`InternshipBasesLoader`（**12 小时快照缓存**：`InternshipCacheRules.shouldUseCache`
（纯函数，判定矩阵有测试）——非强刷 + 抓过 + 新鲜才走缓存，页面进页默认零网络；顶栏刷新
强制重抓；**整个读写收口 `Dispatchers.IO`**（页面从主线程调用）；更新失败**宁旧勿空**
（抓过就有旧快照带 `updateFailed` 标记展示，从没抓到才报错）。**空结果口径**：`count≤0`
的权威空也写快照（页面如实显示空态，先例 `SelectionRoundsStore`）；`count>0` 却零行
按「条目缺名称、结构可能已变」走宁旧勿空，不当「暂无数据」吞掉）→
`ui/internship/InternshipBasesScreen`。**不落 Room**：低频参考数据（学校级清单，
短时间几乎不变），快照是 `filesDir` 一份 JSON、**已进两份备份排除 xml**（不上云，
DESIGN §4.43）。

**测试**：`InternshipBaseParserTest` 9 例（字段落位、缺名字丢弃、code 非 0、非 JSON、
ERR 前缀、空列表、null 字段、`data:null`、count 缺失按 -1、listUrl 带 pageNum/pageSize）+
`InternshipBaseRulesTest` 2 例（分组顺序与组内编号排序是 UI 契约）+
`InternshipCacheRulesTest` 2 例（新鲜期边界、未来时间戳）。

### 4.44 学生评教直达（2026-10-07，P6；UI 见 §3.27）

**为什么没有静态 URL**：教务主页菜单是 `toZlpt_11319('jwxsjdp')`——POST
`/jsxsd/qzzlpt/getToken_11319` 取 token，返回
`http://jiaowupj.juwp.edu.cn/api/…/authentication/<JWT>`（跨主机、JWT 约 4 小时），
拼 `+ '/jwxsjdp'` 302 到平台 `/home?xlgToken=…` 的 SPA（内容全由前端 JS 渲染）。

**实现**：`JwImportMode.TeachingEval`（`ui/jwvw/JwImportScreen.kt`）——复用教务 WebView
的整套统一认证自动登录，落 xsMainV 后由 `startAutoNav` 的延迟块**注入
`JwTeachingEval.launchJs()`**：页面自带 jQuery `$.ajax` 取 token → `location.href`
同窗口跳转（`window.__jwPjStarted` 一次性守卫；`evaluateJavascript` 不 await Promise，
发起后没有导航由 `AUTO_EVAL_TIMEOUT_MS`（15s）原地看门狗给状态条出路——任何路径不悬
Loading；顶栏「刷新」放开 `autoNavDone` 即重试）。平台页跳过白屏探针（SPA 空壳会被
误判「内容为空」）；`checkSessionLost` 对非教务域本来就放行。底部无任何按钮（同选课模式
的守卫口径扩展）。

**红线**：App 只导航——**评教全部由用户在平台上自己完成，不代评、不提交任何接口**
（与退课同一纪律）。**token 不落日志**：平台域 URL 在 9 处日志点经
`JwTeachingEval.redactForLog` 脱敏（`/jwxsjdp` 路径带 JWT、`/home` 查询串带 `xlgToken`）
——改那些日志点时别把 `$url` 直接插回去。`network_security_config` 已放行 `juwp.edu.cn`
全子域（含子域），无需改配置。

**2026-10-07 复查修**：① 平台域失败 URL 经 `JwImportDiagnosis` 会**原样进错误浮层**
（正文拼 url）——`networkFailure` / `httpFailure` 的诊断参数改收 `redactForLog` 的
脱敏串（`lastFailedUrl` 仍存原文做等值匹配），SSL 日志同样过脱敏（第 10 处）；
② 15s 看门狗带「尝试代次」（`evalAttempt`）——刷新重试后旧代次看门狗作废，不再补一条
与事实相反的失败提示；③ 评教模式底部整块不渲染（原先留一条空的高程条）。

**移动端评教页调查（2026-10-07，未接入）**：`http://jiaowupj.juwp.edu.cn:81/#/jspxxsp`
是「教学质量保障平台」的移动端 SPA（同一平台另一端口）。实测：它的 SPA 启动认证调用
（`/api/AuthenticationController/user/info`，`Bearer <xlgToken>`）**拒绝教务
`getToken_11319` 的 JWT（401）**，且其 CAS/OAuth 配置指向外校域名（nit / zhku，
疑似未配置好）。**暂维持桌面版**；若用户能提供其平时进入该页的方式（移动端教务 App 的
免登 ticket / 该页自身的登录形态），再评估接入。快照 `scripts/out/pingjiao_81_*`。

**测试**：无独立纯逻辑（注入脚本与导航靠设备点验；`JwImportScreen` 的枚举穷尽 `when`
由编译器逐个点名）。设备点验清单见 AGENTS.md 待点验表。

---

### 4.45 问渠（志愿服务 / 劳动教育，2026-10-08）

学校「**问渠**实践教育平台」——**道然科技基于 CRMEB 商城框架定制**的第三方系统
（后端 `https://wenqu.juwp.edu.cn/api/`，小程序 appid `wxadcfe9039706799c`，入口在
「江西水利电力大学水宝宝」公众号 → 便捷服务）。实现在 `data/wenqu/` + `ui/wenqu/`，
UI 规格见 §3.28。

**为什么不用微信登录**：小程序主登录是 `POST /api/wechat/mp_auth`，入参是 `wx.login` 的
`code`（或 `{encryptedData, iv, cache_key}` 四件套），**只有微信运行时能生成**；`cache_key`
存在微信**加密** MMKV 里，磁盘上无明文。App 造不出来，这条死路**别再试**。

**所以走手机号 + 密码**（三条实测口径）：

| 环节 | 接口 | 口径 |
|------|------|------|
| 发码 | `POST /api/register/verify` | `{phone, type:"reset"}`；**成功与失败都是 `status:200`**（成功 `msg="发送成功"`），只能看 `msg`；限流约 5 分钟一条 |
| 设/重置密码 | `POST /api/register/reset` | `{account:手机号, captcha, password}`；语义是重置，对**从没设过密码**的账号实测即创建 |
| 登录 | `POST /api/login` | `{account:手机号, password}` → `{token, expires_time}`；**账号是手机号，学号不行** |
| 我的记录 | `POST /api/volunteer/my` | `{status:"", start_time:"", end_time:""}` → `total_score` + `list`（我参加过的） |
| 星级证书 | `POST /api/volunteer/star_cert` | `{}` → `volunteer_score_total` + `star_level` |
| **证书 PDF** | `GET /api/volunteer/star_cert/download` | 无参 → `pdf_base64` + `filename` + `size`（**JSON，不是文件流**）；2026-10-15 实测 |
| **活动列表** | `POST /api/volunteer/v2` | `{page, limit, keyword}` → `list` + `qj_count` / `qx_count` / `isBlack` / `stop_apply_reason`；**需要登录**（无 token 回 `410000 请登录`） |
| **活动详情** | `POST /api/volunteer/detail/{id}` | id 走路径、请求体 `{}` → `detail` + `apply`；**工时 `score` 与活动群号 `group_id` 只有这里才有**，`apply` 段 = 我对这条活动的报名（报名 id / 状态） |
| **报名**（写） | `POST /api/volunteer/apply` | `{"id": <活动id>}`；实测 `{}` / `id=0` → `参数错误`（id 必填）、id 不存在 → `服务项目不存在或已下架`（它查的是**活动**）⇒ `id` 是活动 id |
| **取消报名**（写） | `POST /api/volunteer/apply/cancel/{id}` | id 走路径、请求体 `{}`；`{id}` 是**活动 id** 还是**报名 id** 未钉死（平台对两种不存在的 id 都回 `您未报名该活动`），App 先发活动 id、撞上这句且有报名 id 时再换一次 |

**路由是怎么探出来的**（2026-10-11，别再重复踩）：未登录请求**真端点回 `200` +
`{"status":410000,"msg":"请登录"}`，不存在的路径回 `404`**，且路由**大小写不敏感**
（`volunteer/MY` 也命中）。取消报名藏在**路径参数路由**里（`volunteer/apply/cancel/{id}`）——
只试不带 id 的 `volunteer/apply/cancel` 会 404 漏掉，**探路由时路径参数要一起试**
（`volunteer/detail/{id}` 同族）。

- `volunteer/my` 的每条记录带 **`apply_id`**（报名 id）、`apply_status`、**`canCancel`**
  （平台算好的「这条还能不能取消」，**只有记录列表有这个字段**，活动列表 / 详情都没有）。
  `apply` 段里还有 `username` / `student_no` 等个人字段——**模型里不声明**，`ignoreUnknownKeys`
  下连内存都不进。

- token 是 **3 天** JWT（`exp - iat = 259200s`）；`data` 里**没有 cache_key**，续期就是重登，
  本地存着手机号+密码所以用户无感。
- 请求头是 **`Authori-zation`**（CRMEB 错拼），不是 `Authorization`。
- 会话失效只有 `status:410000`（`请登录`）——网络 / 5xx 一律不计（对齐一卡通纪律）。

**「活动列表」与「我的记录」是两张表，别互相复用解析**：`volunteer/my` 是「我参加过的」
（字段 `total_score` / 单条 `score` / `apply_status_text`），`volunteer/v2` 是「平台上有的」
（字段 `people_num` / `join_num` / `balance_num` / `isApply` / 单条 `status`）。两者都有
`title` / `unit` / `start_time`，但语义与取值都不同。状态文案照小程序那张表：
`{0:已取消, 1:招募中, 2:进行中, 3:已结束}`（`WenquRules.statusLabel`）。

**但两条记录的 `id` 是同一个活动 id**：小程序「我的志愿服务」的 `goToDetail` 就是拿记录的
`id` 去 `volunteer_detail/index?id=`，实测对得上（记录 34658 与 `volunteer/detail/34658`
标题与工时一致）。所以**记录详情 = 活动详情**，客户端复用同一份详情正文
（`WenquActivityDetailContent`），记录视角只把徽章换成 `apply_status_text`（如「已完成」）。
映射是 `WenquVolunteerRecord.asActivity()`，它把 `status` 钉成
`WENQU_STATUS_NOT_APPLICABLE`（-1）——**别用 `WenquActivity.status` 的默认值 0**：
平台表里 0 是「已取消」，照搬会让记录徽章写「已取消」；-1 在 `statusLabel` 下回空串
（宁可不显示徽章，也不显示一个编出来的状态）。自主（DIY）类记录 `id` 为 null
（`coerceInputValues` 收成 0），本地字段已经够看，不发注定失败的详情请求。

**活动列表的两条实测口径**：① `keyword` **空串是合法的**（回全量前 `limit` 条），
与教务选课中心「空关键词回 0 行」相反；② **接口不返回总数**——早先靠「上一页回满 `limit`
即可能还有」推断 `hasMore` 做「加载更多」，**2026-10-11 起整条分页浏览删掉**（列表 = 全量快照，
见下），`WenquActivityPage` 的 `page` / `hasMore` 一并删除。
总量上千且按 id 倒序，所以列表是「时间流」——已结束的占绝大多数，状态筛选在**本地**做。

**列表与搜索 = 本地全量快照**（2026-10-11，两轮改动同一个根因）：
① 用户报「志愿列表里明明有『爱心家教』的活动，搜索却搜不到」——搜索原先完全依赖服务端
`keyword`，而那个参数到底匹配哪些字段**无法验证**（无 token 回 `410000 请登录`）；
② 用户报「选了『招募中优先』还是把旧的已结束排前面，切『招募中』显示没有、点加载更多才有」
——因为列表只拿**服务端第一页 20 条**，而本地筛选 / 排序都作用在这 20 条上，
招募中只有个位数且不在第一页。**筛选与排序必须覆盖全量才有意义**，所以列表也换成快照，
两处共用一个数据源。现在：

- `WenquRepository.activityIndex()` 按**三条路**分派，判据是纯函数 `WenquRules.indexLoadPlan`：

  | 路 | 什么时候 | 代价 |
  |----|----------|------|
  | `UseCache` | 距上次同步 < `INDEX_SYNC_INTERVAL_MS`（30 分钟） | **零请求** |
  | `SyncNewest` | 到点了 / 下拉刷新（`force`） | **1 个请求**：只抓第一页 20 条，按 id 合并（`mergeNewest`） |
  | `Rebuild` | 没快照 / 快照不完整 / 上次整份重抓超过 `INDEX_MAX_AGE_MS`（24 小时） | 整份重抓（按页翻，几秒） |

- **增量为什么够**（用户口径「已发布的老活动基本不会改，没必要重复请求」）：平台列表
  **开头按 id 倒序**（实测前 21 条严格降序），新活动一定落在第一页——抓一页就能捞回新的。
- **增量的代价与两个兜底**：深层条目的可变字段（名额 / 状态 / 已报名）会冻在抓取那一刻。
  ① 24 小时一次整份重抓把漂移封顶（同时清掉平台侧删掉的条目）；
  ② `WenquRules.effectiveStatus` 用**不可变的时间字段**校正状态——**活动结束时间已过 → 已结束**
  （冻住的「招募中」会被本地排序顶到最上面，这条就是堵它）。「报名截止已过但活动还没结束」
  平台语义没实测过，**不猜**。校正发生在**数据进状态**那一步（`WenquViewModel.corrected`）。
- **整份重抓**（`buildIndex`）：按页抓 `volunteer/v2`（`keyword=""`、
  `limit = ACTIVITY_INDEX_PAGE_SIZE = 200`、`page = 1..`），页间
  `AutoSyncRules.interRequestDelayMs()` 放慢，并发由 `indexMutex` 串行化。
  **收工判据在 `WenquRules.shouldContinueIndexing`**（纯函数）：这一页没带来新 id /
  累计触顶 `ACTIVITY_INDEX_MAX_ITEMS = 2000` / 页数触顶 `ACTIVITY_INDEX_MAX_PAGES = 15` /
  超 `ACTIVITY_INDEX_BUDGET_MS = 8000` 预算。**只有「没带来新 id」算抓全**（`complete = true`）；
  首页失败回 null（不缓存），中途失败带部分收工并缓存（`complete = false`）。
- **匹配字段 = `WenquRules.matchesKeyword`**：活动名 / 项目名 / 单位 / 地点，trim + 小写后子串包含
  （**不传 `content`**——卡片上看不到的字不该搜出来）。
- **命中来源**：`complete` 时**纯本地、零请求**；不完整 / 建不起来时补一次服务端关键词查询
  （只取第一页）按 id 去重并入（`mergeActivities`，本地优先）。兜底失败不打断本地结果。
- **生命周期**：**进页就按上表走**（`WenquViewModel.loadIndex`）；清空检索词回浏览列表、零请求。
  `WenquViewModel.indexJob` 让抓取**不随搜索协程取消**（防抖会取消上一个搜索协程），
  结果落地带世代号 `searchSeq`。

**进程内 TTL 缓存**（2026-10-08 二轮，用户报「每次进这个界面都要重新获取」）：
问渠页是 `SubpageActivity` 上的独立窗口，**每次进页都新建 ViewModel**，旧写法在 `init` 里
一订阅到登录态就取数 → 进页 = 三次请求。现在工时 / 星级 / 活动快照各有一份 `WenquTtlCache`
（工时与星级 30 分钟；**快照 24 小时**——它要活得比一次同步久，30 分钟那档闸门改由
`WenquActivityIndex.syncedAt` 判），`WenquRepository` 是 `Graph` 单例所以跨页面、跨窗口都命中；
进页还会先用 `cachedIndex` / `cachedVolunteer` **同步**铺上快照，不闪骨架屏。只有下拉刷新
（`force = true`）绕过同步闸门。**不做磁盘缓存**（第三方公开内容、随时在变，落盘还要处理
备份排除与陈旧展示，收益不抵成本），**也不做周期轮询**（活动提醒那一路是周期任务，
但它是独立的开关 + 间隔闸门，见下）。换人登录会 `clearCaches()`。

**本地筛选与排序**（2026-10-08 三轮；2026-10-11 扩到三个筛选 + 三档排序，同样作用于搜索结果）：
状态 / 「隐藏已满」/「**只看我报名的**」（`isApply`，2026-10-11 新增）三个维度都在本地做
（`WenquRules.matchesFilter` / `isFull`），再按 **`WenquRules.Sort`** 排序：

| 档位 | 判据 |
|------|------|
| 招募中优先（默认） | `displayRank`（招募中 → 进行中 → 已结束 → 已取消），组内保持平台原序 |
| 报名截止临近 | `closingSortKey`（用 `apply_end_time`，**已截止 / 没填的排最后**） |
| 最新发布 | 活动 id 降序 |

`sortedBy` 是稳定排序（只重排不隐藏）。平台的顺序既不是 id 也不是 add_time（实测前 21 条严格降序、
之后跳回旧 id），不排就是满屏已结束。「满」= 招募中 + 剩余名额 0，**招募人数没填（0）时一律不算满**。
三个筛选 + 排序都落 DataStore（`wenqu_status_filter` / `wenqu_hide_full` / `wenqu_applied_only` /
`wenqu_sort` 存枚举 name，插档不错位）。筛选把整页挡空时，页面说「有 N 条活动被当前筛选隐藏了」
并给「清除筛选」（`UiState.searchHiddenByFilter` 就是这条判据）——**别把「被筛掉了」说成「没搜到」**。

**下拉刷新**（2026-10-11 调整）：强刷「我的」+ 浏览列表；**搜索模式下不重抓索引**，只把命中
重算一遍（索引在 TTL 内直接复用——重抓一次要几秒，用户点刷新不该干等它；真过期了
`ensureIndex` 自己会重来）。

**`isApply` / `isBlack` / `stop_apply_reason` 三个平台字段要展示**（2026-10-08 三轮）：
前者在活动卡上给一枚「已报名」徽章（早先解析了却没展示，报过名和没报名的长得一模一样）；
后两个是**账号级**的报名限制，`WenquActivityPage` 带上它们，页面在筛选行下面挂一条
「报名受限」+ 平台原话（`WenquRules.applyBlockedText`）。

**详情取不到不是错误**（2026-10-08 用户报「点记录弹详情，同时飘一条取不到详情」）：
平台回 `detail: null`（活动已删 / 不可见）是**正常回答**，`activityDetail` 回 `WenquActivity?`，
调用方用入口那条（列表 / 记录）继续展示，只在弹层内挂一行说明，**不弹任何提醒**
（`detailUnavailable` 标志）。

**新增志愿活动提醒**（2026-10-08 三轮，`ui/reminder/WenquActivityAlertReminder.kt`）：
搭 `ScoreAlertReminder` 的周期任务（**第五路**，不新开 WorkManager 任务），
开关 `wenqu_activity_alert_enabled` **默认关**（打扰型能力，同选课/调课口径）、
间隔 `wenqu_activity_interval_hours` 默认 **24**（档位复用 `ScoreAlertDefaults.INTERVAL_CHOICES`）。
四条口径：① **只报「招募中」**（`WenquRules.isNewRecruiting`）——平台会把补录的老活动插进列表
（实测 id 最大的一条是「补录」且已结束），按 id 无差别报全是噪音；② **首跑只建基线**
（`wenqu_activity_last_seen_id` ≤ 0 一律不算新增），否则用户一开开关就被几十条历史活动轰一遍，
基线只前进不后退；③ **扫描一整页 100 条**（`WenquRepository.scanActivities`）——
`volunteer/v2` **不保证按 id 排序**（实测前 21 条严格降序、之后跳回旧 id），扫 20 条会漏掉
夹在中间的新活动；④ **检查时刻只在成功时落**（同成绩/考试纪律）。
通知走免扰时段拦截（事实型 → 时段结束补发）+ 收件箱留痕
（`InboxKind.WENQU_ACTIVITY`，键 `wenqu|activity|<本批最大 id>`，一次核对只留一条痕）；
通知 id 1013 / 落点 requestCode 3013 / 渠道 `wenqu_activity`。

**每周日 12:00「抢活动」提醒**（2026-10-11，用户口径「学校很多志愿都是这个时间开抢」，
`ui/reminder/WenquWeeklyAlertReminder.kt`）：与上面那条是**两件事**，别合并——
那条是**事后发现**（周期扫列表，依赖接口 + 网络），这条是**事前蹲点**（固定时刻，
发布时刻还没到、扫也扫不到新东西，所以**一条网络请求都不发**，只有一条精确闹钟）。

- 排法同 `SelectionAlertReminder`：主 = `setAlarmClock` 单点精确闹钟（requestCode **4007**，
  不需要特殊权限）；兜 = 一次性 Work（闹钟落点 / 开机 / 覆盖安装 / 开关变更）+ 冷启动
  `JuwApplication` 补一次本地重排。**不搭周期任务**（不是「周期扫」那类）。
- 时刻由 `WenquRules.nextWeeklyAlertPoint` / `lastWeeklyAlertPoint` 算（纯函数，JVM 测）：
  `publishAt` = 本周日 12:00（本地时区），`remindAt` = `publishAt` − 提前量（1–10 分钟，默认 5）。
- **已发键是「发布时刻」不是「提醒时刻」**（`wenqu_weekly_alert_last_fired_at`）：用户中途把提前量
  从 5 分钟改成 10 分钟，同一周不该再报一次（`WeeklyAlertPoint.publishAt` 与提前量无关）。
- **迟到窗口 10 分钟**（`WEEKLY_ALERT_LATE_MS`，对齐免扰时段对时刻型提醒的过期口径）：
  提醒点过去超过 10 分钟就不再补发——用户要的是「12:00 前就位」，12:30 才响只是噪音。
- **通知被系统整体关掉时不落键**：这次没发出去，下次冷启动 / 开机还在窗口内就还能补。
  这条**不入箱**（时刻型提醒不入箱，与选课那条不同——那边有收件箱兜底）。
- 通知走**免扰拦截**（时刻型，`eventAt` = 发布时刻）+ **直接震动**（`ReminderVibration`，
  不挂通用「通知强提醒」开关：这条是用户为「抢」显式开启的、一周一次的能力，而 HyperOS 上
  第三方通知的振动开关默认是关的，不直接震等于静默）。
- 响过之后 `WenquRepository.invalidateActivities()` 把快照的**同步时刻清零**：
  用户点通知进来时不该看到发布前的旧列表，下次进页立刻**增量**同步一次（**不是整份重抓**）。
  **只清时刻、不发请求**——进页照旧是用户动作。
- 通知 id **1015** / 落点 requestCode **3014** / 渠道 `wenqu_weekly`；设置页改开关 / 提前量走
  `onSettingsChanged`（只重排闹钟，**不碰** `ScoreAlertReminder.onSettingsChanged`）。

**一处 2026-10-08 三轮的修补（2026-10-11 已作废）**：原先 `scanActivities` 成功后顺手把
「浏览页」缓存写成刚抓到的前 20 条，让用户点通知进来不至于看到旧列表——列表换成全量快照之后
那条缓存没有读的人了，**写入已删**（要拿新数据走 `invalidateActivities()` + 下次进页增量同步）。
另一处保留：设置页改开关 / 间隔走 `WenquActivityAlertReminder.onSettingsChanged`，
**不用** `ScoreAlertReminder.onSettingsChanged`——后者会把五路的检查时刻一起清 0，
于是动一下问渠开关就顺带让成绩 / 考试 / 选课 / 调课各打一次教务；问渠的入口只清自己那一路，
再 `ensurePeriodicWork` + `enqueueWenquCheck`（复用同一个 Worker 类名与工作名，不新开任务）。

**蹲点模式（抢活动）**（2026-10-11，用户口径；UI 见 §3.28「蹲点抢活动页」）：
把周日 12:00 的抢活动压成「到点自动检测 → 点一下确认报名」。实现在
`ui/wenqu/WenquGrabViewModel.kt` + `WenquRepository.grabProbe`，参数与判据全在
`WenquRules`（纯函数，JVM 测）：

- **只发一种请求**：每轮 1 个 `volunteer/v2` 第一页（`GRAB_PAGE_LIMIT = 30`、空关键词）；
  不拉工时 / 星级 / 详情、不整份重抓。成功后 `mergeNewest` 并进快照并推 `syncedAt`
  （探测 = 一次「只看最新」的同步，页面列表跟着新，不需要下拉）。
- **短超时（两档，2026-10-16）**：常规探测走 `probeApi`（6 秒）、发布窗口内走 `burstApi`
  （`GRAB_BURST_TIMEOUT_S = 3` 秒）；共享那份 30 秒**不动**（登录 / 详情 / 写操作靠它）。
  `prewarmSession` 在开蹲时先把 token 换新，可能的登录请求不挤进 12:00 的第一轮。
- **串行 + 退避（2026-10-16 改）**：上一轮不回来不发下一轮；成功间隔
  `GRAB_POLL_INTERVAL_MS = 3` 秒，连续失败**窗口外**按 `grabNextDelayMs` 逐档退避到
  `GRAB_BACKOFF_MAX_MS = 12` 秒封顶 + **±20% 抖动**（`jitteredDelayMs`，防节奏指纹），
  成功恢复。**窗口内失败也走 1 秒快重试**——原先失败照退避 6/9/12 秒，而 12:00 的拥堵
  恰好表现为超时，于是最该续命的时候反而最沉默。**发布窗口** =
  `[publishAt - GRAB_BURST_BEFORE_MS(20 秒), publishAt + GRAB_BURST_AFTER_MS(15 秒)]`
  （前缘 2026-10-16 由 5 秒放宽到 20 秒——平台写接口会**提前约 10 秒放行**），成功与失败
  都加密到 `GRAB_POLL_INTERVAL_FAST_MS = 1` 秒。`publishAt` 由 `grabPublishAt`（周日 12:00，
  非周日回 0）给；**串行设计**保证高峰时自然退化成服务器响应速度，加密不会雪崩。
- **窗口（2026-10-16 重做）**：点「开始蹲点」**立即检测，不再待机到 11:59:50**（旧
  `grabDetectStartAt` 已删）；检测窗口 `GRAB_WINDOW_MS = 10` 分钟、开始早于发布时锚到
  「发布 + 10 分钟」（`grabWindowEndAt` 取两口径较晚者）+ `GRAB_MAX_CHECKS = 900` 次
  安全阀（`grabShouldStop`）。**离发布超过 5 分钟**走 10 秒慢档
  （`GRAB_POLL_INTERVAL_RELAXED_MS`）。
- **命中 = `grabFirstMatch`**：在**整份快照**里找第一条「（`matchesKeyword` 匹配 **或** 是盯着的
  那条）+ `applyBlockReason` 为空」的活动——**不是只扫探测那一页**（2026-10-11 点验报「有招募中且
  匹配的活动却不弹」，根因是多数招募活动不在最新 30 条里）。会话内 `shownHitIds` 去重：
  同一条只弹一次，**弹过检测继续**（先弹到旧一批、12:00 新品出现还会再弹）；
  报名成功 / 手动停止 / 窗口到点才收工。
- **盯指定活动**（2026-10-15；2026-10-16 改并发 + 短超时，`wenqu_grab_pinned_id`）：盯了就在
  每轮**额外**拉那一条的详情（`refreshIndexItem`，绕过详情缓存）并替换快照里同 id 那条——
  快照深层条目的名额会冻住（`mergeNewest` 只刷新最新一页），**已满的老活动空出名额只有这一路
  看得见**。命中判定里它**优先于名字匹配**且不看名字：已满 → 一直不命中，`balance_num` 转正
  才弹。**与列表探测并发发**（`coroutineScope + async`）——原先串行在后、还挂 30 秒超时。
  **「盯」只对还会再放名额的状态开放**（入口 `WenquRules.canPin`：报名尚未开始 / 名额已满）；
  转为终结态（结束 / 取消 / 报名截止 / 已报名）时自动取消盯（前台提示、后台静默，`pinFutile`）。
- **盯的活动「可以报名了」→ 后台通知**（2026-10-15；2026-10-16 改口径）：搭第五路共享周期任务
  （`WenquActivityAlertReminder.checkPinnedSlot`，**不新开 WorkManager 任务**）——每轮多拉一次
  盯着那条的详情，**可以报名了**（`applyBlockReason` 回空——名额空出 / 报名开放都算）就发通知 +
  入箱（标题「可以报名了：<活动名>」），同一条只报一次；盯的活动转为永远等不到的状态时
  静默取消盯并重排任务。**不挂开关**（盯 = 显式选择）、**不过免扰时段**（可以报名是当下要动的
  事）。**粒度 = 共享任务间隔**（盯了活动压到 `GRAB_PIN_CHECK_INTERVAL_HOURS = 1`），所以只救
  冷活动；秒级热活动得靠前台蹲点。通知 id 1016 / requestCode 3015 / 渠道复用 `wenqu_activity`。
- **蹲点不自动提交**：命中只弹 `WenquGrabHitDialog`（确认报名 / 先看看 / 关闭），报名仍走
  `writeOnce` 单发写路径；自动提交只发生在用户单独开启的「预报名」（见下节）。目标名
  `wenqu_grab_keyword` 落盘预填，输入框随时可改（检测中每轮读最新值）；「过滤已满」复用
  `wenqu_hide_full`；探测回 `isBlack` → 立即停 + 平台原话。
- 页面侧：命中**震动 + 提示音**（`ReminderVibration` + `ReminderChime`，2026-10-15 加音，
  用户可能没盯着屏幕）+ 弹窗开着暂停探测 + 蹲点期间屏幕常亮 + 状态行给「上轮耗时 / 连败」
  诊断（2026-10-16）；**离开页面即停止**，蹲点**不做后台 / 息屏轮询**（Android 限制 + MIUI
  冻结且弹窗弹不出来）——无人值守场景走下面的「预报名」。

**预报名（准点自动抢，2026-10-16；用户拍板）**：用户口径——平台**写接口会提前约 10 秒
放行**（活动写着 12:00 开始报名，11:59:50 就能抢），手点抢不过；要「选好活动、到点全自动」。
**全 App 唯一允许自动提交的路径**，只对用户点名的单条活动生效。分层：
`ui/wenqu/WenquPreregReminder.kt`（落盘 + 精确闹钟 4008 + 兜底 Work + 通知 1017 / 落点 3016）+
`WenquPreregRunner.kt`（执行器：T−60s 预热与并发校对 → T−15s 起串行试发，结果不明才只读核对
（「尚未开始」确定没落库，不核对；核对单独用 2 秒短超时）；≤30 发 / 每发间隔 1 秒 /
≤150 秒 / 开始后 ≤90 秒三闸收工；
平台话术分类 `WenquRules.classifyPreregReject`）+
`WenquPreregService.kt`（`shortService` 短时前台服务——精确闹钟触发属官方豁免，同骑行那条链）+
`data/wenqu/WenquServerClock.kt`（`Date` 头时钟偏移，试发窗口按服务器钟折算）。
入口在详情弹层（`applyBlockReason == APPLY_NOT_STARTED` 才给按钮 + `WenquPreregConfirmDialog`
二次确认；已排上显示「已预报名」状态行可取消）。**写请求不自动重发**：网络类失败只读核对后
再决定。细则见 `.agents/rules/wenqu.md`「预报名」节。

**本地凭证**：独立文件 `secure_wenqu`（`EncryptedSharedPreferences`，**含密码**，与一卡通查询密码
同级），自愈三段照抄 `QzxySessionStore`；**两份备份排除 xml 都要登记**（正式件 + `.fallback`）。
存 token 的 `expiresAt`（从 JWT 解）+ 工时影子（`hours` / `hoursFetchedAt`，30 分钟 TTL）。

**红线**：① **写操作只有用户显式触发的路径**——发码 / 设密码 / **报名** / **取消报名**，
一律只发一次、失败不自动重试（`WenquRepository.writeOnce`）；报名失败是网络类错误时补一次
**只读核对**（「其实报上了」不当失败报，2026-10-16）。**唯一例外 = 预报名**（见上节，
2026-10-16 用户拍板：只对点名的单条活动、有限窗口与次数内自动试发）；**签到
（`volunteer/checkin`）与提交材料类（`*_submit`）仍不碰**——它们有到点 / 定位语义，不是
「一次点击」能安全覆盖的。报名 / 取消都先过二次确认，失败原因留在弹层里（页面级 Snackbar
会被弹层盖住）；
② **无日志拦截器**，手机号 / 密码 / token / 负责人姓名电话不进 Logcat；③ 响应里的他人信息**分两档**
（2026-10-08 起）：`leader_name` / `leader_phone`（活动负责人）**已声明进模型**、在详情弹层展示
（电话可长按复制），但**只展示**——不进日志、不进持久化、不进夹具与文档（仓库是公开的，
夹具一律假名假号）；`attendance_name` / `attendance_phone`（考勤人）与 `apply` 段里的
`username` / `student_no` **都不声明**，`ignoreUnknownKeys` 下连内存都不进
（`WenquModelsTest` 有兜底用例：toString 里出现即红）。

**测试**：`WenquRulesTest` 71 例（续期时机 / 5 分钟冷却 / 手机号正则与脱敏 / JWT 取 exp /
星级与工时文案 / 状态文案 / 时间戳与时间段 / 名额文案 / 「满了没」/ 本地筛选两维叠加 /
展示排序权重 / 报名限制文案 / 筛选哨兵 / 新增活动判据 / 活动提醒间隔档位与兜底 /
**报名闸门六例**——可报回空串 / 被限制与已报名先说 / 状态各档 / 时间窗口 / 名额已满排最后 /
活动视角取消入口只给已报名且招募中 / **蹲点八例（2026-10-16 改）**——窗口锚定（早于发布
锚到发布后十分钟）/ 轮询四档（窗口内 1 秒 / 近发布 3 秒 / 远发布 10 秒 / 窗口内失败也快试）/
退避序列与封顶 / 抖动边界 / 收工判据（900 次）/ 命中筛选四例 / **预报名六例**——闹钟与试发
时刻（服务器钟）/ 三闸收工 / 时刻文案 / 平台话术分类 / 判据常量 / `Date` 头解析与偏移）
+ `WenquModelsTest` 21 例（信封判定 / 发码双 200 只看 msg / 数字与字符串两种工时 /
记录带地点与报名时间 / 记录→活动映射与 `status` 钉 -1 / 自主类 id 收 0 / 负责人进模型而考勤人不进 /
记录转活动带负责人 / 报名限制标记 / 活动列表与详情解析 / 忽略未知字段 / `data:null` 容错 /
**详情 `apply` 段带报名 id 且个人字段不进模型** / **记录带 `apply_id` 与 `canCancel`**）
+ `WenquTtlCacheTest` 6 例（新鲜期命中 / 过期即删 / 按关键词各存一份 / 超量按写入序淘汰 /
重写排到最新 / clear）。夹具按真实结构脱敏改写，人名手机号一律假值。

### 4.46 天气（Open-Meteo，2026-10-11；UI 见 §3.29）

**选型**（2026-10-11 定，换源前先读这段）：**Open-Meteo**（`api.open-meteo.com/v1/forecast`）——
免注册、免 API Key、覆盖中国（模型池含 CMA 的 GFS GRAPES），一次请求同时给实时 / 逐小时 /
逐日。对比过的两条：和风天气要建项目 + 凭据（SDK v5 起只支持 JWT），密钥落进 App 就是泄露，
让每个用户自己申请又不现实；中国天气网那批非官方端点无文档、随时变。代价是许可 CC BY 4.0
**要求署名**——界面上的「数据来源 Open-Meteo（CC BY 4.0）」不能删。

**分层**：`data/weather/WeatherApi`（裸 OkHttp + 宽容序列化；**无日志拦截器**，
请求里只有坐标与字段清单；超时收到 20s）→ `data/weather/WeatherRepository`
（30 分钟 TTL + `Mutex` 保证同刻只放一个请求 + **刷新失败回退旧快照**；`load()` 带
`fresh` 标记供页面判「这份可能旧了」）→ `domain/Weather`（快照模型 + WMO 码 → emoji /
中文短语的唯一映射处）+ `domain/WeatherAdvice`（18:00 分界、话术优先级、候选句列表，
纯函数）+ `domain/ScheduleTips`（顶栏课表句：南北楼 / 实验课 / 快下课有雨，纯函数）。
UI 见 §3.29。

**查询点**：校区坐标（`WeatherApi.CAMPUS_LAT/LNG`，与 `BikeNearby.DEFAULT_CENTER_*` 同一组数字）。
那边是 GCJ-02、这里是 WGS84 口径，南昌一带差几百米——天气网格以公里计，不做反算；
将来要跟随设备定位时再补 `domain/Gcj02` 那一步。

**请求字段**：`current`（温度 / 湿度 / 体感 / 昼夜 / 天气码 / 风速 / 风向）、
`hourly`（温度 / 降水概率 / 天气码 / **昼夜 `is_day`**，小时格的昼夜图标靠它）、
`daily`（天气码 / 最高最低温 / 降水概率上限 /
紫外线 / 最大风速 / 日出日落），`timezone=Asia/Shanghai`。
窗口是 **`forecast_days=7` + `forecast_hours=24`**：逐日要 7 天（主流天气页的日数），
逐小时只要 24 条（从当前整点起算，接口按这个参数给）——别为了 7 天把 168 条小时数据也拉回来。
解析全部可空（`uv_index_max` 这类会回 null），`ignoreUnknownKeys` 接新增字段。

**地点名**：`WeatherApi.CAMPUS_LABEL`（「南昌 · 瑶湖校区」），与坐标一起写死——
天气查的是固定坐标，没有反查地名这一步，界面上写的就是这个常量。

**缓存形态**：`Cached(snapshot, atMs)` 一个不可变对（取数时刻与快照同读同写，不会读到
「新快照 + 旧时刻」）。`peek()` 不判 TTL、不发网络，给页面首帧直接铺缓存用；
`load()` 返回 `Load(snapshot, fresh, atMs)`，`atMs` 是**这份数据自己的**取数时刻
（回退旧值时就是旧那份的时刻，页面「更新于」据此显示）。

**下拉刷新的最短驻留**：`WeatherViewModel.MIN_REFRESH_MILLIS = 650ms`，
与今日页同口径——请求命中得再快也不让指示器一拉到顶就弹回去。

**开关与取数纪律**：偏好键 `weather_enabled`（DataStore 全局项，**默认关**），
单独一条流不进 `baseDisplayPrefs`（combine 已满员）；**关着时今日页一行网络都不发**。
仓库单例走 `Graph.weatherRepository(context)`。

**测试**：`WeatherAdviceTest`（18:00 边界 / 优先级顺序 / 兜底与容错 / 候选句列表）与
`ScheduleTipsTest`（半天分组 · ≥2 节课门槛 · 南/北楼判定 · 实验课优先 · 快下课窗口边界）。

### 4.47 百宝箱：图片水印 · 图片拼接 · 菜鸟批量查（2026-10-12，P6；UI 见 §3.30）

**来源**：水印与拼接两个工具的实现思路拆自「一个木函」（`com.One.WoodenLetter` 7.19.1）的
`program.imageutils.watermark` / `stitch` 两个模块（REA 静态逆向，笔记在本地
`.re-tmp/ymh/`，不入库）。**只借鉴思路不抄代码**——那是闭源商业软件；
若将来要抄代码级的实现，参考 [T8RIN/ImageToolbox]（Apache-2.0，Kotlin + Compose），
那是允许带署名复用的。

**文件**：`domain/ToolboxImage.kt`（全部排版口径的纯函数）+
`ui/toolbox/ToolboxRenderer.kt`（Canvas 渲染核）+ `ToolboxCommon.kt`（选图 / 滑杆 /
出口）+ 四个页面（`ToolboxScreen` / `WatermarkScreen` / `StitchScreen` /
`CainiaoQueryScreen`）+ `domain/CainiaoQuery.kt`（菜鸟批量查的纯逻辑）。
入口与开关见 §3.30。

**预览即所得（口径 1）**：所有尺寸**只按图片宽度取比例**（字号、边距、平铺块）。
预览用长边 ≤ 1200 的降采样源、成品用长边 ≤ 2600 的源，
同一组参数渲染两遍，观感一致——**不要**给预览另写一套口径（那正是漂移的开始），
也不要让任何尺寸依赖绝对像素。渲染核与参数解耦，域函数在
`ToolboxImageTest` 里钉住（字号比例 / 密度三档 = 尾部空行 / 拼接取宽
与总高 / 像素上限 / alpha 换算）。

**水印（平铺）**：文字先渲染成一小块平铺单元（多行 `StaticLayout`，行距 1.0——
密度档位靠**尾部空行**调，不靠行距，行距一改块内两行文字自己的间距也跟着变），
再 `BitmapShader + TileMode.REPEAT` 铺满全图（「一个木函」同款做法）。
**改进一处**：平铺块先按旋转后的包围盒扩边再旋（原版直接旋原尺寸块，45° 时啃字边）。
不透明度走 `Paint.alpha`（一层即可，文字色本身不透明度不参与）。

**拼接（两种方式）**：默认**智能拼接**（PPIICC 那套）；**直接拼接**是拼接页里的可选档。

> 2026-10-12 用户口径：要 PPIICC 那套。**智能只适合「同一页面连续滚动」的一批截图**——
> 一组不相关的图会被「公共头尾 / 重叠」识别裁掉真实内容，所以选择行下的说明写明了这条
> 边界，并保留「直接」一档作为出口。

- **直接拼接**：目标宽取**最大源宽**（大图降采样、小图不放大；原版取第一张的宽，
  会让后面的大图降质）、逐张按比例算高累计、硬摞。内存纪律两条：① 先
  `inJustDecodeBounds` 读全部尺寸（零像素内存），再**要一张、画一张、丢一张**
  （`stitchStreaming`）——九张截屏同时解码就是上百 MB；② 总像素超过 2400 万按面积比开方
  **等比缩宽**（不裁内容），超过 800 万换 `RGB_565`（每像素 2 字节）。
  底色铺白（带透明通道的 PNG 截图拼出来不该是黑底）。单张解码失败 = 该槽留白继续拼，
  不让一张坏图作废整张长图。
- **智能拼接**（2026-10-12，口径拆自 PPIICC `bos.consoar.imagestitch` 0.5.5，
  纯像素比对、无 OpenCV）：`domain/StitchScan`（匹配核，纯逻辑）+ `ui/toolbox/StitchSmart`
  （装配）两步——① 找**公共头 / 尾**（所有图对连续相同行的最小值，即状态栏 / 标题栏 /
  底部 Tab 栏），中间各图裁掉；② 找**相邻图的滚动重叠**（从最大往小搜、逐行验证，
  命中取最大），裁掉重复内容；③ 找不到就退回直接拼接。状态栏行不参与比对
  （时钟每张不同），按系统状态栏高度**直接计入头高**。匹配把各图归一到同一工作宽度
  （480）上做、出图在原分辨率（`keeps` 行区间，按每张图自己的纵向比例折算）。
  前提：**所有图同宽**（容差 2%），**高度可以不同**——长截图与普通截图混拼是常态。
  容差三档（严格 / 标准 / 宽松 = 每样点平均色差 90/150/220）。匹配的三道保险：
  快筛抽行（隔 4 行先比，活下来的才全行精验）、坏行预算（带内平均达标 + 超阈值行
  ≤ 5%，容忍光标闪这类个别噪声行）、纹理守卫（大片纯色的「匹配」没有唯一解，
  判歧义退回硬接——宁留重复不裁内容）；小于 16 行的碎片重叠不当真。
  **还有一道精修**（`RefineRadius = 16`）：达标候选定下来后，在 ±16 行里按**平均差异
  最小**重挑——真机实测「从大往小第一个达标」会差一至几十行（相邻行长得像时错一行也
  达标），精修把接缝拉回真对齐（真对齐 8.5 vs 错一行 14.9 的分数地形，见
  `.re-tmp/ymh/stitch_probe/` 的探针）。
  细节与边界见 `.agents/rules/toolbox.md`。

**菜鸟批量查（非图片工具，2026-10-12）**：贴入多个运单号 → 拉起菜鸟查询。
链接 = `cainiao://startapp/search_alert?querySourceId=68719476739&prePath=Pasteboard&content=<单号逗号分隔>`
——菜鸟导出的 `OpenGuoGuoUrlActivity`（三方路由）把前缀重写成内部路由
`guoguo://go/search_alert?...` 再交 App 内 Router 跳转，外部 App 借此直达平时进不去的
内部页面（页面本身未导出；同为该通道的还有「菜鸟取件码」预设的 take_express，§3.8）。
对 8.11.126 真机实测全链路：搜索页预填 →「去批量查询」→ 添加包裹页全部自动查完 →
「添加到包裹列表」保存；不在列表 / 不存在的单号各有空态。输入清洗拆分的判据
（全角折半角 · 只留字母数字与连字符 · 8~40 位且至少含一个数字 · 大小写不敏感去重 ·
上限 20；被丢掉的重复 / 杂项 / 超上限在页面上报数，不静默少发）和链接拼装、历史
编解码都是纯逻辑，在 `domain/CainiaoQuery`（`CainiaoQueryTest` 钉住），页面
`ui/toolbox/CainiaoQueryScreen`；最近提交 5 条存 DataStore `cainiao_query_history`
（脏 JSON 回空表，口径同 ebike 最近车号）。**不做网络请求、不碰菜鸟账号**；拉起复用
`ShortcutLauncher`（二级页拉起外部应用的过渡口径）。**改链接任何一段前先在真机重验；
通道哪天收紧时 `CainiaoQuery` 是唯一改动点。**

**水印平铺块的宽度取自文字实际渲染宽度（2026-10-12 真机 abort 的真因）**：
`Layout.getWidth()` 返回的是构造时传进去的宽度（`StaticLayout` 为「不换行」传的是
`Int.MAX_VALUE`），不是最长行的宽度。拿它当位图宽就是
`Bitmap.createBitmap(2147483647, h, ARGB_8888)`——原生 `SkBitmap::setInfo` 失败、
colorType 留成未知，`LOG_ALWAYS_FATAL("unknown bitmap configuration")` **直接 abort**
（整个进程被杀；用户看到的现象是「选完图页面自己跳回去了」，因为 SubpageStack 把恢复的
页面又放回最上层——别拿这个现象去查导航）。布局宽取最宽行的 `paint.measureText`，
块宽取 `layout.getLineWidth(i)` 的最大值。

**位图 config 只走白名单（同日的防患，不是上面那次的病因）**：解码端两遍都钉死
`inPreferredConfig = ARGB_8888`（`ui/common/DecodeSampled.kt`），出图端
`ToolboxRenderer.safeCopyConfig` 只放行 ARGB_8888 / RGB_565、其余回退 ARGB_8888。
**不要把 `src.config` 原样转发给 `createBitmap`**——10-bit / HDR 照片在 API 34+ 会解成
RGBA_F16 / RGBA_1010102 这类非常规配置，不该漏给出图侧。

**出口**：保存到相册走 `ui/common/BitmapSave`（MediaStore `Pictures/水贝贝`）；
分享复用课表导出的 `ScheduleShareStore` 机制、目录换 `toolbox_share/`
（`Graph.toolboxShareStore`，FileProvider 根已登记；两边各自裁到 5 份）。
格式统一 **JPEG q92**：产物多为截图 / 照片，PNG 会把体积放大一个量级；课表导出是纯色块
图才用 PNG。文件名 `水贝贝-百宝箱-<工具>-<时间戳>.jpg`。

**开关**：偏好键 `toolbox_enabled`（DataStore 全局项，**默认开**），
经 `MiscPrefs` 切片进 `baseDisplayPrefs`（外层 combine 五参已满，新流只能进内层切片）；
今日页那行在 `TodayScreen` 直接订阅 `Graph.displayPrefs(context).toolboxEnabled`。
`DisplayPrefsDefaultsTest` 钉住默认开。

### 4.48 空调遥控：宿舍美的空调（2026-10-16；2026-10-18 补 R05D；UI 见 §3.31）

寝室美的空调的红外遥控：**状态 → 6 字节帧 → ConsumerIrManager 脉冲序列**，
纯本机发射（`TRANSMIT_IR` 普通权限，安装即授予），不联网、不落账号。

- **协议口径**（`domain/AcProtocol`，默认 `R05D`；协议卡可切 `A1`，切换只改本机、
  不发红外，下一次按键才按新口径发完整状态）：
  - **R05D**（`domain/MideaR05d`，国内美的挂机最常见）：6 字节 = 固定头 `B2 4D` +
    功能字节 B + `~B` + 模式/温度字节 C + `~C`，整帧原样连发两遍，没有额外校验和；
    关机 = 专用帧 `B2 4D 7B 84 E0 1F`；温度 / 模式 / 风速查码表（温度非二进制顺序，
    送风取 0xEF 的「无定义」档）；波形 = 头 4400/4400 → 48 位 → 550/5220 → 头 →
    48 位 → 550/8800，一组约 192ms。实测向量 `B2 4D BF 40 D0 2F`（自动风 26℃ 制冷）
    / `B2 4D 7B 84 E0 1F`（关机）由 `MideaR05dTest` 钉死。**没有睡眠 / 定时 / 摆风 /
    屏显 / 强力 / ECO 的公开编码，R05D 下隐藏这些卡。**
  - **A1**（`domain/MideaAc`，IRremoteESP8266 `ir_Midea` 口径，Pioneer / Comfee /
    Danby 等 OEM 与部分美的）：6 字节 = `0xA1` 头 / 电源·睡眠·风速·模式位 / 温度位
    （**固定位 6 恒 1（0x40）**；摄氏 = 17 + 位值，钳制 17–30）/ 关机定时 / 开机定时 /
    校验和（前 5 字节按位反序求和 → 取 256 的补 → 再按位反序）；定时 = 30 分钟一档、
    `0x80 | ((N-1) shl 1) | 1`（0xFF 不定时）；波形 = 头 4480/4480 → 正码 48 位
    （位 mark 560，1 间隔 1680、0 间隔 560）→ 560/5600 → 头 → 按位取反 48 位 →
    560/105600。帧与波形逐位对齐 IRremoteESP8266 `ir_Midea` 的测试向量（`MideaAcTest`
    钉死：校验和 + 完整波形 + 定时 7 小时 `A1A34DFF9B34` + 切换帧）。切换类功能
    （摆风 / 屏显 / 强力 / ECO，`MideaAc.Toggle`）：协议无状态位，特殊帧（`0xA2` 头）
    与主帧连发，**切换帧固定只发 1 次**（重复会翻转两次）；界面记忆
    `MideaAc.Toggles` 跟快照一起持久化。
- **发射**（`data/ir/AcIrBlaster`）：`ConsumerIrManager.transmit(MideaAc.FREQUENCY_HZ,
  pattern)`；`supported = hasIrEmitter()`；失败（无发射器 / 系统拒绝 / 时长超预算）
  返回带原因的结果，页面提示。**每次 `transmit` 只放一组**（R05D ≈192ms / A1 ≈291ms），
  普通操作补发 2 次；小米 `ir-spi` HAL 会整条丢弃超长序列（主帧 + 切换帧拼一条会超）。
- **状态**（`data/prefs/AcRemotePrefsStore`）：独立 DataStore `ac_remote_prefs`
  （protocol / power / mode_bits / temp_c / fan_bits / sleep / timers / 切换记忆；
  模式与风速存协议位值，枚举顺序变了旧值照认）。每次操作发完整状态帧 + 写回；
  `AcRemoteViewModel` 把发射放 `Dispatchers.Default` 并用 `Mutex` 串行化（`transmit`
  在部分机型上同步等发完；连点与温度长按连发都不允许并发、顺序要保）。
- **入口**：今日页「江水生活」的三枚图标入口之一（2026-10-19 自生活页迁入）；
  路由 `SubpageScreen.AC_REMOTE`。
- **后续**（未做）：A1 的自清洁（`A20DFFFFFF70`，仅制冷 / 除湿 / 自动档）、8℃ 加热
  （`A20FFFFFFF73`，仅制热档）、静音（`A212/13` 开关系）——都是现成的切换帧形态，
  要加就在 `MideaAc.Toggle` 补一条 + UI 一个芯片；R05D 的睡眠 / 定时编码公开资料
  只有占位，未确认；Coolix 作为第三套口径，等 R05D / A1 都不认再上。

### 4.49 宿舍报修进度：今日页卡片 · 原生详情 · 办结提醒（2026-10-19；UI 见 §3.3 / §3.15）

宿舍报修本身仍在学工 WebView 里提交（§4.26），App 只**只读**抄一份「个人提交记录」
到本机：今日页顶部一张卡片、点进原生二级页 `DORM_REPAIR`、办结时入收件箱并发通知。
**绝不调用提交 / 审批 / 退回 / 保存接口**——写操作仍然只由用户在官方页面里完成。

- **只读接口**：`POST https://xgxt.juwp.edu.cn/office/data/approve/apps/forms/fore/user/list`
  （`XgUrls.OFFICE_RECORD_LIST`）。参数由 `XgForm.recordListForm()` 生成：
  `formId` / `aprvAppId` 同值（报修 278625）、`pageEnc`、`cpage=1`、`pageSize=20`、
  `version=2`、空的搜索 / 时间 / 脱敏参数；`uid` 可省（会话 cookie 足够）。
  同目录下的 `approve/flow/approve/handle/*`、`user/save`、`submit/validate` 一律不碰。
- **会话两级（按成本从低到高）**：
  1. **WebView 现成的 office 会话**（`DormRepairWebClient`）：`CookieManager.getCookie(
     "https://xgxt.juwp.edu.cn/office/")` → 直接带 `Cookie` 头 POST。不登录、最便宜；
     用户刚在报修页提交完关窗 / 回前台时，这条一定新鲜——卡片立刻更新。
  2. **CAS 回退**（`CasSession`）：`ensureValid` → `GET /sfrz/login343962` →
     `GET /office/` → 同一个 POST。**自动检查的 CAS 登录最多 12 小时一次**
     （`DormRepairSync.AUTO_LOGIN_INTERVAL_MS`）；手动刷新不受限。这样既能在关掉 App
     后仍发现办结，又不会每个冷启动都完整登一次统一认证（教务脚本踩过 CAS 风控）。
  两条都不可用 → 静默 `Skipped`，保留旧快照；不在今日页弹错误。
- **解析**（`data/xg/DormRepairParser`，纯 JVM 可测）：
  - `formsConfig` 建 `id → label` 映射，再按 **label** 取 `formData`：
    「当前状态」→ 报修状态（待处理 / 已派单 / 已完成）；「宿舍信息」（没有则「宿舍」）、
    「故障类型」、「详情描述」、「最终完成时间」。
  - `aprvStatusType` / `aprvStatusTypeId` 是审批状态；`currentApproveUserList` 的
    `nodeUniqueId` 用来标出 `flowUser` 里当前审批节点；`cardType == 2` 的抄送节点不进流程条。
  - **姓名 / 学号 / 院系 / 专业 / 班级 / 手机号在解析阶段直接丢弃**（`PERSONAL_LABELS`），
    不进快照、不上屏、不进日志。
  - 完成判定：表单「当前状态」= 已完成，或审批到终点（已通过 / 已拒绝 / 已撤销）。
    审批通过后表单来不及改成「已完成」时，卡片主状态按「已完成」，详情页把表单的
    「已派单」并列列出，不藏差异。
- **本机快照**（`data/repo/DormRepairStore`）：`filesDir/dorm_repair.json`，含最近 20 条、
  `dismissedIds`（用户点过「完成并隐藏」）、`notifiedIds`（已发过办结通知）、
  `checkedOnce` / `tracking`（打开 App 检查的闸门）。**已登记进两份备份排除 XML**。
  失败宁旧勿空；`tracking` 一旦用户用过报修就保持（进程重启后仍会检查）。
  **退出学校统一认证时 `JwAccountScreen` 调 `DormRepairStore.clear()` 删掉快照**，
  换账号后不能把上一位用户的报修记录留在今日页。
- **卡片 / 详情**：
  - 今日页滚动区**第一项**（今日课程上方；未开学 / 课表为空时也在顶部槽），没有可见
    记录时整卡不渲染。有在途优先（最新提交）；没有在途才显示 3 天内已完成的。
  - 详情页 `SubpageScreen.DORM_REPAIR`：状态英雄卡 + 报修信息 + 审批流程 + 当前处理人；
    「去学工报修页核对」打开现有 `XgFormActivity`；「完成并隐藏（仅本机）」只写
    `dismissedIds`，不向学工提交任何写操作。
- **办结通知与收件箱**（`ui/reminder/DormRepairReminder`）：
  - 只在**回到前台**时核对（不做后台轮询）；同一进程 30 分钟内不重复请求。
  - 首跑只建基线；只有「上次同一条未结束 → 这次结束」才入箱
    （`InboxKind.DORM_REPAIR`，key `repair|done|<formUserId>`）。通知开关只拦通知，
    不拦入箱；免扰时段走 `QuietHoursGate`（事实型，时段结束补发）。
  - 通知渠道 `dorm_repair`（IMPORTANCE_DEFAULT），id 1018 / tag `dorm_repair` /
    requestCode 3018；点击落 `DORM_REPAIR` 详情页。完成时间超过 3 天的不再弹通知
    （只入箱）。
- **已知边界**：自动检查优先用 WebView 会话；若进程重启后 WebView 会话已不在、
  且 12 小时 CAS 窗口未到，则本轮跳过，卡片继续显示上次快照——用户可在详情页手动
  刷新或打开报修页后恢复。打卡片只进原生详情，不跳 Web。
