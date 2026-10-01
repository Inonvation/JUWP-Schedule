# U净（美的校园洗衣） · 规则

入口：我的 → 校园服务 → 「U净」（`SubpageScreen.UJING`）。DESIGN §3.23（UI）/ §4.37（技术）。
**P1+P2 已交付（2026-10-01），同日体验补**：登录 / 空闲看板 / 扫码识别 / 套餐 / **下单 →
支付宝 SDK 支付 → 订单卡（倒计时 · 取消 · 云端启动）**；下单可选参数（水温 / 强制投放档 /
烘干 `dryTime`）、洗衣完成提醒（`ui/reminder/UjingDoneReminder`）、页面排版收口
（订单 > 扫码 > 看板 > 账号）。P3 蓝牙机型 BLE 透传启动待做（按学校机型 `moduleType`
实测决定；云端机型的启动已随 P2 交付），端点与 BLE 协议细节在 `docs/ujing-plan.md`（本地）。
**实测定论（2026-10-01）**：匿名 `scanWasherCode` 三种头组合均回 `401 "JWT token is missing"`
——只读也必须登录，**无免登路径**。

## 协议与指纹

- 网关 `https://phoenix.ujing.online/api/v1`，壳 `{code, message, data}`，`code == 0` 成功；
  `401 / -99` = 会话过期（`UjingSessionExpiredException`）。
- 两组「客户端指纹」：取码/登录 `x-app-code: ZI`（2.4.3）、业务 `BI`（2.4.2）+ `weex-version`。
  **没有客户端签名**（无需逆向任何加密函数）。服务端对 code 宽容（ZA/BA 亦可用）。
- 服务端收紧校验时**先改 `UjingApiConfig`**（版本号 / UA / 头组合），改前重新抓包对齐。
- 注意平台把经度拼成 **`lont`**；`devices/scanWasherCode` 必须传**二维码原始内容**（勿预提取 uuid）。

## 红线（违反即打回）

1. **不绕过支付**：费用全走官方渠道（支付宝 SDK，orderInfo 服务端签发）；不代付、不伪造支付
   凭证、不做"免费洗衣"；不做批量 / 自动 / 抢单脚本（社区有，别学）；只操作使用者本人有
   使用权的设备。
2. **写操作零自动重试 + 二次确认**：下单 / 取消 / 启动都是写操作；超时后先查在案订单
   （`refreshOrder`），确认没有已创建的订单再让用户重试，绝不盲目重发；**已有在案订单不允许
   叠单**（`requestOrder` 先拦）。
3. **登录节制**：只在用户主动点「获取验证码」时发码（60 秒冷却，UI 与 ViewModel 双保险）；
   短时间高频登录会被服务端限制数十分钟（实测）。会话过期**绝不自动重登**——清 token 留手机号，
   由用户重新登录（`UjingRepository.markExpired`）。
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
- `wp_detergentGearId` / `wp_disinfectantGearId`：`isForceDetergent` / `isForceDisinfectant`
  为真时补**标准档**（1 / 4，社区档位枚举）。档位的价格语义没有实样，别在 UI 上造档位选择。
- `dryTime`：仅烘干机（type == 2），协议口径 = 模式时长 / 10（`UjingState.dryTimeFor`）。
- `orders/{id}/detail` 固定带 `additional=price`（照抄官方抓包形态，取 `payPrice`）。
- 订单快照（`UjingOrderSnapshot`）的 `createdAt`（支付窗口基准）与 `durationSeconds`
  （进度条分母）**只在下单时写、刷新原样保留**；旧版本快照两列为 0，UI 各自退回静态提示。

## 命名与文案

- 页面显示名「U净」（用户 2026-10-01 拍板，不用「洗衣房」）；模块与包名用 `ujing`。
- 图标 `HugeIcons.WashingMachine`（本地 JAR 查核，勿猜）。
- 看板文案口径在 `UjingState.BoardLine`（`空闲 N/M` / `暂无空闲` + `约等 X 分钟`），UI 不另拼。

## 下一步（P3 动工前重读）

`docs/ujing-plan.md` §4.7（BLE 通道三坑：随机地址必须扫描连接、stopScan 后 delay(400ms)、
静默拒绝）与 §2.5 坑表。先实测学校机型 `moduleType`（扫码结果卡的「通信模块」行）：
1/5 = 纯蓝牙 → 必做 BLE；0/2/3/4/7 → 云端启动已随 P2 交付，无需 BLE。
遗留实测：JWT 有效期（登录后 base64 解 `exp`）。
