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
- **七个落点**各调一次 `JwImportOutcomeEffect(snackbar)`：课表页 / 今日页 / 成绩页 /
  学校统一认证页 / 课表中心页 / 考试设置页 / 教务 Tab（2026-10-20 加；后四个
  是本页的「导入课表」「导入成绩」「导入考试安排」「导入」入口，导入窗口 finish
  后落回的就是它们）。新增落点页面时别忘了同时补 `AppSnackbarHost`；`JwAccountScreen` 的
  Scaffold 把 `contentWindowInsets` 归零了，提示条要自己 `navigationBarsPadding()`。
- 四条别改坏：① 课表/考试与成绩两条路径都发，文案各自拼（成绩不报课表名）；
  ② 发布放在**写库之后、`onBack()` 之前**，顺序反了就是「窗口关了、消息还没发」；
  ③ 清通道的时机：**过期消息在进 `showNotice` 之前丢，新鲜消息等 `showNotice` 返回后才
  `consume()`**——consume 把 flow 置 null 会让 `JwImportOutcomeEffect` 的 `LaunchedEffect(outcome)`
  下一帧以 null 重启、取消还挂在 `showNotice` 上的协程，而 M3 的契约是
  「caller cancelled → snackbar removed」：气泡刚挂上去就被撤掉（2026-09-30 修正此前
  「先 consume 再 show」导致五处落点全都不显示）；过期即丢仍然挡着「旧消息在后续组合里
  反复触发」那条老问题；④ 别把落点改回「只在课表页消费」；⑤ 队列节奏是自持的——`OutcomeQueue`
  只在 current 被消费后才换值，所以同一时刻只有一个 `showNotice` 在飞，「导入完成」与
  「教材未同步」两条都会轮到，不会被 `showNotice` 的「新的顶掉旧的」吃掉（2026-10-08 核对）。
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

## 教材（2026-09-28；触发点与失败可见性 2026-10-06 订正）

- 教材来自「教材管理 → 学生教材确认」：壳页 `/jsxsd/nxsjc/jccx` + 数据接口
  `/jsxsd/nxsjc/xsjcqr`（layui 形态，参数 `xnxqid` + `pageNum/pageSize`，**不带 .do**）。
  解析在 `data/jw/TextbookParser.kt`（照 ScoreParser 的三道检查），同步器 `data/repo/TextbookSync.kt`。
- **触发点只有两个**：教务导入写库成功后 `Graph.appScope.launch { textbookSync().syncForTerm(term) }`
  （`ui/jwvw/JwImportScreen.kt`），以及冷启动补抓 `TextbookSync.backfillIfMissing`（`JuwApplication`，
  搭在 CAS 那组，顺序 成绩→考试→学业→教材）。**「导入 JSON 文件」不抓**——它走 `importJson`，
  教材从备份自己的 `textbooks` 段恢复（带数据才整体替换）。
- **失败不再无声**（2026-10-06）：`syncForTerm` 的结果落一条 `Log.i`，导入路径失败另补一条气泡
  「教材未同步：<原因>」。教材真的丢过，原因是会话被别处登录顶掉（强智单会话，
  `.agents/rules/login-session.md`），不是教务没数据——别把「详情里没教材」直接当成数据问题。
- **补抓闸门**（`AutoSyncRules.textbookBackfillDue`）：该学期教材为空 **且** 距上次**尝试** ≥ 6 小时
  才抓。记的是尝试时刻（失败会一直保持「空」，只记成功就变成每次冷启动都打一次教务）。
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

## 培养方案（课程设置总表，2026-10-07，DESIGN §3.26 / §4.41）

- 教务「培养方案」明细页 `/jsxsd/pyfa/topyfamx`（无参数；服务端渲染整页 HTML，由会话决定
  学生与方案），解析在 `data/jw/CurriculumParser.kt`。手机端 `jiaowuh5`「标准版」的
  「课程设置总表」是同一份数据的展示层——**不要走它**（443 直连不通、数据接口在登录后）。
- **学时 5 个子列 DOM class 全同**（`table-td-cell cols-9-2-1`），只能按**行内出现顺序**
  区分（理论/实践/讲座/实验/总），按 class 名找必然错位；「2(周)」这类值原样保留字符串。
  **方案名在「三、课程设置总表」切片之外**（页首段落），必须全页搜。
- 同步纪律与学业同一套（`CurriculumSync`）：mutex 让位、7 天闸门（与成绩/学业共用
  `AutoSyncRules.shouldAttempt`）、先 `CurriculumRules.validate` 再整体替换、失败静默、
  成功才记日期。
- 触发点三处：首启引导登录成功（force，静默、**不进**「同步完成」结果弹窗）、冷启动链
  （成绩→考试→学业→**培养方案**→教材补抓）、页面进页自动 + 顶栏手动刷新。**不做变更提醒**，
  不参与周期任务链。
- 备份走 `CourseExport.curriculumCourses` 段：非空才整体替换、缺课程名的行整体拒绝、
  方案名不进备份（展示性字段，恢复后下次抓取即补）。

## 成绩/考试变动提醒（2026-09-30，DESIGN §4.33）

- **考试自动检查链（`ExamSync`）与手动导入共用同一份 `ExamScheduleParser.parseFetchJson`**，
  只是传输层换成 `CasSession.fetchHtml`（OkHttp）。改解析字段或接口参数时两处一起看；
  接口地址常量在 `JwUrls`（壳页 `EXAM_QUERY` / 数据 `EXAM_LIST_API`，2026-10-04 从
  `ExamSync` 伴生对象收口过去，URL 只此一份），**不带 .do**——带 .do 的同名地址回
  no-open 页，别把「功能被校方关闭」当「没数据」。
- **缺省学期以教务为准**：GET 壳页正则抽 `select#xnxqid` 选中项（`ExamSync.termFromShell`，
  单测钉住 value 属性 / 纯文本 / data-selected 三种形态），抽不到直接 Failed，**不猜学期**
  （猜错的学期会拿空基线当「首跑」吞掉真变动，或拿错学期基线刷一屏假通知）。
  2026-10-04 起壳页带 **12 小时 TTL 缓存**（`AutoSyncRules.shellTermCacheUsable`，考试/选课共用判据）：
  学期下拉一学期才变一次，TTL 内不再打壳页；force（手动更新）永远真抓。学期翻年最多滞后半个
  窗口，而翻年发生在假期，旧学期查出来是空的正巧无害。
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
  学习 hub——**别搬回课表 hub**，用户口径是「考试跟成绩一起看」）。检查间隔
  **2026-10-04 起两处各自独立**（`score_alert_interval_hours` / `exam_alert_interval_hours`；
  用户口径推翻了此前「共用一键」，旧 `alert_interval_hours` 只作老用户读取回退）；
  `ScoreAlertReminder.ensurePeriodicWork` 仍只排一个周期任务，按四链最小档叫醒。
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

## 调课检测与首启自动导课表（2026-10-04 重立，DESIGN §4.17）

- 旧版 2026-09-24 下线的理由是「独立凭证 + 后台自动登录」的成本；重立版没有这两项
  （统一会话层 + 搭既有周期任务）。改这条链前先想清楚：**不要再造出需要单独配置或
  后台自动登录的形态**。
- **课表直取只有一个入口**：`ScheduleSync.fetch`（OkHttp + `CasSession.fetchHtml`），
  解析用 `QiangzhiScheduleParser.parseFromHtml` / `SyjxScheduleParser.parseFromHtml`
  （与 WebView 注入同一套算法的备用路径），合成复用 `OneClickImport.combine`。
  **不要**在这里再写一套解析；学期以理论页 `select#xnxq01id` 选中项为准，带学期请求时
  校验「请求学期 = 返回学期」（同脚本纪律）；实验页结构不可信（无 `qz-weeklyTable`）时
  检查整体放弃——硬比对会把整批实验课报成「教务删除」。
- **HTML 解析必须与注入脚本逐块对齐**（2026-10-07 修）：一格里的**每个**
  `li.courselists-item` 都要抽，切法收口在 `CourseBlocks`（两页共用，别再各写一份）。
  同格多课块是常态（单双周拆块 / 回课时换教室；实测真实理论页 35 个数据格里 9 格是多课块）。
  只取第一个 = 静默丢课 → 直取结果比基线少几门 → 全被误报「教务删除」，而**重新导入
  也修不好**（每次检查都重爬重解析）。同一个坑的验证方法：拿 `scripts/fetch_courses.py`
  / `fetch_lab_courses.py` 的产物（真机口径）与 `parseFromHtml` 逐条对，0 差集才算齐
  （2026-10-07 复核已在 2025-2026-2 / 2026-2027-1 两个学期对到 0 差集）。
- **周次解析的三个口径**（`QiangzhiScheduleParser.parseWeeks`，scripts 侧 `parse_weeks` 同口径）：
  ① 「单周 / 双周」标注（实测 `1,11单周` / `2,6双周`，挂整串末尾）数字照解析、按奇偶过滤——
  带标注的 token 不许丢（丢了课少排几周）；② 脏 token 抠数字兜底；③ 全空才判失败。
- **两页学期互校**（2026-10-07 复核补）：实验页也有 `select#xnxq01id`（单引号 + 无值
  selected 形态），`fetch` 用 `labTermError` 校验它与理论页一致，抽不到下拉同样判不可信
  ——教务若忽略实验页学期参数，混两个学期比对会刷一屏假差异。
- **整类课「有 → 零」闸门**（`vanishingKindError`，2026-10-07 复核补）：理论 / 实验一整类
  解析为 0 条而基线里该类有货 → 判 Failed（不报差异、不落检查时刻，下周期重试）。只守
  这一侧：基线没有、教务新有的（0 → N）是正常新增，照报。
- **首启自动导入只此一次**：`importIfEmpty` 对非空课表一律 Skipped。把它的调用点挪到
  周期任务、或放宽成「覆盖合并用户课表」，等于让 App 静默改用户的课表——禁止。
  结果弹窗（`OnboardingSyncOutcome`）消费即清，startAtJw（改密码重进）不写不弹。
  **实验页结构不可信时按失败报告**（2026-10-07 复核补）：静默导入会把实验课整批漏掉而
  用户看不到分项条数，报失败让他手动导入一次核对（手动弹窗有分项提示）。
- **基线语义**：`ScheduleBaselineStore`（filesDir JSON）=「上次与教务达成一致时的课表
  状态」。推进点只有三个：导入写库成功（`importParsedCourses` / `importJson` **收口在
  repo 内**，新导入入口不许绕过）、合并差异成功、检查首跑（不通知）。**检查成功不推进
  基线**——这点与 `ExamSync` 相反（考试基线是「上次抓到什么」，调课基线是「用户认可了
  什么」），照抄考试链会让差异永远消失。基线只存 Theory+Lab（教务抓不到考试）。
  **基线只装教务口径的条目**（2026-10-07 复核补）：
  - 合并路径的推进 = 旧基线套用已应用的改动（`ScheduleDiff.baselineAfter`），**不是**
    拿写库后的课表整体重建——重建会把手动添加 / AI 调课加进来的本机条目塞进基线，
    下次检查全被报成「教务删除」，勾选合并即删掉用户自己的课；
  - `importJson` 的推进**必须排在课程写完之后**（此前调用点写得太早，基线存的是恢复前的
    旧课表，恢复完一查全是假差异）；
  - 整体覆盖导入（`importParsedCourses(merge=false)`）仍是「课表=教务口径」，走
    `refreshBaselineFromDb` 整体重建。
- **待处置缓存的失效口径**（2026-10-07 起）：基线推进（导入写库成功 / 合并差异成功）与
  「检查成功且无差异」两处都会清掉属于该课表的待处置缓存。缓存是派生数据（拿基线一比
  就能重算），不这样清，处置页会一直挂着已经不存在的变动，而「合并」会照指纹把它们
  套到当前课表上——静默删课（用户报障里「重新导入后仍显示有差异」的另一半）。
- **diff 纪律**（`domain/ScheduleDiff`）：指纹只含教务字段——把 colorIndex/remark/
  自定义时间加进指纹，本机数据一动就报假差异；配对锚点是**同一天 + 同一节次**
  （2026-10-20 修）：组内 1:1 仍直接配「调整」，多条时只认同槽位的对应，同槽位候选
  多于一个时按周次重叠严格最大配对、平手不配。**不能只按数量 1:1 配对**——教务表达
  「某一周调课」的固定写法是「原块去掉那一周 + 新块只含那一周」，1 删 2 增会被报成
  「删除整门课 + 两条新增」，默认勾选只剩删除。冲突（用户改过的课）默认不勾；
  ADDED 的冲突判据是**同一课块（`mergeKey`）且周次有交集**（合并会跳过），同门课的
  其他时段不算冲突。改任何一条都要同步 `ScheduleDiffTest` 的契约用例。
- **重复打扰双闸**：差异指纹 = 已阅指纹（忽略过）或 = 待处置缓存指纹（提醒过未处置）
  → 不再发通知。合并/忽略后「已阅指纹 = 本次展示的完整差异」——未勾的条目下次检查
  会以剩余差异的新指纹重新报出，这是有意行为。
- **合并是唯一的写路径**：`applyScheduleChanges` 事务内逐条、指纹匹配不上就跳过不硬套、
  新增按「同 `mergeKey` 且周次有交集」防重（周次不相交的同槽位课块是补课 / 回课时换教室，
  另插一行）、修改保留原行 id/配色/备注。**后台静默改课表是红线**——
  `ScheduleAlertReminder` 只发通知，点通知落处置页（`SCHEDULE_CHANGES`，requestCode 3011
  与 3008/3009 错开），差异的每一条都要用户勾选。
  > 2026-10-06 起事务体抽成私有 `applyChangeSet`（唯一实现）；2026-10-07 复核起
  > `applyScheduleChanges` = 它 + `advanceBaselineFromApplied`（旧基线 + 已应用改动，
  > 见上「基线只装教务口径的条目」），AI 调课走 `applyLocalChanges`
  > = 它本身（**不推基线**）。改这条链时两个出口一起想，见 `.agents/rules/ai-schedule.md`。
- 周期检查搭 `ScoreAlertReminder` **第四路**：`ensurePeriodicWork` 的全关判定、`check` 的
  开关列表、`onSettingsChanged` 的清时刻，三处改任何一处都要四个开关一起想。

## 选课（2026-10-01 建；2026-10-08 窗口期接入真实接口，DESIGN §3.19 / §3.20 / §3.21 / §3.22 / §4.35 / §4.36）

- 选课结果 = 教务「选课日志」`loadXsxkjgList?lx=xkrz&type=list&xnxqid=<学期>`（**不带 .do**，
  layui JSON）；轮次 = `xsxk/xklc_list_data`。解析 `data/jw/SelectionParser.kt`，同步
  `SelectionSync`（OkHttp + `CasSession`），落 Room v17 `course_selections`（按学期替换）。
  接口字段与实测见 DESIGN §4.35，脚本侧 §5.8（`fetch_selections.py`）。
- **应用内选课中心的三条接口**（2026-10-08 窗口期实测，真身 `SelectionCenterHttpClient`，
  解析 `SelectionCenterParser`）：
  ① 列表 = **POST** `xsxkkc/xsxkBxqjhxk`——body 必须是**旧版 DataTables 参数**
  （`sEcho`/`iDisplayStart`/`mDataProp_i`…，键集是契约：缺了教务回它自家 **404 错误页**），
  查询条件在 URL 上（页面 `queryKxkcList` 全参数面，2026-10-08 v2 起全部接入）；
  ② 提交 = GET `xsxkkc/bxqjhxkOper?kcid=<jx02id>&cfbs=..&jx0404id=..`（照页面 JS 口径，
  空 cfbs 页面会拼字面量 `null`——照抄）；
  ③ 退课 = GET `xsxkjg/xstkOper?jx0404id=..`。课程主键 = `jx0404id|jx02id|cfbs` 拼串
  （`SelectionCenterParser.splitCourseKey`）；验证码开关 `input#sfyzmxk` 在列表壳页
  `getBxqjxk` 读，为 `"1"` 一律拒绝（红线：不绕验证码）。
- **「先进轮次」是 xsxkkc 模块的会话前提**（2026-10-08 复测坐实）：列表/壳页请求前，
  当前会话必须 GET 过 `newXsxkzx?jx0502zbid=<轮次>`——没进过时壳页只回 534 字节空存根、
  列表一律回教务 404 页（**同参数进轮次后 200，不是参数问题**）。`prepare` 负责进轮次
  （进程内一次）；会话被顶重登后这个状态会丢 → 列表 404，`fetchPageRetrying` 会
  **重进轮次再试一次**（读语义可重试；写路径的「不自动重登重发」不动）。
- **列表的查询条件与「浏览全量」**（v2 实测）：`kch`（课名/编号子串）、`jg0101id`
  （教师名文本）、`skxq`（1-7）、`skjc`/`endJc`（1-11，须配星期）、`kctype`（01-09）、
  `sfym`/`sfct`/`sfxx`——服务端 **AND** 叠加，全部接入 UI（`SelectionQuery`）。
  **条件要求**：`kch`/`jg0101id`/`skxq` 至少一个非空（全空回 0 行、`%`/空格也回 0——
  服务端按字面匹配）；**浏览全量 = `kch=0`**（编号全以 0 开头；数字扫描并集 48/48 覆盖，
  `SelectionCenterParser.BROWSE_KEYWORD`）——空关键词/教师时由 `listQuery` 注入。
  星期扫描会漏「无排课时间」的课程设计类（实测差 5 行），别拿它当浏览方案。
- **三个开关的服务端语义**（v2 实测，App 默认值据此定）：`sfct=true` 会把所有带 `ctsm`
  的行滤掉——**连「该课堂已选」的已选行一起**（48→15）→ **App 默认 `hideConflict=false`**
  （否则已选课从列表消失、退课入口断链；教务页面自己默认勾选，不跟）；`sfym=true` 滤满员
  （48→46，默认关）；`sfxx` 默认 true 保持既有口径。
- **写路径只剩一条、过 `SelectionCenterClient` 抽象**（`Graph.selectionCenterClient` 是
  唯一替换点）：**用户逐次确认**的手动加/退课（应用内中心 + 确认弹窗 `SelectionConfirmDialog`）。
  **自动抢课整条链已于 2026-10-08 v3 下线**（引擎 / 前台服务 / 面板 / 到点自动开始 /
  抢课须知，用户拍板「风险与未知太大」；留档见 DESIGN §3.21）——**没有任何自动提交、
  后台轮询或到点自动执行**；预选清单改「预购匹配」（命中课程优先显示，见下一条）。
  **写请求不自动重登重发**（`CasSession.fetchForAction`：会话被顶就停手、让用户重登——
  防重复提交；读路径的 POST 查询才允许重登重试一次）。`JwImportMode.Selection` 的 WebView
  仍是备用入口（选课页「教务网页」行），里面由用户自己操作。
  **联调期 debug 落盘**：`filesDir/selection_debug/`（`SelectionDebugDump`，debug 包才写、
  release 传 null；目录已进两套备份排除）——只落提交/退课应答原文，校准三态映射用。
- **预选命中（预购匹配，v3）**：选课中心进页 / 刷新时按预选清单**逐条**调
  `fetchCourses(roundId, [wish.nameKeyword])`（服务端只按课名/编号查，每条 1–3 页、串行
  `interRequestDelayMs` 间隔；**不周期轮询**），`SelectionWishHits.group` 本地复核教师关键词、
  按优先级归组去重；命中区块置顶优先显示，主列表同打标。匹配失败只在该区块提示 + 重试，
  **不影响浏览与手动选课**。预选清单页（`SelectionWishesScreen`）进页同款匹配一次，标
  「本轮已开 N 门」。
- **红线补充**：`/jsxsd/xkgl/Xsxkjg_tk.do`（申请退课，老入口）与 `/jsxsd/xsxk/mzlist.do`
  （免责声明查询）**任何路径都不碰**——2026-10-08 实测直接 GET 进选课页与数据接口都可用、
  未见免责硬门槛，App 依旧不代发；若真机提交遇到免责门槛再回来定（先例：只由用户动作触发）。
  **脚本永远只读**（探针也不例外；列表查询虽是 POST 但语义只读）。**退课永远只有用户
  逐次确认这一条路径**（没有任何自动退课入口）。
- **默认抓「当前学期 + 紧邻下一学期」**：选课常发生在学期末选下学期，光抓当前学期会漏掉
  「已选下学期」这件最要紧的事（`SelectionSync.resolveDefaultTerms`，学期从壳页选中项来、
  不猜；2026-10-04 起同样吃 12 小时壳页缓存，非 force 复用上次抓到的学期）。先全部抓到内存、
  再一次性落库——中途失败不留半套。
- **轮次快照允许存空**（`SelectionRoundsStore` 与 `ExamSnapshotStore` 的「宁旧勿空」相反）：
  `count=0`（非选课期）是教务的权威状态，存空才会让页面如实显示、让过期提醒被撤销。
- **提醒的时间解析**：`xksj` 真实格式已实测 = `2026-10-08 10:00~2026-10-12 23:59`
  （`~` 无空格；夹具在 `SelectionRoundsTest.parsesRealWindowSample`）。容错口径不变：
  日期+时刻对 / 仅日期对 / 单个时刻，**解析不了就不排**。
  提醒点 = 开始前 30 分钟 + 截止前 6 小时；已发键 `轮次id|start` / `轮次id|end` 落 DataStore，
  闹钟与周期核对共用去重；发不出去不落键（同作业提醒）。
- **周期核对搭 `ScoreAlertReminder` 的周期任务**（核对间隔独立设置
  `selection_alert_interval_hours`，2026-10-04 起；轮次提醒本身由轮次时刻的精确闹钟驱动）：
  `ensurePeriodicWork` 的开关条件与 `check` 里多了选课一路，
  改那两处时三个开关（成绩/考试/选课）一起想。闹钟落点 `SelectionAlertReceiver`
  （requestCode 4004，与 4002/4003 错开），开机补排在 `ReminderBootReceiver` 里顺带叫它。
- **`JwImportMode.Selection` 没有导入动作**：底部只给「选课中心」一个入口，
  `if (mode != JwImportMode.Selection)` 挡住的导入按钮别顺手取消；落页判据用
  `JwUrls.isSelectionCenterUrl`（**不要**用 `/jsxsd/xsxk/` 前缀——轮次页、进选课页
  `newXsxkzx`、预览页 `yxxsxk_index` 都在它下面）。
- **轮次直达**（2026-10-01；同日排版收口后语义变化）：选课页轮次行现在直达**应用内
  选课中心**（轮次 id 走二级页 `focusItemId` 通道 → `SelectionCenterScreen.focusRoundId`，
  快照认不出退回 `pickTarget`）。带轮次 id 开教务 WebView（`JwImportActivity.start(...,
  selectionRoundId=)` → `autoNavTarget = JwUrls.selectionRoundUrl(id)`，`newXsxkzx?jx0502zbid=`）
  只剩中心「打开教务页面（备用）」一处调用。轮次 id **必须过 `ROUND_ID_PATTERN` 白名单**
  （会拼进 URL，同 `TERM_PATTERN` 纪律），不合法一律退回选课中心列表。2026-10-08 实测：
  直接 GET `newXsxkzx` 可进（页面渲染与数据接口都可用）；从列表进时教务仍会先 POST
  `mzlist.do`（那一步 App 不碰）。预览页受 `yxzt=='1'` 门控，不满足回 no-open 页（实测）。

## 自动落页：触发点用 onPageCommitVisible（2026-10-01 修）

- **登录落地（`xsMainV`）→ 各模式目标页的自动跳转，触发点必须是 `onPageCommitVisible`
  （首选）或 `onPageFinished` 的探针回调（兜底），两者共用 `startAutoNav`**，
  别改回「只在 `onPageFinished` 里 `postDelayed`」——那条链会被挂住的子资源拖住，
  迟迟不来时页面永远停在 `onPageStarted` 写的「加载中…」且**没有任何后续触发点**
  （2026-10-01 用户报障：点「进入选课中心」卡在教务主页面，手动再点一次才走）。
- 三条配套纪律：① 会话探针守卫不变（未登录时登录页就地渲染在 `xsMainV` 上，不看清内容就跳
  会跳到登录页上）；② **只跳一次**（`autoNavDone`），延迟复核失败/看门狗到点都要把状态
  解锁成 `Ready` + 可操作提示，**任何路径都不允许悬在 Loading 且无出口**；
  ③ 顶栏刷新与错误浮层重试会把 `autoNavDone` 放开重试一次（看门狗的提示就指向刷新）。
- `autoNavPending` 是「本轮跳转在途」的闸门：用户在途期间手动发导航（底部按钮/刷新）
  会清掉它，在途的那一跳据此作废（回调里先判 `autoNavPending`）。

## 电脑版预览：教务页的 viewport 覆盖（2026-10-08，DESIGN §4.4.3）

- **挤压的根因是页面自己的 meta**，不是 WebView 配置漏了：教务业务页都带
  `width=device-width, initial-scale=1.0`，而 `useWideViewPort = true` 会尊重它 →
  布局宽 = 设备宽（约 411 CSS px）。`initial-scale=1` 同时把最小缩放钳在 1.0（只能放大）。
  登录页没有 meta，所以它一直是 980 桌面宽度 + 缩放到屏幕——**电脑版就是让业务页跟它同口径**。
- 三段脚本 + 判据收口在 `ui/jwvw/JwWebFit.kt`（纯 Kotlin，可 JVM 测）：
  `DESKTOP_JS`（覆盖）/ `DESKTOP_REVERT_JS`（撤销）/ `SCHEDULE_FIT_JS` + `LOGIN_FIT_JS`（既有，
  从 `JwImportScreen` 原样搬入）；`fitScript(url, desktop)` 是**唯一判据入口**，
  别在别处再写一份「哪些页要覆盖」。
- **红线一：覆盖必须整串替换 content**（存原文到 `data-juw-orig` 再写
  `width=980, initial-scale=<铺满屏幕的倍数>`）。只改 width 会留下 `initial-scale=1` /
  `user-scalable=no` → 不能缩小 / 不能双指放大。
- **红线一之半：`initial-scale` 必须自己写出来**（2026-10-08 用户报「手机版切电脑版界面不变，
  得手动缩小才看得出差异」）。**Blink 只在 meta 里显式写了 `initial-scale` 时才重新套用缩放**：
  只改 `width=980` 时它只重新布局（`iw` 412→980），缩放停在 1.0 → 屏上只看得见 980 宽布局的
  左边 412px、字号不变，看着就是"没变化"（首屏那 0.42 倍是 WebView overview 口径给的，动态改
  meta 不会重算）。倍数 = 屏幕 CSS 宽 / 980，屏幕宽取 `visualViewport.width × scale`
  （乘 scale 后与缩放无关，双指缩放不打架），拿不到就退回裸 `width=980`。
  实测（真机 WebView，CDP 跑脚本原文）：`width=980` → `iw=980 vvw=412 scale=1.000`；
  `width=980, initial-scale=0.4206` → `iw=980 vvw=980 scale=0.421`（`vvw == iw` 即铺满）。
- **红线二：登录 / SSO 页（`/cas/login`、`Logon.do`、`/sso.jsp`）不覆盖**。它们本来就没有
  meta（已是 980 口径），而自动填表（`JwAutoLogin`）与整条会话链挂在这几页的 DOM 上。
- 幂等判据**看 content 不看标记**：`onPageCommitVisible` 与就绪路径两轮都注入，第一轮可能
  跑在页面自己的 meta 之前（那时我们新建的 meta 会被页面后到的盖过），按标记早退就补不上了。
- **重算时机**：`DOMContentLoaded` / `load` / `pageshow` / `resize` 四条挂同一个处理函数
  （存 `window.__juwViewportApply`）——首测 0×0、转屏后 980 不再等于屏宽都靠 `resize` 补算。
- **红线三：撤销要把常驻的东西全摘掉**（自愈 `MutationObserver` + 上面四条监听）。只还原 meta
  会被它们当场压回 980——尤其 `resize`：**还原视口这一步本身就派发 resize**，用户看到的是
  「切回手机版没反应」。这条是 2026-10-08 在真机 WebView 里用 CDP 跑这两个脚本时踩到的
  （单测与 Chrome 侧替身脚本都看不出来——那边是直接改 meta，没跑这两个脚本）。
- **切换不重载页面**：实测改 meta viewport 会触发 Blink 重新布局（模拟器实测 `iw=412 → 980`），
  所以顶栏开关是当场重注入——重载只会白等一次网络，还会把选课中心 iframe 里的列表与筛选状态清掉。
- 选课中心是「壳页 + `width="100%"` 的 iframe」，iframe 内的 meta 不参与布局 → 父级变宽它就跟着变，
  **不要**去给 iframe 单独注入。
- 偏好 `jw_desktop_mode`（默认开，独立单流，见 `DisplayPrefsStore.jwDesktopMode`）。
  关掉时行为必须与引入电脑版之前逐字一致——`JwWebFitTest` 有回归闸钉住。
- 验证靠 `viewport probe`（`JwImportScreen` 每次 `onPageFinished` 都打）：业务页 `iw` 应 ≈980，
  **CAS 登录页应保持 ≈412**（未被覆盖）。2026-10-08 模拟器端到端已验证这一组数字。
  ⚠️ **探针是 timing 敏感的**：它在 `onPageFinished` 里跑，可能早于"就绪路径"的第二次注入，
  于是会打出一帧的旧宽度（实测偶发 412）。要判"最终生效没生效"，看**渲染结果**或页面自己
  在 load 之后读的 `innerWidth`，别只信这一行。
  ⚠️ 探针**不报缩放**，判「整页铺满没有」看的是 `vvw == iw`，得另测。现成办法是 CDP：
  debug 包开了 `WebView.setWebContentsDebuggingEnabled(true)`，
  `adb forward tcp:8000 localabstract:webview_devtools_remote_<pid>` 后连 `/json` 里的 ws 目标
  `Runtime.evaluate`（2026-10-08 就是这么验的）。

- **各业务页顶栏的「网页」按钮也走这个窗口**（2026-10-20）：统一入口是
  `JwWebActionButton` → `JwImportActivity.startBrowse()` → `JwImportMode.Browse`。
  不要再新建 WebView 窗口；Browse 模式没有导入按钮，教务目标仍从 `SSO_WARMUP`
  起跳，非教务目标直接开目标页。网页核对由此天然继承电脑版/手机版、
  返回先关弹层、会话失效回登录与错误态。
- **cookie 过期后的自动重登与落页**（2026-10-21，用户报「网页核对入口重登后不自动跳」）：
  ① Browse 的「落点」不再只认 `xsMainV`——`startAutoNavOnLanding` 接受教务域里任何
  已登录的普通页（`JwUrls.isAuthHop` 排除 `sso.jsp` / `LoginToXk`，`isLoginLikeUrl`
  排除登录页，`samePath` 排除目标页本身），重登后 SSO 直接回发起页也能导到目标；
  ② 探针认出登录页 / 会话被顶时，Browse 模式**自动**回 `SSO_WARMUP` 重走认证一次
  （`browseRecoveryUsed`，有凭证才走；顶栏刷新与错误浮层重试会把它重新放开），
  不再停在错误浮层等人点重试；
  ③ 自动填表返回 `no-form` 时用 `SessionStatus.releaseAutoLoginAttempt()` 退回进程级
  5 分钟节流（那一跳没真的提交，不该白占窗口）；探针通过后用
  `markAutoLoginSucceeded()` 放开节流（登录成功说明凭证没错，下次过期应能立即重登）。
  三条都别改回「只看 xsMainV / 只给错误浮层」。

## 教务窗口的返回语义（2026-10-08，DESIGN §4.4.4）

- 用户报「有时候按返回没有用」。根因是历史里有**退过去看不出变化**的条目，不是返回键坏了：
  ① 重定向链 / 同址重载 / `location.href` 指回自己留下的**同址条目**；② App 自己走过的
  **CAS / SSO / 教务登录页**。
- 口径：**退到最近一个"有意义的业务页"，没有就关窗口**。判据 `JwWebBack.backDelta`（纯函数，
  跳过同址 / 同址不同 fragment / 认证链 / `about:blank`，跳无可跳返回 null → 关窗口）。
  **系统返回（手势 / 三键）**走 `backOrClose()`；**顶栏左上角箭头是直接退出窗口**（`onBack()`）
  ——两边语义分开，别再把它们并成一个动作；**也不要再引入 `canGoBack` 直接 `goBack()`**
  ——它只能回答"能不能退"，回答不了"退了有没有意义"。
- 注意 `Logon.do`（教务独立登录）、`/sso.jsp`（预热入口）、CAS 域都算认证链，别只判 `/cas/login`。

## 「内容为空」探针的 iframe 例外（2026-10-08）

- `PAGE_CONTENT_PROBE_JS` 只量顶层文档的文字与元素数；**选课中心是「壳页 + 三个 iframe」**，
  内容全在子文档里 → 旧口径必判空，用户在选课中心永远看到「页面已加载但内容为空，可点重试」。
  现在**有 iframe 一律不算空**（返回 999）。探针是白屏排查的启发式，不是"内容存在"的证明，
  宁可漏报不可误报——要改这条判据时先想清楚这个前提。

## 教务弹层（layui layer）的两条纪律（2026-10-08）

- **弹层宽度必须收口**（用户报「选课结果等弹窗显示不全」）。根因在教务自己的
  `assets_newL/js/qzDialog.js`：弹层尺寸是硬编码像素（`选课结果` = `area: ['1500px','720px']`，
  见 `scripts/out/xsxk_c_iframe_selectBottom_*.html` 的 `xkrz()`），定位却是
  `left = (视口宽 - 弹窗宽) / 2` → 视口 980 时 left = **-260px**（实测
  `layer0=-260,451 1500x720 inlineLeft=-260px winW=980`），左边够不着、右边溢出，两头被切。
  收口见 `JwWebFit.LAYER_FIT_CSS`：`max-width: calc(100vw - 8px)`（max-width 恒定胜过 layui 的
  行内 width）+ `left/right:0 + margin:auto` 重新居中，**不动 top**。改这条时注意：
  弹层样式随电脑版开关一起注入/撤销（关掉电脑版要逐字还原）。
- **返回键先关弹层**（用户报「按返回不会关闭弹窗，实则是窗口后的界面已经返回成功了」）。
  `backOrClose()` 先注入 `JwWebFit.CLOSE_TOP_LAYER_JS` 点弹层自己的关闭按钮
  （`.layui-layer-close`，与手点同一入口），返回 `1` = 关掉了 → 这一下返回就用掉，**别顺带退页**；
  没有弹层才走 `JwWebBack` 的退页 / 关窗口。实测：弹层开着按返回 = 关弹层 + 0 次导航。

## 教学日历 → 开学日（2026-10-07，DESIGN §4.42）

- 数据源 `/jsxsd/jxzl/jxzl_query?xnxq01id=<学期>`（**学期参数真实生效**；不带参按教务
  当前学期渲染。快照 `scripts/out/jxzl.html`）。解析 `TeachingCalendarParser`、
  同步 `TeachingCalendarSync`（`data/repo/`）。
- 两条最容易写错的：**工作日列是裸日号、周末列才是 `MM月DD日`、全表无年份**（年份从学期号
  推导 + 周六格必须真是周六 + 周一/周二裸日号交叉校验）；**响应学期 ≠ 请求学期一律放弃**
  （不猜学期——教务没有该学期日历时的回落必须挡住）。
- 触发点与教材同两个（WebView 导入写库成功 / 首启 `importIfEmpty` 成功），**失败静默只记
  `Log.i`**、值没变不写库不弹泡（区别于教材的失败气泡：开学日失败 = 维持现状，不是数据
  丢失，没有「去哪儿看」的问题）。
- 口径「教务为准」：每次导入重设；用户手改只保证到下一次导入（课表设置照常可改）。
  **不动总周数**——课程侧口径与日历 25 周编制口径可能不一，不顺手改。

## 学生评教直达（2026-10-07，DESIGN §4.44）

- 评教**没有静态 URL**：`toZlpt_11319('jwxsjdp')` = POST `/jsxsd/qzzlpt/getToken_11319`
  取 token → `token + '/jwxsjdp'`（跨主机 `jiaowupj.juwp.edu.cn` 的 SPA；HTTP 明文已在
  `network_security_config` 放行）。窗口形态 = `JwImportMode.TeachingEval` 复用教务
  WebView（CAS 自动登录 → xsMainV → 注入 `JwTeachingEval.launchJs()`——页面自带 jQuery
  `$.ajax` 取 token 后 `location.href` **同窗口**跳；不要改成 window.open）。
- **红线：App 只导航，不代评、不提交任何接口**；注入发起后无导航由 `AUTO_EVAL_TIMEOUT_MS`
  （15s）原地看门狗给状态条出路，顶栏「刷新」放开 `autoNavDone` 即重试（既有机制，别另造）。
- **token 不落日志**：平台域 URL 在 9 处日志点经 `JwTeachingEval.redactForLog` 脱敏
  （`/jwxsjdp` 路径带 JWT、`/home?xlgToken=`）——改这些日志点时别把 `$url` 直接插回去；
  `checkSessionLost` 对非教务域本来就直接放行，平台页不受会话探针干扰。
- 平台页跳过白屏探针（SPA 空壳必误判「内容为空」）；底部无按钮（同选课模式的守卫口径扩展，
  `when (mode)` 四处穷尽分支由编译器点名）。
- **2026-10-07 复查修**：错误浮层/诊断也会带 URL（`JwImportDiagnosis` 正文拼 url）——
  `networkFailure`/`httpFailure` 的诊断参数改收 `redactForLog` 脱敏串（原文只留
  `lastFailedUrl` 做等值匹配）；SSL 日志补脱敏（第 10 处）；15s 看门狗带「尝试代次」
  （`evalAttempt`），刷新后旧看门狗作废，不再补假告警。
- **移动端评教页（:81 `/#/jspxxsp`）调查结论（暂不接入）**：其 SPA 启动认证调用
  `/api/AuthenticationController/user/info` 用 `Bearer <xlgToken>`，**教务
  `getToken_11319` 的 JWT 实测被拒（401）**；平台 CAS/OAuth 配置指向外校域名
  （nit / zhku，疑似未配置好）。若拿到其平时进入方式（移动端教务免登 ticket /
  该页登录形态）再评估。快照 `scripts/out/pingjiao_81_*`。

## 实习基地查看（2026-10-07，DESIGN §4.43）

- 数据接口 `/jsxsd/sxsxgl/sxjd.do`（layui JSON）。**该接口忽略一切分页参数**（实测
  `page` / `pageNum` / `pageSize` / `limit` 全形态都回同一页前 10 条；`count` 字段实测
  不可信——曾声明 17，而教务页面实际只有 10 条）。**单次抓取、不做翻页**；
  **界面只显示实际返回条数，不展示 `count` / 差额**；**多翻只会合成重复行**（曾因按页循环拿回两页同样的
  10 条，真机上表现为「每张卡重复一份」）。
- **12 小时快照缓存**（`InternshipSnapshotStore`，filesDir JSON、**已进两份备份排除
  xml**）：新鲜期内进页零网络（`InternshipCacheRules.shouldUseCache` 纯函数判定），
  顶栏刷新强刷；更新失败**宁旧勿空**（抓过就有旧快照带 `updateFailed` 标记展示）。
  **整个读写收口 `Dispatchers.IO`**（页面从 `LaunchedEffect` 主线程直接调装载器，
  2026-10-07 复查修——快照文件与凭证闸门都是阻塞 IO）。**空结果口径**：`count≤0` 的
  权威空也写快照（页面如实显示，先例 `SelectionRoundsStore`）；`count>0` 却零行按
  「条目缺名称、结构可能已变」走宁旧勿空。
- 解析 `InternshipBaseParser`：三道检查照教材；缺「基地名称」的行丢弃；JSON null 按空串
  收；`data` 为 null 不抛（`as? JsonArray`）；`count` 缺失按 -1（未知）。
