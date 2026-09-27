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
  已经带上的有课表网格、今日页 dock、我的页列表、课表页显示设置面板，提示通道 `AppSnackbarHost`
  在组件内部统一带（调用点不用管）。
  胶囊底色用 `surfaceContainer`（`surface` 与页面底色同色，会读成一条白底栏）；
  选中态只改图标与文字颜色，不加底色块。

## 启动页

- **启动页**（2026-09-24，DESIGN §3.3）：我的 → 通用 → 启动页，默认今日，选项 = 底栏
  Tab 集（今日/课表/生活/我的），存 `DisplayPrefs.startPage`（键 `start_page`）。
  三条别改坏：① **生活页关掉时「生活」不出现在选项里**，且存着的旧值落回今日页
  （`domain/StartPage.kt` 的 `visiblePages` / `effectivePage`，设置页选中态与
  `MainActivity.resolveStartRouteBlocking` 共用同一口径；不把存储值改写成今日——
  生活页开回来旧选择要还在）；② **重启生效**，调用点 `remember { resolveStartRouteBlocking() }`
  按窗口读死一次（比悬浮导航栏的进程级 `floatingNavBarEffective` 细一档），**不要**让
  `startDestination` 跟 Flow 变——Compose Navigation 会按
  `remember(route, startDestination, builder)` 重建整张导航图，用户被弹回起点；
  ③ `StartPage` 只带显示名，`StartPage → Routes.*` 的映射留在
  `MainActivity`（路由字符串的唯一来源仍是 `Routes`，别在 domain 里复制字面量）。

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
