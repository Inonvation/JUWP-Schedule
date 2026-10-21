# 今日页 · 课表网格 · 作息表

作用域：改今日页结构、课表网格几何、课程时间口径、课程配色、作息表时读。规格见 DESIGN §3.3 / §3.5。

## 课程时间只有一条口径

- 课程时间**只有一条口径**：`ScheduleCalculator.courseStartMinutes` / `courseEndMinutes`（自定义时间课以 custom 字段为准）。
  排序、行内时刻、倒计时、进度条、`coursePhase`/`nextCourse`/`dayLastEndMinutes` 全部走它，不要再自己写 `isCustomTime` 分支。

## 今日页结构：单列列表 + 就地焦点卡（2026-10-13 改版）

- 课程区 = 区块标题「今日课程 · N 节课」+ 一列 `TodayCourseCard`（三种形态**就地**切换）：
  **未上** = 课程色 16% 底 + 左列节次/时间 + 竖分隔 + 课名 / 地点·教师；
  **正在上 / 下一节** = 现版焦点卡形态（主色 8% 底 + 左缘主色竖条 + 状态行 + 进度条，
  **不带左列节次/时间**），**留在列表原位**（不再单独置顶）；
  **已上完** = 整卡置灰 + 右端「已下课」，**只在「课间」露一次**——正在上的课一开始就收起
  （`endedRecent` 在 `ongoing != null` 时恒为 null）；当天最后一节上完走 `todayAllDone` 的
  「今天的课都上完了」提示，不渲染置灰卡。
- 标题计数 = **`timeline.size`**（列表卡片数，不是 `todayTotal`）——数字必须和看到的卡数对上。
- 左列宽度按当天最宽节次标签**实测取齐**（`rememberSectionColumnWidth`）：写死固定值会让短标签
  右侧空出一大截。
- 状态推导：`domain/TodayState` 的 `timeline`（= `endedRecent? + ongoing? + remaining`）与
  `endedRecent`。**`remaining` / `listCourses` / `focus` 语义不变**——桌面小组件（`ui/widget`）
  仍用后者，别改它们的含义。
- 形态切换全走 `animate*`（底色 / 左缘竖条 / 左列宽度收起 / 状态区高度伸缩），焦点转移不硬跳；
  左缘竖条用 `drawBehind` 画（`IntrinsicSize.Min` 与 `AnimatedVisibility` 的固有尺寸测量不兼容）。
- **远课轻提示（`NextStartHint`）已删**：不再有 60 分钟窗口的「下一节」提示。
- 改版前先读 DESIGN §3.3 的 2026-10-13 段。

## 进行中服务卡（2026-10-21，DESIGN §3.3）

- 位置：**作业卡与收件箱卡之间**，`item(key = "active-services")`；本体 `ui/today/ActiveServiceCard.kt`。
  快趣骑行 / 胖乖开水 / 趣智洗澡 / U净 洗烘谁在进行中谁占一行（一张 AppCard 平铺 +
  `AppCardDivider` 分隔，行进出场 220/160ms），全无整卡不渲染。
- 各行真相源与刷新口径（**零新增常驻网络路径**）：
  - 快趣：`Graph.kvcxRide` 单例（2026-10-21 从 BikeMapViewModel 私有提升；骑行页 / 快趣账号页 /
    今日页服务卡共享同一份状态与事件）。今日页只读 `state` 流；查询 = 进页 / 回页（`ON_RESUME`）+
    下拉各静默一拍，**不轮询**；`ON_RESUME` 走 **60 秒节流**（切 Tab / 二级页返回都会重建组合，
    不挡会反复打扰第三方接口；下拉传 `force=true`）。能力闸 =
    `EbikeUseMode.capabilities().inAppRide`（小程序方式不查在案订单）。
    **小程序方式有 `freeRideStartAt` 行**（2026-10-21 补）：`ebikeRideStartAt` 落盘计时在
    `EbikeFreeRide.isActive` 窗口内时展示「免费时长」倒计时行——小程序方式查不了在案订单
    （无账号 token），但计时是本机动作；行尾「结束计时」给提前还车的出口（确认弹窗后调
    `EbikeFreeRideReminder.endRide`，连带撤提醒与通知）。两行能力闸互斥，不同时出现。
    行内「开锁⇄锁车」：`locked==true` 显「开锁」→ `resumeRide()`；未锁显「锁车」→ `tempLock()`
    （免确认，与骑行页同闸）；悬置 / busy 显「确认中…」；开锁 3 秒冷却内锁车 / 还车置灰显剩余秒；
    已锁车的副行追加「已锁车（计费继续）」（临时锁车 ≠ 订单结束）。
    「还车」走今日页本地的 `KvcxConfirmAlertDialog`（文案唯一出处仍是 `kvcxConfirmDialog`）。
  - 胖乖：**`Graph.qiekjWatering` 落盘会话**（`QiekjWateringStore`，2026-10-21）——`unlock()` 流程
    首次进入 Working 时 `begin`，结算 / 失败 / 165 秒超时收尾 `clear`；5 分钟未清按过期静默清除。
    它是跨窗口的唯一真相源（今日页与开水页各持一份 `WaterViewModel`，`Working` 只在各自 VM 里，
    互相看不见）。「结束开水」只在流程跑在**本窗口** VM 时可用（`waterCanStop`），否则行只展示 +
    点行进开水页（跨窗口结束没有对应的 VM 可调）。行落点 = 开水页。
  - 趣智：`QzxyWateringStore.watering`（落盘）；对账沿用今日页已有 `WateringReconcileEffect`；
    超 1h / 设备确认结束转「待核对」，按钮切「结算核对」。
  - U净：`UjingRepository.orderState`（落盘快照）；对表沿用 10s TTL 节流（进页 / 回页 / 下拉 /
    开弹窗四路同一道闸）；行纯展示点整行进 UJING 页（写操作只在 U净 主页）。
- **订单完成入收件箱**（`InboxKind.KVCX_RIDE / WATER_SESSION / QZXY_SESSION / UJING_ORDER`）：
  挂点全在「数据到手并完成对比」处——快趣在 `postKvcxReturnInbox`（手动 + 自动还车共用，
  **别挪进 `performReturnBike`**：那条被 JVM 单测跑到）；胖乖在流程落 `Success` 处；趣智在
  `handleSettled`（清记账之前取 startedAt 做键）；U净 在 `refreshOrder` 的
  `UjingState.transitToWashDone` 过渡（旧非 50 → 新 50；取消 / 超时不投）。完成只入箱不发
  新通知（快趣自动还车 / U净洗衣完成已有现成通知）。
- 行首呼吸圆点 900ms（同 `WateringBadge` 手法）；计时 `tnum` 等宽 + 每秒（不做 crossfade）；
  行图标用侧栏同款品牌位图 24dp。

## 宿舍报修状态行（2026-10-19；2026-10-21 并入收件箱卡，DESIGN §4.49）

- 位置：**收件箱卡内置顶行**（`InboxTodayCard`，消息行之上；§3.24）；未开学 / 课表为空的
  空态也挂收件箱卡——不能只在有课时才出现。原独立报修卡已删，别加回来。
- 可见性由 `DormRepairRules.cardRecord` 决定：有在途优先（最新提交），没有在途才显示
  3 天内已完成且未被用户隐藏的；完成 3 天后自动不渲染（快照仍保留，收件箱可回查）。
- 行内只展示状态 / 宿舍 · 故障 / 当前节点或完成时间，**两行**：第一行「宿舍报修 · 状态 · 当前进度」
  （当前节点 / 完成时间 / 提交时间跟在状态后面同一行，状态词走语气色，与详情页英雄卡同色系），
  第二行「宿舍 · 故障」；姓名、学号等个人字段解析层已丢弃，**不要**为了「信息更全」把它们加回卡片。
- 数据源：`Graph.dormRepairStore(context).snapshot`（进程级单例）。自动检查在
  `MainActivity.onResume` → `DormRepairReminder.checkOnAppOpen`：30 分钟内不重复请求，
  自动 CAS 登录最多 12 小时一次；今日页下拉刷新顺带 `Graph.dormRepairSync(context).refresh()`，
  与课表刷新并行、不阻塞。
- 点整行进 `SubpageScreen.DORM_REPAIR` 原生详情页；「去学工报修页核对」才开 WebView。
- **在途状态不写 `inbox_messages`**（与办结消息区分）：收件箱只收「发生过的事实」，
  在途是随时刷新的当前态，渲染层直接消费快照（见 `.agents/rules/inbox.md`）。

## 顶栏「+」快捷入口（2026-10-02 建；2026-10-21 收口）

- 顶栏右上角一枚 `Add01` + 下拉三行：「扫一扫」「付款码」「百宝箱」（DESIGN §3.3 / §4.47）。
  「收件箱」行与「+」角标 2026-10-21 删（展示入口在收件箱卡）；「志愿服务」一行
  2026-10-13 删除——问渠在生活页是整行入口卡（§3.13），顶栏不再重复。
  三行**只在 `TodayScreen` 的 `actions` 里组合**，不做常驻按钮——低频动作不跟底部固定区的
  卡片抢位置。
- 「百宝箱」= 图片工具集合（水印 / 拼接 / 菜鸟批量查，`SubpageScreen.TOOLBOX`）。
  **受通用设置「功能 → 百宝箱」开关控制**（`toolbox_enabled`，默认开）：关掉时这一行
  整行不渲染（不是置灰）；页面直接订阅 `Graph.displayPrefs(context).toolboxEnabled`。
  规则见 `.agents/rules/toolbox.md`。
- **「志愿服务」一行 2026-10-13 已删**（问渠在生活页是整行入口卡，见 `.agents/rules/life-power.md` /
  `.agents/rules/wenqu.md`）。落点就是那一页，**今日页不加载任何问渠数据**（那一页自己管缓存与刷新）。
- 扫一扫取景走 `EbikeScanActivity` 的 **`MODE_RAW`**（原文模式），**去处只有一个判据**：
  `domain/QuickScan.kt` 的 `quickScanTargetOf`，三档按顺序判：车身码进 `RIDE`
  （`focusItemId` = 完整车号）→ 表里认得出的**别家码**（微信支付 / 微信 / 支付宝 / QQ）
  就地提示不跳页 → 其余原文进 U净。别在页面里另写 `if (车号) … else …`，也别让取景窗口
  自己解析（解析归本地判据 + U净 服务端，见 `.agents/rules/ujing.md`）。
- **别家码那张表只收认得准的**（`FOREIGN_CODES`）：多写一条错的，就是把真机身码判成别人的码，
  比漏拦严重（漏拦只是回到旧行为：跳 U净 后回「未识别到设备信息」）。命中用 `contains`
  而不是 `startsWith`，同一家的域名位置不止一处（`u.wechat.com` / `mp.weixin.qq.com`）。
- **解码格式限定二维码**：显式 Intent 里写 `Intents.Scan.FORMATS` 的**逗号分隔字符串**
  （`putExtra(Intents.Scan.FORMATS, BarcodeFormat.QR_CODE.name)`）。这条读取路径是
  `DecoratedBarcodeView.initializeFromIntent` → `DecodeFormatManager.parseDecodeFormats`，
  它只读 `StringExtra`；**别改成 `putStringArrayListExtra`**（那是老 `IntentIntegrator` 的形态，
  传了等于没设，解码器回到全格式）。
- 空结果 = 取消：静默处理，不弹提示（与骑行页同一口径，2026-09-30 的教训）。
- 送给 U净 的原文走进程内一次性令牌 `UjingScanHandoff`（`SubpageStack.kt`，与
  `WaterAutoStart` 同一纪律）：**不要**改用 `SubpageRequest` 的字段——窗口链恢复会把请求
  重放，等于用户没点又被推进下单页。

## 课表网格导出为图片（2026-10-09；尺寸口径 2026-10-10 改，DESIGN §4.12）

- 入口在课表页分享弹层第 4 行 → 二级页 `SubpageScreen.SCHEDULE_SHARE`（`ui/week/ScheduleShareScreen.kt`）。
  `focusItemId` 复用为「进页预览哪一周」；页内切周是**局部状态**，不动主页面当前周。
- 截图只有一处：`ShareBoard` 根节点上的 `rememberGraphicsLayer()` + `record {}` + `drawLayer`。
  **`drawWithContent` 必须挂在修饰符链最外层**——`record { drawContent() }` 只记录它内部的绘制，
  把 `clip`/`background`/背景图放在它之前，截出来的图底色就是透明的。
- 板 = 可选背景图 + 板头（课表名 / 第 N 周 / 日期范围）+ `TimeRail` + `WeekPage` + 可选脚注；
  **顶栏、底栏、悬浮按钮、Snackbar 一律不进图**。板不画非本周灰态与当前时刻线、空白格不可点
  （`tapBlankToAdd = false`）。
- 尺寸口径：**板 = 课表页的网格在同窗口下的样子**——板宽按**窗口宽**算、行高走与课表页同一份
  `buildGridLayout` 输入（网格可用高 = 窗口高 − 状态栏 − 顶栏 − 底部净空，
  `WeekGridLayout.shareBoardGridHeightDp`）、字号走同一份 `rememberGridTypeSpec`。
  **别退回「按预览区算」**：预览区比课表页的网格区窄（左右留白 + 板内边距）又矮（要装板头 / 选项 /
  按钮），字却是屏幕那一份，图里文字就相对格子偏大、长课名多截一行教室（2026-10-10 用户报障根因）。
  预览按可用区缩放，**缩放只走 `graphicsLayer`**（不改布局）→ 截图仍是板原始分辨率（整屏级，
  1101×2289 @ 1080 屏），别用会改布局的缩放（`size()`/`requiredSize` 组合错一处，预览与导出图就对不上）。
- 出口两条：保存到相册 = `ui/common/BitmapSave.kt`（MediaStore `Pictures/水贝贝`，与单车出码共用）；
  分享 = `data/repo/ScheduleShareStore.kt`（`cacheDir/schedule_share/` + FileProvider 的
  `schedule_share` 根，只留最近 5 份）。
- **不要把这个页面做成弹层（Dialog）**：实测该 Compose 版本下 Dialog 窗口的内容约束比可视区高一个
  状态栏，底部按钮被挤出屏幕，`decorFitsSystemWindows` 开关无效。二级页的 Activity 窗口 insets
  是本项目已经跑通的形态。
- 网格排版参数走 `rememberGridTypeSpec`（`ui/week/GridTypeSpec.kt`）——页面与导出板**必须**同一份，
  两处各算一遍必然漂移。`GridTypography` 要包**整行**（`TimeRail` + `WeekPage`）：课表页就是这么包的，
  时间轴字号按课名倍率预除过，只包 `WeekPage` 会在「课名字号改过」时让时间轴差一个倍率。

## 可见星期序列

- 可见星期序列以 `ScheduleCalculator.visibleDays` 为唯一来源、`columnOf` 取列下标；
  不要用 `day - 1` 当列号（隐藏周六但显示周日时会错位）。

## 今日页底部固定区已删除（2026-10-20）

- `TodayBottomDock` 与它带的全部卡片（`DockQuickEntries` / `DockEntry` / `EbikeCard` /
  `WaterCard` / `QzxyCard` / `DockHandle`）**已整体删除**，连带 `todayDockExpanded` 偏好
  与 `lifeTabEnabled` 开关。入口全部在左侧栏（`.agents/rules/nav-window.md`「左侧栏」），
  **别按旧描述把 dock 加回来**。
- 今日页列表最后一项自己让开悬浮胶囊：`LocalBottomBarClearance` 加在 `LazyColumn` 的
  `contentPadding.bottom`；空态/加载态走 `TodayCenteredShell` 的底部 padding。
- **趣智校园卡右侧余额点开的是 `ui/qzxy/QzxyEntrySheet.kt`**（§3.18），现在从左侧栏
  「趣智校园」一行打开。**关它一律走 `rememberSheetDismisser` 的 `dismiss(after)`**
  （去登录 / 完成结算 / 失败详情里的「去诊断页」各出口），别退回 `onDismiss()` 直接置 false：
  弹层会从组合里瞬间抽掉，"啪"地消失。面板**没有**「打开趣智校园页」按钮之外的整卡入口
  （2026-10-04 删：整卡本体就是趣智页入口），也别再给面板状态区加 `heightIn` 下限——
  待机态内容约 72dp，垫下限会在主动作下方留一截空白（用户报过的「弹窗下方空白过多」根因）。
- 胖乖 / U净 的入口弹窗同理从侧栏打开（`WaterEntrySheet` / `UjingEntrySheet`），
  与今日页曾经的口径一致：取快递开 `ui/common/ShortcutSheet`。

## 课程配色

- 课程配色不按课程名哈希取（12 桶内必然撞色），走 `ScheduleCalculator.colorIndexesBySortedName` / `nextColorIndex`。

## 作息表结构版本

- 作息表结构版本存在 DataStore（`DisplayPrefsStore.slotSchemaVersion`）；改作息要同时调 `DefaultData.SLOT_SCHEMA_VERSION` 并给迁移。
