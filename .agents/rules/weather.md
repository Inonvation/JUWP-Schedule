# 天气（今日页顶栏提示 · 天气页）

作用域：改天气取数、WMO 码映射、顶栏那行提示、天气设置。规格见 DESIGN §3.29 / §4.46。

## 接口与来源

- 数据源 **Open-Meteo**（`https://api.open-meteo.com/v1/forecast`）：免 Key、免注册、覆盖中国。
  **换源前先读 `WeatherApi` 的 KDoc**——选型是拿「密钥不进 App」换来的，换回要凭据的源
  （和风 / 彩云）就得再解一遍密钥分发问题。
- **CC BY 4.0 要求署名**：详情页脚那行「数据来源 Open-Meteo」不能删；
  新增展示天气的地方（小组件 / 分享图）也要带来源。
- 查询点 = 校区坐标（`WeatherApi.CAMPUS_LAT/LNG`，与 `BikeNearby.DEFAULT_CENTER_*` 同一组数字，
  那边 GCJ-02、这边 WGS84 口径）。**不做 GCJ-02 反算**：天气网格以公里计，几百米差不影响结果；
  要跟随设备定位的话，那才需要先过 `domain/Gcj02`。
- 缓存 30 分钟（`WeatherRepository.DEFAULT_TTL_MILLIS`）：Open-Meteo 按请求数计费口径要求客户端缓存。
  **刷新失败回退旧快照**（宁旧勿空），`load()` 的 `fresh` 标记给页面判「这份可能旧了」。

## 那行提示（今日页顶栏）

- **18:00 分界**：18:00 前讲今天、18:00 起讲明天（`WeatherAdvice.referenceDayIndex`）。
  今日页 30 秒的 tick 会顺带重算，跨过 18:00 那行字自己换——别改成只在进页时算一次。
- 话术优先级钉在 `WeatherAdvice.tip`（正在下雨 > 雪 / 雷暴 > 有雨 > 降水概率 > 雾 >
  紫外线 > 高温 > 低温 > 大风 > 兜底），**新增一条就补一条 `WeatherAdviceTest`**，顺序别随手调。
- 那行字**与次标题同一行**，不是顶栏第三行：M3 的 `TopAppBar` 高 64dp 固定，
  三行在用户关掉「固定字体大小」跟随大字号时会被裁（现有两行已占 44sp）。别再改回第三行。
- 开关 `weather_enabled` **默认关**；关着时今日页**一次网络都不发**（判开关的调用点在
  `TodayViewModel.weatherLine`，别把取数挪到无条件路径上）。

## 取数纪律

- 走 `Graph.weatherRepository(context)` 单例，不要自己 `WeatherRepository()`（缓存会各持一份）。
- **无日志拦截器**（与一卡通 / 电费 / 快趣 / 问渠一致），请求里只有坐标与字段清单。
- 窗口是 `forecast_days=7` + `forecast_hours=24`：逐日 7 天、逐小时只要 24 条。
  别把 `forecast_hours` 删了——那会把 168 条小时数据也拉回来，小时条根本用不上。
- WMO 码 → emoji / 中文短语只有 `WeatherCodes` 一处映射；`isRain` **不含**雷暴码 95–99
  （雷暴有更重的话术，两者不能同时命中）。
- 新增接口字段走「全部可空 + `ignoreUnknownKeys`」的宽容解析（`uv_index_max` 这类会回 null）。

## 天气页的排版纪律（2026-10-11 重构后）

- **首屏骨架、不是空态**：骨架与成品同一套卡片与行高（`lineHeightDp` 换算，别写死 dp），
  数据回来直接替换。**重新进页不闪骨架**——`WeatherRepository.peek()` 里还有缓存就先铺上，
  只静默更新。
- **下拉刷新**：阈值 56dp，`pullToRefresh` 与 `PullToRefreshDefaults.Indicator`
  **必须同值**（写岔 = 图标到位了却没刷新）；容器吃 Scaffold 的 padding，否则指示器
  整条滑入轨迹藏在顶栏后面；指示器最短驻留 650ms（`WeatherViewModel.MIN_REFRESH_MILLIS`）。
  只有用户真的下拉才转指示器——进页与失败重试都不转（那两条走骨架 / 静默更新）。
- **空态外层套可滚动容器**：指示器靠嵌套滚动接手势，纯静态子节点接不住下拉。
- **本页关掉了触底回弹**（`CompositionLocalProvider(LocalOverscrollConfiguration provides null)`，
  2026-10-11 用户报「滑到底部再滑会卡一下」）：31+ 的 stretch 走原生 `RenderNode`，
  回弹期间内容**每帧重新录制离屏图层**，而本页是一条 4 屏高的非懒加载长内容，最贵的那种形态；
  展示型静态页的边到边回弹没有信息量。**别删这个 provider**——删了卡顿回来。
  同类页面（长 `Column + verticalScroll`）也照这条处理。
- 温度区间条是 **Canvas** 画的（条位置 = 该日区间在整周范围里的落点），最窄留一个圆点，
  免得「一天没波动」的区间看不见；别改成按 dp 估偏移的布局写法。
- 逐小时格的图标走**接口逐小时给的 `is_day`**（`WeatherHour.isDay`）选昼夜：`weather_code`
  只说「晴」，夜里照白天给会显示 ☀️（2026-10-11 模拟器上抓到的）。
- 小时条的「现在」= 不晚于此刻的**最后一个**整点（`indexOfLast`），一条都不匹配
  （设备时钟 / 时区与接口口径不一致）时退回第一条——接口给的第一个小时就是取数那一刻的当前小时。
- 地点名是 `WeatherApi.CAMPUS_LABEL` 常量（固定坐标、没有反查地名那一步），
  界面写的就是它；将来要跟随定位，才需要先过 `domain/Gcj02` 再换这个常量。
