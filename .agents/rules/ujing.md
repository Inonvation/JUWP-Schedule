# U净（美的校园洗衣） · 规则

入口（三处）：① 今日页「江水生活」的「U净」图标（2026-10-19 自生活页迁入；弹窗含扫码与进页）、
② 今日页顶栏「+ → 扫一扫」（扫到机身码）、③ 我的 → 校园服务 → 「U净」。
页面路由 `SubpageScreen.UJING`。DESIGN §3.23（UI）/ §4.37（技术）。
**P1+P2 已交付（2026-10-01），同日体验补**：登录 / 空闲看板 / 扫码识别 / 套餐 / **下单 →
支付宝 SDK 支付 → 订单卡（倒计时 · 取消 · 云端启动）**；下单可选参数（水温 / 强制投放档 /
烘干 `dryTime`）、洗衣完成提醒（`ui/reminder/UjingDoneReminder`）、页面排版收口
（订单 > 扫码 > 看板 > 账号）。P3 蓝牙机型 BLE 透传启动待做（按学校机型 `moduleType`
实测决定；云端机型的启动已随 P2 交付），端点与 BLE 协议细节在 `docs/ujing-plan.md`（本地）。
**实测定论（2026-10-01）**：匿名 `scanWasherCode` 三种头组合均回 `401 "JWT token is missing"`
——只读也必须登录，**无免登路径**。

## 页面结构与交接（2026-10-16 重排，动 U净 页面前重读）

- 主页（UJING）排版：**在案订单（唯一一张状态英雄卡）> 扫码 > 看板 > 账号与记录**，
  层级靠视觉而不是顺序表达（卡规格见 `.agents/rules/ui-common.md`，动效口径同文件）。
  历史账单与账号合并成一张卡（`RecordsSection`），页尾带 `FooterDisclaimer`。
  扫码区只管「扫没扫成」（按钮内识别中 / 失败行 / 「继续上次识别」/ 空态引导），
  **不再内联展示设备卡与档位**——那是下单页的事。
- 看板行的空闲数放大走主色，取数口是 `UjingState.BoardLine.freeCountText`
  （`primaryText` 由它拼出，**别在 UI 里再拼一份「N/M」**）；首拉骨架的判据是
  `boardRefreshing && board.isEmpty() && houses.isNotEmpty()`，不新增 ViewModel 状态。
- 扫码成功 → `UjingDraftStore.open`（Graph 单例，**进程内**）→ `openOrder` 事件 →
  `SubpageActivity.start(UJING_ORDER)`。下单页读草稿渲染：档位单选（AppCard 高亮即
  选中）+ 水温 / 投放 chips + 贴底动作条 + 确认弹层；「去支付」= `submit` 交回选择 +
  关窗（二级页标准右推入 / 滑出）。
- **写操作的执行点永远在主页 ViewModel**（下单 → 支付宝拉起 → 支付确认）：二级页是
  独立 Activity 窗口、下层窗口与它的 ViewModel 存活，这条才成立。别的入口要发起下单，
  走同一条「草稿 `submit` → 主页 `confirmPreparedOrder`」通道，**别在第二个 ViewModel
  里复制一份下单链**。
- 草稿**故意不落盘**：几秒内的一次性交接，进程回收即丢、重扫一次即可；跨进程必须
  存活的是订单号（`UjingOrderStore`），别把两者搞混。下单受理成功后 `close()`；
  用户中途返回不清草稿（主页「继续上次识别」接回），重扫覆盖。
- **第二个入口：今日页顶栏「+ → 扫一扫」**（2026-10-02，DESIGN §3.3 / §3.23）。扫到的
  原文**不在今日页发起识别**——那会复制一份「识别 → 开草稿 → 推下单页」的链，而写操作的
  执行点只有主页 ViewModel 一处。做法：原文走进程内一次性令牌 `UjingScanHandoff`
  （`SubpageStack.kt`），打开 `UJING` 窗口，`UjingScreen(scanRaw = …)` 进页调
  `onQrScanned(raw)`，后面与页内扫码完全同一条路。
  - 令牌而不是 `SubpageRequest` 字段：窗口链恢复重放请求时**不得**再扫一次。
  - `onQrScanned` 开头有**未登录拦截**（识别接口要 token，匿名回 401）：命中时只提示
    「识别洗衣机需要先登录 U净」，不发那条注定失败的请求，并**把原文留 10 分钟**
    （`UjingViewModel.pendingScan`）：`repo.loggedIn` 收集器在 false→true 时自动接着识别，
    用户不用回今日页重扫；超时作废，免得用户在别处登录也被推进一次识别。页内扫码入口
    本来只在登录后可见，这条拦截只作用于今日页进来的那一档。
- **扫码那一跳也留协议样本**（`UjingRepository.dumpScanWasher`，debug 专用）：`scanWasherCode`
  的**原文与响应**落 `filesDir/ujing_debug/scan_washer_<原文散列前 6 字节>.json`。
  原文只写在文件内容里、不进文件名；token 在请求头不落盘；落盘失败静默。这是「机身码到底长
  什么样」的唯一来源——样本到手后 `domain/QuickScan.kt` 的判据可以从「其余全给 U净」收敛成
  白名单（现在那条偏宽的取舍写在它的 KDoc 里）。
- 历史账单：**本 App 发起的订单留痕**（`UjingHistoryStore`，prefs JSON ≤50 笔，新的
  在前；upsert 同 orderId 去重，刷新更新原条目，取消标注 60 也算一笔）。服务端账单
  接口没有协议样本（端点表只有 `orders/running`），**别猜端点**，拿到官方抓包样本
  再评估换数据源。

## 协议与指纹

- 网关 `https://phoenix.ujing.online/api/v1`，壳 `{code, message, data}`，`code == 0` 成功；
  `401 / -99` = 会话过期（`UjingSessionExpiredException`）。
- **三组「客户端指纹」**（2026-10-21 修正）：取码/登录 `ZI`（2.4.3）；门店查询
  `stores/near` / `devices/reserve` 用 `ZA`（2.4.18，**不带 weex / UA**）；业务
  （扫码 / 套餐 / 订单）`BI`（2.4.2）+ `weex-version: 1.1.30`。
  **看板取不到数先查这一组**：此前门店查询跟 BI 一起发，`devices/reserve` 回 401，
  仓库按会话过期清了 token，重登后再 401——现象就是「看板一直空、刷新无效」。
  **没有客户端签名**（无需逆向任何加密函数）。服务端对 code 宽容（ZA/BA 亦可用），
  但门店查询组用 ZA 是社区实测定论（liteU / DUT-Ujing，2026-09-30）。
- 服务端收紧校验时**先改 `UjingApiConfig`**（版本号 / UA / 头组合），改前重新抓包对齐。
- 注意平台把经度拼成 **`lont`**；`devices/scanWasherCode` 必须传**二维码原始内容**（勿预提取 uuid）。

## 红线（违反即打回）

1. **不绕过支付**：费用全走官方渠道（支付宝 SDK，orderInfo 服务端签发）；不代付、不伪造支付
   凭证、不做"免费洗衣"；不做批量 / 自动 / 抢单脚本（社区有，别学）；只操作使用者本人有
   使用权的设备。
2. **写操作零自动重试 + 二次确认**：下单 / 取消 / 启动都是写操作；超时后先查在案订单
   （`refreshOrder`），确认没有已创建的订单再让用户重试，绝不盲目重发；**已有在案订单
   不允许叠单**（主页 `confirmPreparedOrder` 先拦，下单页确认弹层是二次确认）。
3. **登录节制**：只在用户主动点「获取验证码」时发码（60 秒冷却，UI 与 ViewModel 双保险）；
   短时间高频登录会被服务端限制数十分钟（实测）。会话过期**绝不自动重登**——清 token 留手机号，
   由用户重新登录（`UjingRepository.markExpired`）。`markExpired()` 同时留一条「已过期」
   标记，下次进 U净页由 `SessionExpiredNotice` 提示（口径见 `login-session.md`；
   主动 `logout()` 清标记，不提示）。
4. **不后台轮询**：看板只在进页 / 手动刷新时拉取；订单轮询限"页面可见 + 15 秒"（`LaunchedEffect`
   随组合取消）；完成提醒用 `setAlarmClock`（洗涤中按剩余、已支付未运行按模式总时长兜底，
   兜底早响时 check 按新剩余重排），到点也只查**一次**订单详情，不是轮询。
5. **凭证**：手机号 + token 只进 `secure_ujing`（EncryptedSharedPreferences，已排除两套备份规则）；
   token 绝不进 Logcat / 剪贴板；**网络层无日志拦截器**。
6. **启动前先查订单状态**：服务端对已运行订单返回 `{}` 静默拒绝（HTTP 200、无 `code` 字段）；
   `status ∈ {40,50}` 直接禁启动按钮；云端启动受理判定 `code==0` 或 `1703 + errorCode==0`
   （`UjingState.commandAccepted`）。订单号必须落盘（`UjingOrderStore`）——重启丢 orderId
   服务端就无从签发指令，社区实测教训。
7. **只借鉴协议事实，不复制代码**：参考项目里 NcepuJw 是 GPL-3.0、FlandreSY 是 AGPL-3.0——
   端点 / 参数 / 状态码是事实，实现必须自己写。
8. 扫描窗口的**原文模式**（`EbikeScanActivity.MODE_RAW`）是给本功能复用骑行扫码窗口的唯一入口，
   改动时保持骑行模式（不传 extra）行为不变。

## 下单可选参数（2026-10-01 体验补，改前重读）

- **口径：「服务端声明需要才发」**。实测五基础字段（type / deviceTypeId / deviceId /
  deviceWashModelId / storeId）即可下单成功；可选项是给声明了开关的机型兜底。
- `washTemperatureId`：仅洗衣机业务（type != 2）且 `isWashTemperatureEnable` 时发；
  枚举**固定** 1=常温 / 2=30℃ / 3=40℃ / 4=60℃（`UjingState.temperatureOptions` 单一来源），
  UI 默认常温。
- `wp_detergentGearId` / `wp_disinfectantGearId`：**投放加购**（2026-10-02 起开放给用户，
  此前只有强制投放机型自动补）。非烘干机的扫码结果卡出「洗衣液 / 消毒液」chips，默认按
  `isForceDetergent` / `isForceDisinfectant` 勾好（`UjingState.addonDefaults` 单一来源），
  用户可改；勾了才发**标准档**（`UjingState.DETERGENT_STANDARD_GEAR` = 1 /
  `DISINFECTANT_STANDARD_GEAR` = 4）。**档位本身不可选**——其余档位的价格语义没有实样，
  造出来就是瞎报价；未声明投放的机型发档位可能被拒（下单报错，关掉再试）或被忽略，
  金额一律以支付宝实付为准（确认弹层已注明）。
- `dryTime`：仅烘干机（type == 2），协议口径 = 模式时长 / 10（`UjingState.dryTimeFor`）。
- `orders/{id}/detail` 固定带 `additional=price`（照抄官方抓包形态，取 `payPrice`）。
- 订单快照（`UjingOrderSnapshot`）的 `createdAt`（支付窗口基准）与 `durationSeconds`
  （进度条分母）**只在下单时写、刷新原样保留**；旧版本快照两列为 0，UI 各自退回静态提示。
- **协议样本采集（debug 专用）**：`UjingRepository` 的 `debugDumpDir`（Graph 只在
  `BuildConfig.DEBUG` 配 `filesDir/ujing_debug`）把 `program/info` 原始响应整包落盘，
  一台设备一份。投放声明的完整字段形态（是否还有「可选投放」之类开关）就是靠它补样本；
  `adb shell run-as edu.jxslu.schedule.debug cat files/ujing_debug/program_info_<id>.json`。
  响应体不含凭证，落盘失败静默；release 恒不落盘。

## 订单守卫与看板（2026-10-16 优化，改前重读）

- **终结态订单不挡新单**：`UjingViewModel.confirmPreparedOrder` 只在快照**未终结**时才可能拦。
  完成 / 取消 / 超时 / 失败的快照要留到用户点「知道了」（卡片要展示结果），但业务上它已不在案
  ——拿它拦新单就是「洗完再扫烘干机、先报上一单没完成」的 bug。快照还可能停在旧值「洗涤中」
  （轮询只在 U净 页可见时跑），所以有非终结快照时**先 `repo.refreshOrder` 对一次**：服务端说
  结束了就放行，仍进行中才拦；**刷新失败保守拦住**（写操作零自动重试），会话过期的提示由
  `handleFailure` 发、别再叠一条。
- **看板每行两条**：`UjingBoardRow` 的 `line`（洗衣机）与 `dryerLine`（烘干机），同由
  `UjingState.boardLine` / `dryerLine` 聚合（共用 `lineFor`）；UI 出「烘干机 空闲 N/M」。
- **今日页 U净 弹窗显示洗涤状态**（2026-10-19 自生活页迁入，`ui/ujing/UjingEntrySheet`）：
  弹窗只读 `Graph.ujing().orderState`；**订单对表在打开弹窗那一下做**（`TodayScreen` 的点击处，
  10 秒节流，见 `UJING_ORDER_REFRESH_TTL_MILLIS`——快照只在 U净 页轮询，不这样拿到的是下单时的
  旧状态）。非终结订单顶部出 `ui/ujing/UjingScreen.UjingOrderStatusLine`（设备名 + 状态 +
  洗涤中剩余时间，本地每秒换算）。
- **状态文案服务端优先**：`refreshOrder` 把 `detail.statusRemark` 存进快照
  （`UjingOrderSnapshot.statusRemark`，旧快照读成空串），展示统一走
  `UjingState.statusLabel(status, remark)`——服务端文案非空优先、本地表兜底。**别再直接
  `statusText(status)`**（订单卡与今日页弹窗两处都改了）。
- **进页对表走静默**：`loggedIn` 收集器里那次 `refreshOrder` 用 `silent = true`（进页补数据不该
  把订单卡按钮闪成加载圈）。
- **协议样本采集**：除 `program/info` 外，`devices/reserve` 也整包落盘
  （`filesDir/ujing_debug/devices_reserve_<storeId>.json`，debug 专用、一店一份）——「烘干机在线
  状态」到底能显示什么，只能靠它答（看板只映射了 `deviceTypeName/free/total/waitTime`）。

## 烘干机（2026-10-16，改前重读）

- **判定 `UjingState.isDryer(type, deviceTypeId, deviceTypeName)`**：`program.type == 2` 为准
  （协议事实），`deviceTypeId == 3` 与机型名含「烘干 / 干衣」兜底（与看板同一判据）。
  **请求体仍按服务端 `type` 字段发**（无样本不臆测协议），这里只管 UI 展示判定。
- 下单页：烘干机档位区标题「烘干时长」、`ModelCard` 标题以时长为主（`烘干 N 分钟`，副行只留价格）；
  **水温 / 投放选项整块不出**（都是洗衣概念），`addonDefaults(isDryer, …)` 恒不勾。
- 烘干时长沿用服务端下发的 `deviceWashModel`（每档即一个时长，`dryTime = 时长 / 10`），不另造档位。

## 命名与文案

- 页面显示名「U净」（用户 2026-10-01 拍板，不用「洗衣房」）；模块与包名用 `ujing`。
- 图标 `HugeIcons.WashingMachine`（本地 JAR 查核，勿猜）。
- 看板文案口径在 `UjingState.BoardLine`（`空闲 N/M` / `暂无空闲` + `约等 X 分钟`），UI 不另拼。

## 下一步（P3 动工前重读）

`docs/ujing-plan.md` §4.7（BLE 通道三坑：随机地址必须扫描连接、stopScan 后 delay(400ms)、
静默拒绝）与 §2.5 坑表。先实测学校机型 `moduleType`（扫码结果卡的「通信模块」行）：
1/5 = 纯蓝牙 → 必做 BLE；0/2/3/4/7 → 云端启动已随 P2 交付，无需 BLE。
遗留实测：JWT 有效期（登录后 base64 解 `exp`）。
