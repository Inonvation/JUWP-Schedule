# 导航 · 窗口 · 二级页

作用域：改 `MainActivity` / `SubpageActivity` 的启动姿势、二级页过渡、悬浮导航栏、课表页背景图的层级时读。规格见 DESIGN §3.1 / §4.21 / §4.22。

**全局约束：`MainActivity` 保持 `standard` + `alwaysRetainTaskState="true"`（manifest 实况），不要改回 `singleTask`。** 历史上存在过 `singleTask` 版本，clearTop 会销毁二级页，2026-09-23 已改回。

## 课表页背景图铺在外层 Scaffold 之下

存储：全局显示偏好 `TimetablePrefs.bgImage*` 五字段，存在 `view_prefs_json` 里（DESIGN §4.21）。

- 课表页背景图（DESIGN §4.21/§4.22）由 **JuwApp 铺在外层 Scaffold 之下**，按
  `currentRoute == Routes.WEEK` 只画课表 Tab，WeekScreen 的 Scaffold 底色设透明让它透出来。
  **不要**把它挪回课表页内部：Scaffold 的 Surface 会 clip 内容，图铺不到状态栏与底栏后面
  （2026-09-22 实测踩过一次）。状态栏与底栏的 inset 归属同样别改回去：外层 Scaffold 的
  `contentWindowInsets` 必须是 0，顶部由各页顶栏自取 `WindowInsets.statusBars`。
  背景图**不进** `notes_img/`（附件清扫会把它当孤儿删掉），只进 `filesDir/schedule_bg/`，
  同时只留一张；选图后的顺序固定为「写新文件 → 写偏好 → 删旧文件」。

## 悬浮导航栏是真悬浮

- 悬浮导航栏（DESIGN §4.22）是**真悬浮**：`NavHost` 不吃 Scaffold 的底栏 padding，页面内容铺到
  窗口底、被胶囊压住；页面靠 `ui/common/BottomBarClearance.kt` 的 `LocalBottomBarClearance`
  把滚动内容的最后一项顶出胶囊。新页面加底部内容时**要带上这个净空**，否则最后一项会被胶囊压住；
  已经带上的有课表网格、今日页列表、左侧栏内容、设置页列表、课表页显示设置面板，
  提示通道 `AppSnackbarHost` 在组件内部统一带（调用点不用管）。
  胶囊底色用 `surfaceContainer`（`surface` 与页面底色同色，会读成一条白底栏）；
  选中态只改图标与文字颜色，不加底色块。

## 启动页

- **启动页**（2026-09-24，DESIGN §3.3）：左侧栏 → 设置 → 通用 → 启动页，默认今日，
  选项 = 底栏 Tab 集（今日 / 课表 / 教务），存 `DisplayPrefs.startPage`（键 `start_page`）。
  三条别改坏：① 旧版本存下的「生活 / 我的」落回今日页（`domain/StartPage.kt` 的
  `effectivePage`，设置页选中态与 `MainActivity.resolveStartRouteBlocking` 共用同一口径；
  不把存储值改写成今日——以后若恢复入口，旧选择还在）；② **重启生效**，调用点
  `remember { resolveStartRouteBlocking() }` 按窗口读死一次（比悬浮导航栏的进程级
  `floatingNavBarEffective` 细一档），**不要**让 `startDestination` 跟 Flow 变——
  Compose Navigation 会按 `remember(route, startDestination, builder)` 重建整张导航图，
  用户被弹回起点；③ `StartPage` 只带显示名，`StartPage → Routes.*` 的映射留在
  `MainActivity`（路由字符串的唯一来源仍是 `Routes`，别在 domain 里复制字面量）。

## 左侧栏（层叠抽屉，2026-10-20）

- 结构在 `MainActivity` 的 `JuwApp` 里，内容在 `ui/life/SideDrawer.kt`，手势状态在
  `ui/common/StackedDrawerState.kt`。**层级是「主页面在前、侧栏在后」**：
  - 侧栏是 `Box` 里的**静态后层**（300dp 宽、`surfaceContainerLow`、不位移）；
  - 主页面是前层 `Surface`：`translationX = 300dp·p − (1−scale)·宽/2`、`scale = 1 − 0.06·p`、
    圆角与阴影随 `p` 渐显。**位移补的是缩放绕中心少走的那一半**——少了它页面左缘会离侧栏
    差一截；改缩放比例时这条式子要一起改；
  - 根 `Box` 的底色 = 侧栏底色，否则页面缩小后上下露出的留白会是另一种颜色。
- 手势：`Modifier.draggable`（**不要**换回 `detectHorizontalDragGestures`，会和纵向滚动抢手势）
  + 20dp 方向锁 + 32dp 落定阈值 + 临界阻尼弹簧（`StiffnessMediumLow`、无回弹、速度按
  `ω·剩余距离` 封顶）。**`isOpen` 与触感都在动画播完之后才发生**——在松手瞬间翻状态
  会让用户在页面还没动时先震一下（2026-10-20 用户报）。
- 左边缘 ~24dp 是系统返回手势区，从那里起手会直接退出应用（模拟器实测）。应用层绕不开，
  不要把「从屏幕最左起手」写进任何引导文案。
- 侧栏里的 U净 / 胖乖生活 / 趣智校园三张卡是**双命中区**（2026-10-21 用户口径）：
  **行本体直接进对应二级页**（`SubpageScreen.UJING / WATER / QZXY`），**右侧状态 / 余额
  才开入口弹窗**（`UjingEntrySheet` / `WaterEntrySheet` / `QzxyEntrySheet`）；弹窗挂在外壳层
  （侧栏只有 300dp 宽，挂里面会被裁）。U净 无登录 / 无收藏店时右侧不显示状态，
  弹窗入口随之隐藏。侧栏展开时余额静默刷新一轮（60 秒节流，MainActivity），
  U净 空闲摘要单独拉（30 秒节流，SideDrawer）。
- **服务进行中行描边高亮**（2026-10-21）：快趣 / 胖乖 / 趣智 / U净 四行在对应服务进行中
  （骑行或免费计时 / 开水 Working / 用水记账 / 洗烘在案）时，`DrawerRow` 的 `active` 参数
  点亮——主色描边 + 6% 主色底，180ms 过渡（`AppCardDefaults.HighlightTransitionMillis`，
  与 `AppCard.highlighted` 同族）。标记由 `ServiceActiveFlags` 从壳层（MainActivity）传入：
  各真相源与今日页「进行中服务卡」同源（快趣单例 state + 免费计时落盘 / WaterViewModel
  Working / `QzxyWateringStore` / `UjingOrderStore`），**两处展示不另立判据**；侧栏只消费
  布尔，零网络请求。
- **点普通入口不收侧栏**（2026-10-21 用户口径）：二级页 / 设置是独立窗口，直接盖在侧栏上，
  返回后侧栏仍展开（与设置同一姿势）。`openFromDrawer` 里**不要再调用 `drawer.close()`**：
  先收侧栏再推窗口的叠层动画观感别扭。只有「动作」类出口仍先收：扫一扫、
  充值（选择完由壳层 `RechargeHost` 直接弹对应充值弹层，不再导航到页面）。
- **侧栏身份区（头像 + 姓名 + 班级）可点，进 `STUDENT_INFO`**（2026-10-21）：该入口从
  教务 Tab 的服务列表挪过来；扫一扫是组内独立按钮，不包进这块点击区。
- 充值：选择弹层选完 → `pendingRecharge` → 壳层 `RechargeHost` 直接弹对应充值弹层；
  生活页已删，不再有 `Routes.LIFE` 或页面级充值宿主。

## 设置是独立窗口（2026-10-20）

- `SettingsActivity`（manifest 已注册，竖屏、`adjustResize`），入口是左侧栏左下角齿轮。
  主窗口**没有**「我的」路由了（`Routes.ME` 已删）——此前它作为隐藏路由时，返回栈 /
  状态恢复会和侧栏、底栏互相干扰。
- 设置页里的所有二级入口仍走 `SubpageActivity`，与主窗口同一套。
- 登录失效通知原本落「我的」，现在 `ROUTE_ME` 直接开 `SubpageScreen.ACCOUNTS`
  （那才是这类通知要人处理的地方）。

## 从桌面图标回到 App 要落在离开时那一页

- **从桌面图标回到 App 要落在离开时那一页**（2026-09-23，DESIGN §3.1）：`MainActivity` 保持
  standard + `alwaysRetainTaskState="true"`，**不要**改回 `singleTask`（它 clearTop，会把二级页
  销毁，用户只能落到今日页）。桌面点击时系统会多压一个实例，由 `MainActivity.onCreate` 那条
  「`!isTaskRoot()` + `action=MAIN` + `category=LAUNCHER` → `finish()`」让它不上屏就退出，
  下面那套窗口原样露出——二级页**不重建**，这才是窗口保活。
  `SubpageStack` 只是兜底（某 ROM 真走 clearTop、页面已被销毁时才按链重建），别把它当主路径，
  也**别把记账挂到 `onDestroy`**：clearTop 不走 `finish()`，挂上去记录会被一起清掉。
  通知的 `PendingIntent` 必须指向 MainActivity 跳板（`subpageLaunchIntent`）而不是
  `SubpageActivity`，且带 `NEW_TASK|CLEAR_TASK`；小组件点击同理——`SINGLE_TOP|CLEAR_TOP`
  在 standard 上匹配不上显式 intent，清不掉二级页。

## 二级页过渡分两套

- **二级页过渡分两套**（2026-09-23，DESIGN §3.1，实现在 `WindowTransitions.kt`）：
  API 34+ 由窗口自己 `overrideActivityTransition` 声明（系统才会把返回手势进度交给它，
  也就是预测性返回的跟手预览），API 33 及以下才用 `overridePendingTransition`。
  **34+ 上调旧 API 会把跟手预览关掉**：它是已废弃 API，调了等于声明「未适配」，
  真机上表现为滑到一半毫无预览、松手才切页。manifest 的 `enableOnBackInvokedCallback`
  别改成 `false`（同一件事的声明）。
  **不动的那一侧要给 `R.anim.stay_still`，不能传 0**（2026-09-26 修：二级页返回没有
  跟手预览）：0 = 「这一侧没有动画资源」，跟手预览要两侧都有可按进度驱动的动画才接力得出来；
  静止动画是 0→0 位移，观感与传 0 一致。
- **App 接管了返回的页面没有系统跟手预览**：AndroidX activity 1.9.3 在 API 34+ 会把
  `OnBackPressedDispatcher` 的回调注册成 `OnBackAnimationCallback`（只要该窗口有启用中的
  回调），系统就不再代播跟手动画，而 `BackHandler` / `NavController` 的返回栈不给动画。
  所以：页内浮层想要跟手就自己用 `PredictiveBackHandler` 驱动（`DisplaySettingsOverlay`
  已改）；笔记·作业详情（未保存时）、教务导入·成绩单（WebView 能后退时）、学工表单、
  首启引导这些接管返回的页面在接管生效期间没有跟手预览，这是平台行为；底栏 Tab 之间的
  NavHost 返回栈同理（要跟手就得放弃「返回回启动页」的栈语义，未改）。

## 二级页里拉起外部应用一律走 startActivityOutsideApp

- **二级页里拉起 App 之外的界面（微信扫一扫 / 微信支付 / 浏览器 / 快趣出行 /
  系统设置页…）一律走 `WindowTransitions.startActivityOutsideApp`**，别直接
  `startActivity`（2026-09-26 修「打开微信后页面跳动」）。根因：二级页声明的
  OPEN 过渡 enterAnim（右推入）在**从外部应用回到本窗口**时会被系统当作「被打开」
  的一侧整个重放，页面凭空再滑一次。只有跨 task 的往返才重放，而微信是
  singleTask、永远开不进调用方的 task——此前「Activity context 不设 NEW_TASK 让
  外部应用留在本 task」只救了浏览器，救不了微信，别再把那条注释当修复加回来。
  该出口在拉起前把本窗口的开/关过渡全部临时换成 `stay_still`，返回过渡放完
  （`SubpageActivity.onWindowFocusChanged(true)`）再还原推入/滑出；启动失败
  （未装微信等）会立刻还原，异常原样抛出，调用方的 `ActivityNotFoundException`
  兜底链不受影响。只有 SubpageActivity 参与抑制——MainActivity 没有过渡覆盖，
  **不能**顺手加（会凭空多一套覆盖、哑掉主窗口自己的预测性返回）。
  已收口的调用点：出码页微信扫一扫/快趣出行、一卡通与生活页充值的
  `launchExternal`、生活页电费深链、作业详情链接、快捷方式执行（设置页测试
  与今日页共用）、`AppPermissions` 两个系统设置跳转。新增外部拉起前先 grep
  `startActivity(` 确认没绕过这个出口。

- **会拉起外部应用的页面，顶栏 insets 用钉住版 `pinnedStatusBars()`**
  （`ui/common/StableInsets.kt`，2026-09-26 补）：同一报障的另一半根因——跨 task 过渡
  期间系统临时改变状态栏可见状态（HyperOS 过渡、微信扫一扫页沉浸式），本窗口收到
  statusBars 高度**瞬时归零**的 insets，贴实时值让位的顶栏跟着上跳再回落。钉住版只认
  更大的值（状态栏高度在窗口活着期间不会真变小），瞬时归零与回落都不落地；对静态
  派发与 `WindowInsetsAnimation` 动画帧都免疫（它在消费端钉，不是在派发端拦）。
  已接入：出码页、一卡通、缴费账单、作业详情、快捷方式、权限设置、附近单车、
  今日页、生活页。**新页面顶栏写 `windowInsets` 前先想清楚它会不会拉起外部应用**：
  会就必须传钉住版；M3 `TopAppBar` 的默认 insets 是实时 systemBars，等于没防。
  代价：横屏这类状态栏高度真变小的形态，顶栏保留竖屏让位高度——宁可多让，
  不跟系统栏的瞬时变化跳舞。

## 悬浮球（应用内快捷入口）

- **悬浮球**（2026-10-13，DESIGN §3.1，`ui/common/FloatingBall.kt`）：主窗口三个 Tab 的
  常驻圆形快捷入口。规格与交互见 `design/03a-ui-shell.md` §3.1；这里记实现口径：
  - **渲染点在壳层 `JuwApp` 的内容 `Box` 里、`CompositionLocalProvider` 之后**
    （画在页面之上），但**不是**外层再套一层 `Box`：Scaffold 的底栏画在内容之后，
    球若与底栏同带会被压住。球的落位靠 `topInset`（状态栏）/ `bottomInset`
    （手势条 + 底栏高，悬浮胶囊 = `FloatingPillHeight+margin`、普通底栏 = 80dp）把
    可拖动区域**顶到底栏之上**，因此不会与底栏重叠——**别把这两个 inset 去掉**。
  - **显示设置面板打开时（`bottomBarVisible` 为 false）球一并收起**，否则会浮在面板上。
  - **位置存归一化分数**（`floating_ball_x/y`，`DisplayPrefsStore` 独立单流，combine 已满员），
    纯几何（分数↔像素 / 夹取）全在 `domain/FloatingBall`，UI 不重算。**位置不吸附边缘**——
    松手停在拖到的任意位置（用户拍板：贴边太丑）。
  - **位置显示走弹簧**（`BallMotion`，`animateFloatAsState`）：拖动平滑跟手、松手原地停住。
    **常态半透明**（`BallIdleAlpha` 0.8，压得住内容又不喧宾夺主）、**拖动时放大 1.08**
    （`BallDragScale`）并提到全不透明。**别再写「松手吸附到最近边缘」**——
    那会「啪」地闪到边上，用户已否掉。
  - **半透明度落在颜色上，不要对整球用 `Modifier.alpha`**（2026-10-19 修矩形阴影）：
    整层 alpha 会生成离屏图层，把阴影裁成节点矩形（球下方一块方块阴影）。改成
    `color = primary.copy(alpha = …)`；图标 tint 保持 `onPrimary` 不透明
    （整层 alpha 时图标也一起被稀释，落到颜色上后若再给 tint 加 alpha 会比原来暗一档）。
  - **阴影自绘，不要用 `Surface` 的 `shadowElevation`**（2026-10-21 修七边形）：
    平台渲染 elevation 阴影会把圆轮廓近似成多边形（七边形），底色又只有 80% 不透明，
    那块阴影面直接透出来（用户报「悬浮球后方下部分一个白色七边形」；AOSP 模拟器同样
    复现，不是 ROM 特性）。现在 `Surface(shadowElevation = 0)`，阴影在 `FloatingBall.kt`
    里用 `drawBehind` + `Brush.radialGradient` 自绘（球内等强度、出球后淡出，
    `BallShadowSpread` / `BallShadowOffsetY` / `BallShadowAlpha` 三个常量），
    写在 `Modifier.scale` 之后随球一起缩放。以后别再把 elevation 加回来。
  - **手势是两个独立 `pointerInput`**（`detectTapGestures` + `detectDragGesturesAfterLongPress`）：
    单击/双击共存意味着单击有一档 ~300ms 判定延迟；长按原地松手会误判成单击，
    靠「拖动结束时刻」挡掉（`TapSuppressAfterDragMs`）。改手势前先在真机上对一遍手感。
  - 双击的扫一扫复用 `ui/common/QuickScan.kt`（车码 / 机身码 / 别家码三档，与侧栏「扫一扫」
    同判据）；「别家码」提示落在壳层 `SnackbarHostState`（页面各自的 Snackbar 够不到壳层）。
