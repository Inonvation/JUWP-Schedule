# DESIGN §4.23–§4.32 · 技术架构：校园服务与第三方系统

> 本文档是 [DESIGN.md](../DESIGN.md) 的拆分件。代码注释里的「DESIGN §x.y」章节号一字未改，索引见 DESIGN.md 的映射表。

## 4. 技术架构（续 · §4.23–§4.32）

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

| 环节 | 请求 | 说明 |
|------|------|------|
| 登录 | `POST /blade-auth/oauth/token`（根域，不在 `/charge` 下） | form：`username` / `password` / `grant_type=password` / `scope=all` / `logintype=student-sno-queryPassword`；header `Authorization: Basic charge:charge_secret`（前端公开常量）。`access_token` 3599 秒 |
| 项目 | `GET /charge/feeitem/singleFeeitem?feeitemid=181` | 平台只挂一个项目：`181`「房间电费」0.62 元/度，`impl_interface=iECSceneServiceImpl`；`sceneinfo` = 本人绑定房间（`campus:0#…;building:0#9A;room:14600#9A999`） |
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
`PowerReadingStore.observeAll()`（Room 响应式，生活页记一条、账单页立刻重算）。
**`roomId` 与 `roomName` 不能混用**（2026-09-27 加，Room v11 → v12 走 `ALTER TABLE ... DEFAULT ''`）：
`roomId` 是平台的数字内部 id（如 `14600`），只能当去重键与统计分组键；`roomName` 是房号显示名
（如 `9A999`），只给界面。生活页电费卡的首屏种子要写房号，所以显示名必须随读数落库
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
算不出的段跳过、跨天均摊、周/月桶与窗口、房间过滤、标签与文案）、
`LifeFeedTest`（分段、段内排序/限量/稳定、时间解析失败沉底、只有一段有内容不算空态）。

#### 电费充值（2026-09-23 实测打通，Python 侧 9 笔真实下单验证；2026-09-24 补测下单/删单）

**下单**（与一卡通充值同一接口、同一签名算法 `YktRechargeSign`，`appid`/`SECRET_KEY`
为前端公开常量；**2026-09-29 修复入账缺陷后 8 字段**，见下方红框）：

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
静默刷一次余额快照，生活页余额立即更新。流水兜底口径两条路共用。

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
生活页刷新**不再**触发清理——未支付单只可能由本流程产生，而那正是要用之前的那一刻；
不做后台轮询，也没有 12 小时闸门（一个触发点就够，多一个就是多 1 + N 条对第三方平台的请求）。

**充值免责声明**（2026-09-29 用户要求，§3.13 与 §4.19 共用一条）：**电费与一卡通的每个
充值入口**（生活页钱包卡两格、电费弹层内的「去充值电子账户 ›」、我的 → 校园卡页的「充值」）
在打开充值弹层前都要先过 `ui/common/RechargeDisclaimerDialog`：五条声明（快捷入口非官方、
接口随时可能变更、首充先小额、核对账户防充错、严禁违法用途与泄露凭证）+ 勾选
「一周内不再提醒」。**首次弹出（从未确认过）锁 5 秒**：两枚按钮置灰、返回/点遮罩无效、
确认键倒数秒数；点「继续充值」或「不同意并取消支付」（2026-09-30 起，原「取消」——
明示不接受协议 = 本次充值就地作罢，绝不进充值流程）都落「已确认」
（`recharge_disclaimer_seen_at`，那是"看过"不是"同意"，不同意过的用户下次充值还会
再弹，只是不再锁 5 秒），之后任何一次弹出立即可关。勾选落 `recharge_disclaimer_suppress_until`（+7 天，
`domain/RechargeDisclaimer` 是窗口/锁时长口径的唯一来源，`RechargeDisclaimerTest` 钉住），
电费与一卡通**共用同一份**——同一件事的同一句提醒，分开存只会让弹窗节奏难预期。
静默期内所有入口直开充值弹层，声明不再弹。

**流程（生活页）**：电费充值格 → `PowerRechargeSheet` → 金额步先在两枚 FilterChip 里
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

生活页**每次进页**都会打一串请求：电费侧是项目详情、读表、流水（未支付单清理已于
2026-09-24 挪到打开充值弹层时），一卡通侧是余额两条（`queryCard` + `queryCard?scene=recharge`）
与流水增量同步。
来回切几次 Tab 就是十几条，对第三方平台看就是异常流量。两条链路都收在这一节。

| 项 | 规格 |
|----|------|
| 内存缓存 | `PowerRepository` 给「读数（项目详情 + 读表）」与「流水」各留一份内存缓存，TTL **2 分钟**（`CACHE_TTL_MS`） |
| 谁绕过缓存 | 生活页**进页**刷新走缓存（切 Tab 回来不重复请求）；点电费卡、账单页下拉刷新、充值成功后刷新一律 `force = true`。用户明确要看的那一刻必须是新值，省请求只省「没人看的时候」 |
| 未支付单清理 | **只在打开电费充值弹层时清**（`preparePowerRecharge`，1 + N 条），下单前 `join()` 它。不清的代价是新下单 500（平台不自动清过期单），而清理放在「马上要下单」这一刻，正好卡在需要之前，刷新链路一条请求都不多打 |
| 落点 | token 只在内存、不落盘（红线不变）；读数缓存只在内存（进程结束即失效）。**流水的上次成功结果**另有落盘种子（下一行） |
| 流水落盘种子（2026-09-26） | `PowerHistoryCache`（`filesDir/power_history_cache.json`）存上次成功流水。冷启动进程里内存缓存全空，进页读数链（登录→项目详情→读表）要串行三条、流水与它并行（2026-09-30 起口径；此前四条全串行），此前这段时间电费段只能挂骨架（用户报「一打开一直是骨架屏，手动一刷新反而秒出」——刷新快是因为那时 token 已经热了）。`LifeViewModel` init 在 IO 线程读出种子先顶上，刷新到货原地替换；链路已跑完（含没开凭证的短路）就不用旧数据盖新的；**空结果不落盘**（宁旧勿空）。种子只当首屏数据，节流口径不变 |
| 读数首屏种子（2026-09-27） | 生活页**电费卡**同样起步为空（「—／读取中…」），冷启动要等 登录→项目详情→读表 三条串行请求（用户报「首次进入生活页电费余额还是很慢，手动下拉就秒出」）。种子换成 Room `power_readings` 最新一条（`PowerReadingStore.latest()`，零网络），经 `PowerModels.snapshotSeedOf` 拼成展示用快照顶上：度数、单价、房号、读数时刻都照抄，单价未知就不折算、副行给「上次读数」。仅当卡上还没有数时填（真读数与「刷新失败保留的上次读数」都不许被旧种子盖掉），凭证没开直接跳过。种子不用新建文件、也不写回仓库缓存——同一份读数本就在库里（写入仍只有 `PowerRepository.snapshot()` 一处） |
| 请求并行与登录去重（2026-09-30） | 用户报「下拉刷新要刷新很久」，根因是指示器等的是两条全串行链的较慢者（电费读数链、一卡通余额链，冷 token 各登一次）。三处收口：① `LifeViewModel.refreshPower` 电费流水与读数并行；② `CampusCardViewModel.fetchBalance` 正式卡与电子账户两问并行；③ `YktRepository` / `PowerRepository` 登录互斥锁内复用等锁期间别路登好的 token（`staleToken` 只挡刚试废的），并发冷启动两平台各只真登一次。成功路径请求总数不变（冷启动反而少登一次），只是不再串行排队；失败路径会多发那条附加请求（读数挂了流水照样发、卡挂了电子账户照样发——「附加信息取不到不打扰」的既有口径）。详情见 §3.13「三路并发、登录只登一次」 |
| 不做的 | 不加重试（失败即停，防撞风控）、不做后台轮询 |

> **2026-09-24（§4.27）**：电费 token 仍不落盘（3599 秒，落盘收益近零，
> 每次冷启动重登一条请求可以接受）；一卡通那份 70 天的 token 是否落盘见
> §4.27「一卡通 token 是否落盘」。

一卡通侧（2026-09-24 落地，与电费同一节口径）：

| 项 | 规格 |
|----|------|
| 余额缓存 | `YktRepository.cards` 与 `rechargeAccountDetail` 各留一份内存缓存，TTL **60 秒**（`BALANCE_CACHE_TTL_MS`）。生活页进页传 `force = false`，一次进页从两条请求降到 0 条 |
| 余额的强制穿透 | 点余额卡、生活页下拉刷新、开启流程验证、**到账判定**（`checkArrivalOnce` / `refreshBalanceSilently`）一律 `force = true`。到账判定吃旧值会把「已到账」永远判成「还没到」，这条比省请求重要；刷新入口有「取数中忽略重复触发」闸，连点不叠请求（2026-10-09） |
| 付款码命中后的余额刷新（2026-10-09） | 一笔付款码消费 = 正式卡余额已确定变化。宿主弹「支付成功」时触发 `CampusBalanceSync.refreshAfterPayment`：作废 60 秒缓存 → `Graph.appScope` 单次强拉 `queryCard`（写回缓存、推桌面小组件）→ `revision` 自增，生活页 / 设置页的余额快照用 `force = false` 就地重建。**只取一次数**——两个页面的 VM 都活着也不各自强拉（那会变成一次消费 4–6 条请求）；失败静默不重试；同一份余额再交给 `BalanceAlertReminder.evaluateYktBalance` 当场评估一次（付款把余额刷低不必等下一个检查档） |
| 流水闸门 | `YktSyncGate`（进程级单例，10 分钟最小间隔，`MIN_INTERVAL_MS`）。生活页与消费流水页各建一个 `YktTurnoverSyncer`，闸门必须放在单例里，否则「刚在生活页同步过」对消费流水页无效 |
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
| 现行为 | 新增 `PowerException.RoomNotBound`（`snapshot()` 判 `feeItem.room == null` 即抛）：生活页电费栏转「**未设置寝室，点此设置**」CTA（值「—」、旧读数房号/时刻一并隐藏、`openRecharge` 闸门改道选择器）；「我的 → 校园卡」新增「寝室电费房间」行（设置 / 改绑的常驻入口，值取本机最新读数房号，零网络） |
| 选择器 | `ui/life/PowerRoomPickerSheet(+ViewModel)`：`type=select&level=N` 逐级取候选（实测口径：校区 2、楼栋 58、单楼栋房间 182——候选 >12 带本地搜索过滤、单选项自动推进）→ 房间选定 `type=IEC` **读表预览**（**不落库**：预览的可能不是本人房间，落库仍只有 `snapshot()` 一处）→ 二次确认弹窗（明说会同步到缴费平台「我的房间」）→ 写一次 |
| 绑定写入 | `POST /charge/sceneBind/add`，**JSON body**：`{"feeitemid":181,"sceneinfo":"campus:0#…;building:79#…;room:14686#…"}`（官方 `$api.post` 未带 `"qs"` → axios 默认 JSON）。`sceneinfo` 拼串唯一口径 `PowerModels.sceneinfoOf`（`code:id#name` 分号相连，与官方 `addSceneInfo()` 逐字一致）。**2026-10-08 同房间重绑实测通过**：HTTP 200 / `code=200`、重拉 `singleFeeitem` 不变；upsert 语义、官方允许改绑（确认文案「修改为」） |
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
   生活页冷启动首次进页因此要登两次。
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
| 生活页冷启动首进 | 8 条（登录占 3） | 5 条（登录 0，需 token 落盘） | 低：只是不再每次登录 |

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
| 用户清掉一卡通凭证 | 生活页回「未开启凭证」态（§4.24） | 电费 token 一并清 |

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

**凭证读取点有 8 处**（`Graph` 单例工厂之外）：`SettingsScreen:116`、
`CampusCardSettingsScreen:621`、`PayCodeViewModel:89/396`、`StatementViewModel:73/216`、
`LifeViewModel:72/500`、`PowerBillViewModel:96/280`、`BalanceAlertReminder:150`。

**这 8 处一处都不改**：`YktCredentialStore` 的公开签名（`read` / `save` / `clear` /
`Credentials`）保持不变，内部改成委托 `CredentialVault` 的一卡通部分。改 1 个文件
胜过改 8 个文件，也避免漏改某一处造成「有的页面读得到、有的读不到」。
同理 `JwHttpSession` 的 `login` / `fetchTheoryScheduleHtml` / `fetchLabScheduleHtml`
签名不变，内部改走 `CasSession`。

新增的 CAS 凭证同样有清除入口（§3.16 状态卡那一行），**两套凭证的清除互不牵连**：

| 动作 | 影响 |
|------|------|
| 关闭校园卡付款码（`CampusCardSettingsScreen.disable()`） | 清 ykt 凭证 + `campusCardEnabled` 回落 + 余额提醒两个开关回落（现有 `disable()` 已实现）；**CAS 凭证与教务导入不受影响** |
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

### 4.28 应用图标（自适应图标，2026-09-27，P6）

用户给了一张方形图标图（白底：蓝色渐变日历 + 蓝色水滴），替换旧的青色水滴矢量图标——
旧 `drawable/ic_launcher_foreground.xml`（108dp 水滴矢量）已删除。

| 项 | 现值 |
|----|------|
| 入口 | `mipmap-anydpi-v26/ic_launcher.xml`。manifest 只有 `android:icon`，没有 `roundIcon`；minSdk 26 → 没有 legacy 位图那一套 |
| 背景层 | `@color/ic_launcher_background` = `#FFFFFF`。跟着源图的底走：图形内部有白色内页，底色换别的会露馅 |
| 前景层 | `drawable-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png`（108/162/216/324/432px = 108dp 画布） |
| monochrome | 复用前景的透明度通道（日历方框 + 四格 + 水滴的剪影），没另画矢量 |
| 重新生成 | `tools/app-icon/make_foreground.py`，源图 `tools/app-icon/source.png` 一并入库；换图或改尺寸重跑脚本，超限尺寸脚本自带断言 |

**为什么前景是位图**：源图是蓝色渐变，复刻成 VectorDrawable 要手写渐变节点，收益不抵风险。
位图的麻烦是"生成规则看不见"，所以把提取与缩放口径固化成脚本，而不是手搓一张 PNG。

**尺寸口径（改前必读）**：框架绘制自适应图标时把前景/背景各**放大 1.5 倍**再按遮罩裁切，
所以 108dp 画布中间 72dp 才是可见区，外侧 18dp 是留给视差/效果的余量。图形墨迹外接框固定
**51dp**，占可见区 51/72 ≈ 71%，与源图「图形 418px / 方块 596px = 70%」对齐；51dp 时墨迹
外接圆 ≈31.5dp，仍在官方口径的 66dp 安全圆（半径 33dp）内，换算到遮罩坐标 ≈47dp 也不碰
Pixel 那种半径 54dp 的圆遮罩，日历四角不会被切。

**提取口径**：按「蓝度 = B − R」估不透明度，再按白底反预乘还原原色，前景叠回白底能逐像素
还原源图（实测最大偏差 12/255）。不透明度低于 10% 判为背景，用来清掉源图那层淡投影——不清
的话它会变成一层糊在图标上的半透明蓝晕，主题图标（monochrome）下尤其刺眼。

未做：debug 与 release 仍是同一图标（桌面靠名字区分）；三个小组件的 `previewImage` 指向
`@mipmap/ic_launcher`，跟着换成新图标，没有单独出预览图。

### 4.29 学业完成情况与自动导入（2026-09-27，P6；UI 规格见 §3.17）

#### 接口（2026-09-27 本机实测）

菜单项 `NEW_XSD_XJCJ_XJGL_XXWCQKTX`「学业完成情况」，壳页
`/jsxsd/xxwcqk/xxwcqk_idxOntx.do` 内是四个 tab，各加载一个数据页：

| 维度 | 接口 | 返回 | 分组数 | 课程数 |
|------|------|------|--------|--------|
| 课程体系 | `/jsxsd/xxwcqk/xxwcqkOnkctx.do?isdb=0` | 190 KB | 5 | 83 |
| 课程性质 | `/jsxsd/xxwcqk/xxwcqkOnkclb.do?isdb=0` | 242 KB | 18 | 88 |
| 课程属性 | `/jsxsd/xxwcqk/xxwcqkOnkcxz.do?isdb=0` | 121 KB | 5 | 88 |
| 公选课类别 | `/jsxsd/xxwcqk/xxwcqkOnszklb.do?isdb=0` | 80 KB | 9 | 5 |

`isdb=0` = 本人视图。菜单里那个「对比学业情况」是 `isdb=1` + 院系/年级/专业三选，
教职工查他人用的，学生本人抓了也是空——**不要顺手实现它**。
「毕业结论查询」（`xxwcqk/byjlcx.do`）返回 951 字节的 no-open 页，**校方已关闭**，
同 `cjcx_list.do` 那类开关页，别把它误判成「暂无数据」。

#### 解析（`data/jw/ScholarProgressParser.kt`）

**返回的是 HTML 片段而不是 JSON**，这一点与成绩 / 考试两处不同，所以没有注入 fetch、
也没有 JSON 解析。页面结构（`div.mod-item-detail` 分组块 → `div.sub-table` 明细）与
课表页同一个毛病：**两段并列的 `<html>`**，任何按「一个根节点」写的 XML 式解析都会丢后半段。

三条不能想当然的：

1. **明细列集四个维度各不相同**：课程体系 / 课程属性 11 列（含「修读学期」「是否学位课」）、
   课程性质 9 列（**没有「修读学期」**）、公选课类别 10 列（**没有「课程属性」**，第 6 列
   直接是「课程性质」）。按下标硬取会让公选课那批把性质读成属性、把成绩读成修读状态。
   解析**按表头名映射**（`span.header-th-cell` → 列下标），表头是教务给的稳定契约，
   顺带还能容忍以后加减列。
2. **分组名的 `<b>` 里套着 iconfont 图标**（`<i class="layui-icon">&#xe62f;</i>`）。
   只去标签会把 `&#xe62f;` 留在分组名里，界面上显示成方块；也要去数字实体。
   另外「计划外非通选课」那个分组**没有 `<b>`**，而它的图标 `<i>` 也带
   `header-title-blod` 类名——「类名后跟 `</b>`」的宽松正则会从 `<i>` 一路吞到后面某处，
   把一整段 HTML 当成分组名。正则必须锚在 `<b` 上。
3. **修读情况两种写法**：课程体系维度写「未修读」，课程属性维度写「待修读」，落库前归一
   （`ScholarCourseStatus`），否则 UI 上同一个状态渲出两个标签。

顶层那张总账表（`div.total-list`，kctx 5 列、其余维度 3 列，列集合还不一致）**不入库**：
它的数值就是分组之和（实测 kctx 58.5+4.5+46+52 = 161.0 = 教务合计行），由 UI 现算反而
四个维度口径统一。

#### 存储

Room **v12 → v13**：新表 `scholar_groups`（分组头：维度 / 名称 / 要求 / 已修 / 在修 /
还需 / 结论 / 进度原文）与 `scholar_courses`（11 项明细）。全局归属学生、**不挂 timetableId**
（口径同 `scores`）。可空列（学分、结论、是否学位课）在实体里是 `Double?` / `Boolean?`，
迁移 SQL 对应**不带 NOT NULL**；索引名与 `@Index("dimension")` 生成的逐字对齐。

写入口径 = **整体替换**（一次导入清空两张表再插）。四个维度出自同一次教务快照，
分开更新会留下「课程体系是新的、课程性质是旧的」这种自相矛盾的状态。

备份（§4.3）：`CourseExport` 新增 `scholarGroups` / `scholarCourses` 两个可选段，换机随课表
恢复；空段/缺段不动现有数据。培养方案名不在备份里（展示性字段，恢复后为空只是少显示一行，
下次抓取就有；为此把它塞进 `DisplayPrefs` 会牵动整套偏好默认值契约）。

#### 自动导入（本节的重点）

**抓取走 `CasSession.fetchHtml`（OkHttp + 已存凭证），不开 WebView。** 会话层建好之后
这只是一个带 cookie 的普通 GET，学籍卡补抓（§3.3）已经这么用了。三个触发点：

| 触发点 | 行为 |
|--------|------|
| 首启引导登录成功（`OnboardingScreen` 的 `JwStep`） | 成绩 + 学业各抓一次（`force = true`）。**丢进程级 `Graph.appScope`**，不用页面自己的 `rememberCoroutineScope`——用户点「下一步」跳进 MainActivity 时那个 scope 就被取消，抓取会半路夭折 |
| 冷启动（`JuwApplication.onCreate`） | `ensureValid` 之后按闸门判断，先成绩后学业 |
| 学业页进页 | `force = false` 跑一次，数据还新时是零网络的空跑；顶栏「重新导入」传 `force = true` |

闸门 `AutoSyncRules`（成绩与学业共用）：**库里没数据 → 立即抓；有数据 → 距上次成功满 7 天
才抓**。数据一学期才动一次，天天抓只是白撞教务风控（登录闸门按失败次数停用）。
另有三种边界：有数据但没日期（老版本升上来的库）抓一次补上、日期是脏值当没记录、
日期在未来（时钟回拨）不抓。

**日期只在写库成功之后才落**——失败也记日期等于把闸门顶掉 7 天，一次网络抖动换一周不刷新。

**解析结果先校验再写库**（`ScholarProgressRules.validate`）：分组为空、课程为空、
分组名与明细的归属名全对不上，任一命中就整体放弃、保留旧数据。教务改版时宁可这次不更新，
也不能拿解析残值把用户原来那 83 门课换成几条垃圾。校验里**没有**「要求学分合计 > 0」
这条——公选课类别维度所有分组的学分要求都是 0.0（教务按门数记公选课），拿它当通用闸门
会把整个维度永久判失败；那条检查单独放在 `validateCreditTotal`，只对课程体系维度用。

失败对调用方是返回值不是异常：引导页与冷启动那两处静默（`runCatching` 套住），
学业页手动那次把原因显示出来。抓取串行（四个页面几秒），不并发打教务。

**抓取互斥**：`ScholarProgressSync` / `ScoreSync` 各持一把 `Mutex`，`tryLock` 失败返回
`InProgress` 让位，而不是排队等锁——排队意味着两个触发点串行各抓一遍（四个页面白打两轮），
而它们要的是同一份数据。能撞上的场景是冷启动与引导页登录成功挤在一起；同一页面里的重复
点击已经被 UI 的 `busy` 挡住。抓取途中通过 `onProgress(第几个, 共几个)` 回调出进度，
回调发生在调用方协程的上下文（内部不切线程，所以 UI 那边能直接写 state；
冷启动与引导页那两处不传回调）。

#### 成绩自动导入（`data/repo/ScoreSync.kt`）

用户要求「初始化配置完成后自动导入成绩」，实现方式与学业同构，复用同一份解析与仓库：
接口 `/jsxsd/kscj/cjcx_list`（不带 `.do`）、`ScoreParser.parseFetchJson`、
`ScoreRepository.replaceTerm` 按学期替换，与成绩页的 WebView 导入**写的是同一份数据**。

两条路并存是有意为之：首启配置完就自动导入（打开就有数据），而会话失效、需要人工输验证码时，
WebView 那条仍然是唯一能走通的后路（成绩页的「导入」按钮不动）。

#### 已知边界

- 无已保存的统一认证凭证（引导里跳过了登录）时不会自动导入，页面给「重新导入」+ 原因提示。
- 教务改版导致解析失败时本次不更新，页面保留上次数据；需要 App 侧适配。
- 真机实测（2026-09-27，Redmi 23113RKC6C / Android）：冷启动自动抓取落库
  `scholar_groups` 37 行 / `scholar_courses` 264 行、`scores` 66 行，
  `PRAGMA user_version = 13`；学业页渲染与汇总（161 / 104 / 0 / 32.8）与教务页面一致。

---

### 4.30 趣智校园蓝牙开热水（2026-09-27，P6；UI 规格见 §3.18）

**做了什么**：登录、账号、余额、账单、设备扫描、开阀链路全部搭起来，**真机跑通**
（开阀出水、结束用水结算 ¥0.04）。页面上把每一步的原始数据都摆出来，
而不是只给一句"失败"——清除命令的参数还在试错阶段，留着原文才有得查。

**与胖乖的本质差别**：胖乖（§4.10）开阀全程 HTTP，服务端直接下发到联网饮水机。
趣智校园在多数学校是**蓝牙水控型**设备——服务端只签发一份加密的开阀数据（`downData`），
真正让设备出水的是客户端通过蓝牙把它写进去。两个公开的第三方客户端（linyu、quzhi-lite）
只实现了联网型（`tcpDevice` 系列端点），蓝牙型一条都没有，这正好解释了
「扫描能认到设备、点开阀没反应」。

**模块落位**

```
domain/
  QzxyFrame.kt            帧编解码 + `#<小写十六进制>\n` 文本包装
  QzxyProtocol.kt         命令码、设备类型与状态、设备查询 / 消费记录 / 回包解析、清除候选表
  QzxyCredential.kt       密码与短信 secret 的本地推导
  QzxySign.kt             蓝牙接口的 signature
  QzxyClData.kt           结算响应里 `clData` 的 AES 解密
data/qzxy/
  QzxyApiConfig.kt        Base URL、版本号、BLE 广播名前缀
  QzxyApi.kt              Retrofit 端点，不签名 / 要签名两类分列
  QzxyModels.kt           响应包、DTO、会话失效判定
  QzxySession.kt          会话与认证参数装配（telephone / telPhone 双写）
  QzxySessionStore.kt     EncryptedSharedPreferences（secure_qzxy，独立于胖乖）
  QzxyRepository.kt       登录、账号、余额、账单、下单、上报
  QzxyBluetoothScanner.kt 低功耗蓝牙扫描 + 广播名解析
  QzxyGattLink.kt         低功耗 GATT 通道 + 服务表探测 + 分包写入
  QzxyDeviceStore.kt      已绑定设备
  QzxyDebugStore.kt       调试日志开关（默认关）
  QzxyClearStore.kt       清除命令试出来的可用参数（排到候选表最前）
ui/qzxy/                  QzxyScreen + QzxyViewModel
```

**帧格式**（来自公开逆向资料，加一条可对照的实测样本）

```
包头 0x60 | 长度(2B 大端, = 数据体长 + 3) | 0x80 | 功能码 | 0x00 | 数据体 | 校验和 | 结束码 0x16
校验和 = (数据体各字节之和 + 0x80 + 功能码 + 0x00) & 0xFF
外包装：`#` + 帧的小写十六进制 + `\n`，整串按 ASCII 字节写入设备
```

唯一能对照的样本是查询设备帧 `60000480230000a316`。`QzxyFrameTest` 钉的是
「与这个样本逐字节一致」，不是「与设备一致」——下标 3、5 两个固定字节由这一个样本反推。

**命令码**

| 码 | 作用 |
|---|---|
| 0x23 | 查询设备；响应带 projectId / deviceId / accountId / snCode / 随机数 / 状态 |
| 0x21 | 下发费率数据（`downData`），即开阀 |
| 0x31 / 0x32 | 开始交易 / 结束交易 |
| 0x85 / 0x86 | 采集消费数据 / 清除消费数据 |
| 0x22 / 0x88 | 结束费率 / 设置密钥 |

**两段式开阀**

```
蓝牙连接设备
→ 发查询设备帧，解析设备状态与 randomNumber
→ POST order/downRate/bluetooth/rateOrder（带 signature），拿 downData
→ 把 downData 通过蓝牙写进设备（功能码 0x21）
→ 用完读消费数据 → POST order/upload/bluetooth/data 结算
```

**signature 已经闭合**，见下面的「签名的来路」。参与签名的字段按键名字典序拼接、两次小写
MD5；下单接口只签 `telephone`（或 `telPhone`）、`deviceId`、`xfModel`、`randomNumber`
四项，其中 `randomNumber` 来自蓝牙实时读取——这也是为什么纯 HTTP 方案必然失败。
`QzxySign.candidates` 保留几种历史变体只为防备服务端按客户端版本走两套算法。

**官方两套字段命名**：App 内部有个 `MyApp.notBLE` 开关，对应
`macAddress / bigTypeId`（签名放 `telephone`）与 `deviceMac / mainTypeId`（签名放 `telPhone`）
两种组合。请求模型做成可切换，真机试通哪套就固定哪套。

**验证结果（2026-09-27，真机闭环）**

| 项 | 结果 |
|---|---|
| 域名 `v3-api.china-qzxy.cn`、版本号 6.5.28 | 与两个参考实现及 2025-11 的抓包分析一致，可用 |
| 认证参数（含 `telephone`/`telPhone` 双写） | 一致 |
| 密码（MD5 取后 10 位大写）与短信 secret | 已用公开文档给的样例数值验证 |
| 帧编解码 | 与实测样本一致，真机回包可解析 |
| 蓝牙通道 | **低功耗 GATT + Microchip/ISSC 透传服务**。不是经典 SPP——走 SPP 会触发系统配对并报「PIN 码不正确」，随后 `read ret:-1` |
| 写入口 / 回包口 | `49535343-8841-…` 写、`49535343-1e4d-…` 通知；`0000ff00` 那个自定义服务不是透传口，写进去设备不吭声 |
| 设备查询响应偏移 | 48 字节长包解析正确（deviceState / randomNumber / 协议版本都对上） |
| signature | `md5(md5(字段拼接 + "&key=" + loginCode) + "&key=sign-kailu-855c74a88b8b494187c99b08b8c9a744")` |
| 开阀 | **真机出水，服务端预扣成功** |
| 结束用水 | **真机结算成功（¥0.04）** |
| 回包成败与错误码 | 数据体首字节 `0x80` 判成功，否则第 2 字节是错误码；表取自官方 `KRWMDefine.h` |
| 消费记录偏移 | 官方 iOS SDK 反汇编与看雪反编译两份来源一致，42 字节短记录逐字段对上 |
| `clData` 解密 | AES-128/ECB，密钥与 IV 取自 `libklcxkjencry.so` 的 `.rodata`；明文格式待真机确认 |
| 清除设备记录 | 参数无公开资料，按候选表试；已能把「设备拒收（带错误码）」与「收下了但没清」分开 |

**完整流程（真机跑通）**

```
开阀：
  GATT 连接 → 发 0x23 查询设备 → 状态须为 0（空闲）
  → POST order/downRate/bluetooth/rateOrder（带签名）拿 downData（实测 48 字节）
  → 发 0x21 把 downData 写进设备 → 出水

结束用水：
  读状态；已是 3（消费完成，数据待采集）就跳过下一步，直接采集
  → 否则发 0x22（结束费率）停阀 → 轮询状态直到 3（中间可能先经过状态 6「结算中」，
    实测要五秒以上才到 3）
  → 发 0x85 读消费数据 → POST order/upload/bluetooth/data 结算
  → 发 0x86 清掉设备记录（参数按候选表试，见下）
```

**回包里有成败和错误码，别只看回读状态**（2026-09-27 补）

设备回包的 `[3]` 是 `0x81`（请求是 `0x80`），**成败只能看数据体首字节**：`0x80` 是成功，
否则第 2 字节是错误码。错误码表来自官方头文件 `KRWMDefine.h` 的 `KRErrorCode`：

| 码 | 含义 |
|---|---|
| 0x01 / 0x02 | 包头错误 / 包长度错误 |
| 0x04 / 0x05 | 功能码错误（设备不认这条命令）/ 数据格式错误（参数不对） |
| 0x06 / 0x07 | 校验和错误 / 结束码错误 |
| 0x08 | 来源错误 |
| 0xFF | 未知错误（数据体不足两字节时取它） |

`QzxyProtocol.decodeResponse` 负责这件事，`QzxyViewModel.sendFrame` 把收发原文与这句摘要
一起写进调试日志。清除命令连着失败三轮却查不出原因，就是漏了这一步：设备明明回了
错误码，代码只看「回读状态还是 3」，把「拒收」和「收下了但没清」当成同一件事。

**消费记录的字段偏移有两份独立来源**（2026-09-27 补）

`QzxyProtocol.parseConsumption` 的偏移不再来自推测，官方 iOS SDK 的
`-[KRConsumptionDetailsObject setConsumptionDetailsFromData:]` 反汇编与看雪分析里
`AnalyTools.analyWaterDatas` 的反编译**完全一致**：

```
[0]      0x80 成功标记（不属于记录本身）
[1..6]   时间序号 yyMMddHHmmss，一条记录的唯一标识
[7..10]  项目号   [11..14] 设备号   [15..18] 账号号
[19]     账户类别  [20..23] 使用次数
[24..27] 预扣金额  [28..31] 本次消费  [32..35] 费率   [36..41] MAC
63 字节长记录另有 [58..62] 校验码 tac
```

**清除命令的参数只能试**（2026-09-27 补）

功能码 `0x86` 有两份独立来源确认（官方 SDK 的 `krClearConsumptionDetails:` 与看雪反编译的
`CmdBtUtils.fanhuicunchu`），参数该带什么没有公开资料：官方那句注释只说「此数据由采集到的
消费记录对象生成」。而本校设备回的是 **42 字节短记录，没有长记录里的 `tac`**，
所以清除凭据不可能从记录里取。

线索在结算响应里：`POST order/upload/bluetooth/data` 返回一个 `clData`，官方 App 用
`JniUtils.decryptByAES` 解它，明文带一个 `-` 分隔符。密钥与 IV 就写在
`libklcxkjencry.so` 的 `.rodata`：

```
0xa282e  "20210118klcx@002"   密钥，16 字节
0xa283f  "1234567876543210"   IV，ECB 模式下不用
```

模式是 ECB，so 里那句 `Data not multiple of Block Size` 说明按无填充处理，
`QzxyClData` 因此走 `NoPadding` 再手工剥 PKCS#7。

**`clData` 的明文格式已经解开**（2026-09-27 真机）：

```
1790517647206-2609272200370000011f000010450000a03a00000002
└─ 毫秒时间戳 ─┘└──────────── 22 字节清除参数 ────────────┘
```

右边那段就是设备要的清除参数，布局 = **时间序号(6) + 项目号(4) + 设备号(4) + 账号号(4) +
使用次数(4)**，与刚读回的消费记录同源（记录 `[1..18]` 加 `[20..23]`，跳过 `[19]` 的账户类别）。
把它发进 `0x86`，设备立刻回到空闲。这是清除命令唯一被证实的参数。

**所以清除不必依赖服务端**：`QzxyProtocol.recordDigest` 直接从记录切片得到同样的 22 字节，
「记录早就结算过、上传被服务端拒」的场景（正是卡住用户的那种）也能清。那次实测里账户类别
也是 2，与使用次数撞值，所以另一种读法（末四字节按账户类别）也留在候选表里一起试。

`QzxyProtocol.clearCandidates` 的顺序是「记录摘要 → 服务端凭据（原文 / 十六进制 / 拆段 /
两段拼接）→ 整条记录 → 去掉标记字节 → 时间序号 → 时间序号+MAC → MAC → 空参数」，
`QzxyViewModel.runClearTrials` 逐条发、逐条判：**设备回包成功 + 回读状态离开「消费完成」**
才算通过。日常流程最多试 `CLEAR_TRIAL_LIMIT`（3）条；调试区里的「清除试错」跑完整张表。
试通哪条记进 `QzxyClearStore`，下次排最前。字节完全相同的候选会被去重，所以这条偏好只在
服务端格式变化、摘要失效时才起作用。

**耗时分布与提速**（2026-09-27，真机跑通后）

开阀与结束用水都比官方 App 慢，慢的地方全在蓝牙侧，与 HTTP 那一段无关：

| 环节 | 原来的做法 | 现在 |
|---|---|---|
| 建链 | 点「开阀」才连，GATT 建链加服务发现一到两秒 | 选中设备时后台预连接（`preconnect`），开阀直接复用；另外选中设备先 `stopScan`——扫描与连接抢同一个无线电，扫描不停建链会慢好几倍 |
| 等设备结算 | 固定先睡 600ms 再查，间隔 600ms，预算五秒 | 先查再等，间隔 300ms，预算 12 秒。设备往往在停阀回包到达时就已结算完，先睡一觉纯属白等；预算放宽是因为状态 6 实测要五秒以上才变 3 |
| 清除后校验 | 固定睡 800ms 再回读状态 | 300ms |
| 清除参数 | 每次从候选表第一条试起 | 试通哪条记进 `QzxyClearStore`，下次排到最前（每条候选要一次往返加一次回读，命中排在第五位就是每次白等四轮） |

顺带修掉四处正确性问题：

- **写入排队**：Android 的 GATT 同一时刻只允许一个操作在飞，`writeCharacteristic`
  排队失败时返回 false 且不报错。刚连上时最容易撞上（CCCD 写还没落地），现象是
  「连上后第一条命令超时」。现在每次写入等 `onCharacteristicWrite` 回执（最多 1 秒），
  最多 3 次；回执失败重试，超时直接报「GATT 写入回执超时」，不再把「排上队」当成「写成功」。
- **通知只开已知的两条**：原来遍历所有可通知的特征值写 CCCD，后面的会被静默丢掉，
  开哪条全看遍历顺序。现在照官方 SDK 只开回包口（流控口其次），回包口 CCCD 写等
  `onDescriptorWrite` 回执（最多 2 秒）再让调用方发第一条命令；流控口只开通知，不阻塞建链。
  服务表里认不出来才退回遍历。
- **链路复用按地址判**：`isConnected` 只说明「连着某台设备」，连着 A 去开 B 会把指令发给 A。
  新增 `QzxyGattLink.isConnectedTo(address)`，`ensureLink` 改用它。
- **下单前必须有服务端登记信息**：`ensureServerDevice` 兜底查一次，拿不到就报错，
  不拿蓝牙广播地址凑数——那样订单会挂错设备，结算报「未找到订单」。

阶段耗时（`连接 1200ms` 这样）会进诊断文本的「耗时」行。觉得慢时复制一次诊断，
就能看出卡在建链、蓝牙往返还是 HTTP。

**真机踩到的四个坑**（都不是文档里能查到的）

1. **MAC 首字节不一致**：Android 上报的蓝牙地址首字节是 `C0`，设备在服务端登记的首字节是
   `00`，后五字节相同。下单必须用**服务端登记值**（`device/info/mac` 响应里的 `macAddress`），
   用蓝牙地址会让订单挂到「另一台设备」上，结算时服务端回「未找到订单」。
   quzhi-lite 里那个「C0 开头换成 00」的替换就是同一个坑。
2. **结束用水必须先停阀**：直接去读消费数据，设备在出水或空闲态都没有记录可给，
   `0x85` 不回包，表现成「等待回包超时」。停阀后设备要几百毫秒到几秒才结算完，
   所以代码里是轮询到状态 3 再采集。
3. **设备在「消费完成，数据待采集」时只收采集与清除**：这时发 `0x22` 会被回 `81 01`
   （数据体首字节不是 `0x80`，错误码 1「包头错误」——这个码在这里其实是「当前状态不接受
   这条命令」的意思）。帧本身没问题：官方 iOS SDK 的 `KrStopUsingWater:` 反汇编出来就是
   control `0x80` + funCode `0x22` + 单字节 `0x00`，与我们发的一模一样；官方 App 的状态派发
   也印证这一点，状态 3 只调 `caijishuju`。所以「结束用水」在状态 3 会**跳过停阀**直接采集，
   否则卡着旧记录的用户一点「结束用水」就被挡，永远走不到后面的结算与清除。
4. **状态 6 是「结算中」，官方头文件里没有这个值**：停阀后设备先落到状态 6，实测持续
   五秒以上才变 3（2026-09-27）。老代码的轮询预算只有五秒，超时就报「设备没有生成待结算的
   记录」，用户得手动再点一次「结束用水」才成功——症状是「第一次结束失败，再点一次就好了」。
   现在预算给到 12 秒，等待文案里带上状态名（「等待设备结算（结算中（记录写入中））」），
   真超时也照实说「设备还没结算完，过几秒再点一次」。

   轮询走 `readStateQuiet` 而不是 `ensureDeviceState`：后者每轮都会把流程文案改回
   「读取设备状态」，与「等待设备结算」来回切，用户看到的是文字在两句话之间跳
   （2026-09-27 反馈）。现在只在状态真的变了的时候改一次文案。另外连续四次读不到状态
   就收手，报「读不到设备状态」而不是耗完 12 秒再说「还没结算完」。

**共用设备的残留记录与重试口径**（2026-10-18 补）

- **状态 3 挡开阀时先分清是谁的记录**：`wateringStore` 里有这台设备的本地记账，
  说明大概率是自己的上一笔，主出口仍是「结算设备记录」；没有本地记账，说明是上一位
  用户没结算，失败卡主按钮改成「清除并继续开阀」——一次点击完成「清除 0x86 → 回读
  状态 → 重新下单 → 写 downData」，不再让用户先失败一次、再自己找清除入口。
  清除仍是显式动作，不会在后台静默毁掉别人的消费凭证。
- **downData 失败可重发，不重复下单**：`rateOrder` 成功但蓝牙写入失败时，服务端已经
  预扣；旧流程再点「重试开阀」会重新 `rateOrder`，可能多一笔预扣。现在把
  `downData / orderNo` 留在进程内（`pendingOpen`，两分钟 TTL），失败卡主按钮是
  「重发开阀数据」，复用同一张订单只补蓝牙写入。进程被杀后这单仍会丢，属于已知边界。
- **查询超时从 1.5 秒放到 3 秒**：刚建链、CCCD 还没落地或设备刚唤醒时，1.5 秒经常
  不够；`QzxyGattLink.request` 一超时就 `close()`，用户看到的是「设备没有回应」。
- **扫描同时认广播名与服务 UUID**：名字被系统截断/隐藏时，广播包里的
  `49535343-fe7d-4ae5-8fa9-9fafd205e455`（Microchip/ISSC 透传服务）也能认出来；
  名字明确是别的设备时仍直接排除，避免通用 UUID 误收。
- **流控通知与回包分通道**：`FLOW_CONTROL_UUID` 的字节不是 `#<hex>\n` 回包，
  混进 `incoming` 会卡在 `readUntilLineFeed` 的缓冲里顶掉下一条回包。现在单独进
  `flowControl`（合并通道，只留最新），目前只隔离、不猜字节语义；写入节流靠 GATT
  写回执串行化。

**签名的来路**：官方 APK 加固过，DEX 里搜不到 `getSign`。从 `libklcxkjencry.so` 反汇编
`Java_com_klcxkj_jni_JniUtils_signParams` 得到三段式：先 `append("&key=", 5)` 再拼第二个参数
做一次 MD5，最后拼上 `.rodata` 0xa1fd0 处的全局串做第二次。同段里的 `1234567876543210`、
`20210118klcx@002` 是 `clData` 解密用的 IV 与密钥（见上），`20181201klcx@001`、
`sign-kailu=` 那批常量属于 `getSk`/`getCk` 等旧协议密钥入口，与签名无关。

**还差什么**：只剩「清除命令该带什么参数」。若候选表全试完仍无一条通过，
下一步是拿官方 App 开一次水，在开发者选项里打开「启用蓝牙 HCI 信息收集日志」，
`adb bugreport` 导出 `btsnoop_hci.log`，Wireshark 里直接看官方那一条 `0x86` 的数据体——
HTTP 那一侧抓 HTTPS 要装证书，蓝牙这一侧不用。清除命令的收发原文已经全进调试日志，
对着比即可。

**风控与请求预算**（2026-09-27 收尾）

服务端是第三方的。账号被限流或被判异常，用户就没热水可用，所以请求侧按「够用就好」写：

| 做法 | 原因 |
|---|---|
| 一条流程只下一次单、只上报一次 | 开阀 1 个 HTTP、结束 1 个 HTTP，与官方 App 同级 |
| 设备信息查询串行化（`infoMutex`） | 扫描一次能扫出十几台，并发打过去既没必要也显眼 |
| 设备信息与「已查过的地址」跨扫描保留 | 重扫只查新出现的设备：连扫三次从四十多个请求降到十几个 |
| 账单只在趣智校园页里拉（`loadBillsOnce`） | 今日页卡片那份实例从不显示账单，原来每次冷启动白拉一次 |
| 「刷新」在请求进行中直接返回 | 连点不该变成两轮请求 |
| 短信验证码 60 秒冷却 | 服务端对验证码有频率限制，连点会被拒 |
| 不自动重试 HTTP | OkHttp 默认只重连（`retryOnConnectionFailure`），不对 5xx 反复打 |
| 同一时刻只跑一条流程（`beginFlow`） | 两次开阀各下一单、各写一次设备，是最容易被判异常的行为 |
| 空闲 60 秒主动断开蓝牙 | BLE 外设被连上后通常停止广播，一直占着会让这台水表在别人（和官方 App）的扫描里消失。放水中不断——设备侧可能把断连当成「用户走了」 |
| 会话失效不误判 | `QzxySessionExpiredException` 显式排除含「签名」的文案，签名不通过不踢人重登 |
| `version` 跟着官方版本走（6.5.28） | 服务端若按客户端版本卡人，这个值旧了会连不上，先动 `QzxyApiConfig` |

**不做的事，以及为什么**：不伪造 `User-Agent`（官方 App 用 `okhttp/4.2.2`，改它等于装成官方客户端），
TLS 指纹也无法对齐——那由 OkHttp 版本与 TLS 栈决定。这两项是第三方客户端的固有特征，
改不掉也不该去装。

**已知的异常信号**（保留但可控）：调试工具里的「试签名」会连发多次下单请求（签名已确认，
正常第一次就中，只发 1 次）；扫描时每台设备一次 `device/info/mac`（官方 App 同样如此）。

**诊断文本的隐私边界**：诊断里带设备名（含房间号）、蓝牙地址、账号号与清除凭据，
都是排查必需品。复制给别人之前先想一下对方需不需要这些。

**会话与登录方式**（2026-09-27 补）

趣智校园**没有独立的 token**：`loginCode` 得跟 `projectId` / `accountId` / `userId` /
`telephone` 凑齐才能调接口（见 `QzxySession.authFields`）。登录成功后这串东西落在
`secure_qzxy`，下次进来直接就是账号态，不需要重新输密码——这就是「token 登录」在这边的形态。
密码不落盘，会话被服务端判失效时才要求重新登录（胖乖同样不存密码，两边一致）。

另外给一个**会话串登录**入口，与胖乖的 Token 粘贴登录同构，差别只有两点：

| | 胖乖 | 趣智校园 |
|---|---|---|
| 粘什么 | 服务端签发的 token，单独可用 | 整串会话（`loginCode` + `projectId` + …） |
| 怎么认 | 直接当 token 用 | `QzxySessionLink` 认 JSON / 表单 / 逐行键值三种写法 |

解析时那条 `(?<![A-Za-z0-9])` 不能省：登录响应里同时有 `loginCode` 与 `v3LoginCode`，
少了它会把后者的值当成前者。

**先验后存**：先拿候选会话调一次只读接口（`/project/info/triple`），通了才落盘。
否则用户看到的是「已登录但什么都查不到」，分不清会话错还是网络问题。

导出侧是诊断页的「复制会话串」，供换机或另一台设备粘贴。入口只放在诊断页，
提示语按凭据口径写：这串东西能直接换到登录态。

账号一行的手机号**默认遮蔽**（`QzxyPhoneMask`，11 位留前 3 后 4），点眼睛展开，
与「我的」页学号同一个口径——页面会被截图、会被旁人瞥见。

**凭证与隐私**：会话存 `secure_qzxy`（EncryptedSharedPreferences），
已从 `backup_rules.xml` / `data_extraction_rules.xml` 排除；OkHttp 日志只有 BASIC 级，
不打 header 与 body；会话失效判定**显式排除**含「签名」的文案——签名没过不该把人踢去重新登录。
会话串只有用户在诊断页主动点「复制会话串」才出得去，App 不会自己带它到任何地方；
诊断文本里**不含** `loginCode`。

**计费**：走的是用户自己的趣智校园账户，每次开阀照常扣费，没有绕过计量。
页面底部有免责声明：第三方非官方功能、协议来自公开逆向资料、与校方及设备厂商无关。

**充值不在 REST 层**（2026-09-27）：网关的服务前缀只有
`user / project / device / order / account / coupon`，没有独立的支付或充值服务；
充值实为支付宝小程序 `2018090661238647` 的 `pages/recharge/recharge` 页。官方 App 用
mPaaS（`ALIPUB080DFB9030956`）把小程序嵌在应用内跑，`assets/qzxy.pfx` 是它的客户端证书；
复刻内嵌等于冒充官方身份，所以本 App 只做深链，支付全程在支付宝内完成。manifest 里
`<queries>` 必须声明 `alipays` scheme——Android 11+ 的包可见性会让未声明的
`startActivity` 直接抛 `ActivityNotFoundException`（真机上从本 App 点「充值」已跑通）。

**结算窗口与退款口径**（2026-10-09，用户报「设备长时间没用自动关停后，再想起来结束用水
只得到『设备没有记录』，预扣的 7 块要次日才退回」；UI 见 §3.18）：

- **设备自动关停没有可预测时刻**：一直在用会用到预扣耗尽，停手不用过一阵会自动关，
  开阀时点推不出来。开阀响应里的 `autoDisConTime` 语义未定论（更像「停止用水后的
  自动关停/断开秒数」而不是会话关停时刻），**界面上不出现任何倒计时/预计时刻**，
  只落诊断文本（`lastOrderDiagnostic` + 调试日志）供真实样本对表。
- **反应式对账取代预测**：页面 / 今日页可见（RESUMED）期间每 30 秒读一次设备状态
  （`reconcileWateringOnce`，与开阀/结算共用同一把流程锁，绝不插进别人的流程；
  连读 3 次拿不到就收手，回前台复位）。读到状态 3 → **自动结算**（一次前台周期一次，
  失败不自动重试；**2026-10-11 起可关**：`QzxyDebugStore.autoSettleEnabled` 默认开、
  开关在趣智页尾「用水设置」，关着时读到状态 3 即视为终端态收手，结算全部手动）；
  读到空闲 → 记账转「待核对」。
- **结算失败不再清设备记录**（`QzxyWaterFlow.stop`）：上报被拒时记录**保留**——它是重试
  结算的唯一凭证；「清除设备记录」降为显式放弃出口。旧实现失败也清，是「事后只能得到
  『设备没有记录』」的根之一。
- **记录已丢时的资金口径**（客户端无解，如实告知）：客户端在设备记录丢失后没有任何
  上报凭证（也不伪造），这笔单只能由平台超时统一处理。**用户实测口径：未结算的单在
  「消费记录」里先显示 ¥0，预扣到账/平台处理后才显示实际金额，一般次日**。App 的
  「待核对」记账与文案都按这个口径写，不再承诺客户端「当场退回」。
- **记账过期不清**（`QzxyWatering.EXPIRE_MILLIS` 只切展示口径）：留痕是预扣去向唯一
  的界面痕迹；清理出口 = 结算成功 / 清除记录 / 用户显式「已到账」。桌面小组件
  1 小时不上桌的规则不变。
- **实现收口**：`QzxyWaterFlow` 从「抽出未接线的状态机」变成唯一实现（开阀/结束/清除），
  `QzxyViewModel` 只接线（下单 / 文案 / 对账驱动）；单测 12 例覆盖包括「上报失败不清
  记录」。`sendFrame` 重构为 `requestRaw`（帧级校验、业务成败交给调用方）。

---

### 4.31 教材（学生教材确认，2026-09-28，P6；UI 见 §3.3 详情面板教材行）

**做了什么**：教务「教材管理 → 学生教材确认」的书目抓取入库（Room v14 `textbooks` 表），
导入课表写库成功后自动抓对应学期，课程详情面板直接显示这门课用哪本书。
**2026-10-07 起，今日页课程卡（焦点卡 / 时间轴行 / 明天块）也显示教材名；2026-10-08
收口为挂在课名右侧同一行**（§3.3「课程卡教材名」，用户口径：另起一行会再抬卡高）：
只显书名——单本直显、多本「书名1 等 N 本」（2026-10-08），
作者/出版社不进卡片；数据源 = `ScheduleRepository.textbookTitlesByCourse`（「课程名 →
书名列表」映射流，学期钥匙收口在私有 `currentTermFlow`，与详情面板同一把），
`TextbookDao.observeForTerm` 是新增的整学期订阅查询（纯查询，无写路径变化）。

**接口口径**（2026-09-28 实测）：

| 项 | 值 |
|----|-----|
| 入口 | 教务 → 教材管理 → 学生教材确认（菜单 data-id `NEW_XSD_PYGL_NJCGL_XSJCQR`） |
| 壳页 | `GET /jsxsd/nxsjc/jccx`（layui 表格，学期下拉 `select#xnxqid`） |
| 数据 | `GET /jsxsd/nxsjc/xsjcqr?xnxqid=<学期>&pageNum=&pageSize=` ——与考试/成绩同一套 layui 形态（不带 .do、`code=0`、`count`、按 count 翻页）；学期参数名是 `xnxqid`，**不是**课表页的 `xnxq01id` |
| 字段 | `kcmc`=课程名称（**与课表课名逐字一致，直接当关联键**）、`jcmc`=教材名称、`jczz`=主编、`cbsmc`=出版社、`jcbc`=版次、`isbn`、`jcdj`=定价、`skjs`=上课教师、`zdzt`=征订状态 |
| 红线 | 壳页里另有 `xsjcisxy.do`（征订/不征订确认，**POST**）——那是学生向教务确认订购的写操作，任何路径都不碰；本功能只读 `xsjcqr` |
| 空学期 | `code=0 count=0` 属正常（征订未发布/学期无教材） |
| 脚本 | `scripts/fetch_textbooks.py`（照考试脚本骨架；学期口径校验用行内 `xnxq01id` 字段） |

**归属与查询**：`Textbook` 挂 `courseName` 而非课程行 id（同笔记/作业，覆盖导入换 id 不丢），
另带 `term` 区分学期——课程详情按「**当前课表的 `Timetable.term` + 课程名**」查询
（`ScheduleRepository.textbooksFor`）；课表没有学期号（旧数据/手工建表）时不显示教材，
没有学期钥匙就查会串学期，宁可少显示。

**写库时机**：教务一键导入**写库成功后**把数据学期写进目标课表（`Timetable.term`），
再由 `TextbookSync.syncForTerm`（`CasSession` OkHttp 直抓，不开 WebView）顺带抓该学期教材：
解析先校验再按学期整批替换（`replaceTextbooks` 事务先清后插）、失败绝不挡导入主流程、
成功才记日期（`textbook_sync_date`，只记录不设闸门）。
**超出一页上限（200 条）不写入**：教材是确认清单不是流水，异常大的 count 更可能是接口行为
变化，拿着半页数据写库不安全。

**触发点口径**（2026-10-06 按代码订正，本节早先说「教务/JSON 导入都抓」与实现不符）：

| 触发点 | 抓不抓 | 说明 |
|--------|--------|------|
| 教务一键导入确认后（`JwImportScreen`） | 抓 | 主路径 |
| 冷启动补抓（`TextbookSync.backfillIfMissing`） | 抓 | 见下 |
| 导入 JSON 文件 / 我的→数据→导入备份（`importJson`） | **不抓** | 教材从备份自己的 `textbooks` 段恢复（带数据才整体替换、空段不动现有数据），数据来自导出方而不是教务当前状态 |
| 首启引导的自动导课表（`OnboardingScreen`） | 抓 | 与教务导入同一口径 |

**同源 fetch 也要认「会话被顶」**（2026-10-06 复核补）：考试 / 成绩是在**已加载页面里再发
fetch**（`fetchJsonInWebView`），被顶时 layui 接口回的就是那段 `flag1` JSON，此前会一路走到
解析器变成「教务返回 code=-1，msg=」——既没说清原因，也没走 §4.27 的会话冲突诊断。
现在在 `fetchJsonInWebView` 里一次性判掉（`JwImportDiagnosis.looksLikeSessionConflictBody`，
与 OkHttp 路径同一判据、文案用与探针同源的短串「其它地方登录」），报成 Error 页并置
`lastFetchConflict`，调用方的超时分支据此不再用「请求超时」把它盖掉。

**抓取失败不再无声**（2026-10-06）：`syncForTerm` 的结果落一条 `Log.i`（`TextbookSync`），
导入路径失败时另补一条气泡「教材未同步：<原因>」。用户此前只能看到「导入成功、详情里没有
教材」，分不清是教务没数据还是会话被顶掉（见 §4.27「会话被别处登录顶掉」）。气泡走
`JwImportResultBus`，排在导入结果之后——那条通道已从单值 StateFlow 改成**排队**语义
（`OutcomeQueue`），否则后一条会把还没消费的前一条顶掉。

**重登要过独立窗口**（2026-10-06 复核补）：`CasSession.fetchHtml` 发现会话作废时会作废本地
会话并重登一次，但一次同步里有多个 URL（学业四个维度、选课三个 URL、成绩按翻页数），每个都能
各自触发一次完整 CAS 登录——而每次登录都会把对方顶掉（强智单会话，PC 上跑脚本时尤其明显），
不加冷却就是**登录乒乓**。现在走 `SessionStatus.tryAcquireRelogin`（60s 窗口，与
`tryAcquireAutoLogin` 分开：那条管用户主动触发，这条管取数途中发现失效）。同时
`clearCookies` 与重登收进 `loginMutex`（与 `logout` 同一条纪律：清 cookie 落在别人登录链路
中途会把它打成「取不到 SSO ticket」）。

**冷启动补抓**（`TextbookSync.backfillIfMissing`，2026-10-06；同日复核补两道判据）：当前课表有
学期号、但该学期一条教材都没有时，冷启动补抓一次。教材原本只跟导入抓那一次，而那次会因为会话
被顶掉而静默失败——补抓把「一次性失败」变成可恢复。闸门在 `AutoSyncRules.textbookBackfillDue`，
**三道判据**：

1. 该学期教材非空 → 跳过（成功过就不再打扰教务）；
2. **该学期抓成功过**（`textbook_fetched_term`，含「成功但 0 条」）→ 跳过。
   少了这一道，下学期还没征订、或实习/毕设这类本来没教材的学期会永远满足条件，
   变成**每 6 小时打一次教务、永不停止**——判据不能只看「表里有没有行」；
3. 距上次**尝试**不足 6 小时 → 跳过。**记的是尝试时刻不是成功时刻**（与
   `textbook_sync_date` 相反）：它挡的是「反复打」，「还该不该重试」由前两条判定。

**空结果不写库**（同日复核）：`replaceTextbooks` 是「删后插」，空列表等于把该学期旧教材清掉。
征订窗口关闭 / 选课变动 / 教务抽风时接口就会回空，那不该毁掉已经拿到的数据——解析结果为空时
只记「抓过这个学期」，不动库（文档一直这么承诺，实现此前是反的）。

搭在冷启动 CAS 那组，顺序是成绩 → 考试 → 学业 → 教材。

**展示**：课程详情面板「类型」之下，每本书一行（`textbookLine` = 书名 · 主编 · 出版社 · 版次，
空段跳过；ISBN/定价不进弹窗——识别一本书用不着，堆上只会长行换行）。
多本时逐行，首行带「教材」标签。今日页/课表页两个调用点接入；学业页的详情弹窗是本地私有
组件（`ScholarCourse` 口径），不含教材。

**备份**：`CourseExport` 新增可选 `textbooks` 段（全带默认值），导出全学期带走、
导入带数据才整体替换，空段不动现有数据——与成绩/学业同口径。

---

### 4.32 快趣出行：应用内登录与用车（2026-09-28，P6；A 档只读 + B/C 档开锁/锁车/还车，UI 见 §3.3 校园服务 / §3.9 出码页）

背景：快趣微信小程序逆向（协议记录 `docs/kvcoo-miniprogram-analysis.md`，仅本地）确认后端
`api.kvcoogo.com/ManagerApi/api` **无请求签名**，token 是唯一凭证。A 档 = **账号密码登录 +
骑行中订单只读查询**；B/C 档（用户拍板同日追加）= **应用内直接开锁 / 临时锁车 / 还车**——
写操作有计费后果（开锁起计费、还车失败继续计费、调度费），实现里用「确认弹窗 + 零自动重试 +
定位前置 + 降级官方渠道」四道闸兜责任，见下。

**登录**：`POST /v1.0.0/userLoginByPassword {mobile, password: MD5(密码)}`（标准小写 hex，
spark-md5 口径）；响应 `{resultCode, errorCode, resultMsg, result:{token, mobile, ...}}`，
`resultCode==1 && errorCode==0` 为成功——**与微信授权登录（appletLogin）同一 token 体系**，
登录后 `client-type: 1` + `token` 头即可调全部接口。统一信封与解析在 `domain/KqcxAuth.kt`。
请求与失败分类在 `data/kqcx/KqcxAuthClient.kt`（裸 OkHttp 对齐 `KqcxBikeClient` 形态；
**无日志拦截器**——token 与密码凭据不进 Logcat）。

**未登录时登录表单常驻一句「忘记密码」的出路**（2026-09-30 用户要求）：改密码只能在小程序里
做，App 既不存找回流程也没有客服通道。两个落点从解包产物核对过——小程序登录页的「忘记密码」
（`pages/login/forget`，手机号 + 短信验证码 → `resetPassword`）、登录后「设置 → 修改密码」
（`pages/setting/modifypassword`，只填新密码 → `updatePassword`）。同一句里写明**密码
（连同手机号）默认加密保存在本机**（`KqxCredentialStore`，为的是 token 失效后静默重登），
可关闭「记住登录」；关掉则凭证不落盘、仅本次进程有效（2026-10-21 默认改为开）：
别让人以为"App 不用密码"。

**字段解析的宽容口径（2026-09-28 真机教训）**：快趣后端字段类型不稳定——数值可能给字符串
（`"3665"`）或带小数点的 number（`85.0`）、布尔可能给 `0/1`。首版用严格 `@Serializable` DTO
反序列化，小程序开车后的真实订单直接抛「骑行订单字段解析失败」（用户真机报告）。现在全部
逐字段按 `JsonPrimitive.content` 宽容取值（`text`/`long`/`double`/`int`/`bool` 五个私有扩展），
未知字段天然忽略。**不要再退回 DTO 硬解**（`KqcxAuthTest` 有混合类型回归用例）。

**会话**：`data/kqcx/KqcxSessionRepository.kt`（Graph 单例 `kqcx`）。**token 仅内存**（@Volatile，
与一卡通/电费同红线）；凭证（手机号+密码）落 `KqxCredentialStore`（EncryptedSharedPreferences
+ Keystore 损坏自愈，文件 `secure_kqcx.xml`，**已排除云备份与设备迁移**）——存密码而非 token
是因为 token 短命、密码登录无签名可随时静默重登。token 失效策略「按需重登」：查询遇
`looksLikeTokenError`（保守启发式，宁漏判不误判）清 token 重登一次再试一次；重登互斥
（`AtomicReference<CompletableDeferred<Boolean>>` 单飞，并发调用共享同一次结果）。
**静默重登必须带 `remember = true`**——凭证本来就在盘上，重登只是换 token，不能顺手清凭证
（2026-10-21 修「更新后偶尔掉登录」：旧版走默认 false，每次静默重登都清一次）。
TTL 未知是已知边界，真机确认后如有必要再补主动续期。

**查询**：`queryUnderwayOrder`（POST 空 body）→ `KqcxAuth.Ride`。无骑行订单两种形态：
`result` 为 null/空串/空对象（宽容），或 **errorCode == 12003**（「订单已结束」）。字段名与
单位来自小程序骑行页消费代码：`carNum` / `bluetoothName` / **`totalDate`（已骑秒数）** /
**`payMoney`（当前费用，分）** / `lat` / `lng` / `lockStatus`（1=锁上，锁上不等于订单结束）/
`currentPercent`（电量）。时长文案 `formatRideDuration` **逐字对齐**小程序 `simplehumantime`
的怪渲染（秒 ceil 成分钟数填进「分」段：骑 1 分钟显示 `00:01`、骑 65 分钟显示 `00:01:05`）。

**用车动作（B/C 档，官方调用链逐字对齐）**：

| 动作 | 调用序列（POST form） |
|------|----------------------|
| 开启（两段式，2026-10-21） | `createOrder {carNum, lat, lng, clientType:1, clientVersion:V6.0.0, locationSource}` **只建单、不发票**——车辆保持已锁，骑行卡按「已锁」落位；解锁由用户到车边点骑行卡上的「解锁」（对在案订单重发 `greenCarUnlock`，走「重试开锁」那条现成路）。地图远程开锁 / 车号面板 / 「重新使用该车」都走这条。头盔流程的车（建单响应 `helmet==1&&helmetConfig==1`）两段式覆盖不了，退回完整流程 |
| 完整开锁 | `createOrder` → 官方 `helmet==1&&helmetConfig==1`（或开锁返 16015）走 `helmetUnLock {}`，否则 `greenCarUnlock {lng, lat, locationSource}`。**扫车身码自动开锁专用**（`KvcxAction.DIRECT_UNLOCK`）——人已站在车边，扫完即开 |
| 临时锁车 | `greenCarLock {lng, lat, locationSource}`（官方骑行面板「锁车」，订单与计费继续）；响应过 `parseSuccess`（2026-10-08 起解析信封——旧版把响应整个丢弃，服务端明确拒绝被吞成笼统"未确认"） |
| 还车 | 未锁先 `muteCarLock`（静默锁，尽力而为）→ `endTheOrder {lat, lng, locationValid, locationSource, locationTime(秒), bdStatus:0, bdPileStatus:0, bluetoothName:"", dispatchFlag:2}`；`locationValid` 按坐标是否有效传 1/0 |
| 写后确认 | 开锁/还车写接口成功即返回（2026-10-18 提速，不再阻塞查 `queryUnderwayOrder`）；只有临时锁车保留 8 次 ≈ 12 秒的有界确认 |
| 还车后 | 手动还车结果卡先出「结算中」，`queryUnPayOrder {}` 在后台补查（≤3 次）；有欠费把 `unPayMoney` 显示出来 + 给「去微信结清」（自动还车仍等结果记账；**支付本身不做**） |
| 定位 | 官方 `wx.getLocation type:"gcj02"` → App 用 `BikeLocator.currentLocation`（已是 GCJ-02）；新定位 `realtime`，五分钟缓存 `session`；还车/重试开锁/临时锁车可退到车辆锚点 `vehicle`；只有还车在锚点无效时传 `none` + `locationValid:0`，开锁类动作中止 |

**四道闸（写死的责任边界）**：① **零自动重试**——`createOrder`/`endTheOrder` 超时绝不重发
（防重复订单/重复结算），超时后只读刷新判状态；「重试开锁」是安全特例（订单已在案，只重发
`greenCarUnlock`，官方同款）且由用户手动点；② **写操作不先查在案订单**（2026-10-18 提速：
开锁/还车直接发写请求，在途订单由服务端 12022 / 12003 兜底；调用方用当前骑行快照判断
是否在骑；重试开锁的车号也从当前卡片带入，不再预查；`createOrder` 成功后
`greenCarUnlock` 遇 `IOException` 返回 `Unconfirmed + 最小订单`，先把卡片和重试出口给用户）；
③ **写操作二次确认**（开锁示完整车号+计费、还车示核对车辆物品+调度费风险；
**临时锁车与「解锁继续骑」免确认**——2026-09-28 用户拍板：两者都发生在既有订单内、
没有新增的计费后果，误触代价低（「计费继续」由动作成功后的提示交代），`kvcxConfirmDialog`
对它们返回 null。2026-10-01 打磨：正文改成**编号要点**（`KvcxConfirm.points`，计费 / 核对车号 /
支付分出路各一条，长文配 `heightIn(max=400.dp)` + `verticalScroll`，小屏与大字体档位下按钮
不被挤出去）；**只有开锁能免确认**（弹窗里勾「以后开锁不再确认」→ `ebike_unlock_confirm`，
出口在快趣出行设置 → **开锁与还车**，勾选那一刻就地写明"以后点一下就直接开锁并开始计费"；
还车涉及结算与调度费、重试开锁是异常路径，**都保留每次确认**。关掉后 `requestKvcxAction` 走直发
分支，定位前置仍排在它前面——四道闸里只有这一道允许用户自己关。**弹窗状态的读取收在
`KvcxConfirmHost` 里**（2026-10-01，用户报「点确认开锁后卡一下」）：读在页面函数体里会让开关
弹窗重组整页、连带地图走一遍 `AndroidView` update，那几帧本来就在做关窗口 + 切 busy + 发请求）。
**临时锁车的"未确认"不是失败**（2026-10-08，修用户报的「开锁后临时锁车特别容易失败，
第二次点就能成功」）：车把"已锁"上报服务端是**异步**的（落锁后 IoT 上报常见 3~15 秒，
开锁后紧接着锁车——没骑就走——上报还排在解锁上报之后），旧版确认窗只有 4×1.5s ≈ 6 秒，
几乎必然等不到，被抛成 Error 红字；用户再点的那次其实状态已同步、命中客户端幂等早退
（已锁直接返回）假性"成功"（该早退同日已删，见段末）。修后三件——**确认窗只留给锁车**
（8 次 ≈ 12 秒 `LOCK_CONFIRM_ATTEMPTS`；开锁/还车 2026-10-18 起写成功即返回，不再等 4 次
`queryUnderwayOrder`——锁车免确认、无新增计费后果，多等几秒换确定性）；**超窗不抛错**，
返回 `LockOutcome.Unconfirmed`（形态镜像
`UnlockOutcome`，含 `lastKnown` 快照），控制器置 `lockPending` + **Warning** 提示
「车辆回报稍慢；可点刷新核实，车没锁上就再点一次锁车」，状态胶囊显「锁车确认中…」；
**悬置裁决与快照落库收成一份**（`KvcxRideState.withRideSnapshot`，`query()` 与
`refreshInternal()` 共用）：刷新到已锁即消解，刷新到未锁**保持悬置**等下一次快照
（"未锁快照"不代表失败，那正是本条立论；出口是再点一次锁车，仓库层不再早退）、
订单消失也解除。**已锁也照发**（同日用户口径「锁车失败被系统误判成功时也要能再锁」）：
原 `temporaryLock` 里的「已锁为幂等 no-op」删掉——用户再点一次锁车真重发 `greenCarLock`
（手动单次、零自动重试不变；车若确实已锁，重发无害、确认窗首查即过）；`resumeRide`
成功文案按调用前锁状态分流（已锁→「已解锁，继续骑行」/未锁重发→「已确认开锁」），
成功即清 `lockPending`。**别把"确认不到"当"失败"报**——锁车确认、还车后台结算都是
同一语义家族（开锁 2026-10-18 起不再阻塞等确认）。
**骑行状态的时序纪律**（2026-10-09，修用户报的「开车后要冷却才能锁车还车 / 骑行中显示
锁状态未知 / 同车还车后立即再开、骑行时长有概率丢失」）：五件收口——① **开锁冷却 3 秒**
（`UNLOCK_COOLDOWN_MS`）：开锁类动作（`unlock` / `retryUnlock` / `resumeRide`）**成功**才落
`unlockCooldownUntil`（未确认不落，用户仍可锁车兜底），页面把锁车 / 还车置灰并在按钮上
逐秒显示倒计时（「锁车 3s」，`unlockCooldownSecondsLeft` 向上取整；2026-10-10），开锁位
不冷却（车侧「已解锁」上报异步，紧接着发物理写指令容易两头落空）；② **最小快照先行**：
`UnlockOutcome.Unlocked` 带最小订单（车号 + 未锁，createOrder 响应里已有的时长 / 费用 /
电量一并带上；仓库层三处返回都带），`unlock` 直接摆上卡片（`adoptConfirmedUnlock`：
没报「已锁」就按「已解锁」落位），缺的字段由后台补；
③ **后台补全**（`syncAfterUnlock`）：仓库层写成功即返回；补全最多 `UNLOCK_SYNC_ATTEMPTS` 拍、
快照同时带上 `totalDate` 与 `currentPercent` 才收工（常见路径 0 次查询）、单拍为空不写状态、
报回「已锁」照实采纳并停（**只适用于真发过解锁指令的路径**：完整开锁 / 头盔流程——两段式
`locked=true` 是预期常态，不当停拍信号，2026-10-21）；它跑在 `scope.launch`，**别改回阻塞 unlock**；④ **缺字段沿用**
（`carryKnownRideFields`）：同车新快照缺 `totalDate` / `payMoney` / `currentPercent` 时
沿用上一份，锁状态只在没有悬置待核实时沿用（悬置期间未知比旧值诚实，裁决按快照原文判）；
⑤ **查询时序闸**：写动作 / 锁状态现查开始时 `snapshotSeq +1`；每次读（`query` /
`refreshInternal` / `queryLockState` / `syncAfterUnlock`）开始时 `readSeq +1`，落地序号
不一致即丢弃、busy 期间直接跳过——先发后到的旧包不许覆盖较新的时长 / 电量。另：还车
时长按**订单结束时刻**（`endedAt`）算，不按后台结算回包时刻（否则会把时长与本机记录多算一截）。
**开锁成功先落界面再做善后**（2026-10-10 追加，修用户报的「响应完后停滞一会才弹骑行卡」）：
`unlock` 的 Unlocked 分支先 `_state.update`（最小快照 + 冷却 + 离开结算态）并立即发成功触感，
然后才做 `ensureReminderEnabled` / `startRideTimer` 与 Snackbar，最后 `syncAfterUnlock`——
善后是一串 DataStore 读写与系统调用（排闹钟 / 起通知服务），旧顺序会把骑行卡挡在后面；
但善后**不许**挪到 `syncAfterUnlock` 之后（补全在后台，通知栏倒计时不能等它）。
**冷却只挂真发过指令的动作**（2026-10-21）：两段式开启车没动，`unlockCooldownUntil` 不落
（锁车重发无妨、还车 `wasLocked=true` 跳过静默锁）；完整开锁 / 头盔流程照旧挂 3 秒。
车辆卡的「开锁」按钮同时给等待态（「开锁中…（已等 Ns）」+ 禁用，见 §3.9）。
**弹窗开关带过渡，不瞬间抽动**（2026-10-03，用户口径「不要有突变跳动」）：material3 1.3.0 的
`AlertDialog` 没有 compose 侧动画、compose 的 Dialog 窗口也没配 `windowAnimationStyle`，
开与关都是硬切；现在由 `ui/common/DialogWindowFade.kt` 的 `DialogWindowFadeEffect` 把
`DialogWindowFade` 窗口动画样式（`res/values/dialog_motion.xml`：淡入 + 0.86→1 放大 240ms /
淡出 190ms，与动作区 `BAR_RISE_MS`/`BAR_FALL_MS` 同档）挂到 dialog window 上，由
WindowManager 播——退场时窗口表面停在最后一帧直到播完、压暗同步淡出，确认后动作区的
busy 态在这段淡出里自然露出来；勾「以后开锁不再确认」的说明行显隐也从默认 spring 参数换成
与动作区同族的显式 tween（`FastOutSlowInEasing`，200/150ms）。同页「结束计时」确认弹窗
一并接上；模拟器录屏逐帧验证进出场都是渐进过渡（5 倍动画倍率下抓到多个中间帧）；
④ **降级矩阵**——调度费（`dispatchMoney>0`）与
出围栏（`50011`）**不代用户接受**、免押授权（11035）与支付是微信内流程，全部提示到官方渠道；
官方错误码全表映射可操作文案（`KqcxAuth.errorMessage`，单测锁定；表外回退服务端原文）。

**11035 的硬约束（2026-09-28 真机 + 解包双重确认，别再当缺项排查）**：服务端在需要微信
支付分授权时返回 `11035` + `result.package`，小程序跳订单预览页调
`wx.openBusinessView({businessType:"wxpayScoreUse", extraData:{package}})`——**微信客户端专属
API，第三方 App 无法代调**（支付分 App SDK 需商户侧把应用登记进他们的微信支付商户配置，
水贝贝不是快趣的 App）。**走支付分免押的账号每笔订单都会命中**；出路只有两条：在微信内用车
（App 内给「去微信扫一扫」直达），或用户自行在快趣小程序缴诚信金（可退，¥99 默认）切换
保证金模式。App 的处理：`kvcxScoreAuthRequired` 会话标记（命中后「本机用车」区把直接开锁换成
说明 + 出路，不反复白点；进程重启允许再试——万一官方以后支持免密），**不做**任何绕过尝试。
**账号模式差异的实证（2026-09-28 追加）**：官方在授权失败弹窗里自己写明「本次用车需要完成
微信支付分授权。**你也可以缴纳诚信金后继续用车**」（确认按钮直达钱包缴诚信金）——即
「支付分免押」与「诚信金保证金」两种账号模式并存；**同学能扫车直接开锁，几乎必是保证金模式
（缴过 ¥99）**。这不是客户端差异，App 侧没有任何可绕的空间（授权凭证由微信签发，服务端判定），
伪造授权属绕过付费红线，明确不做。
**第三条出路（2026-09-28 用户实测）**：联系快趣客服反馈后，客服可为账号开通**免授权**
（服务端侧放行，不再返回 11035）——开通后「直接开锁」端到端可用（用户当天验证通过）。
排查 11035 的推荐顺序：先问客服能否开免授权 → 或缴诚信金（可退）→ 或继续微信扫一扫。

**只读能力补全（2026-09-30，从小程序 V6.0.0 全量接口清单里挑的四件，全部无计费后果）**：

| 能力 | 接口 | 实现口径 |
|------|------|---------|
| 响铃寻车 | `POST /v1.0.0/greenCarFind {}` | 让**在案订单**的车鸣笛（官方骑行页「响铃寻车」同款，官方发完 sleep 2 秒）。本地先挡「没有骑行」；走 `KvcxAction.RING` 的 single-flight，**不弹确认**（无计费后果） |
| 锁状态查询 | 复用 `queryUnderwayOrder` | 权威来源是在案订单的 `lockStatus`——解包确认小程序 V6 里 `carLockFlag` **只有定义没有调用**（死导出，参数与响应无参考），**不走它**。交互 = 骑行卡上的状态胶囊**可点**（2026-10-08 由锁徽标升级），点了现查一次并把答案说出来（「已锁；订单还在，计费继续」/「未锁，正在计费」） |
| 单车详情 | `POST /v2.0.0/queryOneCar {carNum, lat, lng}` | 官方扫码后 `loadOneCar` 同款，`result` 直接是车辆对象。用途：附近列表里没有目标车（被筛掉/还没查到那一片）时，「按车号定位」**向快趣点名查一次**，拿到真实坐标就高亮 + 移镜头（`BikeNearby.parseSingle`；在线/启用字段缺失按在线可用算——用户是拿完整车号点名查这辆车，缺数据不该标成失联）。token 允许空（与 queryZoneList 同口径的免凭证读接口），小程序方式也能用 |
| 账户资产 | `POST /v2.0.0/getUserInfo {}` + `POST /v2.0.0/queryUserCoupon {}` + `POST /v1.0.0/queryUserMemberCoupon {page:1,rows:20}` | 快趣账号页「快趣资产」区，**只读**：余额（`rechargeBalance`/`giftBalance`，**分**）+ 持有卡券（`remainFrequency` 剩余次数、`>1e5` = 官方「无限次数卡」口径；`freeTime` **秒**→分钟；`endTime`；`deviceType` 1=电单车/2=单车）+ 会员卡（`discount×10` = 「X 折」）。三个接口**并行拉**，单个失败不影响其它块；进页/登录成功/手动刷新各一次，失败保留已有内容只给「刷新」出路。**充值、退押、买卡仍不做**（微信收银台，App 不碰支付） |
| 结清复查 | 复用 `queryUnPayOrder` | **本机「未结清」只是还车那一刻的扣款确认没赶上**，用户随后在微信里付清 App 并不知道——记录会永远挂着（2026-09-30 用户实测 0.8 元）。快趣账号页进页 / 登录成功各核一次：本机有未结清记录才查一次 `queryUnPayOrder`，快趣已无欠费 → 本机全部翻「已结清」并提示（`RideRecordStore.markUnsettledSettled`；判据成立是因为快趣同时至多一笔待支付订单）。查询失败静默。**为什么 App 内付不了**：快趣支付协议只有小程序 JSAPI 通道（`appletOrderPay {jsCode}` → `wx.requestPayment`），jsCode 只能 `wx.login` 产出、JSAPI 参数绑定小程序 appid+openid 且只能在微信内消费——与一卡通充值能拉起微信收银台的区别在于那边是**H5 支付渠道（checkmweb → `weixin://wap/pay`，不绑调用方）+ 平台把服务端下单暴露成了公开签名可复现的 HTTP 接口**（DESIGN §4.19）。结清引导两条路（解包确认）：小程序主页「待支付」横幅（60 秒节流、关过冷却 5 分钟）、历史订单里的待支付记录 |

**计时联动**：开锁成功 → `EbikeFreeRideReminder.startRide(now)`（免费时长起点钉在真正开锁
时刻），并**顺手打开免费时长提醒开关**
（`KvcxRideController.ensureFreeReminderEnabled`：开锁是"确定在骑行"的信号，关着就打开并在
提示里说明；读偏好失败按"开着"处理，宁可不写不误开）；还车成功 → 手动「结束骑行」同口径
（`endRide` + `burnPending(force=true)` 焚码）。

**自动还车（2026-10-10 用户要求）**：账号登录方式下，用车满 15 分钟**前**自动发起还车，
让订单在免费时段内结束——**触发点 = 开锁 + 15 分钟 − 提前量**（`EbikeAutoReturn.triggerAtMillis`），
提前量 10~60 秒 / 步长 5 / 默认 30，**恒早于免费结束**（晚于免费时段的还车会开始计费，用户口径）。
默认关；失败重试 1~3 次（默认 2，**上一次失败后 3 秒内重试**）、**每次尝试结果都发通知**
（成功一条；失败写原因原文 + 第 i/N 次 + 是否重试），调度费与支付分授权（11035）不重试。
还车坐标**先用骑行页留下的最近用户定位**（`EbikeUserLocation`，5 分钟、进程内，不主动
定位），缺失/过期才用快照里的 `Ride.lat`/`lng`；车辆已锁时不用用户定位；遇到 50011
（不在还车区域）换另一个来源
再试一次（`preferUser` 翻转）。结算口径与手动还车共用 `performReturnBike`
（本地走时在订单结束那一刻截停、扣款确认轮询、结果卡字段——**只允许一份**）。调度 =
独立精确闹钟（requestCode 4004）+ 一次性 `EbikeAutoReturnWorker`（唯一名 `ebike_auto_return`，
KEEP），同一轮只跑一次（`doneKey` 带计时起点；进页 / 账号页 /
开锁 `reconcile` 兜底，
**设置变更不追补**已过触发点的这一轮），执行窗 = 触发点 ~ 免费结束后 5 分钟
（`EbikeAutoReturn.isDue`），与免费时长提醒**两套独立开关**。**与「写操作零自动重试」的
关系**：仓库层的零重试红线不变——重试是这条**用户显式开启**的自动功能自己的行为、次数由
设置封顶；别据此给手动还车加重试。纯逻辑 `domain/EbikeAutoReturn.kt`、循环
`ui/ebike/EbikeAutoReturnRunner.kt`（`EbikeAutoReturnTest` 钉住时序）、调度
`ui/ebike/EbikeAutoReturnReminder.kt`、通知 `ui/ebike/EbikeAutoReturnNotifier.kt`
（channel `ebike_auto_return`、id 1014、落点 2007）。
**结束订单要通知页面**：自动还车 Worker / 手动还车都发 `EbikeRideSignal.notifyEnded()`；
RideScreen（`BikeMapViewModel`）与快趣账号页（`KvcxViewModel`）收集后清 `ride`，否则自动
还车后卡片还在、只是没有时长条（2026-10-19 用户报）。

**使用方式（2026-09-29 用户口径）**：账号与用车这一整套（登录、开锁 / 锁车 / 还车、本机骑行记录、
地图上的「开锁」与「当前用车」卡）现在由一档显式设置把关——
`domain/EbikeUseMode.kt`（`mini_program` / `account`，**默认 `mini_program`**）+ DataStore 键
`ebike_use_mode`，能力表与交互规格见 §3.9。`KvcxRideController` 与它的四道闸**本身不变**
（它只在账号方式下被驱动）；变的是「谁可以驱动它」：出码页 / 地图页 / 账号页的查询与渲染
都先判模式（`val ride = if (accountMode) kvcx.ride else null` 一处收口）。切换入口 = 出码页的「使用方式」卡与账号页顶部
（共用 `UseModeSection`，含账号方式的风险提示）。**为什么默认小程序方式**：账号方式要凭证、
有计费后果、还有支付分授权门槛，只能显式选择；认不出的存储值一律退回这一档。

**UI 与入口归纳（2026-09-28 用户口径：账号与用车放在一起；同日重排）**：账号入口在「我的 →
校园服务 → 第三方账号 → 快趣出行」（`SettingItem`，value = 登录态）。二级页
`SubpageScreen.KVCX` → `ui/ebike/KvcxScreen.kt`（账号 / 骑行状态 / 退出 / 常驻免责，只读），
带「去用车（快趣出行码）」反向入口。**用车全部在出码页**（`EbikeQrScreen`），入口收敛成两处：
① 出码卡里「直接开锁」与「生成二维码」并排（同一个车号输入的两种出路，登录且无骑行时才出现；
支付分账号改给说明 + 「去微信扫一扫」）；② 「快趣账号」行（未登录 = 「去登录」+ 登录后能干什么的
说明；已登录 = 管理，直达账号页）。骑行中动作在**置顶的「当前骑行」卡**（§3.9）：临时锁车 /
还车 / 重试开锁 / 刷新；**订单在案时不给本地「结束骑行」**（还车即结束计时）。账号页与用车页
**互相可达**，登录 / 退出 / 用车不用在两个深处来回翻。写操作前置定位权限（页内 launcher，
未授权先申请），确认弹窗在页面统一处理。**「最近骑行」也在快趣页**（2026-09-28）：本机骑行
记录（`ride_records`，DB v16）——只记本机用车这条链路（起点/终点/车号/费用都齐），
「点扫一扫 → 在微信里骑」只有猜测值、进统计是污染所以不记；列表取最近 `RideRecord.SHOW_LIMIT`
条，可一键清空（纯本机数据，确认后清）。开锁成功走一次性事件补触感
（`EbikeEvent.Unlocked` / `BikeMapEvent.Unlocked`——**别用状态计数器**：那样离开页面再回来会重放一次震动）。
**地图页是同级的第二个用车入口**（2026-09-28）：
车行「开锁」+ 地图上的「当前用车」卡都能开锁 / 锁车 / 还车——编排抽成
`ui/ebike/KvcxRideController.kt`（**出码页与地图页共用同一份**，安全闸与确认文案都不留第二份
副本；`kvcxConfirmDialog` 是唯一文案出处），两个页面各自只负责确认弹窗、定位权限申请与展示。
**别在一处再实现一遍**：写操作的四道闸是责任边界，副本早晚漂移。

**编排的依赖收窄（2026-09-28）**：`KvcxRideController` 的业务依赖是接口 `KvcxRideSession`
（`KqcxSessionRepository` 是唯一实现），外部副作用（起计时 / 打开提醒开关 / 还车善后 / 记账）
是接口 `KvcxSideEffects`（生产实现 `AppKvcxSideEffects`）。**收窄是为了能在 JVM 单测里假造**：
四道闸的落点全在编排里，`KvcxRideControllerTest` 用假会话 + 假副作用钉住
（开锁成功要同步计时与提醒开关、未确认给重试出口、11035 记标记、超时只发一次、定位失败不发请求、
busy 不重入、还车出结果卡并记账……）。改这块先跑那组测试。

**边界**：不轮询（状态只由进页/回页/动作后/手动刷新驱动；骑行卡秒表是本地走时）；token 不落盘；
凭证不进备份；fixture 不含真实账号；不做支付、免押授权、调度费接受。卡券与余额是 2026-09-30
追加的**只读展示**（见上表），支付类动作仍全部不做。已验证：
`KqcxAuthTest`（24 例）+ `BikeNearbyTest`（39 例）+ `KvcxRideControllerTest`（24 例）全绿 +
`assembleDebug`；响铃寻车 / 锁状态查询 / 单车详情 / 资产区已装真机（2026-09-30），点验由用户执行。

**账号页排版统一（2026-10-07）**：① 「账号」区原是身份行下面全宽堆两颗 OutlinedButton——
「打开快趣出行」是主去向、「退出登录」是低频破坏性动作，两者并列等重不合理；现改为
主去向独占一行 OutlinedButton、退出收进右侧 error 色文字按钮，身份行补图标底座
（`SettingItem(icon)`）。② 「快趣资产」的余额原把金额塞进 `SettingItem` 标题位、与副行
「充值余额与赠送余额」大小倒挂；现改为 titleMedium 加重大字独立一行。③ 页尾免责走共享
`FooterDisclaimer`；返回箭头统一 `AutoMirrored.ArrowBack`。登录 / 记录 / 资产三区结构不变。

**开锁前的「没有可用骑行卡」提醒（2026-10-22 用户要求）**：在既有开锁二次确认之后、真正建单
之前多一道**独立**的卡券闸（只读，不写订单）。判据在 `domain/EbikeRideCard.kt`：到期时间已过
或剩余次数 = 0 才不可用；**缺数据（`endAt` / 次数缺失）不下断言**，多张卡任意一张可用即不提醒。
卡券快照由 `KvcxRideController` 持有：账号方式 + 已登录 + 无在案订单时进页预热一次
（`warmRideCard`），**有可用卡缓存 10 分钟、没有可用卡只缓存 60 秒**（用户可能刚去小程序买卡，
没有卡的结论缓存太久会误报）；开锁时最多等 `RIDE_CARD_WAIT_MS`（2.5 秒），查询失败 / 超时
**放行不提醒**。弹窗文案唯一出处 `EbikeRideCard.warningText`（计费后果 + 续卡出路），
勾「今日不再提醒」并点「继续开车」才把当天日期落 `ebike_no_card_notice_date`（取消不记、
跨天自然失效）；确认后直接发开锁，不再重复一次确认。退出登录清缓存并作废在飞查询。
只走单接口 `queryUserCoupon`（`KvcxRideSession.queryCoupons`），不拉资产三接口。

**快趣资产的主次（2026-10-22 用户口径）**：骑行卡的**剩余次数与有效期**是主角——次数
`titleLarge` + Bold、有效期 `bodyLarge` + SemiBold（已过期用 error、3 天内到期用 warning）；
充值 / 赠送余额降为一行 `bodySmall` 的次要信息，并挪到卡券 / 会员之后；免费时长仍在
`labelSmall`。卡片列表为空时给一句「当前没有骑行卡；开锁骑行会直接按快趣规则计费」。

---
