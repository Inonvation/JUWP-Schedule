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
  启动姿势与二级页恢复见 `nav-window.md`（`MainActivity` 是 `standard`，不是 `singleTask`）。

## 车号口径

- 车号口径（DESIGN §3.9）：输入框接受「1~3 位尾部」与「6~12 位完整车号」两种形态，
  唯一实现在 `EbikeQr.resolveCarNum`，`bikeUrl` 只认完整车号。地图选中的车走完整车号
  那条路（别的车队前缀是 `300000…`，靠尾部三位拼不出正确链接），
  **不要再按 `EbikeQr.TEMPLATE + 尾部` 拼 URL**。

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
