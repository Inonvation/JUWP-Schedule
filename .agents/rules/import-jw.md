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
