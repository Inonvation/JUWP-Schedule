# 共享单车 · 地图 · 免费时长提醒

作用域：改快趣出行出码、附近车辆地图、免费时长提醒、精确倒计时时读。规格见 DESIGN §3.9 / §4.23。

## 快趣出行只剩桌面启动意图

- **不再需要 `WRITE_SECURE_SETTINGS`**（2026-09-23 起）：快趣出行的「助手通道」
  （改写系统 `Settings.Secure.assistant` + 反射 `launchAssist` 直达未导出的首页）连同
  `KILL_BACKGROUND_PROCESSES` 权限一起删了，因为内置单车地图（`ui/ebike/BikeMapScreen.kt`）
  已经承担「看车在哪」。现在「打开快趣出行」只剩桌面启动意图一级，打开的是启动页。
  manifest 里保留 `com.kvcoo.go` 的 `queries` 声明仍是必须的，否则包可见性会让
  `getLaunchIntentForPackage` 对已装应用也返回 null。**别把助手通道当漏项加回来**。

## 附近单车地图

- 附近单车地图（DESIGN §3.9/§4.23）的坐标基准是 **GCJ-02**，与高德栅格瓦片同一基准：
  车辆坐标**直接画，不要转换**；唯一要转的是手机定位（WGS84 → GCJ-02），
  唯一实现在 `domain/Gcj02.kt`，漏转或多转一次都会偏出约 500 米。
  刷新只由用户动作驱动（进页 / 拖动停稳 / 点刷新 / 定位成功），**不要加后台轮询**
  ——那是第三方接口，不是自家的。结果只留内存不落盘。
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
  之后一次性落」**——那会让用户干等 9 个请求里最慢的那个。两条路共用
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
  迟到窗口（用户拍板）：提前量那条只要免费时段没结束就补发，结束那条结束后
  5 分钟内仍发（`EbikeFreeRide.END_WINDOW_MS`）；去重键带计时起点，落
  `ebike_free_notified_keys`。`EbikeFreeRideCheckWorker` 的类名也别改
  （老版本排下的周期任务按类名实例化，`KEEP` 策略又不会重排，兜底会永久消失），
  但它的**周期任务只在计时期间排**（15 分钟 = WorkManager 最小周期，`cancelAll` 里撤）：
  别改回「冷启动无条件常排」——那会让没骑车的用户每 15 分钟被冷启动一次进程。
  系统日历那条链路（`data/calendar/EbikeCalendarEvents.kt`、`ebikeFreeEventId`、
  `水贝贝骑行提醒` 标记、日历权限申请）已整套删除，**别加回来**。

- 今日页快趣卡副行在计时中显示「免费剩余 mm:ss」（2026-09-27，`TodayScreen` 的
  `rideSubtitle`）：每秒自刷，到点、或起点被清成 0（结束骑行 / `check` 收干净）落回
  「微信扫一扫开车」。**判据与出码页计时条完全一致**（开关开 + `EbikeFreeRide.isActive`），
  **别改成「有起点就显示」**——同一个计时在两页说法不一样比不显示更糟。起点与开关从
  `Graph.displayPrefs` 直接读，不往 `DisplayPrefs` 合并读模型里加字段（那条 combine 已满员）。

## 精确倒计时

- 「精确倒计时」（DESIGN §3.9，2026-09-24，**默认关**）：`ui/ebike/WechatRentListener.kt`
  （`NotificationListenerService`）识别微信的租车成功通知（关键词「先享后付」），
  把计时起点从「点扫一扫」校准到真正开始计费那一刻。**它要「通知使用权」**——读用户全部
  通知，只能用户去系统设置手动勾选（应用没有 API 能申请），所以默认关、开关旁给未授权提示；
  匹配与窗口口径在 `domain/WechatRentNotice.kt`（四道闸见 DESIGN §3.9）。
  **隐私红线**：只读包名与文本用于当次匹配，**不落盘、不上传**；命中原文的日志只在
  `BuildConfig.DEBUG` 下打（抓真实文案用），**release 一行都不许打**。
  常驻倒计时的起停判据是**起点是否变化**（`EbikeFreeRideService.start`）：起点变了就重新
  `onStartCommand` 重建 tick，起点没变才跳过。**别退回「服务在跑就跳过」**——上一轮没结束
  就换车时 tick 循环握着旧起点，倒计时不会重置（2026-09-24 用户报的 bug，当时是我加的
  `running` 幂等标记惹的）。换车（`startRide`）还要顺手清掉上一轮挂在通知栏的提醒与校准标记。

- 自动结束（2026-09-28）：骑行结束微信再推一条「[先享后付]服务完成通知」（标题与「服务使用
  通知」只差两个字），`WechatRentListener` 识别后走 `EbikeFreeRideReminder.endRideFromNotice`
  自动结束计时（= `endRide` 全套清理 + `burnSavedCodes` 焚码，与 `check` 结束分支同口径）。
  **分流必须先判完成、再判开始**：完成通知正文同样含「先享后付」，`matches` 已排除含
  「服务完成通知」的文本——别把这个排除当冗余删掉，删了短骑行（完成通知落在 5 分钟窗口内）
  会把起点校准到骑行的结尾。`endRideFromNotice` 的幂等靠「起点清零」：微信重复推送完成通知时
  第二次在 `startAt <= 0` 被挡，**不要**再加已结束标记；完成通知窗口是专用的
  `isWithinCompletionWindow`（`COMPLETION_WINDOW_MS` = 20 分钟 = 免费 15 + 结束迟到窗口 5，
  与 `check` 收干净过期计时的视界一致）——**别复用起点校准的 `isWithinWindow`（5 分钟）**，
  正常骑行 routinely 超过 5 分钟，复用它自动结束就只在超短骑行下生效。
