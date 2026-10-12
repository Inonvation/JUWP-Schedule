# 今日页 · 课表网格 · 作息表

作用域：改今日页结构、课表网格几何、课程时间口径、课程配色、作息表时读。规格见 DESIGN §3.3 / §3.5。

## 课程时间只有一条口径

- 课程时间**只有一条口径**：`ScheduleCalculator.courseStartMinutes` / `courseEndMinutes`（自定义时间课以 custom 字段为准）。
  排序、行内时刻、倒计时、进度条、`coursePhase`/`nextCourse`/`dayLastEndMinutes` 全部走它，不要再自己写 `isCustomTime` 分支。

## 今日页结构：焦点卡 + 单时间轴

- 今日页结构：**焦点卡（正在上/下一节）+ 单时间轴**；焦点课从列表里剔除（`TodayUiState.listCourses`），
  同一节课不得两处出现；节次号只在焦点卡出现一次。改版前先读 DESIGN §3.3。

## 顶栏「+」快捷入口（2026-10-02，2026-10-06 加收件箱，2026-10-08 加志愿服务，2026-10-12 加百宝箱）

- 顶栏右上角一枚 `Add01` + 下拉五行：「扫一扫」「付款码」「收件箱」「志愿服务」「百宝箱」
  （DESIGN §3.3 / §3.24 / §3.28 / §3.30）。
  五行**只在 `TodayScreen` 的 `actions` 里组合**，不做常驻按钮——低频动作不跟底部固定区的
  卡片抢位置。有未读消息时「+」图标本体叠红点（`BadgedBox`）+「收件箱」行带未读数，
  数据源是 `Graph.inboxRepository(context).unreadCount`（页面直接订阅，不另开 ViewModel）。
- 「百宝箱」= 图片工具集合（水印 / 拼接 / 菜鸟批量查，`SubpageScreen.TOOLBOX`）。
  **受通用设置「功能 → 百宝箱」开关控制**（`toolbox_enabled`，默认开）：关掉时这一行
  整行不渲染（不是置灰）；页面直接订阅 `Graph.displayPrefs(context).toolboxEnabled`，
  与收件箱未读数同一形态。规则见 `.agents/rules/toolbox.md`。
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
