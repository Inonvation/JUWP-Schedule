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
4. 只读参考：`F:\light-life-v3.0`（胖乖）、`scripts/`（教务爬虫，说明见 `scripts/README.md`）

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
、`BikeNearbyTest`（附近单车：响应容错/聚簇/距离/状态推导）、
`KqcxBikeClientTest`（失败分类：超时与网络不可达不能混）、
`Gcj02Test`（WGS84→GCJ-02：境外不偏移/境内量级/相对距离不变）
等 53 个测试类。

行为约定（改之前先读）：
- 教务页星期只能从课程所在 `<td>` 的**列序**推（第 0 列是节次标签）。`li.qz-hasCourse-N` **几乎恒为 1**（实测 33 处 `-1`、2 处 `-3`），不能当星期来源。
- 课程配色不按课程名哈希取（12 桶内必然撞色），走 `ScheduleCalculator.colorIndexesBySortedName` / `nextColorIndex`。
- 作息表结构版本存在 DataStore（`DisplayPrefsStore.slotSchemaVersion`）；改作息要同时调 `DefaultData.SLOT_SCHEMA_VERSION` 并给迁移。
- 课程时间**只有一条口径**：`ScheduleCalculator.courseStartMinutes` / `courseEndMinutes`（自定义时间课以 custom 字段为准）。
  排序、行内时刻、倒计时、进度条、`coursePhase`/`nextCourse`/`dayLastEndMinutes` 全部走它，不要再自己写 `isCustomTime` 分支。
- 今日页结构：**焦点卡（正在上/下一节）+ 单时间轴**；焦点课从列表里剔除（`TodayUiState.listCourses`），
  同一节课不得两处出现；节次号只在焦点卡出现一次。改版前先读 DESIGN §3.3。
- 可见星期序列以 `ScheduleCalculator.visibleDays` 为唯一来源、`columnOf` 取列下标；
  不要用 `day - 1` 当列号（隐藏周六但显示周日时会错位）。
- 今日页底部固定区（快捷方式网格 → **服务格一行两列** → 开水卡）是 `TodayBottomDock`，
  **钉在滚动区下方**、不进 `LazyColumn`；三态（加载/空/有课）共用同一份，别只改一处。
  开水卡不并进服务格（卡内要放小票余额与流程简报；开水按钮在点余额弹出的
  `WaterEntrySheet` 里）。整块内容可折叠（把手「江水生活」，
  展开态存 `DisplayPrefs.todayDockExpanded`，默认展开），**折叠动画只做高度、锚点必须选 Top**
  ——锚 Bottom 或再叠一层 slide 都会变形，两个失败版本记在 DESIGN §3.3，别重复试。
  改版前先读 DESIGN §3.3。
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
- 小组件是**单条目 + `SizeMode.Exact` 自适应**（2026-09-20 起，旧三档条目已删）：尺寸由
  `WidgetMetrics`（实测 dp）分档 Compact / List / Week，**不要再加按尺寸拆的 receiver 或
  `widget_info_*`**（旧版三条目内容重复，用户明确要求合并）。改渲染前先读 DESIGN §3.6；
  刷新机制（边界闹钟 + WorkManager + 冷启动）与「写状态 + `update()`」双步**不许动**
  （理由见 `ScheduleWidget` 类 KDoc：Glance 会话的两条硬约束是不可绕过的）。
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
- 免费时长提醒（DESIGN §3.9）：**写的是系统日历，不是 App 通知**（2026-09-23 改）。
  日历事件锚在**免费结束那一刻**（`DTSTART == DTEND`，0 时长），挂两条提醒：
  `MINUTES = 提前量` 与 `MINUTES = 0`。理由：`Reminders.MINUTES` 只能表达
  「事件开始前 N 分钟」且非负，锚在扫码时刻会让「提前 3 分钟」落到扫码之前。
  事件 description 用独立标记 `水贝贝骑行提醒`（课表同步只认自己的 `水贝贝课表同步`），
  删除按 DataStore 的 `ebikeFreeEventId`。**不要再把 `setAlarmClock`、通知 channel、
  `EbikeFreeRideReceiver` 加回来**；`EbikeFreeRideCheckWorker` 的类名也别改
  （老版本排下的周期任务按类名实例化，`KEEP` 策略又不会重排，兜底会永久消失）。

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

## 教务爬虫（scripts/）

正式脚本 5 个；历史一次性探测脚本在 `scripts/_archive/`（**勿依赖**，仅留档；该目录不入公开仓库）。

| 文件 | 作用 | 产出 |
|------|------|------|
| `jw_session.py` | 共享登录（CAS → 教务 SSO → 会话校验） | — |
| `fetch_courses.py` | 学期理论课表（`--term` 可选） | `scripts/out/courses.json` |
| `fetch_lab_courses.py` | 实验课表（实践实验 → 实验课表查询，`--term` 可选） | `scripts/out/lab_courses.json` |
| `fetch_exams.py` | 考试安排（`--term` 可选，缺省取教务当前学期；JSON 接口） | `scripts/out/exams.json` |
| `fetch_scores.py` | 课程成绩（`--term` 可选，缺省全部学期；JSON 接口） | `scripts/out/scores.json` |

所有脚本输出 JSON 顶层 `term` = **实际爬到的学期**（如 `2026-2027-1`），App 导入确认弹窗据此展示；
带 `--term` 时脚本会校验「请求学期 = 教务返回学期」，不一致直接报错而不是静默爬错学期。

```powershell
.\.venv-scraper\Scripts\python.exe scripts\fetch_courses.py     # 可加 --term 2025-2026-2
.\.venv-scraper\Scripts\python.exe scripts\fetch_lab_courses.py
.\.venv-scraper\Scripts\python.exe scripts\fetch_exams.py
.\.venv-scraper\Scripts\python.exe scripts\fetch_scores.py
```

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
MainActivity → 底栏今日/课表/我的 + 路由 jw_import；SubpageActivity 承载二级页（含成绩查询 SCORES、
               笔记/作业 7 个二级页 NOTES·NOTES_COURSE·NOTE_DETAIL·HOMEWORK·HOMEWORK_COURSE·
               HOMEWORK_DETAIL·HOMEWORK_TODO，DESIGN §3.11）
domain/          Course·TimeSlot·SemesterConfig·ScheduleCalculator·ExamMapper·Score（纯逻辑，可 JVM 测）
                 + Note·Homework·Markdown·MarkdownEdit·MarkdownImages·MathTex·HomeworkCenter（§4.20）
                 + EbikeQr·EbikeFreeRide·BikeNearby（§3.9：出码车号口径、免费时长、附近车辆解析）
                 + Gcj02（WGS84 → GCJ-02，§4.23 唯一的坐标转换处）
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
ui/today|week|me|water|campus|jwvw|score|timetable|common|theme|widget|ebike|notes|homework
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
见 DESIGN §3.9，已在 Redmi K70 的小米日历验证事件与两条提醒落库；同日修复
「进二级页后挂后台、从桌面图标回来落到今日页」的导航错乱，见 DESIGN §3.1，
已在 Redmi K70 验证：二级页实例 id 不变、录屏无今日页中间帧）

## 仓库与发版

- 公开仓库：https://github.com/Inonvation/JUWP-Schedule （MIT）
- **不入库**（已 gitignore，本地保留）：`scripts/out/`（含真实学号/姓名/会话）、
  `scripts/_archive/`、`docs/`、`scripts/gen_week_layout_preview.py`、`release.jks`、`keystore.properties`
- 发版流程见 `.agents/skills/publish-release/SKILL.md`；图标查名见 `.agents/skills/find-hugeicons/SKILL.md`
- 对外发版必须用正式 keystore 签名；`release.jks` 缺失时构建回退 debug 签名（**仅本地调试**，不可对外分发）
