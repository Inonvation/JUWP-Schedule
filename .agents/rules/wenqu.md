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

## 接口（读 + 用户显式触发的写）

```http
POST /api/register/verify  {"phone":"<手机号>","type":"reset"}       发码
POST /api/register/reset   {"account":"<手机号>","captcha","password"}  设/重置密码
POST /api/login            {"account":"<手机号>","password"}            → token(3天)
POST /api/volunteer/my     {"status":"","start_time":"","end_time":""}  → total_score + list（我参加过的）
POST /api/volunteer/star_cert {}                                        → volunteer_score_total + star_level
POST /api/volunteer/v2     {"page","limit","keyword"}                   → list（平台上有的活动）
POST /api/volunteer/detail/{id}   {}（id 走路径）                        → detail + apply（工时 / 群号 / 报名 id 只有这里）
POST /api/volunteer/apply  {"id":<活动id>}                              报名
POST /api/volunteer/apply/cancel/{id}   {}（id 走路径）                  取消报名
```

**写端点是怎么探出来的（2026-10-11，别再重复踩）**：未登录请求**真端点回 `200` +
`{"status":410000,"msg":"请登录"}`，不存在的路径回 `404`**，且路由**大小写不敏感**
（`volunteer/MY`、`VOLUNTEER/my` 都命中）——用这个信号就能问出路由存在性、不碰任何数据。
`volunteer/apply` 与 `volunteer/checkin` 就是这么确认的；**取消报名藏在路径参数路由里**
（`volunteer/apply/cancel/{id}`），只试不带 id 的 `volunteer/apply/cancel` 会 404 漏掉它
——`volunteer/detail/{id}` 同族，**探路由时路径参数要一起试**。

- **报名**：`POST volunteer/apply`，body `{"id": <活动id>}`。实测 `{}` / `id=0` → `参数错误`
  （`id` 必填），`id` 不存在 → `服务项目不存在或已下架`（它查的是**活动**）⇒ `id` 是活动 id。
- **取消报名**：`POST volunteer/apply/cancel/{id}`。`{id}` 是**活动 id** 还是**报名 id** 未钉死
  （平台对两种不存在的 id 都回 `您未报名该活动`）；App 先发活动 id，撞上这句且手里有报名 id
  时再换一次（见 `WenquRepository.cancelApply`）。
- `volunteer/my` 的每条记录带 **`apply_id`**（报名 id）、`apply_status`、**`canCancel`**
  （平台算好的「这条还能不能取消」，**只有记录列表有这个字段**）；`volunteer/detail/{id}` 的
  `apply` 段 = 我对这条活动的报名（`id` 报名 id / `aid` 活动 id / `status`），里面还有
  `username` / `student_no` 等个人字段——**模型里不声明**，`ignoreUnknownKeys` 下连内存都不进。

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
  别把那边的经验搬过来。`limit` 服务端不限（实测 200 照收）。
  **App 侧只剩两个用处**：抓全量快照时按页翻（见下节）+ 快照不完整时的兜底查询。
- **接口不返回总数**：早先靠「上一页回满 `limit` 即可能还有」推断 `hasMore` 做「加载更多」，
  **2026-10-11 起整条分页浏览都删了**（列表 = 全量快照，见下节），`WenquActivityPage` 的
  `page` / `hasMore` 字段一并删掉——没人读了。
- 列表是「按 id 倒序的时间流」且总量上千（实测 ≥800，其中「招募中」只有个位数），
  所以**状态筛选在本地做**，默认「全部」。

## 列表与搜索 = 本地全量快照（2026-10-11）

**为什么改（两轮，同一个根因）**：① 用户报「志愿列表里明明有『爱心家教』的活动，搜索却搜不到」
——搜索原先完全依赖服务端 `keyword`，而那个参数到底匹配哪些字段**无法验证**（无 token 回
`410000 请登录`）；② 用户报「选了『招募中优先』还是把旧的已结束排前面，切『招募中』显示没有、
点加载更多才有」——因为列表只拿**服务端第一页 20 条**，而本地筛选 / 排序都作用在这 20 条上，
招募中只有个位数且不在第一页。**筛选与排序必须覆盖全量才有意义**，所以列表也换成快照。

**现在的形态**：列表与搜索**共用一份全量快照**，服务端查询只做兜底。

- `WenquRepository.activityIndex()` 按**三条路**分派，判据是纯函数
  `WenquRules.indexLoadPlan`（JVM 测）：

  | 路 | 什么时候 | 代价 |
  |----|----------|------|
  | `UseCache` | 距上次同步 < `INDEX_SYNC_INTERVAL_MS`（30 分钟） | **零请求** |
  | `SyncNewest` | 到点了 / 下拉刷新（`force`） | **1 个请求**：只抓第一页 20 条，按 id 合并（`mergeNewest`） |
  | `Rebuild` | 没快照 / 快照不完整 / 上次**整份重抓**超过 `INDEX_MAX_AGE_MS`（24 小时） | 整份重抓（按页翻，几秒） |

  **增量为什么够**：平台列表**开头按 id 倒序**（实测前 21 条严格降序），新发布的活动一定
  落在第一页——抓一页就能把新的捞回来（用户口径：已发布的老活动基本不会改，没必要重复请求）。
- **增量的代价与两个兜底**：深层条目的**可变字段**（名额 / 状态 / 已报名）会冻在抓取那一刻。
  ① 24 小时一次整份重抓把漂移封顶（同时清掉平台侧删掉的条目）；
  ② `WenquRules.effectiveStatus` 用**不可变的时间字段**校正状态——**活动结束时间已过 → 已结束**
  （冻住的「招募中」会被本地排序顶到最上面，这条就是堵它）。「报名截止已过但活动还没结束」
  平台语义没实测过，**不猜**。校正发生在**数据进状态**那一步（`WenquViewModel.corrected`），
  每次装载一次、不是每次重组。
- **整份重抓**（`buildIndex`）：按页抓 `volunteer/v2`（`keyword=""`、
  `limit = ACTIVITY_INDEX_PAGE_SIZE = 200`、`page = 1..`），页间按
  `AutoSyncRules.interRequestDelayMs()`（200–600ms）放慢；并发调用由 `indexMutex` 串行化。
  **进页就按上表走**（`WenquViewModel.loadIndex`），抓完就是列表；下拉刷新走增量。
- **收工判据全在 `WenquRules.shouldContinueIndexing`**（纯函数，JVM 测）：这一页没带来新 id
  / 累计触顶（`ACTIVITY_INDEX_MAX_ITEMS = 2000`）/ 页数触顶（`ACTIVITY_INDEX_MAX_PAGES = 15`）
  / 超时间预算（`ACTIVITY_INDEX_BUDGET_MS = 8000`），任一命中就停。
  **只有「没带来新 id」算抓全**（`WenquActivityIndex.complete = true`），其余都是部分快照
  ——页面据此在列表底部说「这次没抓全，下拉刷新可重试」。
- **失败处理**：首页失败 → 回 null（不缓存，下次进页重试）；中途失败 → 带着部分收工**并缓存**
  （`complete = false`），否则网络一抖就要每次进页重爬一遍。`index == null` 时页面给
  「活动没抓到，下拉刷新重试」的空态（**没有「只显示第一页」的降级**——那正是要修掉的毛病）。
- **账号级报名限制跟着快照带出来**：`WenquActivityIndex.blacklisted` / `stopApplyReason`
  （每页都一样），页面据此挂「报名受限」说明——列表不再有「浏览页」那个载体了。
- **匹配字段** = `WenquRules.matchesKeyword`：活动名 / 项目名 / 单位 / 地点，trim + 小写后
  子串包含。**故意不传 `content`**——搜出来的卡片上看不到那个词，用户会当成乱命中。
- **命中来源**：快照 `complete` 时**纯本地、零请求**；不完整或建不起来时再补一次服务端关键词
  查询（只取第一页），按 id 去重并入（`mergeActivities`，本地那份优先）。
- **清空检索词 = 回浏览列表，一个请求都不发**：快照那串（`indexItems`）在搜索期间一直留着。
- **在飞的快照抓取挂在 `viewModelScope` 上、不随搜索协程取消**（`WenquViewModel.indexJob`）：
  输入防抖后每次改词都会取消上一个搜索协程，抓取若挂在它下面，用户一边打字就把一次全量抓取
  反复掐掉。**搜索结果落地带世代号**（`searchSeq`）——前一个词的慢查询回来不覆盖后一个词的结果。
- **没有「加载更多」**：列表本来就是全量（本地筛 / 排 / 搜），`loadMore` / `hasMore` /
  `loadMoreSeq` / `WenquActivityPage.page` 全部删掉；`scanActivities` 也不再顺手写浏览页缓存
  （没有读的人了）。列表底部给「已到底 · 共 N 条」。

**界面对应的三件事**（§3.28）：即输即搜（防抖 `SEARCH_DEBOUNCE_MS = 300`，有索引时是内存过滤
所以不打服务端）；搜索模式条数行（「找到 N 条 · 筛选隐藏 M 条」）；**三类空态**——
正在建索引 / 命中了但被本地筛选藏了（给「清除筛选」）/ 真没有匹配。

> 兜底那一跳在索引抓全时不会发生。若哪天拿到一条真实响应样本能钉死服务端 `keyword` 的匹配
> 字段，这一跳可以彻底删掉。

## 进页不重复取数（进程内 TTL 缓存）

问渠页是 `SubpageActivity` 上的独立窗口，**每次进页都新建 ViewModel**——旧写法在 `init` 里
一订阅到登录态就取数，于是「进页 = 三次请求」。现在：

- `WenquTtlCache`（纯 Kotlin，`@Synchronized`，`LinkedHashMap` 当 LRU）给工时 / 星级 /
  活动列表各一份 **30 分钟**缓存；`WenquRepository` 是 `Graph` 单例，跨页面、跨窗口都命中。
- 进页还会先用 `cachedActivities` / `cachedVolunteer` **同步**把快照铺上（零请求），
  不闪骨架屏。
- 只有**下拉刷新**（`force = true`）绕过缓存；「我的」页的 `refreshHoursIfStale` 过了自己的
  30 分钟闸门后也显式 `force`，别让缓存把「该刷了」又挡回去。
  **下拉刷新在搜索模式下不重抓索引**（2026-10-11）：只把命中重算一遍，索引在 TTL 内直接复用
  ——重抓一次要几秒，用户点刷新不该干等它（真过期了 `ensureIndex` 自己会重来）。
  浏览那串照旧强刷，所以清空检索词时看到的是刚刷到的列表。
- 换人登录（手机号变了）`clearCaches()`；退登也清（索引缓存 `indexCache` 一并清）。
- **快照是唯一会打「整份抓」的动作**（`activityIndex`，见上节）：首次进页整份抓一份
  （约 5–10 个请求 / 1000 条、几秒，页面给「正在获取全部活动…」的提示）；
  之后 30 分钟内进页 / 筛选 / 排序 / 搜索全部零请求，到点只**增量**抓第一页（1 个请求）；
  下拉刷新也是增量；只有满 24 小时或快照不完整才再整份重抓。
  **这是列表的正确性成本**：只拿服务端第一页 20 条时，本地筛选 / 排序就是错的
  （2026-10-11 用户报的那两条）。
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
  副标题「志愿工时 · 活动报名 · 劳动教育」。
- **今日页顶栏「+」→「志愿服务」**也落问渠页（2026-10-08，见 `.agents/rules/today-ui.md`）。
- 问渠页（`SubpageScreen.WENQU`）已登录时**两块**（2026-10-11 按提案页收口）：
  **工时行**（一行：工时数 + 小时 + 星级 + 右侧「记录 ›」）→ **可报名活动**
  （搜索行 + 状态分段 + 条数·排序行 + 卡片 + 详情弹层；列表 = 全量快照，没有「加载更多」）。
  顶栏 = **只有齿轮（设置）**——刷新走**下拉**（2026-10-11，`PullToRefreshBox`，同流水 / 账单 /
  生活 / 趣智 / 开水那几页的既有口径；记录页同款）。
  - 工时行是 2026-10-11 由整块大卡（~100dp）收成一行的：首屏原来只剩 2 张活动卡，
    而主角是活动列表。**记录入口两处都在**（这行右侧 + 设置页那行），谁近用谁。
  - 顶栏标题是 **「志愿服务」**（原「问渠 · 志愿服务」，页面里不需要重复品牌名）。
- **统一卡片** `WenquEntryCard`（`ui/wenqu/WenquCards.kt`）：首行 = 状态徽章
  （+ 可选的「已报名」）· **名额** · **时间（右对齐）**；第二行标题（2 行，**搜索时命中高亮**）；
  第三行 `单位 · 地点`（1 行）。活动与记录两种数据源共用它，别各写一套。
  - **时间必须在首行右端**：挤进 `单位 · 地点 · 时间` 那行时，长单位名会先把时间吃掉
    （2026-10-11 修的就是它）。
  - 名额走 `WenquRules.cardQuotaText`（只对招募中给「还剩 N」/「已满」），详情弹层里
    完整口径仍走 `quotaText`。
  - 已结束 / 已取消的卡整体压暗（alpha 0.62）。
- **搜索框的文案要与实现一致**：`搜索活动 / 单位 / 地点`（匹配字段是 活动名 / 项目名 / 单位 /
  地点，见 `WenquRules.matchesKeyword`；placeholder 短是为了窄屏不截断，完整字段说明在空态里）。
  改匹配字段就同步改文案，别让文案许愿。
- **筛选与排序**（2026-10-11 改版）：
  - **状态**留在页面上：4 项 `SingleChoiceSegmentedButtonRow` **等宽分段**，不滚动。
    原来的 5 枚横向滚动 chip 粗算约 400dp > 内容宽 328dp，最右那枚被裁在屏外
    ——「看不见的选项等于不存在」。
  - **低频项在底部弹层**（`ui/wenqu/WenquFilterSheet.kt`）：**只看我报名的**（`isApply`）
    + **隐藏已满** + **排序三选** + 「清除筛选」+「看 N 条」（实时算筛选后的条数）。
    弹层无输入框 → 走 `ModalBottomSheet` + `rememberSheetDismisser`（关弹层先播退场动画）。
  - **排序三档**（`WenquRules.Sort`）：招募中优先（默认 `displayRank`）/ 报名截止临近
    （`closingSortKey`，已截止与没填的排最后）/ 最新发布（id 降序）。`sortedBy` 稳定排序，
    **只重排不隐藏**，组内保持平台原序（平台的顺序既不是 id 也不是 add_time）。
  - 三个筛选 + 排序**都落盘**：`wenqu_status_filter`（-1 = 全部）/ `wenqu_hide_full` /
    `wenqu_applied_only` / `wenqu_sort`（**存枚举 name**，往枚举中间插一档不错位）。
  - 「满」= 招募中 + 剩余名额 0，**招募人数没填（0）时一律不算满**（平台有整格不填的时候）。
  - 角标 = 弹层里开着几项（状态不算）；**条数·排序行**左写条数、右写当前档位，点它开同一个弹层。
- **命中高亮**：搜索时把命中的段标成主色加粗，位置由 `WenquRules.keywordRanges` 算
  （与 `matchesKeyword` 同一份归一化，别另写一份）。
- **活动卡在 `isApply` 为真时多一枚「已报名」徽章**：平台一直在回这个字段，
  早先解析了却没展示，报过名的活动和没报名的长得一模一样。
- **账号被限制报名时**列表上方挂一条说明（`WenquActivityIndex.blacklisted` /
  `stopApplyReason` → `WenquRules.applyBlockedText`；2026-10-11 起从快照带出来）：
  它是**账号级**的，与具体活动无关；用平台原话，静默丢掉等于让用户自己猜「为什么报不了名」。
  默认**只占一行**（点开看平台原话）——它长期不变，摊开会白占一屏。
- **提示一律走 `AppSnackbarHost`**（全 App 统一那张提示卡）。裸 `SnackbarHost`
  在这套主题里是另一副模样，用户会当成「不是软件内的弹窗」（2026-10-08 用户报）。
- **头部（工时行 / 搜索行 / 状态分段 / 条数·排序 / 可选的「报名受限」条）合成一个
  `item("header")`**：往头部加一行不会改变列表的 item 计数。
- **条数行 + 四类空态**（2026-10-11）：条数行左写「共 N 条」（搜索模式「找到 N 条」）、
  右写当前排序档位（点它开筛选弹层）；有数据却被本地筛选藏掉时追「· 筛选隐藏 M 条」。
  列表空时分四种——正在抓快照（「正在获取全部活动…（首次需要几秒，之后走缓存）」）/
  **一条都没抓到**（「活动没抓到，下拉刷新重试」）/ 原始条数 > 0 但被筛选全挡掉
  （「有 N 条活动被当前筛选隐藏了」+ **「清除筛选」按钮**）/ 真没有
  （快照 `complete` 时说「平台上没有名称 / 项目 / 单位 / 地点含这段字的活动」，否则说没抓全）。
  **别把「被筛掉了」说成「没搜到」**——用户会去反复换关键词。
- **列表底部**：「已到底 · 共 N 条」；快照没抓全时补一句「（这次没抓全，下拉刷新可重试）」。
- **加载态给骨架、不给转圈文案**（2026-10-11，用户口径）：主页列表区与记录页首屏都走
  `ui/wenqu/WenquSkeleton.kt`（`SkeletonBox` + `lineHeightDp`，几何与卡片一致，数据回来时不跳）。
  **文字行必须走 `lineHeightDp`**，别写死 dp——系统字体调大时写死的骨架会比成品矮一截，
  跳动又回来了（同生活页流水卡口径）。条高 = 行高 × 0.62。
- **「回到顶部」**（2026-10-11）：列表是全量快照（上千条），滚下去之后回顶很费劲——
  滚过一屏（`firstVisibleItemIndex > 4`）出现一枚贴底右的小 FAB
  （`SmallFloatingActionButton` + `HugeIcons.ArrowUp01`），按下给触感 + `animateScrollToItem(0)`。
  它是**贴底悬浮件**：出现时把 `LocalBottomBarClearance` 垫成 64dp，否则 Snackbar 会压在它上面
  （同底栏那套口径）；列表底部 contentPadding 同时留 76dp，别让它盖住最后一张卡。
- **换筛选 / 排序 / 检索词 → 列表拉回顶部**（2026-10-11）：列表是全量，换条件后内容整批换掉，
  停在原来的滚动位置等于把用户丢在「另一份结果的中间」。`LaunchedEffect` 盯
  `statusFilter / hideFull / appliedOnly / sort / keyword` 五个键。
- **卡片时刻写「今天 / 明天」**（`WenquRules.cardTimeLabel`）：今天 / 明天（抢活动的常态）
  一眼就看得懂，不用先算日期；只有日期没时刻时只给「今天」，别拼出「今天 10-11」。
- **详情取不到不是错误**（2026-10-08 用户报「点记录弹详情，同时飘一条取不到详情」）：
  平台回 `detail: null`（活动已删 / 不可见）是**正常回答**，`activityDetail` 回 null、
  调用方用入口那条（列表 / 记录）继续展示，只在弹层内挂一行说明，**不弹任何提醒**。
- **记录列表是独立二级页 `SubpageScreen.WENQU_RECORDS`（`WenquRecordsScreen`），不是弹层**
  （2026-10-08 用户口径：记录详情本身也要弹层，弹层里再开弹层会互相打架）。
  页首「共 N 条 · 累计 N 小时」，下面**按年份分组**（年份降序，「未标注时间」排最后，
  组内保持平台原序）——记录一多就是一条长流水（2026-10-11）。
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
- **底部动作行**（2026-10-11 改版，本应用不再是纯只读）：次按钮固定 `复制活动名`，
  主按钮按状态三选一——**可报名 → 报名 / 已报名且平台允许 → 取消报名 / 其余 → 去微信报名**
  （不能报名时按钮禁用 + 行上方写原因，别让按钮凭空消失）。
  - 两个写操作都**先过二次确认**（`WenquActionConfirmDialog`，接 `DialogWindowFadeEffect`）；
    失败原因留在弹层里（`actionError`，**不飘页面级提醒**——弹层比页面高一层，会被盖住）；
    进行中按钮转圈 + 防连点（VM 的 `applying` 闸）。
  - 记录视角默认不给动作行，**只有平台说这条能取消（`canCancel`）时才补一个「取消报名」**。
  - 复制 / 去微信那类动作**先关弹层再执行**（`ImeAwareModalBottomSheet(pendingDismiss)`）；
    `去微信报名` 走 `startActivityOutsideApp` + 复制活动名，**只递手不代报名**。
  - 电话与群号那两行**整行可点即复制**（行尾一枚淡复制图标当提示，行高抬到 44dp 保命中区）。
- **报名闸门是纯函数** `WenquRules.applyBlockReason`（JVM 测）：账号被限制 → 已报名 →
  活动状态（已取消 / 进行中 / 已结束）→ 报名时间窗口 → 名额已满，回空串才可报名。
  **活动视角的取消入口**走 `WenquRules.canCancelFromActivity`（已报名 + 招募中）——
  平台只在 `volunteer/my` 回 `canCancel`，活动视角拿不到权威值，给个必然被拒的按钮只会白点。
- **写操作后的本地更新**：仓库就地改索引里那条（`applied` / `joinNum` / `balanceNum` 乐观更新）
  并作废详情 / 「我的记录」缓存；权威值等下一次同步回来对齐。列表那条当场变「已报名」。
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
  它与 `activities()` 分开是**语义不同**：那个是「取一页」（进缓存、只给搜索兜底用），
  这个是「扫一遍看有没有新活动」（一次取够、**不写任何缓存**，2026-10-11 起）。
- **检查时刻只在成功时落**：未登录 / 网络 / 5xx 都不写，下一轮还试（同成绩/考试纪律）。
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

## 每周日 12:00「抢活动」提醒（2026-10-11）

用户口径：**学校很多志愿活动都是周日中午 12:00 发布、当场开抢**，所以要一条「提前 N 分钟」
的固定提醒。实现在 `ui/reminder/WenquWeeklyAlertReminder.kt`。

**与「新增志愿活动提醒」是两件事，别合并**：那条是**事后发现**（周期扫列表、依赖接口 + 网络），
这条是**事前蹲点**（发布时刻还没到，扫也扫不到新东西）——所以它**一条网络请求都不发**，
只有一条精确闹钟。

- 排法同 `SelectionAlertReminder`：主 = `setAlarmClock`（requestCode **4007**，不需要特殊权限）；
  兜 = 一次性 Work（闹钟落点 / 开机 / 覆盖安装 / 开关变更）+ 冷启动 `JuwApplication` 补排。
  **不搭周期任务**（不是「周期扫」那类，别往 `ScoreAlertReminder` 里塞）。
- 时刻算在 `WenquRules`：`nextWeeklyAlertPoint` / `lastWeeklyAlertPoint`（纯函数，JVM 测）。
  `publishAt` = 本周日 12:00（本地时区），`remindAt` = `publishAt` − 提前量（**1–10 分钟，
  默认 5**，`WEEKLY_ALERT_OFFSET_*`）。
- **已发键是「发布时刻」不是「提醒时刻」**（`wenqu_weekly_alert_last_fired_at`）：用户把提前量
  从 5 分钟改成 10 分钟，同一周不该再报一次。
- **迟到窗口 10 分钟**（`WEEKLY_ALERT_LATE_MS`，对齐免扰时段对时刻型提醒的过期口径）：
  过了就不补发——用户要的是「12:00 前就位」，12:30 才响只是噪音。
- **通知被系统整体关掉时不落键**（下次冷启动 / 开机还在窗口内就还能补）。这条**不入箱**
  （时刻型提醒不入箱——与选课那条不同，那边有收件箱兜底）。
- 通知走**免扰拦截**（时刻型，`eventAt` = 发布时刻）+ **直接震动**（`ReminderVibration`）。
  **不挂通用「通知强提醒」开关**：这条是用户为「抢」显式开启的、一周一次的能力，而 HyperOS 上
  第三方通知的振动开关默认是关的（2026-10-05 实测），不直接震等于静默。
- 响过之后调 `WenquRepository.invalidateActivities()`：把快照的**同步时刻清零**，
  下次进页立刻增量同步一次（**不是整份重抓**）——用户点通知进来时不该看到发布前的旧列表。
  **只清时刻、不发请求**——进页照旧是用户动作。
- 通知 id **1015** / 落点 requestCode **3014** / 渠道 `wenqu_weekly`；设置页改开关 / 提前量走
  `WenquWeeklyAlertReminder.onSettingsChanged`（**只重排自己的闹钟**，别碰
  `ScoreAlertReminder.onSettingsChanged`——那会顺带骚扰教务四路）。
- 开关 `wenqu_weekly_alert_enabled` **默认关**（打扰型能力，同选课 / 调课 / 新增活动提醒口径）。

## 红线

- **写操作只有用户显式触发的四处**：发码 / 设密码 / **报名** / **取消报名**，
  一律只发一次、失败不自动重试（`WenquRepository.writeOnce`）。
  **签到（`volunteer/checkin`）与提交材料类（`*_submit`）仍不碰**——它们有到点 / 定位语义，
  不是「一次点击」能安全覆盖的。
- 报名 / 取消都**先过二次确认**（`WenquActionConfirmDialog`），失败原因留在弹层里
  （页面级 Snackbar 会被弹层盖住）。
- **无日志拦截器**：手机号 / 密码 / token / 负责人姓名电话绝不进 Logcat（对齐一卡通 / 快趣 / U净）。
- 负责人姓名与电话**只在详情弹层展示**，不进日志 / 持久化 / 夹具 / 文档；
  考勤人字段**不进模型**（见「七个坑」第 5 条）；报名记录里的 `username` / `student_no`
  同理**不进模型**（见「接口」节）。
