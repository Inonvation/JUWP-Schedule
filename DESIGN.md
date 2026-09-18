# JUWP Schedule 设计说明书

面向 **江西水利电力大学** 的 Android 课表 App。技术栈 Kotlin + Jetpack Compose（Material 3）。

本文件是工程蓝图。后续 AI 编码、人工开发都以它为准。有冲突时：本文件 > 口头沟通 > 灵感。

---

## 0. 一句话定位

课表看清 + 教务一键导入 + 胖乖一键开水/余额/订单，一个轻量 App 全包。

---

## 1. 背景与决策记录

### 1.1 调研结论（2026）

| 项目 | 结论 | 对本项目的意义 |
|------|------|----------------|
| 拾光课程表 `XingHeYuZhuan/shiguangschedule` | **已开源**（Apache-2.0），Kotlin + Compose Multiplatform，约 890 stars | UI、课表引擎、小组件的**设计参考**；不是代码基座（见用户决策） |
| 拾光教务适配 `shiguang_warehouse` | JS 脚本 + WebView 桥接，学校脚本集中管理 | **教务导入的交互与数据模型范本**（v2 bridge API） |
| 昆工 `kust-schedule` | 基于拾光深度定制分校 | 深度品牌定制范本 |
| WakeUp 课程表 | 闭源；开源重制质量不稳 | 不采用 |
| 胖乖生活 | 本地参考 `F:\light-life-v3.0`（及 GitHub `Inonvation/light-life`） | **一键开水/余额/订单 API 直接复用** |
| HugeIcons Compose | `me.rerere:hugeicons-compose`；查名用仓库内 skill `.agents/skills/find-hugeicons` | 图标不自绘 |

### 1.2 用户已拍板的决策

1. **基座路线：全新 Compose App（路线 D）**，不直接 fork 拾光。  
   含义：UI 与课表逻辑**按拾光风格重做**，不继承其仓库历史。工作量大于 fork，故 M1 功能必须收窄。
2. **项目根目录：`F:\JUWP-schedule`**。
3. **第一阶段只交付规划文档 + AI 提示词**，不 clone 上游代码。工程脚手架在文档确认后、教务 URL 就绪后再初始化。

### 1.3 路线 D 的代价与约束（必读）

- 拾光的课表手势、长按改块、多课表、WebDAV、多语言等能力**不会自动拥有**，要一个个排期。
- 教务侧**不要重写浏览器**，应仿拾光：App 内 WebView 打开教务登录页 + 注入/执行 JS，把课表 JSON 写入本地。
- 能抄的就抄：数据模型字段名对齐拾光 v2 课表 JSON，便于用户从别处导入/导出互通。

---

## 2. 产品范围

### 2.1 M1（第一版必须有）

| 模块 | 说明 |
|------|------|
| 周课表 | 5–7 天网格，左右滑切换周次，课程色块 |
| 今日课表 | 首页默认页，突出“下一节” |
| 课程管理 | 增删改课程（本地 CRUD） |
| 教务导入 | WebView 登录教务 → JS 解析 → 写入本地（依赖学校 URL） |
| 本地存储 | 课表/配置持久化；JSON 导入导出 |
| 胖乖 | 验证码或 Token 登录；一键开水；余额；订单列表 |
| 设置 | 学期起止、作息表、深色模式、胖乖开关 |
| 图标/主题 | HugeIcons + M3 动态色可选 |

### 2.2 M2（有余力再做）

- 桌面小组件（本周/今日/明日预告）
- 上课提醒 + 勿扰
- WebDAV / 云备份
- 签到（若接口仍可用；注意 light-life 已声明除签到外旧脚本多失效）
- 空教室、成绩、校历（需额外教务接口）

### 2.3 明确不做（M1）

- 不做刷积分脚本（接口已失效，且有风险）
- 不做 iOS
- 不做账号系统/后端服务器（全部本地 + 第三方 API）

---

## 3. 信息架构与 UI

### 3.1 导航结构

```
底部导航 3 Tab
├── 今日      TodayScreen      ← 默认启动
├── 课表      WeekScreen
│     ├── 顶栏日期块 → 周次选择弹层
│     ├── 眼睛图标   → 显示设置页内覆盖面板（与「我的 → 显示设置」共用同一份选项内容）
│     └── 课表名 ▾   → 切换课表弹层 → 管理课表
└── 我的      SettingsScreen（入口列表）
      ├── 通用：主题（全局）
      ├── 课表管理 / 课表设置（学期·作息）/ 显示设置 / 教务导入
      ├── 课表数据（JSON 导入导出）
      └── 关于
```

弹层：课程编辑 Sheet、教务 WebView 全屏、订单列表 Sheet、登录 Sheet。

### 3.2 视觉方向（对齐拾光气质，不像素级抄袭）

| 项 | 规格 |
|----|------|
| 风格 | 极简校园工具；低装饰；信息密度高但不挤 |
| 色彩 | 默认 Material You 动态取色；课程色**按课程名排序顺序分配调色板下标**（16 色，同一周内不同课不撞色——12 色时代理论+实验课名超 12 必回绕撞色，已扩容并带启动自愈重排）；强调色默认蓝绿（水电意象） |
| 圆角 | 课表色块 **6dp**；卡片 16dp |
| 色块质感 | 对齐 WakeUp：实色粉彩 + 白字、**白色虚线描边**、内容顶部起排（课名 → @地点）、教师沉到块底、色块四周只留 1dp（几乎填满格子）、无左侧强调条。此前居中排版让多行课名的块上下留白不均、教师位置漂移 |
| 深色 | 跟随系统 + 手动覆盖 |
| 字体 | 系统字体栈；**字号按列宽分档**：7 天模式课名 11sp、地点与教师 9sp；5 天模式 12.5sp / 10.5sp。14sp 课名在 7 列（单列内容宽约 37dp）下只能排 2 字，不采用 |
| 课表字号 | 网格行高按可用高度算死（11 行约 45–50dp），格子里的文字却是 sp 会跟随系统缩放，因此网格内**单独定字号**：无极滑块，单位是**课名目标字号（dp），范围 8–14dp**（真机确认 12dp 起课名开始明显截断，上限收到 14），网格内其余文字按同一倍率等比缩放；默认跟随系统字体设置（课名基准 × 系统倍率再夹取）；用户拖动后即为权威值（绝对 dp，不随系统/列数漂移），可一键还原「跟随系统」。只收住网格，其余页面仍跟随系统设置。存储键 `grid_font_dp`；旧倍率键 `grid_font_scale` 仅作读路径折算迁移（×11sp 基准） |
| 图标 | HugeIcons stroke 2px；禁止 emoji 当功能图标 |

### 3.3 关键屏交互要点

**今日**

- 顶部标题两行：`今日` + `9月18日 周四 · 第 7 周`（顶栏不放一键开水图标，入口在列表尾部）
- 结构：**焦点卡 + 单时间轴**，同一条信息只摆一次
  - 焦点卡 = 正在上课 / 今天的下一节；正在上课时带整门课进度条与「还有 N 分钟下课」，
    点卡片进编辑。焦点课**不出现在下方列表**（旧版两处重复是"乱"的首要来源）
  - 时间轴行 = 行首时刻（`10:15`）+ 两行色卡（课名 / `@地点 · 教师`）；行内不重复时刻与节次号
  - 已结束的课不显示；列表头计数**含**焦点卡那节，数字与页面上的课块数一致
- 明天：只在今天没有待上课程（上完 / 没课）时上桌，复用同一行组件；明天也没课给一句休息提示
- 空态：无课 +「从教务导入」CTA
- 列表尾部：**一键开水**卡（已登录胖乖时显示，1dp 描边与焦点卡区分）

**课表**

- 周网格：横轴周几（默认周一至周日），纵轴 **11 个小节**（不是 5 个大节，见 3.5）
- 大节之间留 6dp、大节内小节之间留 3dp。原为 12dp（仿「20 分钟:5 分钟」比例），真机反馈
  同日相邻课块空白偏大后收成 6dp——保留「大节间 > 大节内」层级，不再追求与作息分钟成比例：
  午休 140 分钟本就不可能按真实比例画
- 连续小节合并为一个连续色块，中间不切、不留缝
- 左右滑：±1 周；**两个入口两张弹层**：顶栏日期块 → 周次选择器（W1–W20 网格 + 回到本周）；
  眼睛图标 → 显示设置弹层。此前两者共用一个弹层，改显示选项得从周次网格里翻
- 显示设置（眼睛图标）→ **课表页内覆盖面板**（早期曾是独立子页与半屏弹层）：
  `课表字号`（8–14dp，见 3.2）、`教室字号`/`教师字号`（7–14dp，可跟随课名）、
  `时间轴字号`/`日期字号`（7–14dp，**独立于课名**）、`格子高度`（50%–150%，乘在自适应行高上，
  默认 110%，超一屏走竖滑，仍受 40dp 下限保护）、`格子圆角`（0–12dp）、`格子不透明度`（50%–100%）；
  开关：`文字水平居中`（默认关=WakeUp 左对齐）、`文字竖直居中`（默认关=顶部起排教师沉底）、
  `显示授课教师`（默认开）、`显示时刻线`（默认开）、`显示周六`/`显示周日`（各自独立，默认均开）、
  `地点显示「@」`（默认开）、`点空白格新建课程`（默认开）、
  `显示非本周课程`（默认关，打开后在空闲格子显示灰态，每格最多 1 门）、
  `显示哪些课程`（全部/理论课/实验课，默认全部）。格子样式项经 `GridCellStyle` 贯穿三张色块卡。
- 当前时刻线：今日列叠 1.5dp 主色线 + 左端圆点 + 左侧时间胶囊；按小节时间插值，落在休息时段则在相邻两小节之间插值
- 点击课程：**只读详情面板**（教师/地点/时间/周次 + 编辑、删除两个二级动作）
- 空白格点击：按该节快速加课（可在显示设置关闭防误触）。顶栏不再放「+」（与导入弹层的
  「手动添加课程」重复）；**课表页不放 FAB**（信息密集页面必然遮挡）
- 「回到本周」：仅在当前不在本周时出现的**右下角悬浮按钮**，不占顶栏（放顶栏会在切周时挤动右侧图标）

**我的**（入口列表）

- 通用：主题（全局）
- 课表：管理 / 课表设置（学期+作息）/ 显示设置 / 教务导入 —— 后三者课表级，随当前课表
- 数据：JSON 导入导出、清空

### 3.4 胖乖开水页

1. 未登录 → 手机验证码或粘贴 Token  
2. 已登录 → 历史设备列表（最近用过的饮水机）  
3. 大按钮「开水」；可选积分抵扣开关  
4. 成功 → Toast + 本地记一笔订单快照  
5. 底部：余额、历史订单（分页或简单列表）

### 3.5 作息表（2026-09-17 实测，唯一准绳）

学校作息：**每小节 40 分钟**；大节内两小节之间休息 **5 分钟**；大节之间换教室 **20 分钟**
（上午一二节→三四节、下午五六节→七八节）。
11:40→14:00 是 140 分钟午休、17:10→19:00 是 110 分钟晚饭，不属于「换教室」。
5 个大节共 **11 小节**（第 5 个大节是 9/10/11 三小节，与教务页行标签「第九十十一节」及 19:00~21:10 对齐）。

| 节 | 起止 | 节 | 起止 | 节 | 起止 |
|----|------|----|------|----|------|
| 1 | 08:30–09:10 | 5 | 14:00–14:40 | 9 | 19:00–19:40 |
| 2 | 09:15–09:55 | 6 | 14:45–15:25 | 10 | 19:45–20:25 |
| 3 | 10:15–10:55 | 7 | 15:45–16:25 | 11 | 20:30–21:10 |
| 4 | 11:00–11:40 | 8 | 16:30–17:10 | | |

校验方式：用「40 分钟 + 5 分钟」逐节递推，5 个大节的结束时间（09:55 / 11:40 / 15:25 / 17:10 / 21:10）
与教务页 `xskb_list.do` 行标签给的时间逐一吻合。

**`TimeSlot` 存小节（11 条），`Course.startSection/endSection` 也是小节号**，两者语义一致，不再做折算。
`DefaultData.defaultTimeSlots` 就是上表。作息变更走「我的 → 课表设置 → 作息表」，届时以用户值为准：
保存时写 `time_slots` 并把 DataStore 的 `slot_customized` 置位，此后 `migrateTimeSlotSchema` 直接跳过，
避免下一次结构性迁移把用户改过的作息悄悄改回默认值。「恢复默认作息」会清掉该标记，重新交回迁移管辖。

---

## 4. 技术架构

### 4.1 工程形态

- 单模块 `:app` 起步（M1 不搞多 module 仓库，降低脚手架复杂度）
- Kotlin 2.x + AGP 8.x + Compose BOM
- minSdk 26（Android 8+，对齐拾光），targetSdk 35 附近（以当时稳定版为准）
- 包名：`edu.jxslu.schedule`（已确认）
- 应用名：**水贝贝**（已确认）/ 工程名 JUWP Schedule

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
| 图标 | me.rerere:hugeicons-compose |
| 存储 | DataStore + SQLDelight/Room（二选一，建议 Room 更省心） |
| 图片 | Coil（若需要） |
| 异步 | Kotlin Coroutines + Flow |

不引入：RxJava、过度组件化、自研路由框架。

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
- `JwImportScreen` 底部改为 `[理论课表] [实验课表]` 导航 + `[导入本页]`，按当前 URL 自动选择解析器；两者都不匹配时提示「请先打开学期理论课表或实验课表查询页」
- 确认弹窗显示「共 N 条，其中实验课 M 条」，避免两种课表混淆

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

课表级显示偏好存 `timetables.prefs_json`（kotlinx 序列化，字段带默认值，`ignoreUnknownKeys`）：
这些值没有查询需求（与当初放 DataStore 同理），JSON 列让「复制配置到新课表」变成拷一行，加字段不动表结构。
迁移是同步 SQLite，**不能读 DataStore**——显示偏好与旧全局 `slot_customized` 标记的一次性搬迁
放在 `ensureDefaults`（可挂起）里做，靠 `timetable_prefs_migrated` 标记保证幂等。

**设置分层**

| 层 | 存储位置 | 内容 |
|----|----------|------|
| 全局 | DataStore | 主题模式、胖乖、教务账号、`current_timetable_id`、`default_config_source_id` |
| 课表级 | Room，随课表存取/复制/删除 | 学期配置、作息表、显示偏好（原 DisplayPrefs 除 themeMode 外全部字段） |

`ScheduleRepository.displayPrefs` 对 UI 仍暴露合并后的 `DisplayPrefs`（全局主题 + 当前课表视图偏好），
视图偏好 setter 写进当前课表行——WeekScreen 等调用点签名不变。

**默认配置（引用型）**

`default_config_source_id` 指向某课表；新建/复制配置时实时拷贝该课表的学期+作息+显示偏好（含
`slots_customized`）。源课表被删 → 回退内置默认。设默认配置的入口在「课表管理」页。

**切换与管理 UI**

- Week 顶栏（日期块左侧）加课表名入口 → 底部弹层：课表列表（单选切换）+「管理课表」。
- 我的 → 课表管理页：新建（输入名称，配置取默认源）、重命名、复制、删除、设为默认配置。
- 删除保护：至少保留一张课表；删当前课表自动切到第一张；默认源被删回退内置默认。

**导入交互（强制选择目标）**

- 教务导入与 JSON 导入共用一个目标选择确认弹窗：目标课表单选（默认预选当前课表）+「新建课表…」
  （输名字，配置取默认源）+ 覆盖/合并模式 + 取消。弹窗显示目标课表现有课程数。
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
5. **轮询**：`goods/water/sync` 等 `workStatus==2`，1s 间隔、上限 300 次，放 repository suspend +
   delay，随协程取消；须记录「曾经出过水」（`everWorked`）区分设备未启动。
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
→ order/detail(orderId)             取原价/小票/积分明细（promotionType 4=小票 8=积分）
→ 写本地订单快照 OrderHistoryStore
```

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

## 5. 非功能

| 项 | 要求 |
|----|------|
| 隐私 | 账号/Token 不进 Log；不上传第三方分析 |
| 权限 | 网络、可选通知；震动/勿扰按 M2 再要 |
| 性能 | 首页可交互 < 2s（中端机冷启动参考） |
| 体积 | APK 目标 < 15MB（无大资源时应远小于此） |
| 稳定 | 胖乖接口失败不崩溃；教务 WebView 与原生状态分离 |
| 合规 | README/设置含免责；不内置破解/刷分 |

---

## 6. 里程碑

| 阶段 | 交付 | 依赖 |
|------|------|------|
| P0 文档 | DESIGN + PROMPTS + AGENTS | 无 |
| P1 工程脚手架 | 可编译空壳 3 Tab + 主题 + HugeIcons | Android Studio / SDK |
| P2 课表域 | 本地课表 CRUD + 今日/周课表 UI | P1 |
| P3 导入导出 | JSON 双向；手动 HTML 解析（可选） | P2 |
| P4 胖乖 | 登录/开水/余额/订单 | P3 可并行 |
| P5 教务 WebView | 适配江西水利电力大学 | **教务 URL** |
| P5b 实验课表导入 | ✅ **已完成**（2026-09-17）：`SyjxScheduleParser` + `CourseKind`/DB v2 + 导入入口区分 + 周课表标注与筛选（见 §4.8） | P5 |
| P6 打磨 | 深色、动效、错误态、真机 | P2–P5b |

---

## 7. 风险

1. **教务未知**：无 URL 则 P5 阻塞；用手动课表兜底。  
2. **胖乖接口变更**：light-life 已提示接口可能再变；网络层集中改 Base/路径。  
3. **包名/品牌侵权观感**：名称用「水贝贝」等非官方注册滥用词；README 注明非学校官方。  
4. **路线 D 工期**：比 fork 长；M1 严格砍范围，先能上课、能开水。  
5. **实验课表 DOM 未知**：必须先做 §4.8 Step 0 快照，否则解析器只能靠猜；syjx 页面若为 AJAX 渲染，注入时机需实测。  
6. **教务脚本链路易被环境代理破坏**（2026-09-17 定位；此前曾误判为教务服务端故障）：shell 若被注入
   `HTTP_PROXY` / `HTTPS_PROXY`（本机实测为 IDE 的本地代理 `http://127.0.0.1:12892`），
   `requests` 会默认读取该变量，请求经代理出口发出，教务 SSO 落点 `/jsxsd/xk/LoginToXk`
   将返回 404 通用错误页，表现为「CAS 认证成功但 `xsMainV` 退回 860 字节的未登录页」。
   同一 URL 时而 200 时而 404，正是代理出口与直连出口被服务端区别对待所致——**不是教务不稳定**。
   **修复**：`scripts/fetch_courses.py` 内两个 Session 均设 `trust_env = False` 强制直连，已实测验证
   （代理变量存在时仍可跑通）。App 端 WebView 由用户手动登录，不经过该落点，不受影响。

---

## 8. 开放问题（2026 已部分敲定）

- [x] 正式包名与应用名 → `edu.jxslu.schedule` / 显示名「水贝贝」  
- [x] 教务登录 URL + 厂商 → **已打通**：CAS `eapp2:9443` + 门户 portal + **强智** `jiaowu.juwp.edu.cn:81` → SSO `:8080/jsxsd`；课表 `xskb_list.do?viweType=0`。见 §4.4 / `scripts/fetch_courses.py`。
- [x] 默认作息表 → **已实测确定**，见 3.5（11 小节，08:30 起，每节 40 分钟）
- [x] 开学日与总周数 → 用户未提供；P2 默认总周数 20，开学日在设置中手填  
- [x] 是否 M1 就要小组件 → **否**，归 M2  
- [x] 胖乖是否默认集成签到 → **默认否**；仅设置开关，默认关

---

## 9. 参考仓库（只读参考，不强制拉进 M1 代码树）

| 仓库 | 用途 |
|------|------|
| https://github.com/XingHeYuZhuan/shiguangschedule | UI/课表/导航参考 |
| https://github.com/XingHeYuZhuan/shiguang_warehouse | JS 适配与仓库结构 |
| https://github.com/XingHeYuZhuan/shiguangschedule/wiki/如何适配教务v2 | 教务 v2 数据模型与 bridge |
| https://github.com/linling-zy/kust-schedule | 分校深度定制 |
| 本地 `F:\light-life-v3.0` | 胖乖 API 与 UI 模块 |

---

## 10. 附录：实现日志去哪了

原 §4.9–§4.19 是逐日实现记录（占全文 58%），2026-09-18 拆出：**规格类内容（多课表数据模型、胖乖调用链）
回填为本文件 §4.9 / §4.10**，纯流水记录移入 **`docs/devlog.md`**（仅本地保留，不入公开仓库）。

拆分后的编号对照（代码注释若引用旧编号，按此表理解）：

| 旧编号 | 现在的去处 |
|--------|-----------|
| 4.15 | → 本文件 **§4.9 多课表支持**（数据模型与分层，仍在 §4） |
| 4.16 | → 本文件 **§4.10 胖乖生活开水模块**（调用链与模块落位，仍在 §4） |
| 4.9 / 4.10（旧） | 网格字号防护、WakeUp 视觉对齐 → `docs/devlog.md` |
| 4.11–4.14 | 显示设置扩展、主题、撞色修复 → `docs/devlog.md` |
| 4.17 ×2 | 设置入口收口（原文件重复编号）→ `docs/devlog.md` |
| 4.18 / 4.19 | 课表页交互修正、今日页重构 → `docs/devlog.md` |

**当前生效的规格**（导航结构、显示设置项、今日页形态等）已就地并入 §3，不必去 devlog 找。
