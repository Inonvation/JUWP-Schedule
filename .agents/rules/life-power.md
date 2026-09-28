# 生活页 · 校园卡 · 电费 · 胖乖生活

作用域：改生活页、一卡通付款码与流水、寝室电费读写与充值、余额提醒、胖乖生活（开水 / 余额 / 订单）时读。规格见 DESIGN §3.13 / §4.19 / §4.24。

## 胖乖（P4）开水

- 参考 `F:\light-life-v3.0`；Base `https://userapi.qiekj.com/`
- 只做：登录、开水、余额、订单、充值深链；签到默认关；禁止刷积分
- 实现在 `data/qiekj/` + `ui/water/`；Token 走 EncryptedSharedPreferences，禁止进日志
- **登录态只有一份**（2026-09-27）：`QiekjRepository.loggedIn`（StateFlow，`saveToken` /
  `logout` 就地翻转）。今日页开水卡与开水页各持一份 `WaterViewModel`，**别在 VM 里另存布尔**：
  init 读它、`syncLoginState` 跟着它翻转（相等即跳过，否则本地登录路径会把余额/设备各刷两轮）。
  `saveToken` 只在**校验通过后**调：Token 登录先 `validateToken(token)` 再落盘，顺序倒了
  今日页卡片会先切已登录再跳回来。账户卡那格与「扩展服务」副标题订阅同一条流，别再改回
  组合期读一次 / ON_RESUME 重读 token。
- **退出要二次确认**（2026-09-27）：胖乖生活页顶栏的退出图标先弹 `AlertDialog`。确认后
  token 与开水订单快照一起清，重新登录要再收一次短信——代价不对等，别改回一点就走。
- **充值是深链不是 REST**（2026-09-28）：QiekjApi 没有充值端点，余额行的「充值」走
  `ui/water/QiekjRecharge` 深链拉支付宝的胖乖生活小程序充值页（口径与实现细节同
  DESIGN §6「2026-09-28 胖乖生活充值入口与页内打磨」：分享短链剥埋点、page 值整体
  编码、`alipays` scheme queries 声明与趣智共用）。深链整串被 `QiekjRechargeTest`
  钉死，别改字符串；返回本页的 ON_RESUME 余额重拉挂在 `WaterScreen` 的
  `awaitingRecharge` 上，别挪进 VM。小注里「核对小程序账号与本 App 登录手机号一致」
  是防充错账户的用户要求（2026-09-28），别删。Token 登录**拿不到**手机号：已知接口面
  （QiekjApi 与参考工程逐端点对过）没有任何按 token 查用户的端点，响应里也无手机号
  字段，只能沿用短信登录记住的号码——别为它硬猜第三方接口。
- **刷新是整页下拉，不是按钮**（2026-09-28）：余额行的刷新图标已删，下拉刷新走
  `PullToRefreshBox` + `WaterViewModel.refresh()`（余额、设备两轮 join 完才收指示器；
  进页首载与各登录路径仍直调 refreshBalance / refreshDevices）。
  `WaterUiState.refreshing` 只归下拉刷新，别拿来当一般 loading 用。
- **登录手机号默认遮蔽**（2026-09-28）：余额行下显示 `accountPhone` = 仓库记住的
  登录手机号（短信登录落盘、Token 登录沿用旧值、没有就不显示），遮蔽复用 domain 的
  `QzxyPhoneMask`（名字带 Qzxy 但是通用工具），点眼睛才展开。它与登录输入框的
  `phone` 字段是两回事——那个值用户随时在改，别把两处合并。
- **复制 Token 的出口在顶栏**（2026-09-28）：已登录时顶栏 `Copy01` 图标 →
  `WaterViewModel.exportToken()`（= `QiekjRepository.readToken()`，只读、不走网络）
  进剪贴板，snackbar 按「账号通行证，只粘到本应用的『Token 登录』」口径提示
  （对齐趣智「复制会话串」）。这是 token 唯一的主动出 App 通道，别再加别的口。
- 一卡通付款码（DESIGN §3.10/§4.19）：密码字段 = 安全键盘密文（字形 MD5 表一次替换）+ `$1$` + uuid；
  **未知字形/非双射/样板自检不过 = 立即报错不猜**；登录密码仅数字（键盘只映射 0-9）；
  token 只存内存不落盘；付款码不进日志/剪贴板/相册；凭证交互照 `TweakDetectScreen`（开启先真实验证、关闭即清除）
  调用链（11 步顺序不可乱）见 DESIGN §4.10

## 生活页（一卡通 · 寝室电费）

- 底栏第三项「生活」，开关 `DisplayPrefs.lifeTabEnabled` **默认开**（我的 → 通用 → 生活页）；
  关掉后底栏回到 3 项，页内关掉时自动退回今日页。今日页的一卡通卡入口已移除，见 `today-ui.md`。
- **样式（2026-09-26 重构，`LifeScreen.kt`）**：付款码条 → 我的钱包卡 → 最近流水（分两段）。
  改版式之前先读 DESIGN §3.13 的「2026-09-26 版式重构」那一段，那里有改的理由。
- **码不预取，且只有一处出码**：生活页只放一条付款码入口，点了进全屏 `PayCodeScreen`；
  取码、`FLAG_SECURE`、亮度拉满、`startPayWatch` 的「扫码后自动退出」全在那一页。
  **别再往生活页内嵌码位**——2026-09-23 到 2026-09-26 之间那套内嵌卡（`CodeSlots` +
  码位骨架 + 三枚等宽按钮）已删，未取码时要占约 280dp 的空框与置灰按钮，展示的却是
  「还没有出示」。扣款结果仍经 `PayCodeResultBus` 回生活页弹「支付成功」，那条链路别断。
- **钱包卡两栏靠 `IntrinsicSize.Min` + 栏内 `weight(1f)` 空隙等高**（两个「充值」按钮落在
  同一水平线），取数时刻写在各自栏标题行右侧，说明合并在卡头的「钱包说明」弹窗里。
  栏内 `clickable` 刷新、按钮自己吃点击，改的时候别把这两个点击合成一个。
- **流水卡按来源分两段**（`domain/LifeFeed.sections`，每段 `SECTION_LIMIT = 2` 条），
  段标题右侧各接自己的出口（一卡通 → `CAMPUS_STATEMENT`，电费 → `POWER_BILL`）。
  **别再退回混排**：混在一起挂两个出口，用户点之前看不出会跳去哪。
- **流水区分段先骨架再数据**：两个来源都异步，进页第一帧两段都是空的。两段各自的 loading
  （`LifeUiState.campusFeedLoading` / `powerFeedLoading`，2026-09-26 由整卡一个 `feedLoading`
  拆开——用户报「加载太慢」，根因是本地一卡通陪电费的网络请求一起挂骨架）都从 **true** 起，
  未就绪的那段渲染两条 `FeedRowSkeleton`（行盒走 `lineHeightDp`，与 `FeedRow` 对齐；文字条
  本身取行盒 62% 居中，见 `ui-common.md`）。一卡通就绪 = Room 流首帧；电费就绪 = 流水链路
  跑完一次（**失败也算**，否则会一直挂骨架）。**别用 `PowerCardState.loading` 判电费段**：
  它只管读数那一段，流水是它之后才发的请求。
- **电费段有落盘种子（2026-09-26）**：进页链路 = 登录→项目详情→读表→流水 串行四条，冷启动
  手机网上要好几秒，此前这段时间电费段只能挂骨架（用户报「一打开一直是骨架屏，手动一刷新
  反而秒出」——刷新快是因为那时 token 已经热了）。上次成功流水落 `PowerHistoryCache`
  （filesDir JSON），`LifeViewModel` init 在 IO 线程读出先顶上、刷新到货原地替换；链路已
  跑完（含没开凭证的短路）就不用旧数据盖新的；空结果不落盘（宁旧勿空）。**电费段骨架只在
  真·首次启动出现**；种子只当首屏数据，§4.24 节流口径不变。
- **电费读数有首屏种子（2026-09-27）**：钱包卡的**电费栏**是页面上最后一处会从
  「—／读取中…」起步的地方（用户报「首次进入生活页电费余额还是很慢，手动下拉就秒出」，
  下拉快同样是 token 热）。种子不另建文件：读 Room `power_readings` 最新一条
  （`PowerReadingStore.latest()`）→ `PowerModels.snapshotSeedOf` 拼展示快照，`LifeViewModel`
  init 在 IO 线程填入。四条钉子：① **卡上已经有数就不填**（真读数、刷新失败保留的上次读数
  都不许被旧种子盖掉）；② 凭证没开直接跳过；③ 房号取读数的 `roomName`，**`roomId` 是平台
  数字 id（形如 `14600`），显示出来是一串数字**，只能当去重键与统计分组键；④ 单价未知
  （库里的 0）不折算，副行给「上次读数」。种子只是展示影子快照，不写回仓库缓存，
  `snapshot()` 的落库点仍只有一处。
- **取数失败的提示走 `NetworkHint`（2026-09-27）**：电费读数失败时，网络类异常
  （`PowerException.Network`）不再报「网络不给力」，改说下一步做什么——开着 VPN/代理
  就关掉它，没开就换一条网络（关掉校园网 Wi-Fi 用手机流量）。**卡副行只有一行、约十来个
  字宽，这里必须用 `NetworkHint.briefOf`**；账单页的提示块整行会折行，用 `of`（带原因）。
  凭证错、平台结构变化照旧报原文，别拿网络建议把真原因盖掉。
- **等数据的地方不许先渲染空态文案再跳**：骨架高度要跟成品一致，文字行高一律走
  `ui/common/TextMetrics.kt` 的 `lineHeightDp`（从样式换算，跟随系统字体缩放），
  不写死 dp。排队等数据的场景一律照这条来。
- **一处凭证**：电费登录 = 一卡通的学号 + 查询密码（`YktCredentialStore`，2026-09-23 实测
  两个平台同一密码）。一关了之：凭证清掉时电费卡同样显示「未开启凭证」。
- **读表参数缺一不可**：`feeitemid=181` / `type=IEC` / `level=3` / campus+building+room；
  少一个平台只回 `code=500「未知异常」`（HTTP 200），**业务码 401 也藏在 HTTP 200 里**，
  必须读 body 的 `code` 才能触发重登。
- **电费充值已 App 内完成**（2026-09-24 实测收口）：下单 = `POST /blade-pay/pay`
  `paystep=0`（`feeitemid=181 + tranamt + flag=choose`，签名 `PowerPaySign`）；
  支付 = `paystep=2 + paytype=ACCOUNT + paytypeid=59` 拿 `passwordMap` 乱序表，
  `PowerPayChallenge.cipherOf` 是密文换算唯一口径（用户数字 d → **d 在乱序表里的
  下标**；官方键盘第 i 个键显示 `table[i]` 但提交 `String(i)`，2026-09-24 读前端
  `app.7abec7aa…js` 修正——此前 `d → table[d]` 方向反了，正确密码也报「密码错误」），
  6 位消费密码 = **登录缴费平台用的那个 6 位密码**（2026-09-24 用户纠正：学校就一套
  6 位密码，不存在独立的「支付密码」）。测试单用完就 `deleteOrder`（JSON body），别留未支付单——堆积会让
  新下单 500。服务时间闸门等业务拒绝原样透传，不降级跳网页；深链 `#/pays?id=181`
  保留作兜底，无效路由会被前端打回首页，新增深链前先实测。
  三条一改就坏的钉子：**订单号只能用下单响应里那一个**（`paystep=2` 的 `orderid` 恒为
  null，别拿 `passwordMap` 的键——那是 uuid，发出去服务端回「订单不存在，请重新预定」）；
  **`ccctype[0].balance` 单位是元**（同一时刻一卡通 `accinfo[].balance=100` 分对照确认，
 按分渲染会把 1 元显示成 ¥0.01）；服务端拒绝含「订单不存在/已过期/已失效」时回金额步
 重新下单（`PowerPayModels.isOrderGone`），别让人在密码步反复重输。
- **电费充值的密码步没有输入框**（2026-09-24 用户拍板）：6 格点阵就是输入位，键盘仍是
  系统的（透明 `BasicTextField` 垫在点阵下面，点阵不拦触摸）。进步自动聚焦、失败自动清空、
  受理后自动收键盘。别把 `OutlinedTextField` 加回来——用户明确否掉了那个矩形框。
- **余额提醒**（2026-09-24，DESIGN §3.13「余额提醒」）：设置项在「我的 → 校园卡」页
  （寝室电费 10–80 元 / 一卡通余额 10–50 元，步长 5）。口径单一来源 `domain/BalanceAlert.kt`：
  电费「元」= 剩余电量 × 单价（**唯一换算处**，生活页电费卡也走它，别再内联乘一次）、
  阈值是**严格小于**、档位表/夹取/文案都在这里。调度在 `ui/reminder/BalanceAlertReminder.kt`：
  每天一次 + 冷启动补查 + 设置变更后立即评估，闸门是「上次**成功**检查日期」
  （同一天每个来源最多一条；**取数失败不落日期**，当天还能补查）。
  四条不许动：**失败不重试**（防撞风控）、一卡通只算**正式卡余额**（不含电子账户）、
  `balance_alert_periodic` / `BalanceAlertCheckWorker` 的名字（`KEEP` 下改名 = 每日兜底永久消失）、
  通知 id/tag 与落点（电费 1005 / 一卡通 1006，`EXTRA_ROUTE=ROUTE_LIFE` 落生活页；
  生活页开关关掉时不带 extra 落今日页）。关凭证时两个开关一并回落（都靠那份凭证）。
  **PendingIntent requestCode（3005/3006）与通知 id 分离**：骑行提醒的通知 id 也是
  1005/1006，两边落点 intent 都指向 MainActivity 无 action，filterEquals 相同——
  requestCode 撞了会被 `FLAG_UPDATE_CURRENT` 改写落点（2026-09-24 修的真 bug）。
  **给通知加落点前先全局 grep 现有 requestCode**（上课 0、作业 1002/1003、
  骑行 2000/2005/2006、余额 3005/3006），撞了就是静默的点击错页。
- 一卡通设置页的学号输入框**明文回填**已保存的学号（2026-09-24 用户拍板）；
  改回空框 = 不显示，会被用户当成「凭证丢了」，别当隐私优化删掉。
- **开启不再是一个 Switch**（2026-09-28 用户拍板，DESIGN §3.10）：填学号 + 查询密码后点
  「保存并开启」→ 先真实登录验证一次，成功才落库并置 `campusCardEnabled`；已开启后同一颗
  按钮变成「保存」（覆盖更新凭证），关闭走状态卡右侧的「关闭并清除」（二次确认后清凭证）。
  卡头只报状态、不带交互——别把 Switch 加回来。

## 电费流水的方向只看 tranamt 符号

- **电费流水的方向只看 `tranamt` 符号**（负 = 退款），金额存绝对值——`refund_flag` 在真实
  流水上**恒为 1**（2026-09-24 实测 11/11，含 50 元农行支付那几笔），拿它判退款会让整页充值
  显示成「退款」；平台 H5 自己也是 `tranamt > 0` 判充值（DESIGN §4.24「退款判据」）。
  用电量**没有平台接口**，靠本机读数表 `power_readings`（DB v11）差分：写入只有
  `PowerRepository.snapshot()` 一处、去重靠 `(epochMs, roomId)` 唯一索引，公式/均摊/跳过规则
  的唯一实现在 `domain/PowerUsage.kt`——别在 UI 或别处再算一遍，也别加后台轮询去采读数
  （第三方平台，记录密度就等于用户打开 App 的密度，DESIGN §3.13「用电统计」）。

## 消费流水页 / 缴费账单页（2026-09-26 重构）

- 统计图形**只有一份实现**：`ui/common/Charts.kt` 的 `AppBarChart`（柱状，柱子可点选中）与
  `AppRatioBar`（占比条）。三处调用 = 消费流水页「近 12 个月支出」、缴费账单页「近 12 个月充值」、
  用电统计「用电量（度）」。**别再各写一份自绘 Canvas**——此前三份的柱宽、透明度、轴标签都不一样。
- 月份导航同样共用：`ui/common/MonthPicker.kt` 的 `MonthNavRow` + `MonthPickerDialog`。
  窗口月份的唯一来源是 `StatementViewModel.windowMonths()`（流水页）与 `PowerBillUiState.monthKeys`
  （账单页）；UI 里别再按 `YearMonth.now()` 另算一份（两处各算一遍，跨月后弹窗会和柱子对不上）。
- 数值标签放在**标题行右侧**（`AppBarChart` 的 `selectedText`），不在柱顶：一屏 12 根柱、每格约
  24dp，柱顶浮标会被 Compose 裁成「¥2…」。改成柱顶前先量一下最小屏宽。
- 消费流水页的**类型筛选是本地过滤**当前月已加载的列表（`state.records`），不发请求、不改 DAO；
  整页只有一个 `LazyColumn`，汇总卡是它的第一个 item（别改回 `Column` 包列表，那样汇总卡会钉在顶部）。
- 缴费账单汇总口径 = **充值 / 净额 / 笔数**（净额 = 充值 − 退款），退款另起一行小注；
  别再把它并排成第三个指标。月份归属仍只看 `createdate`，**不要**改回 `feerange`。
- 账单与用电统计的明细行都可点：账单详情的「费用所属月」直接展示平台 `feerange` 原文
  （展示口径，与月份归属那条计算口径是两回事，别合并）。
