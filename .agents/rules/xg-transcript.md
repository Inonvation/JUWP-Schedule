# 学工表单 · 盖章成绩单

作用域：改学工 WebView 表单（宿舍报修 / 请假）、成绩单导出与「最近导出」时读。规格见 DESIGN §3.15 / §4.25 / §4.26。

## 盖章成绩单走签章管理系统

- **导出盖章成绩单走的是签章管理系统，不是强智教务**（DESIGN §4.25）：
  CAS service = `http://jwxyxx.juwp.edu.cn/ptwork/cas`，落地拿 `sid`，再
  `POST /ptwork/DzqzController/ddqzcjList` 取 `pagePri`、`POST …/printStartCj` 回传它拿 PDF。
  四条不许按直觉改的口径：**`dysj`（pagePri）是权威条件、`xnxq` 被服务端忽略**；
  列表分页不影响出单（token 编码查询条件而非当页数据）；`limit` 无效、固定 15 行一页；
  **令牌存在不等于有数据**——无成绩学期照样给 token，解不开时服务端返回**空白却带章**的模板 PDF，
  所以出单前必须用 `total>0` 当闸门，响应必须过 `%PDF` 魔数。
  会话只从 WebView 的 `CookieManager` 现取（不存账号密码，与「不要写死密码」同一条纪律）；
  学期清单从接口取，**不要解析页面下拉**——「后台首页」那份模板的下拉最高只到 2022-2023-2
  且 37 个旧学期重复，是坏的。成绩单 PDF 的章是**注释 + 数字签名**（`/FT /Sig`，Rect 压在
  「学校盖章：」上），PDFium 系渲染器（含 pypdfium2）不画注释，用它截图自检会误判「没盖章」。
  签章系统只有 HTTP 明文，成绩单与 CAS 票据都明文回传，这句实话要留在导出页上。
  「最近导出」页（`SubpageScreen.TRANSCRIPTS`）的列表**就是 `filesDir/transcripts/` 目录本身**，
  不要给它加 Room 表或清单文件：文件名里已经带学期标签与导出时刻，两套真相迟早对不上。
  判定顺序是「先 `.part` 再 `.pdf`」（`x.pdf.part` 同时满足两个后缀），否则崩溃留下的半成品
  会被当成一条记录列出来；打开/分享前必须用 `TranscriptStore.existingFile` 复核（新导出会触发
  保留策略删旧的，列表那一屏可能已经过期）。

## 学工表单是 WebView

- **学工表单是 WebView，不是原生表单**（2026-09-24，DESIGN §3.15 / §4.26）：
  `XgFormActivity` 承载全部学工表单（已登记：宿舍报修、请假），表单标识走 extra、
  清单在 `XgUrls.FORMS`，加一个新的只需在清单里补一条 + 在扩展服务页的图标映射里补一条。
  它是第三个「因为窗口里有统一认证表单而锁竖屏」的窗口（前两个是教务导入与成绩单导出），
  进页打开 `XgUrls.SSO_LOGIN`（`/sfrz/login343962`）→ 302 到 `eapp2` 的 CAS。
  学工与教务**共用同一套统一身份认证**，所以教务导入登录过一次这边就免登。学工自己的
  token **App 不存**（它在 localStorage 里，取不出来也用不上）；两者共用的那一层 CAS 凭证
  由 `data/session/CredentialVault` 管，口径见 DESIGN §4.27 与下面「登录态只有一条口径」。
  **别换成**超星的 `/passport/mlogin`（手机号 + 学习通密码，是另一套账号，学校没配它）；
  **也别去复刻** `/office/...` 的表单提交：请求里那批 `pageEnc` / `traceId` / `nodeUniqueId`
  由服务端每次下发，复刻出来的实现必然随官方改版失效。附件上传靠
  `WebChromeClient.onShowFileChooser`（漏了它 = 点上传没反应），状态条按域分档的判据
  与直达闸门唯一实现在 `data/xg/XgUrls.kt`。直达的顺序是硬约束：**先 `/sfrz/`，
  落到学工域后再进表单页**——表单页自己匿名可访问，先开它会把人引到超星 passport
  那套账号上；而闸门必须排除 `/sfrz/`（它也在学工域），否则 CAS 回跳的一刻被截断。
  直达地址**不带 `uuid`**：那是前端提交时现场生成的随机值，URL 上那个没有读取点。
