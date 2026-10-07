# JUWP Schedule · Agent 工作规范

本文件给 AI Agent / 结对工具读。人看 `README.md` 与 `DESIGN.md`。

## 项目身份

- 名称：JUWP Schedule / 显示名「水贝贝」
- 学校：江西水利电力大学（**非官方**；对外文案必须免责声明）
- 包名：`edu.jxslu.schedule`（debug 变体加 `.debug` 后缀，详见「工程实况」）
- 形态：单模块 `:app` · Kotlin · Compose + Material3 · minSdk 26 / compileSdk 35

## 怎么用这份文件

**按需读，不要通读。** 本文件只放每次都用得上的东西：工程实况、命令、架构、硬性禁止。
领域细节按主题拆在 `.agents/rules/`，动到哪块读哪块，读完再动手。

| 你要改 | 先读 |
|--------|------|
| 构建 / R8 / 签名 / 发版 / 包名 | `.agents/rules/build-release.md` |
| 找「某个行为被哪个测试钉住」 | `.agents/rules/tests.md` |
| 导航 / 启动页 / 二级页 / 过渡 / 悬浮栏 / 背景图层级 | `.agents/rules/nav-window.md` |
| 卡片 / 弹层 / 一次性提示 | `.agents/rules/ui-common.md` |
| 今日页 / 课表网格 / 作息表 / 课程时间 | `.agents/rules/today-ui.md` |
| 收件箱（入箱挂点 · 去重 · 保留策略） | `.agents/rules/inbox.md` |
| AI 调课（prompt · 工具调用 · API Key · 落库语义） | `.agents/rules/ai-schedule.md` |
| 桌面小组件 | `.agents/rules/widget.md` |
| 教务课表导入 / 考试 / 成绩 / 教材 / 选课 / 教学日历 / 评教 / 实习基地 | `.agents/rules/import-jw.md` |
| 登录 / 凭证 / 会话 / 自动填表 / 首启引导 | `.agents/rules/login-session.md` |
| 笔记 / 作业 / Markdown / 公式 / 课程备注 | `.agents/rules/notes-homework.md` |
| 共享单车 / 地图 / 免费时长提醒 | `.agents/rules/ebike.md` |
| 生活页 / 一卡通 / 电费 / 胖乖生活 | `.agents/rules/life-power.md` |
| U净 洗衣房（美的校园洗衣） | `.agents/rules/ujing.md` |
| 学工表单 / 盖章成绩单 | `.agents/rules/xg-transcript.md` |

其他只读参考：

1. `PROMPTS.md` 只读你要做的那一个阶段块
2. `DESIGN.md` **只读与任务相关的章节**（§3 导航/UI 规格、§4 结构规格、§3.5 作息表）。
   2026-10-06 起 `DESIGN.md` 只是**索引**，正文按 §3 / §4 拆在 `design/*.md`；
   章节号一字未改，先查索引里的「章节 → 文件」表再打开对应文件
3. 历史实现记录在 `docs/devlog.md`（仅本地），只在排查「当初为什么这么改」时才翻
4. 只读参考：`F:\light-life-v3.0`（胖乖）、`scripts/README.md`（爬虫脚本）

**改完的收尾顺序**：写代码 → 编译 + 单测 → 按「装机与验证路径」决定装在哪台设备、谁来点验 → 最后同步文档。
`DESIGN.md` 对应章节（`design/` 下对应文件，不是 devlog）放在最后一步，用户可以先在真机上并行点验。
同步时顺手 grep 旧说法，确认没有残留，同一口径只留一份：

```powershell
Get-ChildItem -Recurse -Include *.md,*.kt | Select-String -Pattern '<旧说法>'
```

## 工程实况（勿按过时文档猜）

| 项 | 现值 |
|----|------|
| Gradle Wrapper | **8.10.2** |
| AGP | **8.7.3** |
| Kotlin | **2.1.21**（compose / serialization 同版本；KSP `2.1.21-2.0.1`） |
| Room | **2.7.1**（2.6 + Kotlin 2.1 会 KSP `unexpected jvm signature V`） |
| Room DB | **v20**。表：`courses`（含 `kind` / `remark` / `timetableId`）、`time_slots`、`semester_config`、`timetables`（v14 起 `term` 列记数据学期，是详情查教材的钥匙）、`scores`、`scholar_groups` / `scholar_courses`（学业完成情况，v13）、`ykt_turnovers`（v15 起 `fromAccount` / `accType` 记交易账户，消费流水「充值到哪」的钥匙）、`notes`、`homework`、`power_readings`（含 `roomId` 数字 id 与 `roomName` 房号显示名，两者别混用）、`textbooks`（教材，v14，挂 courseName 带 term）、`ride_records`（本机骑行记录，v16，只记本机用车那条链路）、`course_selections`（选课记录，v17，按学期替换，DESIGN §4.35）、`inbox_messages`（收件箱，v18，§4.38）、`date_overrides`（日期覆盖：调休 / 停课，v19，§4.40）、`curriculum_courses`（培养方案课程设置，v20，§4.41）。迁移逐级 `ALTER TABLE` / `CREATE TABLE`，**禁止**改 destructive；实体 `@Index` 必须与迁移 `CREATE INDEX` 对齐，漏声明会迁移校验崩溃；**实体带 Kotlin 默认值的列，迁移建表必须写 `DEFAULT`**（v7→v8 remark、v11→v12 roomName、v13→v14 教材展示列同坑；v19 的 `note` 特意不给 Kotlin 默认值来绕开这条） |
| 作息表 | **11 小节**（每节 40 分钟，大节内 5 分钟、大节之间 20 分钟换教室），见 DESIGN §3.5 |
| 课表网格 | 行号 = **小节号 1–11**（不是大节号）；`Course.startSection/endSection` 也是小节号 |
| HugeIcons | `com.github.rikkahub:hugeicons-compose:1.4`（**JitPack**，**`isTransitive = false`**）。**不要**写 `me.rerere:hugeicons-compose:1.0.0`（Maven Central 不存在）；不要打开传递依赖（会拉 `androidx.core` 1.17，AGP 8.7 / compileSdk 35 编不过） |
| 图标用法 | `import me.rerere.hugeicons.stroke.*` + `HugeIcons.Calendar01` 等；查名用本地 JAR，勿猜 |
| osmdroid | `org.osmdroid:osmdroid-android:6.1.18`（Maven Central；POM 里没有 `<dependencies>`，不拉传递依赖）。**加依赖后第一次构建要联网 resolve 一次**，之后 `--offline` 照常用 |
| Glance | `androidx.glance:glance-appwidget:1.2.0`（桌面小组件）；传递抬 compose runtime 至 1.7.8，`androidx.core` 仍 1.15.0 |
| 样例课 | **已移除**；课表默认空，从教务 WebView 导入 |
| 包名 | release = `edu.jxslu.schedule`；debug = `edu.jxslu.schedule.debug`（两者签名不同，**必须**靠后缀区分） |

> 表里的版本号与 DB 版本**以代码为准**，本表只是索引。对不上时先查代码再改表：
> `Select-String app\build.gradle.kts -Pattern 'versionName'`、
> `Select-String app\src\main\java\edu\jxslu\schedule\data\local\JuwDatabase.kt -Pattern 'version ='`

## 常用命令

```powershell
# 构建 + 测试（本机依赖已齐备，加 --offline 后秒级完成）
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline
# APK: app\build\outputs\apk\debug\app-debug.apk
# release（R8 压缩 + 签名，约 1.5 分钟）：app\build\outputs\apk\release\app-release.apk
.\gradlew.bat :app:assembleRelease --offline
```

- 用例数从 `app/build/test-results/testDebugUnitTest/*.xml` 汇总（Gradle 成功时不打印用例数），读 XML 用 `-Encoding UTF8`。
- 改过混淆规则要装 release 包冒烟，理由与已踩的坑见 `.agents/rules/build-release.md`。

## 装机与验证路径

**先 `adb devices`，再决定谁来点验**（2026-09-29 起）。默认把点验交给用户，AI 自己测只走下面那个窄口子。
「手机在线」的判据：设备列表里出现非 `emulator-` 前缀的 serial。

| 情况 | 做法 |
|------|------|
| 手机在线 | `adb install -r` 装 debug 包 → 告诉用户已装好、装的是哪个包、建议点验的 2–3 处 → 停下等反馈。不要自己接着在设备上反复点 |
| 手机不在线，且改动够小 | 自己起模拟器自测（起法见下），结论落成一句话 |
| 手机不在线，改动不够小 | 说明缺哪台设备、需要用户点验什么。不要为了「测过」而扩大模拟器范围 |

「够小」= 下面三条**同时**满足，缺一条就交给用户：

1. 不碰网络请求路径（Retrofit/OkHttp、WebView 注入、教务/胖乖/一卡通/电费/快趣/农行收银台）
2. 不碰凭证、签名、`proguard-rules.pro`、Room 迁移、`AndroidManifest` 权限
3. 验证点只在 UI 层，且 `uiautomator dump` 或 `screencap` 能给出结论

模拟器上的结果只算冒烟，不算真机验证；沾到账号、网络、系统权限的功能一律等用户点验。

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
# MIUI 可能弹「USB 安装」需在手机上允许；首次常被 INSTALL_FAILED_USER_RESTRICTED 拦，重试一次通常就过
# 多台设备在线时加 -s <serial>，用 adb devices -l 分辨真机与模拟器
```

debug 与 release 是两个独立应用（桌面名「水贝贝 Debug」/「水贝贝」），可同时安装、数据各一份。改动冲突时改 `app/src/debug/res/values/strings.xml`（仅覆盖 `app_name`）；包名与签名口径见 `.agents/rules/build-release.md`。

启动 debug 包**必须写全限定名**——短式 `am start -n <applicationId>/.MainActivity` 会按 applicationId
补前缀、解析成 `edu.jxslu.schedule.debug.MainActivity` 并报 `Error type 3 ... does not exist`
（manifest 里声明的是源码包名，不含后缀）：

```powershell
adb shell am start -n edu.jxslu.schedule.debug/edu.jxslu.schedule.MainActivity
```

**设备列表看不到手机时**：先确认是不是根本没连。重跑 `adb connect` 无效、排除僵尸 adb /
小米妙享抢接口后，直接提醒用户插线或确认无线调试已开，不要在环境侧反复排查空转。

**模拟器必须避开 WinNAT 保留端口段（2026-09-29 实测）**：`-port 5554/5556/5558` 全部起不来
——现象是 qemu 活着、客户机也在渲染（日志里有 RenderThread），但 `adb devices` 里没有它，
`bind` 直接回 `WinError 10013`。**根因是这些端口落在 Hyper-V/WinNAT 的保留段里**，
不是"本机禁绑"（同一台机器 `5183/5960/8000` 都能绑）。起模拟器前先查：

```powershell
netsh int ipv4 show excludedportrange protocol=tcp   # 本机实测保留 5458-5557 / 5558-5657…
```

再挑一个**不在保留段**的端口（如 `-port 5183`），启动后就是 `emulator-5183`。
本机两个 AVD：`jwptest`、`jw35`（android-35），可用的 headless 起法：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd jw35 -no-window -no-audio `
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -port 5183
adb -s emulator-5183 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5183 shell uiautomator dump /sdcard/u.xml   # 页面文字树，可逐条核对
adb -s emulator-5183 exec-out screencap -p > shot.png       # 真渲染截图
```

**模拟器比真机好用**：`uiautomator dump` 能直接读到 Compose 的文字树、`screencap` 出真图，
真机锁屏时这两条都拿不到（`screencap` 全黑、dump 只读到 `com.android.systemui`）。
`input tap <x> <y>` 的坐标可从 dump 里的 `bounds` 算中心点。
真机若锁屏，远程也做不了点验：`mDreamingLockscreen=true` + `locksettings get-disabled` 为
`false` 时没有解锁凭据就进不去（唤醒后顶层是 MIUI `ScreenOnProximitySensorGuide`），
`screencap` 全黑、`uiautomator dump` 只读到 `com.android.systemui`——**请用户解锁并保持亮屏**，
那时 `uiautomator dump` 能读到本应用窗口的文字，可以逐条核对。

无线调试（手机重启或 `adb usb` 后失效，IP 要现取勿记死）：
`adb -s <serial> tcpip 5555` → `adb shell ip route` 取 IP（接口是 **wlan2**，不是 wlan0）→ `adb connect <ip>:5555`。

## 爬虫脚本（scripts/）

正式脚本 11 个；历史一次性探测脚本在 `scripts/_archive/`（**勿依赖**，仅留档；该目录不入公开仓库）。

| 文件 | 作用 | 产出 |
|------|------|------|
| `jw_session.py` | 共享登录（CAS → 教务 SSO → 会话校验） | — |
| `fetch_courses.py` | 学期理论课表（`--term` 可选） | `scripts/out/courses.json` |
| `fetch_lab_courses.py` | 实验课表（实践实验 → 实验课表查询，`--term` 可选） | `scripts/out/lab_courses.json` |
| `fetch_exams.py` | 考试安排（`--term` 可选，缺省取教务当前学期；JSON 接口） | `scripts/out/exams.json` |
| `fetch_scores.py` | 课程成绩（`--term` 可选，缺省全部学期；JSON 接口） | `scripts/out/scores.json` |
| `fetch_textbooks.py` | 学生教材确认（`--term` 可选，缺省教务当前学期；JSON 接口 `/jsxsd/nxsjc/xsjcqr`） | `scripts/out/textbooks.json` |
| `fetch_selections.py` | 选课记录 + 选课轮次（`--term` 可重复，缺省当前学期+下一学期；layui JSON；**只读**，退课接口绝不碰） | `scripts/out/selections.json` |
| `fetch_curriculum.py` | 培养方案「课程设置总表」（全量 84 门级别，含开设学期 1–8；服务端渲染 HTML，不随学期；另有 `curriculum.md` 按学年可读版） | `scripts/out/curriculum.json` |
| `fetch_power.py` | 寝室电费（新开普缴费平台 `charge.juwp.edu.cn`，**非教务**；`--history` / `--room 9A101`） | `scripts/out/power.json` |
| `fetch_transcript.py` | 教务处**盖章成绩单**（金格签章系统 `jwxyxx.juwp.edu.cn`，**非强智教务**；`--list` / `--term` 可多个 / `--out`） | `scripts/out/transcript_<标签>.pdf` |
| `sync_calendar.py` | 校历镜像同步（教务处官网通知公告 → 抓高清原图 → 学年/文件名/sha256 比对，变更才落盘并自动 commit & push；**免登录免凭证**） | `docs-public/calendar/`（更新仓库镜像）；**2026-10-07 后 App 已直取官网为主，本脚本为应急工具** |

所有脚本输出 JSON 顶层 `term` = **实际爬到的学期**（如 `2026-2027-1`），App 导入确认弹窗据此展示
（`fetch_curriculum.py` 例外：培养方案全量、不按学期抓取，无 term 口径）；
带 `--term` 时脚本会校验「请求学期 = 教务返回学期」，不一致直接报错而不是静默爬错学期。

```powershell
.\.venv-scraper\Scripts\python.exe scripts\fetch_courses.py     # 可加 --term 2025-2026-2
.\.venv-scraper\Scripts\python.exe scripts\fetch_lab_courses.py
.\.venv-scraper\Scripts\python.exe scripts\fetch_exams.py
.\.venv-scraper\Scripts\python.exe scripts\fetch_scores.py
.\.venv-scraper\Scripts\python.exe scripts\fetch_textbooks.py
.\.venv-scraper\Scripts\python.exe scripts\fetch_selections.py
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
- 教材确认同款 layui 接口 `/jsxsd/nxsjc/xsjcqr`（`xnxqid` + 分页；`fetch_textbooks.py` §5.7）。
  **`xsjcisxy.do` 是征订确认写操作（POST），脚本与 App 只读 `xsjcqr`，绝不触碰**。
- 选课同款 layui 接口：选课日志 `loadXsxkjgList?lx=xkrz&type=list&xnxqid=`、轮次
  `xsxk/xklc_list_data`（`fetch_selections.py` §5.8；**轮次接口的 `code` 是字符串 `"0"`**）。
  **`Xsxkjg_tk.do`（申请退课，POST）与 `mzlist.do`（免责声明查询）绝不触碰**；
  选课/退课只在 App 的 WebView 里由用户自己在教务页面上操作。
- 登录链路、DOM 规则、排错表、WebView 注入 JS：**`scripts/README.md`**（比 DESIGN 更细）。

## 架构（改代码前对齐）

```
MainActivity → 底栏今日/课表/生活/我的（生活页可关，默认开，DESIGN §3.13）+ 路由 jw_import；
               SubpageActivity 承载二级页（含成绩查询 SCORES、选课 SELECTIONS·SELECTION_WISHES、
               笔记/作业 7 个二级页 NOTES·NOTES_COURSE·NOTE_DETAIL·HOMEWORK·HOMEWORK_COURSE·
               HOMEWORK_DETAIL·HOMEWORK_TODO，DESIGN §3.11；U净 三页 UJING·UJING_ORDER·
               UJING_HISTORY，下单选择走 UjingDraftStore 跨窗口交接，§4.37；
               调课处置页 SCHEDULE_CHANGES，§4.17；AI 调课 AI_SCHEDULE + 配置 AI_SETTINGS，§4.39；
               培养方案 CURRICULUM + 实习基地 INTERNSHIP_BASES，§3.26 / §3.27；学生评教走
               JwImportActivity 的 TeachingEval 模式（§4.44））
domain/          Course·TimeSlot·SemesterConfig·ScheduleCalculator·ExamMapper·Score·ScholarProgress（纯逻辑，可 JVM 测）
                 + ScheduleDiff（调课差异指纹/配对/冲突，§4.17）+ ScheduleCourseRow·SchedulePendingChanges（可序列化影子）
                 + Note·Homework·Markdown·MarkdownEdit·MarkdownImages·MathTex·HomeworkCenter（§4.20）
                 + Textbook（§4.31：挂 courseName 带 term，教务教材确认）
                 + EbikeQr·EbikeFreeRide·BikeNearby·EbikeUseMode（§3.9：出码车号口径、免费时长、
                 附近车辆解析、使用方式=小程序/账号两档 + `EbikeCapabilities` 能力矩阵）
                 + Gcj02（WGS84 → GCJ-02，§4.23 唯一的坐标转换处）
                 + LifeFeed（一卡通与电费流水分段，§3.13）
 + QzxyFrame·QzxyProtocol·QzxyCredential·QzxySign（趣智校园蓝牙水控，§4.30）
 + QzxyClData·QzxySessionLink·QzxyPhoneMask·QzxyWatering（会话串 · 手机号遮蔽 · 用水记账）
 + AiSchedulePrompt·AiScheduleTool·AiScheduleConfig（AI 调课：prompt/脱敏/学期日历 ·
 工具 schema 与解析 · 默认服务配置，§4.39）
 + ScheduleOverride·ScheduleOverrides（日期覆盖：调休 / 停课，§4.40——`coursesOnDay` 的
 覆盖查表，渲染层叠加不写 courses 表）
 + ScheduleUndo·AppliedEntry（撤销的反向规划，§4.39——纯函数，仓储只按 Action 落库）
 + Curriculum·CurriculumRules（培养方案课程设置：全量课程 + 开设学期 1–8 ·
 学年分组/校验/标签，§4.41）
 + TeachingCalendar·TeachingCalendarRules / InternshipBase·InternshipBaseRules（§4.42 / §4.43：
 教学日历→第 1 周周一推导 · 实习基地与类别分组）
data/local/      Room v20：courses / time_slots / semester_config / timetables（term 列，v14）/ scores
                 / scholar_groups / scholar_courses / ykt_turnovers / notes / homework
                 / power_readings（v12 起；房号显示名 roomName）/ textbooks（v14）
                 / ride_records（v16）/ course_selections（v17，选课记录按学期替换，§4.35）
                 / inbox_messages（v18，收件箱，dedupeKey 唯一索引，§4.38）
                 / date_overrides（v19，日期覆盖：调休 / 停课，§4.40）
                 / curriculum_courses（v20，培养方案课程设置，§4.41）
data/repo/       ScheduleRepository + JSON 导入校验；ScoreRepository（成绩按学期替换）
                 ScholarProgressRepository；ScoreSync / ScholarProgressSync（自动导入，DESIGN §4.29）
                 CurriculumRepository / CurriculumSync（培养方案课程设置，§4.41——同学业一套纪律）
                 TeachingCalendarSync（导入课表后按学期校准开学日，§4.42）
                 TextbookSync（导入课表后抓教材 + 冷启动补抓，§4.31）
                 InternshipBasesLoader（实习基地直取 + 按页循环，不落库，§4.43）
                 SelectionRepository / SelectionSync / SelectionRoundsStore（选课记录与轮次，§4.35）
                 NoteRepository / HomeworkRepository / AttachmentStore（笔记作业图片，§4.20）
                 AiImageStore（AI 调课图片，§4.39——filesDir/ai_img/，**会话级**，
                 口径 1600 / q80 / 强制 JPEG / 上限 4 张，新会话与冷启动清空）
                 ScheduleSync（课表直取 + 首启自动导入 + 调课检查）/ ScheduleBaselineStore
                 （调课基线 filesDir JSON，§4.17）
                 InboxRepository（收件箱留痕层，旁路不发网络请求，§4.38；规则见 .agents/rules/inbox.md）
data/prefs/      DataStore 显示偏好（含 slotSchemaVersion）
data/jw/         JwUrls + QiangzhiScheduleParser（理论 xskb）+ SyjxScheduleParser（实验 syjx）
                 + ExamScheduleParser / ScoreParser（考试·成绩 = 同源 fetch JSON，非 DOM 解析）
                 + ScholarProgressParser（学业完成情况 = 教务返 HTML，按表头名映射，非 JSON）
                 + CurriculumParser（培养方案课程设置 = 教务返 HTML 整页，正则抠节点，§4.41）
                 + TextbookParser（教材 = layui JSON 接口，§4.31）
                 + SelectionParser（选课日志与轮次 = layui JSON，§4.35）
                 + TeachingCalendarParser（教学日历 = 教务返 HTML，只取第 1 周周一，§4.42）
                 + InternshipBaseParser（实习基地 = layui JSON，§4.43）
                 + JwTeachingEval（学生评教：token 注入脚本 · 平台域判据 · 日志脱敏，§4.44）
data/qiekj/      胖乖生活 API（登录/开水/余额/订单）
data/ykt/        一卡通（新中新慧新e校）登录与付款码（DESIGN §4.19；凭证 ykt_credentials.xml
                 已排除备份；token 仅内存；无日志拦截器；8002/8003 验证码绝不重试）
data/kqcx/       快趣出行（DESIGN §4.23 / §4.32）：附近车辆接口（无鉴权、无凭证、只发坐标）
                 + KqcxAuthClient / KqcxSessionRepository / KqxCredentialStore（账号登录与
                 用车；token 仅内存、凭证 secure_kqcx.xml 已排除备份）
                 + KvcxRideSession（用车编排的业务接口）+ ZoneCacheStore（图层缓存）
data/power/      寝室电费（新开普缴费平台 charge.juwp.edu.cn，DESIGN §4.24；凭证复用一卡通的
                 学号 + 查询密码；token 仅内存、无日志拦截器。充值两渠道：电子账户
                 App 内 6 位密码；农行支付 = 内嵌手机版收银台 PowerBankPayActivity，
                 取链接那一跳必须发微信 UA）
data/qzxy/       趣智校园开热水（DESIGN §4.30 / UI §3.18；真机闭环：开阀 + 结束用水结算）
                 QzxyWateringStore = 「用水中」状态（StateFlow + 落盘，今日页卡片与页面共享）
                 QzxyWaterFlow = 协议状态机（纯 JVM 可测，单测在 QzxyWaterFlowTest）
data/ai/         AI 调课（DESIGN §4.39；OpenAI 兼容 /chat/completions + /models）：
                 AiScheduleClient（流式；tools + tool_choice；档位关时 DeepSeek 端点带
                 thinking disabled；带图时 content 升多模态数组；**无日志拦截器**）
                 + AiCredentialStore（API Key，secure_ai.xml 已排除备份）
                 规则见 .agents/rules/ai-schedule.md
ui/today|week|life|me|water|qzxy|campus|jwvw|score|exam|scholar|curriculum|internship|selection|timetable|common|theme|widget|ebike|notes|homework|ujing|inbox|ai
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
- **提交不带个人信息**：提交信息、代码、注释、测试 fixture 里不出现真实学号、姓名、手机号、寝室房号、真实邮箱；作者身份用 noreply 邮箱
- **提交信息精简**：标题一行说清改了什么，正文只写必要的理由与影响面。细则见 `.agents/rules/build-release.md`
- 禁止改导航或课表领域模型时不同步 `DESIGN.md`（同步排在编译/装机之后，见「怎么用这份文件」的收尾顺序）
- 禁止 emoji 当功能图标；HugeIcons 查名用本地 JAR，勿猜
- 禁止把「能编译」当完成；禁止故意压制编译错误

## 沟通与 DoD

- 与用户中文交流；少形容词，多可验证结论
- 完成定义：功能可演示。能跑则跑 `assembleDebug` + `testDebugUnitTest`；装机与点验按「装机与验证路径」
  走，手机在线就装给用户点验，不在线且改动够小才自测，其余写明阻塞；未越权改无关模块
- 文档同步是收尾动作，排在编译/装机之后。删功能时把引用它的文档、注释、测试一并清掉

## 阶段状态（见 DESIGN.md §6 里程碑）

P1 脚手架 · P2 Room+UI · P3 我的页导入导出/学期 · P4 胖乖（已实现，待真机验证）·
P5 教务 WebView · P5b 实验课表导入 — **已完成**

P6 打磨 — **进行中**。2026-09-21 起陆续落地：笔记与作业（自研 Markdown/TeX）、课表页背景图、
免费时长提醒、生活页（一卡通 · 寝室电费）、统一登录会话层、首启引导、学工表单、盖章成绩单导出、
桌面小组件条目（课表 / 校园卡 · 电费合并卡，2026-09-27；胖乖开水 / 趣智开水固定 2×2，
2026-09-28，红线见 `.agents/rules/widget.md`）。
电费充值农行支付渠道（2026-09-28，DESIGN §4.24：内嵌农行手机版收银台「农银快e付」，
手机号 / 短信验证码 / 支付密码只在农行页面里；取链接那一跳必须发微信 UA，否则平台回
空的 `code=6230`、`msg` 却写「处理成功」）。
学业完成情况与成绩自动导入（2026-09-27，DESIGN §3.17 / §4.29：首启登录成功与冷启动各抓一次，OkHttp 直取不依赖 WebView）。
教材与导入学期选择（2026-09-28，DESIGN §4.31 / §4.4.2：导入写库成功后自动抓导入学期教材并显示在课程详情，
一键导入确认弹窗支持切换学期自动重爬；红线见 `.agents/rules/import-jw.md`「教材」节）。
快趣出行使用方式（2026-09-29，DESIGN §3.9 / §4.32：小程序 / 账号登录两档互斥，默认小程序；
能力的唯一判据是 `EbikeUseMode.capabilities()` 算出的 `EbikeCapabilities`；
**2026-09-29 结构重构**：出码页与地图页合并成一个「骑行」页（`ui/ebike/RideScreen.kt`，
`SubpageScreen.RIDE`），**两档共用同一套结构**（「两档各有一套页面布局」的口径作废）——
地图在上、抽屉在下（不是覆盖：覆盖会把「我的位置」压在抽屉底下），抽屉分「固定区
（车号输入 + 唯一主动作 + 文字入口 + 列表头）/ 滚动区（车辆列表）」两段；点车辆 = 车号自动
填进输入框 + 悬浮车辆卡（**不是**模态弹层）；二维码以弹窗展示，出码两档都保留；
使用方式开关从设置弹层搬到标题栏常驻 chip，**骑行中禁切**；
红线见 `.agents/rules/ebike.md` 首节）。
电费充值入账修复与充值免责声明（2026-09-29，DESIGN §4.24：**下单必须带 `third_party`** =
读表 `map.data` 原文 JSON，缺了平台照样收钱但**永不入账**（`order.flag` 停 0，只能人工找
管理员，已实际卡死三笔 26 元）；下单前强制新鲜读表、缺读数拒单；扣款与入账分开报告。
**每个充值入口**先过充值免责声明弹窗（五条声明 + 一周静默 + 首次锁 5 秒）；
红线见 `.agents/rules/life-power.md`「电费充值」「充值免责声明」两节）。
校历（2026-09-30，DESIGN §4.34：校园服务里免登录的图片页——学校侧校历只是一张图、
发现链路绑微信 token，改为**公开仓库镜像** `docs-public/calendar/`（图 + index.json），
App 冷启动 7 天闸门静默刷 + 页内手动刷新，内置兜底图永不空屏，点图全屏手势缩放；
**每年更新 = 存新图 + 改 index.json 两个值**，用户端免发版自动换）。
快趣出行三处调整（2026-09-30，DESIGN §3.9 / §4.32）：① 未登录的登录表单写清「忘记密码」
的出路（小程序登录页「忘记密码」重设 / 登录后「设置 → 修改密码」，密码保存在本机加密存储）；
② 骑行态的还车点列表改用运营方点位名当标题（`givecarList[].name`，缺名才回落「还车点」），
这个字段一并进缓存文件；③ 免费时长提醒补 `VIBRATE` 权限 + 提醒时直接震动（此前通知渠道配了
震动但 manifest 缺权限，系统静默丢弃——用户报的「通知了但没震动」根因），设置弹层「提醒」区
新增「通知与悬浮」入口；**横幅（heads-up）弹不弹由系统 / ROM 决定，App 强制不了**。
开锁 / 还车确认弹窗打磨（2026-10-01，DESIGN §3.9 / §4.32）：正文改成编号要点（长文可滚，
小屏与大字体档位下按钮不被挤出去）；**开锁弹窗新增「以后开锁不再确认」勾选**，落
`ebike_unlock_confirm`，出口在快趣出行设置新增的「开锁与还车」区（可随时恢复）；
**只有开锁能免确认**——还车涉及结算与调度费、重试开锁是异常路径，都保留每次确认，
定位前置照旧排在直发分支前面。
地图重绘与弹窗重组（2026-10-01，DESIGN §3.9「刷新成本第二刀」/ §4.32）：用户报「点确认开锁后
卡一下」，查出两条——① `AndroidView` 的 update 每轮重组都跑、收尾无条件 `invalidate()`，
于是页面上任何与地图无关的状态变化都整幅重画地图，现在改为**先比对 `MapOverlayInputs`、
画的东西没变就不重绘**；② 用车确认弹窗的状态读取收进 `KvcxConfirmHost`，开关弹窗不再重组整页。
开锁后掉帧的根因与修复（2026-10-01 第二轮，DESIGN §3.9）：用户报「关掉二次弹窗后，开锁就掉帧、
然后骑行面板弹出来」——那 240ms 是动作区 `AnimatedContent` 的高度动画，地图跟着实测高度走，
**每帧 resize 一次 → osmdroid 每帧整幅重绘**。现在地图的让位高度在换形态期间**冻结**
（`mapReserveDp` + `DpHolder` 只记账不写状态），动画结束才一次性落位；页面结构改成
`Box { 地图; 贴底的 Column（面板 + 常驻块，叠在图上）}`，形态 key 由 `rideBarKey()` 唯一算出
（`RideBarKeyTest` 3 例钉住）。
点地图标记滚列表（2026-10-01，DESIGN §3.9 / 规则见 `.agents/rules/ebike.md`）：用户报「点地图上
单车的数字圆圈，列表有时候不会滚到对应地点卡片」——展开目标卡的同时上一张展开的卡在收起，
目标卡一路上移，当帧算出的偏移直接滚就是**滚过头**（卡片落到视口上方）。现在等位置落定
（`RidePanels.awaitSettledOffset`：逐帧读记账、连续 3 帧不动才启程）再滚一次到位；模拟器
（jw35）已复现并验证三种场景（目标在上/在下/原报障序列）。
选课（2026-10-01，DESIGN §3.19 / §3.20 / §3.21 / §3.22 / §4.35 / §4.36；规则见 `.agents/rules/import-jw.md`）：
我的 → 学习 → 选课——选课记录（教务「选课日志」，Room v17 `course_selections` 按学期替换，
OkHttp 直取默认抓当前学期+下一学期）、选课轮次卡（`SelectionRoundsStore` 快照 +
「应用内选课 / 教务页面」双入口）、轮次提醒
（开始前 30 分钟 / 截止前 6 小时，`setAlarmClock` 精确闹钟，周期核对搭成绩/考试那趟车、
间隔共用）。同日修「进入选课中心卡加载中」（自动落页触发点改 `onPageCommitVisible` +
看门狗，见 `.agents/rules/import-jw.md`）、加**轮次直达**（`selectionRoundId` 白名单）与
**预选清单**（§3.20，DataStore JSON）；**B 阶段落地抢课骨架**（§3.21 面板 + §4.36 引擎/策略/
前台服务/须知——**接口未接入**，唯一替换点 `Graph.selectionCenterClient`，窗口期在下学期）；
**应用内选课中心**（§3.22：课程列表 / 搜索筛选 / 选课 · 退课逐次确认——回答了
「所有操作都在 App 内」，教务页面降为备用入口）。
**选课页排版收口**（2026-10-01，§3.19）：主页只放轮次卡 + 功能入口 + 提醒三块；
选课记录挪进独立二级页「已选课程」（`SELECTION_RECORDS`）；**教务 WebView 全页只留
「教务网页」一个备用入口**，可操作轮次行改直达应用内中心（轮次 id 走 `focusItemId` 通道）；
同轮微调：学习 hub 入口标「选课（beta）」（接口联调前）、轮次卡加「上次同步/失败」脚注、
轮次相位每分钟重算、预选清单加已选/课表防重复对照、固定高按钮改 `heightIn` 防大字体裁字。
**红线**：`Xsxkjg_tk.do`（申请退课，POST）全链路不碰；写操作只做加课、只在用户显式开始后发出，
不并发、连续失败自动停；应用内的退课是逐次确认的手动操作，抢课引擎永不调退课。
轮次展示与提醒的时间解析要等选课期才有真实样本（`xksj` 格式），这是唯一未验证面。
U净 洗衣房 P1+P2（2026-10-01，DESIGN §3.23 / §4.37；规则见 `.agents/rules/ujing.md`）：
我的 → 校园服务 → U净——登录（手机号 + 短信验证码）→ 洗衣房空闲看板 → 扫码识别设备与套餐 →
**下单 → 支付宝 SDK 支付 → 订单卡（倒计时 / 取消 / 云端启动）**。协议来自社区多份独立逆向
成果交叉核对（无客户端签名；NcepuJw 为 GPL、仅取协议事实）。实测定论：匿名 `scanWasherCode`
= 401，只读也必须登录。**遗留实测**：JWT 有效期、学校机型 `moduleType`（定 P3 是否需要
BLE 通道）。
同日体验补：下单可选参数按「服务端声明需要才发」（水温 chips / 强制投放标准档 / 烘干
`dryTime`）、洗衣完成提醒（`setAlarmClock` + 渠道 `ujing_done`，无 App 内开关）、页面排版
收口（订单 > 扫码 > 看板 > 账号，进度条 + 2 分钟支付窗口倒计时）、登录态联动补数据。
全量 1262 例 0 失败，装机完成。
投放加购（2026-10-02，DESIGN §3.23 / §4.37）：非烘干机的扫码结果卡出「洗衣液 / 消毒液」
投放 chips，默认按机型强制投放声明勾好、用户可改，勾选 = 下单带标准档 1 / 4
（**档位不可选**——其余档位价格语义没实样；费用以支付宝实付为准）；debug 包另把
`program/info` 原始响应落盘 `filesDir/ujing_debug/` 补协议样本。全量 1265 例 0 失败。
U净 改版（2026-10-02，DESIGN §3.23 / §4.37；规则见 `.agents/rules/ujing.md`「页面结构
与交接」）：扫码后进**独立下单页**（`UJING_ORDER`：档位单选 + 水温 / 投放 chips +
贴底动作条 + 确认弹层），主页扫码区瘦身为「识别中 / 失败 / 继续上次识别」；选择经
`UjingDraftStore`（进程内草稿，故意不落盘）交回主页执行——**下单与支付宝拉起的执行点
仍在主页 ViewModel**。新增**历史账单**（`UJING_HISTORY`：本机发起的订单留痕 ≤50 笔，
下单入账 / 刷新更新 / 取消也算一笔；服务端账单接口无样本，先落本地不猜端点）。
新增 `UjingHistoryStoreTest` 6 例，全量 **1271 例 0 失败**；未碰网络路径 / 凭证 / Room
（「够小」判据满足），模拟器冒烟通过（主页 / 账单页 / 下单页空态）。
顶栏「+」扫一扫 / 付款码（2026-10-02，DESIGN §3.3；规则见 `.agents/rules/today-ui.md`
「顶栏「+」快捷入口」）：今日页右上角一枚 `Add01` + 下拉两行（微信首页形态）。
**扫一扫一个入口管三种去处**：取景走 `EbikeScanActivity` 原文模式 + 解码限定二维码，
去处由 `domain/QuickScan.kt` 单点判——车身码带完整车号进「骑行」页（`focusItemId` 复用，
进页即定位车辆）；认得出的别家码（微信支付 / 微信 / 支付宝 / QQ）就地提示不跳页（这类码送
U净 必然回「未识别到设备信息」，白跳一趟）；其余原文经进程内一次性令牌 `UjingScanHandoff`
交 U净 页识别（不复制那条「识别 → 开草稿 → 推下单页」的链；未登录只提示先登录，不发注定
401 的请求，**原文留 10 分钟，登录完自动接着识别**）。
**付款码** = 校园卡付款码页（§3.10）。同一轮另给 `scanWasherCode` 加了 debug 落盘
（`filesDir/ujing_debug/scan_washer_*.json` 记二维码原文与响应），机身码格式到手后本地判据
可以从「其余全给 U净」收敛成白名单。新增 `QuickScanTest` 6 例，全量 **1277 例 0 失败**。
趣智校园开水面板手感（2026-10-02，DESIGN §3.18）：今日页点余额弹出的面板补齐动画与出口
——关面板走退场动画（此前直接置 `showQzxyEntrySheet = false`，弹层瞬间抽掉）、状态区
`AnimatedContent` + 高度下限（待机 ↔ 用水中不再硬切）、用水中加呼吸徽标与「开始于 HH:mm」、
失败态补「清除设备记录」与「详情」（详情复用页面那份 `FailedDetailDialog`，改 `internal`）、
开阀与结算成功给触感。今日页新增 `onOpenQzxyDebug` 参数接到 `QZXY_DEBUG`。
全量 **1281 例 0 失败**；模拟器冒烟到未登录态（开、关、去登录跳页正常）。
体检优化三批（2026-10-02，跨模块；详单在本地 `docs/optimization-audit-2026-10-02.md`）：
**第一批 正确性**（支付宝 8000 单列 Pending、下单/取参拆两段 try、抢课重叠愿望去重、
提醒变更清检查时刻、TeX/Markdown 递归深度闸门 64 层、图片三态与降采样、保存 in-flight
守卫、焦点卡竖条 IntrinsicSize）；**第二批 性能结构**（displayPrefs shareIn 共享热流、
触感轻流、壳层 AppShellPrefs 切片、滑杆 150ms 节流、tick 统一 repeatOnLifecycle(RESUMED)
——新增 `ui/common/ResumedTicker.kt`、附件清扫每日闸门、busy 反馈收口）；**第三批
健壮性基建**（新增 `data/repo/CrashReporter.kt` 崩溃落盘 + 关于页导出、appScope 异常
handler、CancellationException 穿透 10 处、**新增 `data/net/AppHttp.kt` OkHttp 十客户端
合一** + callTimeout、覆盖安装闹钟补排 MY_PACKAGE_REPLACED、启动读合并）。
三批经两路独立复查与终审（累计修复 4 P1 + 5 P1 + 2 P2），全量 **1281 例 0 失败**。
**红线**：崩溃堆栈只落本机（crash/ 与 ujing_debug/ 已排除出两套备份规则）；AppHttp 派生
只加不改共享件；`ScheduleRepository` / `MainActivity` / `ResumedTicker` / `RideScreen`
四文件耦合必须同 commit。
快趣确认弹窗动画打磨（2026-10-03，DESIGN §4.32；规则见 `.agents/rules/ebike.md`、
`.agents/rules/ui-common.md`）：用户报「开车还车的弹窗有突变跳动」——定位为 material3 1.3.0
的 `AlertDialog` 没有 compose 侧动画、compose Dialog 窗口也没配 `windowAnimationStyle`，
开与关都是硬切。新增 `ui/common/DialogWindowFade.kt`（`DialogWindowFadeEffect` 把
`DialogWindowFade` 窗口动画样式挂到 dialog window：淡入 + 0.86→1 放大 240ms / 淡出 190ms，
与动作区 `BAR_RISE_MS`/`BAR_FALL_MS` 同档；退场时窗口表面停在最后一帧直到播完），
`KvcxConfirmDialog` 与同页「结束计时」确认弹窗已接；勾选说明行从默认 spring 换成与动作区
同族的显式 tween（`FastOutSlowInEasing`）。模拟器录屏逐帧验证进出场都是渐进过渡；
全量 1281 例 0 失败。
请求链路降频与拟人化（2026-10-04，DESIGN §3.10 / §4.19 / §4.33 / §4.35；规则见
`.agents/rules/import-jw.md`）：目标是不影响体验地降低教务/一卡通风控面。四块——
① 付款码扫码检测（`YktPayWatch.pollIntervalMs`：前 30 秒 5 秒、之后 10 秒、20 秒，
15 分钟 180→约 54 条）与充值到账检测（`YktArrival.arrivalPollIntervalMs`：前 2 分钟 5 秒、
之后 15 秒、60 秒，30 分钟 480→约 100 条）两条 5 秒轮询改渐进；判定口径与窗口不变；
付款码页进页余额改吃 60 秒缓存。② 考试/选课同步的壳页学期缓存（`AutoSyncRules.shellTermCacheUsable`
12 小时 TTL、考试/选课共用判据），非 force 省一次壳页 GET；考试 URL 常量收口回 `JwUrls`。
③ 新增 `AutoSyncRules.interRequestDelayMs()`（200–600ms 随机），冷启动三条 sync、各翻页
循环、学业四维页、选课多学期循环接上——是放慢不是重试，失败不重试纪律不变。
④ 爬虫脚本会话持久化（`scripts/README.md` §3.1）：`jw_session.load_or_login` 三级降级
（教务会话探针 → CAS TGC 换票 → 完整登录），cookie 存 `scripts/out/jw_session_state.json`
（已 gitignore、与凭证同级敏感）；`fetch_power` token 同文件缓存 3000 秒；循环统一
`polite_pause`；新增 `fetch_all.py` 一条命令顺序抓全套。另删 `JwHttpSession` 两个无调用者的
课表直取方法。全量 **1287 例 0 失败**。
调课检测重立与首启自动导课表（2026-10-04，DESIGN §4.17；规则见 `.agents/rules/import-jw.md`
「调课检测」节）：本功能旧版 2026-09-19 上线、09-24 因「独立凭证配置 + 后台自动登录」的成本
被下线；重立的前提是这两个成本项已随统一会话层与 OkHttp 直取链消失。两块——
① **首启自动导课表**：引导登录成功后 `startBgSync` 第三路 `ScheduleSync.importIfEmpty`
只在**课表为空**时 OkHttp 直取理论+实验两页覆盖写库（教材钩子照挂），课表非空一律让位，
**绝不周期性静默覆盖**；三项 settle 后结果落盘，主界面在引导结束后弹一次「同步完成」
结果弹窗（成功带查看入口 / 失败给手动导入出路 / 消费即清；startAtJw 重进不写不弹）。
② **调课检测**（开关默认关，入口课表 hub「调课检测」行 → 处置页 `SCHEDULE_CHANGES`）：
基线 = 「上次与教务达成一致时的课表状态」（filesDir JSON，**不动 DB 版本**；导入/合并成功
收口推进、检查首跑用教务现状建立、**检查成功不推进**——与 `ExamSync` 相反），`ScheduleDiff`
指纹只含教务字段（本机配色/备注/自定义时间不参与），同名同 kind 一删一增配成「调整」，
冲突条目（用户改过的课）默认不勾；周期检查搭 `ScoreAlertReminder` **第四路**（不新开
WorkManager 任务）；重复打扰靠「已阅指纹 + 待处置缓存指纹」双闸；合并 = 处置页逐条勾选
后 `applyScheduleChanges` 事务内删/改/增（指纹匹配不上就跳过），**后台静默改课表仍是红线**。
新增 `ScheduleDiffTest` / `ScheduleSyncTermFromHtmlTest`，全量 **1302 例 0 失败**；
模拟器本次未能拉起（5183/5960 都不注册 adb）。
首启反馈修（2026-10-04 第二轮，用户点验后）：① **提醒间隔四链各自独立**（用户口径推翻
「共用一键」）——`score/exam/selection/schedule_alert_interval_hours` 四键，旧
`alert_interval_hours` 只作老用户读取回退（新键没写过沿用旧值，无需迁移标记）；调课默认
24h（`ScheduleAlertDefaults`，抓两张整页低频起步），其余默认 6h；`ensurePeriodicWork` 按
四链最小档排周期任务、各路用自己的间隔闸门；成绩设置页/考试页/选课页/调课处置页四处
各自绑定。② 结果弹窗「**查看不再消费**」——跳子页返回弹窗仍在，可接着看另一项，只有
「知道了」/点外部才清；弹窗加厚：失败行显示教务返回的原因、学业行带条数、底部补完成时刻。
③ 修调课通知与选课提醒的 **1010/3010 撞号**（requestCode 同则落点互相改写）→ 1011/3011。
提醒「有感」补课（2026-10-05，DESIGN §3.7；规则同步见 `.agents/rules/ebike.md` 通知节）：
用户报上课提醒 / 免费时长提醒「不弹横幅也没震动」。设备实测（Redmi K70 Pro / HyperOS）给出
根因分层：① 上课提醒渠道从未配振动（`mVibrationEnabled=false`）；② 免费时长渠道记录里
HIGH + 振动俱全、设置页那四个开关（悬浮通知/声音/振动/锁屏）照样默认关——**那是 HyperOS
对第三方应用的应用级通知策略，不认渠道创建参数，App 无公开 API 可改**；全局 heads-up 开着，
悬浮通知只能用户手动去系统设置开（提醒设置页与骑行设置弹层的「去系统设置」入口已有）。
App 侧落地两件：① 新增 `ui/reminder/ReminderVibration`（自 ebike `vibrateAlert` 抽成共享），
上课 / 作业截止 / 免费时长三类提醒**发出即直接震动**（`Vibrator` 不走通知策略，渠道开关
拦不住）；② 上课/作业截止渠道创建配振动并换 id 迁移（`class_reminder_v2` /
`homework_deadline_v2`，旧 id 删除，挂冷启动重排；真机 dumpsys 核对迁移已生效）。
全量 **1313 例 0 失败**；debug 包已装真机（正式版要等下次发版）。
小程序免费倒计时口径（2026-10-05，DESIGN §3.9；规则见 `.agents/rules/ebike.md`
免费时长提醒节首）：用户要求 ① 倒计时标注上写明是预估——骑行页倒计时块在小程序方式
（`caps.wechatScan`）进度线下常驻「倒计时为预估，实际以微信小程序显示为准」，账号方式
不写；② 改成点「打开微信扫一扫」后 5 秒再开始倒计时——新增 `EbikeFreeRide.SCAN_DELAY_MS`，
起点由 `onWechatScanClicked` 算好传给 `startFreeRideNotice(startAtMillis)`（账号开锁
走默认参数仍是当下），缓冲期内倒计时满格 15:00 不走（`remainingSeconds` 钳上限）、
免费结束与提醒点整体后移 5 秒，新增 `EbikeFreeRideTest` 缓冲用例。全量 **1314 例
0 失败**；debug 包已装真机。
通知强提醒开关（2026-10-06，DESIGN §3.3 通用设置；规则同步 `.agents/rules/ebike.md`
通知节）：用户确认直接震动能穿免打扰后要求收成显式开关——通用设置新增「通知」区
「通知强提醒」（`strong_reminder_vibration`，**默认关**，`HugeIcons.Notification03`），
上课 / 作业截止 / 免费时长三类提醒发出后的直接震动改为**开了才发**；其余提醒
（U净 / 余额 / 调课 / 选课）本来就没接直接震动，不受影响。偏好走独立单流
（`baseDisplayPrefs` combine 已满员，不进合并模型；`MeViewModel.strongReminderVibration`
考虑 USAGE_ALARM 出路）。全量 **1314 例 0 失败**；模拟器本轮起不来（jw35 崩，同 10-04）。
收件箱（2026-10-06，DESIGN §3.24 / §4.38；规则见 `.agents/rules/inbox.md`）：
应用内重要事实的留痕层——系统通知会被 ROM 吞（HyperOS 悬浮通知默认关），收件箱保证
「打开 App 一定能看到」。入口 = 今日页顶栏「+」下拉第三行（两行改三行），有未读时
「+」图标叠红点（`BadgedBox`）+ 菜单行未读数；收件箱二级页（`INBOX`）未读行右侧红点、
点行标已读并按 route 跳转、顶栏「全部已读」、长按进多选（`AppCard` 新增可选
`onLongClick` 参数）+ 底部批量条「标为已读 / 删除」，删除是软删（重投同键不复活）。
数据 = Room v18 `inbox_messages`（dedupeKey 唯一索引 upsert 去重：同键不重发、已删
不复活、未删重投重新置未读）；保留策略软删 30 天 / 已读 90 天 / 总量 200 条，冷启动
purge。五个挂点（ExamSync / ScoreSync / ScheduleSync / SelectionAlertReminder /
BalanceAlertReminder）全部落在既有结果点上，**收件箱不发任何网络请求**、写操作吞错
不反向打断业务链；`ScoreSync` 的 changes 与 `scoreAlertEnabled` 解耦（恒计算，通知层
自判开关）；余额消息是状态型（回升即整条移除，失败绝不误删）；**通知总开关只拦发通知
不拦核对与入箱**（2026-10-06 修正：`ScoreAlertReminder`/`BalanceAlertReminder` 入口
原先 `areNotificationsEnabled()` 早退，通知全关收件箱跟着失效；选课已发键在通知被
系统整体关闭时也落——否则重排闹钟把已过去的点反复排成立即触发的空转）。
时刻型提醒不入箱。
新增 `InboxRulesTest` 8 例，全量 **1322 例 0 失败**；碰网络结果路径与 Room 迁移。
成绩/考试变动提醒默认开（2026-10-06，DESIGN §4.33）：`score_alert_enabled` /
`exam_alert_enabled` 默认值 false → true（偏好两处 + 页面 `collectAsState(initial =)` 两处
同改，否则首帧闪一下关态）；显式关过的用户落盘 false，不受默认值影响，无需迁移。
口径：出分与考试变动是用户要的事实，默认就该收；**选课提醒仍默认关**（通知是打扰型
能力，两者不同口径，别顺手统一）。全量 **1325 例 0 失败**；随 2.2.0 发版。
会话被顶与教材丢失（2026-10-06，用户报「从教务导入后课程详情不显示教材，debug 有 release 没有」；
DESIGN §4.27「会话被别处登录顶掉」/ §4.31）：根因是**强智单会话**——同账号在别处登录后，教务对
业务请求回一段裸 JSON `{"flag1":3,"msgContent":"您的账号在其它地方登录"}`（HTTP 200、URL 不变），
而登录页判据只认 `userPassword`，于是它被当页面数据交给解析器；教材抓取因此抛「教务返回 code=-1」
并被**丢掉**（`syncForTerm` 的 Result 在唯一调用点没人接），表现成「导入成功但没教材」。
三处落地：① `JwHttpSession` 新增 `looksLikeSessionConflict`（业务文案 + `flag1` 同时命中）与
`JwHttpException.SessionInvalid`，`CasSession.fetchHtml` 对会话被顶**作废会话重登一次**
（只重试一次、不动防锁号计数）；② WebView 探针补 `conflict` token +
`JwImportDiagnosis.sessionConflict`（不再把裸 JSON 摊给用户）；③ 教材链路补可见性与可恢复性——
`TextbookSync` 结果落 `Log.i`、导入失败补一条气泡「教材未同步」、**冷启动补抓**
（`backfillIfMissing`：该学期教材为空 + 距上次尝试 ≥6h，闸门 `AutoSyncRules.textbookBackfillDue`）。
`JwImportResultBus` 顺手从单值 StateFlow 改成排队（`OutcomeQueue`），否则第二条消息会把还没
消费的导入气泡顶掉。同时订正一处**文档与实现不符**：教材触发点只有「教务导入」与「冷启动补抓」，
**「导入 JSON 文件」不抓**（它从备份的 `textbooks` 段恢复）。新增 16 例，全量 **1341 例 0 失败**。
AI 调课（2026-10-06，DESIGN §3.25 / §4.39；规则见 `.agents/rules/ai-schedule.md`）：
课表 hub「使用」区新入口——粘贴调课通知（可附诉求），模型用 **function calling**
（工具 `propose_schedule_changes`）返回结构化改动，以**差异卡**呈现（与调课处置页共用
`ui/common/ScheduleChangeCard`，措辞切「建议」口径），逐条勾选后落库。四块——
① **纯逻辑层**：`AiSchedulePrompt`（编号清单 1 起始 + 教师名脱敏）+ `AiScheduleTool`
（工具 schema、`arguments` → `ScheduleDiff.Change`、严格校验：越界编号/缺字段/非法取值/
前后一致逐条丢弃，不整批失败）+ `AiScheduleConfig`（预填 `https://api.deepseek.com/v1`
与 `deepseek-flash`，只需用户填 Key）；② **写路径拆分**：`applyScheduleChanges` 的核心
事务体抽成唯一实现 `applyChangeSet`，原方法 = 它 + `refreshBaselineFromDb`（行为逐字不变），
新增 `applyLocalChanges` = 它本身——**AI 改动与手动改课同语义，不推调课基线、不清已阅指纹**；
③ **网络与凭证**：`data/ai/AiScheduleClient`（`tools` + 指定函数名 `tool_choice`；**DeepSeek
端点必须带 `thinking: disabled`**——思考模式默认开、named tool_choice 在思考模式下 400，
且该字段只对 deepseek 域名发；无日志拦截器）+ `AiCredentialStore`（`secure_ai.xml`，
已登记进两份备份排除 xml 三段）；④ **UI**：`AI_SCHEDULE` 主页面 + `AI_SETTINGS` 配置页
（地址/模型/Key/脱敏/测试连接 + 「从上游获取」拉 `/models`），通用设置新增「AI」区入口，
首次使用弹隐私确认（`ai_privacy_ack`），教师名脱敏默认开。
新增 `AiScheduleToolTest` 13 例 + `AiSchedulePromptTest` 9 例，全量 **1363 例 0 失败**。
AI 调课二轮：日期覆盖 + 流式 + 思考档位（2026-10-06，用户实测反馈「校历调休通知答出
『不做改动』」；DESIGN §4.40 / 规则见 `.agents/rules/ai-schedule.md`）：
**根因不是模型能力**——课表是「周次 × 星期」两维、通知按具体日期发，而 prompt 里既没有
学期日历、工具也没有表达日期安排的字段，模型只能诚实回「没有需要改动的课程」。四块——
① **日期覆盖新领域概念**：`domain/ScheduleOverride` + `ScheduleOverrides`（日期 ↔ 槽位换算、
摊平成 `槽位 → 指令`、文案），Room v19 新表 `date_overrides`（`note` 特意不给 Kotlin 默认值
绕开 DEFAULT 坑；迁移 SQL 与 Room 生成的期望 schema 逐字对齐）；`follow`（照搬某槽位的课）/
`suspended`（放假区间）两类，**渲染层叠加、不写 courses 表**；② **口径收敛到唯一入口**：
`ScheduleCalculator.coursesOnDay` 加 plan 参数，今日页 / 周网格（`weekColumns` 逐列）/
桌面小组件 / 上课提醒（`ReminderPlanner`）/ 小组件边界闹钟 / 日历导出（`expandEvents`）
全部改走它——**FOLLOW 不递归**（源槽位自己停课不影响借来的那天）；网格表头对放假列标
「放假」、补课列标「补课」；③ **prompt 补学期日历**（今天 + 每一起止日期，模型换算周次的
唯一依据）+ 工具加 `overrides` + 强制分野说明；AI 页多一类「日期覆盖」勾选卡；
④ **流式 + 思考档位 + 思考过程可见**：`chatStream` 返回 `Flow<StreamEvent>`
（`reasoning_content` / `content` / 分片 `tool_arguments`），偏好
`ai_thinking_effort`（none/low/high/max，默认 none）+ `ai_show_reasoning`（默认关）；
**思考档位与强制工具调用互斥**——DeepSeek `thinking` 默认开而指定函数名 `tool_choice`
在思考模式返回 400，故档位开启时退 `tool_choice: auto` + 正文 JSON 兜底，
`thinking` / `reasoning_effort` 只对 deepseek 域名发。
新增 `ScheduleOverridesTest` 12 例 + `AiScheduleToolTest` 4 例，全量 **1379 例 0 失败**。
AI 调课三轮：改对话式 + 图片（2026-10-06，用户实测反馈「不是流式」「看不出课表会变成
什么样」「思考顶掉结果」「课表重建后放假/补课能否点开看」；DESIGN §3.25 / §4.39 / §4.40，
规则见 `.agents/rules/ai-schedule.md`）：
① **改对话式**——一次会话就是一段对话：粘贴通知（文字或**截图**）→ 结果气泡（结论一句 +
徽章组 + **迷你课表** + 动作行）→ 确认层**从底部升起**（前/后切换、逐条勾选，勾选写回
消息卡）→ 应用；不满意直接**追问**（模型每轮给 3 条建议按钮，追问只带「上一轮结果 +
这条追问」，模型只输出差异）；**可撤销**（`ScheduleUndo` 倒序反向事务，课表 + 覆盖同一
事务，不碰基线）。② **图片**：选图 / 剪贴板 → `AiImageStore`（会话级，1600 / q80 /
强制 JPEG / 上限 4 张）→ base64 成 data URI；**`ImageImport` 补 EXIF 方向转正**（笔记
附件与课表背景图一并受益）；能力判定探测不了，故为**默认关**的显式开关
`ai_supports_image` + `looksVisionModel` 打勾建议（`deepseek-flash` 官方多模态，
在建议名单；网关是否吃图以实测为准）；带图时 AI 先给「**我读到的通知**」核对块（OCR 错了能改）；
图片类失败给「去掉图片重发」。③ **打磨**：思考过程限高 132dp + 内部滚动 + 自动贴底；
滚动跟随可打断（贴底推导，不猜手势）+「回到最新」；停止键与发送键同位 morph；
失败留在气泡里按类型给出路。新增 `AiScheduleChatTest` 15 例 + `ScheduleUndoTest` 6 例，
全量 **1413 例 0 失败**；debug 包已装真机（`b75e0467`）。
AI 调课四轮：三路独立复查 + 逐条修复（2026-10-06，**复核驱动**）：
AI 状态机 / 数据事务隐私 / 非 AI 那批各一路，共报 5 条 P1 + 约 20 条 P2，**全部核验后逐条修**。
P1 五条——① 追问文字在「上一轮答空」时被整段丢掉（`isFollowUp` 误由 `previous != null` 决定）；
② `parsePartial` 的增量结果**从没接到渲染上**（结果卡只在非流式渲染，等于白跑）；
③ 模型回抄的脱敏占位名「教师1」被**写回数据库**、真实姓名静默丢失；
④ 连点两次「应用」→ 日期覆盖写两行 + 回执被覆盖 → 撤销假成功；
⑤ 预览与真应用口径不一致（`isCustomTime` 只在真应用里被清）。
其余修复：图片链路（`ai_img/` 与各 store `.fallback.xml` 进备份排除、`onCleared` 补清理、
`Mutex` 串行化、LRU 缓存）、网络（非 SSE 响应体 / 流中 error / `finish_reason` 截断三类
「静默成功」、`tool_calls` 只认 0 号、413 算图片类、`callTimeout` 600s、`InterruptedIOException`
文案）、`stop()` 改 `callbackFlow` + `call.cancel()`（**真能关 socket**）、`extractJson` 取第一个
可解析对象、`rejected` 在 UI 可见、重试走 `AiTurn` 快照、撤销加状态闸与 `timetableId`、
`CancellationException` 穿透、`AiImageStore` 串行化、大字体与命中区、VM 去掉 9 处 `runBlocking`；
非 AI 那批：教材「抓到空也算成功」（`textbook_fetched_term`，否则每 6 小时永远重试）+
空结果不清旧数据、重登独立冷却窗口 + `clearCookies` 进锁、`OutcomeQueue.consume` 幂等、
同源 fetch 的「会话被顶」判据、`duplicateTimetable` 复制日期覆盖、周次上限只约束模型给的值。
新增 `AiScheduleChatTest` +10 例、`ScheduleUndoTest` 重写为 10 例（含状态闸）、
`AutoSyncRulesTest` +1 例、`JwImportResultBusTest` +2 例，全量 **1431 例 0 失败**。
各功能的最新口径见 DESIGN §6；逐条实现史见 `docs/devlog.md`（仅本地）；
未闭环的点验项见下表。

检查更新（2026-10-06，DESIGN §3.3；发版流程见 `.agents/skills/publish-release/SKILL.md`
Step 7.5）：关于页「应用」区新增「检查更新」行，**仅手动触发**——拉公开仓库镜像
`docs-public/update/latest.json`（**不请求 api.github.com**，该域名国内直连经常不通）。
清单含版本 / APK 资产名 / SHA-256 / 更新日志 / 镜像前缀；版本比对用三段数字（兼容
debug 后缀），镜像链接 = 前缀 + GitHub 直链。有新版本弹窗：更新日志 + SHA-256（点按
复制）+「国内镜像下载 / GitHub 下载」双通道（浏览器下载，App 不落 APK）；已最新 / 失败
走 Snackbar。发版流程新增 Step 7.5：每次发版同步写清单（`mirrorPrefix` 可随时单独改、
不发版）。同日修两处：① 旧「检查更新」首源 raw（无代理经常不可达）+ 单源网络异常中断
整轮轮询，表现为「不开代理基本必失败」——源清单收口 `domain/RepoMirror`（gh-proxy 加速 →
jsdelivr → raw 兜底、逐源容错），**校历镜像同步接入**（原单源直连 raw，可达性全看网络
脸色）；② ref 统一真实分支 `main`（旧写 `master`：raw/jsdelivr 会按默认分支旧名**兼容
解析**、GitHub API 不认——不依赖兼容行为）。**同日修校历页手动刷新无反馈**：原设计成功
全静默（用户点刷新以为没反应），现进度圈 +「已更新 / 已是最新 / 失败」提示，冷启动仍
静默。新增 `UpdateCheckTest` 10 例 + `RepoMirrorTest` 4 例，全量 **1452 例 0 失败**。

校历自动化（2026-10-07）：复核三条学校侧入口——智慧校园移动端仍是微信一次性 JWT
（09-30 结论成立）、PC 门户的 `/calendar_portal/getCalendar` 是空的个人日历、
**教务处官网通知公告公网免登录发布高清原图**（3438×2552 jpg，与镜像现图同内容）。
新增 `scripts/sync_calendar.py`（第 10 个正式脚本；免登录免凭证：扫 `/xxgk/tzgg.htm`
及翻页 → 抓文章 `orisrc` 原图 → 学年 + 文件名 + sha256 三者比对 → 变更才落盘 + 改写
index.json + 自动 commit & push，分支非 main 拒绝；`--dry-run` 完全只读、`--from-file`
为栏目改版救急通道），镜像由脚本切到教务处高清版（首跑即完成一次全链路：发现 → 下载 →
提交推送）；客户端补 `CampusCalendarRules.matchesLocal`（学年 + 文件名**都**对上才复用
缓存——同学年换文件 png→jpg 也会重下）。至此**年度维护全自动**：明年新学年发布后由
每周计划任务自动上架、全端免发版换图，维护者零手工。全量 **1453 例 0 失败**。

同日再简化（用户拍板「要最方便简单」）：**App 直接读教务处官网为主源**（新
`domain/CampusCalendarSchoolRules`，免登录直取高清原图；仓库镜像降兜底、
`sync_calendar.py` 退居应急工具，**计划任务确定不挂了**）。排查过强智教务：有
「教学周历查看」（`/jsxsd/jxzl/jxzl_query`，周次 × 日期表，需登录、非海报内容），
不采用。新增 `CampusCalendarSchoolRulesTest` 5 例，全量 **1464 例 0 失败**。

AI 调课界面 v2 重构（2026-10-07，DESIGN §3.25；规则见 `.agents/rules/ai-schedule.md`）：
用户反馈「太乱、不统一、排版丑、交互怪」后的整页重构——形态仍是单页工作单（不做对话），
做的是收口与修根：① 视觉全部走项目统一件（`AppCard` 唯一卡片 / M3 按钮与分段控件 /
页面横向 16dp / 一套徽章色映射 / 软底块 / 声明用 `AlertDialog` / 看大图复用
`ImageViewerDialog`）；② 交互修复四个根因——READY 不再自动折叠（首版把勾选主战场藏了，
折叠只在 APPLIED）、补「重新分析」次按钮（首版 READY 无处重来）、回执跨轮保留
（`proposalSeq` 判归属，撤销入口不随新分析消失）、去重去假动作（清空/重试/撤销各留一处；
删「微调」假按钮与「已发送的通知」双份展示；预览改全宽单栏前/后切换，周次 = 改动涉及周
∪ 覆盖周）。`ui/ai` 拆成 Screen / NoticeCard / ProposalCard 三件；配置页「能力」区
文案墙收短。新增 `AiWorkflowTest` 6 例，全量 **1464 例 0 失败**；debug 包已装真机。

AI 调课记录与动效补轮（2026-10-07，DESIGN §3.25 / §4.39）：① **「调课记录」页**（AI 页
顶栏历史图标 → `SubpageScreen.AI_HISTORY`）——本机留痕跨会话（`AiApplyHistoryStore`，
filesDir JSON，CAP 50、随备份走），**栈式撤销**：只允许撤最新一条未撤销（UI / VM 双处
校验同一条判据）；`ScheduleCourseRow` 补 `id` 与自定义时间三件套（**不进差异指纹**，
撤销按 id 删 / 覆盖并原样还原）；主页回执带 `historyId`，resume 时与留痕互相同步。
② **动效补齐**：结果区逐段淡入展开（骨架 / 统计 / 明细 / 预览）、折叠摘要 ↔ 展开体、
思考过程展开、回执条出现、失败卡 ↔ 提案卡 Crossfade。③ 细节：一处都没落库的应用不算
成功（留待确认 + 报条数，不再折叠成「已应用 0 处」）；配置页说明文案收短；勾选行整卡
可点（共享 `ScheduleChangeCard` 加 `onRowToggle`，调课检测页同享）；提示条垫开贴底
动作条。新增 `AiApplyHistoryStoreTest` 6 例 + `AiWorkflowTest` +1 例，全量
**1488 例 0 失败**；debug 包已装真机。

AI 调课复查轮（2026-10-07，用户要求「审查代码及功能完整性、健壮性」；两路独立复查后逐条
修，DESIGN §3.25 / §4.39）：① **课表删除后的撤销防护**——`ScheduleRepository.undoApplied`
对已删课表整批按跳过兜底（反向写会往不存在的 timetableId 塞孤儿行）、两处 VM 撤销前用
`timetableIds()` 先拒绝并说清、`latestUndoable(list, aliveIds)` 让已删课表的记录**不挡栈**
（行上标「课表已删除」）。② **APPLYING 相位接线**（原先枚举不可达）：apply 进事务即置位，
底栏「应用中…」不可点、analyze/retry 被挡；零落库/失败恢复原相位。③ **撤销防重入**：
两处 VM 加 `undoing` 闸，**先扣闸再进第一个挂起点**。④ 「去掉图片重发」改为把输入区
截图一并移除（旧的 `imagesDisabled` 是写了没人读的死代码）。⑤ REMOVED 撤销补
`mergeKey` 闸（用户加回同门课后不再插成两行）。⑥ 细节一批：模型回 `""` 的 position 当
未提供；失败卡补「已解析 N 处」；统计条改 `FlowRow` 防大字体裁 chip；折叠/展开退场期
禁点防「弹回」；命中区补齐；留痕写盘失败落日志；`addImage` 补 CE 穿透。新增
`AiApplyHistoryStoreTest` +1 例（已删课表不挡栈）、`ScheduleUndoTest` +1 例、
`AiScheduleChatTest` +1 例，全量 **1491 例 0 失败**；debug 包已装真机。

AI 调课「同一通知重发仍报差异」修复（2026-10-07，用户报「调完课再发同一通知还有差异、
『应用前』还是调课前的」；DESIGN §3.25 / §4.39 / §4.40，规则见 `.agents/rules/ai-schedule.md`）：
根因三件——① **模型看不见已生效的日期安排**（发给它的只有课程行，`date_overrides` 不可见，
同一通知重发被原样重新解析）：`userMessage` 补【已生效的日期安排】段
（`AiSchedulePrompt.overridesBrief`，空写「无」）+ `parse/parsePartial` 对等价提议确定性
去重（`ScheduleOverrides.sameEffect`，进 `rejected` 注明「已生效，无需重复」，不静默吞）+
`addOverrides` 写入侧幂等，三处同一判据；② **预览两栏都没算已生效覆盖**：「应用前」
看着像调课前 →「应用前」= 课程行 + 已生效安排、「应用后」= 现状 + 本轮勾选（同一天
本轮为准）；③ **同槽位胜负被排序破坏**：`DateOverrideDao` 的 `ORDER BY fromDate` 让
「后应用的赢」变成「日期晚的赢」，后应用更早/更宽区间会输给旧覆盖 → 改 `ORDER BY id ASC`
（写入顺序 = 应用顺序，撤销删行后旧覆盖自动复活）。新增 `sameEffect` / 已生效去重 /
prompt 段 3 个用例，全量 **1496 例 0 失败**；debug 包已装真机。

AI 调课简化轮（2026-10-07，用户质疑「会不会想得太复杂」后的需求定位复盘；规则新增
「三层防线总览」节）：定位写死——**把学校用「日期语言」写的调课通知，翻译成本机课表的
一次可撤销编辑**；模型是翻译器、App 是编辑器、用户是审批人；三条公理 = 模型不可信
（引用式协议 + 本机取真值 + 逐条校验丢弃可见）/ 课表「周次×星期」与通知「日期」两套
语言靠日期覆盖换算 / 写入必须过预览+确认+可撤销。**不采纳**「模型直接改整份课表、
App 做 diff」——复述未改动的行必然产生假差异/静默删除。本轮减脂：删 `suggestions`
整链（schema / 清洗 / `Result` 字段 / SYSTEM 文案 / 测试）与追问链（`previousJson` /
`previous` 参数 / SYSTEM 追问段 / `requirement` 诉求段）；`userMessage` 收成事实四段；
`courseJson` / `indexOf` 无调用者一并清。无用户可见行为变化，全量 **1489 例 0 失败**
（净减 7 例测试）；新包待设备重连后补装。

培养方案（2026-10-07，DESIGN §3.26 / §4.41；规则见 `.agents/rules/import-jw.md`）：
我的 → 学习 → 培养方案——教务「培养方案」明细页（`/jsxsd/pyfa/topyfamx`，服务端渲染整页
HTML）抓**全量课程设置**（84 门级别：编号/名称/性质/属性/学分/五格学时/**开设学期 1–8**），
按学年（大一~大四）→ 学期铺开，与学业完成情况互补（一个看计划、一个看达成）。Room v20
新表 `curriculum_courses`（全局归属学生、不挂 timetableId、整体替换）；`CurriculumSync`
纪律同学业（与成绩共用 7 天闸门 / 先校验再写库 / 失败静默 / 成功才记日期），触发点三处：
首启登录成功（静默、不进结果弹窗）、冷启动链（学业后）、进页自动 + 手动刷新；备份加
`curriculumCourses` 段（非空才整体替换、方案名不进备份）。解析两坑：**学时 5 子列 DOM
class 全同**只能按行内顺序认（理论/实践/讲座/实验/总）；**方案名在「三、课程设置总表」
切片之外**要全页搜（其余口径与 scripts 侧 `fetch_curriculum.py` 互证一致）。新增
`CurriculumParserTest` 8 例 + `CurriculumRulesTest` 5 例 + `ImportJsonShapeTest` +3 例，
全量 **1480 例 0 失败**；debug 包已装真机。

调课检测误报修复（2026-10-07，DESIGN §4.17；规则同步见 `.agents/rules/import-jw.md`）：
用户报「误报上游有差异、重新导入后仍显示、教务里根本没变」。根因两条——① 理论/实验课表的
**HTML 直取解析**（`parseFromHtml`，调课检查与首启导课表共用）每格只取**第一个**
`li.courselists-item`，而同格多课块是常态（单双周拆块 / 回课时换教室；实测用户课表 35 格
里 9 格多课块、34 块只解析出 24 块）。基线是注入脚本全取的结果，检查链拿「少 10 块的
教务」比对 → 9 门课被误报「教务删除」，且**重新导入永远修不好**（每次检查都重解析）。
逐块切法收口到 `CourseBlocks`（两页共用），修后 App 解析产物与 `scripts/fetch_courses.py`
/ `fetch_lab_courses.py`（真机口径）在同一份真实页面上**逐条一致（33+12 条，0 差集）**。
② 待处置缓存只进不出：基线推进（导入/合并）与「检查无差异」都不清缓存 → 处置页一直挂着
已经不存在的变动，而「合并」会照指纹把它们套到课表上（静默删课）。现在两处都清
（同课表才清）。新增契约用例 2 例，全量 **1496 例 0 失败**；含此修复的 debug 包已在真机
（本次装机由并行会话的构建循环带出，已核对装机 APK 与本地构建 md5 一致）。

调课检测复核轮（2026-10-07，用户要求「审核其他代码完整性，确认无其他 bug，比如有差异但
没检测出来」；DESIGN §4.17，规则同步见 `.agents/rules/import-jw.md`）：解析口径跨学期
互证（2025-2026-2 / 2026-2027-1 两学期理论+实验与 `scripts/` 逐条对，0 差集）+ 全链路
走查，共修 6 处（都属「漏报 / 假报 / 静默丢数据」家族）：
① **单/双周标注被丢**（`parseWeeks`）：教务页有 `1,11单周` / `2,6双周` 形态（实测
2025-2026-2 两处），带标注 token 此前被 toIntOrNull 拒掉、整段周次静默少几周（课少排）；
现按奇偶过滤 + 脏 token 抠数字兜底，`scripts/fetch_courses.py` 的 `parse_weeks` 同步改；
② **完全重复的课块**（教务页偶发把同一课块渲染两遍）：理论两条解析路径去重（此前会导入
两行一模一样的课 → 双份提醒 / 双卡）；
③ **实验页学期不校验**：实验页也有 `select#xnxq01id`（单引号形态），新增 `labTermError`
与理论页互校，不一致 / 抽不到都判失败——教务若忽略学期参数，混两个学期比对会刷一屏假差异；
④ **整类课「有 → 零」闸门**（`vanishingKindError`）：解析器与页面脱节时硬比对会把整类课
报成「教务删除」，勾选合并即批量删课——现在判 Failed、不报差异、不落检查时刻；
⑤ **合并路径把本机条目洗进基线**：`applyScheduleChanges` 原用整张课表重建基线，手动加 /
AI 加的课会在下次检查被报「教务删除」、勾选合并即删掉用户自己的课；改为
`ScheduleDiff.baselineAfter`（旧基线 + 已应用改动），与「AI 改动不推基线」同一条纪律；
⑥ **`importJson` 基线推进写太早**（在课程写库之前）：恢复完基线存的是恢复前的旧课表，
一查全是假差异——移到课程写完之后。另：`importIfEmpty` 在实验页结构不可信时改报失败
（静默导入会整批漏掉实验课且用户看不到分项条数）。新增契约用例 11 例，全量
**1524 例 0 失败**；APK 已就绪、设备断连待重装（无线调试掉线，与并行会话同因）。未修、
留观察：处置页「检查被跳过」文案把「课表没有学期号」也归进「开关未开启或间隔未到」；
JSON 恢复后基线 = 恢复内容（含本机条目）的语义模糊。

教学日历开学日 / 学生评教 / 实习基地（2026-10-07，DESIGN §3.27 / §4.42–§4.44；规则见
`.agents/rules/import-jw.md`）：三个新教务数据源一轮落地——① **开学日自动设置**：导入课表
写库成功后抓「教学日历」（`/jsxsd/jxzl/jxzl_query?xnxq01id=<学期>`，学期参数真实生效），
把第 1 周周一写进目标课表的 `semester_config.startDate`；解析的坑全在「工作日列是裸日号、
周末列才是 `MM月DD日`、全表无年份」——年份从学期号推导 + 周六格必须真是周六 + 周一/周二
裸日号交叉校验，响应学期 ≠ 请求学期一律放弃（不猜学期）；失败静默、值没变不写库不弹泡，
口径「教务为准」（用户仍可在课表设置手改，下次导入会再校准；**不动总周数**）。
② **学生评教直达**：评教没有静态 URL（`toZlpt_11319('jwxsjdp')` = POST 取 token →
`jiaowupj.juwp.edu.cn` 的 SPA，跨主机、JWT 约 4 小时）——新增 `JwImportMode.TeachingEval`
复用教务 WebView：自动登录落 xsMainV 后注入 `JwTeachingEval.launchJs()`（页面自带 jQuery
取 token → 同窗口 `location.href`），15s 原地看门狗 + 刷新重试，**App 只导航不代评**；
平台域 URL 在 9 处日志点脱敏，token 不落 logcat（`redactForLog`）。③ **实习基地查看**：
layui 接口 `/jsxsd/sxsxgl/sxjd.do`（**实测忽略一切分页参数、固定只回前 10 条**，见复查轮），
原生只读页按类别分组 + 弹层全字段，**不落 Room**（12 小时快照缓存，见复查轮）。入口都在
「我的 → 学习」新分组「教务服务」。新增 `TeachingCalendarParserTest` 10 例 +
`InternshipBaseParserTest` 6 例 + `InternshipBaseRulesTest` 2 例，全量 **1507 例 0 失败**；
debug 包待设备重连后补装。

一卡通支付反馈一轮（2026-10-07，DESIGN §3.10 / §4.19 / §4.24；规则见
`.agents/rules/life-power.md`、`ui-common.md`）：用户要求「以支付金额大字显示为主」，扩成四块——
① **弹窗改版**：`CampusPaymentDialog` 以**扣款金额大字**为主（新建 `ui/common/AmountText.kt`
的 `AmountHeroText` = `displaySmall` + SemiBold，金额样式唯一口径），商户 · 交易时间与交易后
余额退成 bodyMedium 次级小字（商户与时间都缺就不占行，旧兜底口径保留），说明再低一档；
「充值成功 / 正在确认充值结果」两张弹窗同批换同款（字段与文案语义不变）。
② **宿主补口**：扣款结果收敛到 `CampusPayResultDialogHost`（collect + consume + 弹窗），挂
`MainActivity` 根层（四个 Tab 之上）+ 校园卡设置页两处——此前生活页与设置页各自自持，今日页
「+ → 付款码」出码付款后没人消费结果，弹窗会一直挂着、到下次打开生活页才迟到弹出
（`PayCodeResultBus` 注释与 §3.10 早已写明今日页也要弹）。
③ **电费充值结果步补金额**：`AcceptedStep` 此前只有状态文案（金额只在密码步与农行等待步
出现），现在 `¥X` 大字 + 原状态 / 入账文案不动。
④ **命中即震**：`PayCodeViewModel` 判定到扣款那一刻直接 `ReminderVibration.vibrate`——页面层
LaunchedEffect 会被 STARTED 闸门挂起、晚到亮屏才响，VM 里才是「钱刚动」的时刻；不挂开关
（即时操作反馈，不是打扰型提醒）。该弹窗只在扫码消费后才出现，无模拟器触发路径。全量
**1527 例 0 失败**；设备不在线，debug 包未装。

三源功能复查轮（2026-10-07，三路独立复查 + 逐条修复）：共报 2 条 P1 + 5 条 P2 + 一批
P3，全部核验后逐条修——① **实习基地分页是假象**（P1）：`sxjd.do` 忽略一切分页参数、
固定只回前 10 条，原「按页循环拿齐 count」把同一页拿两遍，真机上就是「每张卡重复一份」；
改单次抓取 + 界面注明差额（10/17），对照实证：同会话成绩接口 `cjcx_list` 翻页正常。
② 同链路补缓存：**12 小时快照**（`InternshipSnapshotStore`，filesDir JSON、不进备份）+
顶栏强刷 + 更新失败宁旧勿空（带 `updateFailed` 标记），进页默认零网络；解析补
`data:null` 容错、`count` 缺失按 -1。③ **评教错误浮层泄 token**（P1）：
`JwImportDiagnosis` 正文拼原始 URL，平台域失败会把 JWT/xlgToken 显示在错误浮层——
诊断参数改收脱敏串、SSL 日志补脱敏（第 10 处）；15s 看门狗加「尝试代次」防刷新后假告警；
评教底部空条去掉。④ 首启开学日竞态：`AutoImportResult.Imported` 携带 `timetableId`，
钩子不再用「apply 时的当前课表」近似。⑤ **评教移动端页**（`jiaowupj:81/#/jspxxsp`）
实测其启动认证拒绝教务 token（401）、CAS 配置指向外校域名——**暂不接入**（调查记录见
§4.44 / import-jw.md）。**复查二轮**（对修复本身的复查）：装载器补 `Dispatchers.IO` 收口
（页面从主线程调、快照与凭证闸门都是阻塞 IO，P1）；空结果口径收口（`count≤0` 权威空写
快照、`count>0` 零行按结构异变走宁旧勿空）；`JwImportDiagnosis` 正文内嵌 url 统一过脱敏
（自防线，防未来调用方传原文）；评教看门狗补 `autoNavDone` 条件（刷新到重注入的间隙不再
命中）；`internship_bases.json` 补进两份备份排除 xml（此前「不进备份」只是口径、实际会被
云备份带走）。新增 `TeachingCalendarParserTest` +3、`InternshipBaseParserTest`
+3、`InternshipCacheRulesTest` 3 例，全量 **1527 例 0 失败**；debug 包待设备重连后补装。

### 待真机点验（未闭环）

2026-09-29 起累计 26 项改动只过了编译 + 单测（全量 1452 例 0 失败），真机点验一直挂着
（AI 调课这几条连模拟器冒烟都没有——改动碰网络 / 凭证 / 备份规则 / Room，按
「装机与验证路径」不属于「够小」）。**以后新增的待点验项只写进这张表**，不要再往上面的
流水里塞「真机点验待用户执行」
——那正是这张表要替掉的东西。验完一行删一行。

| 引入 | 功能 | 要验什么 |
|------|------|----------|
| 09-29 | 快趣出行三处调整 | 登录表单的「忘记密码」出路、还车点用运营方点位名、免费时长提醒震动 |
| 10-01 | 快趣 开锁 / 还车确认弹窗 | 小屏与大字体档位下按钮不被挤出；「以后开锁不再确认」勾选与恢复入口 |
| 10-01 | 快趣 地图重绘 | 拖动 / 刷新后车标与还车点照常出现、点车辆行高亮正常 |
| 10-01 | 快趣 开锁后掉帧 | 开锁后是否还掉帧、面板升起是否照旧 |
| 10-01 | 快趣 点地图标记滚列表 | 真机手势下的滚动落点（模拟器 jw35 已验证三种场景） |
| 10-01 | 选课轮次时间解析 | `xksj` 格式与提醒落点——**要等选课窗口期才有真样本** |
| 10-01 | U净 登录与下单 | JWT 有效期、学校机型 `moduleType`（定 P3 要不要 BLE 通道） |
| 10-02 | U净 投放加购 | 下单请求体带投放档位后的实际结算 |
| 10-02 | U净 扫码 → 选档 → 下单 | 全链路（要真机登录） |
| 10-02 | 顶栏「+」扫一扫 / 付款码 | 三种去处分流；机身码格式到手后把判据收敛成白名单 |
| 10-02 | 趣智开水面板 | 用水中 / 结算 / 失败三态与动效手感 |
| 10-02 | 体检优化三批 | 网络层与支付链路（支付宝 8000 单列 Pending、崩溃日志导出） |
| 10-03 | 快趣确认弹窗动画 | 真机进出场是否渐进（模拟器录屏已验证） |
| 10-04 | 请求链路降频 | 付款码扫码反馈、充值到账节奏、考试 / 选课学期显示、提醒照常 |
| 10-04 | 调课检测 + 首启自动导课表 | 首启静默导入与结果弹窗、处置页空态与开关、调课通知与合并 / 忽略往返 |
| 10-04 | 首启反馈修 | 四链提醒间隔各自独立、结果弹窗「查看不再消费」 |
| 10-05 | 提醒「有感」补课 | 下次课前提醒是否震动（横幅要在系统设置里手动开「悬浮通知」） |
| 10-05 | 小程序免费倒计时 | 点「打开微信扫一扫」后 5 秒才起算、说明句只在小程序方式出现 |
| 10-06 | 通知强提醒开关 | 开 / 关后提醒分别「直接震」/「只走系统渠道」 |
| 10-06 | 收件箱 | 角标亮起、消息内容与教务实际比对、跳转 / 已读 / 多选删除往返 |
| 10-06 | 会话被顶 + 教材补抓 | ① 在别处登录教务（另一台设备 / PC 跑 `scripts/` 脚本）后，导入页应给「账号已在其它地方登录」而不是裸 JSON；② 教材抓取失败时导入后应补一条「教材未同步」气泡；③ 该学期教材为空时冷启动补抓能补上（logcat 看 `TextbookSync`） |
| 10-06 | AI 调课 | 填 DeepSeek API Key →「测试连接」通过 → 粘贴一条真实调课通知 → 差异卡内容与通知是否对得上 → 勾选应用后课表确实变了、**且调课检测基线没被推**（改完课再手动「重新检查」，AI 改的那门课不该被当成教务差异报出来）→ 401/404 的分类提示是否说清问题 |
| 10-06 | AI 调课二轮：日期覆盖 | 粘贴一条**校历调休**通知（如「9月20日按第5周周一上课」「9月25日-10月7日放假」）→ 应出「日期覆盖」卡而不是「不做改动」→ 应用后：今日页/周网格/桌面小组件/上课提醒四处对同一天给出一致的课 → 放假那周网格表头显示「放假」→ 日历导出里放假日不出现、补课日出现借来的课 |
| 10-06 | AI 调课二轮：流式与思考 | 「思考档位」关时是否仍能出结构化结果（强制工具调用）；开时是否先出思考文本再出结果；「显示思考过程」开关切换是否立即生效；流式过程中页面是否边出边显、不卡 |
| 10-06 | 检查更新（关于页） | 无代理点「检查更新」应能拉到清单（多源轮询）；已最新 Snackbar / 有更新弹窗 / 双通道下载跳转 / SHA-256 点按复制 |
| 10-06 | 校历：官网直取 + 多源兜底 + 刷新反馈 | 点刷新：进度圈 + 结果提示；logcat `CampusCalendar` 应见 `school source:`（无代理直取教务处官网成功）；跨学年自动换图需下学年验证 |
| 10-07 | AI 调课界面 v2 + 记录与动效 | ① 分析完成后提案卡**默认展开**、勾选与预览可改，底栏「重新分析」可用（改完通知直接重跑，不必再清空重来）；② 应用后摘要卡 + 「上次应用」条：撤销顺手、**重新分析后仍可撤**；③ 空结果时底栏是「重新分析」而不是灰掉的「应用所选（0）」；④ 无 API Key 时首屏有「去配置」引导行；⑤ 观感：卡片 / 间距 / 徽章一致性与预览的单栏前 / 后切换；⑥ 「调课记录」页：应用后进页能看到记录、重启后仍在；「撤销」只出现在最新一条未撤销上，撤完上一条变可撤销；从记录页撤过回主页，回执应显示已撤销（不再报「跳过」）；⑦ 动效：分析完成后结果卡逐段淡入、思考过程展开收起、应用后折叠过渡；⑧ 配置页文案已收短、勾选行整卡可点、提示条不再压住底栏；⑨ 复查轮：应用事务期间底栏应显示「应用中…」不可点；建两张课表在 B 上应用一条 → 删 B → 记录页那条标「课表已删除」且**更早的记录仍可撤**；失败卡若已解析到条目应多一行说明；⑩ 重发同一通知（国庆调休场景）：已生效的日期安排不应再列为待确认（应在「已略过」里注明「该日期安排已生效」）；预览「应用前」应显示**当前**状态（含已生效的调休 / 停课），不再像「调课前」；同一天先补课后改放假，后者应赢 |
| 10-07 | 培养方案（我的 → 学习） | ① 升级安装（v19→v20 迁移）打开不崩；② 入口副标题出「84 门课 · 共 161 学分」、页内按学年/学期分组与教务一致；③ 冷启动自动抓（logcat `CurriculumSync`）与顶栏「重新导入」；④ 导出 / 导入 JSON 往返（新段、预览弹窗注记） |
| 10-07 | 调课检测误报修复 | 打开调课检测 →「重新检查」应报「暂无新的调课差异」且旧的 10 条差异从页上消失（待处置缓存被清）；对照教务确认那 9 门课仍在（若误点过「合并所选」则重新导入一次）；复核轮：合并几条差异后再「重新检查」，不应冒出「教务删除 你手动加的课」（基线只装教务条目） |
| 10-07 | 教学日历 → 开学日 | 重新导入课表 → 主界面气泡「开学日已按教务教学日历设为 2026-08-31」→ 课表设置里值确实变化、今日页周数与教务一致（今天应为第 6 周）；手改后再导入会按教务重设；logcat 看 `TeachingCalendarSync` |
| 10-07 | 学生评教（我的 → 学习 → 教务服务） | 点开自动登录后**直接落在评教平台**（不是教务主页）→ 能正常评教；失败时刷新可重试；logcat 与**错误浮层**里都不应出现带 token 的 URL；移动端页（:81）接入待定，见 §4.44 |
| 10-07 | 实习基地查看 | **10 条**按校内 / 校外 / 其他分三组（教务接口固定只回前 10、页内会注明「标注共 17 个」）；与教务页面抽两条对照；顶栏刷新强刷；12 小时内再进页应零网络（logcat `InternshipBases` 见 `loaded … (cache)`） |
| 10-07 | 一卡通支付反馈一轮（弹窗大字 + 宿主补口 + 电费金额 + 震动） | ① 今日页「+ → 付款码」出码付款 → 回到今日页**当场**弹「支付成功」（不再要等进生活页）；生活页付款码条、我的 → 校园卡两条路径照旧弹；② 弹窗金额大字为主、商户 · 时间与卡余额次级小字；③「充值成功」「正在确认充值结果」两张弹窗同款金额大字；④ 电费充值「结果步」能看到 ¥ 金额大字（此前只有状态文案）；⑤ 扣款命中那一刻有短震动（付款码页锁屏也震，节奏同提醒的双震） |
| 10-07 | 第三方账号五页排版统一 | 一卡通（`SettingsSection` 化 + 提醒合并一张卡）/ 胖乖（登录·余额·设备·订单全部卡片化）/ 趣智（免责收口 + 假设置行降级）/ 快趣账号（账号区按钮重排 + 资产余额大字）/ U净（按钮 48dp）：五页卡片观感一致；一卡通提醒开关关闭时阈值行置灰但值照旧；胖乖历史订单收成一行入口 + 底部弹层明细（行点击开订单详情）；快趣账号页已登录态的按钮新排布 |
| 10-07 | 滚轮选择器实时选中 | 任一滚轮弹窗（一卡通阈值 / 课表总周数 / 学期 / 作息时间等）：**滚轮还在滑动时直接点「确定」应立即提交当下视觉中心的值**，不再要求等计数停稳；滚到位停稳后点确定照旧；触感 tick 在停稳时仍有一次；**点非中心项直接滚过去**（长距离跳转不用连拖） |
| 10-07 | 第三方账号五页体验补漏 | ① 胖乖 Token 登录态有「改用短信验证码登录」出口（切进去能切回来）；② 胖乖开水完回页面下拉刷新，历史订单入口的笔数与明细应跟着更新（原先要重进页面）；③ 一卡通余额拉取失败后点「卡余额 获取中… …点此重新获取」能重拉（成功后显示数字、不可点）；④ 胖乖设备弹层选中的那台有主色淡底、点行有触感；⑤ 快趣登录密码框右侧是眼睛图标（不再是「显示/隐藏」文字）；⑥ 上述各页弹窗（退出确认 / 订单详情 / 滚轮 / 关闭凭证）开关有淡入淡出过渡，不再是硬切 |
| 10-07 | 作业详情默认进预览 | 从列表点开一条已有作业应直接落在预览态（正文渲染），点右上角铅笔进编辑；「+ 新建」仍直接进编辑态；转屏后所处态保持（`rememberSaveable`） |

**只有两项真的阻塞后续工作**，优先验：

1. **选课轮次时间解析**（`xksj` 格式）——没有真样本就没法联调抢课引擎，要等选课窗口期；
2. **U净 学校机型 `moduleType`**——决定 P3 要不要做 BLE 通道。

其余都是「功能已经在跑，只是没在真机上确认过」，验完删行即可。

## 仓库与发版

- 公开仓库：https://github.com/Inonvation/JUWP-Schedule （MIT）
- **不入库**（已 gitignore，本地保留）：`scripts/out/`（含真实学号/姓名/会话）、
  `scripts/_archive/`、`docs/`、`scripts/gen_week_layout_preview.py`、`release.jks`、`keystore.properties`
- 发版流程见 `.agents/skills/publish-release/SKILL.md`；图标查名见 `.agents/skills/find-hugeicons/SKILL.md`
- 对外发版必须用正式 keystore 签名；`release.jks` 缺失时构建回退 debug 签名（**仅本地调试**，不可对外分发）
