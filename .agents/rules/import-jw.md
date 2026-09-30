# 教务课表导入

作用域：改 `ui/jwvw/` 的导入流程、教务 DOM 解析、考试/成绩取数时读。规格见 DESIGN §4.4.2。脚本侧细节见 `scripts/README.md`（比 DESIGN 更细）。

## 教务页星期只能从列序推

- 教务页星期只能从课程所在 `<td>` 的**列序**推（第 0 列是节次标签）。`li.qz-hasCourse-N` **几乎恒为 1**（实测 33 处 `-1`、2 处 `-3`），不能当星期来源。

## 课表导入只有一个入口

- **课表导入只有一个入口**：`ui/jwvw/JwImportScreen.kt` 的「一键导入课表」依次打开学期理论课表页与
  实验课表页，合成一批后弹一次识别结果（DESIGN §4.4.2）。五条别改坏：① 必须等 `PageLoadGate`
  放行再注入抽取脚本（`loadUrl` 是异步的，不等就抽到旧页面的 DOM），放行只认片段
  `xskb_list.do` / `syjx/toXskb`（两页 URL 都含 `xskb`）；② `reportFailure` 里
  **自动重试分支之后**才放行 false；③ **实验课表 0 条不是失败**（前期学期本来就没实验课），
  靠 `ExtractMeta` 区分「这张表没课」与「拿到的不是这张表」——理论看 `cells>0`（`kbDataTd` 个数）、
  实验看 `container`，把 `SyjxScheduleParser.EXTRACT_JS` 找不到容器改回 `ok:false` 会让两种情形
  重新混在一起；④ 实验页 URL 跟着理论页的学期号走（`JwUrls.labScheduleUrl` + `TERM_PATTERN`），
  否则两页默认学期不同时合并出来的是跨学期课表；⑤ 落到理论课表页会**自动跑一次**一键导入
  （`autoImportOnTheoryReady`，2026-09-24 起，用户不必再点按钮）——触发条件里的「页面类型是
  理论课表」不能换成「URL 含 `xskb`」（实验页 URL 也含它，会变成自动死循环），一次性标记
  `autoImportTried` 同步置位、并在 `runOneClickImport` 入口消费（手动点按钮也算用掉）。

## 导入结果落回主界面（2026-09-29）

- 导入窗口与主界面是两个 Activity，反馈不能停在窗口里：**写库成功即 `finish()`，结果交给
  主界面下方那条气泡**（用户口径：「导入成功提醒是软件内下方那个一行的气泡提醒，不是弹窗
  提醒」）。通道是 `ui/jwvw/JwImportResultBus`，**不再有「导入完成」弹窗**——留着它，用户
  点完「完成」就 finish，反馈停在他看不见的那个窗口里。
- **六个落点**各调一次 `JwImportOutcomeEffect(snackbar)`：课表页 / 今日页 / 成绩页 /
  学校统一认证页 / 课表中心页 / 考试页（`ui/exam/ExamScreen.kt`，2026-09-30 加；后三个
  是本页的「导入课表」「导入成绩」「导入考试安排」入口，导入窗口 finish
  后落回的就是它们）。新增落点页面时别忘了同时补 `AppSnackbarHost`；`JwAccountScreen` 的
  Scaffold 把 `contentWindowInsets` 归零了，提示条要自己 `navigationBarsPadding()`。
- 四条别改坏：① 课表/考试与成绩两条路径都发，文案各自拼（成绩不报课表名）；
  ② 发布放在**写库之后、`onBack()` 之前**，顺序反了就是「窗口关了、消息还没发」；
  ③ 清通道的时机：**过期消息在进 `showSnackbar` 之前丢，新鲜消息等 `showSnackbar` 返回后才
  `consume()`**——consume 把 flow 置 null 会让 `JwImportOutcomeEffect` 的 `LaunchedEffect(outcome)`
  下一帧以 null 重启、取消还挂在 `showSnackbar` 上的协程，而 M3 的契约是
  「caller cancelled → snackbar removed」：气泡刚挂上去就被撤掉（2026-09-30 修正此前
  「先 consume 再 show」导致五处落点全都不显示）；过期即丢仍然挡着「旧消息在后续组合里
  反复触发」那条老问题；④ 别把落点改回「只在课表页消费」。
- 新鲜期常数在 `JwImportOutcome.FRESH_WINDOW_MS`，边界由 `JwImportResultBusTest` 钉住。

## 确认弹窗支持切换学期（2026-09-28）

- 弹窗的「数据学期」行在**一键导入路径**下是可点下拉（`ImportTargetDialogHost` 新参
  `availableTerms/switching/onTermSelected`，其余调用点不传行为不变）。数据源是抽取脚本
  额外输出的 `terms:[{v,t,s}]`（理论页学期下拉全部选项），经 `extractTermOptions` 宽松解析，
  **value 必须过 `TERM_PATTERN` 白名单**（value 要拼进重载 URL），异常降级为空列表。
- 切学期 = `runOneClickImport(forceTerm)` 带着 `xnxq01id` **强制重载**理论页再抽两张表——
  此时不能沿用「已在理论页就不重载」的捷径（那条只适用于没指定学期的默认路径）；
  重爬中 `switching` 置位（下拉不可点、「导入」禁用），失败保持原草稿。
- **导入学期写进目标课表**：`importParsedCourses(..., term)` 会把数据学期写进 `Timetable.term`
  （Room v14）。这列是课程详情查教材的钥匙（DESIGN §4.31）——动导入写库口径时别把它弄丢。

## 教材（2026-09-28）

- 教材来自「教材管理 → 学生教材确认」：壳页 `/jsxsd/nxsjc/jccx` + 数据接口
  `/jsxsd/nxsjc/xsjcqr`（layui 形态，参数 `xnxqid` + `pageNum/pageSize`，**不带 .do**）。
  解析在 `data/jw/TextbookParser.kt`（照 ScoreParser 的三道检查），同步器 `data/repo/TextbookSync.kt`。
- **触发点只有一个**：教务/JSON 导入写库成功后 `Graph.appScope.launch { textbookSync().syncForTerm(term) }`
  （`ui/jwvw/JwImportScreen.kt`）。静默失败、成功才记日期，**没有 7 天闸门**（教材跟导入走，
  天然低频；`prefs.textbookSyncDate` 只做记录）。
- **`xsjcisxy.do` 是征订确认的写操作（POST），任何路径都不碰**——教材功能只读 `xsjcqr`。
- 查询口径：详情面板按「当前课表 `Timetable.term` + 课程名」过滤；term 为空（旧课表）不显示。

## 成绩导入也自动跑（2026-09-26）

- **落到成绩查询页就自动导入**：成绩模式（`JwImportMode.Scores`）下 `xsMainV` → `cjcx_frm`
  的导航由 App 自己完成，落页即由 `autoImportOnScoreReady` 跑一次 `runScoreImport`，
  与课表模式的 `autoImportOnTheoryReady` 同构（共用 `autoImportTried`，同一窗口只会是其中一种
  模式）。写库仍要过「确认导入成绩」弹窗，按钮留作失败后的重试入口。
- **成绩页不是课表页**：`JwUrls.schedulePageKind` 认不出它（返回 `None`），自动触发的闸门用
  `JwUrls.isScoreQueryUrl`。**不要**把成绩页塞进 `JwSchedulePage`——那套 `pageKind` 会驱动
  注入课表页适配样式，并把成绩页误判成「理论课表就绪」，触发课表自动导入。
- **`autoImportOnScoreReady` 必须定义在 `runScoreImport` 之后**：Kotlin 局部函数不能前向引用，
  挪到前面会 `Unresolved reference 'runScoreImport'` 编译不过。

## 学业完成情况与自动导入（2026-09-27）

- 学业完成情况（`/jsxsd/xxwcqk/xxwcqkOn*.do?isdb=0`，四个维度各一个页面）**返回 HTML 不是
  JSON**，解析在 `data/jw/ScholarProgressParser.kt`，**按表头名映射**，不要按下标取——四个维度的
  列集合各不相同（课程性质 9 列连「修读学期」都没有）。规格、坑与实测数字见 DESIGN §4.29。
- **成绩与学业各有两条导入路径，写的是同一份数据**：WebView 注入（成绩页按钮，用用户在页面上
  手登的会话）与 OkHttp 直取（`ScoreSync` / `ScholarProgressSync`，走 `CasSession.fetchHtml`
  加已存凭证）。改解析或写库口径时两处一起看；URL 常量只有 `JwUrls` 一份。
- **自动导入不许挂在页面的 `rememberCoroutineScope` 上**：引导页登录成功后的两次抓取丢
  `Graph.appScope`（进程级）。页面 scope 在跳进 MainActivity 时就被取消，抓取会半路夭折。
- **闸门 7 天**（`AutoSyncRules`，成绩与学业共用）：库里没数据立即抓，有数据满 7 天才抓。
  改这个数字等于改「多久自动打一次教务」。
- **日期只在写库成功后落**。失败也记 = 一次网络抖动换一周不刷新。
- **解析结果先校验再整体替换**（`ScholarProgressRules.validate`）：教务改版时保留旧数据，
  不拿残值覆盖。注意校验里**没有**「要求学分合计 > 0」这条通用闸门——公选课类别维度全是 0.0，
  加了会把整个维度永久判失败；那条检查只在 `validateCreditTotal` 里对课程体系维度用。

## 成绩/考试变动提醒（2026-09-30，DESIGN §4.33）

- **考试自动检查链（`ExamSync`）与手动导入共用同一份 `ExamScheduleParser.parseFetchJson`**，
  只是传输层换成 `CasSession.fetchHtml`（OkHttp）。改解析字段或接口参数时两处一起看；
  接口地址常量在 `ExamSync` 伴生对象（壳页 `xsksap_query` / 数据 `xsksap_list`，
  **不带 .do**——带 .do 的同名地址回 no-open 页，别把「功能被校方关闭」当「没数据」）。
- **缺省学期以教务为准**：先 GET 壳页正则抽 `select#xnxqid` 选中项（`ExamSync.termFromShell`，
  单测钉住 value 属性 / 纯文本 / data-selected 三种形态），抽不到直接 Failed，**不猜学期**
  （猜错的学期会拿空基线当「首跑」吞掉真变动，或拿错学期基线刷一屏假通知）。
- **考试绝不写课程表**：`ExamSync` 只读 + 与 `ExamSnapshotStore`（filesDir 基线）比对 +
  发通知；点通知落**考试页**（`SubpageScreen.EXAMS`，2026-09-30 由直达 `JwImportActivity`
  改过来），导入入口在该页页尾。给这条链加「顺手写库」等于把静默写课表做进后台，禁止。
- **`startAtExam` 只改登录后的落页**（2026-09-30）：`JwImportActivity.start(startAtExam = true)`
  让课表模式的窗口登录后直接开 `xsksap_query`，从而**不触发** `autoImportOnTheoryReady`
  的一键导入（理论+实验）。考试页的导入卡必须带这个参数——不带的话，用户点「导入考试安排
  到课表」看到的是「一键导入课表」（真机复现过）。它不动抽取、写库与确认弹窗任何一环。
- **考试安排也自动跑**（2026-09-30，`autoImportOnExamReady`）：落到考试安排查询页就抓一轮，
  与理论课表的 `autoImportOnTheoryReady` 同构、共用 `autoImportTried` 与 `busy`，
  两条各判自己的页型。出口两条：有安排 → 识别结果确认弹窗（默认合并）；没安排 →
  「暂时没有考试安排」弹窗（`emptyExamTerm`，教务考前数周才录入，`count=0` 是正常空态，
  文案不要写成错误）。**这是省点击不是省确认**：写库仍要过确认弹窗，红线不变。
- **首跑/换学期不通知**：基线为空或 `Snapshot.term` 与本次抓到的学期不一致 → 只存基线、
  返回空变更。删掉这道闸，用户开开关的那一刻会被整表考试刷屏。
- **检查时刻只在成功后落**（`score_check_millis` / `exam_check_millis`），失败不写——
  下个周期自动补查。**失败也不重试**（不加重试 = 防撞风控，与自动导入同口径）。
- **闸门有两套**（都在 `ScoreSync.syncLocked` 的分支里）：成绩提醒开着 →
  `AutoSyncRules.shouldAttemptAt` 毫秒间隔（小时级）；关着 → 原 7 天日期闸门
  （`scoreSyncDate` 照旧写，§4.29 的自动导入节奏不变）。别把两套合并——关提醒的
  用户不该被逼着接受小时级请求频率。
- **成绩变更检测在写库前**：`replaceTerm` 是盲替换，旧值过了这村就没法 diff 了。
  顺序固定 = `repo.getAll()` 快照 → `ScoreChangeDetector.detect` → 逐学期替换。
  给成绩写库加新调用点时想想它绕过了这道 diff 会怎样。
- **设置入口两处、间隔一份（2026-09-30）**：成绩提醒在 `ScoreSettingsScreen`
  （`SubpageScreen.SCORE_SETTINGS`，成绩页齿轮进入；分组/排序/任选课口径也在这页），
  考试提醒在考试页 `ExamScreen`（`SubpageScreen.EXAMS`，入口「我的 → 学习 → 成绩与考试 →
  考试安排」；同日先内联在课表 hub 的 `ExamAlertRows`，再收成课表 hub 入口行，傍晚挪进
  学习 hub——**别搬回课表 hub**，用户口径是「考试跟成绩一起看」）。两边
  **共用 `alert_interval_hours`**——改任意一边另一边跟着变，别给它加「按提醒分开」的键；
  `ScoreAlertReminder.ensurePeriodicWork` 只排一个周期任务，两链共用。
- **考试页的列表与提醒同源**（2026-09-30）：列表读 `ExamSnapshotStore`（`ExamSync` 每次
  成功检查落的基线），所以**没把考试导进课表也能看**；页尾的导入卡只是「进课表」的口子，
  考试进课表仍然只有手动导入一条路。列表里「新增/调整」标用 `ExamChangeDetector.keyOf`
  算的身份键——**别在 UI 里另写一套键**，两份迟早漂。顶栏「更新」= `ExamSync.sync(force = true)`。
- **任选课口径开关只在成绩设置页**（2026-09-30）：成绩页汇总卡的重复开关已删，
  note 行的 `excludedCount` 文案负责展示当前口径生效中。别在两处各放一个开关——
  改口径的地方一多，用户永远对不上「我看到的是按哪个口径算的」。
- **设置项里没有「立即检查」**（用户删，2026-09-30）：`onSettingsChanged` 的 one-shot
  已经是「改完设置当场评估一次」的通道，不要往设置页再塞手动触发入口。
  **数据页的刷新不算这条**：成绩页「从教务导入」（WebView 强制路径）与考试页顶栏
  「更新」（`ExamSync.sync(force = true)`，OkHttp）都是列表数据的刷新入口，允许存在。
  注意「更新」会顺手落新基线，本次变动不再由周期任务重复通知——卡上的「新增/调整」标
  就是这次的结果。
- **通知 requestCode 与 id 解耦**（3008/3009 vs 1008/1009），两落点 intent 只差 extra、
  都无 action——requestCode 撞了会互相改写落点（§3.13 3005/3006 同坑）。
