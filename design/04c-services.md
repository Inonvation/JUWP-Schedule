# DESIGN §4.23–§4.27 · 技术架构：校园服务与第三方系统

> 本文档是 [DESIGN.md](../DESIGN.md) 的拆分件。代码注释里的「DESIGN §x.y」章节号一字未改，索引见 DESIGN.md 的映射表。

## 4. 技术架构（续 · §4.23–§4.27）

### 4.23 附近单车接口与地图（2026-09-23，P6；UI 规格见 §3.9）

#### 还车点 / 禁停区图层（2026-09-28，从解包产物对齐）

| 项 | 值 |
|----|----|
| 地址 | `POST /v1.0.0/queryZoneList`（同一 base；官方还车点页 `pages/epark/index` → `Area.loadNearbyAreaPolygons`） |
| 鉴权 | **要 token**（与 `queryUnderwayOrder` 同一套）；未登录不调 |
| 请求体 | `lat` / `lng` / `carNum` / `page=1` / `rows=15`（`carNum` = 以上哪辆车为上下文，官方传选中车或最近一辆车） |
| 响应 | `result.givecarList[]`（还车点：`lat` / `lng` / `name`（点位名，实测「教学北大楼左侧」，2026-09-30 起进列表标题；也进缓存文件的 `SpotRow.name`）/ `scopeArray:[{lat,lng}]` / `device`）、`result.nogoZoneList[]`（禁停区：`scopeArray`）、`result.servicesiteZoneList[]`（服务区大围栏：**我们不画**，见 §3.9） |
| 官方渲染 | 还车点 `fill #D7535Daa / stroke #D7535D 1px` + 图标（callout「驶入这里可以还车」）；禁停区 `#333333aa`；服务区 `#0891e74f` 虚线 5/5 |
| 解析 | `domain/KvcxZones.kt`（宽容取值：数值可能给字符串；坏点丢掉而不是整层失败；解析失败回空图层）。**fixture 逐字段对齐上面的消费代码**（`KvcxZonesTest`） |
| 作用域（2026-09-28 / 09-29 实测） | 还车点 / 禁停区是**按 `carNum` 所属校区**出的，不是按查询点做半径过滤：拿本校车号、查询点放到 6 公里外，回来的仍是本校的还车点；换一辆师大车号问同一个点，回的是师大校区的点 + 师大服务区。所以 `carNum` 是"哪个校区的上下文"，**换校区要换车号**（§3.9「图层跟着这一带的车号走」）。**但"同一校区"不等于"同一批点"**：`rows=15` 封顶，回的是该校区里**离查询点最近的那 15 个**，而集合极其局部——中心往东 400 米，真实 15 个里 13 个不在原地那 15 个里；100 米就换 5 个。这直接决定了缓存覆盖半径只能取 50 米（§3.9「停车点图层缓存」行） |
| 鉴权（2026-09-28 复测） | 实测**不带 token 也正常返回**（与此前「要 token」的结论不符）。当前实现仍只在登录态下拉取（`KvcxSessionRepository.queryZones` 走 `ensureSession`），**没有据此改口径**——要开放给未登录用户时先复测一次再动 |
| 耗时（2026-09-28 实测） | 本机直连 **0.2~0.3s**（22 KB 响应），连续 8 次无节流；同参数几分钟后复测仍是 0.2s。一次测量里出现过 4 连 20.2s 的偶发（同参数后来复测正常），归因为链路偶发、**不是接口特性**——别拿它当"接口慢"的依据 |
| 缓存（2026-09-28，本 App 加的一层） | 官方小程序**没有**图层缓存（反查确认：`service/area/index.js` 只在拖停 / 进页时拉，每次都要 `carNum` 上下文，同样是"用户动作驱动、不轮询"）；缓存是本 App 的取舍，只针对"同一片区域反复问"。落盘 `data/kqcx/ZoneCacheStore`（`filesDir/bike_zones_cache.json`，只存公开图层数据、无 token），策略 `domain/ZoneCache.kt`：覆盖半径 1 公里 / 新鲜期 12 小时 / 最多 12 条 / 同片区替换（250 米）/ 空结果不写；命中即立刻摆上、新鲜不联网，只有「刷新」强制重拉。UI 侧的占用展示与清除见 §3.9「地图缓存管理」 |

#### 接口契约（本地实测，2026-09-23）

| 项 | 值 |
|----|----|
| 地址 | `POST https://api.kvcoogo.com/ManagerApi/api/v1.0.0/queryNearbyCar` |
| 请求体 | `application/x-www-form-urlencoded`：`lat` / `lng` / `deviceType=1` |
| 鉴权 | 无。`token` 头可以不发；上游参考项目写明「实测不校验，随便填」 |
| 响应外层 | `{"errorCode":0,"resultCode":1,"resultMsg":"请求成功","result":{"carList":[…]}}` |
| 车号 | `carNum` → 字符串，9 位数字（实测 `100000652` / `300000604` / `300000080`） |
| 坐标 | `lat` / `lng`，**GCJ-02** |
| 返回量 | 一次最多 20 辆，服务端按距离升序封顶 |

用到的其余字段：`currentPercent`（电量）、`lowBattery`（低电阈值）、`status`（1 = 启用）、
`onlineStatus`（1 = 在线）、`carTypeName`（车型）、`givecarName`（停车点）、
`servicesiteName`（校区）。`sn` / `bluetoothKey` / `bluetoothName` 与出码无关，不取。

**校区字段实测（2026-09-27）**：`servicesiteName` 就是运营方的车队归属——本校车辆全部
是「南昌工程学院」（`servicesiteId=72`；学校已更名「江西水利电力大学」，运营方台账
至今仍是旧名），江西师大车辆全部是「江西师大」（`servicesiteId=67`），无空值。两校
紧挨着，车队坐标最近处相距约 150 米（本校南缘纬 28.6857 / 师大北缘纬 28.6843），都
落在 2 公里展示上限内，不筛就会同屏出现——这是「只看本校」按校区名过滤的直接依据
（§3.9）。

**实测样例**（校园坐标 `(116.0285, 28.6883)`）：返回 20 辆，`servicesiteName` 全为
「南昌工程学院」，坐标全部落在纬度 28.688226–28.688321、经度 116.028456–116.028546
之间，**10 米见方**，`givecarName` 统一是「教学北大楼左侧」。这就是标记必须按停车点
聚合的直接依据：不聚合，画面上只有一个点。

#### 坐标基准（踩过就别再踩）

瓦片（高德栅格）与车辆坐标都是 GCJ-02，**两者之间直接用，不要转换**：车辆坐标画到瓦片上
不需要任何处理，查询中心点也直接发 GCJ-02。

手机定位给的是 WGS84，是这条链路上**唯一**需要转换的地方：
`domain/Gcj02.kt`（纯 JVM，`Gcj02Test`）负责这一步，只做单向 WGS84 → GCJ-02。
漏了它定位点会偏出约 500 米；转两次会变成双重偏移，同样偏 500 米。反向转换没有用途，
不要因为「看起来对称」而补一个。

设备实测（2026-09-23）：接口返回的车辆坐标落在高德瓦片上的对应建筑物正上方，
证明接口消费与产出都是 GCJ-02，`(116.0285, 28.6883)` 这类硬编码中心点也是这个基准。

#### 只看本校与校区围栏（2026-09-27 追加）

- 判定在 `domain/BikeNearby.isOurCampusBike`（纯 JVM，`BikeNearbyTest` 钉住）：
  `isOurCampus`——校区名白名单按关键词匹配「南昌工程学院」「水利电力」（后者兼容
  运营方将来把台账更新成新校名），**空名放行**（缺数据不该把可能是本校的车藏掉）——
  **且** `inCampusFence`（射线法点内判定，PNPOLY）。双条件互为兜底：台账挂错校区
  （师大的车误挂本校名）会被围栏拦下，台账改名或没填会被空名放行加围栏兜住。
- 围栏 25 边形（`BikeNearby.CAMPUS_FENCE`）顶点的来历：**按运营方官方运营区域
  确定**（2026-09-27 用户对照官方小程序逐边指认，同日按真机反馈复核偏差路段）——
  西沿天祥大道、北沿瑶湖西二路、东沿瑶湖西大道、南沿瑶湖西一路。顶点取四条界路
  的**中心线**：从高德 z17/z18 瓦片按"路面色带"逐列提取（浅灰沥青 / 白边线 /
  人行道米黄各自成带，逐列取路面色带中点），四个角点即路口：西北=天祥大道×
  瑶湖西二路（路口信号灯，28.696040,116.027510）、东北=瑶湖西二路×瑶湖西大道、
  东南=瑶湖西一路×瑶湖西大道、西南=瑶湖西一路出环岛处。131 个实测车辆点全部
  在栏内，师大、江西工业职院、外语外贸职院都在栏外。初版曾凭官方截图做相似
  变换配准，四条边整体偏 30~70 米（南边还把"校园南缘路"当成界路、内缩 60 米），
  真机对照一眼露馅——**要改顶点就重新按路网中心线提取，别目测挪**。维护工具：
  `tools/campus-fence/extract_fence.py`（重抓 z17 高清瓦片 → 提四条界路中心线 → 解四个
  路口 → 对账现行围栏与 fixture，顶点距界路 >25 米报警；路口/环岛处取点本身有 ±20 米级
  自由度）。全量实测点 fixture 在 `app/src/test/resources/campus_fence_bikes_20260927.txt`
  （131 点），`BikeNearbyTest` 全量回归。瑶湖西二路
  东段与瑶湖西大道同时是师大活动区域的边界（官方小程序截图佐证），两校围栏以
  此相邻不重叠。**顶点是 GCJ-02**（与车辆坐标同基准），过滤与绘制都直接用，
  不要再转。
- 地图上的围栏由 `BikeMarkerOverlay` 常驻绘制（半透明填充 + 虚线描边，仿官方小程序的
  整片包裹样式；画在最底层，簇标记、蓝点、中心针都压在它上面）；「只看本校」开关只管
  过滤，不管画不画。填充与虚线样式 2026-09-27 按四轮真机反馈调定：深青 primary 从 0.16
  一路加到 0.32、再提亮 30% 成亮青，都被"压暗/太深"打回——问题不在浓度也不在青的深浅，
  最后**换色系为浅蓝**：填充材质蓝 300（#64B5F6）alpha 0.30、描边 #3D8BEF alpha 0.85。
  结论：**地图围栏用浅蓝，品牌深青不往地图上套**。虚线 dash/空 9/7 → 7/4（dp，略密于
  默认，更像"边界"而非行政区实线）。

#### 定位（2026-09-23 追加；连续定位流 2026-09-26 追加）

- `ui/ebike/BikeLocator.kt`：两条路。**一次性取坐标**（点「定位」）只用平台
  `LocationManager`，不引 Google Play Services 的融合定位（国内机型上本来就不可用）。
先看五分钟内的缓存定位（瞬时返回），没有再同时挂网络与 GPS 两条更新，谁先给结果用谁；
地图页总超时 8 秒，写动作收紧到 5 秒。网络定位先到也无所谓：把地图中心落到用户那一片，
粗略坐标就够。
  **连续定位流**（`updates`，页面可见期间收集）让蓝点跟着人走：先发缓存定位，再按 2 秒
  节奏挂同样的两条更新，经 `shouldMoveDot`（位移 ≥ 4 米或精度显著提升才挪，纯 JVM 可测
  `BikeLocatorLiveDotTest`）滤掉双源交替与站定抖动后逐条转 GCJ-02；**跟随态下镜头也跟着
  人走**（`followUser`，位移 ≥ 8 米推一次相机，2026-10-19，§3.9），但**不重查车辆接口**
  （程序性移动被 `CameraSuppressor` 吃掉）。权限缺失等硬失败发一条 Failed 即结束，
  失败提示归一次性定位管。
- **`LocationListener` 的四个方法都要写全**（含 API 29 起废弃的 `onStatusChanged`）：
  它们在 API 30 的 `android.jar` 里才是 default 方法，编译期少写也能过，
  但 26~29 的设备上框架会真的调用，缺实现就是 `AbstractMethodError`。
- 权限：`ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` 一起申请，任一授权即可
  （API 31+ 用户可能只给「大致位置」）。**进页面时没授权就申请一次**（§3.9），
  用 `ebikeLocationAsked` 保证只自动申请一次；被拒之后改由「定位」按钮触发。
  已授权的进页面静默定一次，失败不提示，留在校园中心。
- manifest 里两条定位 `uses-feature` 都声明 `required="false"`：定位是可选能力，
  不让应用商店把「需要 GPS 硬件」当成硬条件。
- 依赖 `android.hardware.location` 的 `isLocationEnabled` 只在 API 28+ 存在，低版本跳过检查。

#### 地图实现要点（osmdroid 6.1.18）

- 依赖 `org.osmdroid:osmdroid-android:6.1.18`（Maven Central，AAR，POM 无 `<dependencies>`）。
  加入后**第一次构建需要联网 resolve 一次**，之后 `--offline` 照常用。
- 瓦片源用 `OnlineTileSourceBase` 自定义：512 像素档声明（源名 `AmapRoadHD`），四个域名
  `wprd0[1-4].is.autonavi.com` 轮询，路径
  `appmaptile?x=&y=&z=&lang=zh_cn&size=1&style=7`，配 `setTilesScaledToDpi(false)`。
  **不要加 `scl=2`**：2026-09-27 试过又撤——它回的是"无注记纯底图"（512px，路网齐全、
  一个地名都没有），真机上用户第一眼就报"名称没了"。当前口径：不带 scl 的 256px 带注记图
  按 512 声明渲染（拉大 2 倍、略有发虚），**注记优先**；要又高清又有注记得换矢量/付费方案，
  栅格这条路没有。该地址实测返回 200 `image/png`。它是高德的非公开栅格接口：不接官方
  SDK、不申请 key，属于灰色用法，页面免责声明要写清；地址失效时地图页降级为纯列表
  （瓦片加载失败不影响列表）。
- 初始化五个坑，按顺序：
  1. `Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", MODE_PRIVATE))`，
     用平台 `SharedPreferences` 而非 `PreferenceManager`，免得为一个可选项拉进 `androidx.preference`；
  2. 先把 `osmdroidBasePath` / `osmdroidTileCache` 指到应用私有目录（`cacheDir/osmdroid`）。
     默认路径指向外部存储，Android 10 起瓦片缓存会静默失效；
  3. `userAgentValue` 设成包名，否则瓦片服务可能回 403；
  4. 瓦片过期时间自己设：高德响应带 `Cache-Control: max-age=3600`，但 osmdroid 6.1.18
     不解析任何缓存头（拆包确认：expires / cache-control 字符串为 0），默认不过期、旧图
     不随时间换新；`expirationOverrideDuration` 设 7 天——高德改了路网或校名，最迟一周跟上。
  5. **缓存体积上限也要自己设**（2026-09-27）：`DefaultConfigurationProvider` 的默认值是
     上限 600 MiB、回收目标 500 MiB（拆包确认），校园尺度用不到这么多，手机存储却被一直
     占着，而 `cacheDir` 在部分 ROM 上并不按需清理。压到 60 / 50 MiB（单张瓦片几十 KB，
     够放几千张），上限与目标留 10 MiB 差，免得刚回收完又触发一次。
  6. **瓦片下载线程数也要自己设**（2026-09-28）：osmdroid 默认 2（`DefaultConfigurationProvider`
     构造里 `bipush 2`，拆包确认），2026-09-27 抬到 4、2026-09-28 抬到 **8**（与 osmdroid 自己的
     文件系统线程默认值同档）。把视图拖到没缓存过的地方时，一屏十几块瓦片是"地图看起来半天
     不出来"的主要来源（实测单块 ~0.15s，并行一批就下完）。
- `MapView` 的 `onResume` / `onPause` / `onDetach` 要在 Compose 里用 `DisposableEffect` 手动转发，
  osmdroid 不认 Compose 的生命周期。
- **瓦片库是 SQLite，不是一堆图片**（2026-09-28 做「清除地图缓存」时拆包确认）：无自定义
  `IFilesystemCache` 时 `MapTileProviderBasic` 在 API ≥ 10 上用 `SqlTileWriter`，库文件是
  `osmdroidTileCache/cache.db`；`mDb` 是**静态**字段、`onDetach()` 是**空实现**——进程内被
  打开过就一直开着。所以清除只能走 `purgeCache()`（`DELETE FROM tiles`）再尽力 VACUUM：
  只删文件是假清除（unlink 后库继续从旧 inode 读写，用户看着"清了"实际照样命中旧瓦片，
  空间也要等进程退出才释放）。第 5 条的上限 / 回收目标对这个库同样生效
  （`SqlTileWriter` 构造里读的就是 `tileFileSystemCacheMaxBytes` / `TrimBytes`）。
- 标记与点选：自绘 `Overlay`，`draw` 里按投影落点画圆与车辆数，
  `onSingleTapConfirmed` 里做命中测试（24dp 半径）。不用 `Marker` 加 `Drawable`，
  省掉一套位图资源。
- R8：osmdroid 没有反射入口，预期不需要额外 keep；但 release 包**必须冒烟到地图页**，
  混淆问题只在运行期暴露。

#### 数据纪律

- 刷新由用户动作驱动：进页一次、拖动停稳一次、点刷新一次。**不做后台轮询**，
  不给第三方接口添压力。
- 两次中心点位移 < 30 米不重复请求；拖动 debounce 250ms（官方零防抖，见 §3.9「查询时机」行）。
- **车辆列表**只留内存，不落盘（车随时被骑走）。**停车点 / 禁停区图层**落盘缓存
  （`ZoneCacheStore`，覆盖半径 1 公里 / 新鲜期 12 小时，命中即用、新鲜不联网，2026-09-28 起）；
  瓦片由 osmdroid 自己缓存（`cache.db`，上限 60 MiB），两者都可从出码页「地图缓存」卡清除。
- 附近车辆查询的内容只有「地图中心坐标 + `deviceType`」，不含任何账号或设备标识
  （图层那条要 token 且带一个 `carNum` 当上下文，见上表，与账号无关）。

#### 参考来源与许可

| 仓库 | 许可证 | 取用范围 |
|------|--------|----------|
| [dlkz/kqcx-bikemap](https://github.com/dlkz/kqcx-bikemap) | **AGPL-3.0** | 只读参考：接口地址、参数、字段名、GCJ-02 基准、聚合必要性的实测现象。**未复用其代码** |
| [Wuwang777/Kuaiqu_RemoteScan](https://github.com/Wuwang777/Kuaiqu_RemoteScan) | MIT | 只读参考：接口契约与「token 不校验」的结论。Node 项目，无 Kotlin 代码可取 |

本仓库为 MIT。kqcx-bikemap 是 AGPL-3.0，抄它的代码会让整个仓库被迫改按 AGPL 发布，
因此只取接口契约这一层事实（地址、参数名、字段名不受版权保护），实现全部自行编写。

#### 测试清单（JVM）

`BikeNearbyTest`：响应解析（数字与字符串两种写法、缺字段、`JsonNull`）、
`errorCode != 0` 归为服务端错误、空 `carList` 返回空列表、车号非数字与坐标越界丢弃、
按停车点聚簇、空停车点名回落网格、停车点名纠错与纯数字占位名、`reanchor` 换参照点后的
距离与顺序、haversine 距离、排序、状态推导、电量偏低标记、距离文案。

`Gcj02Test`：境外与非法坐标原样返回、境内偏移量在 100~1000 米量级、邻近两点转换后
相对距离不变、确定性、国境判定矩形边界、极值不崩。**没有硬编码的"标准答案"坐标对**：
手头没有权威对照表，抄一组网上的数字当断言只会让测试变成橡皮图章。

`EbikeQrTest`：见 §4.18。

#### 阶段拆解

1. DESIGN 章节（§3.1 / §3.9 / §4.18 / §4.23 / §6 / §9）；
2. `domain/EbikeQr.kt` 车号口径改造 + `domain/BikeNearby.kt` + 两个测试类；
3. `data/kqcx/KqcxBikeClient.kt`；
4. `ui/ebike/OsmMapView` + `BikeMarkerOverlay` + `BikeMapViewModel` + `BikeMapScreen`；
5. 接线：`SubpageScreen.EBIKE_MAP`、出码页入口与 Activity Result 回填、删助手通道与两个权限；
6. release 冒烟（含地图页）与真机点验。

### 4.24 寝室电费（新开普缴费平台，2026-09-23，P6）

另一套系统：新开普「移动服务平台」缴费（`charge.juwp.edu.cn`，前端 BladeX + Vue），
与教务、与一卡通（`yktwx.juwp.edu.cn`）都不是同一域，但**登录凭证同一份**——
学号 + 查询密码在两个平台都成立（2026-09-23 实测，Python 侧全链路验证）。

> 2026-10-22 起生活页已删除：下文「生活页电费卡」均指现左侧栏电费卡；
> 「最近流水」段随页面删除，流水只在缴费账单页按需拉取。

| 环节 | 请求 | 说明 |
|------|------|------|
| 登录 | `POST /blade-auth/oauth/token`（根域，不在 `/charge` 下） | form：`username` / `password` / `grant_type=password` / `scope=all` / `logintype=student-sno-queryPassword`；header `Authorization: Basic charge:charge_secret`（前端公开常量）。`access_token` 3599 秒 |
| 项目 | `GET /charge/feeitem/singleFeeitem?feeitemid=181` | 平台只挂一个项目：`181`「房间电费」0.62 元/度，`impl_interface=iECSceneServiceImpl`；`sceneinfo` = 本人绑定房间（`campus:0#…;building:0#9A;room:99999#9Z999`） |
| 读表 | `POST /charge/feeitem/getThirdData` | form：`feeitemid=181` / `type=IEC` / `level=3` + 场景三键（campus/building/room）**缺一不可**；读数在 `map.showData`（中文键，如「当前剩余电量」），房间信息在 `map.data` |
| 流水 | `GET /charge/turnover/personal_data?feeitemid=181&flag=3` | 电费充值/退款记录（`abstracts` 带房间标签）；方向只看 `tranamt` 符号，见下「退款判据」 |
| 场景 | `POST /charge/feeitem/getThirdData` + `type=select&level=<已选层数>` | 逐级取校区/楼栋/房间；本期 App 只用绑定房间，选择器留待需要时做 |

除登录外每个请求都带 `Authorization: Basic …` 与 `synjones-auth: bearer <access_token>`。

坑（2026-09-23 实测，Python 与 App 两侧同一套）：

1. **参数少一个回 `code=500 未知异常，请联系管理员`**（HTTP 仍是 200）：少了
   `feeitemid`，或只给场景三键不给 `type=IEC`/`level`，都会走到这条——别当成平台故障。
2. **业务码 401 藏在 HTTP 200 里**（token 过期），必须读 body 的 `code` 才能重登。
3. **网站上带 `token=` 的分享链接是一次性会话凭证**，过期后连另一个新开普系统也回 401；
   App 不依赖它，每次由学号 + 查询密码现登。
4. **token 只内存持有**，不落盘、不进日志；`PayCodeViewModel` 同款口径。

**退款判据**（2026-09-24 实测，修掉了「充值显示成退款」）：
`refund_flag` **不能用来判退款**——该账号 11 条流水上它**恒为 1**，其中 9 条是明显的
充值（2026-08-25 农行支付 20 元、2026-08-01 农行支付 50 元…）。平台自己的 H5 也从不读它：
`app.7abec7aa…js` 的 `getTurnoverData` 判充值用的是 `list.filter(tranamt > 0)`；
退款是另一条订单流程（`POST /order/addRefundOrder`，入口在 H5 的「退费申请」页），
不在这份列表里。所以**方向只看 `tranamt` 的正负**（负 = 退款），金额统一取绝对值存。

**没有用电量接口**（2026-09-24 实测）：把 H5 bundle 里的统计类路径逐个试了一遍——
`/turnover/mouthAccount` 是一卡通**月消费**（`SUCCESSDATE` + `TRANAMT`）、
`/turnover/app_totalAccount` 是累计缴费、`/turnover/pie_account` 是按月饼图数据（同一批）、
`/turnover/appAccountDetail` 回 500、`/billapply/personal_data` 空列表；
`getThirdData` 换 `type` 试 `IECDetail`/`detail`/`record`/`history` 一律 500。
平台只给**当前**剩余电量，用电量只能本机记录读数后差分（见 §3.13「用电统计」）。

**电表读数表 `power_readings`**（DB v12，`data/local/PowerReadingEntity.kt`）：
`id` / `epochMs`（= 读数自身的 `PowerMeter.fetchedAtMs`）/ `remainKwh` / `priceYuan`
（0 = 未知）/ `roomId` / `roomName` / `source`（`life` / `bill` / `alert`，排错用）。
`(epochMs, roomId)` **唯一索引**是去重键：仓库内存缓存（TTL 2 分钟）把同一份快照重复交出来时，
`OnConflictStrategy.IGNORE` 自然吞掉重复行——所以不必在调用点判「这算不算新读数」。
写入只有 `PowerRepository.snapshot()` 一处（与缓存命中互斥）；读取走
`PowerReadingStore.observeAll()`（Room 响应式，侧栏记一条、账单页立刻重算）。
**`roomId` 与 `roomName` 不能混用**（2026-09-27 加，Room v11 → v12 走 `ALTER TABLE ... DEFAULT ''`）：
`roomId` 是平台的数字内部 id（如 `99999`），只能当去重键与统计分组键；`roomName` 是房号显示名
（如 `9Z999`），只给界面。侧栏电费卡的首屏种子要写房号，所以显示名必须随读数落库
（`PowerModels.snapshotSeedOf`，用途见 §4.24「请求节流」）。

落位：`data/power/PowerClient.kt`（裸 OkHttp，无日志拦截器）、`PowerModels.kt`
（解析 + `pickRemain` + `roomLabelOf`，纯逻辑）、`PowerBill.kt`（账单的月度聚合，纯逻辑）、
`PowerReadingStore.kt`（读数落库与响应式读取）、`PowerRepository.kt`（登录编排 + 读表 +
流水 + 深链 + 内存缓存 + 读数落库），`Graph.powerRepository` / `Graph.powerReadingStore`
单例；用电量推算见 `domain/PowerUsage.kt`；UI 见 §3.13。
低值提醒（电费低于设定金额）复用同一条读表链路，只是频率为每天一次，规格见 §3.13「余额提醒」。

测试：`PowerModelsTest`（真实 fixture：项目/读数/流水解析、**方向按 `tranamt` 符号而非
`refund_flag`**、500 与 401 外壳、剩余电量键回退、`sceneinfo` 容错、token 与时间解析）、
`PowerBillTest`（账单：月份按缴费日期归/退回 `feerange`/
未知月沉底、充值退款分列、明细倒序、月窗口跨年、标签与金额文案）、
`PowerUsageTest`（用电统计：差分、充值折算、退款扣回、单价缺失的两条分支、
算不出的段跳过、跨天均摊、周/月桶与窗口、房间过滤、标签与文案）。

#### 电费充值（2026-09-23 实测打通，Python 侧 9 笔真实下单验证；2026-09-24 补测下单/删单）

**下单**（与一卡通充值同一接口、同一签名算法 `CampusPaySign`，一卡通侧 `YktRechargeSign`
委托它；`appid`/`SECRET_KEY` 为前端公开常量；**2026-09-29 修复入账缺陷后 8 字段**，见下方红框）：

```
POST https://charge.juwp.edu.cn/blade-pay/pay          # 注意路径在根域，不在 /charge 下
     header: synjones-auth: bearer <token>             # 缺了走不通（2026-09-23 实测）
     form:   feeitemid=181  tranamt=<元>  flag=choose  source=app  paystep=0
             synAccessSource=h5
             abstracts=校区-X;楼栋-Y;房间-Z            # 账单摘要行（2026-09-29 补）
             third_party=<getThirdData map.data 原文JSON>  # 入账目标（2026-09-29 补，致命）
             + APP_ID/TIMESTAMP/NONCE/SIGN_TYPE/SIGN
→ {code:200, data:{orderid, paystep:2, payList:[农行支付/电子账户], payExpDate}}
```

> **2026-09-29 入账事故（26 元悬案，本节最重要的教训）**：平台把「收钱」与「入账」设计成
> 两个互不校验的阶段——旧版 App 下单只发前 5 个字段，平台照样建单收钱、支付照常成功
> （`order.status=1`），但入账任务从订单里取不到 `third_party`（含 campusid/buildingid/
> roomid/sroomid 的电表定位 JSON，官方前端 = 读表响应 `data` 原样 stringify），**永远不入账**：
> `order.flag` 停在 `0000000000`（第 2 位 `1`=入账成功 / `2`=入账失败，官方账单详情页同一
> 判据），不报错、不重试、不退钱，只能人工找管理员。实测三笔（09-24 19:47 电子账户 1 元、
> 09-28 11:54 电子账户 24 元、09-28 18:45 农行 1 元）全中。此前 §3.17 把空 `abstracts`
> 归因为「平台偶尔不填、电子账户路径」是误诊——真相是「谁下的单」：App 单全空、官方单全有。
> 修复（`PowerOrderForm.of`，单测钉死 8 键 + 签名覆盖）：下单前**强制新鲜读表一次**（官方页
> 同时序，`force=true` 走 `PowerReadingSource.RECHARGE`），`third_party` = `map.data` 原文
> JSON（`PowerMeter.dataJson`，显式 JSON 编码防嵌套引号转义翻车）、`abstracts` = 读数三段
> 显示名拼装；**读不到 dataJson 或房号直接拒单**（`PowerException.Protocol`），绝不发缺字段
> 表单。协议级验证（2026-09-29）：按新表单真实下单 1 元（不支付），订单上 `third_party`/
> `abstracts` 逐字节一致落库，随后删单。等待步口径同步校准：`order.status=1` 只是**扣款**
> 成功，入账位（`getpayinfo` 的 `order.flag`）单独查、分开报告（`PowerOrderState` /
> `PowerEntryState`），Accepted 步区分「已入账 / 入账处理中 / 入账失败」，入账失败提示找管理员。
> 结果步自 2026-10-07 起把**金额大字**（`AmountHeroText`）放在最上——钱已动的这一屏必须能
> 看到扣了多少（此前金额只在密码步与农行等待步出现，出结果的一步反而没有）。

渠道（`payList`，也是 `GET /charge/pay/mergepay?feeitemids=181` 的返回）：

| payid | 名称 | code | 支付方式 |
|-------|------|------|----------|
| 4 | 农行支付 | `NEWPAYMENTPLATFORM` | **App 内嵌农行手机版收银台**（2026-09-28 打通）：`paystep=2` 返回 `paysubmit`（HTML form，无字段、TOKEN 在 action 的 query 里）→ 取链接交给 `PowerBankPayActivity` 的内嵌 WebView；页面里是「农银快e付」的手机号 → 短信验证码 → 支付密码。**这一跳必须发微信 UA**，见下「农行支付」 |
| 59 | 电子账户 | `ACCOUNT` | **6 位消费密码**（`passwordMap` 乱序键位），扣电子账户余额 |

**农行支付（2026-09-28 打通，App 内嵌收银台，用户拍板）**：平台侧这一步不需要密码
（`payList` 里农行那条 `nopassword=0`，认证全在农行页面），App 只做三件事：

1. `paystep=2 + paytype=NEWPAYMENTPLATFORM + paytypeid=4` 取收银台表单 →
   `PowerPayModels.bankCashierUrlFrom` 从 `paysubmit` 里取 `action`（实测值首尾带空格，
   要 trim），**只认 https + `*.abchina.com`**——这段 HTML 由第三方平台转发，
   白名单是不把任意 URL 交给内嵌页打开的最后一道闸；
2. 链接交给 **App 内嵌页**（`PowerBankPayActivity`，2026-09-28 用户拍板；页面里再没有任何
   本 App 的表单）：手机号 → 短信验证码 → 支付密码全在农行页面里输入，本 App 不读、不存、
   不落盘。顶栏留「用浏览器打开」兜底，加载失败给「重试 / 用浏览器打开」浮层；
3. 弹层落「等待支付结果」步：从内嵌页回到 App 自动查一次单（`rememberResumeTick`）、
   另有手动「检查到账」；只有 `order.status=1` 才进「支付成功，已确认扣款」，没查到只说
   「银行侧回调可能有延迟」——不编成功、也不报成失败。

**平台按 UA 分三支（2026-09-28 实测，`PowerClient.MOBILE_CASHIER_UA`）**——同一单、同一
token，只有请求头 UA 不同：

| 请求 UA | `paystep=2` 响应 |
|---------|------------------|
| 微信内置（含 `MicroMessenger`） | `200` + `paysubmit`＝**手机版收银台** `https://mobile.abchina.com/mpaynew/mpay/index?TOKEN=…`（农银快e付：手机号 → 验证码 → 支付密码） |
| 桌面 / 其它非手机 UA | `200` + `paysubmit`＝网页版收银台 `https://pay.abchina.com/EbusPerbankFront/PaymentModeNewAct?TOKEN=…`（PC 布局、K 宝/安全控件提示，手机上会被裁） |
| 手机浏览器（含 `Mobile Safari` 且无 `MicroMessenger`） | `{"code":6230,"success":false,"data":null,"msg":"处理成功"}`——**空响应，`msg` 还骗人** |

`body` 里的 `userAgent` / `isWX` 字段**不影响**分支（两个字段各测一遍，差别只在请求头 UA）。
6230 那条没有 `data`、文案却写「处理成功」，最容易被误判成「平台没问题、是自己解析错了」。
用户历史上一直是在**微信里**付这笔电费（学校入口在微信里，拿到手的就是手机版收银台），
所以这一跳固定发微信 UA；手机版页面**不依赖微信 JSAPI**（实测 `WeixinJSBridge`/`jweixin`
0 处），普通手机 UA 的 WebView 里渲染正常（真机截图：农银快e付 + 商户 + 金额 + 手机号/验证码）。

**手机版收银台的会话不能自己拼**（2026-09-28 实测，别省这一步）：拿**网页版**那条的 TOKEN
去拼 `mobile.abchina.com/mpaynew/mpay/index?TOKEN=…`，页面回
`{"respCode":"90015","respMsg":"无可用的支付方式，请下载安装我行掌上银行客户端进行支付。"}`
——手机版链接必须由平台按手机版分支现发（同一 TOKEN 只在它自己那条分支里有效）。

**TOKEN 现取现用**：链接里的 TOKEN 是农行侧一次性会话——「重新打开支付页」不是复用旧链接，
而是再打一次 `paystep=2` 取新的（同一单可反复取，实测连取三次均 200）。

**为什么不做「App 自绘表单」**（2026-09-28 用户问过）：那几个步骤确实是页面提供的，但把
它们搬进本 App 等于自己复刻农行的支付接口、让手机号/短信验证码/支付密码经手本 App 的代码
与内存——银行页面的安全控件、短信风控、协议确认全都不在我们的实现范围内，出一次错就是钱。
所以内嵌页只当容器：**不注入脚本、不读输入**，只有加载、缩放、外链转发（掌银/微信 scheme）。

**App 内电子账户支付**（2026-09-23 用户拍板：同意 App 内安全键盘输 6 位）：

> **密码口径（2026-09-24 修正）**：这里的 6 位消费密码 = **登录缴费平台用的那个 6 位
> 密码**（学校只有这一套；此前「与登录密码相互独立」的写法是错的，用户纠正）。
> 报「密码错误」的真因是密文换算方向反了，见下条。
> 密文换算唯一口径是 `PowerPayChallenge.cipherOf`（用户数字 d →
> **d 在 `passwordMap[uuid]` 里的下标**）。

**两个把首版打回去的坑（2026-09-24 实测，都已在代码里收口）**：

| 现象 | 真因 | 修法 |
|------|------|------|
| 输对密码也报「密码错误」 | 密文方向反了：官方键盘第 i 个键**显示** `passwordMap[uuid][i]`、**提交键位下标 i**（`app.7abec7aa…js` 的 `keyUpHandle` 与渲染模板），旧代码提交的是 `table[d]` | `cipherOf` = 数字 d → d 在乱序表里的下标；表非 0-9 双射直接拒绝 |
| 支付时服务端回「订单不存在，请重新预定」 | `paystep=2` 响应的 `orderid` **恒为 null**，旧解析拿 `passwordMap` 的键（uuid）兜底当订单号，于是把 uuid 当 `orderid` 发出去 | 订单号只用下单响应里那一个：`challengeFrom(raw, requestedOrderId)`，`payConfirm` 另有 `orderId` 参数（VM 手上那个最可信），空值直接报「支付会话缺订单号」 |

**`ccctype[0].balance` 的单位是「元」**（2026-09-24 同一时刻两个来源对照：电费平台
`ccctype[0].balance=1`，一卡通 `queryCard` 的 `accinfo[].balance=100`（分），同一个电子
账户）——所以电费充值弹层曾把 1 元显示成 ¥0.01。解析时按 `元 × 100` 折成分，
全项目内部统一用分（`PowerPayChallenge.accountBalanceFen`）。

**订单没了要回金额步**：服务端拒绝且文案含「订单不存在 / 已过期 / 已失效」时
（`PowerPayModels.isOrderGone`），VM 把流程退回金额步并清掉订单号与挑战，不让用户在
密码步反复重输（`订单已过期，请重新提交` 是官方前端同一口径）。

1. `POST /blade-pay/pay`（`paystep=2`、`paytype=ACCOUNT`、`paytypeid=59`、`orderid`）
   → 返回 `data.passwordMap`（键 = uuid，值 = 乱序数字串）与 `data.ccctype`（账户类型，
   电子账户为 `<account>-000` 形态）；
2. UI 输 6 位数字（明文系统键盘 + 6 格点阵，2026-09-24 现状；官方页是乱序键盘，
   第 i 个键显示 `passwordMap[uuid][i]`、**提交键位下标 i**——`app.7abec7aa…js` 的
   `keyUpHandle` 钉死）。App 端换算 = 数字 d → **d 在乱序表里的下标**（
   `PowerPayChallenge.cipherOf`，2026-09-24 修正方向反了导致密码正确也报错的 bug）；
3. 再次 `POST /blade-pay/pay`（同上 + `password=<6位密文>`、`uuid`、`ccctype`）
   → `code=200` 即支付成功；
4. `GET /charge/pay/getpayinfo?orderid=` → `order.status=1` 即「已扣款」（2026-10-07
   订正与实现不符的旧口径：电费充值**不走** `PendingRecharge` 状态机与余额基线轮询，
   支付确认成功后由 `LifeViewModel.confirmPowerPaid` **有限次查单**确认扣款与入账位
   （`order.flag`），扣款与入账分开报告——见本节「入账修复」；余额基线那一套只属于
   一卡通充值 §4.19）。

**电子账户充值（充电子账户的钱包）与正式卡同链路「立即支付」**（2026-09-24 修正）：
两条路统一走 **thirdOrder 下单（302 收银台）**，`yktcard` 属于下单表单（正式卡=6位卡号，
电子账户=accinfo type `<account>-000`）——上一版「电子账户改走 blade-pay」是误诊，见下。
支付一步两条路相同：`paystep=2 + CAMPUSCARD` **不带任何账户字段**（带 `yktcard` 反而报
「未获取到要充值的卡号」，2×2 实测矩阵）。**到账轮询与 `PendingRecharge` 状态机是一卡通
（§4.19）独有**（基线 = 付款前该目标账户余额），电费充值不共用（见上第 4 步订正）。

**到账判定按目标分两路**（2026-09-24 用户实测「电子账户已到账仍显示确认中」修复；
**一卡通 §4.19**，2026-10-07 补「基线以 pending 为准整体覆盖」，见 §4.19）：
充电子账户后**卡余额不动**（钱进 `accinfo` 钱包），旧逻辑只比卡余额 → 永远不成立。
现在：`PendingRecharge` 落库 `targetAccount`（`<account>-000`）与钱包基线
`walletBalanceBeforeFen`；判定走 `domain/YktArrival.walletArrival`（目标钱包当前余额
≥ 基线 + 订单金额），正式卡仍走 `balanceArrival`（卡余额口径，不变）。判定成功后
静默刷一次余额快照，侧栏余额立即更新。流水兜底口径两条路共用。

**服务时间**（2026-09-23 夜间实测+用户拍板；**2026-09-24 白天复测推翻**）：此前
「blade-pay paystep=0 全天可用」的结论是误诊——blade-pay 建的订单无论带不带 `yktcard`，
paystep=2 一律 `400「未获取到要充值的卡号」`（2026-09-24 白天 2×2 实测矩阵证实，
`scripts/out/probe_ykt_pay_matrix*.py`）；thirdOrder 建的订单同一支付请求立即返回 `paysubmit`。
**下单通道恢复 thirdOrder（302 Location），yktcard 属于下单表单**；支付一步不带任何账户字段。
夜间服务时间闸门是否单独存在待复验，不写死时间窗——支付一步仍被服务端业务拒绝时，
App 按服务时间外处理（在 App 内提示，绝不降级跳浏览器，用户拍板）：
`payDirect` 把服务端业务拒绝（`code!=200` 带 msg）抛成 `Protocol`，`rechargeCreate` 识别后
转 **`YktException.NotInServiceTime`**，VM 捕获后**清掉等待态、在 App 内 Snackbar 提示
「当前不在充值服务时间内…」，绝不降级跳浏览器**（用户明确要求）。不写死时间窗——
服务端闸门时段若有变化仍以服务端原话为准。

**取消未支付订单**（2026-09-24 修正）：`POST /charge/order/deleteOrder` **只认 JSON body**
（`Content-Type: application/json` + `{"orderid":"…"}`，带 `synjones-auth` 头）——此前
「实测 500」是发了签名表单，Content-Type 不对即回 500「未知异常」，与 SIGN 无关；改成
JSON 后连 2024 年的过期单也能删（平台**不自动清理** `status=0` 的过期单，实测积压 107 条）。
**触发点只有一个**（2026-09-24 收口）：用户点「电费充值」**打开弹层**时由
`LifeViewModel.preparePowerRecharge()` 清一次（`PowerRepository.cancelAllPendingOrders`：
列 `personal_data?paystatus=0` → 逐单删），`placePowerOrder` 下单前 `join()` 这次清理，
免得清理与下单抢跑；清掉几笔写在弹层里一行小字（页面 Snackbar 会被弹层窗口盖住）。
侧栏刷新**不再**触发清理——未支付单只可能由本流程产生，而那正是要用之前的那一刻；
不做后台轮询，也没有 12 小时闸门（一个触发点就够，多一个就是多 1 + N 条对第三方平台的请求）。

**充值免责声明**（2026-09-29 用户要求，§3.13 与 §4.19 共用一条）：**电费与一卡通的每个
充值入口**（侧栏钱包卡两格、电费弹层内的「去充值电子账户 ›」、我的 → 校园卡页的「充值」）
在打开充值弹层前都要先过 `ui/common/RechargeDisclaimerDialog`：三条声明（只提供官方入口、
先小额并核对账户、严禁违法用途与泄露凭证）+ 勾选「一周内不再提醒」。**首次弹出（从未确认过）锁 5 秒**：两枚按钮置灰、返回/点遮罩无效、
确认键倒数秒数；点「继续充值」或「不同意并取消支付」（2026-09-30 起，原「取消」——
明示不接受协议 = 本次充值就地作罢，绝不进充值流程）都落「已确认」
（`recharge_disclaimer_seen_at`，那是"看过"不是"同意"，不同意过的用户下次充值还会
再弹，只是不再锁 5 秒），之后任何一次弹出立即可关。勾选落 `recharge_disclaimer_suppress_until`（+7 天，
`domain/RechargeDisclaimer` 是窗口/锁时长口径的唯一来源，`RechargeDisclaimerTest` 钉住），
电费与一卡通**共用同一份**——同一件事的同一句提醒，分开存只会让弹窗节奏难预期。
静默期内所有入口直开充值弹层，声明不再弹。

**流程（侧栏电费卡）**：电费充值格 → `PowerRechargeSheet` → 金额步先在两枚 FilterChip 里
**选渠道**（2026-09-28 加农行；下单后不许换，订单与渠道绑定）：
**电子账户** —— 标题行右上角是「电子账户余额」与「去充值电子账户 ›」入口（只在电子账户
渠道出现；说明文案默认灰、余额不足时转红）→ 金额输入（预设 10/50/100，上限 500）→
二次确认（**房间号加粗**：一个房间一个电表，充错就是别人的；房间取不到就不显示数字）→
下单 → 密码键盘 → 到账轮询（读数卡同步刷新）；
**农行支付** —— 金额 → 二次确认（同样加粗房间号）→ 下单 → 取手机版收银台链接 → 打开
`PowerBankPayActivity` 内嵌页 → 弹层落「等待支付结果」步（重开/检查到账，金额行也带房间号）。

**密码步交互（2026-09-24 改版，用户反馈「那个矩形输入框体验太差」）**：**没有输入框**。
6 格点阵本身就是输入位——点它就弹系统数字键盘（键盘仍是系统的），输入直接填格、退格可改；
做法是透明 `BasicTextField`（`decorationBox` 丢掉内部渲染、字色与光标全透明）铺满那条带，
点阵叠在上面但不带点击处理，触摸自然落到输入框。进这一步自动聚焦（不用先点一下），
提交失败自动清空重来（不需要用户按 6 次退格），受理后自动收键盘。
密码步因此也不再受 IME 半高锚点影响（金额步仍有输入框，`ImeAwareModalBottomSheet` 照旧）。

#### 请求节流（2026-09-24）

侧栏电费卡**每次展开**会打一串请求：电费侧是项目详情 + 读表（流水已随生活页删除，
不再随进页拉），一卡通侧是余额两条（`queryCard` + `queryCard?scene=recharge`）
与流水增量同步。
来回切几次 Tab 就是十几条，对第三方平台看就是异常流量。两条链路都收在这一节。

| 项 | 规格 |
|----|------|
| 内存缓存 | `PowerRepository` 给「读数（项目详情 + 读表）」与「流水」各留一份内存缓存，TTL **2 分钟**（`CACHE_TTL_MS`） |
| 谁绕过缓存 | 侧栏电费卡**进页**刷新走缓存（切 Tab 回来不重复请求）；点电费卡、账单页下拉刷新、充值成功后刷新一律 `force = true`。用户明确要看的那一刻必须是新值，省请求只省「没人看的时候」 |
| 未支付单清理 | **只在打开电费充值弹层时清**（`preparePowerRecharge`，1 + N 条），下单前 `join()` 它。不清的代价是新下单 500（平台不自动清过期单），而清理放在「马上要下单」这一刻，正好卡在需要之前，刷新链路一条请求都不多打 |
| 落点 | token 只在内存、不落盘（红线不变）；读数缓存只在内存（进程结束即失效） |
| 流水落盘种子（2026-09-26，2026-10-22 移除） | 生活页删除后不再有「最近流水」段，`PowerHistoryCache` 与 `filesDir/power_history_cache.json` 一并删除。老版本升级上来的孤儿文件由 `AppCacheCleaner` 清缓存时顺手删掉 |
| 读数首屏种子（2026-09-27） | 侧栏电费卡同样起步为空（「—／读取中…」），冷启动要等 登录→项目详情→读表 三条串行请求（用户报「首次进入生活页电费余额还是很慢，手动下拉就秒出」）。种子换成 Room `power_readings` 最新一条（`PowerReadingStore.latest()`，零网络），经 `PowerModels.snapshotSeedOf` 拼成展示用快照顶上：度数、单价、房号、读数时刻都照抄，单价未知就不折算、副行给「上次读数」。仅当卡上还没有数时填（真读数与「刷新失败保留的上次读数」都不许被旧种子盖掉），凭证没开直接跳过。种子不用新建文件、也不写回仓库缓存——同一份读数本就在库里（写入仍只有 `PowerRepository.snapshot()` 一处） |
| 请求并行与登录去重（2026-09-30） | 用户报「下拉刷新要刷新很久」，根因是指示器等的是两条全串行链的较慢者（电费读数链、一卡通余额链，冷 token 各登一次）。三处收口：① `LifeViewModel.refreshPower` 只取电费读数（流水随生活页删除，改由账单页按需拉）；② `CampusCardViewModel.fetchBalance` 正式卡与电子账户两问并行；③ `YktRepository` / `PowerRepository` 登录互斥锁内复用等锁期间别路登好的 token（`staleToken` 只挡刚试废的），并发冷启动两平台各只真登一次。成功路径请求总数不变（冷启动反而少登一次），只是不再串行排队；失败路径会多发那条附加请求（读数挂了流水照样发、卡挂了电子账户照样发——「附加信息取不到不打扰」的既有口径）。详情见 §3.13「三路并发、登录只登一次」 |
| 不做的 | 不加重试（失败即停，防撞风控）、不做后台轮询 |

> **2026-09-24（§4.27）**：电费 token 仍不落盘（3599 秒，落盘收益近零，
> 每次冷启动重登一条请求可以接受）；一卡通那份 70 天的 token 是否落盘见
> §4.27「一卡通 token 是否落盘」。

一卡通侧（2026-09-24 落地，与电费同一节口径）：

| 项 | 规格 |
|----|------|
| 余额缓存 | `YktRepository.cards` 与 `rechargeAccountDetail` 各留一份内存缓存，TTL **60 秒**（`BALANCE_CACHE_TTL_MS`）。侧栏进页传 `force = false`，一次进页从两条请求降到 0 条 |
| 余额的强制穿透 | 点余额卡、侧栏下拉刷新、开启流程验证、**到账判定**（`checkArrivalOnce` / `refreshBalanceSilently`）一律 `force = true`。到账判定吃旧值会把「已到账」永远判成「还没到」，这条比省请求重要；刷新入口有「取数中忽略重复触发」闸，连点不叠请求（2026-10-09） |
| 付款码命中后的余额刷新（2026-10-09） | 一笔付款码消费 = 正式卡余额已确定变化。宿主弹「支付成功」时触发 `CampusBalanceSync.refreshAfterPayment`：作废 60 秒缓存 → `Graph.appScope` 单次强拉 `queryCard`（写回缓存、推桌面小组件）→ `revision` 自增，侧栏 / 设置页的余额快照用 `force = false` 就地重建。**只取一次数**——两个页面的 VM 都活着也不各自强拉（那会变成一次消费 4–6 条请求）；失败静默不重试；同一份余额再交给 `BalanceAlertReminder.evaluateYktBalance` 当场评估一次（付款把余额刷低不必等下一个检查档） |
| 流水闸门 | `YktSyncGate`（进程级单例，10 分钟最小间隔，`MIN_INTERVAL_MS`）。消费流水页与付款码页各建一个 `YktTurnoverSyncer`，闸门必须放在单例里，否则「刚在流水页同步过」对付款码页无效 |
| 流水的强制穿透 | 消费流水页下拉/重试、到账核对（`checkTurnoverArrival`）、**扫码消费检测**（`PayCodeViewModel` 的每轮同步）传 `force = true`。这三处问的都是「刚刚发生了什么」 |
| 付款码不缓存 | 那是一批**一次性凭证**（一次取 10 个，扫完即弃），缓存旧码比多打一次请求更糟。现有口径已是最省：不预取（展开才取）、收起即丢、位图立即回收 |
| 两条轮询的渐进化（2026-10-04） | **扫码消费检测**（`YktPayWatch.pollIntervalMs`：前 30 秒 5 秒、之后 10 秒、2 分钟后 20 秒，15 分钟封顶 ≈54 条）与**充值到账检测**（`YktArrival.arrivalPollIntervalMs`：前 2 分钟 5 秒、之后 15 秒、10 分钟后 60 秒，30 分钟封顶 ≈100 条；「每 3 轮一次流水」的比例不变，间隔拉长后流水自动等比变疏）都改为渐进节奏——轮询本身是功能依据不能砍，但固定 5 秒 × 长窗口是最像机器的请求形态；渐进后快速到账/刚扫完码的反馈精度不变 |
| 不动的东西 | 8002/8003 验证码绝不重试、`NeedCaptcha` 立刻停手、`YktTurnoverSyncer` 的增量翻页逻辑（遇已入库 orderId 即停）一律保持原样 |

#### 未设置寝室与 App 内确认（2026-10-08）

用户报「缴费平台里没确认寝室时，App 无法加载电费，必须先去官方渠道把寝室选好」。
排查与修复（协议事实来自官方前端 `app.7abec7aa….js` + 2026-10-08 探针实测，脚本
`_archive/_probe_power_scene.py`）：

| 环节 | 口径 |
|------|------|
| 判据 | `singleFeeitem.sceneinfo` 为空 = 未确认寝室——**官方 H5 同款判据**（`getData()` 里 `if (t.sceneinfo)`，为空进选择流程）。`view:"choose"` 是页面模板类型、**不是判据**（已绑定样本同样是 choose）；`bindStatus`（已绑定 = 1）只作旁证。`feeitem.flag[4]` = '0' → 该校是级联选择模式（非手输房间名），App 只做级联 |
| 旧行为 | `snapshot()` 走到 `readMeter` 抛「项目没给绑定房间，读不了表」，卡副行原样显示；充值要到下单读表那一步才失败 |
| 现行为 | 新增 `PowerException.RoomNotBound`（`snapshot()` 判 `feeItem.room == null` 即抛）：侧栏电费栏转「**未设置寝室，点此设置**」CTA（值「—」、旧读数房号/时刻一并隐藏、`openRecharge` 闸门改道选择器）；「我的 → 校园卡」新增「寝室电费房间」行（设置 / 改绑的常驻入口，值取本机最新读数房号，零网络） |
| 选择器 | `ui/life/PowerRoomPickerSheet(+ViewModel)`：`type=select&level=N` 逐级取候选（实测口径：校区 2、楼栋 58、单楼栋房间 182——候选 >12 带本地搜索过滤、单选项自动推进）→ 房间选定 `type=IEC` **读表预览**（**不落库**：预览的可能不是本人房间，落库仍只有 `snapshot()` 一处）→ 二次确认弹窗（明说会同步到缴费平台「我的房间」）→ 写一次 |
| 绑定写入 | `POST /charge/sceneBind/add`，**JSON body**：`{"feeitemid":181,"sceneinfo":"campus:0#…;building:0#…;room:99999#…"}`（官方 `$api.post` 未带 `"qs"` → axios 默认 JSON）。`sceneinfo` 拼串唯一口径 `PowerModels.sceneinfoOf`（`code:id#name` 分号相连，与官方 `addSceneInfo()` 逐字一致）。**2026-10-08 同房间重绑实测通过**：HTTP 200 / `code=200`、重拉 `singleFeeitem` 不变；upsert 语义、官方允许改绑（确认文案「修改为」） |
| 纪律 | 写请求只在用户点「确认绑定」后发一次，失败不重试（弹层里错误 + 手动「重试 / 重新绑定」）；不碰订单 / 支付链路；余额提醒（`BalanceAlertReminder`）遇 `RoomNotBound` 静默跳过；debug 包落盘 `filesDir/power_debug/`（`singleFeeitem` / `sceneBind` 原文，两套备份排除已含 `power_debug/`）——判据还没有真实未绑定账号的样本，首个案例到位时逐字段核对 |

钉子：`PowerModelsTest`（sceneRoot / options 解析、脏数据、拼串格式、**空 `sceneinfo` = 未绑定**）
+ `PowerSceneSelectionTest`（改选上游层级清掉更深层旧选择——残留会让「房间」层直接显示旧预览，
2026-10-08 复查轮修）。

### 4.25 导出成绩单（签章管理系统，2026-09-24，P6；UI 规格见 §3.14）

成绩单**不在强智教务里**。强智的 `/jsxsd/kscj/cjcx_list`（§4.15）给的是成绩数据，
要出**带教务处章的正式单据**得走另一个系统：**金格签章管理系统** `jwxyxx.juwp.edu.cn/ptwork/`
（门户应用列表里的「电子签章成绩单」，`taskCode=dzqz`，开发者标注江西金格信安云）。

| 项 | 值 |
|----|-----|
| 单点入口 | `https://eapp2.juwp.edu.cn:9443/cas/login?service=http%3A%2F%2Fjwxyxx.juwp.edu.cn%2Fptwork%2Fcas` |
| 落地 | `http://jwxyxx.juwp.edu.cn/ptwork/mainIndex?isDd=1`，该域唯一 cookie `sid` |
| 页面入口 | 左侧栏「电子凭证 → 电子成绩签章」= `/ptwork/DzqzController/ddqzcjFind` |
| 传输 | **只有 HTTP**（443 实测连接超时）。`network_security_config` 已按 `juwp.edu.cn` 放行明文，无需新增配置 |

**两个接口**（都在 `/ptwork/DzqzController/`，表单提交）：

| 用途 | 请求 | 应答 |
|------|------|------|
| 列表 | `POST ddqzcjList`：`page` / `limit` / `sort` / `order` / `dysj`（空）/ `dytype`（空）/ `cjfs=1` / `xnxq`（可重复，空 = 全部学期） | `[1, { total, pages, pageNum, pageSize, pagePri, list: [ {XNXQID,KCMC,XF,KSFS,KCSX,KCXZMC,ZCJ,ZCJSTR,…} ] }]` |
| 出单 | `POST printStartCj`：`dysj`（= 上一步的 `pagePri`）/ `dytype=1` / `cjfs=1` | `200 application/x-msdownload`，`Content-Disposition: attachment;filename=成绩打印表.pdf`，请求体即 PDF 字节流 |

**四条实测口径**（改之前先看这几条，按直觉改会出错单）：

1. **`dysj`（pagePri）才是权威条件，`xnxq` 被服务端忽略**。故意制造不一致（`dysj` 指
   2025-2026-2、`xnxq` 传 2024-2025-1）导出，出来的仍是 2025-2026-2 的内容。所以只能
   「先列表拿令牌、再带令牌出单」，不能按学期号直接拼请求。故 `printForm` **不传 `xnxq`**。
2. **列表分页不影响出单**。列表只翻出 15 行时，PDF 里照样是全部 16 门：token 编码的是查询条件，
   不是当页数据。导出前只发一次列表请求即可，不必翻页（只有取学期清单才翻）。
3. **令牌可重复使用**，不是一次性；`limit` 无效，服务端固定 15 行一页（`PAGE_SIZE`）。
4. **pagePri 存在不等于有数据**，`total` 才是闸门（下一条）。

**失败形态**（三种，App 各自映射成不同提示）：

| 触发 | 服务端应答 | 处理 |
|------|-----------|------|
| 选的学期没有成绩 | 79 字节 HTML `parent.wzalert('未发现打印内容')` | 本地先拦：`total<=0` 时根本不发第二个请求；真发出去也按无内容报 |
| 令牌解不开 | **60KB 空白模板 PDF**，0 门课，照样带签章注释 | 决不允许落到用户手里：`total` 闸门 + `%PDF` 魔数校验 |
| `sid` 失效 | 302 到 `/ptwork/LoginPage`，跟随得到登录页 HTML（正文有 `name="dsing"` 与「立即登录」） | 抛 `SessionExpired` → UI 请用户重新授权 |

**出单产物**（2026-09-24 拆包核对）：Aspose.Cells for Java v8.5.2 生成、iText 5.4.5 增量签名、
`Producer: www.tosign.cn`；A4 单页双栏，表头含院系/专业/班级/姓名/学号/年级/层次/学籍状态与证件照；
末尾「打印时间 + 学校盖章：」。章**不是普通图片**，是压在「学校盖章：」上的签章注释：
`/FT /Sig` + `/Subtype /Widget`，`Rect[433.5 3.5 546.5 116.5]`，签名证书 CN = 江西水利电力大学，
另有 `/Subtype /Image` 的印章图（「江西水利电力大学教务处成绩专用章」）与一枚验签二维码。
语义提醒：**它是 PDF 注释与数字签名，不是烧进页面的图形**。PDFium 系渲染器（含 pypdfium2）
默认不画注释，用它们截图自检会看到「没盖章」——真机上用 WPS / Adobe 复核。

**交付物与接线**：

| 位置 | 内容 |
|------|------|
| `data/jw/PtworkTranscript.kt` | 契约与纯逻辑：URL/表单构造、`[1,{…}]` 解析、学期归并、失败归类、文件名。不依赖 android.* |
| `data/jw/TranscriptClient.kt` | 两步编排 + [TranscriptTransport] 传输抽象（OkHttp 实现单独一层），`cookie` 由调用方传入 |
| `data/jw/TranscriptClient.kt` 的 `TranscriptCookies` | 从 WebView `CookieManager` 读该域 Cookie。HttpOnly 也读得到：它返回的是请求头原文 |
| `data/repo/TranscriptStore.kt` | 落盘 `filesDir/transcripts/`（先写 `.part` 再改名）、保留最近 10 份、FileProvider Uri |
| `domain/TranscriptHistory.kt` | 保留策略与文件名解析（纯逻辑）：认哪些文件、留哪几份、标签/时间/体积文案 |
| `ui/score/TranscriptHistoryScreen.kt` | 最近导出页（打开/分享/删除/清空），挂 `SubpageScreen.TRANSCRIPTS` |
| `ui/score/TranscriptActions.kt` | 打开/分享两个 Intent 的唯一出处（导出页与历史页共用，避免两份实现漂移） |
| `ui/score/TranscriptScreen.kt` | 四态 UI + 授权 WebView 层 + 打开/分享/SAF 保存 |
| `TranscriptActivity.kt` + manifest | 独立窗口，**锁竖屏**（窗口里有统一认证表单，转屏会销毁 WebView） |
| manifest + `res/xml/file_paths.xml` | FileProvider，authority `${applicationId}.fileprovider`，只开 `transcripts/` 一个根 |
| `scripts/fetch_transcript.py` | 同链路的 Python 版（`--list` / `--term` / `--out`），Kotlin 实现的契约样本 |

**会话与凭证**：不存账号密码。`sid` 由授权窗口（WebView 走一遍 CAS）写进 CookieManager，
后续请求只带 Cookie；`sid` 是进程外持久化的，所以第二次导出往往连授权层都不出现。
这与「教务导入」同一条纪律（§4.4「不要在代码里写死密码」），也是 `JwCredentialStore`
随调课检测下线后不再回头的原因。

**风险，如实记下**：

- CAS 票据与成绩单（含姓名、学号、证件照、全部成绩）都走**明文 HTTP**。学校就这么部署，
  App 端无法兜底，只能把这件事写在导出页上。
- 实测遇到过一次**间歇性连接超时**：连续探测十几轮后 `eapp2.juwp.edu.cn:9443` 连续两次
  `ConnectTimeout`，几分钟后自愈（同期四个相关域与公网对照全通）。疑与短时间多次登录有关，
  但这只是观察，没有证据。所以失败提示要把「网络不可达」与「会话失效」分开，
  别让用户在网络抖动时去重登。
- 服务端没有版本化接口，页面结构一变就得跟着改；所有判定都按**内容特征**（`dsing`、`wzalert`、
  `%PDF`）而不是按 URL，URL 在跟随 302 后会丢。

**两个页面入口的坑**（与本功能的实现无关，但排查时容易被带偏）：
`/ptwork/main`（左菜单「后台首页」）与 `/DzqzController/ddqzcjFind`（「电子成绩签章」）
是同一套表单的两份模板，接口完全一样；但**「后台首页」那份的学年学期下拉是坏的**：
最高只到 2022-2023-2，2020-2021-1 及更早共 37 个学期各重复一次（实测 83 项、去重后 46）。
App 不解析页面的下拉，学期清单从 `ddqzcjList` 自己取，所以不受影响。网页上出单请走
「电子凭证 → 电子成绩签章」。

**测试**：`TranscriptParsingTest`（应答解析与脏数据容错、学期归并、分页换算、魔数、
失败归类、表单字段、文件命名）、`TranscriptClientTest`（假传输下的完整编排：翻页收学期、
MAX_PAGES 兜底、令牌回传、三条失败路径、IOException → 网络错）、`TranscriptHistoryTest`
（保留策略与文件名解析：`.part` 半成品不进列表、标签反推、同秒稳定排序、保留 10 份的裁边、
时间与体积文案）。均不联网。

**保留策略的两个坑**（顺手在 2026-09-24 的自查里修掉）：

1. **`.pdf.part` 同时带两个后缀**：判定顺序必须是「先 `.part` 再 `.pdf`」，否则崩溃留下的
   半成品会被当成一条记录列出来，用户点开就是坏文件。`recent()` 读到半成品直接删。
2. **列表与动作之间有时间差**：新导出一份会触发保留策略删掉最旧的，历史页当时那一屏就是旧数据。
   所以打开/分享前一律用 `TranscriptStore.existingFile` 复核，文件不在就给提示并刷新列表，
   而不是把一个不存在的 Uri 交给阅读器（那会变成系统报错弹窗）。

### 4.26 学工表单：宿舍报修 · 请假（学工系统 WebView，2026-09-24，P6；UI 规格见 §3.15）

宿舍报修**不在教务系统里**，它在**超星智慧学工**（`xgxt.juwp.edu.cn`，出厂 unitId 343962，
学校侧的 unitname 是「江西水利电力大学智慧学工」）。教务页面上的「新版学生工作系统」
是一个跳过去的链接，两边靠统一身份认证打通。

**登录（2026-09-24 实测）**：学工把自己的登录地址写在 `verifyDomain` 应答的
`unitInfo.loginUrl` 里，值是 `https://xgxt.juwp.edu.cn/sfrz/login343962`。跟一下这个地址：

```text
GET https://xgxt.juwp.edu.cn/sfrz/login343962
302 → https://eapp2.juwp.edu.cn:9443/cas/login?service=https%3A%2F%2Fxgxt.juwp.edu.cn%2Fsfrz%2Flogin343962
```

`eapp2.juwp.edu.cn:9443` 就是教务在用的那个 CAS，所以**报修与教务共用一套账号**。
这是「从教务进学工就免登」的全部机制：CAS 认得浏览器里的会话，发张 ticket 把人送回去。

**两套地址别搞混**（排查时最容易走错的一步）：

| 域 | 是什么 | 登录方式 |
|----|--------|----------|
| `xgxt.juwp.edu.cn`（根） | 超星学工平台本体，token 存 localStorage `cx_qmx_token` | `/sfrz/login343962` → CAS |
| `xgxt.juwp.edu.cn/office/` | 超星 **office 表单引擎**，报修表单跑在这里 | cookie `session_oa` + `JSESSIONID` |

根站那套 `/passport/mlogin`（超星手机号 + 学习通密码）是没配统一认证时的兜底入口，
学校实际用的是 `/sfrz/`，所以**不要**照 passport 那页去还原登录流程。

**表单引擎侧的关键出入**（静态分析 `apply.html` 的打包产物所得）：

| 环节 | 接口 |
|------|------|
| 进页先做的登录校验 | `GET /office/user/login/verify`（未登录时应答里带 `loginUrl`，前端自己 `location.replace`） |
| 表单信息 | `/office/data/apps/forms/fore/form/info/preview` |
| 提交前校验 | `/office/data/approve/apps/forms/fore/submit/validate` |
| 正式提交 | `POST /office/data/approve/flow/approve/handle/process`（form-urlencoded） |
| 附件上传 | `/office/upload/v2`，分片走 `initTask` / `calcFileCRC` |
| 流程与审批 | `/office/data/approve/flow/approve/task/list`、`/office/data/approve/apps/forms/fore/user/form/info` |

**直达报修单**（2026-09-24）：申请页 URL 形如
`/office/apps/forms/mobile/apply.html?formType=1&pageEnc=…&aprvAppId=278625&id=278625&isManager=false`。
里面两个十六进制串形状像，来路完全不同，别当成一类：

| 参数 | 来路 | 能不能写死 |
|------|------|-----------|
| `pageEnc` | 服务端下发的表单标识 | 能 |
| `uuid` | 前端现场生成的随机值，提交时才用（`e.uuid = createUUID()`） | 不能，也不必要 |

判据：`uuidUtils.createUUID()` 生成的串第 13 位固定是 `4`，而 `pageEnc` 的第 13 位是 `a`。
另外引擎对 URL 上那个 uuid 的处理只有 `urlParams.uuid = e.uuid || ""`（缺省空串），
整份代码里没有读取点，所以 App 的直达地址不带它。

**顺序仍是硬约束**：报修单匿名可访问，它的登录引导指向超星 passport
（`/passport/mlogin`，手机号 + 学习通密码）。直接开表单会被带到超星账号上，
而学校配的是统一认证。所以只能「先 `/sfrz/`，落到学工域后再进表单页」，
且这条自动跳转只做一次（否则用户点回首页会被立刻弹回去）。
跳转闸门的判据在 `XgUrls.shouldEnterForm`：**在学工域、已离开认证链路、当前不是表单页**。
中间那条最容易被漏掉——`/sfrz/login343962` 也在学工域，CAS 带 ticket 回跳的那一刻
如果抢着往下跳，登录链路会被截断。

**已登记的表单**（`XgUrls.FORMS`）。加一个新的要动两处：清单补一条，UI 层的图标映射补一条。

| 表单 | id | `pageEnc` | `aprvAppId` |
|------|----|-----------|-------------|
| 宿舍报修 | `dorm_repair` | `2a6e9319e7a9ac992f30e587d26f435d` | 278625 |
| 请假 | `leave` | `5f440bbcb35527e35a7c4e7169bcabc2` | 278616 |

两条真实 URL 顺带印证了上面那条判据：两个 `pageEnc` 的第 13 位分别是 `a` 和 `2`，
而它们各自附带的那条 `uuid` 第 13 位都是 `4`。

**为什么不做原生**（这条是决策，不是偷懒）：

1. 提交请求里有一串**服务端下发的动态值**——`pageEnc`、`nodeUniqueId`、`traceId`、
   `formUserData`（整张表单的 JSON 串）、`buttonName`。它们每次发起流程都重新生成，
   只能「先跑一遍官方的发起流程、把值捞出来、再照抄提交」，逆向出来的实现会随官方改版失效。
2. 表单是**按 `pageEnc` 动态渲染**的：字段有多少、什么类型、必填与否，登录后才由服务端下发。
   要原生渲染就得把超星整套控件体系（几十种 compt）复刻一遍。
3. 学工根站的会话一半在 cookie、一半在 localStorage，纯 HTTP 客户端拿不到后半截；
   WebView 两者都有，是唯一不用假装成浏览器的走法。

所以：**跑官方页面，App 只负责把它装进一个窗口、把登录态接上**。填表、附件、提交、
流程查询全部由官方页面自己完成，官方改版我们不用跟。

**免登怎么落到实现上**：CAS 的会话 cookie 由 WebView 的 `CookieManager` 持有，
而 CookieManager 是**全应用共用**的——教务导入（§4.4）登录过一次，学工这边就免登。
App 不需要读密码、不需要伪造登录请求、更不需要新增凭证存储。

**接线**：

| 位置 | 内容 |
|------|------|
| `data/xg/XgUrls.kt` | 表单清单（`XgForm`：id / 标题 / 副标题 / `pageEnc` / `appId` 与直达地址）、地址常量、纯判定（域归属、表单页、统一认证接入点、直达闸门、状态条分档）。不依赖 android.* |
| `ui/campus/XgFormScreen.kt` | WebView 窗口：状态条 + 进度 + 文件选择 + 返回键接管 |
| `XgFormActivity.kt` + manifest | 独立窗口，按表单标识分发、锁竖屏（理由同 §4.4 / §3.14） |
| `ui/jw/JwHubScreen.kt` | 入口：「学工与校历」分组，请假 / 宿舍报修按 `XgUrls` 直达（图标映射留在 UI 层，data 层不带 Compose 类型） |

**风险，如实记下**：

- 学工的会话 cookie（`session_oa`、`JSESSIONID`）在应答里**没有 `Max-Age`**，是会话级的。
  App 进程被杀掉之后是否还需要重新登录，**尚未实测**——只有真机能回答。
  如果实测发现每次都要求重登，解法是让用户在窗口里多登一次（与教务导入同一体验），
  而不是把密码存进 App。
  **2026-09-24（§4.27）**：这条由统一会话层兜住——用户存过一次 CAS 凭证后，
  打开学工表单前 `CasSession.ensureValid` 先保证 CAS 有效，`XgUrls.SSO_LOGIN` 那一跳
  即免密落地；WebView 会话被系统清掉也只是多一次跳转。学工自己的 token 仍不落盘
  （它在 localStorage 里，App 取不出来）。
- **报修表单的实际字段与附件限制尚未实测**（需要登录态）。§3.15 的「一页四件事」是按
  官方移动页的能力写的，等真机跑通再补实测口径。
- `setLayerType(LAYER_TYPE_SOFTWARE)` 这个 MIUI 白屏兜底（§4.4 那个坑）**暂未套用**：
  学工是 Vue SPA，软件渲染会拖慢滚动。真机若出现「DOM 正常但整面白」，再按教务页的做法加。

### 4.27 统一登录与会话管理（2026-09-24 实现，P7；UI 规格见 §3.16）

**目标**：用户输一次学号密码，此后所有相关请求自动续登，不再手登。

#### 现状盘点（2026-09-24，读码得到，非实测）

账号是 3 套，会话是 6 份——这个差额决定了「统一」能做到哪一步。

| 系统 | 域 | 凭据 | 会话载体 | 谁在用 |
|------|----|------|---------|--------|
| CAS 统一认证 | `eapp2.juwp.edu.cn:9443`（含门户 `portal`） | 学号 + 统一认证密码 | TGC cookie | WebView（教务导入 / 学工 / 签章授权） |
| 教务（强智） | `jiaowu.juwp.edu.cn:8080` / `:81` | 经 CAS 免密 | `JSESSIONID` + `bzb_njw` | WebView 注入 JS 抽取 |
| 学工（超星） | `xgxt.juwp.edu.cn` | 经 CAS 免密 | **localStorage 里的 token** + `session_oa` | WebView（`XgFormScreen`） |
| 签章成绩单（金格） | `jwxyxx.juwp.edu.cn` | 经 CAS 免密 | cookie `sid` | WebView 授权 + OkHttp 取数（§4.25） |
| 一卡通（synjones） | `yktwx.juwp.edu.cn` | 学号 + 查询密码 | OAuth2 token（6047999 秒 ≈ 70 天） | OkHttp 直连（§4.19） |
| 缴费平台（同供应商） | `charge.juwp.edu.cn` | 与一卡通同一份凭证 | **另一个** OAuth2 token（3599 秒） | OkHttp 直连（§4.24） |
| 胖乖生活 | `userapi.qiekj.com` | 手机号 + 短信验证码 | token | Retrofit（§4.10） |

**三个必须先讲清的事实**：

1. **一卡通与缴费平台各自登录、token 不通用**（两个微服务两套 OAuth 端点：
   `/berserker-auth/oauth/token` 与 `/blade-auth/oauth/token`）。共用的是凭证，不是 token。
   侧栏冷启动首次进页因此要登两次。
2. **学工的会话在 localStorage，后台取不出来**，也无法由 OkHttp 复用。学工只能走
   WebView；但 CAS 有效时打开 `XgUrls.SSO_LOGIN` 是免密的，用户视角依然是「不用登」。
3. **胖乖生活做不到自动续登**：换 token 唯一的路是短信验证码，没有密码可存。
   它只能「token 长期保存 + 失效后重收一次短信」。引导页第 4 步必须把这点写出来。

#### 三个新件（`data/session/`）

```
CredentialVault            Graph 单例，加密存储的唯一读写口
 ├─ cas   学号 + 统一认证密码   → jw_credentials.xml（备份排除规则已在，无需新增）
 ├─ ykt   学号 + 查询密码       → ykt_credentials.xml（沿用，不动）
 └─ qiekj 手机号                → secure_token.xml（沿用；token 自身仍是凭据）

CasSession                 CAS 会话的唯一持有者（取代「用完即弃」的 JwHttpSession 用法）
 ├─ ensureValid()        有效直接返回；失效则用 cas 凭据静默重登一次
 ├─ cookieHeader(hosts)  → OkHttp 请求头（教务、签章）
 ├─ adoptFromWebView()   → WebView 登出来的会话回灌进 jar（唯一方向，无注入侧）
 ├─ onCredentialsUpdated() 改密码后清零闸门，并放开自动填表的 5 分钟节流
 └─ shutdown()           仅进程退出时调用（不再是每任务一次）

SessionStatus             StateFlow<Map<LoginTarget, LoginState>>，供 §3.16 状态卡与引导页读
                          LoginTarget = Jw（教务，含学工 / 签章）/ Ykt（一卡通 · 电费）/ Qiekj
 └─ 各仓库请求后上报：Ok / SessionExpired / CredentialWrong / Captcha / Network
```

`CredentialVault` 是加密存储的唯一读写口，**读一次缓存、写时同步**——
`EncryptedSharedPreferences` 读取不便宜，现在各页 `remember { }` 各读一次、各持一份。
现有 8 个读取点**签名不动**，`YktCredentialStore` 内部改为委托它（见「改动面」）。

#### CasSession 的关键工程约束（每条都有踩坑理由）

| 约束 | 理由 |
|------|------|
| `InMemoryCookieJar` 从 `JwHttpSession` 私有内部类提为公共类 | 会话要跨任务常驻，不能再随 `shutdown()` 丢掉 |
| **不做 cookie 注入**（2026-09-24 定案，注入侧代码已删） | 真机实测：把 OkHttp 登录拿到的 cookie 逐条写进 `CookieManager`（读回验证 7/7 落位、`flush()` 已调），85ms 后 WebView 的首跳仍然落到 CAS 登录页。差别只剩属性——注入的那份缺 `SameSite=None`，Chrome 按 Lax 处理，跨站跳转不带。**WebView 的会话只能由 WebView 自己登出来**，这条路不要再试一遍 |
| 回灌是**唯一方向** | `WebViewCookieBridge` 只留 `adopt` / `hasAnyCookie`。落在教务域且不是登录页时 `adoptFromWebView` 把 WebView 用出来的会话抄回 jar；**不要**加「jar 已有就不回灌」的条件，那样网页里的新值永远回不来 |
| **`loadUrl` 不能排在等会话之后** | 登录 / 探会话要联网。三个入口一律「先 `loadUrl` 让页面出来，再后台 `ensureValid` 拿结果写状态条」。2026-09-24 真机的「还是要手动登」其实是 WebView **白屏几十秒**：日志里连一条 `onPageStarted` 都没有 |
| 可信期要带「会话还在」的条件 | `last_success` 落盘、cookie 不落盘；进程重启后只看时间戳会把「空会话」当「可用会话」（真机实测 `last_success` 与当前只差 190 秒，会话早没了）。`canAttempt` 同理：`lastAttemptMs <= lastSuccessMs` 是成功留下的时间戳，必须放行 |
| **落登录页就自动填表登录**（`JwAutoLogin`） | 三个 WebView 入口（教务导入 / 学工表单 / 成绩单授权）在 `onPageFinished` 判到 CAS 域即填表提交。**不要用 `form.submit()`**——它绕过 `onsubmit` 与按钮上的点击处理，现象是「能填入但没点登录」；提交顺序是 `button[type=submit].click()` → `requestSubmit()` → `submit()` |
| 自动填表要过**两道闸** | 页面级 `autoLoginTried` + 进程级 `SessionStatus.tryAcquireAutoLogin`（5 分钟窗口，与 `REPEAT_WINDOW_MS` 同一档）。只有页面级那道挡不住重开窗口：密码改过而 App 还存着旧的时候，每开一次导入页 / 报修 / 成绩单就撞一次 CAS。**取到许可就算用掉一次**（失败也算）。该平台已停用（凭证被判错）时也一律不放行——密码就是错的，再填只是多撞一次失败计数；`onCredentialsUpdated()` 同时清零停用标记与这道节流 |
| `CookieManager` 的读写要在有 Looper 的线程 | 公开 API 的要求；协程里切 `Dispatchers.Main`（回灌同样） |
| 回灌只认白名单域 | `adoptFromWebView` 按域列表（CAS / 教务处 / 学工 / 签章）逐条读，不把整份 `CookieManager` 抄进来 |
| 失败计数**按平台分开**（CAS 与一卡通各一套） | 两套风控互不相干，一边失败不该让另一边停用 |
| 同一账号 5 分钟内不重复登录（CAS 与一卡通各一份） | 防「打开三个窗口各登一次」把风控点着 |
| 连续 2 次凭证错 → 转停用 + 通知，不再自动尝试 | 学校 CAS 有验证码/锁定机制（`scripts/README.md` 排错表第 1 条已提到「触发验证码」）。用户改密码后进 §3.16 状态卡更新即清零 |

#### 落登录页自动填表（`JwAutoLogin`，2026-09-24）

WebView 的会话只能由 WebView 自己登出来（注入无效，理由见上表），所以三个入口都接了
「落在 CAS 登录页就填表提交」：`JwImportScreen`（教务导入 / 成绩 / 考试）、
`XgFormScreen`（报修 / 请假）、`TranscriptScreen`（盖章成绩单授权）。

脚本只做三件事：找到 `input[name=username]` 与 `input[name=password]`，用**原生 setter**
写值并派 `input` / `change` 事件（受控组件直接改 `.value` 不更新框架状态），然后提交。
密码用 JSON 字符串字面量拼进脚本——CAS 密码可能含 `"` 或 `\`，手工拼会把脚本拼坏。
页面结构不认识、或命中验证码页时返回 `no-form`，调用方退回人工登录并提示。

节流两道，取值与理由见上表。同一个 5 分钟窗口内不重复提交——密码错时反复撞是风控
最敏感的形状；该平台已被判停用时也不提交（密码就是错的）；用户改完密码
（`onCredentialsUpdated`）会立刻放开。

#### 手登成功后补存凭据（2026-09-28，堵「跳过首启」的洞）

**用户实测暴露的断链**：首启引导跳过教务 → 之后在导入页 WebView 里**手动**登录成功 →
此前只回灌 cookie（`adoptFromWebView`），**凭据从不落库**——`vault.saveCas` 全仓库只有
引导页 `JwStep` 一个调用点 → 会话过期后 `tryAutoLoginOnCasPage` 读不到凭据直接放弃，
自动填充与自动续登**永远无从谈起**，且「我的」页显示「没有保存密码」也没有任何入口能补。

修复分两层，共享组件 `ui/common/CasLoginDialog`（2026-09-28 用户提议的**前置**口径）：

1. **进窗前置登录**：需要教务数据的窗口（课表导入 / 报修请假 / 成绩单授权）打开时，
   本地没有凭据就先弹一次「登录学校统一认证」——用户在 App 内输入学号密码，
   **先经 `cas.tryLogin` 验证、通过才落库**（坏凭据存进去会让自动续登反复撞 CAS 失败
   计数直至停用，比不存更糟）。保存成功 = OkHttp 会话即就绪，WebView 落 CAS 页由
   既有自动填表（`JwAutoLogin`）接管提交，**之后一直自动登录**。
2. **手登成功后补存**（兜底层）：用户跳过前置层、在网页里手登成功（`checkSessionLost`
   通过、落在教务域）时再给一次机会，文案改「保存账号密码？」。

共同纪律：

- 「先跳过 / 暂不」只挡**本窗口**（进程内标记），不写任何闸门；下个窗口还会再问。
- 保存成功立即刷新 `savedCas`（各页已从 `val` 改 `var`），同一窗口内再落登录页时
  自动填表就能用上。
- **前置层保存成功后立刻重载各入口的统一认证入口 URL**（导入 `SSO_WARMUP` /
  学工 `XgUrls.SSO_LOGIN` / 成绩单 `PtworkTranscript.CAS_ENTRY`）并复位页面级
  `autoLoginTried`——WebView 此刻多半已停在 CAS 登录页，不重载它就一直停在那，
  用户得退出重进才生效（2026-09-28 用户实测）。重载后落 CAS 页时自动填表接管提交，
  全程免手输。补存层（手登成功后）不强制重载：会话本身是好的，只复位标记。
- 三个 WebView 入口全部接入；别在「自动填表失败」路径里弹（那时用户还没登成功，
  弹了也存不了正确的密码）。

#### 退出登录必须两侧同清（2026-09-28 用户实测）

「退出登录并清除密码」后导入课表仍能直进教务、「我的」页教务格仍显「已登录」——
`logout()` 原来只清 OkHttp jar 与凭据，**WebView 的 `CookieManager` 没清**：
之前手登/自动填表留下的会话在 WebView 里仍然有效，导入页探针在它上面通过照常能用，
状态卡的 `hasAnyCookie`（只读 `CookieManager`）也一直报「已登录」。

修复：`WebCookieBridge` 新增 `clearAll()`（`removeAllCookies` 整库清 + `flush`；
`CookieManager` 没有按域删除的公开 API，而 App 内 WebView 只访问学校域，整库清
不会误伤第三方登录态），`CasSession.logout` 改**挂起**并按序清 OkHttp jar →
WebView cookie → 凭据 → 闸门/节流；UI 调用点挪进协程。回归断言钉在
`logout_clearsCookieCredentialAndGate`（`web.clearAllCalls == 1`）。

#### CAS 登录结果必须分四类（现状只有两类）

**并发登录要串行化**：`ensureValid` 用 `Mutex` 包住——「同一账号 5 分钟内不重复登录」
只是时间窗，两个协程同时撞到会话失效仍会并发登两次（`YktRepository.loginMutex`
是同一手法）。引导页与运行时共用同一个 `CasSession` 单例，锁也自然共用。

现在的 `JwHttpSession.login()` 把「CAS POST 后没有 Location」一律判成
`JwHttpException.Credential`（`JwHttpSession.kt` 第 3 步）。这会带来一个**静默故障**：
学校下发验证码页时，密码其实是对的，却被记成「凭证错」，累计到阈值还会把账号停用。

| 应答特征 | 判定 | 处理 |
|---------|------|------|
| 302 且有 Location | 登录成功 | 继续 SSO 链 |
| 200 且含密码框 + 验证码元素 | **需验证码** | 停手，转 WebView 手登；**不计入失败计数** |
| 200 且含错误提示文案 | 凭证错 | 计一次失败 |
| 5xx / 网络 | 平台异常 | 不计失败，静默重试一次 |

验证码特征要在实现时**实测确认**（当前无样本），在此之前该分支按「未知 200 响应
一律转 WebView」兜底——宁可多跳一次网页，也不能把对的密码记成错的。

#### 失败计数与失效通知

- **计数只有一份**，引导页与运行时共用。分两份会让「引导里错 2 次 + 后台又试几次」
  叠出超出预期的失败次数，正好是风控最敏感的形状。
- 失效通知用**新 channel `login_state`**（`IMPORTANCE_DEFAULT`）。不复用
  `balance_alert`：用户想把登录提醒静音时会连带把余额提醒一起静音，两个语义独立的
  东西不该共用通道。channel id 一次定死——建出来之后 importance 与震动都改不动
  （§3.9 那条踩坑记录）。
- 通知只在「转停用」那一刻发一条，之后不再重复；`POST_NOTIFICATIONS` 未授权时
  静默降级为只改状态卡（与 `BalanceAlertReminder` 同款处理）。

#### 会话被别处登录顶掉（强智单会话，2026-10-06 用户实测）

**现象**：教务页面上直接显示一段裸 JSON `{"flag1":3,"msgContent":"您的账号在其它地方登录"}`。
用户当时的判断是「是不是我 release 包的问题？」——实际是**强智只允许一条会话**：同一学号在
别处（另一台设备、PC 上的爬虫脚本、同一台手机上另一个安装包）登录后，先前那份会话对业务
请求不再回数据，而是回这段 JSON（HTTP 200、URL 不变、**既不是登录页也不是数据**）。

**两处代价**（都曾静默）：

| 位置 | 旧行为 | 现行为 |
|------|--------|--------|
| OkHttp 侧 `JwHttpSession.fetchHtml` | 登录页判据只认 `用户没有登录` / `userPassword`，这段 JSON 两个都不含 → **被当页面数据交给解析器**，教材抓取因此抛「教务返回 code=-1」并被丢掉 → 「导入成功但详情里没教材」 | 新增判据 `looksLikeSessionConflict`（业务文案 + `flag1` 键**同时**命中，缺一不可），命中抛 `JwHttpException.SessionInvalid` |
| WebView 侧 `SESSION_PROBE_JS` | 探针只查 `#loginDiv` / `input[type=password]`，这段 JSON 两个都没有 → 判定「会话有效」→ 继续自动导航 → 用户盯着裸 JSON | 探针补 `conflict` token（正文 < 4000 字节时扫标记），命中走 `JwImportDiagnosis.sessionConflict`，给「账号已在其它地方登录 + 在本机重登一次」的人话提示 |

**自动恢复**：`CasSession.fetchHtml` 只对 `SessionInvalid` 作废本地会话（`clearCookies`）后
重走一遍 `ensureValid` 再取一次——有凭证就真登一次（顺带把别处那份顶掉），没凭证就回灌
WebView 那份。**只重试一次**，失败不重试的纪律不变；**不动闸门的失败计数**（作废的是会话
不是凭证，那是「防锁号」的计数器）。

**为什么非做不可**：信任期内 `ensureValid` 不会去验会话（那是「减少请求」的另一半），
于是一份已经被顶掉的会话会被一路用下去——所有 OkHttp 取数（教材 / 成绩 / 学业 / 学籍卡）
同时静默失效，而 WebView 那条导入链路照常成功，现象就是「导入没问题、就是没数据」。

#### 请求收敛（按 §4.4.2 的决策收窄）

**课表导入不动。** §4.4.2 已经评估过「同源 fetch 两份 HTML 再本地解析」并**否决**：
`parseFromHtml` 只有合成 fixture 的单测，两张表的 DOM 抽取脚本才是真机口径。
那条决策的优先级高于本节，不要再提案一次。
（后记 2026-10-07：`parseFromHtml` 这条路 2026-10-04 起已被 §4.17 调课检测/首启导课表
直取采用，并在当天修过一处与注入脚本不一致的「一格多课块只取第一个」——本节只约束
**导入**链路不再改造，改动直取链时口径对齐要求见 `.agents/rules/import-jw.md`。）

能动的只有「数据本来就是 JSON、解析器和 WebView 路径共用同一份」的两条，加学籍卡：

| 操作 | 现状 | 改后 | 风险 |
|------|------|------|------|
| 成绩导入 | 页面加载 + 注入 fetch 分页 | 1~2 个 GET | 低：URL 与参数原样搬（`/jsxsd/kscj/cjcx_list`），`ScoreParser.parseFetchJson` 直接复用 |
| 考试导入 | 页面加载 + 注入 fetch 分页 | 1 个 GET | 低：同上（`/jsxsd/xsks/xsksap_list`） |
| 学籍卡 | 1 次页面加载 + 注入 fetch | 1 个 GET | 中：`parseStudentCard` 用正则解析 HTML，需真机比对一次 |
| 课表导入 | 6~7 次页面加载 + 2 次 JS 注入 | **不动** | — |
| 侧栏冷启动首进 | 8 条（登录占 3） | 5 条（登录 0，需 token 落盘） | 低：只是不再每次登录 |

成绩/考试搬过来的收益不只是省请求：OkHttp 那条路可以 JVM 单测（注入 fetch 的实现测不了），
也能被 `CasSession` 统一重登。前提是先真机比对一次「OkHttp GET 的响应 == WebView 里
`fetch` 到的响应」——同源同 cookie，理论上一致，但要验过再切。

#### 一卡通 token 落盘口径（2026-09-24 用户拍板：落盘）

**用户拍板：落盘。** 理由：密码都已经存了，token 的泄露面并不更大，换来的是每次冷启动
少一次登录（取安全键盘 + 换 token，两条请求）。

| 项 | 规格 |
|----|------|
| 存放 | 新文件 `ykt_tokens.xml`（EncryptedSharedPreferences），与凭证分开；两个备份规则里都加排除 |
| 结构 | `token` + `token_at`（写入时刻） |
| 新鲜度 | `domain` 层的 `TokenFreshness`：超过 `MAX_AGE_MS`（60 天，比服务端标称的 70 天保守）或时钟被往回拨过即视为不新鲜 |
| 定位 | **只当「冷启动少登一次」的加速，不是授权依据**——服务端回 401 时既有的重登逻辑照常接管 |
| 清除 | `CredentialVault.clearYkt()` 连 token 一起清（凭证没了，token 留着没有意义） |
| 电费 token | **仍不落盘**（3599 秒，收益近零），每次冷启动重登一条请求 |

#### 实现状态（2026-09-24）

已实现并随本次改动编过：`CredentialVault` / `CasSession` / `SessionStatus` / `MemoryCookieJar` /
`CookieBridge` / `CasLoginClassifier` / `LoginGateRules` / `LoginStateRules` / `TokenFreshness` /
`YktTokenCache` / `WebViewCookieBridge`；导入页、学工表单、签章授权三个 WebView 入口接上
「先 loadUrl、后台探会话」与「落到教务域后回灌」；`OnboardingActivity` 首启引导五屏；
「我的」页账户卡改常显 + 三行状态（2026-09-26 改为三格服务入口，2026-09-27 起最多四格，
见 §3.16）；
登录失效通知 channel `login_state`；
一卡通 token 落盘复用。测试新增 6 个类 46 个用例（会话策略、四分类、闸门、状态推导、
cookie 桥、新鲜度）。

**2026-09-24 第二轮（收尾三项 + 引导补 Token 登录）**：① 删掉 cookie 注入侧
（`WebCookieBridge.inject` / `CasSession.prepareWebView` / `injectToWebView`），四个调用点
改成「先 loadUrl、后台 `ensureValid`」——注入既无效，留着还会误导后来人；② `CredentialVault`
给 CAS / 一卡通两份凭证各加内存缓存（`EncryptedSharedPreferences` 读要走 Keystore 解密，
三个入口 + 账户卡各读一次都在主线程），写入时同步更新缓存；③ 自动填表加进程级节流
（`SessionStatus.tryAcquireAutoLogin`，5 分钟），`onCredentialsUpdated` 清零；
④ 引导第 4 步（胖乖生活）加 Token 粘贴登录（与胖乖生活页同一序列：查余额校验 → 落盘，
2026-09-27 由「先落盘再校验」改为先验后存），并补上 60 秒验证码冷却与登录成功后记住手机号。

**2026-09-24 追加**：一卡通与电费的凭证错会上报 `SessionStatus`（`credentialFailure` 统一
处理），所以状态卡第二行也会转「已失效」；token 过期（401）**不上报**——那条会重登一次，
属正常轮换，标了会让状态卡在每次 token 轮换时闪一下。后台任务（`BalanceAlertReminder`）
走同一批仓库方法，因此自动获得这个上报，不需要单独埋点。

**未做**（明确留待真机）：P3「成绩 / 考试 / 学籍卡取数搬 OkHttp」需要先真机比对
「OkHttp GET 的响应 == WebView 里 fetch 到的响应」，没验之前不动；P5「统一 OkHttpClient」
收益最低，可以不做。

#### 兜底矩阵

| 场景 | 用户看到 | 系统行为 |
|------|---------|---------|
| CAS 会话过期、凭证有效 | 无感 | `ensureValid` 静默重登一次 |
| 密码改了 | 状态卡该行转红 + 一次通知 | 连续 2 次凭证错后停用，不再自动尝试 |
| 命中 CAS 验证码 | 无感（转 WebView）或一次网页登录 | 不计失败；登录后回灌 |
| VPN / 代理开启 | 「先关掉代理再试」 | 尝试前 `JwVpnDetector` 拦下，不计失败 |
| 校园 IPv6 黑洞 | 无感 | `Ipv4FirstDns`（已有，推广到统一 client） |
| 教务页面结构变了 | 「页面结构可能已变化」 | 解析 0 条即回退 WebView 老路径 |
| 一卡通触发风控 | 现成文案「去浏览器登录一次」 | 不重试、不停用其他平台 |
| 用户在 WebView 手登 | 无感 | 落域后 `adoptFromWebView` 回灌 |
| 只在网页里手登过、没存密码 | 账户卡「已登录 · 网页会话」，教务账户页提示填一次密码可自动续登 | `ensureValid` 在「没凭证且 jar 为空」时抄一次 `CookieManager` 并 `verifySession`，**验过才写闸门**（抄回不写 `last_success`，否则信任期会把过期会话当可用）；学籍卡 / 成绩 / 学业完成情况因此也能取数 |
| 当天先跳过了教务、当天又手登回来 | 姓名 / 学号 / 班级补上 | `ProfileSync` 只在 `ensureValid` 真的试过时才落「今天试过」的日期；`NoCredential` 不落，当天还能再补抓 |
| 落到 CAS 登录页、存过凭证 | 无感（页面自己填表提交） | 脚本返回 `no-form` 时提示「请在下方页面手动登录一次」 |
| 密码改过、App 还存着旧的 | 一次「自动登录没走通」提示 | 自动填表 5 分钟只放行一次，不再每个窗口撞一次 CAS；改密码后闸门清零 |
| 引导中途退出 | 主界面可用，功能各自提示未配置 | 每步独立落库 |
| 用户清掉一卡通凭证 | 侧栏回「未开启凭证」态（§4.24） | 电费 token 一并清 |

**会话探针要认得出「就地渲染的登录页」（2026-09-27）**：未登录时教务把登录页渲染在
`xsMainV.htmlx` 的 200 应答里（约 79KB，title「登录」，表单
`<form name="loginForm" action="/jsxsd/xk/LoginToXk">`），它比 860 字节的提示页更容易骗过
「字节数阈值 + 不含『用户没有登录』」这条老判据。`JwHttpSession.looksLikeLoginPage` 因此加了
`LoginToXk` / `userPassword` 两个表单特征（`用户没有登录` 按 §4.17 的实测在真实页面上出现
**0 次**，只作兜底），`verifySession` / `fetchHtml` / `fetchPage` 三处共用。
真机实测的后果：一份**过期的 CAS cookie** 会让探针判「会话有效」，状态卡显示已登录、
学籍卡抓回一张登录页（解析必失败），而「今天试过」的闸门又把重试挡到第二天。
配套改的还有 `ProfileSync`：「今天试过」只在**真拿到页面**之后才落，抓不到就下次进页再试。

**回灌 URL 的路径必须落在 cookie 作用域里（2026-09-27）**：CAS 的 `JSESSIONID` 作用域是
`/cas`、`TGC` 是 `/cas/`、教务的 `JSESSIONID` 是 `/jsxsd`，`CookieManager.getCookie` 按 URL
路径筛。`ADOPT_URLS` 早先写成根路径，读回来的只有教务预热写的 `bzb_njw` 这类站点标记，
于是「有没有会话」全靠标记撑着（WebView 里只剩 `bzb_*` 时状态卡仍显示已登录）。
`WebViewCookieBridge.hasAnyCookie` 同时收紧成**只认会话标识**（`SessionCookieRules`：
`JSESSIONID` / `JSESSIONIDSSO` / `TGC` / `session_oa` / `sid`）。

#### 改动面与凭证清除的联动

**凭证读取点**（`Graph` 单例工厂之外）：`SettingsScreen`、`CampusCardViewModel`、
`PayCodeViewModel`、`StatementViewModel`、`LifeViewModel`、`PowerBillViewModel`、
`BalanceAlertReminder`。

**这 8 处一处都不改**：`YktCredentialStore` 的公开签名（`read` / `save` / `clear` /
`Credentials`）保持不变，内部改成委托 `CredentialVault` 的一卡通部分。改 1 个文件
胜过改 8 个文件，也避免漏改某一处造成「有的页面读得到、有的读不到」。
同理 `JwHttpSession` 的 `login` / `fetchTheoryScheduleHtml` / `fetchLabScheduleHtml`
签名不变，内部改走 `CasSession`。

新增的 CAS 凭证同样有清除入口（§3.16 状态卡那一行），**两套凭证的清除互不牵连**：

| 动作 | 影响 |
|------|------|
| 关闭校园卡付款码（`CampusCardViewModel.disable()`） | 清 ykt 凭证 + `campusCardEnabled` 回落 + 余额提醒两个开关回落（现有 `disable()` 已实现）；**CAS 凭证与教务导入不受影响** |
| 状态卡里「退出教务登录」 | 清 cas 凭证 + `CasSession` 的 cookie 全清；一卡通不受影响 |
| 用户改密码后重新登录 | 只重写对应那套凭证，失败计数清零 |

**容易漏的边界**：生活页的「未开启凭证」态按 **ykt** 凭证判定（不是 CAS）；
教务导入窗口的提示按 **cas** 凭证判定。两者不能混用一个布尔。

后台任务（`BalanceAlertReminder`）失败时**只上报 `SessionStatus`、不发通知**：
它的每日闸门只挡重复请求，挡不住「凭证失效 → 每天一条失效通知」的骚扰。
失效提示由用户打开 App 时在状态卡上看到。

#### 第三方会话过期的进页提示（2026-10-21 用户要求）

胖乖 / 趣智 / U净 / 快趣 / 问渠的会话不在 CAS 体系里，失效往往发生在用户离开页面之后
（token / JWT 到期、被平台顶掉、本机凭证被清）。旧行为是下次进页只剩一张登录表单，
用户不知道刚才发生了什么。现行口径：

- 会话在**判定失效的那一刻**写一条持久标记（`data/session/SessionExpiryStore`，
  `SessionService` 五档），功能页进页时**消费一次**并提示
  「登录状态已过期，请重新登录」（`ui/common/SessionExpiredNotice`）；
  **登录成功与用户主动退出清标记**——主动退出不该报过期。
- 标记点：胖乖 `QiekjRepository.markExpired()`（401/403 /「请先登录」）、趣智
  `QzxyRepository.markExpired()`、U净 `UjingRepository.markExpired()`、快趣
  `KqcxSessionRepository` 的静默重登业务失败、问渠 `WenquRepository.expireSession()`
  （JWT 到期且不能续期 / 410000）。**只有「会话失效」才标**，网络与 5xx 不动标记。
- 问渠不能续期时**同时清 token 并翻 `loggedIn`**，否则页面会一边显示已登录、一边报过期。
- 提示只在**功能自己的页面**（胖乖开水 / 趣智 / U净 / 快趣账号 / 问渠），
  **不要放进今日页卡片**：那会变成一打开 App 就弹多家过期提示。
- 账号中心的状态胶囊也读这条标记：页面消费前显示「重新登录」（`LoginState.Expired`），
  消费后回到「登录」；主动退出不会让胶囊变红。
- 新增第三方登录功能照这个模式接；别在页面里自己观察 `loggedIn` 变化来弹提示。

#### 兼容与迁移

- `onboarding_seen` 缺失的老用户 → **不弹引导**（默认不打扰），只在「我的」页显示状态卡
  与入口。首启引导只服务新安装。
- WebView 的 `CookieManager` 里可能已有老用户的有效会话：WebView 入口照旧直接加载，
  页面自己就是已登录态（不会触发填表）。OkHttp 那条链（学籍卡 / 成绩导入）要凭证，
  老用户没存过就显示「未登录」；页面落到登录页时由 `JwAutoLogin` 填表提交，
  成功后再 `adoptFromWebView` 抄回 jar。
- **与 AGENTS.md 的现有约定冲突，必须同步改**：AGENTS.md 的学工表单那条写着
  「学工与教务共用同一套统一身份认证，所以教务导入登录过一次这边就免登，App 不存密码」。
  本方案会存 CAS 密码用于续登，那句要改成「学工不存自己的密码；CAS 密码口径见 §4.27」。
  不改的话，后续任何一次读 AGENTS.md 都会把新代码判成违规。
- `JwHttpSession` 的公开签名（`login` / `fetchTheoryScheduleHtml` / `fetchLabScheduleHtml`）
  保持兼容，内部改走 `CasSession`，避免一次性改完所有调用点。
- 一卡通 token 落盘是**新增**行为，老版本升级后第一次仍会登一次（正常路径）。

#### 测试清单（JVM）

- `CredentialVaultTest`：两套凭证独立读写、空值/脏值、清除不误伤另一套。
- `CasSessionPolicyTest`：失败计数分类（凭证错计入 / 验证码不计 / 网络不计）、
  5 分钟窗口、2 次停用、更新密码后清零。
- `SessionStatusTest`：三种状态推导（未请求 / 成功 / 失效）、平台互不影响。
- `CasResponseClassifyTest`：四类应答分类（含验证码页样本、错误页样本、302）。
- `CookieBridgeTest`：请求头 `Cookie:` 拼接（分号 + 空格、空集合为空串）。
- 既有 `JwHttpSessionTest` 全绿（重定向解析、IPv4 优先两条回归钉不许动）。

#### 阶段拆解

| 阶段 | 内容 | 独立验收 |
|------|------|---------|
| P1 | `CredentialVault` + `CasSession` + `SessionStatus`，UI 不动 | 单测 + 真机「登录一次后一段时间内不再手登」 |
| P2 | 导入/学工/签章三条链改走 `CasSession`（先 loadUrl、后台探会话；落登录页填表提交，成功后回灌） | 三处功能不回归 |
| P3 | 成绩 / 考试 / 学籍卡取数搬 OkHttp（**课表不动**，理由见「请求收敛」），老路径兜底 | 与 P2 结果逐条比对 |
| P4 | 引导窗口 + 状态卡 + 失效提醒 | 首次安装全流程 |
| P5 | 统一 OkHttpClient（连接池 + DNS），收益最低，可砍 | 构建 + 冒烟 |

P1 与 P2 分开验收：会话层的坑（cookie 属性、端口、回灌）和导入链路的坑混在一起，
出问题极难定位。

---
