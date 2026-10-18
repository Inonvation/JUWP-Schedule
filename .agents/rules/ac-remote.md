# 宿舍空调遥控（今日页 · 江水生活）

作用域：改 `domain/MideaAc`、`ui/life/AcRemote*`、`data/ir/`、`data/prefs/AcRemotePrefsStore`、
今日页「江水生活」的图标入口与主界面接线时读。规格见 DESIGN §3.31 / §4.48。

## 协议口径（改帧 / 波形前必读）

两套口径，按 `AcProtocol` 切换，**默认 R05D**（国内美的挂机；2026-10-18 起）：

- **R05D（默认）**：6 字节 = 固定头 `B2 4D` + 功能字节 B + `~B` + 模式/温度字节 C + `~C`，
  整帧原样连发两遍，没有额外校验和。关机是专用帧 `B2 4D 7B 84 E0 1F`
  （B=0x7B、C=0xE0），不是翻电源位。温度 / 模式 / 风速查 `MideaR05d` 的表
  （温度非二进制顺序；送风取 0xEF 的「无定义」档）。波形 = 头 4400/4400 →
  48 位 → 550/5220 → 头 → 48 位 → 550/8800，一组约 192ms。
  **没有睡眠 / 定时 / 摆风 / 屏显 / 强力 / ECO 的公开编码**，R05D 下 UI 隐藏这些卡。
- **A1（可选）**：IRremoteESP8266 `ir_Midea` 的 48 位口径（Pioneer / Comfee / Danby
  等 OEM 与部分美的）：`0xA1` 头 / 电源·睡眠·风速·模式位 / 温度位 / 关机定时 /
  开机定时 / 校验和。校验和 = 前 5 字节逐字节按位反序求和 → 取 256 的补 → 再按位反序。
  **温度字节位 6 恒 1（0x40）**：原装帧每一帧都有（17℃ = 0x40、30℃ = 0x4D）；
  2026-10-18 前漏了这位，空调可能整帧不认。温度 17–30 在 `MideaAc.frame` 钳制；
  睡眠 = 第二字节位 6；定时见 `timerByte`（30 分钟一档、0.5–24 小时、不足 30 分钟
  或 null = 0xFF 不定时；档位 N 时 `0x80 | ((N-1) shl 1) | 1`；7 小时 = 0x9B，
  对齐上游 raw `A1A34DFF9B34`）。切换类功能（摆风 / 屏显 / 强力 / ECO）协议里没有
  状态位：特殊帧 `MideaAc.Toggle`（`0xA2` 头）与主帧**连发**（先主帧、后切换帧）；
  **切换帧固定发 1 次，不参与补发**（同一帧发两次会翻转两次 = 白按）；界面记忆在
  `MideaAc.Toggles`（跟快照落盘），物理遥控动过会不同步，接受。
  波形 = 头 4480/4480 → 正码 48 位 → 560/5600 → 头 → 按位取反 48 位 → 560/105600。
- 发一次按键 ViewModel 默认补发 2 次（每次 `transmit` 只放一组，见下条纪律）。
- **动任何一位之前先动测试**：R05D 的实测向量 `B2 4D BF 40 D0 2F`（自动风 26℃ 制冷）/
  `B2 4D 7B 84 E0 1F`（关机）见 `MideaR05dTest`；A1 的校验和 5 组、完整波形 2 组、
  重复组边界、定时 7 小时、切换帧校验和见 `MideaAcTest`（对齐 IRremoteESP8266）。
  别只改实现不改测试。
- 模式 / 风速持久化存**协议位值**（不是 ordinal）——状态层存 `MideaAc` 的位值，
  发 R05D 时按枚举名映射到 `MideaR05d` 的码表。
- 参考实现（公开文档 / 代码）：IRremoteESP8266 `ir_Midea`（A1）/ `ir_Midea24` /
  `ir_Coolix`；R05D 解码说明
  https://pinaland.cn/archives/infrared-remote-nec-midea-r05d-protocol.html ；
  可工作的 ESP8266 实现 `poboll/esp-media-ir`（`IR_2.ino`）。

## 纪律

- 发射只走 `AcIrBlaster`（ConsumerIrManager 是平台唯一出口）；`TRANSMIT_IR` 是普通权限，
  不弹窗、不申请。
- 发射在 `AcRemoteViewModel` 里走 `sendLock`（Mutex）**串行化**：连点与温度长按连发的
  帧序靠它，别把 `transmit` 挪出互斥。
- 温度长按连发的起判 / 间隔（`HOLD_START_DELAY_MS` / `HOLD_REPEAT_INTERVAL_MS`，
  `AcRemoteScreen` 页内常量）改前先真机对拍；松手时点击回调补一步是**有意的**
  （原装遥控手感，数值看得到）。送风档温度置灰（`state.mode != Fan`）只在 UI 层拦。
- 页面走描边卡分组（主控 / 模式风速 / 附加 / 定时）；定时两行是卡内平铺行口径
  （`AppCardDivider` 分隔、行自己 clickable + haptics）。
- **每次操作发完整状态帧**，同时写回 `AcRemotePrefsStore`；空调无回执，面板显示的是
  「本机替它记的设定」，别在 UI 上做「查询空调状态」这类不存在的动作。
- 无红外发射器机型：进页只给提示（`AcIrBlaster.supported`），不发空炮、不做隐藏入口。
- 入口 2026-10-16 在生活页「校园服务」卡（成绩查询前）；**2026-10-19 起迁到今日页
  「江水生活」的三枚图标入口之一**（取快递 / U净 / 空调遥控）。别再挪去百宝箱，也别加回生活页。
- 真机点验口径：对着空调按电源，开机 / 关机两态各响应即过。不通时先切
  R05D ↔ A1 两套口径试（协议卡，默认 R05D），别直接改 UI；两套都不认再考虑
  Coolix（IRremoteESP8266 的第三套 Midea OEM 口径）。
