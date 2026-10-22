# 共享单车 · 地图 · 免费时长提醒

作用域：改快趣出行出码、附近车辆地图、免费时长提醒时读（「精确倒计时」已删，见同节墓碑）。规格见 DESIGN §3.9 / §4.23 / §4.32。账号登录 / 用车 / 卡券 / 设置见 `ebike-account.md`。

## 使用方式：小程序 / 账号两档（2026-09-29）

- 一个显式设置把两套能力隔开：`domain/EbikeUseMode.kt`（纯 JVM 可测）+ DataStore 键
  `ebike_use_mode`（**默认 `MiniProgram`**）。**能力的唯一判据是 `EbikeUseMode.capabilities()`
  算出的 `EbikeCapabilities`**（同文件：`wechatScan` / `inAppRide` /
  `directUnlock`），页面只消费那几个布尔量，**不要在任何地方再写一份 `if`**
  （矩阵本身由 `EbikeUseModeTest` 钉住）。切换入口 = 出码页的「使用方式」卡（`UseModeSection`，
  摆在出码卡正下方）+ 快趣出行账号页顶部（**同一份组件**，两处都能切）。

  | 能力 | 小程序方式 | 账号登录方式 |
  |------|-----------|--------------|
  | 车号输入 / 生成二维码 / 保存到相册 | ✅ | ✅ |
  | 打开微信扫一扫（顺带起免费计时） | ✅ | ❌ |
  | 直接开锁 / 临时锁车 / 还车 | ❌ | ✅（需登录） |
  | 快趣账号页 / 本机骑行记录 | ❌ | ✅ |
  | 地图：附近车辆（含校园围栏） | ✅ | ✅ |
  | 地图：还车点 / 禁停区图层 | ✅（接口不需要凭证） | ✅ |
  | 地图：车行「开锁」与「当前用车」卡 | ❌ | ✅ |

- **默认小程序方式**：账号方式要凭证、有计费后果、还有支付分授权门槛，只能显式选择；
  认不出的存储值一律退回这一档（`EbikeUseMode.fromId`）。
- **一处收口**：出码页与地图页都写 `val ride = if (caps.inAppRide) kvcx.ride else null`——
  切换使用方式后内存里残留的快趣订单不该在另一档冒出来（还车结果卡同样按能力 gate）。
- **查询也要跟着停**：`EbikeViewModel.queryKvcxRideQuietly` / `BikeMapViewModel.queryRideQuietly` /
  `KvcxViewModel.refreshRide` 都先判能力，且**读 DataStore 原始流**（不读 UI 快照——
  快照首帧是默认档，冷启动会把账号方式误判成小程序方式，与 `autoSave` 那个坑同源）。
  小程序方式不查订单：没人看的查询不该打扰第三方接口。
- **首帧真值**：`EbikeViewModel.initialUseMode` / `BikeMapViewModel.useMode` 的初值
  **阻塞读一次**（同 `MeViewModel.initialPrefs` 的模式）。模式决定首帧露出哪一套按钮，
  初值给错会先按另一档画一帧再翻过来——那是看得见的闪。
- 「校园服务 → 快趣出行」入口按模式显隐（小程序方式不展示账号页，切回去入口自然回来）；
  账号页在小程序方式下把账号 / 骑行状态 / 本机记录整片收起，只留切换开关与一句说明。
- 免责文案随模式换口径：小程序方式写明「只出码与计时，不代你开锁、不触碰计费与订单」。
- **一个页面，三种状态**（2026-09-29 合并出码页与地图页；**2026-09-30 二次重构**；
  **2026-10-01 收口**，用户口径「固定在弹窗底部、列表默认展开、面板可拖、变化别突变」）：
  **出码页与地图页合并成「骑行」页**（`ui/ebike/RideScreen.kt`，`SubpageScreen.RIDE`；
  旧 `EBIKE` / `EBIKE_MAP` 两个枚举名在 `SubpageStack.LEGACY_NAMES` 里映射到它——
  旧版本排下的通知 PendingIntent 升级后触发时不能落到「课表管理」）。
  **两档共用同一套结构**：「两档各有一套页面布局」这条口径**作废**，模式只决定主动作是什么。
  - 布局（2026-10-01）：**地图 → 可拖车辆面板 → 页面底部常驻块**三层。面板只装列表
    （`RideBikePanel`：把手 + 头行 + 列表）；**主动作与免责那行在面板之外的常驻块里**
    （`RideActionArea` + 一行 labelSmall）。动作区原来在面板的 `footer` 槽里，而面板高度是
    定值、动作区是变量：骑行态的仪表盘一长，最矮档位的把手 + 仪表盘 + 免责就超过面板高度，
    底部按钮被面板圆角裁掉（真机可复现）。**别把它塞回面板**——要新形态就往动作区里加，
    面板的高度只归列表。
  - 面板高度 = `BikeMapUiState.panelHeightDp`（`DEFAULT_PANEL_HEIGHT_DP` **270dp** /
    `MIN` **240** / `MAX` 620），**可拖**（`PanelDragHandle`）、**默认就展开着**
    （用户 2026-09-30 口径：「附近车辆跟原来一样默认展开」）——别再退回"点一下才看得到列表"。
    上限 = `min(MAX_PANEL_HEIGHT_DP, 窗口高度 − BOTTOM_RESERVE_DP)`，**`BOTTOM_RESERVE_DP`
    是固定常数、不吃底部块实测高度**（2026-10-14 修用户报的「车辆信息卡片出现 / 消失时
    弹窗跳动」）：旧口径是 `窗口*0.72 − 块实测高度`（已删的 `PANEL_MAX_RATIO`），块高随
    车辆卡 / 骑行卡进出而变 → 上限跟着变 → `clampPanelHeight` 把面板夹小且**不还原** →
    面板顶边先随块升起再跳回去。**别把块实测高度加回上限**。`BOTTOM_RESERVE_DP` 的取值
    口径（最高档底部块 + 最小地图可见高度）写在 `RideScreen` 那段注释里；改它要重新量。
    导航栏内边距归常驻块吃，面板自己不再垫（旧版两处都垫）。
  - 三态由数据推导（`phase`），不另存可变状态：`Settled`（有还车结果）→ `Riding`
    （有订单或在案计时）→ `Finding`。「选中某辆车」「选中某停车点」**不在三态里**，
    它们是**动作区**的形态（`pickedCar` / `state.spotKey`）。
  - **动作区分两层，动画只作用在上层**（2026-09-30 用户口径「做成底部窗口升起的过渡动画，
    没有组件跳变」）：
    - **上区**（`RideActionUpper`）= 状态相关的额外内容（车辆卡的头行与详情、骑行指标、
      结算明细）。换状态时**直接竖直升起**（2026-10-08 用户口径「从右下角升起有点丑，
      直接上升就行」）：进 = 全高 `slideInVertically { it }` + 快速淡入（[BAR_FADE_IN_MS]）；
      出 = **原地淡出**（[BAR_FADE_OUT_MS] 120ms），不再向下沉——旧版进出两块反向滑动叠上
      容器长高（`SizeTransform` 240ms），合成的运动轨迹发斜、看着像"从右下角冒出来"。
      **别把退场改回滑动**；容器高度动画两个方向都是 `BAR_RISE_MS` 240。
      **车辆卡的 key 带车号**（`car:100000398`）：换一辆车时上区要重走一遍升起动画，
      不然只有文字原地换掉。车号**从 key 里取**（`carNumOf`），别读外层那个 `pickedCar`
      ——退场那一份读的是最新值，会跟着变成新车、两层画同一张卡。
    - **下区**（`RideActionButtons`）= 主动作，**常驻、不参与上面的动画**。找车态与车辆卡里
      它是同一枚按钮、同一个位置，所以换状态时看不见它跳；按钮内部的图标与文字用一层
      `AnimatedContent` 交叉淡入（`RideActionButton`：整行 46dp 胶囊，主次只差实心 / 描边；
      旧的 `RidePrimaryButton` 已并进它）。
    **加新形态时把内容分进这两层**，别把主动作塞回上区——塞回去就又开始跳了。
    **退场那一份会继续组合 120ms（`BAR_FADE_OUT_MS`，原地淡出）**，它读的是外层参数的**最新值**：还车时 `ride` 变 null、
    点「继续找车」时 `summary` 变 null、结束计时时 `timerActive` 变 false，退场路上那半秒
    就画不出东西（看着是"内容先没了、区域再收回去"）。所以 `RideActionArea` 里按 key
    留了一份 `RideUpperStash`（普通持有者、不驱动重组）：
    **当前档（key == barKey）逐轮记账「最近一次实时值」，退场那份只读**——悬置位/计时
    当前档一律实时值（重试成功、到点撤卡要立刻消解）。**别退回"入场快照"**——
    `remember(key)` 只记首帧，会让「已骑」回跳、悬置色复活（2026-10-08 复核修）；
    别的字段（更新时刻、筛选开关）照旧读实时值，冻住会把走时冻掉。
    面板内容在「车辆列表 ↔ 还车点」之间走 `Crossfade`，二维码面板与生成按钮的主次切换
    也各有一层淡入。**列表卡片换批走 `AnimatedCardList`**（2026-10-19 用户口径「卡片消失
    和出现要有动画」）：判据是**卡片身份集合**（簇键 / 还车点坐标 + 点位名），只是距离数字
    变不播过渡、原地更新；换一片区域或车没了/多出来才淡入淡出 + `SizeTransform` 高度过渡
    （`BAR_FADE_IN_MS` / `BAR_FALL_MS` / `BAR_RISE_MS`，与动作区同族）。**别退回
    `forEach` 直接摆卡**——那是整列硬切、卡片凭空出现/消失的来路。
    **动作区的 key 要把找车态的四种排版都分开**（`BAR_FINDING_PLAIN` / `_CODE` / `_LOGIN` /
    `_SCAN`）：出过码之后主动作会从「按车号生成乘车码」翻成「打开微信扫一扫」，只按 `phase`
    分就会在 `when` 里硬切。面板里的按钮**不要直接改 `showXxx = false`**——那会把弹层从组合里
    瞬间抽掉、退场来不及播；含输入框的走 `ImeAwareModalBottomSheet` 的 `pendingDismiss`，
    其余的走 `rememberSheetDismisser`（口径见 `ui-common.md`）。
  - **列表的参照点在查询落地时定一次**（`BikeMapUiState.anchorLat` / `anchorFromUser`，
    2026-09-30）：查询中心离用户 ≤ `USER_ANCHOR_RADIUS_METERS`（150 米）→ 用用户位置
    （标「距你」）；否则用查询中心（标「距中心」）。**距离数字与排序必须同源**，旧版数字
    按「距你」、排序按查询中心，拖远之后顺序和地图对不上。`onUserLocationChanged`
    **不许再重设参照点**——蓝点每动一下就把整张列表重排，用户站着不动也会看到顺序在变。
  - **一个停车点是一张卡**（`RideClusterCard`，2026-09-30 用户口径「好丑」）：头行 + 发丝线 +
    车辆行（`RideBikeRow`，缩进 42dp 对齐停车点名那一列）全在同一张 `AppCard` 里，
    `contentPadding = 0` 自己排。旧版"头一张卡、每辆车各一张卡"会摞出一屏描边圆角。
    **行点击的触感由行自己发**（`rememberAppHaptics().tap()`），页面回调里**不要**再调一次
    ——`AppCard` / `AppCardRow` 内部已经统一触发，双份就是两下震动。
  - **车号输入在「车号 / 生成乘车码」面板里**（`RideCarNumberSheet`，
    `ImeAwareModalBottomSheet`——含输入框就必须走它，见 `ui-common.md`）：
    输入、最近车号、码、用码动作在同一个容器里走完。最近车号**只留一个**，摆在输入框
    **内部右侧**（`RideCarField` 的 `lastRecent`，2026-09-30 用户口径）——它就是一个
    "再出一张"的快捷方式，不占一行、不列一排，`clearRecent()` 随之删除。
    **码区那一段两档共用一份**（未出码时是常占位空框 + 常显置灰的保存 / 去微信两枚按钮）：
    旧版按档复制了两份，小程序方式是"常占位 + 置灰"、账号方式是整块条件渲染，同一个位置
    两种长相。内容**可滚**（240dp 码区 + 输入框，小屏本来就放不下），别照抄充值弹层
    「内容固定不可滚」那条——那边的问题是滚到底滚不到位。
  - **点地图上的数字 = 展开列表里对应的那张停车点卡 + 把列表滚过去**
    （`BikeMapViewModel.onClusterTap`，与点列表里的分组头**同一个入口**）：标记同时高亮
    （`OsmMapView.selectedKey = expandedKey`）。**不要再为它另弹一份卡片**——
    2026-09-30 用户口径「点击地图上的车弹出的弹窗重复了」：停车点卡和列表说的是同一件事，
    同一批车只在一处出现。`spotKey` / `RideSpotBar` / `onSpotTap` 那一套已删除。
    **滚动要等目标卡的位置落定再启程**（`RidePanels.awaitSettledOffset`，2026-10-01 修
    用户报的「点了地图上单车的数字圆圈，列表有时候不会滚到对应地点卡片」）：展开目标卡的
    同时**上一张展开的卡正在收起**（190ms 高度动画），目标卡一路往上走，按当帧记下的位置
    直接滚就是滚过头——卡片落到视口上方，用户看到的是"列表滚了、要找的卡没了"。旧版
    LazyColumn 的 `animateScrollToItem` 内部会分趟逼近移动中的目标，换成手写偏移后没有那层
    保护。**别退回"读一次 `clusterOffsets` 就 `animateScrollTo`"**，也别改成写死 delay
    （没在动的时候白等）；等落定的**上限按时间封顶、不按帧数**——帧数与刷新率挂钩，同样
    20 帧在 60Hz 是 333ms、在 120Hz 只有 167ms，后者会赶在收起动画之前启程（真机 120Hz）。
  - **小程序方式：只有出过码才给「打开微信扫一扫」**（2026-09-30 用户口径
    「没有码跳转什么」）。没有码时主动作是「按车号生成乘车码」；码出来之后主动作换成
    「打开微信扫一扫」。**别再无条件摆一枚跳微信的按钮**——
    车就在旁边的话，用户自己开微信扫车身上的码即可，不需要经过这个按钮。
  - **「按车号」在面板头行**（`RideTextAction` + `HugeIcons.Keyboard`，带文字的一枚，
    2026-10-01）：地图上不再挂浮动图标（旧版是左下角一枚没文字的圆钮，压在面板上沿、
    跟拖动把手抢位置）。显隐由 `showCarNumberEntry` 决定：**账号方式常驻；小程序方式
    只在主动作不是它的时候出现**（没出码时主动作本身就是「按车号生成乘车码」，再摆一枚
    就是 2026-09-30 用户说的"重复的图标"）。动作区因此在找车态恒为一枚按钮、没有第二行，
    四种形态高度一致。
  - **车辆行右侧直接给生成乘车码 / 开启**（`RideBikeRow` 行尾那枚）：行的信息（车号 /
    状态 / 电量 / 距离）和车辆卡里那份差不多，没必要非得先点开卡片才能出码。账号方式已登录给
    「开启」（**两段式**，2026-10-21——创建订单、车保持已锁，解锁在骑行卡上点），其余给
    「生成乘车码」。行尾的 chevron 因此删了。
  - **车辆卡里的次级入口（账号方式的「生成乘车码」）是一枚右对齐的小按钮**，
    摆在主动作上方（`RideCarUpper`）：**别做成与主动作同宽的描边按钮**——2026-09-30
    用户口径「太占高度了」。也**别挪到主动作下方**（那会把主动作顶上去，"主动作常驻不动"
    就不成立了）。
  - **弹层的把手用 M3 默认那根**（`dragHandle` 不传）：2026-10-01 起与本页其余弹层、
    与全项目一致。旧版 `dragHandle = null` + 自己垫 12dp 顶距只在骑行页这么干，同页的
    设置弹层还留着把手——同一屏两种头部做法比那点高度显眼。要改就整项目一起改。
    弹层内容顶距交给把手自带的那一段，自己不再垫。
  - 账号方式：已登录主动作是**「扫车身码」**（内置相机）；未登录主动作只能是「登录快趣账号」。
    **「扫车身码」这个说法只属于账号方式**——那是 App 自己的相机，小程序方式没有内置相机。
  - 骑行态**按档分流**（2026-10-01）：
    - 判据只有一条（2026-10-01 第二次修订后**两档同规则**）：
      `showSpots = phase == Riding && state.zones.parkSpots.isNotEmpty()`。
      `parkSpots` 非空 → 地图改画还车点（附近车辆让位）、列表换成还车点（`RideSpotsContent`）；
      为空 → 地图与列表**一起留在车上**。图层接口不需要凭证，小程序方式也拉得到（见上节），
      所以**别再加 `caps.inAppRide` 这个条件**；也**别按 `phase` 一刀切**——那样这一带没有
      还车点数据时会给用户"空地图 + 一屏没有数据"（点完「打开微信扫一扫」回来就像走错页，
      旧版就是这么被报的）。
    - 动作区在骑行态是仪表盘；还车点列表在下面那块面板里。
  - **动作按钮一个规格**（`RideActionButton`）：整行 46dp 胶囊，实心 / 描边分主次；
    密集图标按钮 40dp（`IconButtonSmall`、`RideFilterButton`），弹层关闭 48dp
    （M3 默认，别再写 `Modifier.size(32.dp)` 压小）。旧版一屏里有 46 / 40 / 48 三种按钮高度，
    换状态就换字号，看着像没做完。
  - **Snackbar 抬到底部常驻块之上**（`AppSnackbarHost(snackbar, Modifier.padding(bottom = bottomBlockDp.dp))`）：
    它默认贴窗口最底，正好压住主动作——骑行态的提示一来就看不见「还车」。
  - 结算：**动作区里的结算卡**（`RideSettledBar`），不弹 AlertDialog——还完车常常要接着
    骑下一辆，弹窗把流程打断；卡上给「继续找车」这条出路，以及**「重新使用该车」**
    （2026-10-10，对刚还的车重发开锁；见「还车结果卡」一节）。
  - 术语统一：出码 → **生成乘车码**；直接开锁 / 临时锁车 / 解锁继续骑 → **开锁 / 锁车 / 解锁**；
    结束骑行 → **结束计时**；页面与今日页入口卡都叫**「骑行」**（今日页右侧「附近单车 ›」
    二级入口取消——地图就是那个页面的主体）。
- **切换入口是标题栏常驻 chip**（`RideModeChip` + `RideModeSheet`，2026-09-29 结构重构）：
  原来藏在设置弹层里——用户在一个看不见当前状态的地方做决定、切完整页变形；
  现在一眼看得见自己在哪一档、一次点击可换，切换后 Snackbar 说明主动作变成什么。
  **骑行中禁用**（chip 变灰 + 点它只给一句说明）：切换会让在案订单从界面消失，
  那是最容易让人以为「车丢了 / 钱没了」的一刻。
- 开锁前的支付分授权提示在出码卡里常驻一句（未命中 11035 时），命中后换成实打实的
  「去微信扫一扫」出路；`kvcxConfirmDialog` 的 UNLOCK 文案里同样写了这两句
  （`KvcxRideControllerTest` 钉住「微信支付分」「客服」两个关键词）。

## 快趣出行跳转已删（2026-09-30）

- 「打开官方快趣出行 App」按用户要求**整体删除**，别当漏项加回来：设置弹层的「其他」区与
  入口、`EbikeActions.openKvcoo` / `KVCOO_PACKAGE`、manifest `queries` 的 `com.kvcoo.go`
  包可见性声明都没了（声明当时是为 `getLaunchIntentForPackage` 的包可见性留的，入口没了
  它也没用了）。官方 App 不再是任何流程的必经步骤，要用的人自己去桌面打开；
  本页地图已承担「看车在哪」。快趣 API 域名 `api.kvcoogo.com` 与车身码链接
  `www.kvcoogo.com` 是另一回事，照旧。
- 更早删掉的（2026-09-23）：「助手通道」（改写系统 `Settings.Secure.assistant` + 反射
  `launchAssist` 直达未导出的首页）连同 `WRITE_SECURE_SETTINGS` / `KILL_BACKGROUND_PROCESSES`
  权限与未安装下载引导弹窗——理由同上。**别把助手通道当漏项加回来**。

## 附近单车地图

- 附近单车地图（DESIGN §3.9/§4.23）的坐标基准是 **GCJ-02**，与高德栅格瓦片同一基准：
  车辆坐标**直接画，不要转换**；唯一要转的是手机定位（WGS84 → GCJ-02），
  唯一实现在 `domain/Gcj02.kt`，漏转或多转一次都会偏出约 500 米。
  刷新只由用户动作驱动（进页 / 拖动停稳 / 点刷新 / 定位成功），**不要加后台轮询**
  ——那是第三方接口，不是自家的。**车辆列表**只留内存不落盘（车随时被骑走）；
  **停车点 / 禁停区图层**从 2026-09-28 起有落盘缓存（见「还车点 / 禁停区图层」节末），
  瓦片由 osmdroid 自缓存——两块的清除见同节的「地图缓存」条。
  **「停手」判据 = 手指松开 + 地图中心连续 120ms 没动**（`SettleWatcher`，2026-09-29）：
  ① **别看事件间隔**——惯性滑动时渲染重、帧率低到 5~10fps，事件间隔会超过任何防抖阈值，
  实测一次 swipe 连发 **7** 次查询（费流量 + 列表在地图还在滑时反复重排 = 卡）；改成
  **轮询 `mapView.mapCenter`**，滑行时中心一直在变、与事件密不密无关。
  ② **`scroller.isFinished` 不能用**——实测拖完之后它**一直是 false**（44 轮没变），
  拿它当闸门一个查询都发不出去（这条是踩过的坑，别照直觉改回去）。
  ③ **手指还按着不算停手**（慢拖时中心几百毫秒只挪几米），靠只读的 `TouchTracker` overlay
  看 `ACTION_DOWN/UP`；它 `onTouchEvent` 返回 false，不拦地图手势。
  ViewModel 侧只剩 80ms 合并窗口（`QUERY_CONFIRM_MS`）+ 30 米位移闸门，**别在那里再写一套
  停手判定**（两处判据会互相打架）。官方小程序是 drag-end 零延迟发，这 120ms 是那点差距。
  瓦片下载线程 8（`OsmMapView` 里 `setTileDownloadThreads`；osmdroid 默认 2）：拖到没缓存过的
  地方时一屏十几块瓦片要一次下完，这是「地图半天不出来」的主要来源。
**刷新范围**（2026-09-29 用户口径「太多了有点卡」）：采样环 450 米、展示上限 1200 米，
叠加层还有屏幕外剔除（48dp 余量）——**别把这三个数随手放大**，那会同时加重列表与每帧绘制。
**刷新成本第二刀**（2026-10-01 用户口径「减少地图刷新卡顿」）四条，各有各的代价，
改之前先看清这条挡的是哪一层：

- **解析与聚簇必须留在 `Dispatchers.Default`**：`query` / `sampleAt` 里的 `BikeNearby.parse`
  与 `applyResult`（含 `rebuildClusters`）都包了 `withContext`。响应回来后的解析原本跑在
  主线程（`viewModelScope` 是 Main），一次刷新几份 payload 正好顶在拖动停手那一帧上。
  **别为了"看着干净"把 withContext 去掉**；往这几个函数里加新的重活（排序、过滤、字符串）
  时也留在同一个块里。
- **采样点数按缩放**（`ringSampleCount`，`BikeNearby.samplePoints(lat, lng, ringCount)`）：
  z ≥ `RING_SAMPLE_ZOOM`（16.5）取 **4** 个对角点，更小取 8 个方位；请求数 9 → 5。
  **别改成 0**：z17 在纬度 28.7° 下的视野约 1.1×1.7 公里，比采样环还大，
  只查中心会让地图上只剩中心一小片有车。`samplePoints` 内部把点数夹在 4~8。
- **列表与地图的簇封顶 `MAX_CLUSTER_COUNT`（24）**：超出部分在列表尾部给一行
  「还有 N 处更远」。**头部的车辆总数 `bikeCount` 走未封顶的那一份**——它是
  `BikeMapUiState` 里的**存储字段**（不再是 `clusters.sumOf{}` 的 getter），
  所以封顶不会把「附近 138 辆」裁成「95 辆」。改这个上限时两边一起看。
- **列表项有 `key(cluster.key)`**：撒点合并落地会换一批簇，有 key 才按节点移动而不是整列
  按位置重组。**别把它删了**（删了列表在第二次落地时会整体抖一下）。
- 每帧那边的两条：图层先过**经纬度包围盒**（`ZONE_CULL_PADDING_DEG`）再建路径
  （接口固定回 15 个还车点，视野里通常只有三五个）；投影复用同一个 `tmpGeo`，
  不要回到"每簇每帧 new 一个 GeoPoint"。
- **叠加层只在输入真变了才重画**（2026-10-01 用户报「点确认开锁后卡」时定位到这条）：
  `AndroidView` 的 `update` 块**每次外层重组都会跑**（update 的 lambda 每轮新实例，
  跳不过去），历史上它收尾无条件 `view.invalidate()` —— 于是页面上任何与地图无关的状态变化
  （开确认弹窗、按钮 busy、Snackbar、偏好变化）都会整幅重画地图，正好撞在写操作那两帧上。
  现在 update 里先比对 `MapOverlayInputs`（colors / clusters / selectedKey / 三个点 /
  highlightLabel / zones，全是数据类，比较是值比较），**相等就跳过赋值与 invalidate**；
  回调（`onClusterTap` / `onRideTap`）不进比对，每轮照写——它们是闭包，换了不需要重画。
  **别退回无条件 invalidate**；加新的绘制输入时记得一并进 `MapOverlayInputs`，
  漏进去就是"数据变了画面不动"的静默 bug。记账用的 `DrawnOverlayInputs` 是普通持有者，
  **别换成 `mutableStateOf`**（update 跑在 apply 阶段，写状态会再触发一轮重组）。
- **地图让位 = 恒定尺寸 + 整幅平移**（2026-10-11 起，2026-10-14 收口；用户报的
  「车辆卡弹出时地图突变」「拖面板时地图游」「车辆信息卡片进出时弹窗跳动」都是这一族）：
  地图视图**尺寸恒定**（= 整个可用区，`fillMaxSize`，**不带** `padding(bottom = panelHeight)`），
  让位靠 `graphicsLayer.translationY = -(panelHeight + liveBlockDp) / 2`——可见区 = 地图层
  减去被面板 + 底部块盖住的那段，把地图按这段的一半上移，视图中心就落回可见区中心
  （相机中心语义不变）。上半截平移到窗口外的部分由外层 `clipToBounds` 裁掉。
  **为什么尺寸必须恒定**：osmdroid 每次 resize 都重投影、把整幅内容平移 delta/2，视图尺寸
  跟着面板高度走的话，面板一变（拖动 / 夹取）地图就跳一次；平移只是图层变换，不重投影、
  不重绘瓦片。**别把 `padding(bottom = panelHeight)` 加回来**，也别改成逐帧 resize
  （那是 10-01 修掉的掉帧来源）。
  配套件别拆：① 页面结构是 `Box { 地图（恒定尺寸 + 平移）; 贴底的 Column（面板 + 常驻块，
  叠在地图之上）}`——不是 `Column` 里排；② 常驻块高度 `liveBlockDp` 由 `onGloballyPositioned`
  实测（含导航栏内边距），只喂地图平移与 Snackbar 抬升两处（读点各在自己的作用域里，
  每帧写不贵），**不再喂面板上限**；③ 面板上限走固定预留 `BOTTOM_RESERVE_DP`（见上），
  旧版的 `mapReserveDp` / `measuredBlockDp` / `blockAnimating` / `barBlockShrinks` /
  `barHeightTier` / `PANEL_MAX_RATIO` 整套已删，**别当漏项加回来**。
- 量法：debug 下 `BikeMapQuery` 打两行（首屏落地 / 合并落地，含条数与环点数）；
  `adb shell dumpsys gfxinfo <包名> framestats` 看拖动时段的掉帧。
  **展示上限 1200 米这次没收**：采样环只有 450 米，捞回来的车最远六百多米，
  它实际过滤不掉几条，真正管渲染量的是上面的簇封顶——要再压列表长度就动采样环半径，别动它。

  定位只走平台 `LocationManager`（`ui/ebike/BikeLocator.kt`），不引 Play Services 融合定位。
  `LocationListener` 的四个回调都要写全：少写一个在 26~29 的设备上是 `AbstractMethodError`，
  编译期看不出来。
  两条容易改坏的口径：**距离的参照点**（有定位按用户位置、否则按地图中心，而且 UI 必须
  把参照点写出来，别让「473 米」在拖动后悄悄换意思）；**镜头静默区**
  （`CameraSuppressor` 吃掉程序性移动 30 米内的中心回调，否则「点分组→移动地图」会马上
  触发一次重查，把用户刚展开的列表换掉）。改这两处前先看 DESIGN §3.9 的对应行。
  车号回传只有一条通道：地图页 `setResult(EXTRA_PICKED_CAR_NUM)`，**别改回进程级单例**
  （单例会被任何一个还活着的出码页实例抢走，用户眼前那页空手而归）。
  面板高度存在 `BikeMapUiState.panelHeightDp`（VM 状态，不是页面局部 `remember`），拖动回调传增量。
  这两条的理由都写在 DESIGN §3.9 的表里。
  **刷新按钮的 loading 占位必须继续吃触摸**（2026-10-18 修「连点刷新会双击放大地图」）：
  `RideBikePanel` 头行在 `state.loading` 时把按钮换成转圈，那个 40dp 槽位要挂
  `pointerInput { detectTapGestures(...) }` 吞掉单/双击；否则第二下会穿到下面的 osmdroid
  地图，被当成双击缩放。`IconButtonSmall(enabled=false)` 同理也要吞触摸。
  两种形态外面套一层 `AnimatedContent` 交叉淡入（2026-10-19），外层固定 40dp——
  加载一次头行别闪。
  **写动作开始先取消地图查询**（`BikeMapViewModel.cancelQueriesForRideAction`）：开锁 / 锁车 /
  还车共用连接池，高峰期不要让还在飞的附近车辆/图层请求排在写请求前面。
  蓝点实时更新 + 镜头跟随（2026-09-26 / **2026-10-19 加跟随**）：页面可见期间（`repeatOnLifecycle(STARTED)`）
  挂 `BikeLocator.updates` 连续定位流，走 `onUserLocationChanged`——挪蓝点与「距你」距离；
  **跟随态（`BikeMapUiState.followUser`）下位移 ≥ `FOLLOW_RECENTER_METERS`（8 米）就把镜头
  平移过去**（`pushCamera(animated = true)`），**仍然不重查车辆接口**——程序性移动被
  `CameraSuppressor` 吃掉、走不到拖动停手那条查询，接口禁止轮询的红线不变。用户口径
  （2026-10-19）：从一个地方开锁骑到另一个地方，地图不能还停在开锁那一刻的位置。
  打开跟随 = 进页自动定位 / 点「定位」/ **人就在车边时开始骑行**（`RideScreen` 的 phase
  effect 调 `startFollowing`）；关闭 = **用户单指拖动地图**（`TouchTracker` 过系统 touch slop；
  双指缩放不算、点地图不算）、点「回到校区」、点停车点 / 车辆定位 / 按车号找车——
  那些动作表达的是"我现在要看别处"。**远程开锁不打开跟随**（2026-10-19 用户报「点了地图上
  很远的车开锁，视角却回到我的位置」）：开锁时按 `rideBikePosition` 判人车距离，超过
  `RIDE_NEAR_USER_METERS`（100 米）就不跟随、并 `stopFollowing`，镜头留在车上；之后人走到
  车边（距离进入 100 米）才在 `rideCameraFollowArmed` 的 false → true 转折点重新打开。
  小程序方式只有计时、没有本机订单，无从判距离，照旧跟随。View 层先 `stopAnimation` 再回调 `onUserPan`，
  别让跟随的缓动和手指抢。地图右上那枚定位按钮在跟随态走主色底（`MapCircleButton.active`），
  用户一眼知道镜头正跟着自己。挪点口径 `shouldMoveDot`（**明显更粗的
  读数一律不认** + 位移 ≥4 米 + 原地没动但精度显著提升）是防双源交替与站定抖动的，**别删**；
  请求节奏 2 秒一次，别改回 0（费电不讨好）。**「明显更粗不认」是 2026-10-13 修「定位乱跳」加的**
  （`DOT_COARSER_RATIO` 2.5）：网络点在楼群里能偏出几十上百米、位移远超 4 米，只靠位移闸门拦不住，
  蓝点会在准的 GPS 点与粗的网络点之间来回跳；两者都粗（只有网络可用）时这条不触发，照常跟随。
  **`onUserLocationChanged` 里不许再调 `rebuildClusters()`**（2026-10-13 删）：簇的距离用查询落地
  时定下的参照点（`anchorLat ?: fetchedLat`），与蓝点实时位置无关——重算结果逐字段相同，纯浪费
  （还在主线程、走动时每 2 秒一次）。停车点距离读的是 `state.userLat`，不经过簇，照常跟着蓝点走。
  **蓝点还画精度圈**（2026-10-13）：`LocateResult.Ok.accuracyMeters` 从 `BikeLocator` 透传到
  `BikeMapUiState.userAccuracyMeters`，`BikeMarkerOverlay.drawUserAccuracy` 按当前投影把米折成屏幕
  半径画淡圆（半径靠投影两个点现算，不引 osdroid 米/像素换算）；0 / 半径不大于圆点时不画。
  **中心针恒显**（2026-10-19 用户口径「移除重合时消失的特性，改成一直显示」）：它表示
  "查询中心"，与"我在哪"是两件事，蓝点落在中心时两枚都画。**别恢复
  `CENTER_PIN_HIDE_RADIUS_DP` 那条藏针逻辑**。
  **定位点 / 中心浮标走官方小程序样式**（2026-10-19，用户给了官方截图）：用户点 =
  **绿色实心圆**（`#55B837`，5dp）+ 1dp 白描边 + **11dp 淡绿平铺光晕**（`#63C46B` @30%），
  不再是主题色径向渐变；中心浮标 = **黑色圆头**（9dp）+ 中央薄荷绿点（3dp，`#63D099`）+
  0.8dp 黑色短杆（17dp），**没有白色水滴描边**；针尖仍落在视图中心。色值是官方截图取样，
  **不跟主题走**（地图瓦片始终浅色）。改尺寸 / 色值前先看 DESIGN §3.9「定位」「地图中心针」。
  蓝点朝向（2026-10-08 建链；**2026-10-19 二次改形态：实心小箭头**，用户对照官方截图）：
  同一段生命周期里并挂 `BikeCompass.heading`（`ui/ebike/BikeCompass.kt`）罗盘流，只走
  `onUserHeadingChanged`——只转蓝点本体上的箭头，同样不移镜头、不重查。
  形态沿革：**饼形锥（两层）→ 光晕外沿弧段（11→14dp 环弧，10-19 上午）→ 实心小三角
  （`drawHeadingArrow`，10-19 用户口径「参考官方小程序，是一个实心的小箭头」）**。箭头 =
  8dp 尖端 / 3.5dp 底边 / 3dp 半宽的实心三角（用 `userDot` 绿），底边压在圆点内部、由圆点的
  白描边盖住接缝，绘制顺序 光晕 → 箭头 → 圆点 + 白描边。**别改回饼形锥，也别改回环弧**
  ——两版都因为"在绿点圆底上染色 / 形状不像官方"被否。
  语义是**罗盘朝向**（手机顶边指北偏角，站定也显示），不是 GPS 行进方向。四条硬口径：
  ① 传感器读数**必须节流**（流内已做：低通 0.15 + 变化 >2° 且 ≥100ms 才发，≈≤10Hz）——
  heading 的终点是 `MapOverlayInputs` 值比对 + 一次整幅重绘，高频直通就是复刻 10-01 掉帧；
  ② `userHeading` **必须进 `MapOverlayInputs`**（新绘制输入进比对是红线）；
  ③ **朝向走独立流 `BikeMapViewModel.userHeading`，不进 `BikeMapUiState`**（2026-10-13）：
  `RideScreen` 整页读 `uiState`，朝向挂在状态对象上会让 ≤10Hz 的跳动每下都重组整页（含车辆列表）；
  现在由 `OsmMapView` 自己收（`userHeadingFlow`），重组范围收在地图层；
  ④ 屏幕旋转档取 `DisplayManager.getDisplay(Display.DEFAULT_DISPLAY)`——
  **别改成 `SensorManager.getDisplay`**（API 30 起，minSdk 26 直接 NoSuchMethodError）。
  remap 轴向映射按官方示例口径（ROTATION_0 → AXIS_X/AXIS_Y …），`remapAxes` 有单测钉住；
  设备无磁力计 → 空流，蓝点退回无方向圆点，调用方零降级分支。
  启动姿势与二级页恢复见 `nav-window.md`（`MainActivity` 是 `standard`，不是 `singleTask`）。
落状态分两步（2026-09-27）：**中心点结果先落列表**，撒点在飞时 `loading` 保持 true
（头部进度圈就是「还在补全周围」的提示），采样合并回来再刷一遍。**别改回「`awaitAll`
之后一次性落」**——那会让用户干等 5~9 个请求里最慢的那个。两条路共用
  `BikeMapViewModel.applyResult`，视野记账仍在中心那一次写。

- 瓦片源三处硬口径（`ui/ebike/OsmMapView.kt`）：① **URL 别加 `scl=2`**——2026-09-27 试过：
  它回的是"无注记纯底图"（路网齐全、地名全没），真机上用户第一眼就报"名称没了"、当天撤销。
  带注记的只有 256px 版（按 512 声明渲染、略发虚），当前取舍是**注记优先**；要又高清又有
  注记得换方案，栅格这条路没有。② `Configuration.expirationOverrideDuration` 必须自己设：
  osmdroid 6.1.18 不解析 `Cache-Control`（拆包确认），默认不过期、旧图永远不换新，现设 7 天。
  ③ `tileFileSystemCacheMaxBytes` / `tileFileSystemCacheTrimBytes` 也要自己设：osmdroid 默认
  上限 600 MiB、回收目标 500 MiB，现设 60 / 50 MiB。**别把这两行删了**——默认值下瓦片能
  把 `cacheDir` 占到半个 G，而系统清理时机不可控。

- 「只看本校」（2026-09-27）：默认开的持久开关（`ebike_map_only_our_campus`，与「只看可用」
  同一套 VM 口径：数据在手、只重算簇不重查接口）。筛选口径是**校区名白名单 + 校区围栏
  双条件**（`BikeNearby.isOurCampusBike`）——快趣同时服务隔壁江西师大，两校车队坐标最近处
  只隔约 150 米，都在 2 公里展示上限内。校区字段实测：本校全部是「南昌工程学院」
  （servicesiteId=72，学校更名后运营方台账仍是旧名），师大全部是「江西师大」；
  白名单关键词是「南昌工程学院」「水利电力」，**空名放行**（缺数据不下断言，与电量的
  容错同一口径）。**围栏顶点（`BikeNearby.CAMPUS_FENCE`）是 GCJ-02**，与车辆坐标同基准，
  点内判定与地图绘制都直接用，**不要再过 Gcj02**（转一次偏 500 米）。顶点 2026-09-27 按
  **运营方官方运营区域**确定（用户对照官方小程序逐边指认、当天又按真机反馈复核偏差）：
  西沿天祥大道、北沿瑶湖西二路、东沿瑶湖西大道、南沿**瑶湖西一路**；顶点取四条界路的
  **中心线**（高德 z17/z18 瓦片按路面色带逐列提取，25 点），四个角点即路口（西南=出环岛、
  东南=一路×大道、东北=二路×大道、西北=天祥×二路信号灯）。131 实测点全在栏内、师大与
  工业职院全在栏外（`BikeNearbyTest` 钉了代表点）。初版凭截图配准曾整体偏 30~70 米、
  南边还把"校园南缘路"当界路——**要改顶点就重新按路网中心线提取，别目测挪**（工具
  `tools/campus-fence/extract_fence.py`：重抓 z17 高清瓦片→提四条路中心线→解路口→对账
  现行围栏与 131 点 fixture，跑法见脚本头；fixture 在 `app/src/test/resources/`，
  `BikeNearbyTest` 全量回归）。瑶湖西二路
  东段与瑶湖西大道同时是师大活动区域的边界，两校围栏相邻不重叠。地图上围栏由
  `BikeMarkerOverlay` **常驻**画在最底层（半透明填充 = **浅蓝 #64B5F6 alpha 0.30** + 蓝描边 #3D8BEF，
  dash/空 7/4dp，仿官方包裹样式；**别用主题深青**——深青 0.32 / 提亮 30% 的青真机都嫌"深、压暗"，
  2026-09-27 定稿直接换浅蓝色系），开关只管过滤不管画不画。

## 车号口径

- 车号口径（DESIGN §3.9）：输入框接受「1~3 位尾部」与「6~12 位完整车号」两种形态，
  唯一实现在 `EbikeQr.resolveCarNum`，`bikeUrl` 只认完整车号。地图选中的车走完整车号
  那条路（别的车队前缀是 `300000…`，靠尾部三位拼不出正确链接），
  **不要再按 `EbikeQr.TEMPLATE + 尾部` 拼 URL**。

## 内置相机扫一扫（2026-09-30 换自有取景窗口）

- 扫车身码的窗口从库自带的 `CaptureActivity` 换成自己的 **`EbikeScanActivity`**
  （`ui/ebike/EbikeScanActivity.kt`）：库窗口只有取景框 + 一行提示，没有开关手电筒、
  也没有「从相册选图」的入口，而这两件正是暗光 / 码已在相册里时的唯一出路。
  **相机那一套没重写**——预览仍是库的 `DecoratedBarcodeView`，开关机 / 权限 / 取景框 /
  解码节流仍归 `CaptureManager`，只换界面（Compose）。入口只在账号方式
  （`caps.cameraScan`，只此一处），调用点是 `RideScreen.scanBodyCode` 里的
  `ScanOptions.setCaptureActivity(...)`——**别把这行删了**，删了就退回库窗口。
- **结果与库窗口逐字段同形**（`CaptureManager.resultIntent` + `ScanContract` 不改）。
  相册那条路解出的车号**先在窗口里过一遍 `EbikeQr.parseScannedCarNum`**：从相册挑到
  无关截图是常态，那种情况留在取景页给一句「换一张试试」，别踢回骑行页报「未识别到
  有效车号」——那等于把用户刚打开的面板关掉。
- **回页面的判定分三档**（`RideScreen.scanLauncher`，2026-09-30）：`result.contents`
  **为空 = 取消**（取景页没扫到就被关掉，`ScanContract` 把 `RESULT_CANCELED` 映射成空
  结果）→ **不提示**；非空但 `parseScannedCarNum` 为 null（名片码 / 小程序码 / 别的链接）
  → 才报「未识别到有效车号」；其余照旧回填车号 + 车辆卡。**别把前两档并回一个
  `carNum == null`**——那正是「每次取消都挨一句报错」的来路（2026-09-30 修）。
  相机权限被拒 / 相机起不来这两条路走库自己的对话框，回来同样是空结果。
- **手电筒状态只有库一处**（`TorchListener` 回调 → `torchOn` 这枚 state，界面只读）：
  `setTorchOn/Off` 是同步回调，**别在页面里另存一份开关值**——去相册再回来时
  `CameraPreview` 会按自己记住的状态重新点亮补光灯，两处各记一份迟早对不上。
  设备没有补光灯（`FEATURE_CAMERA_FLASH`）时不摆这枚按钮。
- **相册解码先降采样**（`readSampledBitmap`：`inJustDecodeBounds` 探针 → `inSampleSize`
  取到长边 ≤ 2000 的最小 2 的幂）：12MP 照片整解是 48MB 的 int 数组，而二维码只要模块
  够清楚。解码的**纯逻辑在 `EbikeQr.decodeFromPixels`**（ARGB 像素 + 尺寸），Bitmap 那层
  留在 UI——所以 `EbikeQrTest` 能直接喂 `qrMatrix` 渲染出的像素做 round-trip。
  返回结果里的 `SourceData` 是 1×1 占位（`resultIntent` 只读 `Result` 的文本 / 格式 /
  字节），别把它当成"像素要回传"。
- **侧栏「扫一扫」与悬浮球双击是同一个取景窗口的另两个调用点**（侧栏入口 2026-10-20 起；
  今日页顶栏那枚「+」2026-10-22 已删，别加回来）：
  它们走 **`MODE_RAW` 原文模式**（本地不解析），去处由 `domain/QuickScan.kt` 的
  `quickScanTargetOf` 判——`EbikeQr.parseScannedCarNum` 过得去就带完整车号进
  `SubpageScreen.RIDE`（`focusItemId` 复用，进页即定位）；过不去的先看是不是认得准的
  别家码（就地提示），剩下的原文才交 U净。
  骑行页自己那枚入口的三档判定（取消不提示 / 不是车号才报错）**不受影响**，
  别把两条并成一条。

- **扫到车号后「确认可用就自动开锁」**（2026-10-15 用户要求；**确认闸保留**）：
  两条扫码入口——骑行页「扫车身码」（`focusCar(carNum, autoUnlock = true)`）与
  侧栏扫一扫 / 悬浮球带车号进页（`setPendingFocusCar(carNum, autoUnlock = true)`）——
  识别出车号后先回填车号，**确认这辆车可用后**才发一次 `BikeMapEvent.AutoUnlockReady`，
  页面收到事件才摆车辆卡并走**开锁的同一套闸**（`requestKvcxAction`：
  定位前置 + 二次确认），即「识别 → 确认 → 本机开锁」，不再要用户再点一次开锁。
  - **可用判据**：附近列表命中且 `NearbyBike.available`，或按车号点名查（`queryOneCar`）
    返回 `CarDetailResult.Ok` 且 available。**其余一律不自动开锁**——已出租 / 维修 / 离线 /
    查不到 / 网络失败 / **正在骑这辆**都只清目标、不发事件。**扫到已出租/不可用的车时
    不摆车辆卡**，页面留在原地给一句原因，用户可以直接再点扫一扫；不要逼人先关卡片。
    **宁可不自动开锁，也不误开**。
  - `autoUnlockCar` 只认**最近一次扫码**：`focusCar` 换了车就把旧目标作废（免得一个没结论的
    旧目标被后来的「定位」动作意外触发）；目标没有结论的几条路（session 不可用 / 已有查询
    在飞 / Malformed / 撞在骑车辆 / **带车号进页那条路首查失败**）都**显式清掉**，
    别留悬空目标。
  - **三条硬口径**：① 自动开锁走的是与「开锁」按钮**同一个** `requestKvcxAction`——不是另开
    一条免确认的路（用户关掉「开锁前确认」才直发）；② **能力判据仍是 `caps.directUnlock`**
    （账号方式 + 已登录 + 无在案订单）——今日页扫一扫在小程序方式下也会进本页，但**不触发**；
    ③ 页面侧的执行走一条 **keyed `LaunchedEffect`**（`autoUnlockRequest`）：事件收集跑在
    `LaunchedEffect(Unit)` 里、闭包停在首次组合（`prefs.unlockConfirm` 是 DataStore 默认值），
    **别把执行并回事件收集**，否则用户关掉的免确认设置会被忽略。
  - **带车号进页那条 effect 在 Activity 重建时会重跑**（转屏 / 改字号 / 深色切换 / 进程恢复），
    不设闸就会对同一次扫码再发一次 `AutoUnlockReady` = **重发一次开锁**（违反零自动重试）。
    所以 `LaunchedEffect(initialFocusCarNum)` 用 `rememberSaveable` 的 `autoUnlockArmed`
    只**首次**带 `autoUnlock = true`（重跑那次仍 `setPendingFocusCar` 定位 / 高亮，但不发开锁）。
    骑行页「扫车身码」不经这条 effect（ActivityResult 回调不重放），无需这道闸。

## 扫完即焚（2026-09-27 改口径）

- **出码页两个开关的「行为判定」读原始流，不读页面快照**（2026-09-27 修「开了自动保存
  却无效」）：`EbikeViewModel.generate` / `saveCurrent` 读 `prefs.ebikeAutoSave.first()` /
  `prefs.ebikeBurnAfterScan.first()`，**不要**读 `ebikePrefs`（`stateIn` 缓存快照，
  DataStore 首次发射前是构造时的默认值，冷启动首帧一定命中）。踩坑路径是「进页即出码」
  （今日页地图选车带 `carNum` 进页、进程被回收后恢复出码页）：`generate` 在首帧就跑到，
  快照那时还是默认值（`autoSave=false`、`dark=false`），自动保存被静默跳过（相册没图、
  也没有任何提示），深色主题还会出一张白底码。快照现在只用于界面展示与读失败兜底；
  同一个坑 `burnPending` 早就改过了，理由写在它里面的注释。

- 删除时机**跟计时段落走，不跟「回到 App」走**（用户拍板，修「保存后相册里没有图」的
  bug：旧版 ON_RESUME 无条件删，用户保存 → 切微信 → 中途回 App 一眼，码就被清了）。
  三个触发点：① `EbikeFreeRideReminder.check` 的免费结束分支 + 收干净分支（结束闹钟/
  周期兜底/进页核对，到点即删，不必等用户回 App）；② 手动结束骑行（`EbikeViewModel.onEndRide`
  → `burnPending(force = true)`）；③ 页面 ON_RESUME 的 `burnPending()` 只是兜底——
  **计时进行中必须不删**，无计时在案（没点「打开微信扫一扫」）才维持回 App 即删。
  码是开锁耗材，无计时段落兜着就不能留在相册；**别把 ③ 的计时判断或 check 收干净分支
  里的删除当冗余优化掉**，也别改回「ON_RESUME 无条件删」。
- `burnSavedCodes`（Reminder）与 VM 的 `burnPending` 可能并发：同一条 key 第二次删除返回
  false、记录更新是 DataStore 原子变换，靠幂等成立，**不要加跨对象锁**。
- 开关文案「骑完车自动删除」；提醒开关关着但计过时的场景没有闹钟/周期兜底，删除退化为
  下次回 App/进页，是有意行为。

## 免费时长提醒

- **小程序方式的计时起点 = 点「打开微信扫一扫」+ 5 秒缓冲**（2026-10-05 用户拍板，
  `EbikeFreeRide.SCAN_DELAY_MS`）：起点由 `EbikeViewModel.onWechatScanClicked` 算好
  传给 `startFreeRideNotice(startAtMillis)`——**别把参数改回函数内取当下**；账号方式
  开锁成功走默认参数，起点仍是真正开锁时刻。缓冲期内（now < 起点）倒计时**显示满格
  15:00 不走**（`remainingSeconds` 钳上限，`progressFraction` 的 elapsed 钳 0，
  `EbikeFreeRideTest` 钉住），免费结束与两个提醒点整体后移 5 秒。**倒计时是预估**：
  骑行页倒计时块（`RideCountdown` 的 `showEstimateNote`，判据 `caps.wechatScan`，
  经 `RideRidingUpper` 透传）进度线下常驻「倒计时为预估，实际以微信小程序显示为准」；
  账号方式起点是服务端确认的开锁时刻，**不写这句**。

- **免费是套餐卡权益，所有免费时长文案都带前提**（2026-10-07 用户要求写明）：
  前 15 分钟免费**以持有骑行套餐卡为前提**，文案不许让它看起来是无条件的。
  统一出处 `EbikeFreeRide.TICKET_NOTE`（「免费以骑行套餐卡为准」）+ 通知文案的
  「按套餐卡 15 分钟免费计」（`COUNTDOWN_TEXT` / `END_TEXT` / `leadText` 三处，
  `EbikeFreeRideTest` 钉住「套餐卡」字样）：① 通知正文（提醒本体）；② 倒计时块进度线下
  **常驻一句 `TICKET_NOTE`（两档都显示）**；③ 设置弹层「免费时长提醒」副标题追加
  「；免费以骑行套餐卡为准（前 N 分钟免费）」——数字用 `FREE_MINUTES` 拼，**别手抄 15**。

- **倒计时上「灵动岛」= 系统标准 Live Updates，不是小米私有焦点通知**（2026-10-16 用户要求）：
  倒计时通知在 **Android 16+** 上被系统提升为 Live Update——状态栏芯片
  （`EbikeFreeRide.countdownChipText`，「剩mm:ss」，每秒重发；**HyperOS 3 的芯片不显示
  chronometer**，没这条只显示通知标题）＋ 卡片头部由 `setWhen(免费结束时刻)` +
  `setUsesChronometer(true)` + `setChronometerCountDown(true)` 自己走 `mm:ss` ＋ 通知抽屉顶部
  常驻卡；**开启通知即默认生效，没有开关**。提升请求只要两件东西：manifest 声明普通权限
  `POST_PROMOTED_NOTIFICATIONS`（安装即授予），通知上 `.setRequestPromotedOngoing(true)`。
  芯片小图标用 `ic_stat_ebike`（实心自行车，2026-10-19；倒计时这一条专用）。**别用
  HugeIcons 的线性滑板车**（16dp 下糊成一团），**别用桌面图标**（小图标只取 alpha 通道，
  会压成白块）；两条到点提醒继续用 `ic_stat_bell`。
  **卡片带进度条**（`ProgressStyle`，填满 = 免费时长还剩满格、随免费时间流干；不设 segment /
  tracker 图标，走默认「按进度着色」，无 segment 时满刻度 100）。**它曾被当成「岛抖」的嫌疑犯
  撤掉过一次（2026-10-16 二轮），用户确认抖动在它之前就有、已按用户要求加回**——别再拿它当
  抖动的原因。**2026-10-19 用户先报「下拉状态栏时水贝贝图标一闪一闪」，看了 5 秒版后
  又明确「我要看的就是倒计时」**：HyperOS 3 的芯片不显示 chronometer，岛上逐秒走只能靠
  每秒重发 `setShortCriticalText`；代价是 Live Update 卡片每收到一次更新会重绘头部的
  应用图标。最终口径：**芯片文案与进度条每秒重发**（`EbikeFreeRideService.TICK_MS =
  1_000L`），正文用静态 `COUNTDOWN_TEXT`，秒数优先、接受闪烁。**别再改成 5 秒一跳**；
  **也别把芯片文案去掉**（HyperOS 会退回显示通知标题，岛上就没有秒数了）。
  这条链上做过两处**无损**优化（2026-10-16 二轮，观感与行为不变）：① 落点 PendingIntent
  进程内缓存（`contentIntents` 按 requestCode / `cachedDelete` 按起点，每轮少两趟到
  ActivityManager 的 IPC；删除落点那份**必须是单个不可变持有者、一次原子读**——拆成两个
  `@Volatile` 字段会在换车时读到「新 intent + 旧起点」）；
  ② 服务只在芯片文案或进度刻度真变了时才 notify（扫一扫后那 5 秒缓冲期进度满格不动、原本白刷；
  灭屏回来必补一次，所以「点亮即准」照旧）。
  **被划掉就收手**（`setDeleteIntent` → `EbikeCountdownDismissReceiver` → `EbikeCountdownDismissal`
  记账 + 停常驻服务；`EbikeFreeRideService.start` 对同一轮起点直接返回 false，`check` 每轮都调它，
  那道闸是唯一拦点）——官方明写「别把用户划掉的 Live Update 再顶回去」，划掉 = **这一轮不要这条
  倒计时**（前台服务只为这条通知而活，没它就没存在理由）；提醒闹钟与 App 内倒计时块不受影响。
  记账是**进程内**的（与 `runningStartAt` 同性质）：换车换起点自然失效，进程被杀就丢——重启后
  不该继承上一轮的「别打扰我」。
  **别去碰 `miui.focus.param` / 小米超级岛**——那要小米平台白名单（企业认证 + 上架 + 逐场景审核 +
  证书指纹），本仓库私有、非官方、不分发，拿不到；参考实现是 RikkaHub（同机型同 ROM 已跑通），
  它用的就是这套标准 API。官方资格清单现在全满足：Standard/BigText/Call/Progress/Metric 样式之一 ·
  `ongoing` · 有 contentTitle · 无自定义 RemoteViews · 非 group summary · 不 colorized ·
  渠道非 `IMPORTANCE_MIN`——**别加 `setCustomContentView`、别把倒计时渠道降成 IMPORTANCE_MIN**，
  那会当场取消资格。工具链代价：compileSdk 36 + AGP 8.9.1 + androidx.core 1.17（见 AGENTS 工程实况）。
  两条到点提醒**不做**提升（不是 ongoing）。

- 免费时长提醒（DESIGN §3.9）：**走 App 通知 + 前台服务常驻倒计时，不写系统日历**
  （2026-09-24 用户拍板改回；2026-09-23 那次「改系统日历」已作废）。
  两个提醒点（提前量点、免费结束）由 `AlarmManager.setAlarmClock` **精确**触发：
  系统级闹钟，到点唤醒设备、不受 Doze/省电推迟、**不需要任何特殊权限**
  （与上课提醒同一手法，requestCode 4003 与上课的 4002 错开）。
  **通知栏常驻倒计时**由 `EbikeFreeRideService` 承载（`specialUse` 类型；
  `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`）。剩余时间**由服务每秒
  重发一次通知文案**（`EbikeFreeRide.countdownText`，每秒一次是系统允许的上限），
  但**只在亮屏时刷**：灭屏一个 notify 都不发（协程阻塞等屏幕亮，点亮补刷一次）——
  这是省电的主要来源，别改回「不分屏幕状态一律每秒刷」；
  **不要**改用系统 chronometer（`setUsesChronometer` / `setChronometerCountDown`）——
  2026-09-24 在 Redmi K70 / 澎湃OS 实测：面板静止时系统不主动重绘，数字不动，
  用户报「通知栏没有秒」。到点提醒**不要**改由服务里的协程 `delay` 负责——
  屏幕关闭后 CPU 挂起，会迟到几分钟；服务只管展示与保活。
  channel 用新 id（`ebike_free_ride_countdown` / `ebike_free_ride_alert_v2`），提醒那个
  **带震动**（双震 pattern）且 category 用 `EVENT`（对齐上课提醒）；**不要复用首版的
  `ebike_free_ride`**——已存在 channel 的 importance 与震动都改不动，且「删掉重建」也无效
  （delete 是异步的，紧接着 create 会被当成更新，实测震动没生效），只能换 id；
  通知 id 1000/1005/1006，与上课（1001/1004）、作业（1002/1003）错开。
- **震动要 `VIBRATE` 权限，横幅弹不弹不由我们定**（2026-09-30 用户报「通知了但只在通知栏
  看到，不弹出也没震动」）：manifest 此前一直**没声明 `VIBRATE`**——Android 8 起渠道上的
  `vibrationPattern` 靠它才生效，缺了系统静默丢弃，所以 `enableVibration(true)` 配了也白配
  （这是那一轮的真根因，已补）。另外 `ReminderVibration.vibrate`（2026-10-05 自
  `EbikeFreeRideNotifier.vibrateAlert` 抽成共享工具 `ui/reminder/ReminderVibration`，
  上课/作业截止提醒也用它；**2026-10-06 起收在通用设置「通知强提醒」开关后面**——
  直接震动能穿免打扰，属强打扰，默认关，`EbikeFreeRideReminder.check` 读
  `strongReminderVibration` 传入）在两条提醒里
  各发一次**直接震动**（`VibrationEffect`；API 31+ 走 `VibratorManager.defaultVibrator`，
  更低走 `Vibrator`）：渠道震动归系统通知设置管，用户把那条渠道的「震动」关掉后它就不震，
  直接震动不看那个开关。**横幅（heads-up）只能尽力**：App 侧只有「HIGH 渠道 + 不静音」
  这一条路，应用被后台限制、通知设置里「悬浮通知 / 横幅」被关，都会让提醒只落在通知栏
  ——所以设置弹层「提醒」区常驻一条「通知与悬浮」（`AppPermissions.jumpNotificationSettings`，
  跳应用通知设置页、失败兜底应用详情页），**别删**：那是用户唯一能改的地方。
  **常驻倒计时那条保持 LOW 无震动**——它每秒重发一次，做成横幅等于每秒弹一次。
  迟到窗口（用户拍板）：提前量那条只要免费时段没结束就补发，结束那条结束后
  5 分钟内仍发（`EbikeFreeRide.END_WINDOW_MS`）；去重键带计时起点，落
  `ebike_free_notified_keys`。`EbikeFreeRideCheckWorker` 的类名也别改
  （老版本排下的周期任务按类名实例化，`KEEP` 策略又不会重排，兜底会永久消失），
  但它的**周期任务只在计时期间排**（15 分钟 = WorkManager 最小周期，`cancelAll` 里撤）：
  别改回「冷启动无条件常排」——那会让没骑车的用户每 15 分钟被冷启动一次进程。
  系统日历那条链路（`data/calendar/EbikeCalendarEvents.kt`、`ebikeFreeEventId`、
  `水贝贝骑行提醒` 标记、日历权限申请）已整套删除，**别加回来**。

- **`produceState` 的 key 变了不会重算 `initialValue`**（2026-09-29 真机点验抓到）：
  今日页副行 `rideSubtitle` 用 `key3 = idle`（随使用方式变的常态文案）重启 producer，
  但 `initialValue` 只在**首次组合**生效——producer 重启后若直接 `return` 不写 `value`，
  State 就永远停在首帧那个字符串（现象：切换使用方式后副行仍显示「微信扫一扫开车」，
  且切页/回页都不刷新）。修法是在 `!counting` 分支里先 `value = idle` 再 return。
  **凡是用 `produceState` 承载「会随参数变的静态文案」的地方，重启分支必须显式赋 `value`。**
- 今日页快趣卡副行在计时中显示「免费剩余 mm:ss」（2026-09-27，`TodayScreen` 的
  `rideSubtitle`）：每秒自刷，到点、或起点被清成 0（结束骑行 / `check` 收干净）落回
  「微信扫一扫开车」。**判据与出码页计时条完全一致**（开关开 + `EbikeFreeRide.isActive`），
  **别改成「有起点就显示」**——同一个计时在两页说法不一样比不显示更糟。起点与开关从
  `Graph.displayPrefs` 直接读，不往 `DisplayPrefs` 合并读模型里加字段（那条 combine 已满员）。

## 自动还车（2026-10-10 用户要求）

- **账号登录方式专用 + 默认关**。用车满 15 分钟**前**自动发起还车，让订单在免费时段内
  结束；设置入口 = 快趣出行设置的「自动还车」区（开关 + 触发提前量 + 失败重试次数）。
  **触发点 = 开锁 + 15 分钟 − 提前量**（`EbikeAutoReturn.triggerAtMillis`），提前量
  10~60 秒、步长 5、默认 30——**不许卡在 15 分钟那一刻或之后**：晚于免费时段的还车会
  开始计费（用户 2026-10-10 明确）。提前量下限 10 秒就是这条口径的代码保证，别再放宽。
- **重试 = 上一次失败后 3 秒内**（`EbikeAutoReturn.RETRY_INTERVAL_MS`），次数 1~3、
  默认 2（`totalAttempts` = 重试次数 + 1）。**每次尝试结果都发通知**（用户口径）：
  成功一条；失败写清原因原文 + 第 i/N 次 + 是否还会重试。调度费（`dispatchMoneyCents`）
  与支付分授权（11035）**不重试**，直接通知收场（`isRetryable`）。
  **与「写操作零自动重试」的关系**：仓库层红线不变——重试是这条**用户显式开启**的
  自动功能自己的行为、次数由设置封顶；别据此给手动还车加任何重试。
- **还车坐标：用户定位优先、车辆坐标兜底**（2026-10-19 修订）：自动还车不再只用
  `Ride.lat/lng`。执行时读骑行页留下的最近用户定位（`EbikeUserLocation`，5 分钟、进程内，
  **不主动定位**）；先用它，缺了/过期才用车辆坐标；**车辆已锁**（`ride.locked == true`）
  时不用用户定位——人可能已经走开，车停在哪就用哪个坐标。遇到 50011（不在还车区域）**换另一个
  来源再试一次**（`preferUser` 翻转）——车辆上报滞后与用户定位漂移都可能造成「人在还车点
  却报 50011」。别把 `BikeLocator` 主动加回这条路（后台拿不到定位，也没必要）。
- **调度三层**（`ui/ebike/EbikeAutoReturnReminder.kt`）：精确闹钟（`setAlarmClock`，
  requestCode **4004**，与上课 4002 / 免费提醒 4003 错开）到点 → `EbikeAutoReturnAlarmReceiver`
  入队一次性 `EbikeAutoReturnWorker`（WorkManager 唯一名 `ebike_auto_return`，KEEP）→
  `runAutoReturn` 循环（`ui/ebike/EbikeAutoReturnRunner.kt`）。**别把执行挪进闹钟接收器**
  （网络 + 3 秒重试超出 `goAsync` 的时限），**也别改由前台服务的协程 delay 负责**
  （灭屏 CPU 挂起，和免费提醒同一条理由）。Worker 类名别改（WorkManager 按类名恢复任务）。
- **同一轮只跑一次**：`EbikeAutoReturn.doneKey(startAt)` 落 `ebike_auto_return_done_key`——
  成功、重试用尽、不该重试的失败都落键；换车（新起点）自然换键。进页 / 账号页 / 开锁
  各调一次 `EbikeAutoReturnReminder.reconcile` 兜底（闹钟被 ROM 丢掉时补跑）。
  `reconcile(catchUp = false)`（设置变更）**不追补**触发点已过的这一轮——刚打开开关
  不该让 App 立刻还车；已排着的闹钟也不撤，到点按最新设置复核。
  `run` 的早退（开关关 / 非账号方式 / 没到点 / 过了迟到窗口 / 已执行过）**都不落键**：
  那是"这次不该跑"，不是"这轮跑完了"。
- **订单结束后要通知活着的页面**（2026-10-19）：Worker 拿不到页面的 `KvcxRideController`，
  只清 DataStore 会出现「自动还车后车辆卡片还在、只是没有时长条」。`EbikeRideSignal.notifyEnded()`
  是唯一交接点；RideScreen（`BikeMapViewModel`）与快趣账号页（`KvcxViewModel`）各收一次，
  清 `ride` 并补一条 Snackbar。手动还车也发一次空文案，清账号页那份状态。
- **执行窗 = 触发点 ~ 免费结束后 5 分钟**（`EbikeAutoReturn.isDue`，迟到窗口与免费提醒的
  `END_WINDOW_MS` 同口径）：闹钟晚到十几秒仍要还车，拖过窗口就不再追；窗口外由
  `reconcile` 清残留排程。
- **结算口径与手动还车共用** `performReturnBike`（`KvcxRideController.returnBike` 与
  `runAutoReturn` 都调它）：本地走时在订单结束那一刻截停、扣款确认轮询、结果卡字段
  **只允许一份**。成功 → 通知 + `AppKvcxSideEffects.recordRide` + `endRideAndBurn`
  （收计时 / 焚码 / 撤自动还车闹钟）；订单已不在 → 静默收尾（可能其他端还了）；
  未登录 → 通知并停。订单在案时**不给** App 内「结束计时」（`ride == null` 才露出），
  所以不存在"结束计时但订单还在、自动还车继续排"的组合。
- **与免费时长提醒两套独立开关**：自动还车开着、免费提醒关着也照排（反之亦然）——
  别把它的排程塞进 `EbikeFreeRideReminder.check` 的开关分支里。
- 通知：channel `ebike_auto_return`（HIGH + 震动；新 id 的理由同 `_v2` 踩坑）、
  通知 id **1014**、落点 requestCode **2007**（与免费提醒 2000/2005/2006、余额 3005/3006
  错开）；「通知强提醒」开着时补一次直接震动。同 id 覆盖——第 1 次失败会被第 2 次的
  结果顶掉，不堆一摞。
- 红线：`EbikeAutoReturnTest` 钉住时刻 / 吸附 / 文案 / 重试判定 / 循环时序；
  改这块先跑它和 `KvcxRideControllerTest`（开锁要排自动还车、未确认不排）。

## 精确倒计时（已删除，2026-09-30）

- 「精确倒计时」（微信通知校准，DESIGN §3.9 曾有）**已按用户要求整套删除**，别当漏项加回来：
  `ui/ebike/WechatRentListener.kt`（`NotificationListenerService`，要「通知使用权」）、
  `domain/WechatRentNotice.kt`、`EbikeFreeRideReminder.calibrateRideStart` / `endRideFromNotice`
  两个入口、prefs 两键（`ebike_precise_countdown_enabled` / `ebike_precise_calibrated_at`）、
  设置弹层的开关与授权提示、`EbikeCapabilities.wechatNoticeCalibration` 能力位、
  manifest 里的服务声明、`WechatRentNoticeTest`。
  免费时长提醒本身**不受影响**：计时起点固定为「点扫一扫」（小程序方式）与「开锁成功」
  （账号方式）两个口径，不再校准。
- **仍适用的口径**（原记在这一节，与服务有关，别丢）：常驻倒计时的起停判据是
  **起点是否变化**（`EbikeFreeRideService.start`）：起点变了就重新
  `onStartCommand` 重建 tick，起点没变才跳过。**别退回「服务在跑就跳过」**——上一轮没结束
  就换车时 tick 循环握着旧起点，倒计时不会重置（2026-09-24 用户报的 bug，当时是
  `running` 幂等标记惹的）。换车（`startRide`）还要顺手清掉上一轮挂在通知栏的提醒。
