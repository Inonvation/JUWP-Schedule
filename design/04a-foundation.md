# DESIGN §4.1–§4.10 · 技术架构：基座 · 领域模型 · 教务导入

> 本文档是 [DESIGN.md](../DESIGN.md) 的拆分件。代码注释里的「DESIGN §x.y」章节号一字未改，索引见 DESIGN.md 的映射表。

## 4. 技术架构

### 4.1 工程形态

- 单模块 `:app` 起步（M1 不搞多 module 仓库，降低脚手架复杂度）
- Kotlin 2.x + AGP 8.x + Compose BOM
- minSdk 26（Android 8+，对齐拾光），targetSdk 35 附近（以当时稳定版为准）
- 包名：`edu.jxslu.schedule`（已确认）；debug 构建变体加 `.debug` 后缀，与正式包可共存安装
- 应用名：**水贝贝**（已确认）/ 工程名 JUWP Schedule；debug 变体显示「水贝贝 Debug」

### 4.2 分层

```
ui/                 Compose Screen + ViewModel
  today/ week/ settings/ water/ courseedit/ jwvw/
domain/             纯 Kotlin 模型与用例（Course, Semester, ...）
data/
  local/            Room 或 DataStore（课程、配置、胖乖 token）
  remote/           Retrofit + OkHttp
    jw/             教务相关（M1 主要是 WebView + JS，未必有纯 HTTP）
    qiekj/          胖乖 API
  repo/             Repository 汇聚
core/               Result 封装、日志、调度（WorkManager 可选）
```

原则：

- UI 不直接碰 Retrofit
- 教务解析结果必须先变成 `domain` 的 `Course` 再入库
- 网络错误一律可展示、可重试；禁止吞异常

### 4.3 课表领域模型（对齐拾光互通）

```kotlin
data class Course(
  val id: Long,
  val name: String,
  val teacher: String,
  val position: String,
  val day: Int,              // 1=周一 … 7=周日
  val startSection: Int,
  val endSection: Int,
  val weeks: Set<Int>,
  val isCustomTime: Boolean = false,
  val customStartTime: String? = null,
  val customEndTime: String? = null,
  val colorIndex: Int = 0,
  val remark: String = "",   // 课程备注（2026-09-21 新增，见下方说明）
)

data class TimeSlot(val number: Int, val startTime: String, val endTime: String)
// number 是**小节号 1–11**（见 3.5），不是大节号；一条一个 40 分钟小节

data class SemesterConfig(
  val startDate: String,     // yyyy-MM-dd
  val totalWeeks: Int,       // 默认 20
  val firstDayOfWeek: Int,   // 1=周一
)
```

JSON 导入导出字段名与上述一致，便于与拾光用户互导。

> **成绩备份段（2026-09-19）**：导出文件顶层可带可选 `scores` 数组（每条含
> `term / name / credit / score / scoreStr / gradePoint / pendingReview` 等，口径见 §4.15）。
> 读取侧 `ignoreUnknownKeys`：旧版 App 忽略该段、新版读旧文件该段缺省为空，两版互不破坏。
> 导入时成绩**整体替换**（先清后插），确认弹窗注明「N 个学期共 M 条」。
>
> **学期配置与作息备份段（2026-09-19）**：导出顶层再带可选 `semester`
> （`startDate / totalWeeks / firstDayOfWeek`）与 `timeSlots`（`number / startTime / endTime`，
> 小节号口径同 §3.5）两个键，导出的是**当前课表**的配置；导入时应用到**目标课表**，
> 恢复作息同时置位 `slots_customized`（防结构性迁移覆盖用户数据）。旧文件没有这两键
> → 不动目标课表配置；段落带值但校验不过（开学日期格式 / `TimeSlotRules`）→
> **整个导入拒绝**，不留「课程对了时间错」的半套。

> **`Course.kind` 扩展（DESIGN §4.14）**：`CourseKind{Theory, Lab, Exam}`。
> 考试用既有字段承载：日期落 `weeks`(单元素)+`day`、起止时刻落 `customStart/EndTime`
> (`isCustomTime=true`)、节次按作息表映射、考场落 `position`；无独立表，无 schema 迁移。

> **`Course.remark` 扩展（2026-09-21，课程备注）**：给课程行加一条**用户自写备注**
> （如「带计算器」「考试范围：第 3 章」），在课程编辑弹层里填写、课程详情弹层里展示
> （§3.3）。存储落在 `courses.remark`（Room v8，`ALTER TABLE ... DEFAULT ''`）；
> JSON 侧同为可选键 `remark`，缺省空串——旧文件照读、新版文件对拾光侧是未知键（对方忽略），
> 与 `scores`/`semester` 段同一套「只增不减、缺省兼容」的互导纪律。
>
> **备注的存活口径（与笔记/作业同一条纪律）**：课程行 id 不稳定——覆盖导入
> （`replaceAllCourses` 清表重建）会换掉行，所以这条路径**按 `mergeKey` 把旧备注搬回来**
> （`domain/courseRemarksCarriedOver`，
> 与导入去重同一把钥匙）。语义上的取舍：备注属于**课程行**（同一门课的不同时段各写各的），
> 不是"课程档案"；跨表/换学期后旧备注随旧行存在，不主动迁移。

### 4.4 教务导入（WebView + JS）

**2026-09-17 实测结论（已用真实学生账号跑通全链路）：**

| 项 | 值 |
|----|-----|
| 统一认证 CAS | `https://eapp2.juwp.edu.cn:9443/cas/login?service=...` |
| 门户 | `http://portal.juwp.edu.cn`（HTTP；443 不通） |
| 教务系统 | **强智科技** `https://jiaowu.juwp.edu.cn:81/`（登录页）→ SSO 后 `http://jiaowu.juwp.edu.cn:8080/jsxsd/` |
| 课表页 | `GET /jsxsd/xskb/xskb_list.do?viweType=0`（学期理论课表 / 个人课表信息） |
| SSO service | 必须用 `http://jiaowu.juwp.edu.cn/sso.jsp`（**不要**带 :81/:8080，否则 500） |
| 关键 cookie | 先访问 `:81/` 取 `bzb_njw`，再跟 CAS ticket |
| 直登 Logon.do | 页面有交织编码，直登报「帐号不存在或密码错误」；**统一认证密码可用，教务独立密码可能不同** |
| 爬虫脚本 | `scripts/fetch_courses.py`（凭证放 `scripts/credentials.local.json`，已 gitignore） |
| 导出样例 | `scripts/out/courses.json`（2026-2027-1，约 29 条课次） |

**导入窗口锁竖屏（2026-09-19）**：`JwImportActivity` 声明 `screenOrientation="portrait"`——
转屏会销毁 WebView（统一认证密码输到一半全没）并取消 `rememberCoroutineScope` 里的导入协程。
全 App **唯一**锁方向的窗口，理由如上；其余页面保持不锁。

课表 DOM 规律（强智 newL）：

- `li.courselists-item` + `div.qz-hasCourse-title` 课程名  
- 父级 `qz-hasCourse-N` → 星期 N  
- `span.qz-hasCourse-abbrinfo`：`老师:X;时间:1-10周[1-2节];地点:...`  
- 解析 HTML 用 `html.parser`（页面双 doctype，lxml 会丢节点）

流程：

1. App 内 WebView 打开教务/门户 CAS，用户登录  
2. 门户应用中心「教务管理系统」→ `https://jiaowu.juwp.edu.cn:81/sso.jsp`  
3. 或脚本：CAS TGC + bzb_njw → sso.jsp ticket → 8080 xsd  
4. 打开 `xskb_list.do?viweType=0`，解析 DOM → `List<Course>`  
5. 预览确认 → 写库  

抽象：

```kotlin
interface CourseImporter {
  val id: String
  val displayName: String
  suspend fun import(session: WebSession): ImportResult
}
```

M1：**ManualImporter** + **QiangzhiJsxdImporter**（按上表路径）。

**仍需注意：**

- Android WebView 用用户自己的 CAS 登录，不要在代码里写死密码  
- Python 爬虫仅本机调试；凭证不进 git  
- 开学日/总周数/作息表：可从课表周次反推或设置手填

#### 4.4.1 导入失败的错误呈现（2026-09-18 实测定位，含两轮修正）

教务链路上的失败有四种**外观完全不同**的表现。初版实现只处理了传输层一种，
其余被呈现成别的样子——用户看到的是"能用的页面"或"空页"，而不是错误：

| 现象 | 真实原因 | 旧实现的表现 | 现方案 |
|------|----------|--------------|--------|
| 「500 error System Error.Please Wait…」 | `sso.jsp?ticket=` 票据校验失败（HTTP 500）。**票据一次性，重放必 500**；该 500 是自愈型：响应同时写下 `bzb_njw`，重走一遍即通过 | 没有重写 `onReceivedHttpError`，HTTP 层错误完全看不见 → 状态条显示「已登录教务…」（与事实相反） | `onReceivedHttpError`（仅主 frame，`>= 400`）→ 错误浮层；认证链上的 5xx 先**自动重试一次**（`shouldAutoRetry`） |
| 「网页无法打开 / ERR_CONNECTION_TIMED_OUT」 | 传输层失败 | `onReceivedError` 置了错误态，但失败页随后仍回调 `onPageFinished` 无条件置 `Ready` 把浮层盖掉 | `lastFailedUrl` 记失败 URL，`onPageFinished` **按 URL 相等一次性消费**后返回 |
| URL 不变、页面却是登录框 | 未登录时教务**不跳转**，把登录页就地渲染在 `xsMainV`/`xskb_list` 上（HTTP 200、`#loginDiv` + 密码框） | 按 URL 判定为「已登录主页」并自动跳课表 → 与课表页的登录页**互跳成环**（实测 18 次导航、约 1 秒一轮） | 会话探针先于所有自动导航执行，`looksLikeLoginPage` 命中即报会话失效 |
| 卡在教务主页、状态条一直「加载中…」 | 自动落页挂在 `onPageFinished` 的会话探针回调里；该回调被挂住的子资源拖住（或延迟复核发现 URL 已不是 `xsMainV`）时，旧实现把状态留在 `Loading` 且**再无任何触发点**（2026-10-01 用户报障：点「进入选课中心」不跳，手动再点一次才走） | 「进入选课中心」等自动落页入口偶发卡死 | 触发点提前到 `onPageCommitVisible`（内容可见即算落地）；复核失败/看门狗不再留在 Loading（见下） |
| 顶栏标题变「500错误」 | 服务端错误页的 `<title>` 被 `onReceivedTitle` 如实反映 | — | 保留（作为辅助线索） |

**回调顺序是本块的关键坑**（实测）：`onReceivedHttpError` → `onPageStarted` → `onPageFinished`，
即**错误先到、start 后到**。首版把清标记写在 `onPageStarted`，等于守卫被下一次回调拆掉，
错误浮层被洗成「已登录教务…」。因此标记只能**按 URL 相等**在 `onPageFinished` 里消费
（既顶住重复失败轮次，也不误伤成功重试），**不要**在 `onPageStarted` 里清。

**自动落页的触发点用 `onPageCommitVisible`，不要只等 `onPageFinished`**（2026-10-01，上表第 4 行）：
`onPageFinished` 会被挂住的子资源拖住，迟迟不来时旧实现（`postDelayed` 写在它的回调里）就永远
停在 `onPageStarted` 写的「加载中…」，且**没有任何后续触发点**。现在两个触发点共用
`JwImportScreen.startAutoNav`：`onPageCommitVisible` 首选、`onPageFinished` 的探针回调兜底；
会话探针守卫与「只跳一次（`autoNavDone`）」纪律不变，顶栏刷新/错误浮层重试会把 `autoNavDone`
放开重试一次；延迟复核失败与 10 秒看门狗（`AUTO_NAV_TIMEOUT_MS`）都把状态解锁成 `Ready` +
可操作提示——**任何路径都不允许悬在 Loading 且无出口**。

**重试目标是预热入口，不是 CAS 直链**：CAS ticket 一次性（重放恒定 500）。
`JwImportDiagnosis.retryUrl()` 裁决——认证链上的失败一律回 `JwUrls.SSO_WARMUP`
（先落教务域写 `bzb_njw`，再由页面 JS 跳同一个 CAS），只有已登录后的页面
（如课表页自身 5xx）才就地重放。

**会话判定的特征必须与探针输出逐字对齐**：探针输出 token（`loginDiv` / `password`），
判据用「与」（两者齐备才算）。踩过的两个坑：① 曾用文案「用户没有登录」判定，
而该文案在真实页面上出现 **0 次**，判据从未生效；② 探针输出 `password` 而匹配器
找 `type="password"`，两边不一致 → 判定恒 false，且**不报错**（静默失效）。
另外判定只限教务域（`JwUrls.isJwHost`，host 精确比较）——CAS 登录页本来就有密码框，
在那儿判定会把「正在登录」误报成「会话失效」。

**出口问题**（两条链路的共同诱因）：移动端开着第三方 VPN/代理时统一认证超时、
SSO 落点 500；关闭即恢复。`JwVpnDetector` 在失败路径探测 `TRANSPORT_VPN` 并点名提示
（需 `ACCESS_NETWORK_STATE`）。同时注意**取证环境本身会污染结论**：本机开着代理时
用 curl 测得的结果与手机直连不一致，此类对照实验必须在干净出口（如手机）复做。
脚本侧同一现象见 §7.6 与 `scripts/README.md` §4。

失败正文里的排查动作**按探测结果分两句**（2026-09-27，口径统一在
`data/session/NetworkHint.kt`）：开着代理 → 点名先关掉它；没开 → 让人换一条网络
（关掉校园网 Wi-Fi 用手机流量）。教务、一卡通、电费的失败文案都引这一份，别再各写一句。

实现：`data/jw/JwImportDiagnosis.kt`（纯逻辑，不依赖 android.* → JVM 可测）、
`data/jw/JwVpnDetector.kt`、`data/jw/JsStringDecode.kt`；
调用点 `ui/jwvw/JwImportScreen.kt`（`reportFailure` 是唯一失败出口）。

**OkHttp 登录链的失败也要可诊断**（2026-09-28）：`JwHttpSession.login()` 第 [6] 步
「CAS 已通过但教务侧会话没建立」原本只抛一句「教务会话无效（已退回登录页）」，链路里
**没有任何日志**，用户报障时无法回答「是 ticket 环节还是主页校验环节坏的」。现失败时落
一条 `Log.w`（字节数 + 是否登录页形态，**不含任何 cookie 或页面内容**），异常文案改成
可行动的：「教务登录没走通（CAS 已通过，教务侧会话未建立）。请在导入页手动登录一次，
或稍后重试。」——这同时解释了「我的 → 更新账号密码」提交后报此错的现象：它走的就是
`tryLogin` → `login()` 这条链，CAS 密码验证已过，坏的是教务侧环节。

**而这句日志立刻揪出了真正的根因——登录页判据误伤**（2026-09-28 当天）：新日志显示
`bytes=151552 looksLogin=true`，即 **151KB 的正常已登录主页被判成了登录页**。对照实测：
已登录主页含 `LoginToXk` **2 次**——那是「退出登录」隐藏表单的 action
（`/jsxsd/xk/LoginToXk?method=exit`），每个已登录主页都有；未登录的 80KB 登录页里
`userPassword`（密码框 id/name）出现 **11 次**，已登录主页为 **0**。所以
`LOGIN_PAGE_MARKERS` 里的 `LoginToXk` 必须剔除，只留 `userPassword`——否则探针把
已登录判成失效 → 完整登录 → 第 6 步再校验又失败，「教务登录没走通」**必现**，
整条自动续登链路在教学周期内瘫痪（上一节修的「跳过首启」只是这条链的前置断点，
这一个才是让它彻底走不通的雷）。回归单测 `loggedInHomeWithLogoutForm_isNotALoginPage` 钉住。

#### 4.4.2 一键导入：理论 + 实验合成一批（2026-09-24）

用户拍板：底部只留「一键导入课表」一个主按钮。点一下，App 依次打开学期理论课表页与实验课表页，
各注入一次抽取脚本，两份结果合成一批，弹**一次**识别结果，确认后写进同一张课表。

为什么是顺序导航而不是「同源 fetch 两份 HTML 再本地解析」：两张表的 DOM 抽取脚本是真机跑出来的
口径，而 `parseFromHtml` 那套正则只有合成 fixture 的单测（§4.8 把它标为备用路径）。顺序导航
只新增一个「等页面加载完」的闸门，解析口径一行不动。
（后记 2026-10-07：该备用路径 2026-10-04 起被 §4.17 调课检测/首启导课表直取采用；当天修过它
与注入脚本的一处不一致——一格多课块只取第一个，见 §4.17 与 `.agents/rules/import-jw.md`。）

实现要点（`ui/jwvw/JwImportScreen.kt`）：

| 环节 | 口径 |
|------|------|
| 等待页面加载 | `PageLoadGate`（`CompletableDeferred<Boolean>`）+ `onPageFinished` 里在 `checkSessionLost` 通过、`pageState = Ready` 之后放行。`loadUrl` 是异步的，不等就注入会抽到旧页面的 DOM |
| 放行的 URL 匹配 | 片段 `xskb_list.do` / `syjx/toXskb`。两张课表 URL 都含 `xskb`，整串比较会互相误判（§4.8 记过同一个坑） |
| 失败即中断 | `reportFailure` 里**自动重试分支之后**放行 false——自动重试把目标换成了认证入口，目标页还没到 |
| 超时 | 单页 20 秒（`PAGE_LOAD_TIMEOUT_MS`），超时不写库，状态条给「打开X超时」 |
| 学期一致 | 理论页读到 `select#xnxq01id` 的学期号后拼给实验页（`JwUrls.labScheduleUrl`，白名单 `JwUrls.TERM_PATTERN`）。两页各有一套默认学期，不一致时合并出来的是跨学期课表 |
| 已在理论页就不重载 | 用户可能自己在下拉里选了学期，重载会把这个选择打回教务默认 |

**「0 条」与「拿到的不是这张表」必须分开**（`data/jw/OneClickImport.kt` + `ExtractMeta`）：

- 理论页：`td[name=kbDataTd]` 扫到过（`cells > 0`）才算识别成功。整屏网格恒在（实测 41 个格子），
  「网格在但没课」与「根本不是这张表」是两件事。
- 实验页：课表 tbody 在不在（`container`）。为此 `SyjxScheduleParser.EXTRACT_JS` 找不到容器时
  返回 `ok:true, container:false` 的空结果，**不再**提前 `ok:false`——两种情形在 Kotlin 侧要能分开。
- 实验课表 0 条**不算失败**。前期学期本来就没有实验课，把它当失败会让用户在没排实验课的学期
  根本导不进来。结论写进识别结果弹窗，让用户在写库前看到。

识别结果弹窗复用 `ImportTargetDialogHost`，新增可选 `breakdown`（其余调用点不传，行为不变）：

```
识别结果
数据学期：2026-2027-1
理论课表 29 条
实验课表 12 条
共 41 门课。示例：高等数学、大学物理、机械制造基础A
```

- 两张表都没拿到东西 → 不弹窗，Snackbar 报错（`OneClickResult.blocked`）
- 有一张 0 条 → 弹窗照常出，警示行写明是「本学期暂无实验课安排」还是「未识别到课表」
- 学期不一致 → 警示行点名两个学期
- 一键导入默认**合并**：两批数据一起进来，覆盖会连用户自建、调课过的行一起清掉且不可撤销

底部按钮：删掉「理论课表 / 实验课表」两个入口（按当前页选解析器这件事，App 自己做得比用户准），
保留「考试安排」入口；停在考试查询页时主按钮切回「导入考试安排」（落页即自动跑一轮，
按钮留作重试），成绩模式不变。

**2026-09-24 补：落在理论课表页就自动跑一次**（用户要求「跳转成功识别到后自动确认导入」）。
课表模式下打开这个窗口的目的就是导入，落哪一页 App 已经自己决定了（`xsMainV` → 理论课表），
中间那次「点一下按钮」是「按当前页选解析器」时代的遗留，现在由 `autoImportOnTheoryReady` 接管：

- 触发条件：课表模式 + 页面类型是**理论课表** + `!busy`，调用点在 `onPageFinished` 的
  「页面已就绪」分支（`releaseLoadGate` 之后），所以不必再判 `pageState`。
- **判页面类型，不判「URL 含 `xskb`」**：实验页 URL 也含 `xskb`，认错会在实验页就绪时再触发
  一轮，而一键导入自己就会跳到实验页，等于自动死循环。
- 只做第一次，标记 `autoImportTried` 同步置位（进协程再置会漏掉连着到的第二次 `onPageFinished`），
  并在 `runOneClickImport` 入口消费，所以手动点按钮也算用掉。用户在理论页换学期会触发表单提交、
  页面重载，那时不再自动跑，改由用户点按钮；失败也不自动重试。
- 只省点击，不绕过确认：写库仍要过识别结果弹窗，学期不对可以直接取消。按钮照旧留着当重试入口。

验证：`OneClickImportTest` 13 项（形态判定 / 合成 / 警示文案 / 脏字段容错）；
真机走「登录 → 一键导入 → 弹窗条数与学期 → 合并 → 周课表同时出现理论与实验课 → 再点一次不重复」。

**2026-09-30 补：考试安排也自动跑**（用户要求「考试安排也变成一键式的，和导入课表一样，
识别到就弹详情，没有安排就弹暂时没有考试安排的弹窗」）。`autoImportOnExamReady(page)`
与理论课表那条同构、共用 `autoImportTried` 与 `busy`：

- 触发条件：课表模式 + 页面类型是**考试安排查询页**（`JwSchedulePage.Exam`），调用点就在
  `autoImportOnTheoryReady(page)` 下一行——两条各判自己的页型，同一页只会命中一条；
- 走的是既有 `runExamImport`（壳页读学期 → `xsksap_list` 翻页 → `ExamMapper`），**没有新增
  任何写库路径**：有安排 → 识别结果确认弹窗（默认合并、带学期口径与估算披露）；没安排 →
  「暂时没有考试安排」弹窗（`emptyExamTerm`，陈述事实 + 下次再来，不用错误语气——
  教务考前数周才录入，`count=0` 是正常空态）；
- 进这个页面的两条路都受益：考试页页尾的导入卡（`startAtExam`）与底部「考试安排」入口。
  落页即抓，「点一下按钮」不再是必需动作，按钮留作失败重试；用户取消弹窗后不再自动重跑。

**2026-09-28 补：确认弹窗支持切换学期**（用户要求「一键导入后支持选择导入的学期，默认是当前爬取到的学期」）：

- `EXTRACT_JS` 在读选中项之外同时输出学期下拉的全部选项（`terms:[{v,t,s}]`），
  经 `extractTermOptions` 宽松解析——**value 必须过 `TERM_PATTERN` 白名单**（value 会被拼进
  重载 URL），缺失/结构异常降级为空列表，弹窗退回纯文本展示（JSON/剪贴板/考试路径不传，
  行为不变）。
- 「数据学期」行变成可点下拉，默认停在当前爬到的学期；选别的学期 → `runOneClickImport(forceTerm)`
  带着 `xnxq01id` **强制重载**理论页（此时不能沿用「已在理论页就不重载」的捷径）→ 实验页跟
  同一学期 → 新草稿替换旧草稿（弹窗课程数/分项/学期随新数据刷新）。重爬中 `switching` 状态：
  下拉不可点、「导入」按钮禁用；失败保持原草稿，由既有错误浮层/状态条提示。
- 切学期后弹窗的目标课表选择回到默认（`remember(courses)` 键随新数据重置）——换了学期数据
  统计全变，回默认是合理行为。
- 导入学期会写进目标课表（`importParsedCourses(..., term)` → `Timetable.term`，Room v14），
  成为课程详情查教材的钥匙（§4.31）；JSON 导入用文件顶层 `term` 同样写入。

### 4.5 胖乖 API（源：light-life）

Base：`https://userapi.qiekj.com/`  
内容类型：`application/x-www-form-urlencoded;charset=UTF-8`  
UA 可参考：`okhttp/3.14.9`  
Channel：`android_app`

| 能力 | 接口（Form POST） | 备注 |
|------|-------------------|------|
| 发验证码 | `common/sms/sendCode` | `phone`, `template=reg` |
| 登录/注册 | `user/reg` | `channel`, `phone`, `verify` → 返回 token |
| 余额 | `user/balance` | 需 token |
| 历史设备 | `goods/latestUsed` | `categoryCode=5`（饮水类，以实测为准） |
| SKU | `goods/normal/skus` | `goodsId` |
| 设备详情 | `goods/normal/details` | 含 imei 等 |
| 风控检查 | `userIntegral/checkUserIsRisk` | |
| 支付通道 | `payChannelRoute/addUserAfterPayChannel` | method=15 |
| 位置校验 | `orderRisk/isCheckLocation` | |
| **开水** | `goods/water/unlock` | `skuId`, `promotions`, `token` |
| 同步 | `goods/water/sync` | |
| 订单详情 | `order/detail` | `orderId` |

实现注意：

- Token 存 EncryptedSharedPreferences 或 DataStore + 非备份目录  
- 一键开水按 light-life 的顺序调用，不要漏风控/通道步骤  
- 订单列表：优先本地 `OrderHistoryStore` 快照；接口有列表再补  
- **禁止**实现刷积分  
- 签到若做，做成设置开关，默认关，并注明可能违反平台条款  

免责声明（设置/关于页必须有）：模拟客户端操作，风险自负，仅供学习。

### 4.6 网络与依赖清单（建议版本族，初始化时锁定）

| 用途 | 库 |
|------|-----|
| UI | Compose BOM, Material3, Activity Compose, Navigation Compose |
| ViewModel | lifecycle-viewmodel-compose |
| 网络 | Retrofit, OkHttp, kotlinx-serialization 或 Moshi |
| 图标 | com.github.rikkahub:hugeicons-compose（JitPack） |
| 存储 | DataStore + SQLDelight/Room（二选一，建议 Room 更省心） |
| 图片 | Coil（若需要） |
| 异步 | Kotlin Coroutines + Flow |

不引入：RxJava、过度组件化、自研路由框架。Markdown／LaTeX 渲染也自研子集
（§4.20），不引第三方渲染或公式库——`--offline` 构建与体积预算是硬约束。

### 4.7 图标

编码前用 skill 查名，勿猜：`.agents/skills/find-hugeicons`。

常用场景关键词建议：`calendar`, `book`, `water`, `cup`, `wallet`, `setting`, `import`, `refresh`, `moon`, `sun`, `login`, `list`。

### 4.8 实验课表导入（syjx / toXskb）

**已确认（2026-09-17 实测，取自登录后主页快照 `scripts/out/jw_xs_main.html`）**

| 项 | 值 |
|----|-----|
| 菜单路径 | 实践实验 → 实验课表查询 |
| URL | `http://jiaowu.juwp.edu.cn:8080/jsxsd/syjx/toXskb.do` |
| 菜单 data-id | `NEW_XSD_PYGL_WDKB_SYKBCX` |
| 会话 | 与理论课表同一 WebView 会话，无需二次认证 |
| 页内条件 | 学年学期下拉 + 周次（全部 / 指定） |
| DOM | **已实测**（2026-09-17，快照 `scripts/out/syxkb_toXskb.html`，108937 字节） |

同域下另有 `实验预约管理 /jsxsd/syjx/syyy_find.do`、`开放实验预约 /jsxsd/view/syjx/kfsy_find.jsp`、`实验室信息查询 /jsxsd/view/syjx/sysxx_find.jsp`，本期不做。

**与理论课表的本质差异（决定解析策略）**

实验课表网格是「周次 × 节次」**两级纵轴**（周次 1/2/3… 各占一组，组内再分 1-2、3-4、5-6… 节），横轴星期一至星期六；理论课表是「整学期一屏、节次为纵轴」。由此推出两条硬约束：

1. 同一门实验课若排在第 1、3、4 周，页面上是**三个独立课块**；
2. 课块本身很可能**不带周次字段**，周次信息挂在它所属的**行分组**上。

因此解析**不能**照搬 `QiangzhiScheduleParser`（从 `qz-hasCourse-abbrinfo` 文本 parse 周次），必须**由课块所在周次分组反推 weeks，再聚合成一条**。这是本节最主要的技术判断。

**Step 0 · DOM 快照（已完成）**

快照已落到 `scripts/out/syxkb_toXskb.html`，后续解析器与单测 fixture 直接用它。
复现方式：复用 `scripts/fetch_courses.py` 的登录函数直连抓 `/jsxsd/syjx/toXskb.do`
（**脚本必须 `trust_env = False`**，否则会踩 §7 风险 6）。

App 端若需自助取证，可在 debug 构建加「保存当前页 HTML」入口（把
`document.documentElement.outerHTML` 写入 `filesDir/snapshots/`）。非本期必需，作为后续能力。

**DOM 结构（实测）**

表头 9 列：`周次 | 节次 | 星期一 … 星期日`——**7 天，含周日**。

`table.qz-weeklyTable` 共 121 行，按周次分块，**每个周次占 6 行**：

| 行 | td 数 | 布局 |
|----|------|------|
| 周次首行 | 9 | `[周次标签 rowspan=6] [节次标签] [星期一 … 星期日]` |
| 该周其余 5 行 | 8 | `[节次标签] [星期一 … 星期日]` |

节次标签取值：`1-2` / `3-4` / `5-6` / `7-8` / `9-10` / `11`。

有课的单元格：`td.qz-weeklyTable-td.qz-hasCourse.qz-mixrow`，内部结构为
`div.td-cell > ul.courselists > li.courselists-item`。

**字段映射**

| 字段 | 来源 |
|------|------|
| 星期 | 该 td 在本行 tr 中的索引：8-td 行 `day = idx`；9-td 行 `day = idx - 1`。等价写法 `day = idx - (len(tds) - 8)` |
| 周次 | 向上取所属 tr，再取其**周次标签 td**（`class` 含 `qz-weeklyTable-label` 且 `rowspan=6`）的文本 |
| 节次 | 所属 tr 的**节次标签 td** 文本，`a-b` 拆成 `startSection`/`endSection`；单值如 `11` 则首尾相同 |
| 名称 | `div.qz-hasCourse-title` |
| 地点 | `div.qz-hasCourse-detailitem`（位于 `qz-hasCourse-abbrinfo` 内，**只含地点**） |
| 教师 | **页面不提供**，留空 |

**两条实测纠正**（推翻了此前基于理论课表经验的假设）：

1. 实验课表页**没有** `td[name=kbDataTd]`，也**没有** `老师:X;时间:Y;地点:Z` 这种合并 detail 文本
   ——`qz-hasCourse-abbrinfo` 里只有地点。所以 `QiangzhiScheduleParser` 的抽取逻辑与字段正则
   **完全不可复用**，必须独立实现。
2. `div.qz-tooltipContent-detailitem` 里另有 `课程编号：` / `班级：` / `地址：` / `节次：`，
   但其中 **`节次：60304` 是页面内部编码，不是真实节次**，不要拿它当数据源——真实节次只能取行标签。

**聚合仍必须做**：同一门课在不同周次各出现一次（实测「机电传动控制B / 工程训练中心207」在第 1、2 周
各一块），且同名课可能配不同地点（「机械制造基础A」实测分布 212 / 105 / 403 三个实训室）。
故按 `(name, day, startSection, endSection, teacher, position)` 聚合、`weeks` 取并集。

**数据模型（最小扩展）**

```kotlin
enum class CourseKind { Theory, Lab }   // domain
```

- `Course.kind: CourseKind = CourseKind.Theory`
- `CourseEntity.kind: String = "theory"`；DB version 1 → 2，Migration 用
  `ALTER TABLE courses ADD COLUMN kind TEXT NOT NULL DEFAULT 'theory'`
  （**禁用** destructive migration，用户已有课表数据）
- `CourseJson.kind: String = "theory"`：带默认值，旧导出文件仍可读
- `mergeKey()` 追加 `kind`：避免理论与实践课同名同节次时互相吞并

**解析器**

新增 `data/jw/SyjxScheduleParser.kt`，与理论课解析器并列，**不改动**既有类：

1. 注入 JS 抽 raw 块：`{ name, detail, day, sections, weekGroup }`，`weekGroup` 取自课块所在的周次行分组
2. **聚合**：按 `(name, day, startSection, endSection, teacher, position)` 分组，`weeks` 取**并集**
   - 聚合键必须含 `position` / `teacher`：工程训练按批次分周上课，地点与教师可能不同，只按课程名合并会丢信息
3. **回退**：`weekGroup` 取不到时，用与理论课表相同的 `parseWeeks(detail)` 兜底
4. 产出 `Course(kind = CourseKind.Lab)`

**UI（叠加 + 样式区分）**

- 实验课与理论课同处一张周课表，**不新建页面**
- `CourseBlock` 增加 `kind` 分支：跨度 ≥2 节时右下角显示「实验」角标（labelSmall，白色 70%）；单节小格只留色块，避免挤压
- 周课表顶部加分段筛选「全部 / 理论 / 实验」，默认「全部」
- **顺带修既有缺陷**：同格重叠的课程现在是 `forEach` 直接叠放、后者覆盖前者；改为按重叠组均分列宽并排显示

**导入交互**

- `JwUrls` 增加 `LAB_SCHEDULE` 常量与 `isLabScheduleUrl(url)`（匹配 `syjx/toXskb`）
- ~~`JwImportScreen` 底部改为 `[理论课表] [实验课表]` 导航 + `[导入本页]`，按当前 URL 自动选择解析器~~
  → **2026-09-24 起改成「一键导入课表」**（依次打开两张表，见 §4.4.2）：按当前页选解析器这件事
  App 自己做得比用户准，底部只留「考试安排」入口
- ~~确认弹窗显示「共 N 条，其中实验课 M 条」~~ → 由识别结果弹窗的逐项条数取代（§4.4.2），
  含 0 条的那一项也要列出来

**验收**

- JVM 单测：以 `scripts/out/syxkb.html` 为 fixture，断言条数、周次并集、聚合结果
- 真机：WebView 登录 → 实验课表 → 导入 → 周课表出现实验课且周次正确；先导理论课表再合并导入实验课不产生重复

**实现记录（2026-09-17）**

| 层 | 文件 | 变更 |
|----|------|------|
| domain | `Models.kt` | 新增 `CourseKind{Theory,Lab}`（带 `label`）、`CourseFilter{All,Theory,Lab}`；`Course.kind` |
| local | `Entities.kt` / `JuwDatabase.kt` | `CourseEntity.kind`；**Room v1 → v2**：`ALTER TABLE courses ADD COLUMN kind TEXT NOT NULL DEFAULT 'theory'`（非 destructive，老数据自动落为理论课） |
| repo | `ScheduleRepository.kt` | `CourseJson.kind`（默认 `theory`，旧导出文件仍可读）；导出写出；`mergeKey` 追加 `kind` |
| jw | `SyjxScheduleParser.kt` | **新增**：`EXTRACT_JS` + 聚合 + `parseFromHtml`（单测用正则实现，与 JS 同一套判定规则） |
| jw | `CourseImporter.kt` | `LAB_SCHEDULE`、`isLabScheduleUrl()`、`schedulePageKind()` 与 `JwSchedulePage` |
| ui/jwvw | `JwImportScreen.kt` | 底部改「理论课表 / 实验课表 / 导入（按当前页自动命名）」；解析器由当前 URL 决定，不在课表页时给明确提示 |
| ui/common | `CourseUi.kt` | 实验课：多节块右下角「实验」小字；单节块右上角小圆点（放不下两个字）。**不换色**——颜色已被「不同课程不同色」占用 |
| ui/week | `WeekSheets.kt` / `WeekViewModel.kt` / `WeekScreen.kt` / `DisplayPrefsStore.kt` | 「显示哪些课程」筛选（默认全部、持久化到 DataStore）；课程详情显示类型；筛选后无课时文案区分「课表为空」与「当前筛选下没有课」 |
| ui/common | `CourseEditSheet.kt` | **顺带修缺陷**：编辑时未带回非表单字段，自定义时间课会被打回按作息表计算，实验课会被打回理论课 |

验证：`testDebugUnitTest` 全部通过（新增 `SyjxScheduleParserTest` 9 项、`ImportJsonShapeTest` 2 项兼容性断言）；
`assembleDebug` 通过；`adb install -r` 真机安装成功。

---

### 4.9 多课表支持（2026-09-17 规划，用户已拍板三个决策点）

需求：① 支持新建/管理多个课表，各课表设置参数独立；② 全局设置与课表设置分层，某课表的设置可设为
「新建课表默认配置」；③ 教务/JSON 导入时**强制选择**目标课表（可新建），覆盖/合并模式由弹窗选定。

**决策点（用户拍板）**

1. 显示偏好（字号/格子样式等）→ **归课表**（换课表即换观感）。
   （**2026-09-19 反转，用户拍板**：显示参数改回**全局**、不再按课表分开，见下方「设置分层」。）
2. 默认配置 → **引用型**：新建课表实时拷贝「默认配置源课表」的当前设置；不做独立快照。
3. 导入落点 → **每次强制选择**：确认弹窗列出全部课表 + 「新建课表」入口，并选覆盖/合并。

**存储：单库多记录（拾光式），Room v2 → v3**

| 变更 | 方式 |
|------|------|
| 新表 `timetables(id PK, name, created_at, sort_order, slots_customized, prefs_json)` | 建表；迁移插入 id=1「我的课表」，prefs_json 为内置默认 |
| `courses.timetable_id` | `ALTER TABLE ... DEFAULT 1` + 索引 |
| `time_slots` | **重建表**：主键 `(timetable_id, number)`，旧数据并入课表 1 |
| `semester_config` | **重建表**：主键 `timetable_id`（每课表一行） |
| DataStore | `current_timetable_id`（默认 1）、`default_config_source_id`（null=内置默认）、一次性迁移标记 |

~~课表级显示偏好存 `timetables.prefs_json`~~ → **2026-09-19 起全局化**：显示偏好整体（同一 JSON
结构 `TimetablePrefs`）改存 DataStore 键 `view_prefs_json`，**所有课表共用一份**——换课表不再换观感。
`prefs_json` 列保留但不再读写（列稳定优先，不做破坏性删列迁移）；`TimetablePrefs` 的 JSON 结构、
字段默认值与 decode 夹取逻辑不变，只是换了存储位置。
一次性迁移在 `ensureDefaults`（可挂起）：优先读旧版全局键（v2 直升用户），否则取**当前课表行**的
`prefs_json`（v3 用户保留的是正在使用的观感），写入全局键后落 `view_prefs_globalized` 标记幂等。
v3 时代「旧全局键 → 课表 1 行」的迁移被本迁移替代合并（`migrateLegacyGlobalPrefs` →
`globalizeViewPrefs`）。

**设置分层**

| 层 | 存储位置 | 内容 |
|----|----------|------|
| 全局 | DataStore | 主题模式、**显示偏好全部字段**（2026-09-19 起全局，键 `view_prefs_json`）、胖乖、教务账号、`current_timetable_id`、`default_config_source_id`；背景图文件本身在 `filesDir/schedule_bg/`（§4.21），prefs 里只存文件名 |
| 课表级 | Room，随课表存取/复制/删除 | 学期配置、作息表（含 `slots_customized` 标记） |

`ScheduleRepository.displayPrefs` 对 UI 仍暴露合并后的 `DisplayPrefs`，但**全部字段都是全局**
——换课表不影响观感；视图偏好 setter 写 DataStore，WeekScreen 等调用点签名不变。

**默认配置（引用型）**

`default_config_source_id` 指向某课表；新建/复制配置时实时拷贝该课表的**学期+作息**（含
`slots_customized`）。源课表被删 → 回退内置默认。设默认配置的入口在「课表管理」页。
显示偏好自 2026-09-19 起全局，不再参与配置拷贝。

**切换与管理 UI**

- Week 顶栏（日期块左侧）加课表名入口 → 底部弹层：课表列表（单选切换）+「管理课表」。
- 我的 → 课表管理页：新建（输入名称，配置取默认源）、重命名、复制、删除、设为默认配置。
  排版（2026-09-19）：主操作「设为当前」是卡内填充按钮；重命名/复制/设为默认/删除收进
  卡内「⋮」菜单（五个平铺文字按钮在窄屏必然溢出，删除混在平铺行里易误触）；
  列表尾有一个整行「新建课表」入口；动作带触感反馈。
- 删除保护：至少保留一张课表；删当前课表自动切到第一张；默认源被删回退内置默认。
- 课表设置页（2026-09-19）：学期区顶部加**课表切换 chips**——默认选中当前课表，选中哪张就
  编辑哪张的开学日期/总周数，读写都按目标课表落（`observeSemesterFor` / `updateSemesterFor`，
  不再隐式只写当前课表）；chips 上当前课表带「·当前」标记，信息行按目标课表显示周次与课程数。
  作息区仍固定编辑**当前课表**（页面注明），不随学期区切换。

**导入交互（强制选择目标）**

- 教务导入与 JSON 导入共用一个目标选择确认弹窗：目标课表单选（默认预选当前课表）+「新建课表…」
  （输名字，配置取默认源）+ 覆盖/合并模式 + 取消。弹窗显示目标课表现有课程数。
  **默认值必须现算，不能冻结在弹窗首次组合那一刻**（2026-09-24 修）：`timetables` 来自 Room、
  当前课表 id 来自 DataStore，首帧拿到的是初始值（空列表 / 0），据此算出「一张课表都没有」
  就会默认停在「新建课表…」，而「导入」按钮又被空名称禁用——用户看到的是一屏没选中的课表列表，
  得自己点回来。现在以「用户是否动过手」的 null 态兜住：没动过 → 当前课表（失效退第一张），
  一张都没有才落「新建课表…」。
- 覆盖/合并语义不变；`mergeKey` 去重只在目标课表内做。
- 导出 = 导出当前课表；JSON 格式 `{"courses":[...]}` **不变**，拾光互导兼容。

**受波及面（scope 清单）**

- `CourseDao` 全部查询加 `timetable_id` 过滤；`nextColorIndex` / `colorIndexesBySortedName` /
  `rebalanceCourseColorsIfColliding` 按课表 scope（防止跨课表污染配色）。
- `migrateTimeSlotSchema` 课表级化：schema version 全局保留，`slots_customized` 收编进 timetables 表
  （旧全局标记在一次性迁移中映射到课表 1）。
- 各 ViewModel `combine(currentTimetableId, ...)`；课程编辑/删除落在当前课表。

**分阶段实施**

| 阶段 | 内容 | 门禁 |
|------|------|------|
| A 数据层 | Room v3 迁移 + scope 改造 + DataStore 键 + 一次性搬迁；行为与现状等价 | assembleDebug + 单测全绿 |
| B CRUD/切换 | 课表管理页 + 顶栏切换弹层 + 路由 | 真机 |
| C 设置分层 | 归属标注（课表级/全局）、入口收口 | ✅ **已完成**（2026-09-17） | 真机 |
| D 导入选择 | 目标选择弹窗（教务 + JSON） | 真机 |

风险：`time_slots` 重建表是迁移最大风险点；真机带真实数据升级（v2→v3）验证旧数据完整保留。

---

---

### 4.10 胖乖生活开水模块（P4 框架规划，2026-09-17）

**范围**

| 做（M1/P4） | 不做 |
|----|----|
| 验证码登录 + Token 粘贴登录 | 刷积分（**硬性禁止**） |
| 余额（积分/小票/可抵扣） | 签到（M2，做也默认关） |
| 历史设备列表 + 一键开水完整链路 | 桌面快捷方式（M2） |
| 本地订单快照列表 | 趣智校园淋浴（light-life 有，本期不搬） |
| 免责声明 + Token 不进日志 | 前台 Service 保活（页面级生命周期） |

**模块落位（对齐 §4.2 分层，参照 light-life 但不整包照搬）**

```
data/qiekj/
  QiekjApiConfig.kt          Base/VERSION/channel/secret/promotions 常量（对齐 light-life ApiConfig）
  QiekjApi.kt                Retrofit Form POST 接口（§4.5 的 13 个端点）
  QiekjHeaderInterceptor.kt  统一头 + sign 装配（登录接口不带 sign/token）
  QiekjModels.kt             ApiEnvelope + 各端点 DTO + TokenExpiredException
  QiekjJson.kt               脏数据容错（data 可能是 ""、数字当字符串，见下）
  QiekjTokenStore.kt         token/phone 加密存储
  QiekjOrderHistoryStore.kt  订单快照（按 orderNo 去重，上限 50）
  QiekjRepository.kt         登录/余额/设备/unlock 流程编排（onStep 回调驱动 UI）
  QiekjErrorDiagnosis.kt     错误→原因+建议（实名/位置风控/设备离线/次数用完…）
domain/
  QiekjSign.kt               SHA-256 签名纯函数（JVM 可测，与 Interceptor 分离）
  UnlockFlowState.kt         Idle/PreChecking/Working/Success/Failed
ui/water/                    WaterScreen + WaterViewModel + 登录/设备/订单 Sheet
Graph.kt                     增 qiekjRepository(context) 单例
```

**关键技术决策**

1. **序列化沿用 kotlinx-serialization**（工程已有，不引 Moshi）。轻乖接口返回脏数据：`data` 可能是
   空串、数字字段当字符串返回——light-life 用 EmptyData/LenientString 两个 adapter 兜住。kotlinx
   等价方案：`ApiEnvelope.data` 声明为 `JsonElement` 再二次 decode，或自定义 lenient KSerializer，
   P4a 先定并配单测。
2. **Token 存储**：EncryptedSharedPreferences（`androidx.security:security-crypto:1.1.0`，light-life
   已验证），并在 `backup_rules.xml` / `data_extraction_rules.xml` 排除 secure prefs（对齐 light-life）。
3. **签名抽纯函数**：sign（SHA-256 of `appSecret=..&channel=..&timestamp=..&token=..&version=..`+path）
   放 domain 便于单测；Interceptor 只做装配。登录类接口（`common/*`、`user/reg`）channel 用
   LOGIN_CHANNEL 且不带 sign。
4. **开水状态机**：`UnlockFlowState` 进 domain，ViewModel 驱动；Mutex 防重入；Working 阶段 UI 显示
   165s 自动结算倒计时兜底（light-life 同款，服务端超时自动关阀）。
   **出水满 5 秒给「结束用水」**（2026-10-05 用户拍板）：只结束本地等待，设备已回报结束
   （`status==5` + 单号）就照常建单读账单，否则按「未确认用水」收尾（`UnlockStoppedException`
   → Idle + 提示，不建单）。开水页与今日页开水面板共用 `ui/water/WaterFlowControls.kt`。
   同一批实测还改掉了两条话术：165s 兜底不再说「已自动关闭并结算」（设备断电时根本走不到建单，
   说结算是假话），改说「设备未回报结束，实际用量以平台账单为准」。
5. **轮询**：`goods/water/sync` 等 `workStatus==2`，1s 间隔、上限 300 次，放 repository suspend +
   delay，随协程取消；须记录「曾经出过水」（`everWorked`）区分设备未启动。
   **`workStatus` 判不了设备死活**（2026-10-05 实测：设备拔电 15 分钟后仍回 `status=1 / workStatus=2`，
   服务端 165s 超时也不翻状态）。会话是否结束只看 `status==5`，见 `isSessionFinished`。
6. **网络层**：复用工程 Retrofit 2.11 + OkHttp 4.12；`HttpLoggingInterceptor` BASIC 级（不打 header，
   token/sign 不进日志）；共享单例 client（20s 超时）。

**开水调用链（顺序不可乱，对齐 light-life unlockDevice，每步失败走诊断）**

```
goods/latestUsed(categoryCode=5) 取设备
→ goods/normal/skus(goodsId)        取 skuId（必需，缺失即报错）
→ goods/water/sync                  设备预检（失败不阻断，仅记录）
→ goods/normal/details(goodsId)     取 imei（必需）
→ userIntegral/checkUserIsRisk      积分风控检查（必需，失败阻断）
→ payChannelRoute/addUserAfterPayChannel(method=15)  开通后付（必需）
→ orderRisk/isCheckLocation(categoryCode=04, imei)   位置风控（必需）
→ goods/water/unlock(skuId, promotions, token)       启动出水；promotions 两套常量 JSON
  （带积分 / 不带积分，按设置开关选择）
→ 轮询 goods/water/sync（1s × ≤300，workStatus==2 为出水中）
→ order/afterPay/creating(orderNo)  创建后付订单（orderNo 取 sync.identify ?: unlock.orderNo）
→ order/detail(orderId)             取真实账单（字段语义见下方「账单口径」，2026-09-20 实测回填）
→ 写本地订单快照 OrderHistoryStore
```

**账单口径（2026-09-21 修订，真机数据 + 参考实现交叉验证）**

`order/detail` 真实响应关键字段（单位均为元；单号/设备号等隐私字段略）：

```
markPrice          计量计费金额（本次用水的原价口径）
payPrice           现金实付（在线支付口径）；小票/积分全额支付时为 0.00
payTypeName        支付渠道名（如「支付宝-代扣」，后付单 payType=15 对应 method=15 渠道）
tokenCoinDiscount  小票（tokenCoin 余额）抵扣金额，与 promotionList type=4 同源
tradeOrderItem[].originPrice / realPrice   原价 / 现金实付（与 payPrice 一致）
promotionList[].promotionType / discountAmount / subsidyAmount
                   4=小票支付、8=积分抵扣；subsidyAmount=平台补贴
```

- **小票（tokenCoin）是账户余额，不是平台优惠**。2026-09-20 曾把 `tokenCoinDiscount`
  读成「平台自动优惠」，于是把「实付 0.00」当正常账单解释——方向错了。2026-09-21 复核两单
  真实数据（0.09、0.16 两单的 `ticketCost` 均等于原价、也等于 `tokenCoinDiscount`），并对照
  light-life 实现（其主数字即 `ticketCost`，兜底文案「小票支付」），确认：每次开水默认先扣
  小票余额，扣完剩余部分才走现金（后付代扣），所以「实付 0.00」只是现金口径为零。
- **花费展示口径**：本次花费 = 小票支付 + 现金实付。小票支付取 `promotionList[type=4]`
  （缺失时回退 `tokenCoinDiscount`）；现金实付取服务端 `realPrice`（回退 `payPrice`，
  都缺失回退 `origin - 小票 - 积分 - 其他优惠` 公式）。积分抵扣、其他优惠是省钱项，
  不计入花费。
- **订单详情弹窗必须展示账单构成**（原价 / 小票支付 / 积分抵扣 / 其他优惠 / 现金支付 /
  本次花费）；只展示一个孤零零的「实付 ¥0.00」视同信息缺失，且方向错误。
- 本地快照同步存 `realPrice` / `payTypeName` / `tokenCoinDiscount`（可空，默认 null 兼容旧快照）。

**UI 接入**

- WaterScreen：未登录→登录 Sheet（验证码/Token 两种）；已登录→设备选择 + 「开水」大按钮 +
  积分抵扣开关 + 余额行 + 订单 Sheet；状态区 Idle/进行中/成功/失败 原地切换（不弹新卡）。
- 我的页：胖乖卡片（余额摘要 + 入口）；今日页：已登录时顶栏「一键开水」快捷入口。
- 错误呈现：一行主因 + 「查看详情」弹窗（失败步骤 + 建议），对齐 light-life 的 DiagnosisResult。

**验收 / 单测（JVM）**

- `QiekjSignTest`：固定输入 → 固定 SHA-256 输出（与 light-life 算法逐字节对齐）。
- promotions 两套常量 JSON 合法性（decode 不抛）。
- `TokenExpiredException.isTokenExpired` 启发式（401/403/文案关键词）。
- 订单快照序列化往返 + 按 orderNo 去重 + 上限 50。
- `UnlockFlowState` 状态机用 fake QiekjApi 走全流程（成功/风控失败/设备未启动）。

**P4 内部里程碑**

| 阶段 | 内容 | 门禁 |
|------|------|------|
| P4a 网络骨架 | ApiConfig/Api/Interceptor/Models/Json/TokenStore + 签名单测 | assembleDebug + 单测 |
| P4b 登录+余额 | sendCode/reg/balance + 登录 Sheet + 我的页胖乖卡片 | 真机登录成功 |
| P4c 开水主链路 | latestUsed→unlock 状态机 + WaterScreen | 真机开水一次成功 |
| P4d 订单+收尾 | 订单快照 Sheet + 今日页快捷入口 + 免责声明 | 真机全路径 |

风险：接口再变（§7.2，网络层集中改）；轮询长任务页面退后台被杀（M1 接受，开关水前提示保持在
前台）；风控类失败（实名/位置/次数）不可程序化绕过，只给诊断建议。

---
