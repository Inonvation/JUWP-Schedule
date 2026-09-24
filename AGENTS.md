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
| 桌面小组件 | `.agents/rules/widget.md` |
| 教务课表导入 / 考试 / 成绩 | `.agents/rules/import-jw.md` |
| 登录 / 凭证 / 会话 / 自动填表 / 首启引导 | `.agents/rules/login-session.md` |
| 笔记 / 作业 / Markdown / 公式 / 课程备注 | `.agents/rules/notes-homework.md` |
| 共享单车 / 地图 / 免费时长提醒 | `.agents/rules/ebike.md` |
| 生活页 / 一卡通 / 电费 / 胖乖开水 | `.agents/rules/life-power.md` |
| 学工表单 / 盖章成绩单 | `.agents/rules/xg-transcript.md` |

其他只读参考：

1. `PROMPTS.md` 只读你要做的那一个阶段块
2. `DESIGN.md` **只读与任务相关的章节**（§3 导航/UI 规格、§4 结构规格、§3.5 作息表）
3. 历史实现记录在 `docs/devlog.md`（仅本地），只在排查「当初为什么这么改」时才翻
4. 只读参考：`F:\light-life-v3.0`（胖乖）、`scripts/README.md`（爬虫脚本）

**改完的收尾顺序**：写代码 → 编译 + 单测 → 有设备就装真机 → 最后同步文档。
`DESIGN.md` 对应章节（不是 devlog）放在最后一步，用户可以先在真机上并行点验。
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
| Room DB | **v11**。表：`courses`（含 `kind` / `remark` / `timetableId`）、`time_slots`、`semester_config`、`timetables`、`scores`、`ykt_turnovers`、`notes`、`homework`、`power_readings`。迁移逐级 `ALTER TABLE` / `CREATE TABLE`，**禁止**改 destructive；实体 `@Index` 必须与迁移 `CREATE INDEX` 对齐，漏声明会迁移校验崩溃 |
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

## 装真机

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
# MIUI 可能弹「USB 安装」需在手机上允许
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
data/local/      Room v11：courses / time_slots / semester_config / timetables / scores
                 / ykt_turnovers / notes / homework / power_readings（v11）
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
- **提交不带个人信息**：提交信息、代码、注释、测试 fixture 里不出现真实学号、姓名、手机号、寝室房号、真实邮箱；作者身份用 noreply 邮箱
- **提交信息精简**：标题一行说清改了什么，正文只写必要的理由与影响面。细则见 `.agents/rules/build-release.md`
- 禁止改导航或课表领域模型时不同步 `DESIGN.md`（同步排在编译/装机之后，见「怎么用这份文件」的收尾顺序）
- 禁止 emoji 当功能图标；HugeIcons 查名用本地 JAR，勿猜
- 禁止把「能编译」当完成；禁止故意压制编译错误

## 沟通与 DoD

- 与用户中文交流；少形容词，多可验证结论
- 完成定义：功能可演示（真机/模拟器或写明阻塞）；能跑则跑 `assembleDebug` + `testDebugUnitTest`；
  有设备则 `adb install -r` 装 debug 包；未越权改无关模块
- 文档同步是收尾动作，排在编译/装机之后。删功能时把引用它的文档、注释、测试一并清掉

## 阶段状态（见 DESIGN.md §6 里程碑）

P1 脚手架 · P2 Room+UI · P3 我的页导入导出/学期 · P4 胖乖（已实现，待真机验证）·
P5 教务 WebView · P5b 实验课表导入 — **已完成**

P6 打磨 — **进行中**。2026-09-21 起陆续落地：笔记与作业（自研 Markdown/TeX）、课表页背景图、
免费时长提醒、生活页（一卡通 · 寝室电费）、统一登录会话层、首启引导、学工表单、盖章成绩单导出。
各功能的最新口径与真机验证状态见 DESIGN §6，逐条实现史见 `docs/devlog.md`（仅本地）。

## 仓库与发版

- 公开仓库：https://github.com/Inonvation/JUWP-Schedule （MIT）
- **不入库**（已 gitignore，本地保留）：`scripts/out/`（含真实学号/姓名/会话）、
  `scripts/_archive/`、`docs/`、`scripts/gen_week_layout_preview.py`、`release.jks`、`keystore.properties`
- 发版流程见 `.agents/skills/publish-release/SKILL.md`；图标查名见 `.agents/skills/find-hugeicons/SKILL.md`
- 对外发版必须用正式 keystore 签名；`release.jks` 缺失时构建回退 debug 签名（**仅本地调试**，不可对外分发）
