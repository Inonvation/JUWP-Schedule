# 共享单车 · 地图 · 免费时长提醒

作用域：改快趣出行出码、附近车辆地图、免费时长提醒时读（「精确倒计时」已删，见同节墓碑）。规格见 DESIGN §3.9 / §4.23 / §4.32。

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
    上限两道：`MAX_PANEL_HEIGHT_DP` 与「面板 + 底部常驻块 ≤ 窗口 72%」（`PANEL_MAX_RATIO`，
    地图留三成）。常驻块高度**由页面实测**（`onGloballyPositioned` 写 `bottomBlockDp`，
    `remember(phase)` 先用估值兜底：骑行 250dp / 找车 120dp），别改成写死的常数。
    导航栏内边距归常驻块吃，面板自己不再垫（旧版两处都垫）。
  - 三态由数据推导（`phase`），不另存可变状态：`Settled`（有还车结果）→ `Riding`
    （有订单或在案计时）→ `Finding`。「选中某辆车」「选中某停车点」**不在三态里**，
    它们是**动作区**的形态（`pickedCar` / `state.spotKey`）。
  - **动作区分两层，动画只作用在上层**（2026-09-30 用户口径「做成底部窗口升起的过渡动画，
    没有组件跳变」）：
    - **上区**（`RideActionUpper`）= 状态相关的额外内容（车辆卡的头行与详情、骑行指标、
      结算明细）。换状态时走 `slideInVertically { it } + fadeIn` / `slideOutVertically`，
      像底部窗口一样**从下沿升起来、沉回去**（`BAR_RISE_MS` 240 / `BAR_FALL_MS` 190，
      缓动 `FastOutSlowInEasing`）；容器高度也走同一条曲线（`ContentTransform.using(SizeTransform)`，
      **`AnimatedContent` 没有 `sizeTransform` 参数**，别往那里塞）。
      **车辆卡的 key 带车号**（`car:100000398`）：换一辆车时上区要重走一遍升起动画，
      不然只有文字原地换掉。车号**从 key 里取**（`carNumOf`），别读外层那个 `pickedCar`
      ——退场那一份读的是最新值，会跟着变成新车、两层画同一张卡。
    - **下区**（`RideActionButtons`）= 主动作，**常驻、不参与上面的动画**。找车态与车辆卡里
      它是同一枚按钮、同一个位置，所以换状态时看不见它跳；按钮内部的图标与文字用一层
      `AnimatedContent` 交叉淡入（`RideActionButton`：整行 46dp 胶囊，主次只差实心 / 描边；
      旧的 `RidePrimaryButton` 已并进它）。
    **加新形态时把内容分进这两层**，别把主动作塞回上区——塞回去就又开始跳了。
    **退场那一份会继续组合 190ms**，它读的是外层参数的**最新值**：还车时 `ride` 变 null、
    点「继续找车」时 `summary` 变 null、结束计时时 `timerActive` 变 false，退场路上那半秒
    就画不出东西（看着是"内容先没了、区域再收回去"）。所以 `RideActionArea` 里按 key
    留了一份 `RideUpperHeld` 快照（ride / summary / 计时两项），**加新字段时先想清楚它会不会
    在状态切换那一刻被清空**；别的字段（更新时刻、筛选开关）照旧读实时值，冻住会把走时冻掉。
    面板内容在「车辆列表 ↔ 还车点」之间走 `Crossfade`，二维码面板与生成按钮的主次切换
    也各有一层淡入。
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
  - **车辆行右侧直接给生成乘车码 / 开锁**（`RideBikeRow` 行尾那枚）：行的信息（车号 /
    状态 / 电量 / 距离）和车辆卡里那份差不多，没必要非得先点开卡片才能出码。账号方式已登录给
    「开锁」（写操作，有计费后果），其余给「生成乘车码」。行尾的 chevron 因此删了。
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
    骑下一辆，弹窗把流程打断；卡上给「继续找车」这条出路。
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
- **地图的让位高度在底部块换形态时冻结**（2026-10-01 同一条报障的另一半，`RideScreen`）：
  动作区 `barKey` 一变，`AnimatedContent` 的 `SizeTransform` 走 240ms 高度动画；页面若让地图
  跟着实测高度走，这 240ms 里**每帧 resize 一次地图**（osmdroid 每帧整幅重绘 + 每帧写状态
  重组整页）。现在：`mapReserveDp` 是**冻结值**，动画期间不动（地图被升起来的底部块盖住），
  动画结束取最终实测高度一次性落位——被切掉的那一截正好在块后面，看不见。
  三个配套件别拆：① 页面结构是 `Box { 地图（按 panelHeight + mapReserveDp 让位）;
  贴底的 Column（面板 + 常驻块，叠在地图之上）}`——不是 `Column` 里排，否则冻结会撑破容器；
  ② `barKey` 由 `rideBarKey()` 唯一算出（页面与动作区共用），页面靠它触发冻结；
  ③ 布局回调（`onGloballyPositioned`）在动画期间**只记账不写状态**（`DpHolder`），
  不然每帧一次状态写就把省下来的又花回去。
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
  蓝点实时更新（2026-09-26）：页面可见期间（`repeatOnLifecycle(STARTED)`）挂 `BikeLocator.updates`
  连续定位流，只走 `onUserLocationChanged`——**只挪蓝点与「距你」距离，不移镜头、不重查车辆接口**
  （镜头归一次性定位、接口禁止轮询，见 DESIGN §3.9）。挪点口径 `shouldMoveDot`（位移 ≥4 米
  或精度显著提升）是防双源交替与站定抖动的，**别删**；请求节奏 2 秒一次，别改回 0（费电不讨好）。
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
  （这是那一轮的真根因，已补）。另外 `EbikeFreeRideNotifier.vibrateAlert` 在两条提醒里
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

## 快趣出行登录与用车（DESIGN §4.32，2026-09-28；A 只读 + B/C 用车）

- 逆向产物（`docs/kvcoo-miniprogram-analysis.md`，仅本地）确认快趣后端无签名、token 唯一凭证。
 登录 = `userLoginByPassword {mobile, password: MD5(密码)}`（标准小写 hex，复用
 `QzxyCredential.md5Hex`）。
- **未登录的登录表单要写清「忘记密码去哪儿改」**（2026-09-30 用户要求，`KvcxScreen.LoginSection`
  登录按钮下方常驻一句）：改密码只能在快趣小程序里做，App 不存找回流程也没有客服通道。
  两个落点从解包产物核对过——小程序**登录页的「忘记密码」**（`pages/login/forget`：
  手机号 + 短信验证码 → `resetPassword`）、**登录后「设置 → 修改密码」**
  （`pages/setting/modifypassword`：只填新密码 → `updatePassword`）。同一句里写明密码
  （连同手机号）保存在本机加密存储（`KqxCredentialStore`，为的是 token 失效后静默重登），
  别让人以为「App 不用密码」。
- **字段解析一律宽容取值**（`KqcxAuth` 里 `text/long/double/int/bool` 五个私有扩展）：
  快趣后端字段类型不稳定（数值给字符串或带小数点的 number、布尔给 0/1），严格 DTO 会在真机
  直接抛「骑行订单字段解析失败」（2026-09-28 用户真机报过）——**别退回 `@Serializable` 硬解**，
  测试里有混合类型回归用例。
- **token 仅内存**（`KqcxSessionRepository` 的 @Volatile），落盘只落账号密码
  （`KqxCredentialStore` → `secure_kqcx.xml`，已排除备份）。TTL 未知，靠「按需重登」兜：
  查询遇 `looksLikeTokenError` 才重登一次——这个启发式**宁漏判不误判**，别放宽（误判会把
  「车辆不存在」这类业务错误变成反复重登）。重登有 AtomicBoolean 互斥，别删。
- 出码页的骑行状态（置顶「当前骑行」卡）只在**登录时进页查一次**、失败完全静默
  （`queryKvcxRideQuietly`）；手动刷新与动作后刷新另有出口。**不要**改成轮询——骑行卡的秒表是
  **本地走时**（`kvcxRideFetchedAt` + `totalDate` 每秒重算，不联网）。
- 骑行订单字段已钉（解包骑行页消费代码 + 真机确认）：`carNum` / `bluetoothName` /
  `totalDate`（**已骑秒数**）/ `payMoney`（**分**）/ `lat` / `lng` / `lockStatus`（1=锁上，
  锁上不等于订单结束）/ `currentPercent`（电量）。无订单还可能是 **errorCode 12003**
  （「订单已结束」），解析层把它归成 null，别当错误抛。时长文案 `formatRideDuration`
  **逐字对齐**小程序 `simplehumantime` 的怪渲染（秒 ceil 成分钟填「分」段、首段恒 00：
  骑 1 分钟显示 `00:01`）——别「修正」成正常的 mm:ss，那是另一套展示。
- **用车动作（B/C 档）的官方调用链**：开锁 = `createOrder` →（`helmet==1&&helmetConfig==1`
  或开锁返 16015 → `helmetUnLock`）否则 `greenCarUnlock`；临时锁车 = `greenCarLock`；
  还车 = 未锁先 `muteCarLock` → `endTheOrder`（`dispatchFlag:2`；官方在用户确认调度费时才发 1）。
  定位一律 GCJ-02（官方 `wx.getLocation type:"gcj02"`），App 用 `BikeLocator.currentLocation`，
  `locationSource:"realtime"`。
- **写操作红线**（2026-09-28 用户拍板，别松）：
  1. **零自动重试**——`createOrder`/`endTheOrder` 超时绝不重发（防重复订单/重复结算）；
     超时后只读刷新判状态。唯一的安全重试是「重试开锁」（订单已在案、只重发 `greenCarUnlock`，
     官方同款），且必须由用户手动点。
  2. **动作前先查在案订单**（`unlockBike` 闸在案骑行、`returnBike` 闸无骑行）——本地先挡，
     服务端 12022 只是兜底；别为「省一次查询」删掉。
  3. **写操作二次确认**（`kvcxConfirmDialog`）：开锁示完整车号 + 计费、还车示核对车辆物品 +
     调度费风险；**临时锁车与「解锁继续骑」免确认**（2026-09-28 用户拍板——两者都发生在既有
     订单内、没有新增的计费后果，误触代价低于多一次确认的打扰；「计费继续」由 `tempLock`
     成功后的提示文案交代）。弹窗文案与「哪些动作要弹」都只在这个函数里，两页共用，
     别在页面里另写一份（`KvcxRideControllerTest` 里那几条断言钉着这件事）。
     - **正文是编号要点**（`KvcxConfirm.points`，2026-10-01）：三件事（计费 / 核对车号 /
       支付分出路）各一条，别糊回一整段；长文配 `heightIn(max = 400.dp)` + `verticalScroll`
       （`ui-common.md` 对确认型弹窗的硬要求，小屏与大字体档位下按钮不能被挤出去）。
     - **只有开锁能免确认**（`allowSkip`）：勾选「以后开锁不再确认」落 `ebike_unlock_confirm`，
       出口在快趣出行设置 → 开锁与还车（`RideConfirmSettingsSection`）。**四道闸里只放开这一道**
       ——它是"用户对自己账号"的授权；还车涉及结算与调度费、重试开锁是异常路径，都保留每次
       确认（弹窗那行常显说明就是写给用户看的，别顺手把还车也做成可关）。
      关掉后 `RideScreen.requestKvcxAction` 走直发分支，**定位前置仍在它前面**（那一道不可关）；
      页面判定读的是 `EbikePrefsSnapshot.unlockConfirm`，它滞后时只会多弹一次确认，偏保守。
       **勾选不记忆**：每次打开弹窗都从没勾开始（勾选是这一次的明确决定）。
     - **弹窗的状态读取收在 `KvcxConfirmHost` 里**（2026-10-01）：`pendingAction` 用裸 state
       对象传递、在宿主组件里才 `by` 读——读在 `RideScreen` 函数体里的话，开关弹窗会重组整页，
       连带地图走一遍 `AndroidView` update。**别再把它改回页面函数体里的 `by` 读**。
  4. **调度费/出围栏不代确认**——`dispatchMoney>0`、50011 一律抛错降级官方渠道，
     **别**改成发 `dispatchFlag:1` 替用户接受费用。
  5. **支付/免押授权不做**（微信内流程）：`needPay` 只提示 + `confirmUnpaidSettled` 短轮询
     确认结果（≤3 次、2.5 秒间隔）；**11035 不是缺项**——那是微信支付分 `wxpayScoreUse`
     下单授权（微信客户端专属 API，代调不了），走支付分免押的账号每笔订单都会命中；
     处理 = `kvcxScoreAuthRequired` 会话标记 + 「去微信扫一扫」出路，**别尝试任何绕过**
     （伪造 package / 换 clientType / 直调微信 SDK 全在禁区）。**排查顺序**（2026-09-28
     用户实测）：先找快趣客服开免授权（开通后本机开锁直接可用）→ 或缴诚信金（可退，¥99）
     → 或继续微信扫一扫。
  6. 动作后有界确认（`awaitRide`，≤4 次 1.5 秒）是官方同款节奏，**不是**常驻轮询。
- **骑行状态的刷新时机（2026-09-28 补）**：进页一次、**回页（ON_RESUME）一次**、动作后刷新、
  手动刷新。回页那一次是必须的——`LaunchedEffect(Unit)` 只在首次组合跑，退到后台/从别页回来
  不会重放；没有它，「在地图页开的车，返回出码页看不到骑行卡」这类状态过期必现。
  **仍然不轮询**：一个可见页面对应一次查询。
- 计时联动：开锁成功 → `startRide(now)`（起点钉在真正开锁时刻）；还车成功 → 手动「结束骑行」
  同口径（`endRide` + `burnPending(force=true)`）。这条别漏——否则用户本机开锁后计时起点仍
  停在「点扫一扫」的旧语义上。
- **开锁要同步免费计时与通知**（2026-09-28 用户口径）：开锁成功时若提醒开关关着，
  `ensureFreeReminderEnabled` **顺手把它打开**（本机开锁是服务端确认的骑行开始，比「点扫一扫」
  这个准备动作强得多；开关在「骑行设置」里可见、随时可关；读偏好失败按"开着"处理，宁可不写
  不误开），提示文案带上计时结果。**「点扫一扫」那条路不碰开关**——那只是准备动作。
  另外：页面上的倒计时块只看「有没有在案计时」，**不再要求开关开着**（`timerActive` 里的
  `freeReminderEnabled &&` 已删）——开关只管通知（闹钟 + 常驻通知），一个通知偏好不该把事实
  藏起来。改这条前先想清楚：它同时影响骑行卡折叠（`riding = ride != null || timerActive`）。
- **旧「出码页三段布局与折叠」整套作废**（2026-09-29 结构重构）：出码卡 / 识别条 / 折叠行 /
  弹窗码 / 最近 chips / 使用方式卡 这些组件随 `EbikeQrScreen` / `EbikeQrCard` /
  `EbikeRideSection` 一起删了，页面结构见上面「一个页面，三种状态」。**保留的是与形状无关的
  口径**，别在重构里丢掉：
  - 车号口径是**完整车号**（`EbikeQr.resolveCarNum`），不是旧版的"后三位"。
  - 计时到点由倒计时块的 tick 回调 **`onTimerExpired` 让父级撤卡**（父级 `timerExpired`
    用 `remember(rideStartAt)` 绑起点自动复位）：tick 只跑在块内，**别**改成父级每秒重组整页；
    也**别删这个回调**——删了就留一张「免费剩余 0:00」的僵尸卡。
  - 点「打开微信扫一扫」**先补存相册**（`EbikeUiState.generatedSaved` 为 false 才存，单机用户
    到微信只能靠「相册」选图）：别删，删了就是跳过去发现相册里没有这张码。
  - 「上次 ·XXX」气泡点击 = **回填 + 直接出码**（用户拍板），别再只回填。
- **车行两个动作**（2026-09-29 调整）：行尾只在**账号方式且已登录**时给一枚「开锁」
  （有订单时置灰，仓库层还有在案订单闸兜底）；**整行点击 = 车号填进输入框 + 浮出车辆卡**，
  旧版那枚与整行点击同义的「出码」按钮已删（重复占位），行尾用 chevron 暗示可点。
  进行中的订单不再浮「当前用车」卡——它并进抽屉的骑行态面板。
  - 编排全在 `ui/ebike/KvcxRideController.kt`（`KvcxRideState` / `KvcxAction` /
    `kvcxConfirmDialog` / `rememberRideElapsed` / `startFreeRideNotice`）：**全页共用同一份**。
    别在页面里再实现一遍用车动作——四道闸（二次确认、定位前置、零自动重试、降级官方渠道）
    是责任边界，副本早晚漂移；改口径只动这一个文件。页面只负责确认弹窗、定位权限 launcher
    与展示。
  - 标记坐标来自 `Ride.lat/lng`：**与地图瓦片同基准（GCJ-02），不要再过 `Gcj02` 转换**
    （转了车标会偏几百米）。「当前用车」标记画在簇标记之上、蓝点之下；点它的命中判定要
    **排在最前**（否则被它盖住的簇先接走点击）。
  - **未锁时标记并到蓝点**（2026-09-28）：未锁 = 正在骑，车就在你身边，而服务端坐标是拉取
    那一刻的快照（骑出去几百米后标记还杵在原地，看着像"我的车丢了"）；已锁或锁状态未知 =
    车停在某处，用车位坐标；没有自己的定位时退回车位坐标。卡上对应写「未锁 · 标记随你」。
  - 骑行状态仍然是**进页一次 / 动作后 / 手动刷新**驱动，**不轮询**——地图上的位置是拉取那一刻
    的快照，卡上写「位置 hh:mm」把这件事说清楚，别让用户以为车就在那。
  - 地图页的骑行卡用 `AnimatedContent` 而不是 `AnimatedVisibility`：该处同时具备 BoxScope 与
    ColumnScope，两个作用域版本的 `AnimatedVisibility` 会撞解析（编译期报 cannot be called
    in this context with an implicit receiver）。
- **解锁继续骑**（2026-09-28）：临时锁车后要能在本机解锁——与「重试开锁」是同一条调用
  （`retryUnlock`：查在案订单 → 重发 `greenCarUnlock`），只是文案不同（`resumeRide`）。
  骑行卡动作位按锁状态分流：开锁未确认 → 重试开锁 / 已锁 → 解锁继续骑 / 否则 → 临时锁车。
  别把它们合成一个按钮——文案不同就是不同的用户意图。
- **还车结果卡**（`RideSettledBar`，动作区里，2026-09-29 由 AlertDialog 改过来）：还车后给
  时长 / 费用 / 结算状态 + 「继续找车」。数据取**还车前最后一次拉到的订单快照**
  （快趣没有最终账单接口），卡上写明「金额与结算以快趣小程序为准」——别把快照当账单，
  也别为它去加一个「查最终账单」的轮询。数据口径仍在 `KvcxRideController` 的
  `KvcxReturnSummary`（`settleText` 有单测），文案不要在页面里另写一份。
- **开锁成功的触感走一次性事件**（`EbikeEvent.Unlocked` / `BikeMapEvent.Unlocked`），
  **别用状态计数器**：计数器在"离开页面再回来"时会重放一次震动（实现时踩过）。
- **本机骑行记录**（`ride_records`，DB v16；`RideRecordStore`）：只记**本机用车**这条链路
  （起点/终点/车号/费用都齐）；「点扫一扫 → 在微信里骑」只有猜测值，写进统计是污染，明确不记。
  `startAt` 由 `endAt - durationSeconds` 反推（快趣不给开锁时刻），**别拿它当精确开锁时刻**。
  时长文案用 `RideRecord.durationText`（人话口径），**不是**骑行卡那一套 `00:12` 怪格式。
- **还车点 / 禁停区图层**（2026-09-28，`/v1.0.0/queryZoneList` → `domain/KvcxZones.kt` →
  `BikeMarkerOverlay.drawZones`）：
  - 色相用**官方口径**（还车点 `#D7535D`、禁停区 `#333333`），**透明度压淡一档**——官方
    小程序那个 67% 填充会把底图与车辆标记一起压得看不清。
  - **服务区那层（`servicesiteZoneList`）不要画**：我们已有手绘的校园围栏
    （`BikeNearby.CAMPUS_FENCE`，用户对照官方逐边复核过），语义相同、再叠一层两片蓝糊在一起；
    围栏还兼着「只看本校」的过滤，不能拿它替。解析层直接忽略那个列表。
  - 绘制顺序与官方 zIndex 同序（围栏 → 禁停 → 还车点 → 簇 → 当前用车 → 蓝点 → 中心针）；
    **命中判定按绘制顺序倒序**（当前用车 > 车位簇；**还车点「P」不参与命中**，2026-09-29
    用户口径：它常和聚合圈压在一起，接点击只会挡着选车）。排错层会把压在下面的元素接走点击。
  - **「只看本校」要作用到还车点**（2026-09-28 用户口径）：还车点没有车队归属字段，按
    `BikeNearby.inCampusFence` 判坐标；**禁停区不筛**（安全提示，藏掉更糟）。开关变化走
    `applyZones()` 重算，**不重新请求**（与车辆列表同一口径）；原始图层存在 `fetchedZones`。
  - 「P」图标 15dp（20dp 时比车标还抢眼，用户反馈缩小）；命中半径仍是 24dp，别跟着缩。
  - **还车点要带点位名**（2026-09-30 用户口径「一个个还车点能不能换成具体地点」）：
    `givecarList[]` 顶层有 `name`（实测「教学北大楼左侧」「北大楼」「土建楼」），解析进
    `KvcxParkSpot.name`，展示一律走 `displayName`（空名回落「还车点」，**页面里别再写一遍
    `ifBlank`**）。骑行态的列表（`RideSpotsContent`）用它当标题、距离挪到副行；「P」标记
    不标名字（15 个点同时标名会盖住底图）。这个字段也进了缓存文件（`ZoneCacheStore` 的
    `SpotRow.name`，**必须有默认值**——老版本写的缓存没有这一列，缺默认值会让整份文件解码
    失败，用户升级后白丢一次图层缓存）。
  - **停车点图层缓存**（2026-09-28 用户口径；2026-09-29 收小半径，`ZoneCacheStore` + `domain/ZoneCache`）：
    覆盖半径 **50 米**、新鲜期 **12 小时**、最多 **12 条**、同视野（100 米）写入替换、
    **空结果不写**（可能是"这一带确实没有"也可能是解析兜底，宁勿把"没有"缓存半天）。
    **覆盖半径为什么这么小**：接口回的是"离查询点**最近的 15 个**"，集合极其局部——实测
    中心往东 400 米，真实 15 个里 **13 个**不在原地那 15 个里（挪 100 米就换 5 个）。
    初版取 1 公里，等于拿旧视野的点冒充新视野的图层，用户看到的就是「视图移到有停车点的
    地方，P 却刷不出来」（2026-09-29 报的正是这个，**别再放大这个半径**，
    `ZoneCacheTest` 里有钉死"挪 400 米 / 挪 100 米必须重拉"的回归用例）。
    所以缓存的定位只有两条：**进页把上次那片先摆上**（缓存中心 = 上次的查询中心，与恢复的
    视野重合）+ **几乎没动的微调**；真正的移动一律重新拉（0.2~0.3s，察觉不到），与官方
    "每次 drag-end 都拉"同口径。命中覆盖半径就**立即摆上图层**（进页那一条连登录态都不等
    ——缓存是本地数据）；过期仍先摆旧图层再刷（不空白）；**只有「刷新」按钮 `force` 重拉**，
    拖动停稳 / 定位成功 / 回到校区都吃缓存。官方小程序没有这一层（反查确认），这是本 App
    为"回到原地不用再等"加的取舍，**别把缓存做成"替代接口"**——"用户动作驱动、不轮询"的
    红线不变。缓存文件 `filesDir/bike_zones_cache.json`（**无 token、无账号信息**）；
    文件格式是隐式契约，`ZoneCacheStoreTest` 用临时文件往返钉着（改字段名会让读旧文件
    读空——那是静默回归，页面只会少一层图层，没人会报错）。
  - **「地图缓存」卡与清除**（`ui/ebike/EbikeMapCache`；卡片在出码页辅助区，不是地图页）：
    瓦片库是 **SQLite 不是图片文件**（`cacheDir/osmdroid/tiles/cache.db`；`SqlTileWriter` 的
    连接静态、`onDetach()` 空实现，进程内一直开着），清除**只能** `purgeCache()` 删行 +
    尽力 VACUUM 缩文件——**别改成删文件**：unlink 之后那个库继续从旧 inode 读写，
    用户看着"清了"实际照样命中旧瓦片，空间还要等进程退出才释放。停车点那份删 JSON 文件即可；
    库文件**不存在时什么都不做**（`SqlTileWriter` 的构造器就会建库——为清除反而建出空库，
    卡片当场显示几 KB 占用，像没清干净）。**回滚日志 `cache.db-journal` 也删**（purge 的写事务
    提交成功即证明没有未决事务，真有条热日志 SQLite 打开那一刻就回滚消费掉了；它会以高水位
    留在磁盘上——真机实测 512 KB，留着就是"清了还占半兆"的假象）；**purge 失败时别碰它**
    （那时它可能是唯一能恢复库的东西）。清除只从出码页发起、地图页已退出，没有在跑的事务，
    删日志的时序是安全的。
    目录一律走 `ensureOsmdroidConfiguration` 后的 `Configuration.osmdroidTileCache`
    （**别另抄一份路径**）；占用在进页与 ON_RESUME 各统计一次（瓦片是浏览地图时长的）。
  - 拉取节奏**跟车辆列表同一次用户动作**（进页 / 拖动停稳 / 刷新 / 定位成功后），不额外轮询；
    而且**与车辆列表并行发**（别退回去"等中心结果再发"——白多一个往返），新的一发就取消上一发
    （`zoneJob` + `zoneNonce` 防乱序回包）。
    失败静默保留上一层（图层是装饰，空白比旧值更糟）。**联网**那条要一辆车当 `carNum`
    上下文（官方 `carNum: list[0].name` 同口径），这一带连一辆车都没有时不发——这是接口
    约束不是缺项。**凭证不需要**（2026-10-01 实测，见下条）。缓存那条不受这个约束：
    它本来就是本地数据。
  - **图层两种使用方式都拉，且全程不碰账号**（2026-10-01 两次修订，用户当天报「停车点没了」）：
    这个接口**不需要凭证**——实测不带 `token` 头直接 POST `queryZoneList`
    （`lat`/`lng`/`carNum`/`page`/`rows`）回 `resultCode=1 errorCode=0`、
    `givecarList` 15 条。所以 `KqcxSessionRepository.queryZones` **去掉了 `ensureSession()`**：
    有会话就带上 token、没有就空着发（`KqcxAuthClient.zonesJson` 按 `token.isBlank()` 分流）。
    **别把 `ensureSession()` 加回去**——那会在本机留着凭证时"隐式重登一次"，
    小程序方式（对外承诺不碰账号）因此拿到图层、没有凭证的手机则永远看不到「P」，
    同一个使用方式在两台手机上表现还不一样。
    曾经短暂改成「小程序方式只吃缓存、不发请求」，代价就是默认档的还车点整层消失，
    现在是"请求不带凭证"这个更准的口径；`BikeMapViewModel` 里那层
    `inAppRide` 闸（`fetchZones` / `flushPendingZone` / `reloadZonesForSite` 的
    `if (!inAppRide) return`）**已删，别再按"那一档不该有"加回来**。
    缓存那一层照旧：先吃本地缓存（命中就立即摆上），新鲜就不联网。
    能力表里「还车点 / 禁停区」两档都勾得上，见 DESIGN §3.9。
  - **空图层不许覆盖上一层**（2026-09-29 用户报「加载出来又突然消失」）：解析失败（非 JSON /
    结构不符）与业务错误码都回**空**，旧写法 `fetchedZones = 空` 会把整片「P」抹掉。现在空结果
    直接 return（与「失败静默保留上一层」同一口径）——真没有还车点的校区也走这条，旧图层画在
    它自己的坐标上，视野移过去自然看不见，不比空白差。
  - **「只看本校」开着时图层上下文固定用本校车**（2026-09-29 同一条反馈的第二个成因）：
    这一带最近的车若是隔壁师大的，拿它当上下文会把图层换成师大校区的点，再经围栏过滤 →
    本片「P」全没。现在 `zoneContextOf` 在白名单里挑第一辆本校车（取不到就沿用上一次的上下文）；
    关掉「只看本校」时才用这一带最近的那辆（官方口径 `carNum: list[0].name`）。
  - **图层按「车号所属校区」出，不是按查询点半径**（2026-09-28 实测）：拿本校车号、查询点
    放到 6 公里外，回来的仍是本校那 15 个还车点（`rows=15` 封顶，取该校区里离查询点最近的
    一批，校内几公里内几乎同一批）；换师大车号问同一个点，回的是师大的点 + 师大服务区。
    所以**换校区要用这一带的车号重拉一次**（`reloadZonesForSite`，官方 `loadNearbyElements`
    的 `carNum: list[0].name` 同口径）；**同校区拖动不发第二次**（车号变了、校区没变，
    结果一样），别把这条改成"每次拖动都用车队第一辆重拉"——那是每拖一次多一个请求。
    缓存命中（没联网）也要走这一步：缓存条目不记校区，跨校区命中旧图层靠它纠正。
  - **接口耗时不是瓶颈**（2026-09-28 实测）：`queryZoneList` / `queryNearbyCar` 都是
    0.2~0.4s，9 个点并行 0.45s，瓦片单块 ~0.15s。所以"拖动后慢"只能从**我们自己**身上找
    （防抖、撒点、栅格瓦片的下载与绘制），别去优化服务端。另：`queryZoneList` 实测**不带
    token 也返回数据**（旧结论「要 token」不成立，但当前实现仍只在登录态下拉，**没改口径**）。
- **欠费结清只能把人送到微信**（2026-09-28）：`queryUnPayOrder` 的 `unPayMoney` 显示在还车
  结果卡上（别只判零/非零），欠费时给「去微信结清」按钮。**别试图直达快趣小程序的待支付页**：
  URL Scheme / 短链必须由小程序自己的服务端生成（要它的 appsecret），开放平台拉起小程序要求
  App 与小程序在同一开放平台账号下绑定——都做不到。按钮落点就是微信首页。
  **到了微信要把两条能直达付款页的路写给用户**（2026-09-30 从解包确认，用户实测"进了小程序
  找不到支付入口"）：① 主页的「待支付」横幅（onShow 查一次、60 秒节流、**关过一次冷却 5 分钟**）；
  ② 历史订单里那笔待支付记录（点进去就是支付页）。落点在 `KvcxReturnSummary.settleText` 与
  `openWechatForSettle` 的提示（单测钉住），别在页面里另写一份。
  另外：**没确认到结清 ≠ 欠费**（轮询窗口 ~7.5 秒），文案是「费用结算中或未结清」。
- **本机「未结清」记录要复查**（2026-09-30，用户实测"小程序里付了 0.8 元，App 永远显示未结清"）：
  `settled=false` 只是**还车那一刻**（7.5 秒窗口）没确认到，不是欠费事实。`KvcxViewModel.
  recheckUnsettledRecords` 在进页 / 登录成功各核一次：本机有未结清记录 → 查一次
  `queryUnPayOrder`（`confirmUnpaidSettled(attempts=1)` 复用，不新增接口方法）→ 快趣已无欠费
  → `RideRecordStore.markUnsettledSettled()` 全部翻成「已结清」并提示。
  **判据为什么成立**：快趣同时至多一笔待支付订单（有欠费不让开新车，官方 `unpayBlockCardCheck`
  同款约束），所以"现在无欠费"⇒ 历史上没确认到的都已结清。**前置闸必须保留**：没有未结清
  记录就不发查询（同"查询也要跟着停"口径）；查询失败静默，下次进页再核。
- **编排的依赖收窄**（`KvcxRideSession` / `KvcxSideEffects`）：这不是为了分层好看，是为了
  `KvcxRideControllerTest` 能在 JVM 里假造会话与副作用。**加新的写动作或改闸门顺序时，
  先在测试里补一条**——四道闸只有这一处有测试保护。

## 只读补全：响铃寻车 · 锁状态查询 · 单车详情 · 账户资产（2026-09-30）

- **协议出处**：小程序 V6.0.0 的全量接口清单（PC 微信缓存解包产物 `%TEMP%\kvcoo\unpacked`，
  只读分析；官方 Android APK 是 360 加固，静态提不出接口，只见权限：蓝牙开锁通道 + 高德地图）。
  这四件全部**无计费后果**，挑它们就是因为安全；`KvcxAction` 因此多了 `RING` / `QUERY_LOCK`
  两枚，`kvcxConfirmDialog` 对它们返回 null——**别给这两件加确认弹窗**（它们也不走
  `pendingAction` 那条闸）。
- **响铃寻车**（`greenCarFind`，`KvcxRideController.ringFindCar`）：无参数、作用于**在案订单**。
  本地先挡「没有骑行」（`state.ride == null` 直接提示、不发请求——服务端按当前订单定位车辆）；
  成功文案「已发送响铃，留意身边的提示音」。入口是骑行仪表盘头行那枚 `BellRing`
  （40dp 密集规格，与定位/刷新并排），**别挪进底部动作行**——那一行是锁车 / 还车这两个
  计费动作的位置。
- **锁状态查询不走 `carLockFlag`**：解包确认它在小程序 V6 里**只有定义没有调用**（死导出，
  参数与响应形态无参考，别照接口名猜协议）。权威来源是在案订单的 `lockStatus`——
  `queryLockState` 现查 `queryUnderwayOrder` 并把答案说出来（「车辆已锁上；订单还在，计费继续」
  /「车辆未锁，正在计费」，`KvcxRideControllerTest` 钉住）。UI = 骑行卡锁徽标**可点**，
  查询中徽标原位翻成「查询中…」（不跳位置；busy 复用同一 single-flight）。
- **单车详情**（`queryOneCar` v2，`KqcxSessionRepository.queryCarDetail` → `BikeNearby.parseSingle`）：
  只用于 `focusCar` 在附近列表里找不到目标车时的兜底（`fetchCarDetail`：查到就高亮 + 移镜头 +
  车辆卡带电量，车辆卡**优先认这份查回来的车**）；查不到退回「移动地图或点刷新」旧提示。
  解析口径与附近列表不同：**缺在线 / 启用字段按在线可用算**——用户是拿完整车号点名查这辆车，
  缺数据不该标成失联（`BikeNearbyTest` 钉住；在线/停用字段**明确给了**时照旧生效）。
  token 允许空（免凭证读接口，与 `zonesJson` 同口径，小程序方式也能用）；连点只放一个在飞
  （`carDetailJob`），查不到就停，**不轮询**。
- **账户资产**（`getUserInfo` + `queryUserCoupon` + `queryUserMemberCoupon` 三接口**并行**，
  `KqcxSessionRepository.queryAssets`）：展示在快趣账号页「快趣资产」区（`AssetsSection`），
  **只读**——充值 / 退押 / 买卡是微信收银台流程，App 不碰支付，区标题下一行就把出路说清，
  别让人在这张卡里找充值按钮。失败**保留上次结果**、区块内给一行说明与「刷新」出路
  （资产不是本页主任务，不打扰）；三块全空才算失败（`KqcxAuth.Assets.isEmpty`）。
  字段口径（`KqcxAuthTest` 钉住）：余额 `rechargeBalance` / `giftBalance` 单位**分**、缺数据
  不显示成 ¥0；卡券 `remainFrequency > 1e5` = 官方「无限次数卡」、`freeTime` 是**秒**（展示
  /60 成分钟、0 不显示）、`deviceType` 1=电单车 / 2=单车、`endTime` 取日期段；会员
  `discount×10` = 「X 折」。查询时机在 `KvcxViewModel.refreshAssets`：进页 / 登录成功 /
  手动刷新各一次，先判 `accountMode()`（读原始流，同既有口径）。
- **能力矩阵没有加新 if**：响铃 / 查锁只在骑行仪表盘里出现（那里已经被
  `ride = if (caps.inAppRide) kvcx.ride else null` 收口）；单车详情两档都可用（接口不要凭证）；
  资产区只在快趣账号页（本页入口本身就按模式显隐）。**别为它们在页面里另写一份 `caps` 判断**。
