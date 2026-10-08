# 今日页 · 课表网格 · 作息表

作用域：改今日页结构、课表网格几何、课程时间口径、课程配色、作息表时读。规格见 DESIGN §3.3 / §3.5。

## 课程时间只有一条口径

- 课程时间**只有一条口径**：`ScheduleCalculator.courseStartMinutes` / `courseEndMinutes`（自定义时间课以 custom 字段为准）。
  排序、行内时刻、倒计时、进度条、`coursePhase`/`nextCourse`/`dayLastEndMinutes` 全部走它，不要再自己写 `isCustomTime` 分支。

## 今日页结构：焦点卡 + 单时间轴

- 今日页结构：**焦点卡（正在上/下一节）+ 单时间轴**；焦点课从列表里剔除（`TodayUiState.listCourses`），
  同一节课不得两处出现；节次号只在焦点卡出现一次。改版前先读 DESIGN §3.3。

## 顶栏「+」快捷入口（2026-10-02，2026-10-06 加收件箱，2026-10-08 加志愿服务）

- 顶栏右上角一枚 `Add01` + 下拉四行：「扫一扫」「付款码」「收件箱」「志愿服务」
  （DESIGN §3.3 / §3.24 / §3.28）。
  四行**只在 `TodayScreen` 的 `actions` 里组合**，不做常驻按钮——低频动作不跟底部固定区的
  卡片抢位置。有未读消息时「+」图标本体叠红点（`BadgedBox`）+「收件箱」行带未读数，
  数据源是 `Graph.inboxRepository(context).unreadCount`（页面直接订阅，不另开 ViewModel）。
- 「志愿服务」= 问渠页（`SubpageScreen.WENQU`）的近路。问渠在「我的 → 校园服务 → 第三方账号」
  里埋得深，顶栏给一条常驻入口；落点就是那一页，**今日页不加载任何问渠数据**（那一页自己
  管缓存与刷新，见 `.agents/rules/wenqu.md`）。
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

## 可见星期序列

- 可见星期序列以 `ScheduleCalculator.visibleDays` 为唯一来源、`columnOf` 取列下标；
  不要用 `day - 1` 当列号（隐藏周六但显示周日时会错位）。

## 今日页底部固定区 TodayBottomDock

- 趣智校园卡右侧余额点开的是 `ui/qzxy/QzxyEntrySheet.kt`（§3.18）。**关它一律走
  `rememberSheetDismisser` 的 `dismiss(after)`**（去登录 / 完成结算 / 失败详情里的
  「去诊断页」各出口），别退回 `onDismiss()` 直接置 false：弹层会从组合里瞬间抽掉，
  "啪"地消失。状态区那一层 `AnimatedContent` 与用水中徽标的口径见
  DESIGN §3.18「面板的手感与出口」。面板**没有**「打开趣智校园页」按钮（2026-10-04 删：
  整卡本体就是趣智页入口，点余额开面板、点卡身开页面），也别再给面板状态区加
  `heightIn` 下限——待机态内容约 72dp，垫下限会在主动作下方留一截空白
  （用户报过的「弹窗下方空白过多」根因）。
- 今日页底部固定区（快捷方式网格 → **快趣出行码整行卡** → 开水卡）是 `TodayBottomDock`，
  **钉在滚动区下方**、不进 `LazyColumn`；三态（加载/空/有课）共用同一份，别只改一处。
  **水宝宝一卡通卡已于 2026-09-24 自今日页移除**（能力收进生活页，DESIGN §3.13）——
  别按旧描述把它加回来；出行卡右侧「附近单车 ›」直达 `EBIKE_MAP`，右侧动作文本走
  `CardSideActionText`（与开水卡右侧余额同一组件）。整块内容可折叠（把手「江水生活」，
  展开态存 `DisplayPrefs.todayDockExpanded`，默认展开），**折叠动画只做高度、锚点必须选 Top**
  ——锚 Bottom 或再叠一层 slide 都会变形，两个失败版本记在 DESIGN §3.3，别重复试。
  改版前先读 DESIGN §3.3。

## 课程配色

- 课程配色不按课程名哈希取（12 桶内必然撞色），走 `ScheduleCalculator.colorIndexesBySortedName` / `nextColorIndex`。

## 作息表结构版本

- 作息表结构版本存在 DataStore（`DisplayPrefsStore.slotSchemaVersion`）；改作息要同时调 `DefaultData.SLOT_SCHEMA_VERSION` 并给迁移。
