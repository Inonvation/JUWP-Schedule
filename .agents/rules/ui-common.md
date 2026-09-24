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

## 卡片观感只有一处定义

- 卡片观感**只有一处定义**：`ui/common/AppCard.kt` 的 `AppCard` / `AppCardRow`
  （14dp 圆角 + 1dp `outlineVariant` 描边 + surface 底）。新卡片一律走它，
  **不要**再私写 `RoundedCornerShape` + `border`（2026-09-22 之前 12dp/14dp 两套并存）；
  点击涟漪与触感由 `AppCard` 统一给，调用方不要在外面再包一层 `clickable`。
  区块标题同理走 `ui/common/SectionHeader.kt`（「今天还有 N 节」「明天 · 周二」
  与笔记/作业库的「最近更新」「按课程」共用一套规格）。

## 一次性消息只有一条通道

- 一次性消息**只有一条通道**：页面 Scaffold 的 `snackbarHost = { AppSnackbarHost(snackbar) }`
  （`ui/common/AppNotice.kt`）。语气用 `NoticeTone` 四档，视觉规格见 DESIGN §3.2；
  **禁止**新增 `android.widget.Toast`（系统黑框，与 App 其余浮层两套观感）。

## 弹层是比页面高一层的独立窗口

- `ModalBottomSheet` / `AlertDialog` 是**比页面高一层的独立窗口**：页面级提示在它打开时必然被盖住。
  提示要落在弹层里就用 `InlineNoticeRow`（或先关弹层再提示），**不要**指望 Snackbar 穿透；
  加弹层内的异步流程前先确认结果会显示在哪个窗口。
