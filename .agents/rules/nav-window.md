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
