# 快趣出行小程序逆向分析（2026-09-28，只读分析产物，不入库）

来源：PC 微信 4.x 小程序缓存 `wx0dc360db01e2c4f6`（快趣出行，版本号 V6.0.0）。
解密：V1MMWX 头，PBKDF2-SHA1(appid, "saltiest", 1000) 解前 1024 字节，余下 XOR 0x66。
产物：`%TEMP%\kvcoo\unpacked\`（260 文件，核心 `app-service.js` / `appservice.app.js` / `chunk_0.appservice.js`）。

## 服务端

- 生产 base：`https://api.kvcoogo.com/ManagerApi/api`
- 请求头：`content-type: application/x-www-form-urlencoded`、`client-type: 1`、`token: <token>`
- 响应包裹：`{resultCode, errorCode, resultMsg, result}`，`resultCode==1 && errorCode==0` 时 `result` 为业务数据
- **无请求签名**——token 是唯一凭证，form 表单无加密

## 登录与 token

| 项 | 内容 |
|----|------|
| 登录 | `wx.login()` 拿 code → `POST /v3.0.0/appletLogin {code}` → 已注册微信直接返回 `{token, mobile, passwordStatus, ...}` |
| **账号密码登录** | `POST /v1.0.0/userLoginByPassword`，参数 `{mobile, password: MD5(密码)}`（标准小写 hex，spark-md5，无盐）——小程序自带的 `pages/login/index` 登录页即走此接口（`phoneLogin`），**与 App 同源可用** |
| 响应 | 两种登录同构：直接返回 `{token, mobile, passwordStatus, ...}`，同一套 token 体系 |
| 未注册 | 返回空 token 或 code 30015（微信未绑定账号）→ 走手机号注册/绑定 |
| 存储 | `wx.setStorageSync("kvc_token")`；PC 微信的 storage 落在 radium 目录磁盘上（可读取的候选路径） |
| 续期 | 启动/后台静默 `wx.login` → `appletLogin` 换发新 token；`mobile` 变了会强制登出。**账号密码模式无此路径**，过期后用本地凭证重登 |
| 过期 | `kvc_token` 对象带过期时间字段（`before(timestamp(e...))` 判断），具体 TTL 未知 |

**结论：JUWPHub 可以像胖乖/一卡通那样做应用内账号密码登录**——接口、参数、加密方式全部明确，
token 与小程序同一体系，登录后 queryUnderwayOrder / endTheOrder / createOrder 全部可用。
待真机验证两点：① token TTL（决定自动重登频率）；② `client-type: 1` 头对 App 侧请求是否无感。

## 全量接口清单（POST）

| 接口 | 路径 | 说明 |
|------|------|------|
| 查附近车 | `/v2.0.0/queryNearbyCar` | 已实现于 App（`data/kqcx`），无鉴权 |
| 查单车 | `/v1.0.0/queryOneCar`、`/v2.0.0/queryOneCar` | 车号/扫码查车 |
| 查价格 | `/v1.0.0/queryCarPrice` | |
| **创建订单（开车）** | `/v4.0.0/createOrder` | `{carNum, lat, lng, clientType:1, clientVersion, locationSource}`；响应含 `carNum/bluetoothName/currentPercent/deviceType/dispatchMoney/payMoney/closeStatus/subList/helmet` |
| **开锁** | `/v1.0.0/greenCarUnlock` | `{lng, lat, locationSource}`；`16015` = 头盔锁未开；`muteCarLock`（蓝牙路径，`ecuType==0`） |
| **还车** | `/v3.0.0/endTheOrder` | `{lat, lng, locationValid, locationSource, locationTime, bdStatus:0, bdPileStatus:0, bluetoothName:"", dispatchFlag, siteId?, voucherRecordId?}`；响应可含 `dispatchMoney`（调度费提示） |
| 锁状态 | `/v1.0.0/carLockFlag` | 查是否还锁上 |
| 骑行中订单 | `/v1.0.0/queryUnderwayOrder` | **只读**；响应含 carNum、bluetoothName、开始时间等 |
| 待支付订单 | `/v1.0.0/queryUnPayOrder` | |
| 订单列表 | `/v2.0.0/queryOrderList` | |
| **订单支付** | `/v3.0.0/appletOrderPay` | `{jsCode}`（wx.login code 换支付参数）→ `wx.requestPayment` 拉收银台 |
| 充值/押金 | `/v2.0.0/appletRechargeBalance`、`appletRechargeDeposit`、`/v1.0.0/refundDeposit` | 同走 requestPayment |
| 找车响铃 | `/v1.0.0/greenCarFind` | `{}` |
| 头盔锁 | `/v1.0.0/helmetUnLock` / `helmetLock` | |
| 上传定位 | `/v1.0.0/uploadBdRecord` | 骑行中上报轨迹 |
| 用户信息 | `/v2.0.0/getUserInfo` | |
| 消息/公告 | `/v2.0.0/queryPushMsg`、`/v1.0.0/queryNotice` | |
| 优惠券 | `/v3.0.0/queryCouponOption`、`/v2.0.0/queryUserCoupon` 等 | |
| 短信/密码 | `/v3.0.0/getRandomCode`、`sendSmsForPassword`、`/v1.0.0/updatePassword` | |
| 注销 | `/v1.0.0/sendLogoutSms`、`/v1.0.0/userLogout` | |

## 骑行主流程（chunk_0 = 骑行页）

1. 扫码 → `queryOneCar` → `createOrder`（创建订单即绑定车）→ `greenCarUnlock`（GPRS 开锁）→ 骑行页显示 soc/时长/费用
2. 骑行中：`uploadBdRecord` 定期上报；`queryUnderwayOrder` 刷新
3. 还车：面板「还车」→ `endTheOrder`（带定位与调度标志）→ 免费时段内 `payMoney==0` 无需支付（对应「订单无需支付」通知）；有费用 → 待支付页 `appletOrderPay`

## 应用内实现的可行性分级

**A. 只读：骑行状态查询（推荐起点）**
- 依赖：token + `queryUnderwayOrder`。无资金动作、无副作用。
- token 获取：PC 微信 storage 读 `kvc_token`（一次性）；难点是续期——token 过期后只能重新取。
- 价值：今日页/小组件直接显示「骑行中 · 已 x 分钟」，配合现有免费时长倒计时。

**B. 应用内还车（中风险）**
- `endTheOrder` 纯 HTTP，协议无障碍。风险：定位有效性校验（`locationValid`）、失败重试语义、调度费意外产生；代用户执行还车 = 替用户做履约动作，失败计费责任在 App。
- token 失效时的处理必须有明确降级（跳小程序，不能静默失败）。

**C. 应用内开车（高风险，不建议）**
- `createOrder` 创建即计费开始 + `greenCarUnlock` 物理开锁。误触/风控封号的代价最高。
- 快趣没有开放 API，代调核心写接口被风控识别的可能性随频率上升。

## 共同前置：token 从哪来

1. **PC 微信 storage**（已验证路径存在，读取与解密待做）——一次性手动导出
2. 手机无 root 无法读 `kvc_token`；小程序也没有导出途径
3. token 过期后需用户重新在 PC 微信打开一次快趣小程序（或找到 storage 的自动同步办法）

隐私红线沿用：token 仅内存不落盘、无日志、凭证不入库。
