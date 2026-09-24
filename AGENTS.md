# JUWP Schedule · Agent 工作规范

本文件给 AI Agent / 结对工具读。人看 `README.md` 与 `DESIGN.md`。

## 项目身份

- 名称：JUWP Schedule / 显示名「水贝贝」
- 学校：江西水利电力大学（**非官方**；对外文案必须免责声明）
- 包名：`edu.jxslu.schedule`（debug 变体加 `.debug` 后缀，详见「工程实况」）
- 形态：单模块 `:app` · Kotlin · Compose + Material3 · minSdk 26 / compileSdk 35

## 必读顺序

**按需读，不要通读**——这是公开仓库的上下文成本纪律：

1. 本文件（先读，看完就知道该去哪个文件找什么）
2. `PROMPTS.md` 只读你要做的那一个阶段块
3. `DESIGN.md` **只读与任务相关的章节**（§3 导航/UI 规格、§4.1–4.8 结构规格、§3.5 作息表）；
   历史实现记录在 `docs/devlog.md`（仅本地），只在排查"当初为什么这么改"时才翻
4. 只读参考：`F:\light-life-v3.0`（胖乖）、`scripts/`（爬虫脚本，说明见 `scripts/README.md`）

改导航或课表领域模型前，必须先改 `DESIGN.md` 对应章节（不是 devlog）。

## 工程实况（勿按过时文档猜）

| 项 | 现值 |
|----|------|
| Gradle | Wrapper **8.10.2** |
| AGP | **8.7.3** |
| Kotlin | **2.1.21**（+ compose / serialization / KSP 同版本） |
| Room | **2.7.1**（2.6 + Kotlin 2.1 会 KSP `unexpected jvm signature V`） |
| Room DB | **v8**：v2 加 `courses.kind`（理论/实验），v3 加多课表（`timetables` 表 + `courses.timetableId`），v4 加成绩表 `scores`，v5 加调课检测（`detect_baselines`/`detect_reports`），v6 加一卡通流水（`ykt_turnovers`，orderId 主键 + jndatetime 索引），v7 加笔记·课件与作业（`notes`/`homework`，**按课程名归属、不带 timetableId**，DESIGN §4.20），v8 加 `courses.remark`（课程备注，DEFAULT ''，DESIGN §4.3）。实体 `@Index` 必须与迁移 `CREATE INDEX` 对齐，漏声明会迁移校验崩溃；逐级 `ALTER TABLE`/`CREATE TABLE`，**禁止**改 destructive |
| Room DB | **v9**（2026-09-23）：`homework` 去 `title` 列（重建表搬数据，DESIGN §4.20）；作业无标题，列表/通知文案用 `homeworkDisplayTitle`（正文第一行摘要，唯一口径在 `domain/Homework.kt`，勿在 UI 另写） |
| 作息表 | **11 小节**（每节 40 分钟，大节内 5 分钟、大节之间 20 分钟换教室），见 DESIGN 3.5 |
| 课表网格 | 行号 = **小节号 1–11**（不是大节号）；`Course.startSection/endSection` 也是小节号 |
| HugeIcons | `com.github.rikkahub:hugeicons-compose:1.4`（**JitPack**，**`isTransitive = false`**） |
| osmdroid | `org.osmdroid:osmdroid-android:6.1.18`（Maven Central；POM 里**没有** `<dependencies>`，不拉传递依赖）。瓦片源是自建的高德栅格地址（`ui/ebike/OsmMapView.kt`），初始化三个坑见 DESIGN §4.23；**加依赖后第一次构建要联网 resolve 一次**，之后 `--offline` 照常用 |
| Glance | `androidx.glance:glance-appwidget:1.2.0`（桌面小组件，单条目 `SizeMode.Exact`）；传递抬 compose runtime 至 1.7.8，`androidx.core` 仍 1.15.0 |
| 图标用法 | `import me.rerere.hugeicons.stroke.*` + `HugeIcons.Calendar01` 等 |
| 课表背景图 | 全局显示偏好（`TimetablePrefs.bgImage*` 五字段，存 `view_prefs_json`），文件在 `filesDir/schedule_bg/` 只留一张（DESIGN §4.21）；**不要**并进 `notes_img/`，`AttachmentStore.sweep` 会按笔记引用差集把它删掉 |
| 样例课 | **已移除**；课表默认空，从教务 WebView 导入 |
| 包名 | release = `edu.jxslu.schedule`；debug 加后缀 = `edu.jxslu.schedule.debug`（两者签名不同，**必须**靠后缀区分，否则互相覆盖安装） |

HugeIcons **不要**写 `me.rerere:hugeicons-compose:1.0.0`（Maven Central 不存在）。不要打开其传递依赖（会拉 `androidx.core` 1.17，AGP 8.7/compileSdk 35 编不过）。

## 常用命令

```powershell
# 构建 + 测试（本机依赖已齐备，加 --offline 后秒级完成）
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline
# APK: app\build\outputs\apk\debug\app-debug.apk
# release（R8 压缩 + 签名，约 1.5 分钟）：app\build\outputs\apk\release\app-release.apk
.\gradlew.bat :app:assembleRelease --offline
```


- **release 自 2026-09-21 起开启 R8（`isMinifyEnabled` + `isShrinkResources`）**：体积 17.9MB → 3.6MB。
  混淆规则改动（`proguard-rules.pro`）后**必须装 release 包冒烟**，且要冒到真实网络路径
  （胖乖开水这类 Retrofit 接口）——R8 的问题不在编译期暴露。已踩的三个坑：
  1. Tink 引用的 errorprone 注解、KeysDownloader 的可选依赖缺失 → `-dontwarn` 收口；
     **不要**写成 `-keep class com.google.crypto.tink.**`，那会把缺口一起保住；
  2. **只被泛型签名引用的模型类被整类删除**（2026-09-21 开水接口的真实根因，别再按"签名被剥"查）：
     R8 静态分析看不到使用者（Retrofit/序列化都走运行期反射），把 `data/qiekj` 的模型类
     （如占位类 `EmptyData`）与 `EmptyData$Companion`、`EmptyDataSerializer.INSTANCE` 删掉，
     于是 `ApiEnvelope<EmptyData>` 的签名实参退化成 `Object`，调用时抛
     `Unable to create converter for ApiEnvelope<java.lang.Object> for method …`；
     **只有用到被删类型的接口会炸**，其余接口正常——所以它看起来像"某个功能坏了"而不是"混淆炸了"。
     修法 = 整包 keep（`proguard-rules.pro` 里 `-keep class edu.jxslu.schedule.data.qiekj.**`）。
     定位手法：release 临时加 `-printusage`，报告里**没有冒号的行**就是被整类删除的类；
     DataStore/课表那几条 JSON 链是编译期 serializer，不受影响——**别只测它们就以为序列化没事**；
  3. 冒烟要覆盖「开了混淆才走到的分支」：`-printusage/-printmapping` 只在本地临时加（用完删），
     它们会把路径写进仓库文件。
- **不再需要 `WRITE_SECURE_SETTINGS`**（2026-09-23 起）：快趣出行的「助手通道」
  （改写系统 `Settings.Secure.assistant` + 反射 `launchAssist` 直达未导出的首页）连同
  `KILL_BACKGROUND_PROCESSES` 权限一起删了，因为内置单车地图（`ui/ebike/BikeMapScreen.kt`）
  已经承担「看车在哪」。现在「打开快趣出行」只剩桌面启动意图一级，打开的是启动页。
  manifest 里保留 `com.kvcoo.go` 的 `queries` 声明仍是必须的，否则包可见性会让
  `getLaunchIntentForPackage` 对已装应用也返回 null。**别把助手通道当漏项加回来**。
- 测试结论从 `app/build/test-results/testDebugUnitTest/*.xml` 汇总（Gradle 成功时不打印用例数）；
  读 XML 用 `-Encoding UTF8`，否则中文断言消息乱码。
- 换机/重装后若 wrapper 重复下载：把 Gradle 8.10.2 解压版拷进
  `~/.gradle/wrapper/dists/gradle-8.10.2-bin/<distributionUrl 的 MD5-base36>/`，
  补一个空的 `gradle-8.10.2-bin.zip.ok`、删掉 `.part`。**不要**改 `distributionUrl`（哈希变则缓存对不上）。

单测覆盖：`ScheduleCalculatorTest`、`TimeSlotRulesTest`、`TimeSlotScheduleTest`（作息不变量）、
`WeekGridLayoutTest`（网格几何）、`QiangzhiScheduleParserTest`、`SyjxScheduleParserTest`、
`OneClickImportTest`（一键导入：页面形态判定/合成/警示文案/脏字段容错）、
`ImportJsonShapeTest`、`TodayStateTest`、`ParseWeeksInputTest`、`QiekjSignTest`、
`CourseTweakTest`（调课规划：拆分/覆盖/交换/同格去重）、`TodayBoundaryTest`（小组件边界闹钟时刻）、
`WidgetModelTest`（小组件：尺寸分档/行数预算/**明日接棒**/周网格列序与去重叠/旧 JSON 兼容）、
`ExamMapperTest`（考试→课条目映射，含历史学期估算）、
`ExamScheduleParserTest` / `ScoreParserTest`（注入 fetch JSON 解析）、`ScoreCalculatorTest`（学期/学年汇总）、
`ScoreGroupsTest`（成绩学年分组与年级标签）、
`ShortcutsTest`（快捷方式：拉起口径/表单校验/预设表/JSON 兜底/列表操作）、
`ScheduleDetectTest`（调课检测三方合并：归因/冲突/调课不误报/序列化 roundtrip）、
`JwHttpSessionTest`（检测登录链路：重定向解析参数顺序、IPv4 优先 DNS）、
`EbikeQrTest`（共享单车出码：URL 拼装/车号校验/BitMatrix 参数/最近车号序列化）、
`EbikeFreeRideTest`（免费时长提醒：提醒点/下一个未过点/迟到窗口/去重键带起点/通知 id 不撞号）、
`WechatRentNoticeTest`（精确倒计时：只认微信包名/关键词命中先享后付/窗口两端与越界）、
`YktKeyboardTest`（校园卡键盘：字形 MD5 表/双射硬校验/密文构造/协议自检）、
`YktModelsTest`（一卡通响应解析：BOM 剥离/错误码/CARD 账户提取）、
`MarkdownParserTest`（Markdown 子集：块/行内/嵌套/未闭合按字面回退/中文数字混排）、
`MarkdownEditTest`（编辑器：列表续行全分支/选区包裹/`$` 自动配对/图片插入）、
`MathTexTest`（LaTeX 子集：支持清单逐条解析/排版几何/超范围回退 null）、
`NoteExcerptTest`（笔记摘要提取 + 正文 img 引用收集与移除）、
`HomeworkCenterTest`（作业排序：逾期→今天→未来→无截止 / 汇总 / 截止文案 /
`homeworkDisplayTitle` 摘要剥离：前缀/包边/空行/兜底）、
`HomeworkReminderTest`（作业提醒点与有效期窗口 / 越窗跳过 / 去重键）、
`CourseRemarkTest`（课程备注搬运：mergeKey 匹配/kid 区分/新行不覆盖/多行同 key）、
`ScheduleExporterTest`（日历/CSV 事件展开）、`ReminderPlannerTest`（提醒时刻与有效期窗口）、
`CalendarSyncDefaultsTest`（日历提醒档位表）、`TimetablePrefsDefaultsTest`（显示偏好默认值契约）、
`GridFontDecouplingTest` / `GridFontScaleTest`（课表字号解耦与收敛）、
`PanelSnapTest`（面板高度吸附）、`CompactPositionTest`（地点压缩）、
`SubpageStackTest`（二级页离开位置：链增删/重建不重复/活窗口门控）、
`JsStringDecodeTest`（evaluateJavascript 返回值解码）、`JwImportDiagnosisTest`（导入失败诊断契约）、
`QiekjModelsTest`（胖乖响应包脏数据容错）、`YktPayCodeTest`（付款码矩阵参数）、
`YktRechargeSignTest`（充值下单签名）、`YktTurnoverSyncerTest`（流水增量同步纯逻辑）
、`ScheduleBackgroundTest`（背景图：默认值/模糊档位到解码尺寸/文件名白名单）
、`XgUrlsTest`（学工：域判定不退化成子串匹配/状态条三档/统一认证入口钉子）
、`BikeNearbyTest`（附近单车：响应容错/聚簇/距离/状态推导）、
`KqcxBikeClientTest`（失败分类：超时与网络不可达不能混）、
`Gcj02Test`（WGS84→GCJ-02：境外不偏移/境内量级/相对距离不变）、
`PowerModelsTest`（电费响应解析：项目/读数/流水 + 500 与 401 外壳 + 剩余电量键回退）、
`LifeFeedTest`（一卡通与电费流水混排：排序/限量/同刻稳定/解析失败沉底）、
`DisplayPrefsDefaultsTest`（生活页默认开 + 既有开关默认值契约）、
`StartPageTest`（启动页：显示名/选项顺序/生活页关掉时不列/落回今日/脏值回退）、
`PowerClientUrlTest`（缴费页/账单页深链形态与 feeitemid 钉子）、
`BalanceAlertTest`（余额提醒：档位表/夹取/下标/文案、电费元换算三态、阈值边界、每日闸门）
、`TranscriptParsingTest`（盖章成绩单：应答解析与脏数据容错/学期归并/分页换算/魔数/失败归类/表单字段/文件命名）
、`TranscriptClientTest`（导出编排：翻页收学期/MAX_PAGES 兜底/令牌回传/三条失败路径/网络错分类）
、`TranscriptHistoryTest`（最近导出：`.part` 半成品不进列表/标签反推/同秒稳定排序/保留 10 份裁边/文案/文件名越界防护）
等 72 个测试类（761 个用例，2026-09-24 现数）。

行为约定（改之前先读）：
- 教务页星期只能从课程所在 `<td>` 的**列序**推（第 0 列是节次标签）。`li.qz-hasCourse-N` **几乎恒为 1**（实测 33 处 `-1`、2 处 `-3`），不能当星期来源。
- **课表导入只有一个入口**：`ui/jwvw/JwImportScreen.kt` 的「一键导入课表」依次打开学期理论课表页与
  实验课表页，合成一批后弹一次识别结果（DESIGN §4.4.2）。四条别改坏：① 必须等 `PageLoadGate`
  放行再注入抽取脚本（`loadUrl` 是异步的，不等就抽到旧页面的 DOM），放行只认片段
  `xskb_list.do` / `syjx/toXskb`（两页 URL 都含 `xskb`）；② `reportFailure` 里
  **自动重试分支之后**才放行 false；③ **实验课表 0 条不是失败**（前期学期本来就没实验课），
  靠 `ExtractMeta` 区分「这张表没课」与「拿到的不是这张表」——理论看 `cells>0`（`kbDataTd` 个数）、
  实验看 `container`，把 `SyjxScheduleParser.EXTRACT_JS` 找不到容器改回 `ok:false` 会让两种情形
  重新混在一起；④ 实验页 URL 跟着理论页的学期号走（`JwUrls.labScheduleUrl` + `TERM_PATTERN`），
  否则两页默认学期不同时合并出来的是跨学期课表。
- 课程配色不按课程名哈希取（12 桶内必然撞色），走 `ScheduleCalculator.colorIndexesBySortedName` / `nextColorIndex`。
- 作息表结构版本存在 DataStore（`DisplayPrefsStore.slotSchemaVersion`）；改作息要同时调 `DefaultData.SLOT_SCHEMA_VERSION` 并给迁移。
- 课程时间**只有一条口径**：`ScheduleCalculator.courseStartMinutes` / `courseEndMinutes`（自定义时间课以 custom 字段为准）。
  排序、行内时刻、倒计时、进度条、`coursePhase`/`nextCourse`/`dayLastEndMinutes` 全部走它，不要再自己写 `isCustomTime` 分支。
- 今日页结构：**焦点卡（正在上/下一节）+ 单时间轴**；焦点课从列表里剔除（`TodayUiState.listCourses`），
  同一节课不得两处出现；节次号只在焦点卡出现一次。改版前先读 DESIGN §3.3。
- 可见星期序列以 `ScheduleCalculator.visibleDays` 为唯一来源、`columnOf` 取列下标；
  不要用 `day - 1` 当列号（隐藏周六但显示周日时会错位）。
- 今日页底部固定区（快捷方式网格 → **快趣出行码整行卡** → 开水卡）是 `TodayBottomDock`，
  **钉在滚动区下方**、不进 `LazyColumn`；三态（加载/空/有课）共用同一份，别只改一处。
  **水宝宝一卡通卡已于 2026-09-24 自今日页移除**（能力收进生活页，DESIGN §3.13）——
  别按旧描述把它加回来；出行卡右侧「附近单车 ›」直达 `EBIKE_MAP`，右侧动作文本走
  `CardSideActionText`（与开水卡右侧余额同一组件）。整块内容可折叠（把手「江水生活」，
  展开态存 `DisplayPrefs.todayDockExpanded`，默认展开），**折叠动画只做高度、锚点必须选 Top**
  ——锚 Bottom 或再叠一层 slide 都会变形，两个失败版本记在 DESIGN §3.3，别重复试。
  改版前先读 DESIGN §3.3。
- **含输入框的弹层一律用 `ui/common/SheetDismissIme.kt` 的 `ImeAwareModalBottomSheet`**
  （**不要自己拼 `ModalBottomSheet` + `rememberModalBottomSheetState`**：`skipPartiallyExpanded = true`
  与退场时序都收在它里面，2026-09-24 两次修订后的唯一口径）。
  `skipPartiallyExpanded` 的根因（2026-09-24 真机定位「充值弹层下一步被键盘挡住」；**别再往
  insets 方向查**）：键盘弹起后弹层可用高度腰斩，内容高于一半时 M3 会造出
  `PartiallyExpanded = fullHeight/2` 锚点，并在锚点更新时把 target 从 Expanded **改判**成
  PartiallyExpanded → 弹层停在半高，最底部的按钮被键盘盖住。Redmi K70 实测：可用高度
  2400→1604px、内容 986px → 停在 802px 而不是 Expanded 的 618px，差 184px 正好盖住 126px
  高的「下一步」；手动上划 = 拖回 Expanded，所以表现是「划一下才看得见」。跳过该锚点后只剩
  Hidden/Expanded，键盘弹起时 target 保持 Expanded（实测 618px，按钮落在键盘上缘之上）。
  **内容固定不可滚**（用户拍板）：勿加 `verticalScroll` 再滚到底——首次点击时 maxValue
  未更新会滚不到位，滚动方案已弃用。**也不要自己垫键盘高度**：M3 已经把弹层底边
  （`Box(fillMaxSize().imePadding())`）与内容底（`contentWindowInsets = safeDrawing.only(Bottom)`）
  垫到键盘上缘，多垫一份会把内容挤出可视区——旧 `ui/common/ImeSheetGuard.kt` 就是这么错的，
  已删。
  **退场与键盘必须分两段、不能并行收**（2026-09-24 用户二次反馈「点弹窗其余地方 → 先键盘
  收回、弹窗延迟收回、收回动画诡异」）：M3 的退场是一条 `tween(300ms)`，锚点由**容器高度**
  算出（父 Box 是 `imePadding()`，键盘一收 1604→2400px）——并行收键盘时键盘动画每帧改高度
  → 每帧 `updateAnchors()` → 动画中被**取消重启**（foundation `restartable{}`），而 tween
  每次重启都从缓动曲线 0 点重新计时、不吸收初速度，终点每帧又下移 → 弹层每帧只推进剩余距离
  的不到 1%，几乎停在原地，键盘收完才一口气滑完。现在由
  `confirmValueChange`（M3 询问「能不能去 Hidden」的钩子，点遮罩 `animateToDismiss` 与下滑
  `settle` 都走它）在键盘还起着时**否决 Hidden**，宿主先交还焦点收键盘（弹层跟着键盘逐帧
  下移），`WindowInsets.ime` 归零（逐帧回调，收到 0 = 键盘动画结束）后再 `hide()`；超时 450ms
  兜底。宿主**必须组合在弹层内容里**：写在 `ModalBottomSheet { … }` 外面拿到的是 Activity
  窗口的 `LocalFocusManager` / `LocalSoftwareKeyboardController` / insets，收不动弹窗里的
  键盘（第一版实测无效）。校园卡充值、电费充值、快捷方式表单三个弹层已接；新弹层照抄
  `ImeAwareModalBottomSheet`。
- 卡片观感**只有一处定义**：`ui/common/AppCard.kt` 的 `AppCard` / `AppCardRow`
  （14dp 圆角 + 1dp `outlineVariant` 描边 + surface 底）。新卡片一律走它，
  **不要**再私写 `RoundedCornerShape` + `border`（2026-09-22 之前 12dp/14dp 两套并存）；
  点击涟漪与触感由 `AppCard` 统一给，调用方不要在外面再包一层 `clickable`。
  区块标题同理走 `ui/common/SectionHeader.kt`（「今天还有 N 节」「明天 · 周二」
  与笔记/作业库的「最近更新」「按课程」共用一套规格）。
- 一次性消息**只有一条通道**：页面 Scaffold 的 `snackbarHost = { AppSnackbarHost(snackbar) }`
  （`ui/common/AppNotice.kt`）。语气用 `NoticeTone` 四档，视觉规格见 DESIGN §3.2；
  **禁止**新增 `android.widget.Toast`（系统黑框，与 App 其余浮层两套观感）。
- `ModalBottomSheet` / `AlertDialog` 是**比页面高一层的独立窗口**：页面级提示在它打开时必然被盖住。
  提示要落在弹层里就用 `InlineNoticeRow`（或先关弹层再提示），**不要**指望 Snackbar 穿透；
  加弹层内的异步流程前先确认结果会显示在哪个窗口。
- 课表页背景图（DESIGN §4.21/§4.22）由 **JuwApp 铺在外层 Scaffold 之下**，按
  `currentRoute == Routes.WEEK` 只画课表 Tab，WeekScreen 的 Scaffold 底色设透明让它透出来。
  **不要**把它挪回课表页内部：Scaffold 的 Surface 会 clip 内容，图铺不到状态栏与底栏后面
  （2026-09-22 实测踩过一次）。状态栏与底栏的 inset 归属同样别改回去：外层 Scaffold 的
  `contentWindowInsets` 必须是 0，顶部由各页顶栏自取 `WindowInsets.statusBars`。
  背景图**不进** `notes_img/`（附件清扫会把它当孤儿删掉），只进 `filesDir/schedule_bg/`，
  同时只留一张；选图后的顺序固定为「写新文件 → 写偏好 → 删旧文件」。
- 悬浮导航栏（DESIGN §4.22）是**真悬浮**：`NavHost` 不吃 Scaffold 的底栏 padding，页面内容铺到
  窗口底、被胶囊压住；页面靠 `ui/common/BottomBarClearance.kt` 的 `LocalBottomBarClearance`
  把滚动内容的最后一项顶出胶囊。新页面加底部内容时**要带上这个净空**，否则最后一项会被胶囊压住；
  已经带上的有课表网格、今日页 dock、我的页列表、课表页显示设置面板，提示通道 `AppSnackbarHost`
  在组件内部统一带（调用点不用管）。
  胶囊底色用 `surfaceContainer`（`surface` 与页面底色同色，会读成一条白底栏）；
  选中态只改图标与文字颜色，不加底色块。
- **启动页**（2026-09-24，DESIGN §3.3）：我的 → 通用 → 启动页，默认今日，选项 = 底栏
  Tab 集（今日/课表/生活/我的），存 `DisplayPrefs.startPage`（键 `start_page`）。
  三条别改坏：① **生活页关掉时「生活」不出现在选项里**，且存着的旧值落回今日页
  （`domain/StartPage.kt` 的 `visiblePages` / `effectivePage`，设置页选中态与
  `MainActivity.resolveStartRouteBlocking` 共用同一口径；不把存储值改写成今日——
  生活页开回来旧选择要还在）；② **重启生效**，调用点 `remember { resolveStartRouteBlocking() }`
  按窗口读死一次（比悬浮导航栏的进程级 `floatingNavBarEffective` 细一档），**不要**让
  `startDestination` 跟 Flow 变——Compose Navigation 会按
  `remember(route, startDestination, builder)` 重建整张导航图，用户被弹回起点；
  ③ `StartPage` 只带显示名，`StartPage → Routes.*` 的映射留在
  `MainActivity`（路由字符串的唯一来源仍是 `Routes`，别在 domain 里复制字面量）。
- **从桌面图标回到 App 要落在离开时那一页**（2026-09-23，DESIGN §3.1）：`MainActivity` 保持
  standard + `alwaysRetainTaskState="true"`，**不要**改回 `singleTask`（它 clearTop，会把二级页
  销毁，用户只能落到今日页）。桌面点击时系统会多压一个实例，由 `MainActivity.onCreate` 那条
  「`!isTaskRoot()` + `action=MAIN` + `category=LAUNCHER` → `finish()`」让它不上屏就退出，
  下面那套窗口原样露出——二级页**不重建**，这才是窗口保活。
  `SubpageStack` 只是兜底（某 ROM 真走 clearTop、页面已被销毁时才按链重建），别把它当主路径，
  也**别把记账挂到 `onDestroy`**：clearTop 不走 `finish()`，挂上去记录会被一起清掉。
  通知的 `PendingIntent` 必须指向 MainActivity 跳板（`subpageLaunchIntent`）而不是
  `SubpageActivity`，且带 `NEW_TASK|CLEAR_TASK`；小组件点击同理——`SINGLE_TOP|CLEAR_TOP`
  在 standard 上匹配不上显式 intent，清不掉二级页。
- **二级页过渡分两套**（2026-09-23，DESIGN §3.1，实现在 `WindowTransitions.kt`）：
  API 34+ 由窗口自己 `overrideActivityTransition` 声明（系统才会把返回手势进度交给它，
  也就是预测性返回的跟手预览），API 33 及以下才用 `overridePendingTransition`。
  **34+ 上调旧 API 会把跟手预览关掉**：它是已废弃 API，调了等于声明「未适配」，
  真机上表现为滑到一半毫无预览、松手才切页。manifest 的 `enableOnBackInvokedCallback`
  别改成 `false`（同一件事的声明）。
- 小组件是**单条目 + `SizeMode.Exact` 自适应**（2026-09-20 起，旧三档条目已删）：尺寸由
  `WidgetMetrics`（实测 dp）分档 Compact / List / Week，**不要再加按尺寸拆的 receiver 或
  `widget_info_*`**（旧版三条目内容重复，用户明确要求合并）。改渲染前先读 DESIGN §3.6；
  刷新机制（边界闹钟 + WorkManager + 冷启动）与「写状态 + `update()`」双步**不许动**
  （理由见 `ScheduleWidget` 类 KDoc：Glance 会话的两条硬约束是不可绕过的）。
  边界闹钟 2026-09-24 起用 **`setAlarmClock` 精确闹钟**（此前 `setAndAllowWhileIdle` 被
  Redmi K70 推迟几分钟，用户报「上下课了小组件还不换」）——**别改回**非精确闹钟；
  无特殊权限，代价仅是触发时状态栏短暂显示闹钟图标（上课提醒同一手法）。
- 小组件周网格的列取 `ScheduleCalculator.visibleDays`，与课表页同一口径（不要 `day - 1`）；
  高亮列规则是「今天还有课 → 今天，否则明天」，改这条前先读 DESIGN §3.6 的「明日接棒」。
- 澎湃OS / MIUI 的负一屏只收录「小米小部件」（需开放平台审核），**原生小组件进不去**；
  设置页已给出替代路径（负一屏搜索 / 日历同步），不要把它当 bug 修（DESIGN §3.6「负一屏」）。
- 课程备注（DESIGN §4.3）属于**课程行**（同一门课的不同时段各写各的），存储在 `courses.remark`；
  覆盖导入（`replaceAllCourses`）与调课检测应用（`applyDetectGroups`）会重建课程行，
  **必须**经 `domain/courseRemarksCarriedOver` 按 `Course.mergeKey()` 把备注搬回来——
  少了这一步的表现是「导入一次备注全没了」。`mergeKey` 的唯一实现在 `domain/Models.kt`，
  不要再往仓库里加第二份（导入去重、导入统计与备注搬运共用它）。
- 笔记·课件与作业（DESIGN §3.11/§4.20）归属键 = **课程名原样字符串**，不带 courseId、不带
  timetableId：课程行 id 在覆盖导入/调课/撤销里会被重建，绑 id 必丢数据；换课表后旧内容仍应可查。
  改归属口径先读 §4.20「归属」的取舍段。
- Markdown 渲染是**自研子集**（`domain/Markdown.kt` + `ui/common/MarkdownView.kt`）、公式是
  **自研 TeX 子集**（`domain/MathTex.kt` + `ui/common/MathTex` 绘制）：超范围语法**原样显示源码**，
  **不许**为了"好看"引入第三方渲染/公式库（体积与 `--offline` 构建是硬约束）。支持清单见 DESIGN §4.20。
- 编辑器自动补全（列表续行 / `$` 补全 / 选区包裹）**只有一条口径**：`domain/MarkdownEdit.kt`
  纯函数，UI 只负责把结果应用回 `TextFieldValue`；不要在 Composable 里另写续行判断。
- 笔记/作业的图片只走系统 Photo Picker（`PickVisualMedia`）+ 应用私有目录，**不申请相册权限**；
  正文引用形如 `![](img:文件名)`，删引用要同时清理文件（`data/repo/AttachmentStore.kt`）。
- 附近单车地图（DESIGN §3.9/§4.23）的坐标基准是 **GCJ-02**，与高德栅格瓦片同一基准：
  车辆坐标**直接画，不要转换**；唯一要转的是手机定位（WGS84 → GCJ-02），
  唯一实现在 `domain/Gcj02.kt`，漏转或多转一次都会偏出约 500 米。
  刷新只由用户动作驱动（进页 / 拖动停稳 / 点刷新 / 定位成功），**不要加后台轮询**
  ——那是第三方接口，不是自家的。结果只留内存不落盘。
  定位只走平台 `LocationManager`（`ui/ebike/BikeLocator.kt`），不引 Play Services 融合定位。
  `LocationListener` 的四个回调都要写全：少写一个在 26~29 的设备上是 `AbstractMethodError`，
  编译期看不出来。
  两条容易改坏的口径：**距离的参照点**（有定位按用户位置、否则按地图中心，而且 UI 必须
  把参照点写出来，别让「473 米」在拖动后悄悄换意思）；**镜头静默区**
  （`CameraSuppressor` 吃掉程序性移动 30 米内的中心回调，否则「点分组→移动地图」会马上
  触发一次重查，把用户刚展开的列表换掉）。改这两处前先看 DESIGN §3.9 的对应行。
  车号回传只有一条通道：地图页 `setResult(EXTRA_PICKED_CAR_NUM)`，**别改回进程级单例**
  （单例会被任何一个还活着的出码页实例抢走，用户眼前那页空手而归）。`MainActivity` 是
  `launchMode="singleTask"`，**别改回标准启动**：退到后台再被拉起时系统会在栈上再压一个
  实例，用户看到「今日页」而底下还压着二级页，按返回又回去了。面板高度存在
  `BikeMapUiState.panelHeightDp`（VM 状态，不是页面局部 `remember`），拖动回调传增量。
  这三条的理由都写在 DESIGN §3.9 的表里。
  singleTask 的 clearTop 会拆掉二级页，**「回来还在原来那页」靠 `SubpageStack`**：
  二级页在 `onResume` 记账、用户主动关窗时在 `onFinish` 摘除（不挂 `onDestroy`，
  clearTop 不走 `finish()`），`MainActivity.onNewIntent` 按记录重新打开。别把它当冗余删掉。
- 车号口径（DESIGN §3.9）：输入框接受「1~3 位尾部」与「6~12 位完整车号」两种形态，
  唯一实现在 `EbikeQr.resolveCarNum`，`bikeUrl` 只认完整车号。地图选中的车走完整车号
  那条路（别的车队前缀是 `300000…`，靠尾部三位拼不出正确链接），
  **不要再按 `EbikeQr.TEMPLATE + 尾部` 拼 URL**。
- 免费时长提醒（DESIGN §3.9）：**走 App 通知 + 前台服务常驻倒计时，不写系统日历**
  （2026-09-24 用户拍板改回；2026-09-23 那次「改系统日历」已作废）。
  两个提醒点（提前量点、免费结束）由 `AlarmManager.setAlarmClock` **精确**触发：
  系统级闹钟，到点唤醒设备、不受 Doze/省电推迟、**不需要任何特殊权限**
  （与上课提醒同一手法，requestCode 4003 与上课的 4002 错开）。
  **通知栏常驻倒计时**由 `EbikeFreeRideService` 承载（`specialUse` 类型；
  `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`）。剩余时间**由服务每秒
  重发一次通知文案**（`EbikeFreeRide.countdownText`，每秒一次是系统允许的上限），
  但**只在亮屏时刷**：灭屏一个 notify 都不发（协程阻塞等屏幕亮，点亮补刷一次）——
  这是省电的主要来源，别改回「不分屏幕状态一律每秒刷」；
  **不要**改用系统 chronometer（`setUsesChronometer` / `setChronometerCountDown`）——
  2026-09-24 在 Redmi K70 / 澎湃OS 实测：面板静止时系统不主动重绘，数字不动，
  用户报「通知栏没有秒」。到点提醒**不要**改由服务里的协程 `delay` 负责——
  屏幕关闭后 CPU 挂起，会迟到几分钟；服务只管展示与保活。
  channel 用新 id（`ebike_free_ride_countdown` / `ebike_free_ride_alert_v2`），提醒那个
  **带震动**（双震 pattern）且 category 用 `EVENT`（对齐上课提醒）；**不要复用首版的
  `ebike_free_ride`**——已存在 channel 的 importance 与震动都改不动，且「删掉重建」也无效
  （delete 是异步的，紧接着 create 会被当成更新，实测震动没生效），只能换 id；
  通知 id 1000/1005/1006，与上课（1001/1004）、作业（1002/1003）错开。
  迟到窗口（用户拍板）：提前量那条只要免费时段没结束就补发，结束那条结束后
  5 分钟内仍发（`EbikeFreeRide.END_WINDOW_MS`）；去重键带计时起点，落
  `ebike_free_notified_keys`。`EbikeFreeRideCheckWorker` 的类名也别改
  （老版本排下的周期任务按类名实例化，`KEEP` 策略又不会重排，兜底会永久消失），
  但它的**周期任务只在计时期间排**（15 分钟 = WorkManager 最小周期，`cancelAll` 里撤）：
  别改回「冷启动无条件常排」——那会让没骑车的用户每 15 分钟被冷启动一次进程。
  系统日历那条链路（`data/calendar/EbikeCalendarEvents.kt`、`ebikeFreeEventId`、
  `水贝贝骑行提醒` 标记、日历权限申请）已整套删除，**别加回来**。
- 「精确倒计时」（DESIGN §3.9，2026-09-24，**默认关**）：`ui/ebike/WechatRentListener.kt`
  （`NotificationListenerService`）识别微信的租车成功通知（关键词「先享后付」），
  把计时起点从「点扫一扫」校准到真正开始计费那一刻。**它要「通知使用权」**——读用户全部
  通知，只能用户去系统设置手动勾选（应用没有 API 能申请），所以默认关、开关旁给未授权提示；
  匹配与窗口口径在 `domain/WechatRentNotice.kt`（四道闸见 DESIGN §3.9）。
  **隐私红线**：只读包名与文本用于当次匹配，**不落盘、不上传**；命中原文的日志只在
  `BuildConfig.DEBUG` 下打（抓真实文案用），**release 一行都不许打**。
  常驻倒计时的起停判据是**起点是否变化**（`EbikeFreeRideService.start`）：起点变了就重新
  `onStartCommand` 重建 tick，起点没变才跳过。**别退回「服务在跑就跳过」**——上一轮没结束
  就换车时 tick 循环握着旧起点，倒计时不会重置（2026-09-24 用户报的 bug，当时是我加的
   `running` 幂等标记惹的）。换车（`startRide`）还要顺手清掉上一轮挂在通知栏的提醒与校准标记。
- **导出盖章成绩单走的是签章管理系统，不是强智教务**（DESIGN §4.25）：
  CAS service = `http://jwxyxx.juwp.edu.cn/ptwork/cas`，落地拿 `sid`，再
  `POST /ptwork/DzqzController/ddqzcjList` 取 `pagePri`、`POST …/printStartCj` 回传它拿 PDF。
  四条不许按直觉改的口径：**`dysj`（pagePri）是权威条件、`xnxq` 被服务端忽略**；
  列表分页不影响出单（token 编码查询条件而非当页数据）；`limit` 无效、固定 15 行一页；
  **令牌存在不等于有数据**——无成绩学期照样给 token，解不开时服务端返回**空白却带章**的模板 PDF，
  所以出单前必须用 `total>0` 当闸门，响应必须过 `%PDF` 魔数。
  会话只从 WebView 的 `CookieManager` 现取（不存账号密码，与「不要写死密码」同一条纪律）；
  学期清单从接口取，**不要解析页面下拉**——「后台首页」那份模板的下拉最高只到 2022-2023-2
  且 37 个旧学期重复，是坏的。成绩单 PDF 的章是**注释 + 数字签名**（`/FT /Sig`，Rect 压在
  「学校盖章：」上），PDFium 系渲染器（含 pypdfium2）不画注释，用它截图自检会误判「没盖章」。
  签章系统只有 HTTP 明文，成绩单与 CAS 票据都明文回传，这句实话要留在导出页上。
  「最近导出」页（`SubpageScreen.TRANSCRIPTS`）的列表**就是 `filesDir/transcripts/` 目录本身**，
  不要给它加 Room 表或清单文件：文件名里已经带学期标签与导出时刻，两套真相迟早对不上。
  判定顺序是「先 `.part` 再 `.pdf`」（`x.pdf.part` 同时满足两个后缀），否则崩溃留下的半成品
  会被当成一条记录列出来；打开/分享前必须用 `TranscriptStore.existingFile` 复核（新导出会触发
  保留策略删旧的，列表那一屏可能已经过期）。

- **宿舍报修是 WebView，不是原生表单**（2026-09-24，DESIGN §3.15 / §4.26）：
  `DormRepairActivity`（第三个「因为窗口里有统一认证表单而锁竖屏」的窗口，前两个是教务导入
  与成绩单导出）进页打开 `XgUrls.SSO_LOGIN`（`/sfrz/login343962`）→ 302 到 `eapp2` 的 CAS。
  学工与教务**共用同一套统一身份认证**，所以教务导入登录过一次这边就免登，App 不存密码。
  **别换成**超星的 `/passport/mlogin`（手机号 + 学习通密码，是另一套账号，学校没配它）；
  **也别去复刻** `/office/...` 的表单提交：请求里那批 `pageEnc` / `traceId` / `nodeUniqueId`
  由服务端每次下发，复刻出来的实现必然随官方改版失效。附件上传靠
  `WebChromeClient.onShowFileChooser`（漏了它 = 点上传没反应），状态条按域分档的判据
  唯一实现在 `data/xg/XgUrls.kt`。

## 装真机

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
# MIUI 可能弹「USB 安装」需在手机上允许
```

debug 与 release 是**两个独立应用**（包名分别 `edu.jxslu.schedule.debug` / `edu.jxslu.schedule`，
桌面名「水贝贝 Debug」/「水贝贝」），可同时安装、数据各一份。改动冲突时改 `app/src/debug/res/values/strings.xml`（仅覆盖 `app_name`）。

启动 debug 包**必须写全限定名**——短式 `am start -n <applicationId>/.MainActivity` 会按 applicationId
补前缀、解析成 `edu.jxslu.schedule.debug.MainActivity` 并报 `Error type 3 ... does not exist`
（manifest 里声明的是源码包名，不含后缀）：

```powershell
adb shell am start -n edu.jxslu.schedule.debug/edu.jxslu.schedule.MainActivity
```

**设备列表看不到手机时**：先确认是不是根本没连。重跑 `adb connect` 无效、排除僵尸 adb /
小米妙享抢接口后，直接提醒用户插线或确认无线调试已开，不要在环境侧反复排查空转。

无线调试（手机重启或 `adb usb` 后失效，IP 要现取勿记死）：
`adb -s <serial> tcpip 5555` → `adb shell ip route` 取 IP（接口是 **wlan2**，不是 wlan0）→ `adb connect <ip>:5555`。

## 爬虫脚本（scripts/）

正式脚本 7 个；历史一次性探测脚本在 `scripts/_archive/`（**勿依赖**，仅留档；该目录不入公开仓库）。

| 文件 | 作用 | 产出 |
|------|------|------|
| `jw_session.py` | 共享登录（CAS → 教务 SSO → 会话校验） | — |
| `fetch_courses.py` | 学期理论课表（`--term` 可选） | `scripts/out/courses.json` |
| `fetch_lab_courses.py` | 实验课表（实践实验 → 实验课表查询，`--term` 可选） | `scripts/out/lab_courses.json` |
| `fetch_exams.py` | 考试安排（`--term` 可选，缺省取教务当前学期；JSON 接口） | `scripts/out/exams.json` |
| `fetch_scores.py` | 课程成绩（`--term` 可选，缺省全部学期；JSON 接口） | `scripts/out/scores.json` |
| `fetch_power.py` | 寝室电费（新开普缴费平台 `charge.juwp.edu.cn`，**非教务**；`--history` / `--room 9A101`） | `scripts/out/power.json` |
| `fetch_transcript.py` | 教务处**盖章成绩单**（金格签章系统 `jwxyxx.juwp.edu.cn`，**非强智教务**；`--list` / `--term` 可多个 / `--out`） | `scripts/out/transcript_<标签>.pdf` |

所有脚本输出 JSON 顶层 `term` = **实际爬到的学期**（如 `2026-2027-1`），App 导入确认弹窗据此展示；
带 `--term` 时脚本会校验「请求学期 = 教务返回学期」，不一致直接报错而不是静默爬错学期。

```powershell
.\.venv-scraper\Scripts\python.exe scripts\fetch_courses.py     # 可加 --term 2025-2026-2
.\.venv-scraper\Scripts\python.exe scripts\fetch_lab_courses.py
.\.venv-scraper\Scripts\python.exe scripts\fetch_exams.py
.\.venv-scraper\Scripts\python.exe scripts\fetch_scores.py
.\.venv-scraper\Scripts\python.exe scripts\fetch_power.py --history
.\.venv-scraper\Scripts\python.exe scripts\fetch_transcript.py --list   # 出单：--term 2025-2026-2
```

- **寝室电费是另一套系统**（新开普「移动服务平台」缴费，`charge.juwp.edu.cn`，与教务无关）：
  登录 = 学号 + **缴费平台查询密码**（`credentials.local.json` 的 `powerPassword`），
  **与教务 `password` 不通用**——实测教务密码登录返回 `{"error":"unauthorized"}`。
  平台只挂了一个项目 `feeitemid=181`「房间电费」（0.62 元/度），读数取 `POST /charge/feeitem/getThirdData`
  （`type=IEC`）的 `map.showData`（中文键，如「当前剩余电量」）；该接口**参数少一个就回
  `code=500「未知异常，请联系管理员」`**（`feeitemid`/`type`/`level`/场景三键缺一不可）。
  网站里带 `token=` 的分享链接是**易失**的，脚本按学号+查询密码现登，别复用链接里的 token。

- **Session 必须 `trust_env = False`**：本机 shell 注入了 `HTTP_PROXY/HTTPS_PROXY`（IDE 本地代理），
  requests 默认走代理会让教务 SSO 落点返回 404、主页退回「用户没有登录」，现象像"教务挂了"。
  统一用 `jw_session.new_session()`，不要自己 `requests.Session()`。
- 凭证在 `scripts/credentials.local.json`（已 gitignore），禁止提交、禁止写进 App。
- 两个课表页结构**完全不同**：理论课表按课程所在 `<td>` 列序推星期；实验课表是「周次 × 节次」两级纵轴，
  周次挂在**行分组**上、且同门课按周拆成多块需聚合。解析逻辑**不可互相复用**。
- 考试安排/成绩走**不带 .do 的 layui JSON 接口**（`xsks/xsksap_list`、`kscj/cjcx_list`，参数
  `xnxqid`/`kksj` + 分页 `pageNum/pageSize`）；带 .do 的同名地址返回「系统功能暂未开放」no-open 页，
  不要把「功能被校方关闭」误判成「暂无数据」。学期参数：课表页 `xnxq01id`，考试 `xnxqid`，成绩 `kksj`。
- 登录链路、DOM 规则、排错表、WebView 注入 JS：**`scripts/README.md`**（比 DESIGN 更细）。

## 架构（改代码前对齐）

```
MainActivity → 底栏今日/课表/生活/我的（生活页可关，默认开，DESIGN §3.13）+ 路由 jw_import；
               SubpageActivity 承载二级页（含成绩查询 SCORES、
               笔记/作业 7 个二级页 NOTES·NOTES_COURSE·NOTE_DETAIL·HOMEWORK·HOMEWORK_COURSE·
               HOMEWORK_DETAIL·HOMEWORK_TODO，DESIGN §3.11）
domain/          Course·TimeSlot·SemesterConfig·ScheduleCalculator·ExamMapper·Score（纯逻辑，可 JVM 测）
                 + Note·Homework·Markdown·MarkdownEdit·MarkdownImages·MathTex·HomeworkCenter（§4.20）
                 + EbikeQr·EbikeFreeRide·BikeNearby（§3.9：出码车号口径、免费时长、附近车辆解析）
                 + Gcj02（WGS84 → GCJ-02，§4.23 唯一的坐标转换处）
                 + LifeFeed（一卡通与电费流水混排，§3.13）
data/local/      Room v8：courses / time_slots / semester_config / timetables / scores
                 / detect_baselines / detect_reports / ykt_turnovers / notes / homework
data/repo/       ScheduleRepository + JSON 导入校验；ScoreRepository（成绩按学期替换）
                 NoteRepository / HomeworkRepository / AttachmentStore（笔记作业图片，§4.20）
data/prefs/      DataStore 显示偏好（含 slotSchemaVersion）
data/jw/         JwUrls + QiangzhiScheduleParser（理论 xskb）+ SyjxScheduleParser（实验 syjx）
                 + ExamScheduleParser / ScoreParser（考试·成绩 = 同源 fetch JSON，非 DOM 解析）
data/qiekj/      胖乖生活 API（登录/开水/余额/订单）
data/ykt/        一卡通（新中新慧新e校）登录与付款码（DESIGN §4.19；凭证 ykt_credentials.xml
                 已排除备份；token 仅内存；无日志拦截器；8002/8003 验证码绝不重试）
data/kqcx/       快趣出行「附近车辆」接口（DESIGN §4.23；无鉴权、无凭证、只发坐标）
data/power/      寝室电费（新开普缴费平台 charge.juwp.edu.cn，DESIGN §4.24；凭证复用一卡通的
                 学号 + 查询密码；token 仅内存、无日志拦截器）
ui/today|week|life|me|water|campus|jwvw|score|timetable|common|theme|widget|ebike|notes|homework
Graph.kt         单例 Repository
JuwApplication   ensureDefaults（节次/学期；课表不预置）+ 小组件冷启动刷新
```

- 课表 JSON 字段对齐 DESIGN 4.3 / 拾光互通；解析层见 `scripts/fetch_courses.py` 与 `data/jw/`
- 教务：强智 `https://jiaowu.juwp.edu.cn:81/` → SSO service 必须是 `http://jiaowu.juwp.edu.cn/sso.jsp`（**不要带 :81/:8080**）→ 课表 `http://jiaowu.juwp.edu.cn:8080/jsxsd/xskb/xskb_list.do?viweType=0`
- 解析 HTML 用注入 JS 抽 `li.courselists-item`；Kotlin 侧 `html.parser` 思路，**lxml 会丢节点**（双 doctype）

## 硬性禁止

- 禁止刷积分、绕过付费、伪造官方身份
- 禁止把学号/密码/token 写进 git（`scripts/credentials.local.json`、`local.properties`、`release.jks`、`keystore.properties` 已 ignore）
- **本仓库为公开仓库**：写文档/注释/示例时不得出现真实学号、姓名、手机号、token；新增抓取产物目录前先确认 `.gitignore` 已覆盖
- 禁止未改 `DESIGN.md` 就改导航或课表领域模型
- 禁止 emoji 当功能图标；HugeIcons 查名用本地 JAR，勿猜
- 禁止把「能编译」当完成；禁止故意压制编译错误

## 胖乖（P4）

- 参考 `F:\light-life-v3.0`；Base `https://userapi.qiekj.com/`
- 只做：登录、开水、余额、订单；签到默认关；禁止刷积分
- 实现在 `data/qiekj/` + `ui/water/`；Token 走 EncryptedSharedPreferences，禁止进日志
- 一卡通付款码（DESIGN §3.10/§4.19）：密码字段 = 安全键盘密文（字形 MD5 表一次替换）+ `$1$` + uuid；
  **未知字形/非双射/样板自检不过 = 立即报错不猜**；登录密码仅数字（键盘只映射 0-9）；
  token 只存内存不落盘；付款码不进日志/剪贴板/相册；凭证交互照 `TweakDetectScreen`（开启先真实验证、关闭即清除）
  调用链（11 步顺序不可乱）见 DESIGN §4.10

## 生活页（一卡通 · 寝室电费，DESIGN §3.13/§4.24）

- 底栏第三项「生活」，开关 `DisplayPrefs.lifeTabEnabled` **默认开**（我的 → 通用 → 生活页）；
  关掉后底栏回到 3 项，页内关掉时自动退回今日页。**今日页的「水宝宝一卡通卡」已于
  2026-09-24 删除**（用户拍板，生活页承接付款码最短路径），别按旧描述加回来。
- **码不预取**：`PayCodeViewModel` 初值 `Idle`（占位条），点击才 `load()`，收起调 `collapse()`
  （丢码 + 回收位图 + 停消费检测）。展开期间 `FLAG_SECURE` + 亮度拉满，收起即恢复。
  付款码页与生活页共用这一份 VM，别再写第二套取码逻辑。
  取码骨架与成功态**同几何**（码位 `aspectRatio` 占死 + 按钮占位行），改布局两边同步改，
  否则取码完成时卡片跳动（2026-09-24 收口口径）。余额卡与电费卡靠
  `IntrinsicSize.Min` + 卡内 `weight` 空隙恒等高，改动别绕开它。
- **一处凭证**：电费登录 = 一卡通的学号 + 查询密码（`YktCredentialStore`，2026-09-23 实测
  两个平台同一密码）。一关了之：凭证清掉时电费卡同样显示「未开启凭证」。
- **读表参数缺一不可**：`feeitemid=181` / `type=IEC` / `level=3` / campus+building+room；
  少一个平台只回 `code=500「未知异常」`（HTTP 200），**业务码 401 也藏在 HTTP 200 里**，
  必须读 body 的 `code` 才能触发重登。
- **电费充值已 App 内完成**（2026-09-24 实测收口）：下单 = `POST /blade-pay/pay`
  `paystep=0`（`feeitemid=181 + tranamt + flag=choose`，签名 `PowerPaySign`）；
  支付 = `paystep=2 + paytype=ACCOUNT + paytypeid=59` 拿 `passwordMap` 乱序表，
  `PowerPayChallenge.cipherOf` 是密文换算唯一口径（用户数字 d → **d 在乱序表里的
  下标**；官方键盘第 i 个键显示 `table[i]` 但提交 `String(i)`，2026-09-24 读前端
  `app.7abec7aa…js` 修正——此前 `d → table[d]` 方向反了，正确密码也报「密码错误」），
  6 位消费密码 = **登录缴费平台用的那个 6 位密码**（2026-09-24 用户纠正：学校就一套
  6 位密码，不存在独立的「支付密码」）。测试单用完就 `deleteOrder`（JSON body），别留未支付单——堆积会让
  新下单 500。服务时间闸门等业务拒绝原样透传，不降级跳网页；深链 `#/pays?id=181`
  保留作兜底，无效路由会被前端打回首页，新增深链前先实测。
  三条一改就坏的钉子：**订单号只能用下单响应里那一个**（`paystep=2` 的 `orderid` 恒为
  null，别拿 `passwordMap` 的键——那是 uuid，发出去服务端回「订单不存在，请重新预定」）；
  **`ccctype[0].balance` 单位是元**（同一时刻一卡通 `accinfo[].balance=100` 分对照确认，
  按分渲染会把 1 元显示成 ¥0.01）；服务端拒绝含「订单不存在/已过期/已失效」时回金额步
  重新下单（`PowerPayModels.isOrderGone`），别让人在密码步反复重输。
- **余额提醒**（2026-09-24，DESIGN §3.13「余额提醒」）：设置项在「我的 → 校园卡」页
  （寝室电费 10–80 元 / 一卡通余额 10–50 元，步长 5）。口径单一来源 `domain/BalanceAlert.kt`：
  电费「元」= 剩余电量 × 单价（**唯一换算处**，生活页电费卡也走它，别再内联乘一次）、
  阈值是**严格小于**、档位表/夹取/文案都在这里。调度在 `ui/reminder/BalanceAlertReminder.kt`：
  每天一次 + 冷启动补查 + 设置变更后立即评估，闸门是「上次**成功**检查日期」
  （同一天每个来源最多一条；**取数失败不落日期**，当天还能补查）。
  四条不许动：**失败不重试**（防撞风控）、一卡通只算**正式卡余额**（不含电子账户）、
  `balance_alert_periodic` / `BalanceAlertCheckWorker` 的名字（`KEEP` 下改名 = 每日兜底永久消失）、
  通知 id/tag 与落点（电费 1005 / 一卡通 1006，`EXTRA_ROUTE=ROUTE_LIFE` 落生活页；
  生活页开关关掉时不带 extra 落今日页）。关凭证时两个开关一并回落（都靠那份凭证）。
  **PendingIntent requestCode（3005/3006）与通知 id 分离**：骑行提醒的通知 id 也是
  1005/1006，两边落点 intent 都指向 MainActivity 无 action，filterEquals 相同——
  requestCode 撞了会被 `FLAG_UPDATE_CURRENT` 改写落点（2026-09-24 修的真 bug）。
  **给通知加落点前先全局 grep 现有 requestCode**（上课 0、作业 1002/1003、
  骑行 2000/2005/2006、余额 3005/3006），撞了就是静默的点击错页。
- 一卡通设置页的学号输入框**明文回填**已保存的学号（2026-09-24 用户拍板）；
  改回空框 = 不显示，会被用户当成「凭证丢了」，别当隐私优化删掉。

## 沟通与 DoD

- 与用户中文交流；少形容词，多可验证结论
- 完成定义：功能可演示（真机/模拟器或写明阻塞）；能跑则跑 `assembleDebug` + `testDebugUnitTest`；未越权改无关模块

## 阶段状态（见 DESIGN.md §6 里程碑）

P1 脚手架 · P2 Room+UI · P3 我的页导入导出/学期 · P4 胖乖（已实现，待真机验证）·
P5 教务 WebView · P5b 实验课表导入 — **已完成**  
P6 打磨 — **进行中**（2026-09-21：笔记·课件与作业落地，含自研 Markdown/LaTeX 渲染与
作业截止提醒，见 DESIGN §3.11/§4.20；真机已验证 Room v6→v7 迁移与各新页面不崩，
图片编辑与提醒弹出需人工点验。2026-09-22：课表页自定义背景图，见 DESIGN §4.21，
选图与滑块调参需真机点验。2026-09-23：免费时长提醒由 App 通知改系统日历，
见 DESIGN §3.9，已在 Redmi K70 的小米日历验证事件与两条提醒落库
（**该口径已于 2026-09-24 反转**，见本段末）；同日修复
「进二级页后挂后台、从桌面图标回来落到今日页」的导航错乱，见 DESIGN §3.1，
已在 Redmi K70 验证：二级页实例 id 不变、录屏无今日页中间帧。2026-09-23（同日）：
生活页落地（底栏第 4 项 + 寝室电费），见 DESIGN §3.13/§4.24，脚本侧电费链路已实测、
App 侧 615 条单测全绿，Redmi K70 实测：底栏 4 项、电费读数 55.37 度（9A101）、
点占位条取码成功且展开期间 FLAG_SECURE 生效（截图为全黑）、收起后恢复、
生活页开关关掉后底栏回 3 项；**电费充值/缴费账单深链（跳浏览器）与消费流水页待人工点验**。2026-09-24：免费时长提醒由系统日历改回 App 通知（`setAlarmClock` 精确闹钟 + 前台服务
常驻倒计时），见 DESIGN §3.9，日历链路整套删除；真机（Redmi K70）已验证：
前台服务 `specialUse` 起得来、通知 id 1000 常驻、精确闹钟按「起点 + 提前量」落点、
通知文案每秒跳秒（**弃用系统 chronometer**：面板静止时不重绘，用户报「没有秒」）；
同日省电口径：灭屏不刷通知、周期兜底只在计时期间排、提醒 channel 加震动 + category 对齐上课提醒；
639 条单测全绿；同日修「上一轮倒计时没结束就换车、倒计时不重置」（服务起停判据改成
「起点是否变化」，见 DESIGN §3.9 与下方行为约定）；
**两个到点提醒（提前量 / 结束）待人工点验**）

## 仓库与发版

- 公开仓库：https://github.com/Inonvation/JUWP-Schedule （MIT）
- **不入库**（已 gitignore，本地保留）：`scripts/out/`（含真实学号/姓名/会话）、
  `scripts/_archive/`、`docs/`、`scripts/gen_week_layout_preview.py`、`release.jks`、`keystore.properties`
- 发版流程见 `.agents/skills/publish-release/SKILL.md`；图标查名见 `.agents/skills/find-hugeicons/SKILL.md`
- 对外发版必须用正式 keystore 签名；`release.jks` 缺失时构建回退 debug 签名（**仅本地调试**，不可对外分发）
