# 开发者文档：复刻一个你自己学校的课表 App

本文档面向想把这套方案搬到**自己学校**的开发者：讲清楚本项目的整体架构、
「从教务系统拿到课表/考试/成绩」完整数据链路的实现细节，以及换校适配的动手步骤。
应用功能与界面规格见 [DESIGN.md](DESIGN.md)，爬虫脚本速查见 [scripts/README.md](scripts/README.md)。

> 快照：v1.2.0（2026-09-22，Room v8）。工程实况（依赖版本、数据库版本、口径清单）
> 以 [AGENTS.md](AGENTS.md) 为准，本文只讲「为什么这么设计、换校要动哪里」。

> 本项目是**江西水利电力大学的非官方学生项目**，仅供学习交流。
> 换校适配时请同样遵守：模拟正常客户端操作、凭证不入代码仓库、不刷积分、不伪造官方身份。

---

## 目录

1. [全景：两条数据链路](#1-全景两条数据链路)
2. [工程形态与架构分层](#2-工程形态与架构分层)
3. [领域模型与数据存储](#3-领域模型与数据存储)
4. [链路 A：Python 爬虫（逆向参考实现）](#4-链路-apython-爬虫逆向参考实现)
5. [链路 B：App 端 WebView 导入（生产路径）](#5-链路-bapp-端-webview-导入生产路径)
6. [核心算法与口径（改代码前必读）](#6-核心算法与口径改代码前必读)
7. [考试安排映射](#7-考试安排映射)
8. [换一所学校：适配指南](#8-换一所学校适配指南)
9. [测试](#9-测试)
10. [构建环境与已知坑](#10-构建环境与已知坑)
11. [安全与合规红线](#11-安全与合规红线)

---

## 1. 全景：两条数据链路

本项目要解决的核心问题只有一个：**把教务系统里的课表，变成手机上可交互的数据**。
围绕它有两条互补的链路（外加一条生产侧复刻，见本节末尾）：

```
链路 A（Python 爬虫，本机调试用 —— 逆向参考实现）
  scripts/jw_session.py        CAS 统一认证 → 教务 SSO，拿到可用会话
  scripts/fetch_courses.py     理论课表 HTML → courses.json
  scripts/fetch_lab_courses.py 实验课表 HTML → lab_courses.json（含周次聚合）
  scripts/fetch_exams.py       考试 JSON 接口 → exams.json
  scripts/fetch_scores.py      成绩 JSON 接口 → scores.json

链路 B（App 端 WebView 注入导入 —— 生产路径，用户数据不出本机）
  JwImportActivity → WebView 里用户自己登录教务
    → 按当前 URL 判定页面类型 → 注入 JS 抽 DOM / 同源 fetch JSON
    → Kotlin 解析成 Course → 确认弹窗（选目标课表/合并或覆盖）→ Room 入库
```

> 另有 `scripts/fetch_power.py`：寝室电费（新开普「移动服务平台」缴费 `charge.juwp.edu.cn`），
> 与教务链路无关，也不进 App，说明见 `scripts/README.md` §5.5。

为什么两条链路并存：

- **Python 爬虫跑在开发机上**，用来把页面结构逆向清楚、产出可当解析器回归
  fixture 的 JSON 与 HTML 快照。改 App 解析器之前先跑它确认页面规则没变。
- **App 端不跑 Python、也不内置任何账号密码**：用户在 WebView 里自己登录，
  Cookie 留在系统 `CookieManager`，代码里只有 URL 与解析规则。这是隐私与合规的底线。

两条链路共用同一套**页面规则**（DOM 结构、接口参数、字段语义），因此排错文档
`scripts/README.md` 同时覆盖 App 行为——脚本里趟过的坑，App 端实现已经一并规避。

2026-09-19 起，链路 A 的登录与抓取又多了**第二份实现**：`data/jw/JwHttpSession.kt`
用 OkHttp 把 `scripts/jw_session.py` 的 CAS → SSO 链路原样复刻进 App，服务「调课自动检测」
（DESIGN §4.17，默认关闭；凭证 EncryptedSharedPreferences 加密存储、排除云备份）。
同一套页面规则自此有 Python 与 Kotlin 两份独立实现，**换校时两边要同步改**（见 §5.6）。

除课表链路外，App 还随附几个**校园生活**模块（胖乖开水、一卡通付款码与账单、寝室电费、
快趣出行码与附近单车地图），它们是彼此独立的 API 客户端（`data/qiekj/`、`data/ykt/`、
`data/power/`、`data/kqcx/`、`domain/EbikeQr.kt`），与课表核心零耦合——换校适配时整块
删掉不影响课表功能（入口在 `ui/` 对应包、今日页底部固定区与底栏「生活」）。
各自的实现细节见 DESIGN §3.9 / §3.10 / §3.13 / §4.5 / §4.10 / §4.18 / §4.19 / §4.23 / §4.24。

---

## 2. 工程形态与架构分层

| 项 | 值 |
|----|-----|
| 模块 | 单模块 `:app` |
| 语言/UI | Kotlin 2.1.21 + Jetpack Compose + Material3 |
| 持久化 | Room 2.7.1（v8：课表/成绩/笔记/作业/检测/一卡通流水）+ DataStore（显示偏好与开关） |
| 网络 | App 端 WebView + Retrofit（胖乖）+ OkHttp（教务检测、一卡通）；课表数据零自建后端 |
| SDK | minSdk 26 / compileSdk 35 |
| 测试 | 纯 JVM 单测 57 个类（domain 层可全量测，见 §9） |

分层与依赖方向（`app/src/main/java/edu/jxslu/schedule/`）：

```
MainActivity.kt        底栏四 Tab：今日 / 课表 / 生活（可关，§3.13）/ 我的
SubpageActivity.kt     二级页容器（成绩查询、笔记/作业 7 个二级页、各类设置）
JwImportActivity.kt    教务导入独立窗口（独立 Activity，见 §5）
Graph.kt               手写单例装配：Repository / 数据库 / 偏好
domain/                纯 Kotlin：Course、ScheduleCalculator、ExamMapper、ScheduleExporter、
                       ScheduleDetect、CourseTweak、ReminderPlanner、Markdown/MathTex、
                       Shortcuts …… 不依赖 Android，可 JVM 测
data/local/            Room v8：Entities / Daos / JuwDatabase（含 v1→v8 逐级迁移）
data/repo/             ScheduleRepository（课表读写 + 导入校验）、ScoreRepository、
                       NoteRepository / HomeworkRepository、AttachmentStore（笔记图片）
data/prefs/            DataStore 显示偏好与全局开关（含 slotSchemaVersion）
data/jw/               教务：JwUrls、两个课表解析器、考试/成绩解析器、JwHttpSession（检测）
data/qiekj/            胖乖生活 API（登录/开水/余额/订单）
data/ykt/              一卡通（新中新慧新e校）登录、付款码与流水同步
data/power/            寝室电费（新开普缴费平台）：登录、读表、电费流水与缴费页深链（§4.24）
data/calendar/         系统日历同步（CalendarSyncer）
ui/                    Compose Screen + ViewModel（today/week/life/me/water/campus/score/
                       notes/homework/timetable/detect/reminder/ebike/...）
ui/widget/             Glance 桌面小组件
```

原则：

- UI 不直接碰网络与数据库，一律走 Repository；
- 教务解析结果必须先变成 `domain.Course` 才能入库（解析层只产出纯模型）；
- 所有可能出错的外部交互（导入、网络、解析）都要有可展示的错误文案与重试路径。

---

## 3. 领域模型与数据存储

### 3.1 三个核心模型（`domain/Models.kt`）

```kotlin
data class Course(
    val id: Long,
    val name: String, val teacher: String, val position: String,
    val day: Int,              // 1=周一 … 7=周日
    val startSection: Int,     // 小节号 1–11（不是大节号！）
    val endSection: Int,
    val weeks: Set<Int>,       // 教学周
    val isCustomTime: Boolean = false,       // 自定义时间课（考试也走这里）
    val customStartTime: String? = null,     // "HH:mm"
    val customEndTime: String? = null,
    val colorIndex: Int = 0,
    val kind: CourseKind = CourseKind.Theory, // Theory / Lab / Exam
    val remark: String = "",                  // 课程备注（用户自写，属课程行）
)

data class TimeSlot(val number: Int, val startTime: String, val endTime: String) // 小节 1–11

data class SemesterConfig(
    val startDate: String,     // 第 1 周周一，"yyyy-MM-dd"
    val totalWeeks: Int = 20,
    val firstDayOfWeek: Int = 1,
)
```

三个关键设计决策：

1. **`kind` 扩展而非新表**。实验课、考试都复用 `Course`：
   考试日期落 `weeks`（单元素）+ `day`，起止时刻落 `customStart/EndTime`
   （`isCustomTime = true`，今日页倒计时/时刻口径自动生效），考场落 `position`。
   加一种课程类型**不需要任何数据库迁移**。
2. **行号 = 小节号**。本校作息是 11 小节、每节 40 分钟（大节内歇 5 分钟、
   大节之间 20 分钟换教室）。网格第 N 行就是第 N 小节，与教务返回的
   `startSection/endSection` 直接对齐，不做任何折算。详见 DESIGN §3.5。
3. **课程行的身份不靠 `id`**——行 id 在覆盖导入（清表重建）与调课检测应用（整组重建）
   里会被换掉，稳定键只有两个：
   - 课程之间的同一性用 `Course.mergeKey()`（名称+星期+节次+教师+kind）：
     导入去重、导入统计与**备注搬运**共用这一把钥匙，唯一实现在 `domain/Models.kt`，
     不要再写第二份；
   - 笔记·课件与作业按**课程名原样字符串**归属，不带 courseId、不带 timetableId
     （DESIGN §4.20）：换课表/换学期后旧内容仍可查，代价是同名课程跨学期共用一个抽屉。
   加字段可以，任何新功能绑行 id 必丢数据。

### 3.2 Room schema（`data/local/JuwDatabase.kt`，当前 v8）

| 表 | 主键 | 说明 |
|----|------|------|
| `timetables` | `id` | 课表身份；`slotsCustomized` 标记用户改过作息 |
| `courses` | `id`（+ `timetableId` 索引） | 课程行：`timetableId` 归属；`weeksCsv` 逗号分隔周次；`kind` 课型；`remark` 备注 |
| `time_slots` | `(timetableId, number)` | 每张课表一份作息表（小节号 1–11） |
| `semester_config` | `timetableId` | 每张课表一份开学日/总周数 |
| `scores` | `id`（+ `term` 索引） | 成绩全局归属学生、不挂课表；按学期整体替换 |
| `detect_baselines` | `timetableId` | 调课检测的教务基线快照（每课表一份） |
| `detect_reports` | `timetableId` | 最新差异报告 + `unread` 未读标记（每课表一份） |
| `ykt_turnovers` | `orderId`（+ `jndatetime` 索引） | 一卡通流水；按服务端订单号去重 |
| `notes` | `id`（+ `courseName` / `updatedAt` 索引） | 笔记·课件，按课程名归属（§3.1 决策 3） |
| `homework` | `id`（+ `courseName` / `done` / `dueDate` 索引） | 作业，按课程名归属；`dueDate` 存 `yyyy-MM-dd` 文本（字典序即时间序） |

版本史（v1→v8 逐级迁移，每级一个 `Migration`）：v2 `courses.kind` → v3 多课表
（`timetables` + `courses.timetableId`）→ v4 成绩表 → v5 调课检测两表 →
v6 一卡通流水 → v7 笔记/作业 → v8 `courses.remark`。

迁移纪律两条：

1. **逐级 `ALTER TABLE` / `CREATE TABLE`，禁用 destructive migration**。
   用户设备上是真实课表，重建表式的迁移等于删库。主键变更（v2→v3 把作息表从全局单份
   改为每课表一份）只能「建新表 → 搬数据 → 改名」。
2. **实体 `@Index` 必须与迁移里的 `CREATE INDEX` 逐字对齐**（Room 生成名
   `index_<表>_<列>`），漏一个就迁移校验崩溃。2026-09-20 实测：`ykt_turnovers.jndatetime`
   漏声明，**只影响从 v5 升级的设备，全新安装不崩**——这类缺陷只在特定升级路径上暴露，
   改 schema 后必须逐级真机验证（旧版升上来 + 全新安装各一次）。

### 3.3 JSON 导入导出（与拾光课程表互通）

`ScheduleRepository.exportJson()/importJson()` 产出/读取的顶层结构（导出 = 当前课表）：

```json
{
  "courses": [ { "name": "...", "teacher": "...", "position": "...",
                 "day": 1, "startSection": 1, "endSection": 2, "weeks": [1,2],
                 "colorIndex": 0, "kind": "theory", "remark": "" } ],
  "term": "2026-2027-1",
  "scores":  [ { "term": "2025-2026-2", "name": "...", "scoreStr": "92", ... } ],
  "semester": { "startDate": "2026-09-01", "totalWeeks": 20, "firstDayOfWeek": 1 },
  "timeSlots": [ { "number": 1, "startTime": "08:30", "endTime": "09:10" } ]
}
```

- 课程字段名与领域模型一致，第三方课表 App（拾光）的用户可以互导；
- 所有段都是**可选键、只增不减**：读取侧 `ignoreUnknownKeys`，旧版 App 忽略新段、
  新版读旧文件缺省为空，双向兼容——`remark`、`semester`/`timeSlots` 都是这么加进来的，
  加字段时保持这条纪律；
- 课程导入支持「合并（按 mergeKey 去重）/覆盖」，成绩导入是**按学期整体替换**（先清后插），
  `semester`/`timeSlots` 应用到**目标课表**；
- 任一已存在的段校验不过（开学日期格式 / `TimeSlotRules` 不变量 / 成绩缺 term）→
  **整个导入拒绝**，不留「课程对了时间错」的半套；
- `term` 是来源学期标记（脚本/教务导出带的），只用于导入弹窗展示，不落库。

---

## 4. 链路 A：Python 爬虫（逆向参考实现）

代码在 `scripts/`（说明：[scripts/README.md](scripts/README.md)）。
这一节讲**可迁移的方法论**：即使你的学校不是强智教务，排查套路也是同一套。

### 4.1 登录链路（`jw_session.py`）

本校链路：CAS 统一认证 → 教务 SSO，共三步，每步都要显式校验：

```
[1] CAS：GET 门户落地页拿登录页 → POST username/password/execution/_eventId=submit
    → 302 后 session 里有了 TGC cookie
[2] SSO：先预热教务 :81 端口拿 bzb_njw cookie（缺了教务不认票据，这是「第一次必挂」的根因）
    → GET cas/login?service=http://jiaowu.juwp.edu.cn/sso.jsp   ← service 不能带端口，带了 500
    → 跟 302 链落到教务学生端 xsMainV.htmlx
[3] 校验：请求主页，确认不是「用户没有登录」的 860 字节退化响应（正常主页 ~150KB）
```

三条通用经验：

1. **`trust_env = False`**：开发机 shell 里被注入的 `HTTP_PROXY/HTTPS_PROXY` 会被
   `requests` 默认读取，而很多教务对代理出口区别对待，现象是「同一请求时而 200 时而
   404」，极易误判成教务挂了。所有 Session 显式直连。
2. **手动跟 302 而不是 `allow_redirects=True`**：保留每一跳落点，出问题能说出断在哪。
3. **每个环节都有可判定的成功标志**（cookie 名、落点 URL、主页字节数/标记文本），
   失败时报「断在哪一步」而不是笼统的「登录失败」。

### 4.2 理论课表解析（`fetch_courses.py` + `data/jw/QiangzhiScheduleParser.kt`）

页面：`GET /jsxsd/xskb/xskb_list.do?viweType=0`（学期参数 `xnxq01id`）。
课程块是 `li.courselists-item`，名称在 `.qz-hasCourse-title`，
详情在 `.qz-hasCourse-abbrinfo`（形如 `老师:张三;时间:1-10周[1-2节];地点:XX楼(B102)`）。

**星期必须从课程所在 `<td>` 的列序推**——这是本项目踩过最深的坑，规则值得抄走：

- 表格第 0 列是节次标签，第 1–7 列才是周一到周日；
- `li` 上的 `qz-hasCourse-N` class **几乎恒为 1**（模板拿它当「有课」样式，实测 33 处
  `-1`、2 处 `-3`），不能当星期来源；
- 列号要**累加 `colspan`**，并用一张 carry 表记录 `rowspan` 的跨行占用：
  强智在「同一天连续两大节上同一门课」时会合并单元格，不补偏移的话，
  该行之后所有课程的星期会整体前移一格。

核心算法（Python 与 Kotlin 注入 JS 是同一套，`fetch_courses.py:parse_courses`）：

```python
carry: dict[int, int] = {}          # 列号 → 还剩几行被上方 rowspan 占用
for tr in soup.select("tbody tr"):
    for key in list(carry):         # 每行开始先衰减占用
        carry[key] -= 1
        if carry[key] <= 0: del carry[key]
    col = 0
    for td in tr.find_all("td", recursive=False):
        while carry.get(col, 0) > 0: col += 1    # 跳过被上方合并占掉的列
        rowspan = int(td.get("rowspan") or 1)
        colspan = int(td.get("colspan") or 1)
        if rowspan > 1: carry[col] = rowspan
        if td.get("name") == "kbDataTd" and 1 <= col <= 7:
            ...           # 该格里的每个 li 就是一门课，day = col
        col += colspan
```

配套细节：

- 详情文本用正则拆 `老师/时间/地点`；周次串 `1-10`、`1,3,5-8` 展开成集合（限定 1..40）；
- 学期口径取 `select#xnxq01id` 的 selected 项——教务**会忽略未知学期参数**照常返回当前
  学期，所以脚本校验「请求学期 = 返回学期」，不一致直接报错而不是静默爬错学期；
- 用 `html.parser` 而不是 `lxml`：强智页面有双 doctype，lxml 会丢节点。

### 4.3 实验课表解析（`fetch_lab_courses.py` + `data/jw/SyjxScheduleParser.kt`）

页面：`GET /jsxsd/syjx/toXskb.do`。与理论课表**没有任何可复用之处**——
同一教务里两张课表页结构完全不同，解析逻辑不可互相套用。差异有三：

1. 没有 `td[name=kbDataTd]`，也没有 `老师:X;时间:Y;地点:Z` 合并文本；
   `abbrinfo` 里只有地点，**页面根本不提供教师字段**（保持空串而不是瞎猜）。
2. 表是「周次 × 节次」两级纵轴：每个周次占 6 行，周次标签是首行里带 `rowspan=6`
   的单元格。**课块不知道自己属于哪一周**，必须按行向上找周次标签。
3. 同一门课在每个有课周次各出一块，必须聚合：按
   `(名称, 星期, 起止节次, 地点)` 分组、`weeks` 取并集。
   聚合键**必须含地点**——实训课按批次分周上课，同名课会在不同实训室
   （实测一门课分布在三个房间），只按课名合并会丢掉地点差异。

星期换算公式（首行比其他行多一列周次标签，按行形态右对齐）：

```
day = td 下标 - (本行 td 总数 - 8)      # 8 = 节次标签 + 7 天
```

另：tooltip 里的「节次：60304」是页面内部编码，不是真实节次，只能取行标签。

### 4.4 考试与成绩（`fetch_exams.py` / `fetch_scores.py` + App 同名解析器）

两者都是 **layui 表格的 JSON 接口**，GET 即可，不解析 HTML：

```
考试安排  GET /jsxsd/xsks/xsksap_list?xnxqid=<学期>&xqlb=&pageNum=1&pageSize=200
课程成绩  GET /jsxsd/kscj/cjcx_list?kksj=<学期|空=全部>&kcxz=&kcsx=&kcmc=&xsfs=&pageNum=1&pageSize=200
响应      { "code": 0, "count": <总条数>, "data": [ ... ] }
```

三个必踩坑（换个强智学校大概率原样复现）：

1. **分页参数是 `pageNum`/`pageSize`**（强智 `window.initQzTable` 自定义），
   用 layui 默认的 `page`/`limit` 拿不到数据；超出单页按 `count` 翻页。
2. **接口地址不带 `.do`**。带 `.do` 的同名地址返回「系统功能暂未开放」页面——
   那是校方的功能开关，必须与「接口正常但 `count=0`」区分开，报错文案不能混。
3. 考试时间 `kssj` 是单字符串 `"2026-05-18 08:30~09:55"`，拆成 date/startTime/endTime 再用；
   成绩是双字段：`zcj`（数值）+ `zcjstr`（字符串，等级制「优」时数值为空），
   **展示一律以 `zcjstr` 为口径**；`kz=1` 表示「请评教」，成绩被锁定不显示分数。

关键字段对照（强智原名 → 语义）见 [scripts/README.md §5.4](scripts/README.md#54-考试安排与课程成绩2026-09-19-实测)。

---

## 5. 链路 B：App 端 WebView 导入（生产路径）

实现集中在 `ui/jwvw/JwImportScreen.kt`（约 1200 行，导入全流程）+
`data/jw/` 各解析器。流程：**用户登录 → 判页 → 注入抽取 → Kotlin 解析 → 确认 → 入库**。

### 5.1 入口与会话

- 导入窗口是**独立 Activity**（`JwImportActivity`），不用 NavHost 子页：
  导入时主界面布局完全不动，写库走 `Graph` 单例 + Flow，返回后课表自动刷新。
- 首次加载从 **预热入口**（`JwUrls.SSO_WARMUP`，即教务域 `sso.jsp`）进而不是 CAS 直链：
  WebView 从未访问过教务域时，CAS 回跳的第一次 `sso.jsp?ticket=` 会 500（缺 `bzb_njw`
  cookie），而那个 500 响应会顺手写下 cookie——这就是「第一次必挂、刷新又好」的全部原因。
  先落教务域再跳认证，把这个竞态消掉。
- WebView 配置要点（`configureForJw`）：
  `useWideViewPort + loadWithOverviewMode`（教务页没有 viewport meta，是固定宽桌面布局）；
  `setLayerType(LAYER_TYPE_SOFTWARE)`（MIUI 上硬件合成层会丢，表现为「功能正常但整面白屏」）；
  CookieManager 接受第三方 cookie（CAS 与教务是两个域）。
- **会话探测**：教务把「未登录」就地渲染成 HTTP 200 的登录页（URL 不变），
  所以每次 `onPageFinished` 先注入探针检查 `#loginDiv`/`input[type=password]`
  （不要按文案找——实测登录页上没有「用户没有登录」五个字），且**只在教务域判定**，
  在 CAS 域判定会把用户正在登录误报成会话失效。

### 5.2 DOM 抽取：`evaluateJavascript` 注入

点击导入时，按当前 URL 判定页面类型（`JwUrls.schedulePageKind`）选择注入脚本：

```kotlin
val extractJs = when (pageKind) {
    JwSchedulePage.Theory -> QiangzhiScheduleParser.EXTRACT_JS
    JwSchedulePage.Lab    -> SyjxScheduleParser.EXTRACT_JS
    ...
}
webView.evaluateJavascript(extractJs) { raw -> ... }   // raw 是包了一层引号的 JSON 字符串
```

注入脚本与 §4 的 Python 解析是同一套算法（列序 + colspan/rowspan carry），
返回 `JSON.stringify({ ok, items, term, url })`；Kotlin 侧用
`kotlinx.serialization` 解析（`unwrapJsString` 先剥掉外层引号与转义）。
每个解析器还提供一份正则版 `parseFromHtml`，供 JVM 单测跑 HTML fixture、不依赖真机。

经验：**两张课表的 URL 都含 `xskb`**（理论 `xskb_list`、实验 `toXskb`），
不能用子串判型，必须按页面特征分别匹配。

### 5.3 同源 fetch JSON（考试/成绩）

考试与成绩不解析 DOM，直接复用用户的登录态发同源请求。难点：
`evaluateJavascript` **不会 await Promise**，异步结果收不回来。方案是
「注入 fire-and-forget 的 fetch 脚本 + Kotlin 轮询窗口变量」：

```js
// FETCH_JS：结果写进 window.__qzJson（"ERR:..." 表示失败）
window.__qzJson = null;
fetch('/jsxsd/xsks/xsksap_list?xnxqid=...&pageNum=1&pageSize=200',
      { credentials: 'same-origin' })
  .then(function(r){ return r.text(); })
  .then(function(t){ window.__qzJson = t; })
  .catch(function(e){ window.__qzJson = 'ERR:' + String(e); });
```

```kotlin
// Kotlin 侧：每 300ms 读一次，最多 60 轮（≈18s 超时）；页面不跳转，窗口变量不会丢
suspend fun fetchJsonInWebView(wv, fetchJs, readJs): String? { ... }
```

安全细节：学期号先从壳页下拉读出，再用**白名单正则** `\d{4}-\d{4}-\d` 校验，
才允许拼进注入 JS 的字符串——既防脏数据落库，也从根上杜绝引号注入。

### 5.4 确认与入库

解析结果不直接写库，先过确认弹窗（`ImportTargetDialogHost`）：

- 用户**强制选择**目标课表（可新建）与合并/覆盖方式——多课表之后
  「导到当前课表」不再是唯一合理解释，静默覆盖正在用的数据不可接受；
- 弹窗展示解析出的条数与页面学期（`term`）；
- 考试导入在确认时用**目标课表**的开学日重新映射周次（预览口径 ≠ 落库口径）；
- 入库走 `ScheduleRepository.importParsedCourses`：合并按 mergeKey 去重，
  覆盖整体替换；整批颜色按课程名排序名次分配（§6.4）。

### 5.5 失败呈现与自愈

导入链路大部分故障来自 WebView，处理原则：

- **HTTP 4xx/5xx 只有 `onReceivedHttpError` 能看到**——Chromium 把 5xx 响应体当普通
  页面渲染，`onPageFinished` 照常回调，`onReceivedError` 只管传输层错误；
- 失败页的 `onPageFinished` 仍会到达并把错误态「洗成正常」，需要按
  失败 URL 标记 + 相等匹配一次性消费（实测回调顺序是
  `onReceivedHttpError → onPageStarted → onPageFinished`，错误先到）；
- CAS ticket 是一次性票据：错误浮层的「重试」区分「reload 失败页」与
  「回认证入口重新走链路」，重放已消费的 ticket 只会再挂一次；
- 认证链上的 500 是**自愈型**（响应同时写下缺失的 cookie），自动重试一次，
  真故障时不无限重试打服务端；
- 诊断文案集中在 `data/jw/JwImportDiagnosis.kt`，含 VPN/代理场景识别（`JwVpnDetector`）。

### 5.6 调课自动检测：脚本链路的 App 内复刻（2026-09-19）

默认关闭的后台链路（DESIGN §4.17）：OkHttp 直接登录教务，拉理论+实验课表，与本地做
**三方合并**（基线快照 / 教务现状 / 本地现状），差异经气泡与通知提示、用户确认才写库。
对本文而言重点是它**复用了同一套页面规则**——换校时这是除 WebView 之外要同步改的第二处：

- `data/jw/JwHttpSession.kt` 复刻 `scripts/jw_session.py`：CAS 表单 → 预热 `:81`
  拿 `bzb_njw` → SSO ticket → `sso.jsp` 302 链 → 会话校验（`xsMainV` 字节数阈值）；
  独立 `CookieJar`（与 WebView 的 `CookieManager` 互不干扰），每次检测全量重登。
- 两个曾让开启流程 100% 失败的坑，换校时大概率原样复现：
  1. **重定向基准写反**：`current.resolve(location)` 误写成 `location.resolve(current)`，
     每轮回到原 URL，误报「重定向次数过多、链路可能已变」；
  2. **校园 IPv6 黑洞**：校园域同时有 A/AAAA 记录而 v6 在移动网络不可达，OkHttp 默认
     v6 优先建连，每步白等约 31 秒超时。修法 `Ipv4FirstDns`（v4 排前、AAAA 不丢弃）。
- 凭证 EncryptedSharedPreferences 加密存储、排除云备份；**连续 3 次凭证错自动停用**
  （防触发验证码锁号）；代理出口命中时检测前直接跳过，不消耗失败计数。
- 基线/报告的刷新时机只有两个：教务导入确认落库后、应用检测报告后——用户手动调课
  **不**刷新基线，这是三方合并能区分「教务改课」与「用户自己调课」的全部前提。

---

## 6. 核心算法与口径（改代码前必读）

这些是全 App 共享的**唯一口径**，任何新功能都应复用而不是重写：

1. **课程时间只有一条口径**：`ScheduleCalculator.courseStartMinutes / courseEndMinutes`
   （自定义时间课以 `customStart/EndTime` 为准，否则查作息表）。
   排序、行内时刻、倒计时、进度条、下一节判定全部走它。
   自己再写一个 `isCustomTime` 分支，自定义时间课就会排错序。
2. **周次计算**：`weekNumberOf` = `startOfWeek(开学日)` 到 `startOfWeek(目标日)` 的周数 + 1，
   两侧都归一化到所在周的周一（`startOfWeek`），开学日填一周内的哪天都不影响周次。
3. **网格列位置**：可见星期序列以 `ScheduleCalculator.visibleDays` 为唯一来源、
   `columnOf` 取列下标。允许单独隐藏周六/周日之后，`day - 1` 不再等于列号
   （隐藏周六但显示周日时整体错位）。
4. **配色必须稳定可复现**：不能用 `name.hashCode()`（JVM 字符串哈希带随机盐，
   两次运行结果不同；且中文课名 12 桶内必撞色）。批量导入用
   `colorIndexesBySortedName`（按课程名排序取名次，同一份课表每次导入颜色一致），
   单条新增用 `nextColorIndex`（不占用已用色），渲染期撞色兜底用
   `weekColorOverrides`（只影响显示、不改库）。
5. **作息表结构版本**存 DataStore（`DisplayPrefsStore.slotSchemaVersion`）：
   改默认作息时要同时升版本号并写一次性迁移，老安装才能拿到新表；
   用户自定义过（`slotsCustomized`）则永不覆盖。
6. **课程行的稳定身份与搬运纪律**（细节见 §3.1 决策 3）：`Course.mergeKey()` 唯一实现在
   `domain/Models.kt`（导入去重、导入统计、备注搬运共用同一把钥匙）；覆盖导入
   （`replaceAllCourses`）与调课检测应用（`applyDetectGroups`）**必须**经
   `domain/courseRemarksCarriedOver` 把课程备注搬回来，少了这一步的表现是
   「导入一次备注全没了」。
7. **提示只有一条通道**：页面级提示统一走 Scaffold 的 `AppSnackbarHost`
   （`ui/common/AppNotice.kt`），语气四档 `NoticeTone`；不要新引入 `android.widget.Toast`。
   `ModalBottomSheet` / `AlertDialog` 是更高一层的独立窗口，Snackbar 会被它盖住——
   弹层内的提示用 `InlineNoticeRow`（或先关弹层再提示）。

---

## 7. 考试安排映射

考试没有独立实体，`domain/ExamMapper.kt` 把接口行映射成 `Course(kind = Exam)`：

| 接口字段 | 落点 |
|----------|------|
| 考试日期 | 按学期配置反推 → `weeks`（单元素）+ `day` |
| `kssj` 起止时刻 | `customStartTime/EndTime`（`isCustomTime = true`），今日页时刻/倒计时口径自动生效 |
| 起止时刻 | 按作息表映射到**相交小节**（半开区间判定，如 08:30~09:55 → 1-2 节）；落在空档时取下一节兜底 |
| 考场 `js_mc` | `position` |

日期超出学期范围（或开学日未配置）时返回 null：导入层跳过并计数提示，
不静默丢弃也不落错周。历史/未来学期没有开学日配置时，按学期号
（如 `2026-2027-1`）**估算开学日**兜底，并在确认弹窗明示「可能与实际相差一两周」。

---

## 8. 换一所学校：适配指南

把本仓库改造成你自己学校的课表，工作量集中在**数据获取层**；
UI、存储、小组件等全部可以原样复用。建议顺序：

### 第 1 步：认清你的教务系统

浏览器登录教务，看两样东西：

- **URL 形态**：`/jsxsd/...`、`.do` 结尾 → 强智（本项目）；`xskbcx.aspx` → 正方；
  其他常见厂商还有青果、金智、URP 等，各自有一套页面族。
- **页面渲染方式**：服务端直出 HTML 表格（照 §4 逆向 DOM），或前端调 JSON 接口
  （照 §4.4 抓包逆向接口）。很多系统两者混用——像本校：课表是 DOM，考试/成绩是 JSON。

### 第 2 步：存快照、跑通 Python 链路

1. 登录后把目标页面「另存为」完整 HTML，放进 `scripts/out/`（已 gitignore）当 fixture；
2. 仿照 `jw_session.py` 写你学校的登录链路（CAS 通常大同小异，注意 `service` 参数）；
3. 仿照 `fetch_courses.py` 写解析：先跑通「能拿到课程名列表」，再补星期/周次/节次。
   排错时对照快照与 `*_raw.json` 中间产物，而不是凭想象猜 DOM。

### 第 3 步：App 端替换常量与解析器

| 要改的位置 | 内容 |
|------------|------|
| `data/jw/CourseImporter.kt` 的 `JwUrls` | CAS/SSO/课表/考试/成绩全部 URL 与 `schedulePageKind` 的判型规则 |
| `QiangzhiScheduleParser.EXTRACT_JS` 等注入脚本 | 换成你学校页面的 DOM 选择器与字段抽取 |
| `ExamScheduleParser` / `ScoreParser` | JSON 接口地址、参数名、字段名 |
| `data/jw/JwHttpSession.kt` | 只有要做「调课自动检测」才需要：同一条登录链路的 OkHttp 复刻（§5.6），与上面几行同步改 |
| `data/DefaultData.kt` | 作息表（从教务页行标签逐节核对，不要拍脑袋）与默认开学日 |

解析器契约保持不变：注入 JS 返回 `{ok, items, term}`，Kotlin 侧把每条数据转成
`Course`。下游（确认弹窗、入库、网格渲染）完全不用动。

**不做校园生活模块**（胖乖/一卡通/快趣）时：删掉 `data/qiekj/`、`data/ykt/` 与
`ui/water|campus|ebike/`、今日页底部固定区的对应入口即可，课表链路零牵动；
若要改 Room 实体，务必遵守 §3.2 的迁移两条纪律。

### 第 4 步：换品牌信息与包名

- `applicationId`、应用显示名（`app/src/main/res/values/strings.xml`，debug 变体在
  `app/src/debug/res/values/strings.xml`）；AGENTS.md 记录了 debug 后缀共存的机制；
- 免责声明改挂你自己的学校；**不要**保留「江西水利电力大学」字样或本项目的
  教务地址（否则你的用户会连到我们的教务）。

### 第 5 步：用单测锁住解析器

把第 2 步的 HTML 快照裁剪后放进 `app/src/test/` 当 fixture
（先抹掉学号姓名等个人信息），仿照 `QiangzhiScheduleParserTest` 写断言。
教务改版时：重抓快照 → 跑测试看哪些断言挂了 → 改解析器。**对着过期结构改代码
是教务解析的头号事故来源。**

---

## 9. 测试

```powershell
.\gradlew.bat :app:testDebugUnitTest          # 纯 JVM，秒级
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline
```

覆盖面（57 个测试类，`app/src/test/`）：

- **解析与数据链路**：`QiangzhiScheduleParserTest` / `SyjxScheduleParserTest`（HTML fixture）、
  `ExamScheduleParserTest` / `ScoreParserTest`（注入 fetch JSON 样例）、
  `JsStringDecodeTest`、`JwHttpSessionTest`（检测登录链路：重定向解析 / IPv4 优先 DNS）、
  `JwImportDiagnosisTest`、`ImportJsonShapeTest`（互通 JSON 形状）；
- **课表领域算法**：`ScheduleCalculatorTest`（周次/时刻/配色）、
  `TimeSlotRulesTest` / `TimeSlotScheduleTest`（作息不变量）、`WeekGridLayoutTest`（网格几何）、
  `TodayStateTest` / `TodayBoundaryTest`、`ExamMapperTest`（含历史学期开学日估算）、
  `ScoreCalculatorTest` / `ScoreGroupsTest`、`CourseTweakTest`（调课规划）、
  `ScheduleDetectTest`（三方合并：归因/冲突/不误报）、`ScheduleExporterTest`（日历/CSV 展开）、
  `ReminderPlannerTest`（提醒时刻与窗口）、`CalendarSyncDefaultsTest`、
  `TimetablePrefsDefaultsTest`、`ScheduleBackgroundTest`（背景图：参数夹取 / 模糊档位到解码尺寸 /
  文件名白名单）、`ShortcutsTest`、`GridFontDecouplingTest`；
- **笔记·作业**：`NoteExcerptTest`、`MarkdownParserTest` / `MarkdownEditTest`（自研子集
  与编辑器补全全分支）、`MathTexTest`、`HomeworkCenterTest` / `HomeworkReminderTest`、
  `CourseRemarkTest`（备注搬运：mergeKey 匹配 / kid 区分 / 不覆盖新行）；
- **校园生活**：`QiekjSignTest` / `QiekjModelsTest`、`YktKeyboardTest` / `YktModelsTest` /
  `YktPayCodeTest` / `YktRechargeSignTest` / `YktTurnoverSyncerTest`、`EbikeQrTest` /
  `EbikeFreeRideTest`、`BikeNearbyTest`（附近车辆响应容错 / 停车点聚簇 / 距离与状态推导）、
  `KqcxBikeClientTest`（失败分类：超时不能吃成网络不可达）、
  `Gcj02Test`（WGS84 → GCJ-02：境外不偏移 / 境内偏移量级 / 邻近两点相对距离不变）、
  `PowerModelsTest`（电费响应解析：项目 / 读数 / 流水 + 500 与 401 外壳 + 剩余电量键回退）、
  `LifeFeedTest`（一卡通与电费流水混排：排序 / 限量 / 同刻稳定 / 解析失败沉底）、
  `DisplayPrefsDefaultsTest`（生活页默认开 + 既有开关默认值契约）、
  `PowerClientUrlTest`（缴费页 / 账单页深链形态与 feeitemid 钉子）；
- **UI 边界**：`WidgetModelTest`（小组件分档/行数/明日接棒）、`ParseWeeksInputTest`、
  `CompactPositionTest`、`PanelSnapTest`、`GridFontScaleTest`。

结论从 `app/build/test-results/testDebugUnitTest/*.xml` 汇总
（Gradle 成功时不打印用例数；读 XML 记得 `-Encoding UTF8`）。

---

## 10. 构建环境与已知坑

| 项 | 现值 / 坑 |
|----|-----------|
| Gradle / AGP | Wrapper 8.10.2 / AGP 8.7.3 |
| Kotlin | 2.1.21（compose / serialization / KSP 同版本） |
| Room | **2.7.1**——2.6 配 Kotlin 2.1 会 KSP `unexpected jvm signature V` |
| HugeIcons | `com.github.rikkahub:hugeicons-compose:1.4`（JitPack，**必须 `isTransitive = false`**，否则拉 androidx.core 1.17 编不过）；查名用 `.agents/skills/find-hugeicons/SKILL.md` 的本地 JAR 方法 |
| Glance | 1.2.0（传递抬 compose runtime 到 1.7.8，`androidx.core` 保持 1.15.0） |
| debug/release | 两个 applicationId（`.debug` 后缀），可共存；启动 debug 包必须写全限定 Activity 名（`am start -n edu.jxslu.schedule.debug/edu.jxslu.schedule.MainActivity`） |
| WebView | MIUI 白屏 → 软件渲染兜底；教务页无 viewport meta → `useWideViewPort` 方案 |
| 离线构建 | 依赖齐备后加 `--offline` 秒级完成：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline` |
| 系统权限 | 2026-09-23 起**不需要任何 adb 授权**：快趣出行的「助手通道」（`WRITE_SECURE_SETTINGS` + 改写 `Settings.Secure.assistant`）已随内置单车地图上线而删除，只剩桌面启动意图。详见 DESIGN §3.9 |
| 运行时权限 | 只有「附近单车地图 → 定位到我的位置」会在**点击那一刻**申请定位权限（精确/粗略任一即可），进页不弹框；不给也能用——地图默认落在校区中心，拖动照常查车。见 DESIGN §4.23 |
| release 体积 | 自 2026-09-21 开 R8 + 资源压缩：17.9MB → 3.6MB（见 §10.1） |

### 10.1 R8（release 自 2026-09-21 开启）

改 `proguard-rules.pro` 后**必须装 release 包冒烟，且要冒到真实网络路径**——R8 的问题不在
编译期暴露。已经踩过的坑，换校加自己的 API 模型时会原样找上来：

1. Tink 引用的 errorprone 注解、KeysDownloader 的可选依赖缺失 → `-dontwarn` 收口即可；
   不要写成 `-keep class com.google.crypto.tink.**`，那会把缺口一起保住。
2. **只被泛型签名引用的模型类会被整类删除**（2026-09-21 的真实根因）：R8 静态分析看不到
   使用者（Retrofit / 序列化都走运行期反射），删掉 `data/qiekj` 的模型类后
   `ApiEnvelope<EmptyData>` 的签名实参退化成 `Object`，调用时抛
   `Unable to create converter for ApiEnvelope<java.lang.Object> for method …`——
   **只有用到被删类型的接口会炸**，看起来像「某个功能坏了」而不是「混淆炸了」。
   修法 = 整包 keep（`-keep class edu.jxslu.schedule.data.qiekj.**`）。
   定位手法：release 临时加 `-printusage`，报告里**没有冒号的行**就是被整类删除的类
   （用完删掉，它会把路径写进仓库文件）。
3. 冒烟要覆盖「开了混淆才走到的分支」；DataStore / 课表那条 JSON 链是**编译期** serializer，
   不受影响——别只测它们就以为序列化没事。

---

## 11. 安全与合规红线

- 凭证只进 gitignore 的 `scripts/credentials.local.json`；**App 端代码里永远不出现
  学号/密码/token**，登录默认由用户在 WebView 里亲手完成（自动检测是显式开启的例外，
  凭证经 EncryptedSharedPreferences 加密存本机、排除云备份）；
- 公开仓库的文档、注释、示例里不得出现真实学号、姓名、手机号；抓取产物
  （`scripts/out/`，含真实数据）不入库；
- 校园卡凭证单独加密存储（`ykt_credentials.xml`，同样排除备份）；付款码等同现金——
  不进日志、不进剪贴板/相册，token 只存内存；验证码类响应（8002/8003）**绝不重试**，
  开启类交互照 `TweakDetectScreen`（开启先真实验证、关闭即清除）；
- 模拟登录/抓取以「正常客户端」为限：不刷积分、不绕过付费、不伪造官方身份、
  不对教务接口做高频请求；
- 对外发布你的改编版时，同样写明非官方声明，使用风险自负。

---

## 参考

- [scripts/README.md](scripts/README.md) —— 爬虫速查：登录链路、DOM 规则、排错表（比本文更细）
- [DESIGN.md](DESIGN.md) —— 产品与界面规格（§3 UI、§4 技术架构逐模块决策记录；
  与本文互补：§4.17 调课检测、§3.11/§4.20 笔记·作业与作业提醒）
- [AGENTS.md](AGENTS.md) —— 给 AI 结对工具的工程约定（版本实况、口径清单）
- [拾光课程表](https://github.com/XingHeYuZhuan/shiguangschedule) —— JSON 互通格式参照
