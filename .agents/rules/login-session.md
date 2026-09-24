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

## 首启引导只服务新安装

- **首启引导只服务新安装**（2026-09-24，DESIGN §3.16）：`onboarding_seen` 为 false 时
  `MainActivity` 拉起 `OnboardingActivity`（五屏：欢迎 → 学校统一认证 → 一卡通·电费 → 开水 →
  完成，**每步可跳过**，跳过只丢那一步的凭据）。老用户没有这个键 ⇒ 不弹引导，只在「我的」页
  看状态。第 4 步（胖乖开水）**两种登录方式二选一**：手机号 + 短信验证码（60 秒冷却，
  与开水页同档），或粘贴已有 Token（落盘 → `validateToken` 查余额，失败**清掉刚存的那份**
  再报错，否则会留一个无效 token 让后续请求一路 401）。**账户卡常显**（不再以「有没有一卡通凭证」为条件），三行状态由
  `LoginStateRules.derive` 合成：凭证在不在是持久事实、停用是运行时事实，**别混成一个布尔**；
  没请求过就是「未登录」，只有真撞上凭证错才转「失效」——**不做后台主动探测**。
  「失效」的上报点都在仓库层：一卡通与电费走各自的 `credentialFailure(...)`（**只有
  凭证错**，token 过期走 401 重登、**不标失效**），教务走 `CasSession` 的闸门。后台任务
  （`BalanceAlertReminder`）调同一批方法，因此自动获得上报、不需要单独埋点。

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
