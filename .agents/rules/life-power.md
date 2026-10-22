# 充值弹层 · 校园卡 · 电费 · 胖乖生活

作用域：改充值弹层、一卡通付款码与流水、寝室电费读写与充值、余额提醒、胖乖生活（开水 / 余额 / 订单）时读。规格见 DESIGN §3.13 / §4.19 / §4.24。
（空调遥控入口 2026-10-19 已迁到今日页「江水生活」，见 `.agents/rules/ac-remote.md`，规格 §3.31 / §4.48。）

## 胖乖（P4）开水

- 参考 `F:\light-life-v3.0`；Base `https://userapi.qiekj.com/`
- 只做：登录、开水、余额、订单、充值深链；签到默认关；禁止刷积分
- 实现在 `data/qiekj/` + `ui/water/`；Token 走 EncryptedSharedPreferences，禁止进日志
- **「出水中」跨窗口口径**（2026-10-21）：`Graph.qiekjWatering`（`QiekjWateringStore`，落盘
  SharedPreferences + StateFlow）。`WaterViewModel.unlock()` 流程首次进入 `Working` 时 `begin`
  （设备名 / 起水时刻），结算 `Success`、流程失败、165 秒超时收尾时 `clear`；5 分钟未清视为
  过期（进程死在流程中），store 读取时静默清除。今日页服务卡、侧栏高亮都订阅它——开水页与
  今日页各持一份 ViewModel，`UnlockFlowState.Working` 只在各自 VM 内，靠它同步。
- **登录态只有一份**（2026-09-27）：`QiekjRepository.loggedIn`（StateFlow，`saveToken` /
  `logout` 就地翻转）。今日页开水卡与开水页各持一份 `WaterViewModel`，**别在 VM 里另存布尔**：
  init 读它、`syncLoginState` 跟着它翻转（相等即跳过，否则本地登录路径会把余额/设备各刷两轮）。
  `saveToken` 只在**校验通过后**调：Token 登录先 `validateToken(token)` 再落盘，顺序倒了
  今日页卡片会先切已登录再跳回来。账户卡那格与「扩展服务」副标题订阅同一条流，别再改回
  组合期读一次 / ON_RESUME 重读 token。
  会话失效（401/403 /「请先登录」）走 `markExpired()` 而不是 `logout()`：前者留一条
  「已过期」标记，进开水页时由 `SessionExpiredNotice` 提示一次（口径见 `login-session.md`）。
- **退出要二次确认**（2026-09-27）：胖乖生活页顶栏的退出图标先弹 `AlertDialog`。确认后
  token 与开水订单快照一起清，重新登录要再收一次短信——代价不对等，别改回一点就走。
- **充值是深链不是 REST**（2026-09-28）：QiekjApi 没有充值端点，余额行的「充值」走
  `ui/water/QiekjRecharge` 深链拉支付宝的胖乖生活小程序充值页（口径与实现细节同
  DESIGN §6「2026-09-28 胖乖生活充值入口与页内打磨」：分享短链剥埋点、page 值整体
  编码、`alipays` scheme queries 声明与趣智共用）。深链整串被 `QiekjRechargeTest`
  钉死，别改字符串；返回本页的 ON_RESUME 余额重拉挂在 `WaterScreen` 的
  `awaitingRecharge` 上，别挪进 VM。小注里「核对小程序账号与本 App 登录手机号一致」
  是防充错账户的用户要求（2026-09-28），别删。Token 登录**拿不到**手机号：已知接口面
  （QiekjApi 与参考工程逐端点对过）没有任何按 token 查用户的端点，响应里也无手机号
  字段，只能沿用短信登录记住的号码——别为它硬猜第三方接口。
- **刷新是整页下拉，不是按钮**（2026-09-28）：余额行的刷新图标已删，下拉刷新走
  `PullToRefreshBox` + `WaterViewModel.refresh()`（余额、设备两轮 join 完才收指示器；
  进页首载与各登录路径仍直调 refreshBalance / refreshDevices）。
  `WaterUiState.refreshing` 只归下拉刷新，别拿来当一般 loading 用。
- **登录手机号默认遮蔽**（2026-09-28）：余额行下显示 `accountPhone` = 仓库记住的
  登录手机号（短信登录落盘、Token 登录沿用旧值、没有就不显示），遮蔽复用 domain 的
  `QzxyPhoneMask`（名字带 Qzxy 但是通用工具），点眼睛才展开。它与登录输入框的
  `phone` 字段是两回事——那个值用户随时在改，别把两处合并。
- **复制 Token 的出口在顶栏**（2026-09-28）：已登录时顶栏 `Copy01` 图标 →
  `WaterViewModel.exportToken()`（= `QiekjRepository.readToken()`，只读、不走网络）
  进剪贴板，snackbar 按「账号通行证，只粘到本应用的『Token 登录』」口径提示
  （对齐趣智「复制会话串」）。这是 token 唯一的主动出 App 通道，别再加别的口。
- **设备状态样本靠 debug 落盘**（2026-10-05）：`goods/latestUsed`、`goods/normal/details`、
  `goods/normal/skus`、`goods/water/sync`、`goods/water/unlock`、`order/afterPay/creating`、
  `order/detail`、`goods/endWater`、`order/list` 九个端点的
  **响应体原文**（后两个 2026-10-14 补，为确认远程关阀与服务端账单字段的返回形态；
  `trade/check_device_status` 2026-10-16 已随「在线显示」整块删除、不再采样）
  在 debug 包落 `filesDir/qiekj_debug/`（`data/qiekj/QiekjDebugDumpInterceptor.kt`；
  每个端点只留最新 40 份，落盘前把 `token` / `phone` 字段替换成 `<redacted>`；
  `Graph.qiekj` 按 `BuildConfig.DEBUG` 传目录，release 一个字节都不写）。
  为什么不在仓库层落盘：`SyncData` / `DeviceItem` / `GoodsDetailsData` 只声明了少数字段，
  `QiekjJson` 又开了 `ignoreUnknownKeys`，服务端多给的字段在解码那步就没了，
  要确认响应里还有什么只能拿原文。（`SyncData.status` / `amount` 是解出来在用的字段，见下条。）
  取样本：`adb exec-out run-as edu.jxslu.schedule.debug sh -c 'cd files/qiekj_debug && tar -cf - .' > qiekj_dump.tar`。
  这三条纯逻辑（白名单 / 脱敏 / 裁剪）由 `QiekjDebugDumpTest` 钉住。
- **设备在线检测：整块删除**（2026-10-16 用户拍板）：`trade/check_device_status` 的 `health`
  实测**恒为 true**（2026-10-15 至 10-16 共 11 份真机样本，含用户不在寝室时段），官方 App
  也只拿它当**故障闸**（`health=false` 弹 `faultDesc`、`msgId` 非空才多一步 `deviceSync`，
  反编译出处见 `.re-tmp/` 的 d8 dump）——它判不了「通没通电、在不在线」。所以 App 侧
  **不再向用户展示任何在线/故障状态**（开水页设备行副行与今日页面板底部那行均已删），
  端点调用、`QiekjDeviceHealth`、`DeviceHealthData`、`QiekjApi.checkDeviceStatus` 与 debug
  落盘白名单里的它一并删除。**别再把它当在线判据加回来**；将来若要做故障提示，
  `faultDesc` 的官方用法（弹窗文案）在 d8 dump 里有据可查。
  - **旧三组实验的结论仍成立，别再照那三条路走**：
    ① `sync` 的 `status` / `workStatus` 是**服务端会话状态**，设备拔电后照样回
    `status=1 / workStatus=2`，而且会**黏住**（断电那笔会话 15 分钟后仍回 1/2，服务端不关它）
    ——下次开水若提示设备忙或失败，先怀疑这笔挂单；
    ② `latestUsed` 回的 `workStatus` 平时恒为 10（见过一次结束后回 20 的样本，语义未定，
    别拿它判状态）；`details` 的 `deviceErrorCode` / `deviceErrorMsg` 恒为 null，与设备死活无关；
    ③ 门店设备清单 `appointNew/near/newMachines` 在旧协议下回 `{"code":-1,"msg":null,"data":null}`
    （HTTP 200、业务码 -1），社区文档记新版 1.139.0 要求 `deviceId` 头与双签，这套 1.60.3 凑不齐。
  - 能判的**结束态**不变：`status==5` 且 `amount>0` 才算真的出过水。为实验加过的只读探测
    （`QiekjProbe` / `probeDeviceState`）已随收尾删除，别再照那个思路加回来。
- **设备侧「结算」后 sync 回 `data:null`，是「会话已结束」不是错误**（2026-10-09 实测 + 修复）：
  用户在饮水机上点「结算」后平台会关闭会话，轮询可能先收到
  `{"code":0,"msg":"成功","data":null}`（HTTP 200、业务成功、无数据），也可能随后一拍再收到
  `status=5 / amount=N` 的收尾样本——两种形态都实测出现过。处置按 `noDataPollAction`
  （`data/qiekj/QiekjModels.kt`）：见过出水（`workStatus==2`）→ **连续两次空数据**判会话
  已结束，用最后已知单号照常走 `afterPay/creating → order/detail` 读账单；从未见到会话 →
  等约 20 秒再报「设备未启动或未响应」。**别再退回 `requireData` 把空 data 当致命错误**
  ——那正是「未知错误（code: -1）」的根因（异常里没有错误码，诊断回退文案就是 code -1），
  且账单读不到、订单快照丢。轮询改用 `throwIfFailed` + 判空后，业务失败（code 非 0/200）
  的诊断口径不变。
- **设备状态显示已删**（2026-10-16 用户拍板，取代 2026-10-08「常驻说明」与 2026-10-14
  「状态感知一行」）：今日页开水面板（`ui/water/WaterEntrySheet.kt`）底部只在**未选设备**时
  报「未选择设备：请先到开水页选择饮水机」；开水页设备行的副行恢复「已选中 · 点右侧可换」
  （`WaterScreen.kt` 的 `DeviceRow`）。**别再给设备加「在线 / 离线 / 未报故障」这类文案**
  ——平台没有可用判据（见上条），写了就是过度承诺；失败引导由开水失败的诊断建议承担。
- **积分折算用服务端 `integralAmount`，别算比例**（2026-10-08 接入）：`user/balance` 回的
  `integralAmount`（元）就是「积分可抵的价格」（语义由社区饮水脚本 3ryng1um/qiekj 注释
  交叉确认；我们自己的 debug 落盘不含余额端点，没有样本），面板积分行显示
  「· 约可抵扣 ¥X」直接读 `BalanceData.pointsDeductText`（为 0 / 解析失败回 null，
  后缀隐藏）。**不要**写死 400:1 之类的兑换比例——官方可调且可能按场景不同。
  同轮「使用积分抵扣」副行加了用户要求的「确保积分充足」，别删。
  **口径（2026-10-08 用户订正）**：积分不足**不会拒绝开水**（照常可用、只是不抵扣
  积分）；`QiekjErrorDiagnosis` 里那条「积分余额不足」是从参考工程搬来的关键词分类，
  别拿它当「积分不足会失败」的依据。
- **「结束用水」按机型能力分流**（2026-10-05 起；2026-10-16 按真机样本重定）：出水满
  `WaterViewModel.END_SESSION_MIN_SECONDS`（5 秒）在开水页与今日页开水面板同时给出
  「结束用水」（共用 `ui/water/WaterFlowControls.kt`）。点下后 `endSession()` 置
  `Working.stopping`（按钮转「正在结束用水…」）→ `UnlockStopSignal` 置位 → 仓库下一轮按
  `goods/normal/details` 的 **`supportAppUserFinish`** 分流（`shouldRequestRemoteEnd`）：
  - **支持**（官方 App 只对这种机型显示「结束使用」按钮）：best-effort 调
    `goods/endWater { goodsId }`（`QiekjApi.endWater`，2026-10-14 反编译补出）。被拒不再
    抛错，退回设备端提示继续轮询；受理后等 `REMOTE_END_WAIT_MS`（20 秒）回报结束（`status==5`
    + `identify` 非空，`shouldSettleOnManualStop`），等不到才抛 `UnlockStoppedException`
    （「已请求结束用水，设备未回报结束，账单以平台为准」）。
  - **不支持**（`supportAppUserFinish=false`，本校直饮水机 13B309 即此档）：**不发**
    `goods/endWater`——平台必回 `4072「结束失败，请在设备端操作结束」`（2026-10-16 18:20
    真机样本）。仓库改提示「该机型不支持 App 结束：请在设备上点击『结束』，等待设备回报」，
    并**继续轮询**等设备回报结束，照常建单读账单。
  - 两条路的收尾判定同一条：`shouldSettleOnManualStop`（`status==5` + `identify` 非空）
    照常建单读账单。
  - **旧 bug 别再犯**（2026-10-16 修）：被拒 / 不支持时曾经立即抛错收尾（「平台未受理，
    本次未确认用水」）——用户随后在机器上结束的账单 App 就不再读，等于丢单（丢的这笔可在
    「服务端账单」里找回）。现在只有「平台已受理但 20 秒没回报」一条路抛错走
    `finishWithoutSettlement`。
  - 文案（2026-10-16 用户订正）：设备侧结束是**点击**机器「结束」键（官方反编译文案是
    「长按『结束』结束使用设备」，不适用本机，用户实测点击即可）。副行「未停止时，请在
    设备上点击『结束』」/「等待结束回报；未停止时请在设备上点击『结束』」；不支持机型的工作
    状态副行常驻「设备工作中；如需结束，请在设备上点击『结束』」。
  165s 兜底话术不变（「设备未回报结束，实际用量以平台账单为准」——设备断电时 `workStatus`
  一直回 2，仓库走不到建单那步，说结算是假话）。
  `QiekjModelsTest` 的「会话结束判定只认 status5」「手动结束_read到账单才结算」「远程结束_
  不支持机型不发注定被拒的请求」钉住口径。
- **服务端账单历史**（2026-10-14，`order/list`，**待真机验证**）：开水页「历史订单」弹层分两段
  （沿用消费流水页的两段约定）——**本地快照**（最多 20 笔、含支付构成）+ **服务端账单**
  （`QiekjRepository.serverOrders()`，账号全部订单，进弹层按需拉一次；点单笔走
  `serverOrderDetail()` = `order/detail { orderId = ServerOrderItem.id }`）。列表行展示
  `createTime / machineName(shopName) / markPrice`——**字段口径无样本**（`markPrice` 是标价，
  不是「本次花费」；真实构成要点开看），真机核对后再回填。
- 一卡通付款码（DESIGN §3.10/§4.19）：密码字段 = 安全键盘密文（字形 MD5 表一次替换）+ `$1$` + uuid；
  **未知字形/非双射/样板自检不过 = 立即报错不猜**；登录密码仅数字（键盘只映射 0-9）；
  token 只存内存不落盘；付款码不进日志/剪贴板/相册；凭证交互照 `TweakDetectScreen`（开启先真实验证、关闭即清除）
  调用链（11 步顺序不可乱）见 DESIGN §4.10

## 充值（一卡通 · 寝室电费）

> **2026-10-22：旧「生活」页与 `Routes.LIFE` 已删除。** 侧栏钱包「充值」→ 选择弹层
> （一卡通 / 电费）→ 壳层 `RechargeHost`（`ui/life/RechargeHost.kt`）直接弹对应充值弹层，
> 没有充值页面。下面关于钱包 hero 卡 / 校园服务 / 最近流水的段落是**历史规格**，只用于
> 回查，不要据此恢复页面；一卡通付款码 / 余额 / 充值弹层本身的口径仍然有效。

- **入口与宿主**：`SideDrawer` 的充值选择弹层选完 → `pendingRecharge` → `RechargeHost`
  弹 `RechargeSheet`（一卡通）或 `PowerRechargeSheet`（电费）；关掉弹层回当前 Tab。
  没有 `lifeTabEnabled` 开关，也没有底栏「生活」项。
- **样式（历史，2026-10-13 / 2026-10-19）**：钱包 hero 卡 → 校园服务四格 → 问渠整行卡 →
  最近流水。相关组件 `WalletHeroCard` / `CampusServicesCard` / `FeedSectionsCard` 已随
  页面删除；这些入口现在分别在侧栏、教务 Tab、流水二级页。
- **免责声明**：每个充值入口（侧栏选择后的两个弹层、电费弹层「去充值电子账户」、
  我的 → 校园卡页「充值」）打开前都过 `RechargeDisclaimerDialog`；静默窗口在 DataStore
  首帧到达后才判（2026-10-22 修，此前初值 0 每次都弹）。
- **码不预取，且只有一处出码**：付款码页 `PayCodeScreen` 是唯一码位；扣款结果经
  `PayCodeResultBus` 交 `CampusPayResultDialogHost`（MainActivity 根层 + 校园卡设置页）。
- **付款成功后余额自动刷新**：宿主收到消费时 `CampusBalanceSync.refreshAfterPayment`
  进程级单次强拉；各页 `CampusCardViewModel` 订阅 `revision` 用 `force = false` 重建。
- **余额快照统一出口、连点去重与失败态**（2026-10-09）：`CampusCardViewModel` 的四类快照写入（进页与手动刷新 / 开启验证 / 到账判定 / 电子账户到账补刷）统一走 `applySnapshot()`——写 `_balance` 的同时顺手推桌面小组件（零额外请求；此前只有付款码页在推，充值到账 / 手动刷新要等 2 小时闸门才上小组件）。`CampusCardViewModel.refreshBalance` 与 `PayCodeViewModel.loadBalance` 有「取数中忽略重复触发」闸，连点不叠请求别删；付款码页余额行的「获取中 / 暂不可用」两态由 `PayCodeViewModel.balanceLoaded` 区分（失败转「余额暂不可用（点击重试）」，与设置页 2026-10-07 的口径对齐）。
- **进页取数三路并发、登录只登一次（2026-09-30）**：充值前要等两条链（电费读数：登录→
  详情→读表；一卡通余额：键盘→OAuth→queryCard→scene=recharge）。收口三处：`refreshPower` 里电费流水与读数
  **并行**；`fetchBalance` 里正式卡与电子账户两问**并行**；`YktRepository` / `PowerRepository`
  的登录互斥锁内**复用**等锁期间别路登好的 token（并发冷启动两平台各只真登一次；401 重登
  传 `staleToken` 只挡刚试废的那个）。**公开的 `login` / `loginForToken` 仍是强制真登录**：
  设置页「保存并开启」要验用户现输的密码，别给它加复用。改这三处前先读 DESIGN §3.13
  「三路并发、登录只登一次」。
- **取数失败的提示走 `NetworkHint`（2026-09-27）**：电费充值读表失败时，网络类异常
  （`PowerException.Network`）改说下一步做什么——开着 VPN/代理就关掉它，没开就换一条
  网络（关掉校园网 Wi-Fi 用手机流量）。弹层里空间窄用 `NetworkHint.briefOf`；
  账单页的提示块整行会折行，用 `of`（带原因）。
  凭证错、平台结构变化照旧报原文，别拿网络建议把真原因盖掉。
- **等数据的地方不许先渲染空态文案再跳**：骨架高度要跟成品一致，文字行高一律走
  `ui/common/TextMetrics.kt` 的 `lineHeightDp`（从样式换算，跟随系统字体缩放），
  不写死 dp。排队等数据的场景一律照这条来。
- **一处凭证**：电费登录 = 一卡通的学号 + 查询密码（`YktCredentialStore`，2026-09-23 实测
  两个平台同一密码）。一关了之：凭证清掉时电费卡同样显示「未开启凭证」。
- **读表参数缺一不可**：`feeitemid=181` / `type=IEC` / `level=3` / campus+building+room；
  少一个平台只回 `code=500「未知异常」`（HTTP 200），**业务码 401 也藏在 HTTP 200 里**，
  必须读 body 的 `code` 才能触发重登。
- **未设置寝室不是错误，是「待用户设置」的状态**（2026-10-08，DESIGN §4.24）：
  `singleFeeitem.sceneinfo` 为空 = 未确认寝室——**这是官方 H5 自己的判据**（`if (t.sceneinfo)`），
  仓库抛 `PowerException.RoomNotBound`；充值弹层 / 房间选择器转「未设置寝室，点此设置」
  （值「—」、旧读数房号 / 时刻一并隐藏、充值闸门改道选择器），出口直通选择器弹层。
  **别把它并进普通错误文案**；也别拿 `view:"choose"` 当判据（已绑定账号同样是 choose，
  它是页面模板类型），`bindStatus` 只作旁证。
- **寝室选择 / 绑定链路**（弹层宿主：侧栏充值 / 充值页的电费入口、「我的 → 校园卡」的「寝室电费房间」行，
  也是改绑入口）：`getThirdData type=select&level=N` 逐级取 校区/楼栋/房间（候选 >12 带本地
  搜索过滤、单选项自动推进）→ 房间选定后 `type=IEC` **读表预览**（**不落库**——预览的可能
  不是本人房间，`power_readings` 落库仍只有 `snapshot()` 一处）→ 二次确认弹窗 → 写一次。
  **写接口纪律（红线）**：`POST /charge/sceneBind/add`（**JSON body**：`feeitemid` + `sceneinfo`，
  拼串唯一口径 `PowerModels.sceneinfoOf`）只在用户点「确认绑定」后发一次、失败不重试
  （错误留在弹层，手动「重试 / 重新绑定」）；不进任何订单 / 支付链路；确认文案必须写清
  「会同步到缴费平台的『我的房间』」（upsert 语义，官方允许改绑——2026-10-08 同房间重绑
  实测 HTTP 200 / `code=200`）。判据还没有真实未绑定账号的样本：debug 包落盘
  `filesDir/power_debug/`（两套备份排除已含 `power_debug/`），首个真实案例到位时核对。
- **级联选择的深层残留是坑**（2026-10-08 复查轮修）：改选上游层级后更深层的旧选择必须清掉
  （`applySceneChoice`，`PowerSceneSelectionTest` 钉住）——残留会让「房间」层直接显示旧预览
  而不是候选列表。
- **电费充值已 App 内完成**（2026-09-24 实测收口；**2026-09-29 下单表单 8 字段**）：
  下单 = `POST /blade-pay/pay` `paystep=0`（`feeitemid=181 + tranamt + flag=choose +
  synAccessSource=h5 + abstracts + third_party`，签名 `PowerPaySign`，表单唯一口径
  `PowerOrderForm.of`）。**`third_party` 是命根子**（2026-09-29 事故）：= 读表响应
  `map.data` 原文 JSON（`PowerMeter.dataJson`），平台入账任务只认它找电表——缺了订单照样
  建、钱照样收、支付照样「成功」（`status=1`），但**永远不入账**（`order.flag` 第 2 位停在
  0；`1`=入账成功/`2`=失败，`PowerEntryState`），不报错不退钱，只能人工找管理员。三笔
  26 元就是这么卡死的。红线：下单前**强制新鲜读表**（缓存/种子快照没有 dataJson，
  `PowerReadingSource.RECHARGE`），读不到 dataJson 或房号**直接拒单**，绝不发缺字段表单。
  扣款与入账分开报告：`orderState`/`PowerOrderState`（status + entry 两字段），Accepted
  步区分「已入账 / 入账处理中 / 入账失败」，别再拿 `status=1` 当「到账」说满话；
  **结果步要带金额**（2026-10-07）：`AcceptedStep` 顶部是 `AmountHeroText` 金额大字——
  「金额只在密码步 / 农行等待步有、出结果的一步反而没有」是旧口径，别改回去；
  协议级验证脚本 `_archive/_probe_power_order_fix.py`（下单→验落库→删单）可随时重跑；
  支付 = `paystep=2 + paytype=ACCOUNT + paytypeid=59` 拿 `passwordMap` 乱序表，
  `PowerPayChallenge.cipherOf` 是密文换算唯一口径（用户数字 d → **d 在乱序表里的
  下标**；官方键盘第 i 个键显示 `table[i]` 但提交 `String(i)`，2026-09-24 读前端
  `app.7abec7aa…js` 修正——此前 `d → table[d]` 方向反了，正确密码也报「密码错误」），
  6 位消费密码 = **登录缴费平台用的那个 6 位密码**（2026-09-24 用户纠正：学校就一套
  6 位密码，不存在独立的「支付密码」）。测试单用完就 `deleteOrder`（JSON body），别留未支付单——堆积会让
  新下单 500。服务时间闸门等业务拒绝原样透传，不降级跳网页；深链 `#/pays?id=181`
  保留作兜底，无效路由会被前端打回首页，新增深链前先实测。
  三条一改就坏的钉子：**订单号只能用下单响应里那一个**（`paystep=2` 的 `orderid` 恒为
  null，别拿 `passwordMap` 的键——那是 uuid，发出去服务端回「订单不存在，请重新预定」）；
  **`ccctype[0].balance` 单位是元**（同一时刻一卡通 `accinfo[].balance=100` 分对照确认，
  按分渲染会把 1 元显示成 ¥0.01）；服务端拒绝含「订单不存在/已过期/已失效」时回金额步
  重新下单（`PowerPayModels.isOrderGone`），别让人在密码步反复重输。
- **充值有两个渠道**（2026-09-28 加农行，弹层金额步两枚 FilterChip，**下单后不许换**：
  `selectPowerChannel` 在非金额步直接忽略，订单与渠道绑定）。
  **农行支付**（`NEWPAYMENTPLATFORM` / `paytypeid=4`）= **App 内嵌农行手机版收银台**
  （`PowerBankPayActivity` + `PowerBankPayScreen`）：手机号 → 短信验证码 → 支付密码
  全在农行页面里，本 App 不读、不存、不落盘。五条不许动：
  ① **这一跳必须发微信 UA**（`PowerClient.MOBILE_CASHIER_UA`）——平台按请求 UA 分三支，
  只有微信 UA 给手机版收银台 `mobile.abchina.com/mpaynew/mpay/index`；桌面 UA 给 PC 版
  （手机上被裁、还要装安全控件）；**手机浏览器 UA 直接回空的 `code=6230`**（`msg` 却写
  「处理成功」，最容易误判成自己解析错了）。`body` 里的 `userAgent`/`isWX` 不影响分支；
  ② **手机版链接不能自己拼**：拿网页版那条的 TOKEN 去拼手机版地址，页面回
  `90015 无可用的支付方式`——必须由平台按手机版分支现发；
  ③ **链接白名单只认 https + `*.abchina.com`**（`bankCashierUrlFrom`）——那段 HTML 由
  第三方平台转发，不许把任意 URL 交给内嵌页；
  ④ **内嵌页只当容器**：不注入脚本、不读输入，只有加载/缩放/重试/外链转发（掌银、
  `intent://`）；别在这条链路上加任何输入框——想「把手机号/验证码/密码画成本 App 的表单」
  就是复刻农行的支付接口，凭证会经手本 App，不做（2026-09-28 用户问过，口径在 DESIGN §4.24）；
  ⑤ **TOKEN 现取现用**：一次性会话，「重新打开」是重新 `paystep=2` 取新的，别缓存复用；
  回到商户域（`charge.juwp.edu.cn`）即关内嵌页，剩下的交给等待步查单。
  等待步只有 `order.status=1` 才说「支付成功，已确认扣款」，没查到只说回调可能有延迟。

- **账单要能看出「充到哪」**（2026-09-28 用户问「已充值的账单能显示这个信息吗」）：
  ① 一卡通消费流水：Room **v15** 起 `ykt_turnovers` 有 `fromAccount`（卡号）与 `accType`
  （`###` 正式卡 / `000` 电子账户），**充值行**副标题与**详情**显示账户，文案唯一来源
  `YktModels.accountLabelOf`（认不出的类型返回 null——显示错账户比不显示更糟）；
  消费行不显示（一张卡一个值，只会挤掉商户名）。
  ② 电费缴费账单：详情有「支付方式」（`payid`：4 农行 / 59 电子账户），行副标题在房间
  缺位时由它顶位。**房间缺失不许推定**：平台 H5 的 `/sceneBind/add` 允许改绑房间——
  拿当前绑定房间去套历史记录会张冠李戴，宁缺勿错。空 `abstracts` 的旧归因（「平台偶尔
  不填、电子账户路径」）**是误诊**：2026-09-29 定位真相 = App 旧版下单缺
  `third_party`/`abstracts`，谁下的单谁空（App 单全空、官方单全有），修复见下节「下单」；
  历史那几笔补不了（平台落库，事后改不了）。
- **电费充值的密码步没有输入框**（2026-09-24 用户拍板）：6 格点阵就是输入位，键盘仍是
  系统的（透明 `BasicTextField` 垫在点阵下面，点阵不拦触摸）。进步自动聚焦、失败自动清空、
  受理后自动收键盘。别把 `OutlinedTextField` 加回来——用户明确否掉了那个矩形框。
- **充值免责声明先过一道**（2026-09-29 用户要求，DESIGN §4.24）：**每个充值入口**（侧栏充值选择、电费弹层「去充值电子账户 ›」、我的 → 校园卡页「充值」）打开弹层前都过
  `ui/common/RechargeDisclaimerDialog`，三条声明 + 「一周内不再提醒」。**首次（从未确认过）
  锁 5 秒**（按钮置灰、返回/遮罩无效、确认键倒数），确认（继续或取消）落
  `recharge_disclaimer_seen_at` 后任何一次弹出立即可关；勾选静默 7 天
  （`domain/RechargeDisclaimer` = 窗口与锁时长唯一口径，`RechargeDisclaimerTest` 钉住），
  电费与一卡通**共用同一份**。取消键文案是**「不同意并取消支付」**（2026-09-30 起）：
  明示不接受协议 = 本次充值作罢，宿主的 onDismiss 路径本来就不开充值弹层，别给它加放行。
  新加充值入口时必须过这道闸——别绕开。
- **充值的二次确认弹窗要加粗「充到哪」**（2026-09-28 用户要求：充错账户是钱的事）：
  电费弹层加粗**房间号**（一个房间一个电表，充错就是别人的；农行支付那条的等待步金额行
  也带房间号）；一卡通弹层加粗**目标账户**——正式卡给 6 位卡号、电子账户给账户名。
  卡号走 `data/ykt/YktModels.rechargeTargetCard`（第一张非挂失卡），与下单链路**同一份判断**：
  两处各写一份的话最坏是「弹窗写 A 卡、钱进 B 卡」，事后对账才发现。
  取不到标识（读数没回来 / 没有可充值的卡）就不显示数字，宁缺勿错。
- **余额提醒**（2026-09-24，DESIGN §3.13「余额提醒」）：设置项在「我的 → 校园卡」页
  （寝室电费 10–80 元 / 一卡通余额 10–50 元，步长 5）。口径单一来源 `domain/BalanceAlert.kt`：
  电费「元」= 剩余电量 × 单价（**唯一换算处**，电费充值弹层也走它，别再内联乘一次）、
  阈值是**严格小于**、档位表/夹取/文案都在这里。调度在 `ui/reminder/BalanceAlertReminder.kt`：
  检查间隔按设置档位（2026-10-07 起小时级：两链**各自独立**，默认 6h、档位
  3/6/8/10/12/24/48，`domain/BalanceAlert` 的间隔口径）+ 冷启动补查 + 设置变更后立即评估；
  周期任务 `balance_alert_periodic` 取两链最小档排（KEEP 保相位 / 变档 REPLACE，
  已排档位记 `balance_periodic_interval`），各路闸门是 `AutoSyncRules.shouldAttemptAt`
  （「上次**成功**检查时刻」epoch millis；**取数失败不落时刻**，间隔闸门保持开着）。
  「偏低期间只提醒一次」（`power/ykt_alert_notified` 标记）：通知发出或免扰拦截（补发兜底）
  即置位，回升随收件箱状态消解一并清除；改设置（开关/阈值/间隔）清标记重新评估。
  四条不许动：**失败不重试**（防撞风控）、一卡通只算**正式卡余额**（不含电子账户）、
  `balance_alert_periodic` / `BalanceAlertCheckWorker` 的名字（`KEEP` 下改名 = 周期兜底永久消失）、
  通知 id/tag 与落点（电费 1005 / 一卡通 1006；2026-10-20 起不带 extra，落今日页 / 左侧栏）。关凭证时两个开关一并回落（都靠那份凭证）。
  **PendingIntent requestCode（3005/3006）与通知 id 分离**：骑行提醒的通知 id 也是
  1005/1006，两边落点 intent 都指向 MainActivity 无 action，filterEquals 相同——
  requestCode 撞了会被 `FLAG_UPDATE_CURRENT` 改写落点（2026-09-24 修的真 bug）。
  **给通知加落点前先全局 grep 现有 requestCode**（上课 0、作业 1002/1003、
  骑行 2000/2005/2006、余额 3005/3006），撞了就是静默的点击错页。**2026-10-09 加事件入口**：付款码消费命中后由 `CampusBalanceSync` 把刚拉到的正式卡余额交给 `evaluateYktBalance` 当场评估一次（同样落「上次成功检查」；开关 / 免扰 / 只提醒一次口径不变）。
- 一卡通设置页的学号输入框**明文回填**已保存的学号（2026-09-24 用户拍板）；
  改回空框 = 不显示，会被用户当成「凭证丢了」，别当隐私优化删掉。
- **开启不再是一个 Switch**（2026-09-28 用户拍板，DESIGN §3.10）：填学号 + 查询密码后点
  「保存并开启」→ 先真实登录验证一次，成功才落库并置 `campusCardEnabled`；已开启后同一颗
  按钮变成「保存」（覆盖更新凭证），关闭走状态卡右侧的「关闭并清除」（二次确认后清凭证）。
  卡头只报状态、不带交互——别把 Switch 加回来。

## 电费流水的方向只看 tranamt 符号

- **电费流水的方向只看 `tranamt` 符号**（负 = 退款），金额存绝对值——`refund_flag` 在真实
  流水上**恒为 1**（2026-09-24 实测 11/11，含 50 元农行支付那几笔），拿它判退款会让整页充值
  显示成「退款」；平台 H5 自己也是 `tranamt > 0` 判充值（DESIGN §4.24「退款判据」）。
  用电量**没有平台接口**，靠本机读数表 `power_readings`（DB v11）差分：写入只有
  `PowerRepository.snapshot()` 一处、去重靠 `(epochMs, roomId)` 唯一索引，公式/均摊/跳过规则
  的唯一实现在 `domain/PowerUsage.kt`——别在 UI 或别处再算一遍，也别加后台轮询去采读数
  （第三方平台，记录密度就等于用户打开 App 的密度，DESIGN §3.13「用电统计」）。

## 消费流水页 / 缴费账单页（2026-09-26 重构）

- 统计图形**只有一份实现**：`ui/common/Charts.kt` 的 `AppBarChart`（柱状，柱子可点选中）与
  `AppRatioBar`（占比条）。三处调用 = 消费流水页「近 12 个月支出」、缴费账单页「近 12 个月充值」、
  用电统计「用电量（度）」。**别再各写一份自绘 Canvas**——此前三份的柱宽、透明度、轴标签都不一样。
- 月份导航同样共用：`ui/common/MonthPicker.kt` 的 `MonthNavRow` + `MonthPickerDialog`。
  窗口月份的唯一来源是 `StatementViewModel.windowMonths()`（流水页）与 `PowerBillUiState.monthKeys`
  （账单页）；UI 里别再按 `YearMonth.now()` 另算一份（两处各算一遍，跨月后弹窗会和柱子对不上）。
- 数值标签放在**标题行右侧**（`AppBarChart` 的 `selectedText`），不在柱顶：一屏 12 根柱、每格约
  24dp，柱顶浮标会被 Compose 裁成「¥2…」。改成柱顶前先量一下最小屏宽。
- 消费流水页的**类型筛选是本地过滤**当前月已加载的列表（`state.records`），不发请求、不改 DAO；
  整页只有一个 `LazyColumn`，汇总卡是它的第一个 item（别改回 `Column` 包列表，那样汇总卡会钉在顶部）。
- 缴费账单汇总口径 = **充值 / 净额 / 笔数**（净额 = 充值 − 退款），退款另起一行小注；
  别再把它并排成第三个指标。月份归属仍只看 `createdate`，**不要**改回 `feerange`。
- 账单与用电统计的明细行都可点：账单详情的「费用所属月」直接展示平台 `feerange` 原文
  （展示口径，与月份归属那条计算口径是两回事，别合并）。
