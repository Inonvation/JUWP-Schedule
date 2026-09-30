# 统一认证 · 凭证 · 会话 · 首启引导

作用域：改登录、凭证存储、会话续期、WebView 自动填表、首启引导、账户状态卡、学籍卡补抓时读。口径见 DESIGN §4.27 / §3.16。
学工表单也共用这套统一身份认证，门户细节见 `xg-transcript.md`。

## 登录态只有一条口径

- **登录态只有一条口径**（2026-09-24，DESIGN §4.27）：`data/session/` 三个件——
  `CredentialVault`（两份凭证加密存储的唯一读写口，文件仍是 `jw_credentials.xml` /
  `ykt_credentials.xml`，备份排除规则已在）、`CasSession`（CAS 会话唯一持有者，教务 / 学工 /
  签章共用，含 `ensureValid` / `tryLogin` / cookie 回灌）、`SessionStatus`（运行时停用状态）。
  四条别改坏：① **闸门只有「凭证错」计数**，验证码 / 网络 / 5xx 一律不计——旧实现把
  「CAS 无 Location」一律当凭证错，会把对的密码记成错的、累计还把账号停用，所以
  `CasLoginClassifier` 必须四分类（详情页 / 认不出的 200 走 `Manual`，转 WebView）；
  ② `ensureValid` 有 10 分钟可信期、5 分钟重复窗口、`Mutex` 串行化，**别为了「实时」去掉**
  （并发登录正是风控最敏感的形状）。但两条判据必须带上「会话还在不在」：**可信期要同时
  要求 `http.hasCookies()`**——`last_success` 落盘、cookie 不落盘，进程重启后时间戳还在而
  jar 已空，只看时间戳会把「空会话」当「可用会话」；**`canAttempt` 里
  `lastAttemptMs <= lastSuccessMs` 要直接放行**——那是成功留下的时间戳不是失败，否则
  冷启动后 5 分钟内既不信会话也不允许重登（2026-09-24 真机：引导登录 4.6 分钟后冷启动，
  `last_attempt` 还停在成功那一刻，用户看到「明明登录了还要手动登」）；
  ③ **不做 cookie 注入，回灌是唯一方向**（2026-09-24 定案，注入侧代码已删）。真机实测
  把 OkHttp 的 cookie 写进 `CookieManager`（读回 7/7 落位、`flush()` 已调）后，85ms 后
  WebView 首跳仍落 CAS 登录页——差别是属性缺 `SameSite=None`，跨站跳转不带。所以
  **WebView 的会话只能由 WebView 自己登出来**（`JwAutoLogin` 填表提交），
  `WebViewCookieBridge` 只剩 `adopt` / `hasAnyCookie`，别再往回加注入。
  WebView 用出来的新会话由「落到教务域且非登录页」时 `adoptFromWebView` 抄回 jar。
  **另外 `loadUrl` 绝不能被等会话的动作挡住**：登录 / 探会话要联网，三个 WebView 入口
  一律「**先 `loadUrl`** 让页面出来，再后台 `ensureValid` 拿结果写状态条」。把 `loadUrl`
  排在等会话之后会让 WebView 白屏几十秒——日志里连一条 `onPageStarted` 都没有
  （2026-09-24 用户报的「还是要手动登」就是这个）。
  ④ 两份凭证互不牵连：清一卡通不动教务，状态卡的「未开启凭证」按 ykt 判定、导入提示按 cas 判定。
  **`CredentialVault` 对两份凭证各留一份内存缓存**（`@Volatile` + 已读标记，`save*` / `clear*`
  同步更新）：`EncryptedSharedPreferences` 的读要走 Keystore 解密，而三个 WebView 入口 +
  「我的」页账户卡各读一次，都在主线程。别再让页面自己 `remember { vault.readCas() }` 之外
  另开一条读盘路径。
  `YktCredentialStore` 现在只是 `CredentialVault` 的薄适配器，**公开签名不要动**（8 个调用点，
  改内部就够——构造点只有 `Graph` 一处）。

## 网页会话也算登录态（2026-09-27 修「我的」页一直显示未登录）

用户报：首启引导跳过教务，后来在导入课表页手登过一次，之后「我的」页账户卡一直写
「未登录」，而同一张卡上「教务」格已是「● 已登录」，点进教务账户页标题也还是「未登录」。
根因是身份区与状态格各判各的。五条口径，改这块时逐条对：

- **身份区标题不许写死「未登录」**：姓名 → 学号 → 按三格合成的一档给词。合成用
  `LoginStateRules.overall`（已登录 > 已失效 > 未登录），词复用 `ui/common` 的
  `stateWord()`。写死「未登录」时，只有网页会话（没存密码）或只登了胖乖生活的用户会看到
  「未登录」与「● 已登录」并存于同一张卡。`AccountCard`（教务账户页 / 校园卡页）与
  「我的」页的 `AccountBar` 是两处实现，口径必须一起改。
- **学号也从学籍卡来**：`profile_student_id`（`DisplayPrefsStore.profileStudentId`）。
  原先学号只从凭证取，没存密码就永远空着；学籍卡本来就有学号，`ProfileSync` 与
  `JwImportScreen` 的成绩导入链路都顺手落库，身份区就有真值可用。
- **没凭证时 `CasSession.ensureValid` 回灌 WebView 会话**：先抄 `CookieManager`
  （`webCookies.adopt`）再 `verifySession`，**验过才写闸门**。抄回不写 `last_success`
  是有意的：没验过的 cookie 一旦记成成功，10 分钟信任期就会把一份过期会话当可用，
  所有 OkHttp 取数都拿到登录页。`http.hasCookies()` 时不再抄，避免每次调用探两遍。
  这条让学籍卡 / 成绩 / 学业完成情况对「只在网页里手登过」的用户同样可用。
- **`ProfileSync` 的「今天试过」只在真试过时落**：`ensureValid` 返回 `NoCredential`
  （没密码、网页里也没有可用会话）时直接返回、不写日期。否则用户当天再去导入页手登，
  这一整天都不会补抓，卡片就停在「未登录」。
- **会话探针必须认得出「就地渲染的登录页」**：未登录时教务把登录页渲染在 `xsMainV.htmlx`
  的 200 应答里（约 79KB、title「登录」、表单 `action="/jsxsd/xk/LoginToXk"`），
  「≥20KB 且不含『用户没有登录』」这条老判据会把它判成会话有效。
  `JwHttpSession.looksLikeLoginPage` 加了 `LoginToXk` / `userPassword` 两个表单特征，
  `verifySession` / `fetchHtml` / `fetchPage` 三处共用，**别再退回只看文案与字节数**
  （`用户没有登录` 按 DESIGN §4.17 实测在真实页面上出现 0 次）。配套：`ProfileSync` 的
  「今天试过」只在**真拿到页面**之后落，抓不到（没会话 / 网络 / 退回登录页）下次进页再试。
- **`ADOPT_URLS` 的路径必须落在 cookie 作用域里**：CAS 的 `JSESSIONID` 在 `/cas`、
  `TGC` 在 `/cas/`、教务的 `JSESSIONID` 在 `/jsxsd`，`getCookie` 按路径筛。写成根路径时
  读回来的只有 `bzb_njw` 这类站点标记。`WebViewCookieBridge.hasAnyCookie` 同时收紧成
  **只认会话标识**（`SessionCookieRules`，JVM 可测）：站点标记不是会话，把它算进来
  会报出一个干不了任何事的「已登录」。
- **组合不重建 ⇒ 用 `rememberResumeTick()` 当 key 重读**：Compose 的组合被另一个
  Activity（引导页 / 导入页 / 校园卡设置页）盖住再回来时**不重建**，`remember` 里的凭证与
  cookie 快照原样留着。账户卡读的正是「凭证在不在 / `CookieManager` 有没有会话」，必须挂在
  tick 上；「我的」页的学籍卡补抓 `LaunchedEffect` 也跟 tick 走。校园卡设置页没有窗口切换，
  它的身份跟着 `feedback()` 里重读的那份凭证走。
- **账户卡的格子**（2026-09-27）：教务 / 一卡通 / 胖乖生活 / 趣智校园，后两格跟着
  今日页卡片开关走（`waterCardEnabled` / `qzxyCardEnabled`，关了不留入口）。
  趣智校园那一格的状态订阅 `QzxyRepository.loggedIn`、落点 `SubpageScreen.QZXY`，
  图标是 `ShowerHead`（洗澡开热水，不是 `GlassWater` 水杯）。
  **快趣出行没有账号可登**（出码只要车号、免费时长是本地计时），因此不设这一格。

## 首启引导只服务新安装

- **首启引导只服务新安装**（2026-09-24，DESIGN §3.16）：`onboarding_seen` 为 false 时
  `MainActivity` 拉起 `OnboardingActivity`（六屏：欢迎 → 学校统一认证 → 一卡通·电费 → 胖乖生活 →
  趣智校园 → 完成，**每步可跳过**，跳过只丢那一步的凭据）。老用户没有这个键 ⇒ 不弹引导，只在「我的」页
  看状态。**第 1 步的免责声明进入即自动弹出、5 秒内关不掉**（2026-09-27 用户拍板，
  弹窗走 `DisclaimerDialog(readSeconds = 5)`；用户自己点「查看免责声明」的那次不强制）。
  两份声明各带一枚**「不同意并退出」**（2026-09-30 用户要求）：点了不写同意记录、不写
  `onboarding_seen`，`finishAffinity` 整应用退场——下次启动引导与声明都会再来，进主界面
  的唯一路径是同意。老安装（引导键已写、没赶上声明队列）的确认口在「我的 → 关于」：
  打开用户须知并关掉即写当前版本同意记录，别在主窗口另起一条弹出链。
  第 4 步（胖乖生活）**两种登录方式二选一**：手机号 + 短信验证码（60 秒冷却，
  与胖乖生活页同档），或粘贴已有 Token（`validateToken` 查余额 → 落盘，**先验后存**：校验
  没过什么都不落盘，也就不可能留一个无效 token 让后续请求一路 401；落盘会翻转仓库的
  登录态 `QiekjRepository.loggedIn`，今日页开水卡会跟着切形态，所以顺序不能倒）。
  第 5 步（趣智校园）**三种登录方式**：手机号 + 密码（默认）/ 短信验证码 / 粘贴会话串，
  会话串走 `QzxySessionLink.parse` → `validateSession` → `adoptSession`（同样**先验后存**）。
  **账户卡常显**（不再以「有没有一卡通凭证」为条件），各格状态由
  `LoginStateRules.derive` 合成：凭证在不在是持久事实、停用是运行时事实，**别混成一个布尔**；
  没请求过就是「未登录」，只有真撞上凭证错才转「失效」——**不做后台主动探测**。
  「失效」的上报点都在仓库层：一卡通与电费走各自的 `credentialFailure(...)`（**只有
  凭证错**，token 过期走 401 重登、**不标失效**），教务走 `CasSession` 的闸门。后台任务
  （`BalanceAlertReminder`）调同一批方法，因此自动获得上报、不需要单独埋点。

## 前置登录与补存凭据（2026-09-28，堵「跳过首启」的洞）

- **`vault.saveCas` 曾只有引导页一个调用点**：首启跳过教务 → 之后在导入页 WebView 手登成功
  → 只回灌 cookie、凭据永不落库 → 会话过期后自动填表读不到凭据直接放弃，且「我的」页
  没有任何补存入口。修复为**两层闭环**，共享组件 `ui/common/CasLoginDialog`：
  ① **进窗前置**——需要教务数据的窗口（课表导入 / 报修请假 / 成绩单授权）打开时无凭据
  就先弹一次，输学号密码**先经 `cas.tryLogin` 验证、通过才落库**（坏凭据存进去会让自动
  续登反复撞 CAS 失败计数直至停用）；保存成功 OkHttp 会话即就绪，WebView 落 CAS 页由
  自动填表接管，之后一直自动登录。② **手登成功后补存**（兜底）——跳过前置层的用户在
  网页里登成功后（`checkSessionLost` 通过 + 教务域）再给一次机会。
- **「先跳过 / 暂不」只挡本窗口**（`casLoginDeclined`，进程内标记），不写任何闸门；
  下个窗口还会再问。保存成功立即刷新 `savedCas`（各页已从 `val` 改 `var`）。
- 新 WebView 入口要接同款弹窗时照这个模式；**别在「自动填表失败」路径里弹**
  （那时用户还没登成功，弹了也存不了正确的密码）。
- **前置层保存成功后必须重载入口 URL + 复位 `autoLoginTried`**：WebView 此刻多半停在
  CAS 登录页，不重载用户就得退出重进（实测踩过）。三个入口各自的 URL 见
  DESIGN §4.27（`SSO_WARMUP` / `XgUrls.SSO_LOGIN` / `PtworkTranscript.CAS_ENTRY`）。

## 退出登录两侧同清（2026-09-28）

- `CasSession.logout` 是**挂起函数**：依次清 OkHttp jar → **WebView `CookieManager`
  （`webCookies.clearAll()`）** → 凭据 → 闸门/节流。WebView 那侧不清的话，手登/自动填表
  留下的会话仍有效——导入页照常直进教务、状态卡 `hasAnyCookie` 一直「已登录」，
  退出形同虚设（用户实测踩过）。
- UI 调用点必须放在协程里（`CookieManager` 读写要有 Looper，桥内部切 Main）；
  清完再 `onBack()`，状态卡重读时看到的才是真的「未登录」。
- `CookieManager` 没有按域删除的公开 API，`clearAll` 是整库清——App 内 WebView 只访问
  学校域，不会误伤第三方登录态；别为了「只清学校域」去手写过期 cookie 拼装。

## 登录链路审查结论（2026-09-28，改动前先读）

- **加密 prefs 创建必须带自愈**（`CredentialVault`/`QiekjTokenStore`/`QzxySessionStore` 三处）：
  Keystore 损坏（换机/云备份恢复后常见）时裸 `EncryptedSharedPreferences.create` 会抛异常，
  而读取都在 Compose 组合期 = 启动即循环崩溃。修法：失败删文件重建（一次性丢该份凭证）→
  再失败退明文空 prefs（等同未登录）。**新增加密存储必须带这个兜底**。
- **`logout` 必须在 `loginMutex` 里**：否则与在途登录并发时，登录成功回调会把会话写回 jar、
  闸门记成成功——退出被悄悄撤销。
- **保存凭据成功后必须调 `onCredentialsUpdated()`**（不只刷新 `savedCas`）：自动填表失败一次
  就消耗进程级 5 分钟节流窗口，刚验证保存的正确密码会被它压住。
- **UI 层吞异常别用 `runCatching`/`catch (Exception)`**：会把 CancellationException 一起吞，
  窗口销毁后登录链还在后台跑完。要 rethrow `CancellationException`。
- **`CasLoginDialog` 的失败文案按 `CasEnsureResult` 分类**（对齐引导页 JwStep）：
  Failed 文案透传 / NeedsManualLogin 与 Suspended 各自的下一步动作 / 加 3~5 秒冷却防连点。
  全吞成「密码错」会把开着 VPN 的用户往改密码方向带。
- ykt 的内存 `cachedToken` 在退出时要一起清（`clearTokenCache`）；qiekj/qzxy 无此问题。

## 登录页判据只用 userPassword（2026-09-28，LoginToXk 误伤已修）

- `JwHttpSession.looksLikeLoginPage` 的特征**只有** `userPassword`（登录页密码框的
  id/name，实测 11 处）与历史兜底「用户没有登录」。**`LoginToXk` 不能当特征**——它是
  强智选课入口的通用路径，**已登录主页的「退出登录」隐藏表单 action 就是
  `/jsxsd/xk/LoginToXk?method=exit`**（实测 2 次）。把它当特征会把每个正常主页判成
  登录页：探针永远失败 → 完整登录 → 第 6 步校验又失败，「教务登录没走通」必现，
  自动续登整条瘫痪。回归单测 `loggedInHomeWithLogoutForm_isNotALoginPage` 钉住。
- 判据再改时必须先抓「已登录主页 + 未登录登录页」两份快照对照，不能只看登录页。

## 落在统一认证登录页时自动填表登录

- **落在统一认证登录页时自动填表登录**（2026-09-24，DESIGN §4.27「落登录页自动填表」）：三个 WebView 入口
  ——教务导入（`JwImportScreen`）、学工表单（`XgFormScreen`）、成绩单授权
  （`TranscriptScreen`）——都在 `onPageFinished` 里判「在 CAS 域」，命中就用 `JwAutoLogin`
  填 `username`/`password` 并**点提交按钮**，只试一次。四条别改坏：
  ① **别再回去注入 cookie**：OkHttp 登录拿到的 cookie 注入 `CookieManager` 在真机上不被
  采用（读回验证 7/7 条落位，85ms 后首跳仍然落到 CAS 登录页；差别是属性缺 `SameSite`，
  跨站跳转不带），让 WebView 自己提交才是与用户手点完全一样的路径；
  ② **提交要点按钮**：`form.submit()` 会绕过 `onsubmit` 与按钮上的点击处理（真机反馈
  「能填入但没点登录」），按 `button/input[type=submit]` → `requestSubmit()` → `submit()`
  的顺序退让；
  ③ 值要用**原生 setter + `input`/`change` 事件**写（受控组件直接改 `.value` 框架状态不更新）；
  ④ **两道闸都要留**：页面级 `autoLoginTried`（同一页面不重复提交）+ 进程级
  `SessionStatus.tryAcquireAutoLogin`（5 分钟窗口，**取到许可就算用掉一次，失败也算**）。
  只有页面级那道挡不住重开窗口：密码改过而 App 还存着旧的时候，每开一次导入页 / 报修 /
  成绩单就撞一次 CAS。该平台已判停用（凭证错到阈值）时也一律不放行——密码就是错的，
  再填只是多撞一次失败计数。`onCredentialsUpdated()`（改密码 / 退出登录）会清零这道闸。
  失败（`no-form` / `err:`）退回人工登录——反复试会撞风控。
  「失效」之后怎么恢复：教务行的落点会**分流**——已登录去导入窗口，未登录/已失效直接进
  `OnboardingActivity.start(startAtJw = true)` 改密码（导入页只能手登 WebView，改不了已存
  的密码）；一卡通行去校园卡设置页。开水的 token 失效**本地判不出来**（无实测样本），
  状态卡按「token 在不在」显示，真失效了在开水页报错——这是它的账号体系决定的，不是漏做。
  **「我的」页的姓名 / 班级**（DESIGN §3.3）：姓名来自一卡通 `queryCard` 的持卡人；
  班级来自教务学籍卡 `/jsxsd/grxx/xsxx`，由 `ProfileSync` 在**两处**补抓——引导第 2 步
  登录成功后、以及「我的」页进页且班级为空时。闸门是 `ProfileSyncRules.shouldAttempt`
  （班级为空 **且** 今天没试过），失败**静默**：别把它改成弹错误，也别去掉日期闸门
  （那样抓不到时会每次进页都打一次教务）。
  **首启判据是两个条件的与**：`onboarding_seen` 为 false **且** `isFreshInstall()`
  （`lastUpdateTime - firstInstallTime < 60 秒`）。**别改成只看键**——老用户设备上这个键
  同样不存在，升级后会被凭空弹一段引导；也别用「有没有课表」判（导入过又清空的会被误判）。
