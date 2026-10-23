# JUWPHub · Agent 工作规范

本文件给 AI Agent / 结对工具读。人看 `README.md` 与 `DESIGN.md`。

## 项目身份

- 名称：JUWPHub / 显示名「JUWPHub」
- 仓库名 JUWPHub；包名不变
- 学校：江西水利电力大学（**非官方**；对外文案必须免责声明）
- 包名：`edu.jxslu.schedule`（debug 变体加 `.debug` 后缀，详见「工程实况」）
- 形态：单模块 `:app` · Kotlin · Compose + Material3 · minSdk 26 / compileSdk 36 / targetSdk 36

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
| 共享单车 / 地图 / 免费时长提醒 / 自动还车 | `.agents/rules/ebike.md` |
| 快趣账号登录 / 用车 / 卡券 / 设置 | `.agents/rules/ebike-account.md` |
| 充值弹层 / 一卡通 / 电费 / 胖乖生活 | `.agents/rules/life-power.md` |
| 宿舍空调遥控（侧栏 · 江水生活，红外） | `.agents/rules/ac-remote.md` |
| 天气（今日页顶栏提示 · 天气页） | `.agents/rules/weather.md` |
| 百宝箱（图片水印 / 拼接 / 菜鸟批量查） | `.agents/rules/toolbox.md` |
| U净 洗衣房（美的校园洗衣） | `.agents/rules/ujing.md` |
| 问渠（志愿服务 / 劳动教育） | `.agents/rules/wenqu.md` |
| 问渠提醒 / 蹲点 / 预报名 | `.agents/rules/wenqu-alerts.md` |
| 学工表单 / 盖章成绩单 | `.agents/rules/xg-transcript.md` |
| 拿不准术语 / 两个相似实现选哪个 | `.agents/rules/README.md`（领域词典） |

**文档分工（同一事实只留一处）**

| 文件 | 只写 | 不写 |
|------|------|------|
| `AGENTS.md` | 入口路由、工程实况、命令、硬性禁止 | 领域细节、实现流水账 |
| `.agents/rules/` | 动手前读什么、改哪里、怎么验、踩过的坑 | 产品决策、历史编年 |
| `design/` | 决策与规格：为什么这样做、边界在哪 | 函数实现、字段清单、测试用例 |
| `docs/devlog.md` | 历史、原因、事故复盘（本地，不入库） | 当前行为承诺 |
| 代码 KDoc | 当前不变量、失败模式、唯一实现 | 日期流水、用户反馈原文 |

- 改规则文件先看上面的路由表；新增规则文件必须同时更新路由表。
- 不把 `AGENTS.md` 的命令/工程实况复制进 rules，用链接指回来。
- 不把 `design/` 的规格复制进代码注释；代码只写「为什么不能改成别的」。
- 文档与代码冲突时：**代码 + 测试 > design > AGENTS 描述**；先修文档，再继续改代码。

**AI 改动顺序**：定位唯一实现 → 读对应 rule / design 章节 → 先写或改测试 → 改代码 →
目标测试 + 全量测试 → 收尾一行 `未验 / 风险`。

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
| Gradle Wrapper | **8.11.1** |
| AGP | **8.9.1**（androidx.core 1.17.0 的 AAR 下限，也是 compileSdk 36 的下限） |
| Kotlin | **2.1.21**（compose / serialization 同版本；KSP `2.1.21-2.0.1`） |
| SDK 档位 | compileSdk **36** / targetSdk **36**（Android 16）。免费倒计时通知的「灵动岛」用的是系统标准 Live Updates，`setRequestPromotedOngoing` 要 compileSdk 36；卡片头部秒数用系统 chronometer，芯片仍用 `setShortCriticalText`（HyperOS 3 不显示芯片 chronometer）；本机已装 android-36 + build-tools 36.0.0 |
| AndroidX core | **1.17.0**（两个 Live Updates compat API 从 1.17 起）。**别升到 1.18+**：那要 compileSdk 36.1 |
| Room | **2.7.1**（2.6 + Kotlin 2.1 会 KSP `unexpected jvm signature V`） |
| Room DB | **v20**。表：`courses`（含 `kind` / `remark` / `timetableId`）、`time_slots`、`semester_config`、`timetables`（v14 起 `term` 列记数据学期，是详情查教材的钥匙）、`scores`、`scholar_groups` / `scholar_courses`（学业完成情况，v13）、`ykt_turnovers`（v15 起 `fromAccount` / `accType` 记交易账户，消费流水「充值到哪」的钥匙）、`notes`、`homework`、`power_readings`（含 `roomId` 数字 id 与 `roomName` 房号显示名，两者别混用）、`textbooks`（教材，v14，挂 courseName 带 term）、`ride_records`（本机骑行记录，v16，只记本机用车那条链路）、`course_selections`（选课记录，v17，按学期替换，DESIGN §4.35）、`inbox_messages`（收件箱，v18，§4.38）、`date_overrides`（日期覆盖：调休 / 停课，v19，§4.40）、`curriculum_courses`（培养方案课程设置，v20，§4.41）。迁移逐级 `ALTER TABLE` / `CREATE TABLE`，**禁止**改 destructive；实体 `@Index` 必须与迁移 `CREATE INDEX` 对齐，漏声明会迁移校验崩溃；**实体带 Kotlin 默认值的列，迁移建表必须写 `DEFAULT`**（v7→v8 remark、v11→v12 roomName、v13→v14 教材展示列同坑；v19 的 `note` 特意不给 Kotlin 默认值来绕开这条） |
| 作息表 | **11 小节**（每节 40 分钟，大节内 5 分钟、大节之间 20 分钟换教室），见 DESIGN §3.5 |
| 课表网格 | 行号 = **小节号 1–11**（不是大节号）；`Course.startSection/endSection` 也是小节号 |
| HugeIcons | `com.github.rikkahub:hugeicons-compose:1.4`（**JitPack**，**`isTransitive = false`**）。**不要**写 `me.rerere:hugeicons-compose:1.0.0`（Maven Central 不存在）；不要打开传递依赖（只用它的图标 AAR，打开会把它那一套 compose / core 版本带进来，与项目的版本边界打架） |
| 图标用法 | `import me.rerere.hugeicons.stroke.*` + `HugeIcons.Calendar01` 等；查名用本地 JAR，勿猜 |
| osmdroid | `org.osmdroid:osmdroid-android:6.1.18`（Maven Central；POM 里没有 `<dependencies>`，不拉传递依赖）。**加依赖后第一次构建要联网 resolve 一次**，之后 `--offline` 照常用 |
| Glance | `androidx.glance:glance-appwidget:1.2.0`（桌面小组件）；传递抬 compose runtime 至 1.7.8 |
| 样例课 | **已移除**；课表默认空，从教务 WebView 导入 |
| 包名 | release = `edu.jxslu.schedule`；debug = `edu.jxslu.schedule.debug`（两者签名不同，**必须**靠后缀区分） |

> 表里的版本号与 DB 版本**以代码为准**，本表只是索引。对不上时先查代码再改表：
> `Select-String app\build.gradle.kts -Pattern 'versionName'`、
> `Select-String app\src\main\java\edu\jxslu\schedule\data\local\JuwDatabase.kt -Pattern 'version ='`

## 常用命令

```powershell
# 构建 + 测试（本机依赖已齐备，加 --offline 后秒级完成）
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline
# 只跑一类（改一处时先跑它，最后再跑全量）
.\gradlew.bat :app:testDebugUnitTest --offline --tests "edu.jxslu.schedule.domain.WeeksTextTest"
# APK: app\build\outputs\apk\debug\app-debug.apk
# release（R8 压缩 + 签名，约 1.5 分钟）：app\build\outputs\apk\release\app-release.apk
.\gradlew.bat :app:assembleRelease --offline
```

- 用例数从 `app/build/test-results/testDebugUnitTest/*.xml` 汇总（Gradle 成功时不打印用例数）：

```powershell
$r = Get-ChildItem app\build\test-results\testDebugUnitTest\*.xml | ForEach-Object { ([xml](Get-Content $_ -Encoding UTF8)).testsuite }
"{0} tests / {1} failures / {2} errors / {3} skipped" -f (($r | Measure-Object tests -Sum).Sum), (($r | Measure-Object failures -Sum).Sum), (($r | Measure-Object errors -Sum).Sum), (($r | Measure-Object skipped -Sum).Sum)
```
- 改过混淆规则要装 release 包冒烟，理由与已踩的坑见 `.agents/rules/build-release.md`。

## 装机与验证路径

**先 `adb devices`，再决定谁来点验**（2026-09-29 起）。默认把点验交给用户，AI 自己测只走下面那个窄口子。
「手机在线」的判据：设备列表里出现非 `emulator-` 前缀的 serial。

| 情况 | 做法 |
|------|------|
| 手机在线 | `adb install -r` 装 debug 包 → 告诉用户已装好、装的是哪个包、建议点验的 2–3 处 → 停下等反馈。不要自己接着在设备上反复点 |
| 手机不在线，且改动够小 | **先判有没有必要自测**（判据见下）：有必要 → 起模拟器自测，结论落成一句话；没必要 → 确认代码无误、指明用户点验什么，不起模拟器 |
| 手机不在线，改动不够小 | 说明缺哪台设备、需要用户点验什么。不要为了「测过」而扩大模拟器范围 |

「够小」= 下面三条**同时**满足，缺一条就交给用户：

1. 不碰网络请求路径（Retrofit/OkHttp、WebView 注入、教务/胖乖/一卡通/电费/快趣/农行收银台）
2. 不碰凭证、签名、`proguard-rules.pro`、Room 迁移、`AndroidManifest` 权限
3. 验证点只在 UI 层，且 `uiautomator dump` 或 `screencap` 能给出结论

「够小」只说明**允许**自测，不等于**必须**自测。三条同时满足后再判有没有必要：

**有必要自测**：验证点读代码定不了、且 `uiautomator dump` / `screencap` 能给出确定结论——
实测布局（行高 / 对齐 / 省略号 / 裁字 / 命中区）、动画与过渡的实际观感、需真实测量值才判的排版。

**没必要自测**：纯文案 / 常量 / 配色 / 资源改动；状态接线、分支条件等读代码即可判定且已有单测覆盖的改动；
验证点只有用户能判（主观观感、真机行为、真实数据）；模拟器起不来（本机常发生，见下）也别空转。
没必要自测时：确认代码无误 + 指明用户点验什么，不起模拟器。

模拟器上的结果只算冒烟，不算真机验证；沾到账号、网络、系统权限的功能一律等用户点验。

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
# MIUI 可能弹「USB 安装」需在手机上允许；首次常被 INSTALL_FAILED_USER_RESTRICTED 拦，重试一次通常就过
# 多台设备在线时加 -s <serial>，用 adb devices -l 分辨真机与模拟器
```

debug 与 release 是两个独立应用（桌面名「JUWPHub Debug」/「JUWPHub」），可同时安装、数据各一份。改动冲突时改 `app/src/debug/res/values/strings.xml`（仅覆盖 `app_name`）；包名与签名口径见 `.agents/rules/build-release.md`。

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
不是"本机禁绑"——同一台机器上不在保留段的端口都能绑。**保留段每次重启会重排，写死的端口迟早失效**
（2026-10-08 复核：文档曾用的 `5183` 已落进保留段 `5137-5236`，`5458-5557` 仍在、`5558-5657` 已变）。
**每次起之前现查现挑，不要照抄任何写死的端口**：

```powershell
netsh int ipv4 show excludedportrange protocol=tcp   # 输出即当前保留段，逐段避开
```

从查询结果之外挑一个端口（下文示例统一写 `<port>`），启动后就是 `emulator-<port>`。
本机两个 AVD：`jwptest`、`jw35`（android-35），可用的 headless 起法：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd jw35 -no-window -no-audio `
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -port <port>   # <port> 照上面的查询现挑
adb -s emulator-<port> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-<port> shell uiautomator dump /sdcard/u.xml   # 页面文字树，可逐条核对
adb -s emulator-<port> exec-out screencap -p > shot.png       # 真渲染截图
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

正式脚本 12 个；历史一次性探测脚本在 `scripts/_archive/`（**勿依赖**，仅留档；该目录不入公开仓库）。

| 文件 | 作用 | 产出 |
|------|------|------|
| `jw_session.py` | 共享登录（CAS → 教务 SSO → 会话校验） | — |
| `fetch_all.py` | 一条命令顺序抓全套教务数据（共享会话，只登录一次） | 下列六个抓取脚本的 JSON（`scripts/out/`） |
| `fetch_courses.py` | 学期理论课表（`--term` 可选） | `scripts/out/courses.json` |
| `fetch_lab_courses.py` | 实验课表（实践实验 → 实验课表查询，`--term` 可选） | `scripts/out/lab_courses.json` |
| `fetch_exams.py` | 考试安排（`--term` 可选，缺省取教务当前学期；JSON 接口） | `scripts/out/exams.json` |
| `fetch_scores.py` | 课程成绩（`--term` 可选，缺省全部学期；JSON 接口） | `scripts/out/scores.json` |
| `fetch_textbooks.py` | 学生教材确认（`--term` 可选，缺省教务当前学期；JSON 接口 `/jsxsd/nxsjc/xsjcqr`） | `scripts/out/textbooks.json` |
| `fetch_selections.py` | 选课记录 + 选课轮次（`--term` 可重复，缺省当前学期+下一学期；layui JSON；**只读**，退课接口绝不碰） | `scripts/out/selections.json` |
| `fetch_curriculum.py` | 培养方案「课程设置总表」（全量 84 门级别，含开设学期 1–8；服务端渲染 HTML，不随学期；另有 `curriculum.md` 按学年可读版） | `scripts/out/curriculum.json` |
| `fetch_power.py` | 寝室电费（新开普缴费平台 `charge.juwp.edu.cn`，**非教务**；`--history` / `--room 9Z999`） | `scripts/out/power.json` |
| `fetch_transcript.py` | 教务处**盖章成绩单**（金格签章系统 `jwxyxx.juwp.edu.cn`，**非强智教务**；`--list` / `--term` 可多个 / `--out`） | `scripts/out/transcript_<标签>.pdf` |
| `sync_calendar.py` | 校历镜像同步（教务处官网通知公告 → 抓高清原图 → 学年/文件名/sha256 比对，变更才落盘并自动 commit & push；**免登录免凭证**） | `docs-public/calendar/`（更新仓库镜像）；**2026-10-07 后 App 已直取官网为主，本脚本为应急工具** |

所有脚本输出 JSON 顶层 `term` = **实际爬到的学期**（如 `2026-2027-1`），App 导入确认弹窗据此展示
（`fetch_curriculum.py` 例外：培养方案全量、不按学期抓取，无 term 口径）；
带 `--term` 时脚本会校验「请求学期 = 教务返回学期」，不一致直接报错而不是静默爬错学期。

```powershell
.\.venv-scraper\Scripts\python.exe scripts\fetch_all.py          # 一条命令顺序抓全套（共享会话，只登录一次）
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
MainActivity
├─ 底栏：今日 / 课表 / 教务（3 Tab）
├─ 左侧栏：身份 / 钱包 / 生活 / 江水生活 / 学习 / 设置 / 付款码（StackedDrawerState）
└─ 独立窗口：Settings / JwImport（含 Browse 与 TeachingEval）/ Transcript / XgForm /
   PowerBankPay / Onboarding / SubpageActivity（二级页，路由枚举见 SubpageScreen）

domain/       纯 JVM 逻辑：课程/作息/学期/成绩/考试/调课差异/笔记作业/单车/水控/AI prompt/
              问渠规则/天气/百宝箱/空调红外/报修/选课（按主题分文件，单测在 app/src/test）
data/local    Room v20（表清单见「工程实况」；迁移只加不删，实体索引必须与迁移对齐）
data/repo     ScheduleRepository（备份模型在 ScheduleBackup）+ 各业务 Repository / Sync
data/prefs    DataStore 显示偏好（DisplayPrefsStore + 按功能拆出的扩展文件）
data/jw       强智教务：URL / 课表 / 考试 / 成绩 / 学业 / 培养方案 / 教材 / 选课 / 日历 / 评教
data/xg       学工表单与宿舍报修（只读）
data/qiekj    胖乖生活（登录/开水/余额/订单/每日签到）
data/ykt      一卡通（登录/付款码/流水；凭证与 token 口径见 rules/login-session.md）
data/kqcx     快趣出行（附近车辆/账号用车/图层缓存）
data/power    寝室电费（新开普；读数/账单/充值）
data/qzxy     趣智校园（蓝牙水控协议与状态机）
data/ai       AI 调课（OpenAI 兼容流式 + 工具调用）
data/wenqu    问渠（JWT/活动/记录/蹲点/预报名）
data/weather  天气（Open-Meteo，30 分钟 TTL）
data/ir       空调红外发射（R05D）
ui/           页面按业务包：today/week/life/me/jw/water/qzxy/campus/...；公共件在 ui/common
Graph.kt      单例装配（新增依赖从这里进）
JuwApplication ensureDefaults（节次/学期；课表不预置）+ 小组件冷启动刷新
```

细节口径：二级页枚举以 `SubpageScreen` 为准；业务规格查 `design/`；操作手册查 `.agents/rules/`。

- 课表 JSON 字段对齐 DESIGN 4.3 / 拾光互通；解析层见 `scripts/fetch_courses.py` 与 `data/jw/`
- 教务：强智 `https://jiaowu.juwp.edu.cn:81/` → SSO service 必须是 `http://jiaowu.juwp.edu.cn/sso.jsp`（**不要带 :81/:8080**）→ 课表 `http://jiaowu.juwp.edu.cn:8080/jsxsd/xskb/xskb_list.do?viweType=0`
- 解析 HTML 用注入 JS 抽 `li.courselists-item`；Kotlin 侧 `html.parser` 思路，**lxml 会丢节点**（双 doctype）

## 硬性禁止

- 刷积分/绕过付费/伪造官方身份：**App 允许个人账号的每日签到**（2026-10-23 用户授权）——当前只接签到，不接任务列表、任务完成上报/领奖。仍然禁止：绕过验证码/广告核验/付费/风控，批量账号，内置他人 token，后台静默刷取。签到接口的版本与签名口径见 `.agents/rules/life-power.md`；token 仍只走 EncryptedSharedPreferences，禁止进日志/git
- 禁止把学号/密码/token 写进 git（`scripts/credentials.local.json`、`local.properties`、`release.jks`、`keystore.properties` 已 ignore）
- 提交与文档中不得出现真实学号、姓名、手机号、token（转私不改这条卫生习惯）；新增抓取产物目录前先确认 `.gitignore` 已覆盖
- **提交不带个人信息**：提交信息、代码、注释、测试 fixture 里不出现真实学号、姓名、手机号、寝室房号、真实邮箱；作者身份用 noreply 邮箱
- **提交信息精简**：标题一行说清改了什么，正文只写必要的理由与影响面。细则见 `.agents/rules/build-release.md`
- 禁止改导航或课表领域模型时不同步 `DESIGN.md`（同步排在编译/装机之后，见「怎么用这份文件」的收尾顺序）
- 禁止 emoji 当功能图标（**文案行内允许**，如今日页天气提示那行的 ☀️🌧️；按钮 / 卡片 /
  标签这类承载操作的图标仍一律用 HugeIcons）；HugeIcons 查名用本地 JAR，勿猜
- 禁止把「能编译」当完成；禁止故意压制编译错误
- **批量写文件前必须校验落点**（2026-10-21 教训：解包脚本把 178 项写到 `F:\` 根）：
  归档 / 解包 / 批量导出的条目名先 `lstrip('/\\')` 再 join，并断言解析出的绝对路径位于目标目录
  之下（`os.path.commonpath`），否则直接报错退出；写盘前打印目标绝对路径，写盘后列目录核对
  落点——**不以脚本自报计数为成功依据**。往工作区之外写文件先确认，不默认 CWD 安全。
- **先找现成的**：新建函数 / Composable 前先看 `ui/common`、`domain/` 和同主题包；能按原用法复用就不新建。
- **最小改动不砍这些**：信任边界校验（导入 JSON / 各类解析）、防数据丢失的错误处理（Room 迁移）、凭证与安全、无障碍。

## 沟通与 DoD

- 与用户中文交流；少形容词，多可验证结论
- 完成定义：功能可演示。能跑则跑 `assembleDebug` + `testDebugUnitTest`；装机与点验按「装机与验证路径」
  走，手机在线就装给用户点验，不在线且够小、有必要才自测（没必要则确认代码后交用户点验），其余写明阻塞；未越权改无关模块
- 文档同步是收尾动作，排在编译/装机之后。删功能时把引用它的文档、注释、测试一并清掉
- **文档纪律（2026-10-11 起）**：改动记录只写 `docs/devlog.md`，不进本文件；真机验证由用户日常使用覆盖，不维护「待点验」清单（验出问题直接修，修复记 devlog）；本文件只接受「工程实况 / 路由表 / 硬性规则」三类变更，领域细节进 `.agents/rules/`；本文件目标 ≤ 500 行，超了先拆再改
- **改动交付的收尾固定一行**：`未验：<没跑到的设备/路径>；风险：<最坏后果>`；全验过写「未验：无」。
- **临时取巧留痕**：代码里写 `// shortcut: <限制>, <何时升级>`；升级条件到了就改，不留「以后再说」。

## 阶段状态（见 DESIGN.md §6 里程碑）

P1 脚手架 · P2 Room+UI · P3 我的页导入导出/学期 · P4 胖乖 · P5 教务 WebView · P5b 实验课表导入 — **已完成**；
P6 打磨 — **进行中**（2026-09-21 起）。

2026-09-21 起的功能开发逐条记录在 `docs/devlog.md`「P6 打磨编年」；本文件只保留本摘要，不再追加流水。

## 仓库与发版

- **仓库 2026-10-23 起恢复公开，仓库名改为 `JUWPHub`**；显示名、仓库名与 APK 资产名统一为 `JUWPHub`。
  旧仓库 `Inonvation/JUWP-Schedule` 由 GitHub 重定向到 `Inonvation/JUWPHub`；旧 release 资产名
  仍为 `JUWP-Schedule-*.apk`，新 release 用 `JUWPHub-*.apk`。应用内「检查更新」、校历的仓库镜像
  兜底、关于页「开源仓库」链接随仓库公开恢复可用。改名后的首个 release 同时上传新旧两个资产名，
  给忽略清单 `apk` 字段的老版本兜底。本地提交 / 推送照常。
- **不入库**（已 gitignore，本地保留）：`scripts/out/`（含真实学号/姓名/会话）、
  `scripts/_archive/`、`docs/`、`scripts/gen_week_layout_preview.py`、`release.jks`、`keystore.properties`
- 发版流程见 `.agents/skills/publish-release/SKILL.md`；图标查名见 `.agents/skills/find-hugeicons/SKILL.md`
- 对外发版必须用正式 keystore 签名；`release.jks` 缺失时构建回退 debug 签名（**仅本地调试**，不可对外分发）
