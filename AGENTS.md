# JUWP Schedule · Agent 工作规范

本文件给 AI Agent / 结对工具读。人看 `README.md` 与 `DESIGN.md`。

## 项目身份

- 名称：JUWP Schedule / 显示名「水贝贝」
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
| 生活页 / 一卡通 / 电费 / 胖乖生活 | `.agents/rules/life-power.md` |
| 宿舍空调遥控（今日页 · 江水生活，红外） | `.agents/rules/ac-remote.md` |
| 天气（今日页顶栏提示 · 天气页） | `.agents/rules/weather.md` |
| 百宝箱（图片水印 / 拼接 / 菜鸟批量查） | `.agents/rules/toolbox.md` |
| U净 洗衣房（美的校园洗衣） | `.agents/rules/ujing.md` |
| 问渠（志愿服务 / 劳动教育） | `.agents/rules/wenqu.md` |
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
MainActivity → 底栏今日/课表/教务（2026-10-20 起 3 Tab；生活与我的收进左侧栏）
               + 层叠左侧栏（StackedDrawerState + ui/life/SideDrawer：身份 / 钱包 / 生活 / 江水生活
                 / 学习 / 设置 / 付款码；右滑展开，主页面在前、缩到 94%，侧栏是静态后层，DESIGN §3.1）
               + 路由 jw_import；SettingsActivity（独立窗口，2026-10-20，不再是隐藏「我的」路由）；
               JwImportActivity 的 Browse 模式（各业务页顶栏「网页」只读核对：复用导入窗口的
               电脑版/手机版、统一认证、返回先关弹层与错误处理）；
                SubpageActivity 承载二级页（含成绩查询 SCORES / 成绩设置 SCORE_SETTINGS、
                考试安排 EXAMS / 考试设置 EXAM_SETTINGS、选课 SELECTIONS·SELECTION_WISHES、
               笔记/作业 7 个二级页 NOTES·NOTES_COURSE·NOTE_DETAIL·HOMEWORK·HOMEWORK_COURSE·
               HOMEWORK_DETAIL·HOMEWORK_TODO，DESIGN §3.11；U净 三页 UJING·UJING_ORDER·
               UJING_HISTORY，下单选择走 UjingDraftStore 跨窗口交接，§4.37；
               调课处置页 SCHEDULE_CHANGES，§4.17；账号与登录 ACCOUNTS（全量账号状态与
               补登入口，2026-10-20），§3.3；AI 调课 AI_SCHEDULE + 配置 AI_SETTINGS，§4.39；
                培养方案 CURRICULUM + 实习基地 INTERNSHIP_BASES + 教材 TEXTBOOKS +
                理论课表 THEORY_SCHEDULE + 实验课表 LAB_SCHEDULE（原生**日程流**：某一周 × 按天
                分段，横滑翻周默认本周，走 coursesOnDay 含调休/停课；顶栏外链教务页，2026-10-21）
                + 学籍信息 STUDENT_INFO（入口在左侧栏身份区）+ 教学日历
                TEACHING_CALENDAR，§3.26 / §3.27；问渠 WENQU +
               志愿服务记录 WENQU_RECORDS（记录列表是页、记录详情才是弹层）+ 问渠设置
                WENQU_SETTINGS + 蹲点抢活动 WENQU_GRAB（命中弹确认，不自动提交）+ 预报名
                （详情弹层入口 · **唯一自动提交路径** · 精确闹钟 4008 + shortService，§4.45），
               §3.28；天气详情 WEATHER + 天气设置 WEATHER_SETTINGS（今日页顶栏那行提示的
               落点与开关，默认关，§3.29）；学生评教走
                JwImportActivity 的 TeachingEval 模式（§4.44）；教务 Tab JwHubScreen（§3.26 入口汇总，
                摘要卡带实时数字：成绩门数/加权 · 考试场次 · 教材本数 · 本周节数；快捷四格 =
                理论课表 / 实验课表 / 成绩查询 / 考试安排；网页核对按钮已收进各业务页顶栏，
                首页不再重复挂「网页」入口）；
               百宝箱 TOOLBOX +
              图片水印 TOOLBOX_WATERMARK + 图片拼接 TOOLBOX_STITCH +
              菜鸟批量查 TOOLBOX_CAINIAO（今日页「+」入口，
               受「设置 → 功能」开关控制，默认开，§4.47）；空调遥控 AC_REMOTE（左侧栏「空调遥控」入口，
               寝室美的空调红外，§3.31 / §4.48）；宿舍报修进度 DORM_REPAIR（今日页报修卡 /
               收件箱办结通知的落点，原生只读，§4.49））
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
 + WenquRules（问渠：token 续期时机 · 验证码 5 分钟冷却 · 手机号/密码校验 · 星级与工时文案 ·
 活动状态文案 · 时间戳/时间段与名额文案 · **本地搜索**（关键词匹配 `matchesKeyword` · 命中高亮
 `keywordRanges` · 索引翻页判据 `shouldContinueIndexing`）· **排序档位 `Sort` 与截止排序键** ·
  **每周日 12:00 提醒点 `next/lastWeeklyAlertPoint`** · **蹲点**（`grabInBurst` 窗口 ·
  `grabNextDelayMs` 四档节奏（窗口内失败也快试）· `jitteredDelayMs` 抖动 · `grabWindowEndAt`
  窗口锚定 · `grabFirstMatch` 命中 · `grabShouldStop` 收工）· **预报名**（`preregAlarmAt` /
  `preregTryStartAt` 服务器钟试发 · `preregShouldStop` 三闸 · `classifyPreregReject` 话术分类 ·
  `parseHttpDate` / `clockOffsetMs` 时钟偏移），§4.45）
 + Weather·WeatherAdvice（天气：WMO 码 → emoji/中文短语 · **18:00 分界**与话术优先级
 （正在下雨 > 雪/雷暴 > 有雨 > 降水概率 > 雾 > 防晒 > 防暑 > 添衣 > 大风 > 兜底），§3.29）
 + ScheduleTips（顶栏课表句：**一条只讲一件事**——实验课 > 南北楼往返跑 > 楼变化/同楼 ·
 快下课有雨独占整行；≥2 节课且楼解析得出才说，否则整句丢弃，§3.29）
 + TodayTip（顶栏提示类型：`kind` 决定点击落点——天气句进天气页、课表句进课表页，§3.29）
 + ToolboxImage（百宝箱图片排版：水印字号/密度三档 · 拼接取宽与
 像素上限，纯函数，§4.47）
 + StitchScan（智能拼接匹配核：公共头尾 · 滚动重叠逐行验证 · 容差三档 · **精修取最优对齐**，
 纯函数；默认开，只适合同页连续滚动的一批截图，§4.47）
 + CainiaoQuery（菜鸟批量查：三方路由链接拼装 · 多单号输入清洗拆分与丢弃计数 ·
 历史编解码，纯函数，§4.47）
 + MideaR05d（美的 R05D 空调红外：开关 / 模式 / 温度 / 风速 + 摆风 / 强劲 / ECO ·
 48 位帧 · ConsumerIrManager 脉冲序列生成，纯函数，§3.31 / §4.48）
 + DormRepair·DormRepairRules（宿舍报修进度：状态 / 完成判定 / 3 天保留 / 卡片选记录 /
 过渡通知，§4.49）
 + SelectionCourse·SelectionQuery·SelectionCourseTypes·SelectionWish·SelectionWishes·
 SelectionWishHits（选课中心：候选课程 / 服务端查询条件与类别表 / 预选清单与命中归组，
 §3.20 / §4.36——自动抢课已下线，v3 起写操作只剩用户逐次确认）
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
                 SelectionCenterClient / SelectionCenterHttpClient（选课中心真身：列表/提交/退课，
                 §4.36——自动抢课整条链 2026-10-08 v3 下线，写操作只剩用户逐次确认）
                 NoteRepository / HomeworkRepository / AttachmentStore（笔记作业图片，§4.20）
                 AiImageStore（AI 调课图片，§4.39——filesDir/ai_img/，**会话级**，
                 口径 1600 / q80 / 强制 JPEG / 上限 4 张，新会话与冷启动清空）
                 ScheduleSync（课表直取 + 首启自动导入 + 调课检查）/ ScheduleBaselineStore
                 （调课基线 filesDir JSON，§4.17）
                 InboxRepository（收件箱留痕层，旁路不发网络请求，§4.38；规则见 .agents/rules/inbox.md）
                 DormRepairStore / DormRepairSync（报修快照 filesDir/dorm_repair.json + 只读同步：
                 WebView office 会话优先、CAS 12 小时窗口回退，§4.49）
data/prefs/      DataStore 显示偏好（含 slotSchemaVersion）+ 空调遥控状态（ac_remote_prefs，§4.48）
data/jw/         JwUrls + QiangzhiScheduleParser（理论 xskb）+ SyjxScheduleParser（实验 syjx）
                 + ExamScheduleParser / ScoreParser（考试·成绩 = 同源 fetch JSON，非 DOM 解析）
                 + ScholarProgressParser（学业完成情况 = 教务返 HTML，按表头名映射，非 JSON）
                 + CurriculumParser（培养方案课程设置 = 教务返 HTML 整页，正则抠节点，§4.41）
                 + TextbookParser（教材 = layui JSON 接口，§4.31）
                 + SelectionParser（选课日志与轮次 = layui JSON，§4.35）
                 + SelectionCenterParser（选课中心 = DataTables/JSON，主键拼串/验证码开关/错误页，§4.36）
                 + TeachingCalendarParser（教学日历 = 教务返 HTML，只取第 1 周周一，§4.42）
                 + InternshipBaseParser（实习基地 = layui JSON，§4.43）
                 + JwTeachingEval（学生评教：token 注入脚本 · 平台域判据 · 日志脱敏，§4.44）
data/xg/         XgUrls（学工表单清单 / 只读记录列表地址）+ DormRepairParser（报修列表 JSON，
                 按 label 取字段、个人信息解析阶段丢弃）+ DormRepairWebClient（WebView office
                 会话回退，§4.26 / §4.49）
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
data/wenqu/      问渠（志愿服务 / 劳动教育，DESIGN §4.45 / UI §3.28；**手机号 + 密码**登录，
                 不走微信——code / cache_key 只有微信运行时能生成，App 造不出来）：
                 WenquApi（裸 OkHttp；请求头是 CRMEB 错拼的 Authori-zation；无日志拦截器）
                 + WenquCredentialStore（secure_wenqu.xml **含密码**，已排除备份）
                 + WenquRepository（3 天 JWT，快到期自动重登；工时影子 30 分钟 TTL；
                 搜索走**本地全量索引** `activityIndex`：按页抓全量快照 + 30 分钟缓存，
                 服务端 `keyword` 只在不完整时兜底，§4.45）
                 + WenquTtlCache（进程内 TTL 缓存：工时 / 星级 / 活动列表 / **搜索索引**各一份，
                 进页不重复取数）
                 活动列表 = `volunteer/v2`（平台上有的，与「我的记录」`volunteer/my` 是两张表）
                 + `volunteer/detail/{id}`（工时 / 群号只有详情有）
                 + scanActivities（新增活动提醒的扫描口：一次取 100 条，**不写任何缓存**；
                 要拿新数据走 `invalidateActivities()` + 下次进页增量同步）
                 + grabProbe（蹲点探测：一轮只拉第一页、6 秒 / 窗口内 3 秒短超时、结果并进快照）
                 + preregSnapshot / preregApplyOnce / preregAppliedNow（预报名校对与试发，
                 2026-10-16——**全 App 唯一允许自动提交的路径**）
                 + prewarmSession（开蹲前把 token 换新）
                 WenquServerClock（HTTP `Date` 头 → 服务器时钟偏移，预报名试发窗口按它折算）
                 蹲点页 ui/wenqu/WenquGrabScreen.kt + WenquGrabViewModel.kt（手动开始 ·
                 命中弹确认**不自动提交** · 离开页面即停，§4.45）
                 预报名 ui/wenqu/WenquPreregReminder.kt + WenquPreregRunner.kt +
                 WenquPreregService.kt（shortService · 闹钟 4008 · 通知 1017 · 单条点名，§4.45）
                 新增活动提醒见 ui/reminder/WenquActivityAlertReminder.kt（第五路周期链）
                 每周日 12:00 抢活动提醒见 ui/reminder/WenquWeeklyAlertReminder.kt（零网络，§4.45）
                 规则见 .agents/rules/wenqu.md
data/weather/    天气（DESIGN §3.29 / §4.46；**Open-Meteo 免 Key 免注册**，CC BY 4.0 要署名）：
                 WeatherApi（裸 OkHttp，无日志拦截器；查询点 = 校区坐标，WGS84 口径）
                 + WeatherRepository（30 分钟 TTL · 同刻只放一个请求 · 刷新失败回退旧快照）
                 规则见 .agents/rules/weather.md
data/ir/         空调红外发射（今日页「江水生活」图标入口，DESIGN §4.48）：
                 AcIrBlaster（ConsumerIrManager 薄封装；TRANSMIT_IR 普通权限，无发射器机型
                 进页只给提示）；协议纯逻辑见 domain/MideaR05d（只走 R05D），
                 状态见 AcRemotePrefsStore
                 规则见 .agents/rules/ac-remote.md
ui/today|week|life|me|jw|water|qzxy|campus|jwvw|score|exam|scholar|curriculum|internship|selection|timetable|common|theme|widget|ebike|notes|homework|ujing|wenqu|weather|inbox|reminder|ai|toolbox
Graph.kt         单例 Repository
JuwApplication   ensureDefaults（节次/学期；课表不预置）+ 小组件冷启动刷新
```

- 课表 JSON 字段对齐 DESIGN 4.3 / 拾光互通；解析层见 `scripts/fetch_courses.py` 与 `data/jw/`
- 教务：强智 `https://jiaowu.juwp.edu.cn:81/` → SSO service 必须是 `http://jiaowu.juwp.edu.cn/sso.jsp`（**不要带 :81/:8080**）→ 课表 `http://jiaowu.juwp.edu.cn:8080/jsxsd/xskb/xskb_list.do?viweType=0`
- 解析 HTML 用注入 JS 抽 `li.courselists-item`；Kotlin 侧 `html.parser` 思路，**lxml 会丢节点**（双 doctype）

## 硬性禁止

- 刷积分/绕过付费/伪造官方身份：**App 与上架模块（`data/qiekj/` 等）仍一律禁止**——App 不接签到/任务/领奖，只保留开水/余额/订单。仓库转私有后（2026-10 起），允许在 `.re-tmp/` 写**个人账号的研究脚本**（自查自用：签到、查活动与积分、复现 App 自身的任务流程）；不用于他人账号，不进 App，不入文档口径
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

## 沟通与 DoD

- 与用户中文交流；少形容词，多可验证结论
- 完成定义：功能可演示。能跑则跑 `assembleDebug` + `testDebugUnitTest`；装机与点验按「装机与验证路径」
  走，手机在线就装给用户点验，不在线且够小、有必要才自测（没必要则确认代码后交用户点验），其余写明阻塞；未越权改无关模块
- 文档同步是收尾动作，排在编译/装机之后。删功能时把引用它的文档、注释、测试一并清掉
- **文档纪律（2026-10-11 起）**：改动记录只写 `docs/devlog.md`，不进本文件；真机验证由用户日常使用覆盖，不维护「待点验」清单（验出问题直接修，修复记 devlog）；本文件只接受「工程实况 / 路由表 / 硬性规则」三类变更，领域细节进 `.agents/rules/`；本文件目标 ≤ 500 行，超了先拆再改

## 阶段状态（见 DESIGN.md §6 里程碑）

P1 脚手架 · P2 Room+UI · P3 我的页导入导出/学期 · P4 胖乖 · P5 教务 WebView · P5b 实验课表导入 — **已完成**；
P6 打磨 — **进行中**（2026-09-21 起）。

2026-09-21 起的功能开发逐条记录在 `docs/devlog.md`「P6 打磨编年」；本文件只保留本摘要，不再追加流水。

## 仓库与发版

- **仓库 2026-10-10 起私有、停止分发**（发 2.7.0 后删除全部旧 release）。连带影响（均未修，用户口径：自用）：
  应用内「检查更新」失效（源全基于公开镜像）、校历的仓库镜像兜底失效（主源教务处官网仍可用）、
  关于页「开源仓库」死链、免责声明下载指引悬空。本地提交 / 推送照常。
- **不入库**（已 gitignore，本地保留）：`scripts/out/`（含真实学号/姓名/会话）、
  `scripts/_archive/`、`docs/`、`scripts/gen_week_layout_preview.py`、`release.jks`、`keystore.properties`
- 发版流程见 `.agents/skills/publish-release/SKILL.md`；图标查名见 `.agents/skills/find-hugeicons/SKILL.md`
- 对外发版必须用正式 keystore 签名；`release.jks` 缺失时构建回退 debug 签名（**仅本地调试**，不可对外分发）
