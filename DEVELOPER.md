# 开发者文档：逆向协议与爬取实现

本项目（JUWPHub，江西水利电力大学的**非官方**课表 App）里**别人最值得借鉴的部分**：
强智教务的爬取链路与解析算法，以及五个校园第三方平台（胖乖生活、趣智校园、快趣出行、
U净 洗衣房、一卡通/电费）逆向出来的协议结构——端点、登录、签名、实机踩坑，和把它们
搬进 App 的工程做法。

> 产品与 UI 规格见 [DESIGN.md](DESIGN.md)（§3 UI / §4 逐模块技术决策；2026-10-06 起该文件
> 只做索引，正文拆在 [`design/`](design/) 下，章节号未变）；
> 爬虫脚本速查见 [scripts/README.md](scripts/README.md)（比本文更细的排错表）；
> AI 结对约定见 [AGENTS.md](AGENTS.md)。
>
> 换校/复用时请遵守同样的红线：只模拟正常客户端操作、凭证不进代码仓库、
> 不刷积分、不绕过付费、不伪造官方身份。本文所有端点均来自**对自己账号**的抓包与
> 公开逆向资料的交叉核对。

---

## 0. 全景：App 连了哪些系统

| 系统 | 网关 | 鉴权 | 请求签名 | 代码 | 借鉴价值 |
|------|------|------|----------|------|----------|
| 教务（强智） | `jiaowu.juwp.edu.cn:8080/jsxsd` | CAS → SSO Cookie | 无 | `data/jw/` + `scripts/` | DOM 解析算法、layui 接口族、WebView 注入 |
| 胖乖生活（开水） | `userapi.qiekj.com` | token 头 | SHA-256 拼串 | `data/qiekj/` | 最简单的签名协议样板 |
| 趣智校园（热水） | `v3-api.china-qzxy.cn` | loginCode 会话串 | 双重 MD5（.so 逆向所得） | `data/qzxy/` | **蓝牙帧协议 + so 反汇编签名**，最硬核 |
| 快趣出行（单车） | `api.kvcoogo.com/ManagerApi/api` | token 头（部分接口免鉴权） | 无 | `data/kqcx/` | 无签名 API、GCJ-02 地图、写操作的责任闸 |
| U净 洗衣房 | `phoenix.ujing.online/api/v1` | JWT | 无（只有 header 指纹） | `data/ujing/` | 官方支付宝 SDK 接入、header 指纹 |
| 一卡通（新中新） | `yktwx.juwp.edu.cn/plat` | OAuth2 + JWT | 下单独立 SIGN | `data/ykt/` | 「安全键盘」复现、字形识别 |
| 寝室电费（新开普） | `charge.juwp.edu.cn` | OAuth token | 无 | `data/power/` | 读表参数纪律、收银台 UA 陷阱 |
| 盖章成绩单（金格） | `jwxyxx.juwp.edu.cn/ptwork` | CAS + sid | 无 | `scripts/fetch_transcript.py` | 令牌式出单（见 scripts/README §5.6） |

架构一句话：单模块 `:app`，`domain/` 纯 Kotlin 可 JVM 测，`data/<系统>/` 每个系统一个
独立 API 客户端（互不依赖、可整块删除），`ui/` Compose 按模块分包。细节见 DESIGN §4.2。

---

## 1. 教务（强智）：从登录到课表 JSON

完整脚本在 `scripts/`（`jw_session.py` 登录 + 6 个 fetch 脚本），App 端 WebView 导入在
`ui/jwvw/`，OkHttp 直取链在 `data/jw/`。**同一套页面规则有三份实现**（Python / 注入 JS /
Kotlin 解析），换校时三处同步改。

### 1.1 登录链路：CAS → 教务 SSO

```
[1] CAS  POST cas/login（username/password/execution/_eventId=submit）→ 拿 TGC cookie
[2] 预热 GET 教务 :81 端口（拿 bzb_njw cookie——缺了教务不认票据，「第一次必挂」的根因）
    → GET cas/login?service=http://jiaowu.juwp.edu.cn/sso.jsp   ← service 不能带端口，带了 500
    → 跟 302 链落到 :8080/jsxsd/framework/xsMainV.htmlx
[3] 校验 拉主页：正常 ~150KB；860 字节的「用户没有登录」= 退化响应
```

三条通用经验（脚本里趟出来的，多数学校适用）：

1. **`trust_env = False`**。shell 注入的 `HTTP_PROXY/HTTPS_PROXY` 会被 requests 默认读取，
   而教务对代理出口区别对待——现象是**同一请求时而 200 时而 404**，极易误判「教务挂了」。
   App 端同病：用户手机开着 VPN 时统一认证直接超时，检测到要点名提示。
2. **手动跟 302**（`allow_redirects=False` 逐跳跟）：出问题能说出断在哪一跳。
3. **每步都有可判定的成功标志**（cookie 名 / 落点 URL / 字节数），报「断在哪步」而不是
   笼统的「登录失败」。

会话别每次重登：CAS 已知会因连环完整登录触发验证码。三级降级——教务会话探针复用
（1 个请求）→ CAS TGC 换新票（≈5 个）→ 完整账密登录。见 `scripts/jw_session.py` 的
`load_or_login`。

### 1.2 理论课表解析：列序 + colspan/rowspan carry

页面 `GET /jsxsd/xskb/xskb_list.do?viweType=0`（学期参数 `xnxq01id`）。课程块
`li.courselists-item`，详情 `.qz-hasCourse-abbrinfo`（`老师:X;时间:1-10周[1-2节];地点:Y`）。

**星期必须从课程所在 `<td>` 的列序推**——这是本项目踩过最深的坑：

- 表格第 0 列是节次标签，第 1–7 列才是周一到周日；
- `li` 上的 `qz-hasCourse-N` class **几乎恒为 1**（模板拿它当「有课」样式），
  拿它当星期来源，所有课会堆到周一；
- 列号要**累加 `colspan`**，并用 carry 表记录 `rowspan` 的跨行占用：强智在
  「同一天连续两大节上同一门课」时合并单元格，不补偏移的话该行之后所有课的星期整体前移。

核心算法（Python 与注入 JS 同一套）：

```python
carry: dict[int, int] = {}          # 列号 → 还剩几行被上方 rowspan 占用
for tr in soup.select("tbody tr"):
    for key in list(carry):         # 每行开始先衰减占用
        carry[key] -= 1
        if carry[key] <= 0: del carry[key]
    col = 0
    for td in tr.find_all("td", recursive=False):
        while carry.get(col, 0) > 0: col += 1    # 跳过被上方合并占掉的列
        rowspan = int(td.get("rowspan") or 1)
        colspan = int(td.get("colspan") or 1)
        if rowspan > 1: carry[col] = rowspan
        if td.get("name") == "kbDataTd" and 1 <= col <= 7:
            ...           # 该格里的每个 li 就是一门课，day = col
        col += colspan
```

另两个坑：用 `html.parser` 别用 `lxml`（强智页面有双 doctype，lxml 会丢节点）；
教务**会忽略未知学期参数**照常返回当前学期——所以「请求学期 ≠ 返回学期」要直接报错，
不能静默爬错学期。

### 1.3 实验课表解析：与理论课表零复用

页面 `GET /jsxsd/syjx/toXskb.do`。**同一教务里两张课表页结构完全不同**，解析逻辑不可互相套用：

- 表是「周次 × 节次」两级纵轴：每个周次占 6 行，周次标签是首行里 `rowspan=6` 的单元格，
  **课块不知道自己属于哪一周**，必须按行向上找周次标签；
- 星期按行形态右对齐：`day = td 下标 - (本行 td 总数 - 8)`；
- 页面**不提供教师字段**（保持空串而不是瞎猜）；tooltip 里的「节次：60304」是页面内部编码；
- 同一门课每个有课周次各出一块，必须按 `(名称, 星期, 节次, 地点)` 聚合、weeks 取并集。
  **聚合键必须含地点**——实训课按批次分周上课，同名课会分布在不同实训室。

### 1.4 layui JSON 接口族：考试 / 成绩 / 教材 / 选课

这四类数据不走 DOM，是 **layui 表格的 JSON 接口**，GET 即可（壳页 → `<table data-url>` 找到地址）：

```
考试  GET /jsxsd/xsks/xsksap_list?xnxqid=<学期>&pageNum=1&pageSize=200   （学期参数 xnxqid）
成绩  GET /jsxsd/kscj/cjcx_list?kksj=<学期|空=全部>&pageNum=1&pageSize=200（学期参数 kksj）
教材  GET /jsxsd/nxsjc/xsjcqr?xnxqid=<学期>&pageNum=1&pageSize=200
选课  GET /jsxsd/xkgl/loadXsxkjgList?lx=xkrz&type=list&xnxqid=<学期>&pageNum=1&pageSize=200
轮次  GET /jsxsd/xsxk/xklc_list_data
响应  { "code": 0, "count": <总条数>, "data": [...] }
```

换个强智学校大概率原样复现的四个坑：

1. **分页参数是 `pageNum`/`pageSize`**（强智 `window.initQzTable` 自定义），layui 默认的
   `page`/`limit` 拿不到数据；超出单页按 `count` 翻页。
2. **接口地址不带 `.do`**。带 `.do` 的同名地址返回「系统功能暂未开放」页面——那是校方的
   功能开关，必须与「接口正常但 count=0」区分开。
3. **学期参数名每个页面都不一样**：课表 `xnxq01id`、考试/教材 `xnxqid`、成绩 `kksj`。
   轮次接口的 `code` 还是**字符串 `"0"`**（其他接口是数字），判等统一按字符串比。
4. 成绩是双字段：`zcj`（数值）+ `zcjstr`（字符串），**展示一律以 `zcjstr` 为口径**
   （等级制「优」时数值为空）；`kz=1` = 「请评教」，成绩被锁定不显示分数。

字段语义对照（强智原名 → 含义）见 [scripts/README.md §5.4](scripts/README.md#54-考试安排与课程成绩2026-09-19-实测)。

### 1.5 App 端 WebView 注入（生产导入路径）

用户在 WebView 里自己登录（代码只有 URL 与解析规则，零凭证），然后注入 JS 抽取。
几个不踩不知道的坑：

- **`loadUrl` 是异步的**：注入前必须等 `onPageFinished`（按 URL 片段放行的闸门 +
  CompletableDeferred，超时 20s/页），否则抽到的是旧页面 DOM。且**不能用 URL 子串判页面类型**
  ——两张课表的 URL 都含 `xskb`（`xskb_list` / `syjx/toXskb`），要按页面特征判。
- **`evaluateJavascript` 不会 await Promise**：同源 fetch 的结果收不回来。方案是注入
  fire-and-forget 脚本把结果写进 `window.__qzJson`（`'ERR:'+e` 表示失败），Kotlin 侧每 300ms
  轮询回收（页面不跳转，窗口变量不会丢）。
- **HTTP 5xx 只有 `onReceivedHttpError` 能看到**：Chromium 把错误响应体当普通页面渲染，
  `onPageFinished` 照常回调——失败页会把错误态「洗成正常」，要按失败 URL 标记一次性消费。
- **教务把「未登录」渲染成 HTTP 200 的登录页**（URL 不变）：会话探测注入 DOM 探针
  （`#loginDiv`/`input[type=password]`），且只在教务域判定（在 CAS 域判定会把用户正在
  登录误报成会话失效）。
- **CAS ticket 是一次性票据**：重试要区分「reload 失败页」与「回认证入口重走链路」，
  重放已消费的 ticket 只会再挂一次；认证链上的 500 是自愈型（响应顺手写下缺失 cookie），
  自动重试一次即可。
- **从预热入口进而不是 CAS 直链**：WebView 从未访问过教务域时，第一次 `sso.jsp?ticket=`
  会 500（缺 `bzb_njw`），先落教务域再跳认证，把竞态消掉。
- 学期号用**白名单正则**校验后才允许拼进注入 JS——既防脏数据落库，也从根上杜绝引号注入。

OkHttp 直取链（成绩/考试/选课/调课四条自动同步用，`data/jw/`）复刻同一登录链，两个专属坑：
重定向基准写反（`current.resolve(location)` 别写成反向，每轮回到原 URL 误报重定向过多）；
**校园 IPv6 黑洞**（域名有 AAAA 记录而 v6 不可达时 OkHttp 默认 v6 优先，每步白等 31 秒
超时）——修法是 v4 优先的自定义 Dns（AAAA 不丢弃）。

---

## 2. 胖乖生活（开水）：HTTP 全链路 + 最简单的签名

协议来源：参考实现 light-life（`Inonvation/light-life`）。设备是联网型，开阀全程 HTTP。

- 网关 `https://userapi.qiekj.com/`，Retrofit Form POST；信封 `{code, msg, data}`。
- **接口返回脏数据**：`data` 可能是空串、数字字段当字符串——模型层要做宽容解码
  （kotlinx 自定义 lenient serializer），别信严格 DTO。
- **签名**（整串小写 SHA-256 hex，字段序不可调换；登录类接口 `common/*`、`user/reg` 不签名）：

```
sign = sha256("appSecret=$s&channel=$c&timestamp=$t&token=$tok&version=$v" + path)
```

开水调用链**顺序不可乱**（每步失败走诊断，风控类失败不可程序化绕过）：

```
goods/latestUsed(categoryCode=5) 取设备
→ goods/normal/skus(goodsId)         取 skuId（必需）
→ goods/water/sync                   预检（失败不阻断）
→ goods/normal/details(goodsId)      取 imei + supportAppUserFinish（机型能力，必需）
→ userIntegral/checkUserIsRisk       积分风控（必需）
→ payChannelRoute/addUserAfterPayChannel(method=15)  开通后付（必需）
→ orderRisk/isCheckLocation          位置风控（必需）
→ goods/water/unlock(skuId, promotions)  出水
→ 轮询 goods/water/sync（1s × ≤300，workStatus==2 为出水中）
   └─ 用户点「结束用水」→ 机型支持才发 goods/endWater(goodsId)（best-effort；不支持/被拒走设备端提示）→ 继续轮询等 status==5 → 结算
→ order/afterPay/creating(orderNo) → order/detail(orderId) 取账单

旁路（2026-10-14 从胖乖 1.123.0 反编译补出）：
  order/list(page, pageSize) → ItemsList<OrderItemBean>      服务端账单历史（点单笔走 order/detail）
  （trade/check_device_status 已删，2026-10-16）
```

**账单口径最容易读反**：`tokenCoinDiscount`（小票）是**账户余额抵扣**，不是平台优惠——
每次开水默认先扣小票，扣完才走现金后付，所以「实付 ¥0.00」只是现金口径为零。
本次花费 = 小票支付 + 现金实付；把实付 0.00 当「免单」展示是方向性错误。

**sync 的会话字段（2026-10-05 实测回填）**：`goods/water/sync` 的 `data` 是
`{status, workStatus, amount, identify}`，实测三态——未开始 `status=6 / workStatus=1 /
amount=null`、出水中 `status=1 / workStatus=2 / amount=null`、设备回报结束 `status=5 /
workStatus=1 / amount=275`。**`status==5` 是判「设备回报过结束」的唯一依据**
（`isSessionFinished`）；`workStatus` 只说明服务端会话开着，设备断电时它也是 2。
`amount` 是设备侧使用量，> 0 表示确实出过水，单位未知（实测那单 275），流程里还没用。
debug 包把只读与开水链路的响应原文落到 `filesDir/qiekj_debug/`（机制与取文件命令见
`.agents/rules/life-power.md`「胖乖」节）。

**会话关闭是第四种状态（2026-10-09 实测）**：设备侧点「结算」后平台关闭会话，`sync` 回
`{"code":0,"msg":"成功","data":null}`——HTTP 200、业务成功、无数据；可能先出这一条、隔一拍
再出 `status=5` 的收尾样本，也可能交替出现。**无 data 不是错误**：轮询要按「会话已结束」
收尾，用最后已知单号继续 `order/afterPay/creating → order/detail` 读账单；把空 data 当
致命错误，就是「未知错误（code: -1）+ 账单读不到」的来源。结算两步的响应也已进 debug
落盘白名单。

**设备在线检测已删（2026-10-16）**：`trade/check_device_status` 的 `health` 实测恒为 true
（2026-10-15/16 共 11 份样本，含「人不在寝室」时段），官方只当故障闸用、判不了在线；
App 侧在线显示与端点调用整块移除。旧结论仍成立：同一台饮水机「通电」与「拔电」两态下
`sync` 都回 `status=1 / workStatus=2`、`latestUsed.workStatus` 都是 10、
`details.deviceErrorCode` 都是 null（字段描述的是服务端会话，不是设备），门店设备清单
（`appointNew/near/newMachines`）在旧协议下回 `code=-1`，会话状态还会黏住（断电那笔
15 分钟后仍回 `status=1`）。可用的判据仍是「结束态」：`status=5` 且 `amount>0` 才算真的出过水。

`goods/endWater { goodsId }` 远程关阀的真实口径（2026-10-16 样本）：**只有
`goods/normal/details.supportAppUserFinish=true` 的机型可用**；本机该字段 false，服务端
对结束请求回 `4072「结束失败，请在设备端操作结束」`。App 不再把它当失效路径报错：不支持 /
被拒都提示到设备上点击「结束」并继续等账单。细节见 `.agents/rules/life-power.md`。

**充值不在 REST 层**：胖乖没有充值端点，实为支付宝小程序深链（appId `2018072460764274`）。
剥掉分享短链的埋点参数（chInfo/shareTimestamp 等）钉住业务参数；`page` 值要**整体百分号
编码**——`?`/`&` 不编码会被当成外层 query 截断。manifest 声明 `alipays` scheme 的
`<queries>`（Android 11+ 包可见性，不声明直接 ActivityNotFoundException）。

---

## 3. 趣智校园（热水）：蓝牙帧协议 + .so 反汇编签名

**最硬核的一个**。与胖乖的本质差别：多数学校的趣智设备是**蓝牙水控型**——服务端只签发
一份加密的开阀数据（`downData`），真正让设备出水的是客户端通过 BLE 把它写进去。
两个公开第三方客户端（linyu、quzhi-lite）只实现了联网型端点，这正解释了「扫描能认到设备、
点开阀没反应」。

### 3.1 帧格式与命令码

```
包头 0x60 | 长度(2B 大端 = 数据体长+3) | 0x80 | 功能码 | 0x00 | 数据体 | 校验和 | 0x16
校验和 = (数据体各字节之和 + 0x80 + 功能码 + 0x00) & 0xFF
外包装：`#` + 帧的小写十六进制 + `\n`，整串按 ASCII 字节写入
```

| 功能码 | 作用 |
|---|---|
| 0x23 | 查询设备（响应带 projectId/deviceId/accountId/snCode/随机数/状态） |
| 0x21 | 下发费率数据（downData）= 开阀 |
| 0x31/0x32 | 开始/结束交易 |
| 0x85/0x86 | 采集/清除消费数据 |
| 0x22 | 结束费率（停阀） |

**成败只能看数据体首字节**（`0x80` 成功，否则第 2 字节是错误码）——别只看回读状态：
设备明明回了错误码，代码只看「回读状态没变」，就会把「拒收」和「收下了但没清」当成同一件事。
错误码表来自官方头文件 `KRWMDefine.h`。

蓝牙通道是**低功耗 GATT + Microchip/ISSC 透传服务**，不是经典 SPP——走 SPP 会触发系统
配对并报「PIN 码不正确」。写入口 `49535343-8841-…`、通知口 `49535343-1e4d-…`。
Android 的 GATT 同一时刻只允许一个操作在飞，`writeCharacteristic` 排队失败返回 false 且
**不报错**（刚连上时最容易撞上，现象「连上后第一条命令超时」）——重试几次、间隔几十毫秒。

### 3.2 两段式开阀与签名

```
GATT 连接 → 发 0x23 查询设备（状态须为 0 空闲，拿 randomNumber）
→ POST order/downRate/bluetooth/rateOrder（带 signature）拿 downData
→ 发 0x21 把 downData 写进设备 → 出水
用完 → 0x22 停阀 → 轮询状态到 3 → 0x85 读消费数据
→ POST order/upload/bluetooth/data 结算 → 0x86 清设备记录
```

**签名从 `libklcxkjencry.so` 反汇编得到**（官方 APK 加固过，DEX 里搜不到 getSign）：
`Java_com_klcxkj_jni_JniUtils_signParams` 的逻辑是

```
signature = md5( md5(字段按键名字典序拼接 + "&key=" + loginCode) + "&key=sign-kailu-855c74a88b8b494187c99b08b8c9a744" )
```

下单只签 `telephone`（或 `telPhone`）、`deviceId`、`xfModel`、`randomNumber` 四项——
`randomNumber` 来自蓝牙实时读取，**这是纯 HTTP 方案必然失败的原因**。同一条 so 的
`.rodata` 里还藏着 `clData` 解密的 AES 密钥（`20210118klcx@002`）与 IV（`1234567876543210`）。

### 3.3 clData：AES 解密出「清除参数」

结算响应里的 `clData` 用 `JniUtils.decryptByAES` 解：**AES-128/ECB**，模式按无填充处理
（so 里的 `Data not multiple of BlockSize` 提示），自己再剥 PKCS#7。明文格式
（2026-09-27 真机解开）：

```
1790517647206-2609272200370000011f000010450000a03a00000002
└─ 毫秒时间戳 ─┘└────── 22 字节清除参数 ──────┘
清除参数 = 时间序号(6) + 项目号(4) + 设备号(4) + 账号号(4) + 使用次数(4)
```

把它发进 0x86 设备立刻回空闲。且**可以从消费记录直接切片算出**（记录 `[1..18]`+`[20..23]`），
不依赖服务端——「记录早结算过、上传被拒」的场景也能清。

消费记录的字段偏移有两份独立来源交叉验证（官方 iOS SDK 反汇编 + 看雪反编译完全一致）：
`[1..6]` 时间序号 yyMMddHHmmss、`[7..10]` 项目号、`[11..14]` 设备号、`[15..18]` 账号、
`[19]` 账户类别、`[20..23]` 使用次数、`[24..27]` 预扣金额、`[28..31]` 本次消费、
`[32..35]` 费率、`[36..41]` MAC。

### 3.4 真机踩到的四个坑（文档查不到的）

1. **MAC 首字节不一致**：Android 上报的蓝牙地址首字节是 `C0`，设备在服务端登记的是 `00`。
   下单必须用**服务端登记值**（`device/info/mac`），用蓝牙地址会让订单挂到「另一台设备」，
   结算回「未找到订单」。
2. **结束用水必须先停阀**：直接读消费数据，设备在出水/空闲态没有记录可给，0x85 不回包
   （表现成「等待回包超时」）。停阀后要轮询状态到 3 再采集。
3. **状态 3（消费完成待采集）只收采集与清除**：这时发 0x22 停阀会被回错误码 1——
   这个码在这里实际是「当前状态不接受这条命令」。「结束用水」在状态 3 要**跳过停阀**直接采集。
4. **状态 6 = 「结算中」，官方头文件里没有**：停阀后设备先落状态 6，实测五秒以上才变 3。
   轮询预算只给五秒的话，症状是「第一次结束失败，再点一次就好了」。

**请求预算（风控）**：一条流程只下一次单/只上报一次；设备信息查询串行化 + 跨扫描记住已查
地址；不自动重试 HTTP；同一时刻只跑一条流程；空闲 60 秒主动断开蓝牙（BLE 外设被连着就
停止广播，占着会让这台设备在别人的扫描里消失）。**不伪造 User-Agent、不对齐 TLS 指纹**——
那是第三方客户端的固有特征，改不掉也不该去装。

---

## 4. 快趣出行（共享单车）：无签名 API + GCJ-02 地图

协议来源：微信小程序解包（`api.kvcoogo.com/ManagerApi/api` **无请求签名**，token 是唯一凭证）；
另有两个开源参考仓库（接口契约层面，AGPL 项目只读参考不复用代码）。

**登录**：`POST /v1.0.0/userLoginByPassword {mobile, password: MD5(密码)}`（小写 hex）；
响应 `resultCode==1 && errorCode==0` 为成功；之后 `client-type: 1` + `token` 头调全部接口。

**字段类型不稳定——宽容解析**：数值可能给字符串（`"3665"`）或带小数点的 number（`85.0`）、
布尔给 `0/1`。首版用严格 `@Serializable` DTO，真实订单直接解析崩溃；改成逐字段按
`JsonPrimitive.content` 宽容取值后稳定。**后端字段类型不稳定的接口，别用严格 DTO 硬解**。

核心端点（均为 POST form）：

| 端点 | 鉴权 | 作用 |
|------|------|------|
| `v1.0.0/queryNearbyCar {lat,lng,deviceType=1}` | **无**（token 不校验） | 附近车辆，≤20 辆按距离升序，坐标 GCJ-02 |
| `v1.0.0/queryZoneList {lat,lng,carNum,page,rows}` | 实测可免 | 还车点 `givecarList[]` / 禁停区 `nogoZoneList[]`（`scopeArray` 多边形）；**按 carNum 所属校区出点，rows=15 封顶是「离查询点最近的 15 个」**——集合极其局部，缓存覆盖半径只能给 50 米 |
| `v1.0.0/queryUnderwayOrder {}` | token | 骑行中订单：`totalDate` 已骑**秒**、`payMoney` 当前费用**分**、`lockStatus` 1=锁上（不等于订单结束）、`currentPercent` 电量 |
| `v1.0.0/createOrder {carNum,lat,lng,...}` | token | 开锁第一跳（超时**绝不重发**，防重复订单） |
| `v1.0.0/greenCarUnlock/lock`、`endTheOrder` | token | 开锁 / 临时锁车 / 还车；开锁/还车写成功即返回（2026-10-18），只有临时锁车保留 8 次 × 1.5s 有界确认 |
| `v1.0.0/queryUnPayOrder {}` | token | `12004`=已结清；有欠费给官方出路，**支付本身不做** |
| `v2.0.0/queryOneCar {carNum,lat,lng}` | 可免 | 按车号点名查车（「按车号定位」） |
| `v2.0.0/getUserInfo` / `queryUserCoupon` | token | 余额（分）/ 卡券，只读展示 |

**写操作的责任闸**（开锁起计费、还车失败继续计费、有调度费——App 必须自己兜住责任）：
① 零自动重试（超时只读刷新判状态，「重试开锁」是安全特例且由用户手动点）；② 写操作不先查
在案订单（服务端 12022 / 12003 兜底，调用方用当前快照判断在骑；重试开锁的车号也由控制器
从当前卡片带入，不再预查）；③ 写操作二次确认（临时锁车/解锁继续骑免确认
——无新增计费后果）；④ 降级矩阵：调度费、出围栏、免押授权一律不代用户接受，提示到官方渠道。

**11035 支付分授权是硬约束**：需要微信支付分授权的账号每单命中，`wx.openBusinessView`
是微信客户端专属 API，第三方 App 无法代调；出路只有客服开通免授权 / 缴诚信金 / 回微信扫一扫。
**伪造授权属绕过付费红线，不做**。

**坐标基准**：高德瓦片与车辆坐标都是 GCJ-02，**两者之间直接用不转换**；手机定位的 WGS84
是链路上唯一需要转换的地方（单向 WGS84→GCJ-02，漏了偏 500 米，转两次同样偏 500 米）。
「只看本校」围栏用 PNPOLY 射线法 + 校区名白名单双条件互为兜底；**围栏顶点按路网中心线
从瓦片提取，别凭截图目测配准**（实测会整体偏 30–70 米）。

---

## 5. U净 洗衣房：无签名 JWT + 官方支付宝 SDK

美的「U净」平台（无锡小净共享网络），官方无开放 API。协议事实来自**社区多份独立逆向
成果交叉核对**（GPL 项目只取协议事实、不复制代码）；端点自 2024 年未变。

- 网关 `https://phoenix.ujing.online/api/v1`，壳 `{code, message, data}`（`code==0` 成功，
  `401/-99` 会话过期）。**无客户端签名**，只有 header 指纹：取码/登录组 `x-app-code: ZI`、
  业务组 `BI` + `weex-version: 1.1.30`（服务端对 code 宽容，ZA/BA 亦可用）。
- 登录：`captcha` → `login`（手机号 + 短信验证码）拿 JWT。**高频登录会被服务端限制数十分钟**。
- **实测：匿名 `scanWasherCode` 回 401 "JWT token is missing"**——只读也必须登录，无免登路径。
- 核心链（POST/GET，二维码**原文直传**）：

```
stores/near（经度拼写是 lont 不是 lng！）→ devices/reserve?storeId=（空闲看板）
→ devices/scanWasherCode → app/washer/devices/program/info?deviceId=
   （套餐/水温开关 isWashTemperatureEnable/强制投放声明 isForceDetergent·Disinfectant 全由服务端动态下发）
→ orders/create（body 基础五字段：type/deviceTypeId/deviceId/deviceWashModelId/storeId；
   可选字段按「服务端声明需要才发」：washTemperatureId（1常温/2/3/4=水温）、
   wp_detergentGearId/wp_disinfectantGearId（投放加购，标准档 1/4）、dryTime（烘干机 = 模式时长/10））
→ payment/arguments?channel=alipay → payInfo.orderInfo 交官方支付宝 SDK
→ orders/{id}/detail?additional=price（status 是字符串；remainTime 秒）
→ app/payment/{id}/lastPayStatus（支付结果双查）→ orders/{id}/cancel → orders/{id}/control/start
```

**支付宝接入**：`com.alipay.sdk:alipaysdk-android`（Maven Central），`PayTask.payV2(orderInfo)`，
orderInfo 全部由服务端签发，App 不经手资金。返回四态：**9000 成功 / 6001 取消 /
8000 = 确认中（钱可能已扣，必须用服务端 payFlag 对表后定文案，绝不按失败报诱导重付）**/ 其余失败。

**写操作纪律**：下单/取消/启动零自动重试 + 二次确认；**启动前必须先查订单状态**（服务端对
已运行订单返回 `{}` 静默拒绝）；已有在案订单不许叠单；订单轮询限「页面可见 + 15s」，
不做后台轮询。**订单号必须跨进程存活**（落盘）——重启丢 orderId 服务端就无从签发指令。

---

## 6. 校园卡（新中新「慧新e校」）：安全键盘复现

移动平台 `https://yktwx.juwp.edu.cn/plat/`（前端 Vue2+Vant），学号登录可完全用 HTTP 复现：

**「安全键盘」本质是一次字符替换**：服务端每次下发 10 张数字字形图（base64 PNG，跨会话
同一组、顺序随机）与等长乱序字符集（`numberKeyboard`），点第 i 个键发第 i 个字符；
提交时拼 `密文$1$uuid`，服务端凭 uuid 还原。传输层无任何密码学保护——因此复现合法可行。

```
GET  /berserker-secure/keyboard?type=Number&order=1     取 uuid + 乱序字符集 + 10 张字形图
POST /berserker-auth/oauth/token                        OAuth2 password 模式
     （Authorization: Basic mobile_service_platform:mobile_service_platform_secret，前端公开常量）
GET  /berserker-app/ykt/tsm/codebarPayinfo              付款账户列表（取 code=CARD）
GET  /berserker-app/ykt/tsm/batchGetBarCodeGet?...      一批 10 个 20 位付款码（expires 17280s/个）
GET  /berserker-app/ykt/tsm/queryCard                   卡列表+余额（分）；余额口径 = (db_balance + unsettle_amount)/100
GET  /berserker-search/search/personal/turnover?size&current   消费流水（分页，不带时间参数）
鉴权头  synjones-auth: bearer <token>；响应可能带 UTF-8 BOM，解析前剥离
```

- **字形识别**：对每张字形图做「alpha 合白底 → 裁包围盒 → 归一 → 与标准字形 IoU 匹配」，
  生成 **MD5 全量表 → 数字**（全量 32 位表零成本消除 8 位前缀的碰撞歧义）；未知哈希直接
  报协议漂移，宁报错不猜。
- **协议自检零成本**：服务端响应自带 `password` 字段 = `numberKeyboard + "$1$" + uuid`，
  每次登录顺带校验，不一致 = 平台改版信号。
- 错误码：HTTP 400 = 凭证错（**与密文构造错误同貌**——先怀疑构造）；`8001` 学号绑多账号；
  **`8002/8003` = 触发图形验证码，绝不重试**，引导浏览器登录一次；401 = token 失效重登一次。
- **时间参数实测矩阵全无效**（`timeFrom/timeTo`、`dateFrom/dateTo`、`jnDate`…单独传被忽略、
  组合传直接清零）——月过滤只能在客户端做：无参全量倒序拉 + 本地按月切分求和。
  数据库本地化后增量同步（时间倒序翻页，**遇到已入库 orderId 即停**），通常一页内完成。
- **充值**：下单是 **HTML 表单同步提交**（非 JSON API）`POST /charge/order/thirdOrder`，
  成功 = **302 Location 下发收银台 URL**（OkHttp 必须关自动重定向才能拿到）。独立 SIGN
  算法：参数按 key 字典序、跳过空值（0 保留）、拼 `k=v&` 末尾拼 SECRET_KEY，SHA256 hex
  **大写**（appid/SECRET_KEY 都硬编码在公开前端 JS，属公开常量）。支付走微信 H5 渠道
  （checkmweb → `weixin://wap/pay?...`，**Referer 必须商户域名**）。**`queryCard` 的
  `account` 是 6 位卡号不是学号**——传错就是「未找到要充值的卡账户」。

---

## 7. 寝室电费（新开普）：读表纪律与收银台 UA 陷阱

新开普「移动服务平台」缴费（`charge.juwp.edu.cn`，前端 BladeX + Vue），**与教务无关**。

- **登录**：`POST /blade-auth/oauth/token`（在根域，不在 `/charge` 下），Basic
  `charge:charge_secret`（前端公开常量），`logintype=student-sno-queryPassword`——
  **查询密码与教务/统一认证密码不通用**（实测教务密码回 `{"error":"unauthorized"}`）。
  token 3599 秒。除登录外每个请求带 `Authorization` + `synjones-auth: bearer <token>` 两头。
- **读表** `POST /charge/feeitem/getThirdData`：`type=IEC&level=3` + 场景三键（campus/building/room），
  **参数少一个就回 `code=500「未知异常，请联系管理员」`**——别把它当平台故障。
  读数在 `map.showData` 的**中文键**里（`{"当前剩余电量":"55.57"}`）。平台没有用电量接口，
  只能自己按时间记读数差分。
- **充值两渠道**（下单后渠道不可换）：
  - 电子账户：`blade-pay` 继续 paystep，6 位消费密码（食堂 POS 密码，不是登录密码）；
  - **农行支付**：`paystep=2 + NEWPAYMENTPLATFORM:4` 换手机版收银台链接，**按请求 UA 分三支**——
    微信 UA（含 `MicroMessenger`）→ 手机版收银台；桌面 UA → 网页版；
    **手机浏览器 UA → 空 body + `code=6230`，msg 却写「处理成功」**（假成功！别当成解析错误）。
    另外：**手机版链接不能拿网页版那条的 TOKEN 自己拼**（回 `90015 无可用的支付方式`）；
    收银台 URL 只在返回 HTML 表单的 `action` 里（值首尾带空格要 trim）。内嵌页**只当容器**：
    不注入脚本、不读输入，手机号/验证码/密码只进农行页面。
- **充值下单必须带 `third_party`**（读表 `map.data` 原文 JSON）：缺了平台照样收钱但**永不
  入账**（只能人工找管理员）——所以下单前强制新鲜读表、缺读数拒单，扣款与入账分开报告。
- 未支付订单平台不自动清，堆积会导致 paystep=0 回 500：先查 `paystatus=0` 列出再逐单
  `deleteOrder`（JSON body）清理。

---

## 8. 可迁移的工程经验（跨系统总结）

1. **逆向方法论**：先解包/抓包拿端点表 → 写最小探针脚本对自己账号实测 → 用 fixture
   （脱敏后）钉住解析 → App 实现 + JVM 单测复用同一份 fixture。**先探明「不存在的路径」**
   （U净匿名 401、快趣死导出 `carLockFlag`、电费无用电量接口）与探明端点同样重要。
2. **风控友好**：渐进轮询（事件驱动的间隔衰减，别固定 5 秒拉长窗口）、请求间随机延迟
   （200–600ms，是放慢不是重试）、**失败一律不自动重试**、验证码类响应绝不重试
   （重试会把「可恢复」打成「锁号」）、写操作零自动重试（超时只读刷新判状态）。
3. **宽容解析**：第三方后端字段类型不稳定（字符串/数字/布尔混给）——逐字段按原始值
   宽容取值 + 未知字段忽略；坏数据丢点不丢层（图层解析失败回空，不整页崩）。
4. **凭证分级**：EncryptedSharedPreferences + `backup_rules.xml`/`data_extraction_rules.xml`
   排除备份；token「仅内存」还是「落盘复用」看寿命（60 天的落盘省一次登录，短命的存密码
   静默重登）；无日志拦截器，密码/token/付款码不进 Logcat。
5. **会话失效判定要显式排除「签名错误」类文案**——签名没过不该把人踢去重新登录。
6. **深链支付模式**：需要支付的一律把用户交给官方容器（支付宝小程序深链 / 官方 SDK /
   微信 H5），App 不拼支付参数、不经手资金、不碰支付密码——既是合规红线也省掉
   整个支付协议的维护成本。Android 11+ 记得 `<queries>` 声明目标 scheme。
7. **R8 与反射序列化**：只被泛型签名引用的模型类会被 R8 整类删除（Retrofit/序列化走
   运行期反射），现象是「某个功能坏了」而非「混淆炸了」——API 模型包整包 keep。
   改混淆规则后必须装 release 包冒到真实网络路径。

---

## 9. 换一所学校：适配指南

工作量集中在**数据获取层**；UI、存储、小组件全部原样复用。

1. **认清教务系统**：URL `/jsxsd/`+`.do` → 强智（本文 §1 照搬）；`xskbcx.aspx` → 正方；
   青果/金智/URP 各有一套页面族。看清是服务端直出 HTML（逆向 DOM）还是 JSON 接口（抓包），
   多数系统两者混用。
2. **存快照、跑通 Python 链路**：登录后把目标页「另存为」完整 HTML 放 `scripts/out/`
   当 fixture；仿 `jw_session.py` 写登录链路；仿 `fetch_courses.py` 写解析——先跑通
   「能拿到课程名列表」，再补星期/周次/节次。**排错对照快照与 `*_raw.json` 中间产物，
   不要凭想象猜 DOM**。
3. **App 端替换常量与解析器**：`data/jw/` 的 URL 常量（`JwUrls`）与判型规则、各解析器的
   `EXTRACT_JS` 注入脚本、考试/成绩解析器的接口参数、`DefaultData.kt` 的作息表（从教务页
   行标签逐节核对，不要拍脑袋）。解析器契约保持 `{ok, items, term}` 不变，下游零改动。
4. **校园生活模块按需取舍**：`data/qiekj|qzxy|ykt|power|kqcx|ujing/` 每个系统独立成包，
   整块删除不影响课表链路；要保留哪个就换哪家的协议。
5. **换品牌与单测锁死**：`applicationId`、显示名、免责声明换掉；**不要保留原学校的教务
   地址**。把 HTML 快照裁剪脱敏后放进 `app/src/test/` 当 fixture 写断言——教务改版时
   重抓快照跑测试就知道哪里挂了。**对着过期结构改代码是教务解析的头号事故来源。**

---

## 10. 构建环境与已知坑（速查）

| 项 | 现值 / 坑 |
|----|-----------|
| Gradle / AGP / Kotlin | Wrapper 8.11.1 / AGP 8.9.1 / Kotlin 2.1.21；compileSdk 36 / targetSdk 36，AndroidX core 1.17.0 |
| Room | **2.7.1**——2.6 配 Kotlin 2.1 会 KSP `unexpected jvm signature V`；**禁 destructive migration**；实体 `@Index` 必须与迁移 `CREATE INDEX` 逐字对齐；实体带默认值的列迁移建表必须写 `DEFAULT` |
| HugeIcons | `com.github.rikkahub:hugeicons-compose`（JitPack，**必须 `isTransitive = false`**，只用它的图标 AAR） |
| osmdroid | 6.1.18（Maven Central，POM 无 `<dependencies>`）；加依赖后第一次构建要联网 resolve |
| WebView | MIUI 硬件合成层会丢 → 软件渲染兜底（否则「功能正常但整面白屏」）；教务页无 viewport meta → `useWideViewPort` |
| debug/release | 两个 applicationId（`.debug` 后缀）可共存；启动 debug 包必须写全限定 Activity 名 |
| 离线构建 | 依赖齐备后 `--offline` 秒级：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline` |

---

## 11. 安全与合规红线

- 凭证只进 gitignore 的本地文件；**App 端代码里永远不出现学号/密码/token**；导入默认由用户
  在 WebView 亲手完成（自动同步的凭证经 EncryptedSharedPreferences 加密、排除云备份）；
- 公开仓库的文档、注释、示例、测试 fixture 不出现真实学号、姓名、手机号、卡号、房号；
  抓取产物（含真实数据）不入库；
- 付款码等同现金：`FLAG_SECURE` 防截屏、不进日志/剪贴板、凭证清除即全链路关闭；
  只调只读接口 + 用户主动触发的充值，挂失/转账/改限额一律不做；
- 模拟登录/抓取以「正常客户端」为限：不刷积分、不绕过付费、不伪造官方身份、
  不对接口做高频请求；写操作（选课、退课、开锁、下单）只由用户显式触发、零自动重试；
- 对外发布你的改编版时，同样写明非官方声明，使用风险自负。

---

## 参考

- [scripts/README.md](scripts/README.md) —— 爬虫速查：登录链路细节、DOM 规则、盖章成绩单、排错表
- [DESIGN.md](DESIGN.md) —— 产品与界面规格 + 逐模块技术决策（§4.17 调课、§4.19 一卡通、
  §4.23 快趣地图、§4.24 电费、§4.27 统一会话、§4.30 趣智、§4.32 快趣用车、§4.35 选课、§4.37 U净）。
  **该文件现在只是索引**，正文在 [`design/`](design/) 下 9 个文件里；`§x.y` 章节号没变，
  用索引里的「章节 → 文件」表定位
- [AGENTS.md](AGENTS.md) + [`.agents/rules/`](.agents/rules/) —— AI 结对约定与领域口径清单
- 参考仓库：[拾光课程表](https://github.com/XingHeYuZhuan/shiguangschedule)（JSON 互通格式）、
  [light-life](https://github.com/Inonvation/light-life)（胖乖）、
  [kqcx-bikemap](https://github.com/dlkz/kqcx-bikemap)（快趣接口，AGPL 只读）、
  [linyu](https://github.com/yehu-imei/linyu) / [quzhi-lite](https://github.com/wzk-chi/quzhi-lite)（趣智联网型）
