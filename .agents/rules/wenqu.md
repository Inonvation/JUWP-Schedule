# 问渠（志愿服务 / 劳动教育）

作用域：改 `data/wenqu/`、`ui/wenqu/`、「我的」页账号卡那一行、校园服务里的问渠入口时读。规格见 DESIGN §3.28 / §4.45。

## 这是什么

学校「**问渠**实践教育平台」——**道然科技基于 CRMEB 商城框架定制**的第三方系统，
后端 `https://wenqu.juwp.edu.cn/api/`，入口在「江西水利电力大学水宝宝」公众号 →
便捷服务 → 问渠系统（小程序，appid `wxadcfe9039706799c`）。

**「志愿时长」和「水利文化里的劳动教育」是同一套数据**：小程序首页「水利文化」下三张卡
（文化研究 / 文艺熏陶 / **劳动教育**），其中「劳动教育」指向 `/pages/volunteer_list/index`
（志愿服务列表）。所以一个模块同时覆盖两块，别去找独立的「劳动教育」接口。

## 鉴权：手机号 + 密码（**不是微信**）

三条实测确认的口径，改登录链前先读：

- **账号 = 手机号**，不是学号。`login {account:"<学号>"}` → `账号或密码错误`；
  换成手机号即成功。
- **首次/忘记密码 = `register/reset`**（平台语义是「重置密码」，对**从没设过密码**的账号
  实测就是创建）：`{account:手机号, captcha, password}` → `修改成功`。
  配 `register/verify {phone, type:"reset"}` 发码。
- **日常 = `login`** → `data.token` 是 **3 天**的 JWT（`exp - iat = 259200s`），
  `data` 里**只有 `{token, expires_time}`，没有 cache_key** ——所以续期只能重登，
  而重登只需要手机号+密码（本地存着），**用户无感**。

> 为什么不走微信：小程序主登录是 `POST /api/wechat/mp_auth`，入参是 `wx.login` 的 code
> （或 `{encryptedData, iv, cache_key}`），**只有微信运行时能生成**，App 造不出来。
> `cache_key` 存在微信**加密** MMKV 里，磁盘上无明文。密码这条路是唯一能自助的。

## 接口（全部只读或用户显式触发）

```http
POST /api/register/verify  {"phone":"<手机号>","type":"reset"}       发码
POST /api/register/reset   {"account":"<手机号>","captcha","password"}  设/重置密码
POST /api/login            {"account":"<手机号>","password"}            → token(3天)
POST /api/volunteer/my     {"status":"","start_time":"","end_time":""}  → total_score + list（我参加过的）
POST /api/volunteer/star_cert {}                                        → volunteer_score_total + star_level
POST /api/volunteer/v2     {"page","limit","keyword"}                   → list（平台上有的活动）
POST /api/volunteer/detail/{id}   {}（id 走路径）                        → detail（工时 / 群号只有这里）```

## 七个坑（都踩过）

1. **请求头是 `Authori-zation`**（CRMEB 的错拼），写成 `Authorization` 会被判未登录。
2. **`register/verify` 成功与失败都是 `status:200`**——成功 `msg="发送成功"`，
   失败 `msg="接口请求失败:暂无使用次数"`。**不能走通用成功判定**，见 `WenquEnvelope.sendResult()`。
3. **短信限流约 5 分钟一条**（实测 19:29:44 失败 → 19:34:46 成功）。首次设置页必须有倒计时，
   别让用户狂点。`暂无使用次数` 要翻成「发送过于频繁」再给用户看。
4. **会话失效是 `status:410000` + `msg="请登录"`**，只有它算凭证失效；网络 / 5xx 一律不计
   （对齐一卡通纪律，别把对的密码记成错的）。
5. **响应里的他人信息分两档**（2026-10-08 起）：
   - `leader_name` / `leader_phone`（**活动负责人**）——用户要求详情里展示，**已声明进模型**，
     在详情弹层里显示（电话可长按复制）。**只展示**：不进日志、不进持久化、不进任何备份。
   - `attendance_name` / `attendance_phone`（**考勤人**）——**不声明**，
     `ignoreUnknownKeys` 下连内存都不进。`WenquModelsTest` 有兜底用例钉着（toString 里出现即红）。
   - **夹具 / 文档 / 提交信息里一律用假名假号**（张三 / 13800000000 这种）——
     这个仓库是公开的，真实姓名电话不许进仓库。别把真实响应粘进测试。
6. **`total_score` / `score` / `group_id` 有时是数字、有时是字符串**（详情里 `score` 是裸数字、
   `group_id` 也是裸数字，而 `volunteer/my` 的 `score` 是字符串），
   走 `LenientStringSerializer` / `LenientStringNonNullSerializer` 收成字符串再
   `WenquRules.hoursOf` 解析。
7. **`volunteer/v2` 与 `volunteer/my` 是两张表，别互相复用解析**：前者是「平台上有的活动」
   （`people_num` / `join_num` / `balance_num` / `isApply` / 单条 `status`），
   后者是「我参加过的」（`total_score` / 单条 `score` / `apply_status_text`）。
   字段名有重叠（`title` / `unit` / `start_time`）但语义与取值都不同。
   活动状态文案照小程序那张表 `{0:已取消,1:招募中,2:进行中,3:已结束}`（`WenquRules.statusLabel`）。

## 活动列表的两条实测口径

- **`keyword` 空串合法**（回全量前 `limit` 条）——与教务选课中心「空关键词回 0 行」**相反**，
  别把那边的经验搬过来。`limit` 服务端不限（实测 200 照收），但仍按页取（`ACTIVITY_PAGE_SIZE = 20`）。
- **接口不返回总数**：`hasMore` 只能按「上一页回满 `limit` 即可能还有」推断
  （`WenquActivityPage.hasMore`）。宁可多给一次「加载更多」，也不要漏数据。
- 列表是「按 id 倒序的时间流」且总量上千（实测 ≥800，其中「招募中」只有个位数），
  所以**状态筛选在本地做**（服务端只认关键词），默认「全部」。

## 进页不重复取数（进程内 TTL 缓存）

问渠页是 `SubpageActivity` 上的独立窗口，**每次进页都新建 ViewModel**——旧写法在 `init` 里
一订阅到登录态就取数，于是「进页 = 三次请求」。现在：

- `WenquTtlCache`（纯 Kotlin，`@Synchronized`，`LinkedHashMap` 当 LRU）给工时 / 星级 /
  活动列表各一份 **30 分钟**缓存；`WenquRepository` 是 `Graph` 单例，跨页面、跨窗口都命中。
- 进页还会先用 `cachedActivities` / `cachedVolunteer` **同步**把快照铺上（零请求），
  不闪骨架屏。
- 只有顶栏刷新（`force = true`）绕过缓存；「我的」页的 `refreshHoursIfStale` 过了自己的
  30 分钟闸门后也显式 `force`，别让缓存把「该刷了」又挡回去。
- 换人登录（手机号变了）`clearCaches()`；退登也清。
- **不做磁盘缓存**（第三方公开内容、随时在变；落盘还要处理备份排除与陈旧展示，收益不抵成本），
  **也不做周期轮询**。

## 本地凭证

- 独立文件 **`secure_wenqu`**（`EncryptedSharedPreferences`，**含密码**——与一卡通查询密码、
  快趣账号密码同级）。**两份备份排除 xml 都要登记**（正式件 + `.fallback`），
  漏了会把明文密码带进云备份。
- 自愈三段照抄 `QzxySessionStore`（create 失败 → 删文件重建 → 退明文空 prefs），
  别省——Keystore 损坏时裸 create 在组合期抛异常 = 启动即循环崩溃。
- 存了 token 的 `expiresAt`（从 JWT 解）+ 工时影子（`hours` / `hoursFetchedAt`）。

## 页面与展示

- **「我的」页账号卡的班级行**（`SettingsScreen.AccountBar`）：班级 + `· 志愿 N 工时`。
  **班级先让位**（吃余量、可省略），工时优先保完整；点工时进问渠页。
  未登录时这一行照旧只有班级。
- **校园服务 → 第三方账号**：`问渠 · 志愿服务` 行，`value = 已登录 / 点击登录`，
  副标题「志愿工时 · 活动 · 劳动教育（只读）」。
- **今日页顶栏「+」→「志愿服务」**也落问渠页（2026-10-08，见 `.agents/rules/today-ui.md`）。
- 问渠页（`SubpageScreen.WENQU`）已登录时**两块**：工时卡（**只显示工时数 + 星级**）→
  可报名活动（搜索 + 筛选 chips + 卡片 + 加载更多 + 详情弹层）。顶栏 = 刷新 + 齿轮（设置）。
  **统一卡片** `WenquEntryCard`（`ui/wenqu/WenquCards.kt`）：状态徽章（+ 可选的「已报名」）+
  右侧数字 / 标题（2 行）/ `单位 · 地点 · 时间`（2 行）。活动与记录两种数据源共用它，别各写一套。
- **筛选两个维度 + 一个排序，全在本地**（服务端只认关键词）：
  状态 chips（全部 / 招募中 / 进行中 / 已结束）+ 一枚「隐藏已满」开关 chip
  （`WenquRules.matchesFilter` / `isFull`），再按 **`WenquRules.displayRank` 稳定排序**
  ——平台的顺序**既不是 id 也不是 add_time**（实测前 21 条严格降序、之后跳回旧 id），
  不排的话默认「全部」打开就是满屏已结束。**只重排不隐藏**，组内保持平台原序。
  「满」= 招募中 + 剩余名额 0，**招募人数没填（0）时一律不算满**（平台有整格不填的时候）。
  **两个筛选都落盘**（`wenqu_status_filter`，-1 = 全部；`wenqu_hide_full`）——
  用户专门要过「隐藏已满」，每次进页重置一遍会烦。
- **活动卡在 `isApply` 为真时多一枚「已报名」徽章**：平台一直在回这个字段，
  早先解析了却没展示，报过名的活动和没报名的长得一模一样。
- **账号被限制报名时**列表上方挂一条说明（`WenquActivityPage.blacklisted` /
  `stopApplyReason` → `WenquRules.applyBlockedText`）：它是**账号级**的，与具体活动无关；
  用平台原话，静默丢掉等于让用户自己猜「为什么报不了名」。
- **提示一律走 `AppSnackbarHost`**（全 App 统一那张提示卡）。裸 `SnackbarHost`
  在这套主题里是另一副模样，用户会当成「不是软件内的弹窗」（2026-10-08 用户报）。
- **「加载更多」的反馈**（2026-10-08 用户报「点了没反应」+「要说清加载了多少条」）：
  按钮转圈、落地后滚到第一张新增卡、并**报条数**——「已加载 N 条」/「已加载 N 条 · 没有更多了」/
  「这一页没有符合筛选的活动」/「没有更多活动了」。列表到底再挂一行「已到底 · 本次共加载 N 条」。
- **头部三块（工时卡 / 搜索 / 筛选 / 可选的「报名受限」条）合成一个 `item("header")`**：
  `WenquScreen.HEADER_ITEM_COUNT = 1` 就是它——加载更多的滚动落点靠这个数算，
  合成一个是为了往头部加行时这个常量不跟着变（改回多个 item 要同步改这个常量）。
- **详情取不到不是错误**（2026-10-08 用户报「点记录弹详情，同时飘一条取不到详情」）：
  平台回 `detail: null`（活动已删 / 不可见）是**正常回答**，`activityDetail` 回 null、
  调用方用入口那条（列表 / 记录）继续展示，只在弹层内挂一行说明，**不弹任何提醒**。
- **记录列表是独立二级页 `SubpageScreen.WENQU_RECORDS`（`WenquRecordsScreen`），不是弹层**
  （2026-10-08 用户口径：记录详情本身也要弹层，弹层里再开弹层会互相打架）。
  它有自己的 `WenquRecordsViewModel`，数据走**同一个仓库缓存**——从主页点进来通常零请求。
- **设置页 `SubpageScreen.WENQU_SETTINGS`（`WenquSettingsScreen`）**：主页顶栏齿轮进来。
  2026-10-08 用户口径——**主页只管展示工时数**，记录入口 / 活动提醒 / 退出登录都收在这里。
- **记录的 `id` 就是活动 id**（小程序 `goToDetail` 同口径，实测记录 34658 与
  `volunteer/detail/34658` 标题工时一致）→ **记录详情复用活动详情弹层**
  （`WenquActivitySheet(..., recordBadge = record.statusText)`）：`recordBadge` 非 null
  就切成记录视角——徽章用「我的报名状态」（如「已完成」）、去掉「我的状态」行、页脚换记录口径。
  记录 → 活动的映射是 `WenquVolunteerRecord.asActivity()`，它把 `status` 钉成
  `WENQU_STATUS_NOT_APPLICABLE`（-1）——**别用默认值 0**，平台表里 0 是「已取消」，
  照搬会让记录徽章写「已取消」。自主（DIY）类记录 `id` 为 null（收成 0），不发注定失败的详情请求。
- 活动详情弹层字段：状态徽章 + 「计 N 工时」、活动名、项目名、单位 / 地点 / 活动时间 / 报名时间 /
  招募人数 / 已报名 / 我的状态（仅活动视角）/ **负责人 / 负责人电话** / 活动群号 / 活动内容。
  电话与群号那两行**整行可点即复制**（行尾一枚淡复制图标当提示，行高抬到 44dp 保命中区）——
  比「只能长按选中」好发现得多。
- **群号下面挂一句说明**（2026-10-08 用户口径）：**「请确保在小程序报名成功后再进群」**——
  有些活动的群号是报名后才放出来的。只在群号非空时显示。
- 工时影子 30 分钟 TTL（`WenquRules.hoursStale`）：进「我的」页过期才后台刷一次，
  失败静默（卡片保留旧值）。

## 新增志愿活动提醒（2026-10-08）

`ui/reminder/WenquActivityAlertReminder.kt`，**搭 `ScoreAlertReminder` 的周期任务（第五路）**，
不新开 WorkManager 任务；开关 / 间隔 / 检查时刻三个闸门都在本类内自判。

- 开关 `wenqu_activity_alert_enabled` **默认关**（通知是打扰型能力，同选课 / 调课口径）；
  间隔 `wenqu_activity_interval_hours` 默认 **24**，档位复用 `ScoreAlertDefaults.INTERVAL_CHOICES`
  （同一个 App 里两个「每 N 小时」给不同档位会让人以为哪个坏了）。
- **只报「招募中」**（`WenquRules.isNewRecruiting`）：平台会把补录的老活动插进列表
  （实测 id 最大的一条是「补录」且已结束），按 id 无差别报会把已结束的活动报成「新活动」。
- **首跑只建基线**（`wenqu_activity_last_seen_id` ≤ 0 时一律不算新增）：第一次抓到的全量
  是基线不是「新增」，否则用户一开开关就被几十条历史活动轰一遍。基线**只前进不后退**。
- **扫描一整页 100 条**（`WenquRepository.scanActivities`）：`volunteer/v2` **不保证按 id 排序**
  （实测前 21 条严格降序、之后跳回旧 id），只扫 20 条会漏掉夹在中间的新活动。
  它与 `activities()` 分开是**语义不同**：那个是「浏览一页」（20 条、进缓存），
  这个是「扫一遍看有没有新活动」（一次取够、不进缓存）。
- **检查时刻只在成功时落**：未登录 / 网络 / 5xx 都不写，下一轮还试（同成绩/考试纪律）。
- **扫描会顺手刷新浏览页缓存**（2026-10-08）：提醒刚说「有新活动」，用户点通知进来却看到
  30 分钟前的旧列表——这个坑就是这么来的。`scanActivities` 成功后把 `""` 那条缓存写成
  刚抓到的**前 20 条**（`page = 1`）：与 `activities()` 的分页口径一致，
  「加载更多」接着取 page 2 不会与多抓的 80 条重叠。
- **改开关 / 间隔走 `WenquActivityAlertReminder.onSettingsChanged`，不要用
  `ScoreAlertReminder.onSettingsChanged`**：后者会把五路的检查时刻一起清 0，于是用户只是
  动一下问渠的开关，成绩 / 考试 / 选课 / 调课四路就各打一次教务（2026-10-08 复查发现）。
  问渠自己的入口只清自己那一路，再 `ensurePeriodicWork` + `enqueueWenquCheck`
  （`ScoreAlertReminder.enqueueCheck(onlyChain = "wenqu")`，复用同一个 Worker 类名与工作名，
  不新开任务）。
- 通知走**免扰时段拦截**（事实型，`eventAt = null` → 时段结束补发）+ **收件箱留痕**
  （`InboxKind.WENQU_ACTIVITY`，键 `wenqu|activity|<本批最大 id>`，一次核对只留一条痕）。
  通知 id 1013 / 落点 requestCode 3013 / 渠道 `wenqu_activity`——**别与既有撞号**
  （1001/1004 上课、1002 作业、1005/1006 余额、1008/1009 成绩、1010 选课、1011 调课与 U净、
  3005–3012 各落点）。
- 设置页改开关 / 间隔后要调 `ScoreAlertReminder.onSettingsChanged`（清检查时刻 + 重排周期
  + 立刻评估一次），否则新规则当天不生效。

## 红线

- **只读**：只发上面七个端点里的**读**那些（`volunteer/my` / `volunteer/star_cert` /
  `volunteer/v2` / `volunteer/detail/{id}`）。报名 / 签到 / 提交等写接口**一律不碰**
  （`volunteer/apply`、`volunteer/checkin`、`*_submit` 一个都不要调）。
- **无日志拦截器**：手机号 / 密码 / token / 负责人姓名电话绝不进 Logcat（对齐一卡通 / 快趣 / U净）。
- 写操作只有「用户点发码」与「用户点设密码」两处，各发一次、失败不自动重试。
- 负责人姓名与电话**只在详情弹层展示**，不进日志 / 持久化 / 夹具 / 文档；
  考勤人字段**不进模型**（见「七个坑」第 5 条）。
