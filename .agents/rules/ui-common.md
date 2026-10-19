# 通用 UI 组件（卡片 · 弹层 · 提示）

作用域：新增或修改弹层、卡片、区块标题、一次性提示时读。视觉规格见 DESIGN §3.2。

## 配色只从主题取

- 页面颜色只从 `MaterialTheme.colorScheme` 取；禁止写死 `PrimaryLight`、`Color(0xFF...)` 品牌色。
  课程色、语义色（成功/警告/错误）、第三方品牌、地图定位、功能图标类别色与桌面图标除外。
- 默认配色晴空蓝（`ThemePalette.SkyBlue`）；新增界面按晴空蓝出方案。

## 含输入框的弹层一律用 ImeAwareModalBottomSheet

- **含输入框的弹层一律用 `ui/common/SheetDismissIme.kt` 的 `ImeAwareModalBottomSheet`**
  （**不要自己拼 `ModalBottomSheet` + `rememberModalBottomSheetState`**：`skipPartiallyExpanded = true`
  与退场时序都收在它里面，2026-09-24 两次修订后的唯一口径）。
  `skipPartiallyExpanded` 的根因（2026-09-24 真机定位「充值弹层下一步被键盘挡住」；**别再往
  insets 方向查**）：键盘弹起后弹层可用高度腰斩，内容高于一半时 M3 会造出
  `PartiallyExpanded = fullHeight/2` 锚点，并在锚点更新时把 target 从 Expanded **改判**成
  PartiallyExpanded → 弹层停在半高，最底部的按钮被键盘盖住。Redmi K70 实测：可用高度
  2400→1604px、内容 986px → 停在 802px 而不是 Expanded 的 618px，差 184px 正好盖住 126px
  高的「下一步」；手动上划 = 拖回 Expanded，所以表现是「划一下才看得见」。跳过该锚点后只剩
  Hidden/Expanded，键盘弹起时 target 保持 Expanded（实测 618px，按钮落在键盘上缘之上）。
  **内容固定不可滚**（用户拍板）：勿加 `verticalScroll` 再滚到底——首次点击时 maxValue
  未更新会滚不到位，滚动方案已弃用。**也不要自己垫键盘高度**：M3 已经把弹层底边
  （`Box(fillMaxSize().imePadding())`）与内容底（`contentWindowInsets = safeDrawing.only(Bottom)`）
  垫到键盘上缘，多垫一份会把内容挤出可视区——旧 `ui/common/ImeSheetGuard.kt` 就是这么错的，
  已删。
  **退场与键盘必须分两段、不能并行收**（2026-09-24 用户二次反馈「点弹窗其余地方 → 先键盘
  收回、弹窗延迟收回、收回动画诡异」）：M3 的退场是一条 `tween(300ms)`，锚点由**容器高度**
  算出（父 Box 是 `imePadding()`，键盘一收 1604→2400px）——并行收键盘时键盘动画每帧改高度
  → 每帧 `updateAnchors()` → 动画中被**取消重启**（foundation `restartable{}`），而 tween
  每次重启都从缓动曲线 0 点重新计时、不吸收初速度，终点每帧又下移 → 弹层每帧只推进剩余距离
  的不到 1%，几乎停在原地，键盘收完才一口气滑完。现在由
  `confirmValueChange`（M3 询问「能不能去 Hidden」的钩子，点遮罩 `animateToDismiss` 与下滑
  `settle` 都走它）在键盘还起着时**否决 Hidden**，宿主先交还焦点收键盘（弹层跟着键盘逐帧
  下移），`WindowInsets.ime` 归零（逐帧回调，收到 0 = 键盘动画结束）后再 `hide()`；超时 450ms
  兜底。宿主**必须组合在弹层内容里**：写在 `ModalBottomSheet { … }` 外面拿到的是 Activity
  窗口的 `LocalFocusManager` / `LocalSoftwareKeyboardController` / insets，收不动弹窗里的
  键盘（第一版实测无效）。校园卡充值、电费充值、快捷方式表单三个弹层已接；新弹层照抄
  `ImeAwareModalBottomSheet`。

## 关弹层一律"先播退场动画、再落状态"

- **任何"关弹层 / 关掉再做一件事"的路径都要走退场动画**：直接改 `showXxx = false` 会把弹层
  从组合里瞬间抽掉，表现是"啪"地消失（2026-09-30 用户报过两次：车号面板的出场，以及
  "只有出码面板有动画、其余弹层没有"）。两个出口，按弹层里有没有输入框选：
  - **含输入框**：`ImeAwareModalBottomSheet(onDismiss = …, pendingDismiss = 关掉之后要跑的动作)`；
    内容里把 `待执行动作` 写进 `pendingDismiss` 即可（同一路径也管"先关面板、再拉起微信"
    这类动作）。**别自己调 `sheetState.hide()`**——键盘还起着时 `confirmValueChange` 会否决它。
  - **不含输入框**：`rememberSheetDismisser(sheetState, onDismiss)`，拿到 `dismiss(after)`。
- **把手用 M3 默认那根**（`ModalBottomSheet` 不传 `dragHandle`）：全项目一致就好。
  嫌那点高度也别改成 `dragHandle = null` + 自己垫顶距——同一页里有的有把手、有的没有，
  比那条空白显眼。真要改就整项目一起改，改前先在真机上对一遍手感。
- **Snackbar 会压住贴底的主操作**：页面底部若有常驻动作条（如骑行页），把它实测的高度垫进
  `AppSnackbarHost` 的 `Modifier.padding(bottom = …)`，否则提示一来就看不见主按钮。

## 二级页一律走 PageScaffold（2026-10-18 起）

- 二级页（SubpageActivity 承载）**不要自己拼** Scaffold + TopAppBar + LazyColumn，
  用 ui/common/PageScaffold.kt：列表型 PageScaffold(...)，表单 / 设置型 PageScaffoldColumn(...)。
- 骨架钉死的口径（此前 14 个页面各写一套，抽查出 2 套顶栏字号、5 套空态、5 种首帧加载、
  3 档卡片间距、2 套提示宿主）：顶栏标题 	itleMedium（16sp）+ Ellipsis、返回键 AutoMirrored、
  提示宿主 AppSnackbarHost、页面边距 16dp、卡片间距 10dp、列表内边距 16/12。
- 三态顺序由骨架保证：**loading > empty > content**，两者都在视口内居中（illParentMaxSize）。
  loading / empty 是**声明**，不要写进 content 里自己判——那会让两者同时成立，
  又回到「先渲染空态再跳数据」。
- 刷新动作统一 PageRefreshAction(busy, onClick)：busy 时换成 20dp 转圈，不是只把图标置灰。
- 迁移时顺手把该页的 DialogWindowFadeEffect() 挪进弹窗内容槽（见下一节）。
  样板：ui/scholar/ScholarScreen.kt（原先是裸 SnackbarHost + 首帧一屏纯空白 + 自造空态）。
- 新页面偏离以上任何一条，要显式传参并在 KDoc 写理由。

## 卡片观感只有两处定义（描边卡 / 状态英雄卡）

- 卡片观感**只有两处定义**，按「这张卡在页面里是什么角色」选，别自己另写一套：
  - **描边卡** `ui/common/AppCard.kt` 的 `AppCard` / `AppCardRow`
    （14dp 圆角 + 1dp `outlineVariant` 描边 + surface 底）：**列表里的一行**，
    克制、可并列。新卡片默认走它；**不要**再私写 `RoundedCornerShape` + `border`
    （2026-09-22 之前 12dp/14dp 两套并存）；点击涟漪与触感由 `AppCard` 统一给，
    调用方不要在外面再包一层 `clickable`。
    `highlighted` 的三处颜色（底 / 描边色 / 描边宽）**走 180ms 过渡**（2026-10-16）：
    选中 / 取消选中不再突变。改动影响全部 `highlighted` 调用点（今日页开水卡、
    笔记·作业多选行、快趣附近车辆卡、U净 档位卡），改它要冒烟这几处。
  - **状态英雄卡** `ui/common/StatusHeroCard.kt`（`StatusHeroCard` + `HeroTone` +
    `heroToneAccent`，16dp 圆角 + 实心 tonal 底 + **无描边**）：**页面此刻正在发生的事**
    （U净 在案订单，2026-10-16 起）。**一屏只该有一张**——出现两张就等于没有英雄卡。
    语气底色的映射只有 `heroToneContainer` 一处，强调色只有 `heroToneAccent` 一处；
    内容排版归调用方。语气变化走底色过渡（220ms），不硬切。
- 区块标题同理走 `ui/common/SectionHeader.kt`（「今天还有 N 节」「明天 · 周二」
  与笔记/作业库的「最近更新」「按课程」共用一套规格）。

## 动效口径（2026-10-16 起，U净 页收口）

- **区块进入 / 退出**：`fadeIn + expandVertically(expandFrom = Top)`，220ms
  `FastOutSlowInEasing`；退出 160ms `shrinkVertically`。写死在页内常量，**不建全局
  motion token 文件**（与项目现状一致）。
- **高频数值（每秒变）不做 crossfade**：倒计时、计时文案每秒都在变，动画会一直闪
  （今日页流程简报同款结论）。变化感交给**进度条**：进度值 `animateFloatAsState` 400ms，
  每秒更新接续平滑，观感是连续推进。
- **非高频状态文案**才 crossfade（180ms），如贴底动作条的所选档位摘要。
- **列表增删**：`LazyColumn` 用 `Modifier.animateItem()`；普通 `Column` 里用
  `Modifier.animateContentSize()` 收平高度（逐项退场动画要 LazyColumn 才撑得住，
  别为此把整页改成 lazy）。整批换内容的卡片列表（骑行页附近车辆 / 还车点，
  `RidePanels.AnimatedCardList`，2026-10-19）可以走一层 `AnimatedContent` 整块
  淡入淡出 + `SizeTransform`：按**卡片身份集合**判是否播，只变距离数字不播；
  它不是逐项退场，别拿它替代 LazyColumn 的 `animateItem()`。
- **进页错峰入场**用 `ui/common/StaggeredReveal.kt`（每块 +60ms、6dp、220ms，
  **只在首次组合播一次**）：只包「一直存在的主区块」，别包由 `AnimatedVisibility`
  管出现/消失的块——两套动画叠加会打架。
- **卡片里放列表用平铺行，不许再套卡**（2026-10-09，用户反馈笔记/作业库「气泡套气泡」）：
  组卡展开区的条目走 `NoteRow(embedded = true)` / `HomeworkRow(embedded = true)` 这类
  平铺行——无卡底、无描边、整行涟漪，行间与头行下用 `AppCardDivider` 分隔
  （同口径的既有实现：流水 `TurnoverRow`、电费明细 `BillRow`，卡内直排 + 细线）。
  平铺行自己 `clickable` + `rememberAppHaptics`，不要再包 `AppCard` / `AppCardRow`。

## 金额大字只有一处定义

- 钱类弹窗 / 结果步的「主角金额」**只有一处实现**：`ui/common/AmountText.kt` 的
  `AmountHeroText`（`displaySmall` + SemiBold，2026-10-07 起）。已接：一卡通「支付成功 /
  充值成功 / 正在确认充值结果」三张弹窗与电费充值结果步（`AcceptedStep`）。
  层级口径：金额大字 → 次要信息（商户 · 时间、余额）`bodyMedium` + 0.75 透明度 →
  说明句 `bodySmall` + 0.55。改金额样式只动那一处，**别再给金额另写一种字号**。

## 等数据的地方先给骨架，不许先渲染空态

- **排队等数据的区域先渲染骨架，不要先渲染空态文案再跳**（2026-09-26 两处都是这个毛病：
  付款码页取码完成时整页上收一截、生活页流水卡先显示「还没有流水」再跳成真数据）。骨架的
  几何必须与成品一致，否则等于把跳动换了个位置。
- 占位块统一用 `ui/common/Skeleton.kt` 的 `SkeletonBox`（流光 shimmer：淡色底 + 高光带
  1200ms 左→右扫，动画值在 `drawBehind` 里读、只重绘不重组；别在各页再写一份
  `rememberInfiniteTransition`）。
- 多行并排的文字骨架条**不占满行盒**（2026-09-26 用户反馈「骨架矩形都粘在一起了」）：
  条高 = 行高 × 62%、在各自行盒里垂直居中——实心条按整行行高画，上下两条贴死成一块，
  而一行文字的字形本来就只占行盒中间约六成。行盒高度仍取 `lineHeightDp`，总几何与成品
  一致、零位移不变（实现见生活页 `LifeScreen` 的 `SkeletonLine`）。
- 占位块要顶掉一行真实文字时，行高一律走 `ui/common/TextMetrics.kt` 的 `lineHeightDp`
  （从样式的 `lineHeight` 换算，跟随系统字体缩放），**不要写死 dp**。写死的话用户把系统
  字体调大，成品那行会比骨架高一截，跳动又回来了。
- 图片位按码图/图源的**真实比例**用 `aspectRatio` 占死（付款码 QR 1:1、Code128 6:1），
  不要按"看起来差不多"估一个高度。
- 按钮行**常显、不可用置灰**，不做条件渲染——行出现或消失会把下方内容顶动。

## 一次性消息只有一条通道

- 一次性消息**只有一条通道**：页面 Scaffold 的 `snackbarHost = { AppSnackbarHost(snackbar) }`
  （`ui/common/AppNotice.kt`）。语气用 `NoticeTone` 四档，视觉规格见 DESIGN §3.2；
  **禁止**新增 `android.widget.Toast`（系统黑框，与 App 其余浮层两套观感）。
- **发送一律用 `snackbar.showNotice(...)`，不要用 M3 的 `showSnackbar`**（2026-10-08，全 App 已换完）：
  `showSnackbar` 是**排队**语义（内部一把 `Mutex`，先到的那条不返回，后来的挂在锁上），
  用户报的「连着切电脑版 / 手机版，第二条要等第一条过期才出来」就是它。`showNotice` 先把当前
  那条 `dismiss()` 掉再上新的——提示是「刚刚发生了什么」的一次性播报，新的盖掉旧的才对。
  **只有带 `actionLabel` 的那条不让位**（撤销 / 去设置：顶掉它等于把出口静默收走），
  它自己最长 `SnackbarDuration.Long` 就会过期，等待有界。判据是 `AppNotice.kt` 里那两个
  `showNotice` 重载，`AppNoticeTest` 钉住（含「M3 原样排队」的对照用例）。
- **事件流（`collect { }`）里发提示用 `postNotice`，别直接写 `showNotice`**（2026-10-09）：`showNotice`
  是挂起调用，要挂到这条提示收场（超时 / 被顶掉 / 点动作）才返回——直接写在收集协程里，
  下一条事件还没轮到发送，顶替逻辑没机会执行，又回到「新提醒要等旧提醒的提醒时间过」
  （用户再报的就是它）。`postNotice`（`ui/common/AppNotice.kt`）给每条提示单开协程；
  用户点击触发、本来就在自己 `scope.launch { }` 里的提示照旧直接 `showNotice`；
  带 `actionLabel` 的提示不走 `postNotice`（要读返回的 `SnackbarResult`）。判据是
  `AppNoticeTest` 的正反两条新用例（`postNotice` 连发当场顶替 / 收集协程里直接 await 会串行）。
- 「一条一条播」的队列要自己搭（如 `OutcomeQueue`）：**别指望提示宿主排队**——它现在只画当前
  那一条，节奏由发送侧「上一条返回后再发下一条」决定。

## 确认型弹窗的既有规格

- 纯确认/声明类弹窗（无输入）用 M3 `AlertDialog`，不走 `ImeAwareModalBottomSheet`（那是
  含输入框弹层的专属口径）。正文长时给 `heightIn(max=…)` + `verticalScroll` 防小屏裁切。
- **长文声明弹窗只有 `ui/common/LegalDialogs.kt` 的 `NoticeDialog` 一个实现**
  （标题 / 开场句 / 逐条正文都由调用方从 `domain/` 取，弹窗只排版）。`DisclaimerDialog`
  是它的免责声明薄封装；加新的声明类弹窗就再包一层，**不要另写一个 AlertDialog**。
- **「给人读」的声明 / 信息类弹窗正文比常规 +1 档**（2026-10-14）：更新日志
  （`UpdateAvailableDialog`）、充值免责声明（`RechargeDisclaimerDialog`）、免责声明 /
  用户须知 / 充值风险只读（`NoticeDialog`）、开源许可（`LicensesDialog`）、U净 使用须知
  （`UjingScreen.UjingNoticeDialog`）这几处的正文——`bodySmall` 处写 `bodyMedium`、
  `bodyMedium` 处写 `bodyLarge`、脚注 `labelSmall` 处写 `bodySmall`。字号仍取
  `ui/theme/Type.kt` 现有角色，**不动全局档位**（那是「字体缩放锁定」的领地）。
  确认 / 操作类弹窗（清缓存、清空课程、删除退出等）按常规档，不跟这条。
- **关闭锁一律走 `rememberCloseLock(totalMs)`**（同文件）：锁住期间确认键置灰、返回与
  点遮罩无效。两个硬性口径：
  1. **用 `SystemClock.elapsedRealtime()`，不要用 `System.currentTimeMillis()`** ——
     后者跟着系统时间走，用户把时间往前调就能把锁瞬间走完；
  2. 计时起点是**组合第一次进入时**，不是宿主算锁的那一刻 —— 弹窗还没上屏就把秒数走掉，
     锁就白设了。
- **弹窗的进出场动画统一走 `ui/common/DialogWindowFade.kt` 的 `DialogWindowFadeEffect`**
  （2026-10-03）：material3 1.3.0 的 `AlertDialog` 内部没有 compose 侧动画、compose 的
  `DialogWindowTheme` 也没配 `windowAnimationStyle`——弹窗开与关都是瞬间出现 / 抽掉
  （用户在快趣开锁 / 还车弹窗上报过「突变跳动」）。**调用点必须放在弹窗窗口自己的组合里
  ——即 `AlertDialog` 某个内容槽（如 `text = { }`）的第一行**；它把 `DialogWindowFade`
  样式（`res/values/dialog_motion.xml`：淡入 + 0.86→1 放大 240ms / 淡出 190ms）挂到
  dialog window 上由 WindowManager 播，内容与背后的压暗一起过渡；退场时窗口表面停在
  最后一帧直到播完，不会"内容先没了、窗再抽掉"。**进、出两段都能播的原因**：副作用落在
  首帧 traversal 里（`SideEffect`），而 enter 动画取样式发生在首帧 HAS_DRAWN 之后；
  弹窗内容里的 `LocalView` 就是 `DialogLayout`（实现 `DialogWindowProvider`）。
  **⚠️ 放 `AlertDialog` 调用外面是死代码（2026-10-08 实测）**：那时 `LocalView` 是页面
  窗口、取不到 dialog window，不会挂上本应用样式——与不调用完全等价（A/B：内容槽内
  `wanim=0x7f…` 本应用样式；外面 = 框架默认 `0x103030a`，与无调用弹窗一致）。
  **快趣出行那 5 处已挪进内容槽（2026-10-14）**；**其余 16 处外置旧写法已于 2026-10-18
  全量移正**（AiChat / WheelPicker / WaterScreen / QzxyScreen / WeekScreen / InboxScreen 等），
  并补齐 8 处从未调用过的（笔记与作业详情的删除/放弃、清空成绩、删除预选、免扰时段、空调定时）。
  校验方式：DialogWindowFadeEffect() 的下一行若是 AlertDialog( 即为死代码，当前剩余 **0 处**。
  新弹窗一律按「内容槽第一行」写。首启「全部跳过」确认弹窗（`OnboardingScreen`）
  已是正确写法可参照。
  material3 升到 1.4.0（弹窗自带进出场动画）后，调用点可整体删掉。
- **锁时长由宿主算好传入，弹窗不做「是否首次」的判断**：
  - 首启的两份声明（用户须知 / 免责声明）：队列只装「没同意过」的那几份
    （`domain/NoticeConsent.pendingNotices`），所以进队列的必然锁满，锁时长直接取
    `domain/FirstRunNotices.closeLockMs`；
  - **充值免责声明**（`ui/common/RechargeDisclaimerDialog`）：每个充值入口开弹层前都过它，
    首次弹出锁 5 秒（宿主按 `domain/RechargeDisclaimer.closeLockMs` 算），
    「已确认」落库在宿主侧（`DisplayPrefsStore.markRechargeDisclaimerSeen`）。
- **同意/已读要落盘**（`DisplayPrefsStore.markNoticeConsented` / `markRechargeDisclaimerSeen`）：
  落的是**版本号 + 时刻**。只锁秒数不留记录，事后什么都核不出来；版本号则是「改了文案
  要让用户重看」的唯一判据（改文案必须 bump `FirstRunNotices.VERSION`）。
- **「不接受」要有明示出口，且关于页是老安装的确认口**（2026-09-30）：
  1. `NoticeDialog` 的 `declineLabel` / `onDecline` 给「不同意」第二枚按钮（首启队列 =
     「不同意并退出」，宿主 `finishAffinity` 整应用退场，不写同意记录也不写
     `onboarding_seen`——进主界面的唯一路径是同意；锁住期间同样置灰）。不给参数 =
     纯声明查看，不渲染这枚按钮。
  2. 「我的 → 关于」的用户须知弹窗：**关掉（确认键/返回/遮罩都算）即写当前版本同意记录**。
     队列只在引导里弹，引导键已写过的老安装永远见不到队列——不补这个口，关于页的
     「未确认」就永远是死的（2026-09-30 用户报的就是这个）。
  3. `RechargeDisclaimerDialog` 的取消键文案是**「不同意并取消支付」**：明示不接受协议 =
     本次充值作罢、不进充值流程；仍落「已确认」时刻（看过 ≠ 同意，下次充值还会再弹，
     只是不再锁 5 秒）。

## 弹层是比页面高一层的独立窗口

- `ModalBottomSheet` / `AlertDialog` 是**比页面高一层的独立窗口**：页面级提示在它打开时必然被盖住。
  提示要落在弹层里就用 `InlineNoticeRow`（或先关弹层再提示），**不要**指望 Snackbar 穿透；
  加弹层内的异步流程前先确认结果会显示在哪个窗口。

## 字体缩放锁定（「固定字体大小」，默认开）

- 2026-10-09 起全 App 文字按固定倍率 **0.9×** 渲染，不随系统「字体大小」缩放：
  不同机器的字号档位差异会把组件文字挤占（换行、裁切、按钮溢出），锁定保证
  「谁在什么手机上，看到的都是同一套排版」。**0.9× 是观感基准**（真机核定：1.0× 偏大），
  定义在 `domain/FontScalePolicy.LockedScale`，`FontScalePolicyTest` 钉住它；要改想清楚——
  它一动，全 App 字号一起动。
- 落点唯一：`MainActivity.JuwRoot` 的 `ProvideFixedFontScale` 覆盖 `LocalDensity.fontScale`。
  **只覆盖 fontScale**——密度（dp/px）原样透传，图标、间距、命中区、自适应行高不受影响。
  弹层 / 底部弹层 / 下拉菜单同属一棵合成树，自动继承，**不要各自再包一层**。
- 写「取字号」的代码时读组合的 `LocalDensity`（= 当前生效值，与真实渲染同源）；
  **不要读 `resources.configuration.fontScale`**——锁定开启时它与真实渲染脱节。
- 排版表（字号/行高/字重）全角色钉死在 `ui/theme/Type.kt`（数值 = material3 1.3.0
  生效值，含各角色字距）；换 material3 版本前后对一遍现值。改任何一档字号属于观感
  重调——全套布局几何（行高 56dp、骨架行高、网格）按现值调校，别顺手改。
- 开关在「我的 → 通用设置 → 观感与布局」，**即时生效**；关掉 = 完全回到旧行为（跟随系统）。
  网格字号面板的「跟随」槽随锁定态换措辞（锁定「默认」/ 解锁「跟随系统」），
  见 DESIGN §3.2「课表字号」行。
- 边界：桌面小组件 / 系统通知 / 教务等 WebView 内容不归它管（系统渲染或 WebView
  自己的口径，WebView 现状 `textZoom = 100` 保留）。

## 设置页说明的层级口径

- 设置说明分四级，**不许出现第五种写法**：
  1. **分区标题**（`SettingsSection.title`）：名词短语，≤ 6 字；
  2. **分区副标题**（`SettingsSection.subtitle`）：一句话说这组是什么，≤ 20 字，可省；
  3. **行副标题**（`SettingItem` / `SettingSwitchRow` / `SettingChoiceRow` 的 `subtitle`）：
     一句话说「打开或点击后会发生什么」，≤ 25 字；
  4. **分区注记**（`SettingsNote`，`ui/common/SettingsUi.kt`）：每区最多一条，≤ 30 字，
     只放跨行的前提或边界。
- **「说明」型分区**：整块由短句组成时允许 2~4 条，每条 ≤ 30 字（带项目符号的列表另算，
  见小组件设置页的 `Bullet`）。
- **禁止**：行副标题重复标题、写日期 / 历史 / 「别…」、写括注补充；分区副标题写实现细节
  （「两个精确闹钟，不写系统日历」这类）；同一件事在两级里说两遍。
- 免责 / 计费 / 第三方口径不进副标题，走 `FooterDisclaimer`（`ui/common/AppNotice.kt`）。
- 注记的颜色与行距只有 `SettingsNote` 一处定义（0.55 透明度），页面别再手写
  `Text(...)` 自己调透明度——旧代码里有 `0.5f` / `onSurfaceVariant` 两套。
- 已按此口径收敛（2026-10-12）：快趣出行设置、我的 → 设置一组（提醒 / 权限 / 通用 /
  提醒与桌面 / 免扰时段）、成绩设置、天气设置、问渠设置、课表设置、日历设置、小组件设置、
  校园卡设置、快捷方式、上课提醒。
