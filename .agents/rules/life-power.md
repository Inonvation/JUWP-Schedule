# 生活页 · 校园卡 · 电费 · 胖乖开水

作用域：改生活页、一卡通付款码与流水、寝室电费读写与充值、余额提醒、胖乖开水时读。规格见 DESIGN §3.13 / §4.19 / §4.24。

## 胖乖（P4）开水

- 参考 `F:\light-life-v3.0`；Base `https://userapi.qiekj.com/`
- 只做：登录、开水、余额、订单；签到默认关；禁止刷积分
- 实现在 `data/qiekj/` + `ui/water/`；Token 走 EncryptedSharedPreferences，禁止进日志
- 一卡通付款码（DESIGN §3.10/§4.19）：密码字段 = 安全键盘密文（字形 MD5 表一次替换）+ `$1$` + uuid；
  **未知字形/非双射/样板自检不过 = 立即报错不猜**；登录密码仅数字（键盘只映射 0-9）；
  token 只存内存不落盘；付款码不进日志/剪贴板/相册；凭证交互照 `TweakDetectScreen`（开启先真实验证、关闭即清除）
  调用链（11 步顺序不可乱）见 DESIGN §4.10

## 生活页（一卡通 · 寝室电费）

- 底栏第三项「生活」，开关 `DisplayPrefs.lifeTabEnabled` **默认开**（我的 → 通用 → 生活页）；
  关掉后底栏回到 3 项，页内关掉时自动退回今日页。今日页的一卡通卡入口已移除，见 `today-ui.md`。
- **码不预取**：`PayCodeViewModel` 初值 `Idle`（占位条），点击才 `load()`，收起调 `collapse()`
  （丢码 + 回收位图 + 停消费检测）。展开期间 `FLAG_SECURE` + 亮度拉满，收起即恢复。
  付款码页与生活页共用这一份 VM，别再写第二套取码逻辑。
  取码骨架与成功态**同几何**（码位 `aspectRatio` 占死 + 按钮占位行），改布局两边同步改，
  否则取码完成时卡片跳动（2026-09-24 收口口径）。余额卡与电费卡靠
  `IntrinsicSize.Min` + 卡内 `weight` 空隙恒等高，改动别绕开它。
- **一处凭证**：电费登录 = 一卡通的学号 + 查询密码（`YktCredentialStore`，2026-09-23 实测
  两个平台同一密码）。一关了之：凭证清掉时电费卡同样显示「未开启凭证」。
- **读表参数缺一不可**：`feeitemid=181` / `type=IEC` / `level=3` / campus+building+room；
  少一个平台只回 `code=500「未知异常」`（HTTP 200），**业务码 401 也藏在 HTTP 200 里**，
  必须读 body 的 `code` 才能触发重登。
- **电费充值已 App 内完成**（2026-09-24 实测收口）：下单 = `POST /blade-pay/pay`
  `paystep=0`（`feeitemid=181 + tranamt + flag=choose`，签名 `PowerPaySign`）；
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
- **电费充值的密码步没有输入框**（2026-09-24 用户拍板）：6 格点阵就是输入位，键盘仍是
  系统的（透明 `BasicTextField` 垫在点阵下面，点阵不拦触摸）。进步自动聚焦、失败自动清空、
  受理后自动收键盘。别把 `OutlinedTextField` 加回来——用户明确否掉了那个矩形框。
- **余额提醒**（2026-09-24，DESIGN §3.13「余额提醒」）：设置项在「我的 → 校园卡」页
  （寝室电费 10–80 元 / 一卡通余额 10–50 元，步长 5）。口径单一来源 `domain/BalanceAlert.kt`：
  电费「元」= 剩余电量 × 单价（**唯一换算处**，生活页电费卡也走它，别再内联乘一次）、
  阈值是**严格小于**、档位表/夹取/文案都在这里。调度在 `ui/reminder/BalanceAlertReminder.kt`：
  每天一次 + 冷启动补查 + 设置变更后立即评估，闸门是「上次**成功**检查日期」
  （同一天每个来源最多一条；**取数失败不落日期**，当天还能补查）。
  四条不许动：**失败不重试**（防撞风控）、一卡通只算**正式卡余额**（不含电子账户）、
  `balance_alert_periodic` / `BalanceAlertCheckWorker` 的名字（`KEEP` 下改名 = 每日兜底永久消失）、
  通知 id/tag 与落点（电费 1005 / 一卡通 1006，`EXTRA_ROUTE=ROUTE_LIFE` 落生活页；
  生活页开关关掉时不带 extra 落今日页）。关凭证时两个开关一并回落（都靠那份凭证）。
  **PendingIntent requestCode（3005/3006）与通知 id 分离**：骑行提醒的通知 id 也是
  1005/1006，两边落点 intent 都指向 MainActivity 无 action，filterEquals 相同——
  requestCode 撞了会被 `FLAG_UPDATE_CURRENT` 改写落点（2026-09-24 修的真 bug）。
  **给通知加落点前先全局 grep 现有 requestCode**（上课 0、作业 1002/1003、
  骑行 2000/2005/2006、余额 3005/3006），撞了就是静默的点击错页。
- 一卡通设置页的学号输入框**明文回填**已保存的学号（2026-09-24 用户拍板）；
  改回空框 = 不显示，会被用户当成「凭证丢了」，别当隐私优化删掉。

## 电费流水的方向只看 tranamt 符号

- **电费流水的方向只看 `tranamt` 符号**（负 = 退款），金额存绝对值——`refund_flag` 在真实
  流水上**恒为 1**（2026-09-24 实测 11/11，含 50 元农行支付那几笔），拿它判退款会让整页充值
  显示成「退款」；平台 H5 自己也是 `tranamt > 0` 判充值（DESIGN §4.24「退款判据」）。
  用电量**没有平台接口**，靠本机读数表 `power_readings`（DB v11）差分：写入只有
  `PowerRepository.snapshot()` 一处、去重靠 `(epochMs, roomId)` 唯一索引，公式/均摊/跳过规则
  的唯一实现在 `domain/PowerUsage.kt`——别在 UI 或别处再算一遍，也别加后台轮询去采读数
  （第三方平台，记录密度就等于用户打开 App 的密度，DESIGN §3.13「用电统计」）。
