# 通用 UI 组件（卡片 · 弹层 · 提示）

作用域：新增或修改弹层、卡片、区块标题、一次性提示时读。视觉规格见 DESIGN §3.2。

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

## 卡片观感只有一处定义

- 卡片观感**只有一处定义**：`ui/common/AppCard.kt` 的 `AppCard` / `AppCardRow`
  （14dp 圆角 + 1dp `outlineVariant` 描边 + surface 底）。新卡片一律走它，
  **不要**再私写 `RoundedCornerShape` + `border`（2026-09-22 之前 12dp/14dp 两套并存）；
  点击涟漪与触感由 `AppCard` 统一给，调用方不要在外面再包一层 `clickable`。
  区块标题同理走 `ui/common/SectionHeader.kt`（「今天还有 N 节」「明天 · 周二」
  与笔记/作业库的「最近更新」「按课程」共用一套规格）。

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

## 确认型弹窗的既有规格

- 纯确认/声明类弹窗（无输入）用 M3 `AlertDialog`，不走 `ImeAwareModalBottomSheet`（那是
  含输入框弹层的专属口径）。正文长时给 `heightIn(max=…)` + `verticalScroll` 防小屏裁切。
- **长文声明弹窗只有 `ui/common/LegalDialogs.kt` 的 `NoticeDialog` 一个实现**
  （标题 / 开场句 / 逐条正文都由调用方从 `domain/` 取，弹窗只排版）。`DisclaimerDialog`
  是它的免责声明薄封装；加新的声明类弹窗就再包一层，**不要另写一个 AlertDialog**。
- **关闭锁一律走 `rememberCloseLock(totalMs)`**（同文件）：锁住期间确认键置灰、返回与
  点遮罩无效。两个硬性口径：
  1. **用 `SystemClock.elapsedRealtime()`，不要用 `System.currentTimeMillis()`** ——
     后者跟着系统时间走，用户把时间往前调就能把锁瞬间走完；
  2. 计时起点是**组合第一次进入时**，不是宿主算锁的那一刻 —— 弹窗还没上屏就把秒数走掉，
     锁就白设了。
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

## 弹层是比页面高一层的独立窗口

- `ModalBottomSheet` / `AlertDialog` 是**比页面高一层的独立窗口**：页面级提示在它打开时必然被盖住。
  提示要落在弹层里就用 `InlineNoticeRow`（或先关弹层再提示），**不要**指望 Snackbar 穿透；
  加弹层内的异步流程前先确认结果会显示在哪个窗口。
