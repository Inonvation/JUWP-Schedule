package edu.jxslu.schedule.ui.onboarding

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.repo.ScholarProgressSync
import edu.jxslu.schedule.data.repo.ScoreSync
import edu.jxslu.schedule.data.session.CasEnsureResult
import edu.jxslu.schedule.data.session.CasSession
import edu.jxslu.schedule.data.session.CredentialVault
import edu.jxslu.schedule.data.ykt.YktException
import edu.jxslu.schedule.domain.FirstRunNotice
import edu.jxslu.schedule.domain.FirstRunNotices
import edu.jxslu.schedule.domain.NoticeConsent
import edu.jxslu.schedule.domain.QzxySessionLink
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.NoticeDialog
import edu.jxslu.schedule.ui.common.NoticeFeedback
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.theme.semanticColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft02
import me.rerere.hugeicons.stroke.Calendar01
import me.rerere.hugeicons.stroke.ChartAverage
import me.rerere.hugeicons.stroke.CreditCard
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.EyeOff
import me.rerere.hugeicons.stroke.GraduationCap
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.ShowerHead
import me.rerere.hugeicons.stroke.Tick02

/** 引导步骤（DESIGN §3.16）。顺序即流程，[Done] 是终点。 */
private enum class Step(val index: Int, val title: String, val desc: String) {
    Welcome(0, "欢迎", ""),
    Jw(1, "学校统一认证", "仅支持江西水利电力大学账号。教务 · 学工（报修 / 请假）· 盖章成绩单，共用这一套账号"),
    Ykt(2, "一卡通 · 寝室电费", "仅支持江西水利电力大学一卡通。余额、付款码、消费流水、电费共用这份凭证（学号 + 查询密码，不是统一认证密码）"),
    Qiekj(3, "胖乖生活", "开水房的账号，与学校账号无关，需要单独登录"),
    Qzxy(4, "趣智校园", "洗澡开热水的账号，与学校账号无关；充值在登录后的官方小程序完成"),
    Done(5, "配置完成", "没配置的项随时能在「我的」页补上，那里也能看到每个平台的登录状态"),
}

/**
 * 完成页后台补抓状态行（DESIGN §3.16）。教务配置成功后成绩 / 学业完成情况在
 * appScope 里补抓，本状态供完成页把「正在同步」收敛成结果。
 * [Idle] = 教务没配置或还没跑到；[DoneOk] = 至少一项拿到 Updated / Skipped / InProgress
 * （数据在库或另一次抓取在跑，都算有结果）；[DoneFailed] = 两项都 Failed。
 */
private enum class BgSyncPhase { Idle, Running, DoneOk, DoneFailed }

private const val TOTAL_STEPS = 5

/** 验证成功后「对勾停留」再自动进下一步的时长。 */
private const val SUCCESS_HOLD_MS = 900L

/** 切屏转场与交错入场的强调缓动（快出缓入，落点稳）。 */
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** 入场缓动（缓缓落定）。 */
private val RiseEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * 首次配置引导（DESIGN §3.16 / §4.27）。
 *
 * 六屏：欢迎 → 学校统一认证 → 一卡通·电费 → 胖乖生活 → 趣智校园 → 完成。**每一步都能跳过**，
 * 跳过的只是那一步的凭据，不挡后面的步骤，也不挡进主界面。
 *
 * 呈现层（2026-09-28 重构）：方向性切屏转场 + 分段进度条 + 每屏交错入场 +
 * 验证成功对勾反馈；登录方式改分段控件。四步登录的业务逻辑与「先验后存」顺序未动。
 *
 * 第 2 步用原生表单收密码，是整个 App 里唯一明确告诉用户「我们会保存这个密码」的地方
 * —— 因为「会话失效后自动续登」必须要有密码才做得到（WebView 登录拿不到密码）。
 *
 * @param startAtJw 直接落在「学校统一认证」那一步。给**重复进入**用：状态卡显示
 *   「登录状态已失效」时点进去就是要改密码，没必要让用户再走一遍欢迎页。
 *   首启由 `MainActivity` 拉起时用默认值（从欢迎页开始）。
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit, startAtJw: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Graph.displayPrefs(context) }
    val vault = remember { Graph.credentialVault(context) }
    val cas = remember { Graph.casSession(context) }
    val view = LocalView.current

    // 验证码冷却起点提到引导级（胖乖与趣智各一份，互不牵连）：跨步返回冷却不丢。
    // 服务端限流本来就还在，这里只求按钮口径与之一致。
    var qiekjCodeSentAt by remember { mutableStateOf(0L) }
    var qzxyCodeSentAt by remember { mutableStateOf(0L) }
    // 成绩 / 学业后台补抓状态，完成页状态行读它（见 startBgSync）
    var bgSyncPhase by remember { mutableStateOf(BgSyncPhase.Idle) }

    var step by remember { mutableStateOf(if (startAtJw) Step.Jw else Step.Welcome) }
    var jwOk by remember { mutableStateOf(false) }
    var yktOk by remember { mutableStateOf(false) }
    var qiekjOk by remember { mutableStateOf(false) }
    var qzxyOk by remember { mutableStateOf(false) }
    // 首启的两份声明（DESIGN §3.16）：待弹队列 + 用户主动点「查看免责声明」的单份查看。
    // 队列在首帧之后由落盘的同意记录算出（见下面的 LaunchedEffect）；主动查看那份不锁、
    // 也不写同意记录（看过 ≠ 首启确认过）。
    var noticeQueue by remember { mutableStateOf<List<FirstRunNotice>>(emptyList()) }
    var browseNotice by remember { mutableStateOf<FirstRunNotice?>(null) }

    // 首启要弹哪几份：按落盘的「已同意版本」比对（`domain/NoticeConsent.pendingNotices`），
    // 全部同意过就是一个空队列。`first()` 会挂到 DataStore 首次发射——不能拿流的初值 0
    // 去判，那会把"同意过"误判成"没同意过"而白弹一轮。
    // 直接进来改密码的（startAtJw）不弹：那是重复进入，不是首启。
    LaunchedEffect(Unit) {
        if (startAtJw || step != Step.Welcome) return@LaunchedEffect
        noticeQueue = NoticeConsent.pendingNotices(
            consents = mapOf(
                FirstRunNotice.UserNotice to
                    prefs.noticeConsent(FirstRunNotice.UserNotice).first().version,
                FirstRunNotice.Disclaimer to
                    prefs.noticeConsent(FirstRunNotice.Disclaimer).first().version,
            ),
            currentVersion = FirstRunNotices.VERSION,
        )
    }

    /** 完成或跳过都写标记——只有「走完了」才算看过，后面不再打扰。 */
    fun finish() {
        scope.launch {
            prefs.setOnboardingSeen()
            onFinish()
        }
    }

    fun next(target: Step) {
        step = target
    }

    /** 上一步（DESIGN §3.16）。[Step.Done] 退回趣智校园那一步。 */
    fun back() {
        step = when (step) {
            Step.Jw -> Step.Welcome
            Step.Ykt -> Step.Jw
            Step.Qiekj -> Step.Ykt
            Step.Qzxy -> Step.Qiekj
            Step.Done -> Step.Qzxy
            Step.Welcome -> Step.Welcome
        }
    }

    /**
     * 成绩 / 学业完成情况后台补抓（DESIGN §4.29），统一认证配置成功后由 JwStep 调。
     *
     * 两条纪律（原 JwStep 内联注释，随实现上移到这）：
     * 1. **不等它**：学业完成情况要连抓四个页面，校园网下可能十几秒；
     *    引导页不能为它卡住「下一步」。用户走到「我的 → 学习」时大概率已落库。
     * 2. **不用本页的 scope**：这里用进程级 appScope。本页的
     *    rememberCoroutineScope 在跳进 MainActivity 时就被取消，
     *    挂上去会让抓取半路夭折（可能只写了一半维度）。
     *    结果写进 [bgSyncPhase]：组合销毁后再写孤儿状态无害，完成页活着就能看到收敛。
     */
    fun startBgSync() {
        // 上一次还在跑就让位：sync 内部有 mutex，重复 force 只会白打一轮
        if (bgSyncPhase == BgSyncPhase.Running) return
        bgSyncPhase = BgSyncPhase.Running
        val appContext = context.applicationContext
        Graph.appScope.launch {
            val score = runCatching { Graph.scoreSync(appContext).sync(force = true) }
            val scholar = runCatching { Graph.scholarProgressSync(appContext).sync(force = true) }
            val okScore = score.getOrNull()?.let { it !is ScoreSync.Result.Failed } == true
            val okScholar = scholar.getOrNull()?.let { it !is ScholarProgressSync.Result.Failed } == true
            bgSyncPhase = if (okScore || okScholar) BgSyncPhase.DoneOk else BgSyncPhase.DoneFailed
        }
    }

    // 返回键 = **上一步**（2026-09-27 改）：此前是「跳过本步」往前跳，与直觉相反——
    // 想回头改一下密码只能一路跳过再重新进引导。跳过本步仍有出口：顶栏「跳过」收整个引导，
    // 每屏底部的「先跳过」进下一步，两条都在。
    // 直接进来改密码的（startAtJw）：返回 = 退出，用户本来就不在配置流程里。
    BackHandler(enabled = !startAtJw && step != Step.Welcome) { back() }

    // 切步轻触感：欢迎页首帧不算，之后每前进一步 tick 一下
    var stepPrimed by remember { mutableStateOf(false) }
    LaunchedEffect(step) {
        if (!stepPrimed) {
            stepPrimed = true
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        OnboardingTopBar(
            showBack = !startAtJw && step != Step.Welcome,
            onBack = { back() },
            showSegments = !startAtJw,
            filledSegments = step.index,
            showSkip = step != Step.Done,
            onSkipAll = { finish() },
        )
        AnimatedContent(
            targetState = step,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                (
                    slideInHorizontally(
                        animationSpec = tween(320, easing = EmphasizedEasing),
                    ) { full -> if (forward) full / 3 else -full / 3 } + fadeIn(tween(320))
                    ) togetherWith (
                    slideOutHorizontally(
                        animationSpec = tween(320, easing = EmphasizedEasing),
                    ) { full -> if (forward) -full / 3 else full / 3 } + fadeOut(tween(200))
                    )
            },
            label = "onboardingStep",
        ) { current ->
            when (current) {
                Step.Welcome -> WelcomeStep(
                    onNext = { next(Step.Jw) },
                    onOpenDisclaimer = { browseNotice = FirstRunNotice.Disclaimer },
                )
                Step.Jw -> JwStep(
                    cas = cas,
                    vault = vault,
                    scope = scope,
                    onLaunchBgSync = { startBgSync() },
                    // 重复进入（只改密码）时这一步做完就收窗，不再拖着用户走后面几屏
                    onDone = { jwOk = true; if (startAtJw) finish() else next(Step.Ykt) },
                    onSkip = { if (startAtJw) finish() else next(Step.Ykt) },
                )
                Step.Ykt -> YktStep(
                    vault = vault,
                    scope = scope,
                    onDone = { yktOk = true; next(Step.Qiekj) },
                    onSkip = { next(Step.Qiekj) },
                )
                Step.Qiekj -> QiekjStep(
                    scope = scope,
                    codeSentAt = qiekjCodeSentAt,
                    onCodeSent = { qiekjCodeSentAt = it },
                    onDone = { qiekjOk = true; next(Step.Qzxy) },
                    onSkip = { next(Step.Qzxy) },
                )
                Step.Qzxy -> QzxyStep(
                    scope = scope,
                    codeSentAt = qzxyCodeSentAt,
                    onCodeSent = { qzxyCodeSentAt = it },
                    onDone = { qzxyOk = true; next(Step.Done) },
                    onSkip = { next(Step.Done) },
                )
                Step.Done -> DoneStep(
                    jwOk = jwOk,
                    yktOk = yktOk,
                    qiekjOk = qiekjOk,
                    qzxyOk = qzxyOk,
                    bgSyncPhase = bgSyncPhase,
                    onFinish = { finish() },
                )
            }
        }
    }

    // 首启的声明队列（DESIGN §3.16）：弹完一份出队一份，两份都确认过才回到引导本身。
    // 文案、锁时长只有 `domain/FirstRunNotices` 一份，这里只做编排；正文取自
    // domain（免责声明那份与 README 同源），弹窗只负责排版与倒数。
    // 确认即落盘（版本号 + 时刻）：下次进来队列为空，不重复打扰。
    noticeQueue.firstOrNull()?.let { notice ->
        NoticeDialog(
            title = FirstRunNotices.title(notice),
            intro = FirstRunNotices.intro(notice),
            items = FirstRunNotices.items(notice),
            closeLockMs = FirstRunNotices.closeLockMs(notice),
            onDismiss = {
                noticeQueue = noticeQueue.drop(1)
                scope.launch { prefs.markNoticeConsented(notice, FirstRunNotices.VERSION) }
            },
        )
    }

    // 用户自己点「查看免责声明」的那一份：随时可关
    browseNotice?.let { notice ->
        NoticeDialog(
            title = FirstRunNotices.title(notice),
            intro = FirstRunNotices.intro(notice),
            items = FirstRunNotices.items(notice),
            onDismiss = { browseNotice = null },
        )
    }
}

// ---------------------------------------------------------------- 顶栏

/**
 * 顶栏：返回箭头 · 分段进度 · 跳过。步骤标题不再放顶栏——每屏自己的「步骤头」承载，
 * 顶栏只留流程控件。
 */
@Composable
private fun OnboardingTopBar(
    showBack: Boolean,
    onBack: () -> Unit,
    showSegments: Boolean,
    filledSegments: Int,
    showSkip: Boolean,
    onSkipAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(
                    HugeIcons.ArrowLeft02,
                    contentDescription = "上一步",
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            Spacer(Modifier.width(44.dp))
        }
        if (showSegments) {
            StepSegments(
                filledCount = filledSegments,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (showSkip) {
            TextButton(onClick = onSkipAll) {
                Text("跳过", color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** 分段进度条：[TOTAL_STEPS] 段，填充数随步骤推进入场；语义上给 TalkBack 报当前位置。 */
@Composable
private fun StepSegments(filledCount: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription =
                if (filledCount > 0) "第 $filledCount 步，共 $TOTAL_STEPS 步" else "准备开始，共 $TOTAL_STEPS 步"
        },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(TOTAL_STEPS) { i ->
            val fraction by animateFloatAsState(
                targetValue = if (filledCount > i) 1f else 0f,
                animationSpec = tween(280, easing = FastOutSlowInEasing),
                label = "segment$i",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 通用件

/**
 * 交错入场：直接内容按 [index] 依次延迟上浮淡入。每步只在进入时播一次
 * （AnimatedContent 切换会重建目标步的组合，旧步组合里已定格在终点）。
 */
@Composable
private fun StaggerIn(index: Int, content: @Composable () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val rise = remember { Animatable(14.dp, Dp.VectorConverter) }
    LaunchedEffect(Unit) {
        delay(index * 60L)
        launch { alpha.animateTo(1f, tween(380, easing = RiseEasing)) }
        launch { rise.animateTo(0.dp, tween(380, easing = RiseEasing)) }
    }
    val density = LocalDensity.current
    Box(
        Modifier.graphicsLayer {
            this.alpha = alpha.value
            translationY = with(density) { rise.value.toPx() }
        },
    ) { content() }
}

/** 错误晃动容器：[tick] 每自增一次播一小段左右晃，把视线引到表单。 */
@Composable
private fun ShakeBox(tick: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val x = remember { Animatable(0.dp, Dp.VectorConverter) }
    LaunchedEffect(tick) {
        if (tick > 0) {
            x.animateTo(
                0.dp,
                keyframes<Dp> {
                    durationMillis = 280
                    0.dp at 0
                    (-5).dp at 55
                    5.dp at 110
                    (-3).dp at 170
                    3.dp at 220
                    0.dp at 280
                },
            )
        }
    }
    Box(modifier.offset(x = x.value)) { content() }
}

/** 步骤头：圆角图标块 + 标题 + 一句话说明；[done] 时右下角弹出「已验证」角标。 */
@Composable
private fun StepHeader(icon: ImageVector, title: String, desc: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(60.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
            // 角标不用 AnimatedVisibility：外层是 Row，Kotlin 会把调用解析到
            // RowScope 扩展上报错；这里用弹簧缩放的 Animatable，行为等价
            val badgeScale = remember { Animatable(0f) }
            LaunchedEffect(done) {
                if (done) {
                    badgeScale.animateTo(
                        1f,
                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                    )
                } else {
                    badgeScale.snapTo(0f)
                }
            }
            if (badgeScale.value > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = badgeScale.value
                            scaleY = badgeScale.value
                        }
                        .clip(CircleShape)
                        .background(MaterialTheme.semanticColors.success)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        HugeIcons.Tick02,
                        contentDescription = "已验证",
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

/** 「为什么需要这一步」折叠行：默认收起，点开展开说明，别让说明文字跟表单抢第一屏。 */
@Composable
private fun WhyCard(question: String, body: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val chevron by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "chevron",
    )
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
            .animateContentSize(tween(240, easing = FastOutSlowInEasing)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                question,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                HugeIcons.ArrowDown01,
                contentDescription = if (open) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = chevron },
            )
        }
        if (open) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            )
        }
    }
}

/** 信任条：绿色语义底 + 盾形图标。引导里只有一句这种级别的话——「我们会保存密码」。 */
@Composable
private fun TrustBar(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.semanticColors.success.copy(alpha = 0.12f))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            HugeIcons.Shield01,
            contentDescription = null,
            tint = MaterialTheme.semanticColors.success,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
    }
}

/** 提示槽：错误/警告走行内提示滑入，成功走对勾描边行；两者互斥（成功时清掉错误）。 */
@Composable
private fun NoticeSlot(notice: NoticeFeedback?, successMessage: String?) {
    AnimatedVisibility(
        visible = successMessage != null,
        enter = expandVertically(tween(260, easing = FastOutSlowInEasing)) + fadeIn(tween(260)),
        exit = shrinkVertically(tween(200)) + fadeOut(tween(200)),
    ) {
        SuccessRow(successMessage.orEmpty())
    }
    AnimatedVisibility(
        visible = notice != null && successMessage == null,
        enter = expandVertically(tween(240, easing = FastOutSlowInEasing)) + fadeIn(tween(240)),
        exit = shrinkVertically(tween(200)) + fadeOut(tween(200)),
    ) {
        val current = notice
        if (current != null) InlineNoticeRow(current.text, current.tone)
    }
}

/** 成功反馈行：对勾描边 + 一句话，停留 [SUCCESS_HOLD_MS] 后自动进下一步。 */
@Composable
private fun SuccessRow(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.semanticColors.success.copy(alpha = 0.13f))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DrawnCheckCircle(Modifier.size(20.dp), MaterialTheme.semanticColors.success)
        Spacer(Modifier.width(9.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
    }
}

/** 小号对勾圆：外圈描完描对勾，一段 Animatable 两阶段完成。 */
@Composable
private fun DrawnCheckCircle(modifier: Modifier, color: Color) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * (progress.value * 2f).coerceAtMost(1f),
            useCenter = false,
            style = stroke,
        )
        val t = ((progress.value - 0.5f) / 0.5f).coerceIn(0f, 1f)
        if (t > 0f) {
            val path = Path().apply {
                moveTo(size.width * 0.26f, size.height * 0.53f)
                lineTo(size.width * 0.44f, size.height * 0.70f)
                lineTo(size.width * 0.76f, size.height * 0.34f)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val segment = Path()
            measure.getSegment(0f, measure.length * t, segment, true)
            drawPath(segment, color, style = stroke)
        }
    }
}

/**
 * 底部动作区：唯一主按钮 + 文字链次动作（先跳过 / 查看免责声明）。
 * [busy] 显示加载圈，[done] 按钮置灰定格「已保存」。
 */
@Composable
private fun StepActions(
    primaryText: String,
    busy: Boolean,
    busyText: String,
    enabled: Boolean,
    onPrimary: () -> Unit,
    skipText: String?,
    onSkip: (() -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .navigationBarsPadding()
            .padding(top = 8.dp, bottom = 10.dp),
    ) {
        val done = !enabled && !busy
        Button(
            onClick = onPrimary,
            enabled = enabled,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(9.dp))
                Text(busyText)
            } else {
                Text(if (done) "已保存" else primaryText)
            }
        }
        if (skipText != null && onSkip != null) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    skipText,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
        }
    }
}

/** 密码输入框：带可见性切换。 */
@Composable
private fun PasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean = false,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    var fieldModifier = modifier
        .fillMaxWidth()
    if (focusRequester != null) fieldModifier = fieldModifier.focusRequester(focusRequester)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Password,
        ),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (visible) HugeIcons.EyeOff else HugeIcons.Eye,
                    contentDescription = if (visible) "隐藏密码" else "显示密码",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.size(19.dp),
                )
            }
        },
        shape = RoundedCornerShape(14.dp),
        modifier = fieldModifier,
    )
}

/** 普通单行输入框：统一 14dp 圆角与可选自动聚焦。 */
@Composable
private fun OnboardingTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var fieldModifier = modifier.fillMaxWidth()
    if (focusRequester != null) fieldModifier = fieldModifier.focusRequester(focusRequester)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = keyboardType?.let { KeyboardOptions(keyboardType = it) } ?: KeyboardOptions(),
        shape = RoundedCornerShape(14.dp),
        modifier = fieldModifier,
    )
}

/** 每步进屏后把焦点交给第一个输入框（等转场落定再弹键盘）。 */
@Composable
private fun FocusOnEnter(requester: FocusRequester) {
    LaunchedEffect(requester) {
        delay(430)
        runCatching { requester.requestFocus() }
    }
}

/**
 * 成功触感工厂：CONFIRM 在 API 30 才有，低版本退回 KEYBOARD_TAP。
 * 四个登录步共用一个口径。
 */
@Composable
private fun rememberSuccessHaptic(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.KEYBOARD_TAP
                },
            )
        }
    }
}

// ---------------------------------------------------------------- 欢迎屏

@Composable
private fun WelcomeStep(onNext: () -> Unit, onOpenDisclaimer: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            StaggerIn(0) { WelcomeHero() }
            Spacer(Modifier.height(22.dp))
            StaggerIn(1) { FeatureRow() }
            Spacer(Modifier.height(18.dp))
            StaggerIn(2) {
                Text(
                    "每一步都可以跳过，之后在「我的」页补上；跳过的功能会提示未配置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            StaggerIn(3) {
                Text(
                    "本应用完全免费，不是学校官方应用。开始之前，请先看一遍免责声明。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        StaggerIn(4) {
            StepActions(
                primaryText = "开始配置",
                busy = false,
                busyText = "",
                enabled = true,
                onPrimary = onNext,
                skipText = "查看免责声明",
                onSkip = onOpenDisclaimer,
            )
        }
    }
}

/** 品牌头：水滴呼吸 + 三圈涟漪常驻循环，贴「水贝贝」的水意象。 */
@Composable
private fun WelcomeHero() {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            repeat(3) { i ->
                RippleRing(
                    startDelay = i * 850,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val breath by rememberInfiniteTransition(label = "breath").animateFloat(
                initialValue = 1f,
                targetValue = 1.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "breathScale",
            )
            Box(
                Modifier
                    .size(86.dp)
                    .scale(breath)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    HugeIcons.Droplet,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "欢迎使用水贝贝",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "江西水利电力大学课表工具（非官方 · 完全免费）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "接下来用几步把学校账号配好。配过之后，课表、成绩、一卡通、开水都不用再手动登录。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/** 一圈向外扩散淡出的涟漪，[startDelay] 错开相位。 */
@Composable
private fun RippleRing(startDelay: Int, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ripple")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            initialStartOffset = StartOffset(startDelay),
        ),
        label = "rippleProgress",
    )
    Canvas(modifier) {
        val r = size.minDimension / 2f
        drawCircle(
            color = color,
            radius = r * (0.55f + 0.95f * progress),
            alpha = (1f - progress) * 0.35f,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

/** 引导首屏的能力预告：课表 / 成绩 / 一卡通 / 开水。 */
@Composable
private fun FeatureRow() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeatureCell(HugeIcons.Calendar01, "课表")
        Spacer(Modifier.width(10.dp))
        FeatureCell(HugeIcons.ChartAverage, "成绩")
        Spacer(Modifier.width(10.dp))
        FeatureCell(HugeIcons.CreditCard, "一卡通")
        Spacer(Modifier.width(10.dp))
        FeatureCell(HugeIcons.Droplet, "开水")
    }
}

@Composable
private fun FeatureCell(icon: ImageVector, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
            .padding(vertical = 12.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
    }
}

// ---------------------------------------------------------------- 学校统一认证

@Composable
private fun JwStep(
    cas: CasSession,
    vault: CredentialVault,
    scope: CoroutineScope,
    onLaunchBgSync: () -> Unit,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<NoticeFeedback?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }
    val usernameFocus = remember { FocusRequester() }
    FocusOnEnter(usernameFocus)
    val successHaptic = rememberSuccessHaptic()

    fun submit() {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            notice = NoticeFeedback("请填入学号和统一认证密码", NoticeTone.Warning)
            errorTick++
            return
        }
        busy = true
        scope.launch {
            // 用户重新输入 = 重新开始计数：先清零闸门，避免上一次的失败次数把这次顶掉
            cas.onCredentialsUpdated()
            val result = try {
                cas.tryLogin(user, password)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 窗口销毁（用户退出引导）时取消必须原样抛，别把登录链拖完
                throw e
            } catch (e: Exception) {
                CasEnsureResult.Failed(e.message ?: "登录异常")
            }
            busy = false
            when (result) {
                is CasEnsureResult.Ready -> {
                    vault.saveCas(user, password)
                    // 顺手补学籍卡的姓名 / 班级（DESIGN §3.3）：走到「完成」页时「我的」页
                    // 已经有名字和班级，不用先导一次成绩。失败静默——它是锦上添花，
                    // 不该挡住引导的下一步。
                    runCatching { Graph.profileSync(context).syncOnce(force = true) }
                    // 成绩 / 学业完成情况后台补抓：壳层 startBgSync（进度收敛到完成页状态行）
                    onLaunchBgSync()
                    // 成功反馈：对勾描边停留一拍再进下一步，别让「成了」一闪而过
                    successHaptic()
                    notice = null
                    successMessage = "统一认证已保存，学籍卡与成绩正在后台补抓"
                    delay(SUCCESS_HOLD_MS)
                    onDone()
                }
                CasEnsureResult.Suspended -> {
                    notice = NoticeFeedback(
                        "密码连续错误，已暂停自动登录。可以跳过这步，稍后在「我的」页重新填写。",
                        NoticeTone.Error,
                    )
                    errorTick++
                }
                CasEnsureResult.NoCredential -> {
                    notice = NoticeFeedback("请填入学号和密码", NoticeTone.Warning)
                    errorTick++
                }
                is CasEnsureResult.NeedsManualLogin -> {
                    notice = NoticeFeedback(
                        "${result.message}。请跳过这步，导入课表时在页面上手动登录一次即可。",
                        NoticeTone.Warning,
                    )
                    errorTick++
                }
                is CasEnsureResult.Failed -> {
                    notice = NoticeFeedback(result.message, NoticeTone.Error)
                    errorTick++
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            StaggerIn(0) {
                StepHeader(
                    icon = HugeIcons.GraduationCap,
                    title = Step.Jw.title,
                    desc = Step.Jw.desc,
                    done = successMessage != null,
                )
            }
            Spacer(Modifier.height(14.dp))
            StaggerIn(1) {
                WhyCard(
                    question = "为什么要保存密码？",
                    body = "保存后，登录状态过期时 App 会自己重新登录，不用你再输一遍。" +
                        "密码加密存放在手机本机（Android Keystore），不进云备份、不上传任何服务器，" +
                        "随时可以在「我的」页退出登录并清除。不保存的话，每次进教务都要手动登录。",
                )
            }
            Spacer(Modifier.height(12.dp))
            StaggerIn(2) {
                TrustBar("密码加密存在本机（Android Keystore），仅用于学校系统登录；" +
                    "这是全应用唯一会保存密码的地方。")
            }
            Spacer(Modifier.height(16.dp))
            StaggerIn(3) {
                ShakeBox(tick = errorTick) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OnboardingTextField(
                            label = "学号",
                            value = username,
                            onValueChange = { username = it.trim() },
                            focusRequester = usernameFocus,
                        )
                        PasswordField(
                            label = "统一认证密码",
                            value = password,
                            onValueChange = { password = it },
                        )
                        NoticeSlot(notice, successMessage)
                    }
                }
            }
        }
        StaggerIn(4) {
            StepActions(
                primaryText = "验证并保存",
                busy = busy,
                busyText = "正在验证…",
                enabled = !busy && successMessage == null,
                onPrimary = { submit() },
                skipText = "先跳过，导入课表时再手动登录",
                onSkip = onSkip,
            )
        }
    }
}

// ---------------------------------------------------------------- 一卡通 · 电费

@Composable
private fun YktStep(
    vault: CredentialVault,
    scope: CoroutineScope,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    var username by remember { mutableStateOf(vault.readCas()?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<NoticeFeedback?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }
    val usernameFocus = remember { FocusRequester() }
    FocusOnEnter(usernameFocus)
    val successHaptic = rememberSuccessHaptic()

    fun submit() {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            notice = NoticeFeedback("请填入学号和查询密码", NoticeTone.Warning)
            errorTick++
            return
        }
        busy = true
        scope.launch {
            try {
                // 与「我的 → 校园卡」开启流程同一序列：先真登一次，再查一次账户
                Graph.yktRepository(context).loginForToken(user, password)
                Graph.yktCredentialStore(context).save(user, password)
                Graph.displayPrefs(context).setCampusCardEnabled(true)
                busy = false
                successHaptic()
                notice = null
                successMessage = "一卡通已开启，余额与寝室电费可用"
                delay(SUCCESS_HOLD_MS)
                onDone()
            } catch (e: YktException.NeedCaptcha) {
                busy = false
                notice = NoticeFeedback(
                    "平台要求图形验证码：请先在浏览器里登录一次一卡通，再回来重试",
                    NoticeTone.Error,
                )
                errorTick++
            } catch (e: YktException.MultiAccount) {
                busy = false
                notice = NoticeFeedback("该学号绑定了多个账号，请先在网页端选择默认账号", NoticeTone.Error)
                errorTick++
            } catch (e: Exception) {
                busy = false
                notice = NoticeFeedback(e.message ?: "登录失败，请检查账号密码", NoticeTone.Error)
                errorTick++
            }
        }
    }

    // 与「我的 → 校园卡」设置页同一句话：账号是学号，默认密码通常为身份证后六位。
    // 只支持数字是平台约束（一卡通登录走服务端下发的数字安全键盘，见 §4.19）。
    val passwordHint = buildAnnotatedString {
        append("账号为学号，")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append("默认密码通常为身份证后六位")
        }
        append("（仅支持数字密码）。改过就用改后的。")
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            StaggerIn(0) {
                StepHeader(
                    icon = HugeIcons.CreditCard,
                    title = Step.Ykt.title,
                    desc = Step.Ykt.desc,
                    done = successMessage != null,
                )
            }
            Spacer(Modifier.height(14.dp))
            StaggerIn(1) {
                WhyCard(
                    question = "什么是「查询密码」？",
                    body = "一卡通缴费平台与学校统一认证是两套密码，互不通用。" +
                        "这份凭证只用于查询余额、消费流水和寝室电费；付款码只在你主动打开时才刷新。",
                )
            }
            Spacer(Modifier.height(16.dp))
            StaggerIn(2) {
                ShakeBox(tick = errorTick) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OnboardingTextField(
                            label = "学号",
                            value = username,
                            onValueChange = { username = it.trim() },
                            focusRequester = usernameFocus,
                        )
                        PasswordField(
                            label = "查询密码",
                            value = password,
                            onValueChange = { password = it.filter { c -> c.isDigit() } },
                            numeric = true,
                        )
                        Text(
                            passwordHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        NoticeSlot(notice, successMessage)
                    }
                }
            }
        }
        StaggerIn(3) {
            StepActions(
                primaryText = "验证并开启",
                busy = busy,
                busyText = "正在验证…",
                enabled = !busy && successMessage == null,
                onPrimary = { submit() },
                skipText = "先跳过，之后在「我的」页补上",
                onSkip = onSkip,
            )
        }
    }
}

// ---------------------------------------------------------------- 胖乖生活

@Composable
private fun QiekjStep(
    scope: CoroutineScope,
    codeSentAt: Long,
    onCodeSent: (Long) -> Unit,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { Graph.qiekj(context) }
    var phone by remember { mutableStateOf(repo.readPhone().orEmpty()) }
    var code by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf("") }
    // 两种登录方式二选一：短信验证码（默认）/ 粘贴已有 Token。
    var tokenMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<NoticeFeedback?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }
    // 发送验证码的冷却起点提到引导级（参数传入）：跨步返回冷却不丢。
    // 倒计时按 codeSentAt 派生，发送成功后 onCodeSent 更新起点即自动重启。
    var cooldownLeft by remember { mutableIntStateOf(0) }
    LaunchedEffect(codeSentAt) {
        while (true) {
            val elapsed = System.currentTimeMillis() - codeSentAt
            cooldownLeft = ((60_000 - elapsed + 999) / 1000).toInt().coerceAtLeast(0)
            if (cooldownLeft == 0) break
            delay(1_000)
        }
    }
    val phoneFocus = remember { FocusRequester() }
    val tokenFocus = remember { FocusRequester() }
    // 进屏与切登录方式都把焦点带过去（等转场落定再弹键盘）
    LaunchedEffect(tokenMode) {
        delay(430)
        runCatching { (if (tokenMode) tokenFocus else phoneFocus).requestFocus() }
    }
    val successHaptic = rememberSuccessHaptic()

    fun sendCode() {
        val p = phone.trim()
        val elapsed = System.currentTimeMillis() - codeSentAt
        if (elapsed < 60_000) {
            notice = NoticeFeedback(
                "验证码已发送，请 ${(60 - elapsed / 1000).toInt()} 秒后再试",
                NoticeTone.Warning,
            )
            errorTick++
            return
        }
        if (p.length != 11) {
            notice = NoticeFeedback("请输入 11 位手机号", NoticeTone.Warning)
            errorTick++
            return
        }
        sending = true
        scope.launch {
            try {
                repo.sendCode(p)
                onCodeSent(System.currentTimeMillis())
                notice = NoticeFeedback("验证码已发送", NoticeTone.Success)
            } catch (e: Exception) {
                notice = NoticeFeedback(e.message ?: "验证码发送失败", NoticeTone.Error)
                errorTick++
            }
            sending = false
        }
    }

    fun submitPhone() {
        val p = phone.trim()
        if (p.length != 11 || code.isBlank()) {
            notice = NoticeFeedback("请填手机号和验证码", NoticeTone.Warning)
            errorTick++
            return
        }
        busy = true
        scope.launch {
            try {
                repo.login(p, code)
                // 记住手机号，下次进来直接填好（与开水页登录成功后的处理一致）
                repo.savePhone(p)
                repo.queryBalance()
                busy = false
                successHaptic()
                notice = null
                successMessage = "胖乖生活已登录，今日页开水卡可用"
                delay(SUCCESS_HOLD_MS)
                onDone()
            } catch (e: Exception) {
                busy = false
                notice = NoticeFeedback(e.message ?: "登录失败", NoticeTone.Error)
                errorTick++
            }
        }
    }

    fun submitToken() {
        val token = tokenInput.trim()
        if (token.isBlank()) {
            notice = NoticeFeedback("请输入 Token", NoticeTone.Warning)
            errorTick++
            return
        }
        busy = true
        scope.launch {
            try {
                // 与开水页同一序列：先查一次余额验 token，验过才落盘——
                // 落盘会翻转仓库的登录态，无效 token 不该先落上再回滚（今日页开水卡会闪跳）
                repo.validateToken(token)
                repo.saveToken(token)
                busy = false
                successHaptic()
                notice = null
                successMessage = "Token 已验证并保存"
                delay(SUCCESS_HOLD_MS)
                onDone()
            } catch (e: Exception) {
                // 校验没过什么都没存，后续请求不会拿着无效 token 一路 401
                busy = false
                notice = NoticeFeedback(e.message ?: "Token 无效或已过期", NoticeTone.Error)
                errorTick++
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            StaggerIn(0) {
                StepHeader(
                    icon = HugeIcons.Droplet,
                    title = Step.Qiekj.title,
                    desc = Step.Qiekj.desc,
                    done = successMessage != null,
                )
            }
            Spacer(Modifier.height(16.dp))
            StaggerIn(1) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !tokenMode,
                        onClick = { tokenMode = false; notice = null; successMessage = null },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("短信验证码") }
                    SegmentedButton(
                        selected = tokenMode,
                        onClick = { tokenMode = true; notice = null; successMessage = null },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("粘贴 Token") }
                }
            }
            Spacer(Modifier.height(14.dp))
            StaggerIn(2) {
                ShakeBox(tick = errorTick) {
                    AnimatedContent(
                        targetState = tokenMode,
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(160)) },
                        label = "qiekjMode",
                    ) { isToken ->
                        if (isToken) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    "粘贴已从其他渠道拿到的 Token 即可，不用再收短信。手机号登录会使旧 Token 失效；两者选一个就行。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                                PasswordField(
                                    label = "Token",
                                    value = tokenInput,
                                    onValueChange = { tokenInput = it },
                                    focusRequester = tokenFocus,
                                )
                                NoticeSlot(notice, successMessage)
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OnboardingTextField(
                                    label = "手机号",
                                    value = phone,
                                    onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11) },
                                    keyboardType = KeyboardType.Phone,
                                    focusRequester = phoneFocus,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = code,
                                        onValueChange = { code = it.filter { c -> c.isDigit() } },
                                        label = { Text("验证码") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = { sendCode() },
                                        enabled = !sending && cooldownLeft == 0 && phone.length == 11,
                                    ) {
                                        Text(
                                            when {
                                                sending -> "发送中"
                                                cooldownLeft > 0 -> "重发 ${cooldownLeft}s"
                                                else -> "发送验证码"
                                            },
                                        )
                                    }
                                }
                                NoticeSlot(notice, successMessage)
                            }
                        }
                    }
                }
            }
        }
        StaggerIn(3) {
            StepActions(
                primaryText = if (tokenMode) "验证并保存" else "登录并保存",
                busy = busy,
                busyText = if (tokenMode) "正在校验…" else "正在登录…",
                enabled = !busy && successMessage == null,
                onPrimary = { if (tokenMode) submitToken() else submitPhone() },
                skipText = "先跳过，开水时再登录",
                onSkip = onSkip,
            )
        }
    }
}

// ---------------------------------------------------------------- 趣智校园

/** 趣智校园那一步的登录方式（DESIGN §3.16）。 */
private enum class QzxyMode { Password, Sms, Session }

@Composable
private fun QzxyStep(
    scope: CoroutineScope,
    codeSentAt: Long,
    onCodeSent: (Long) -> Unit,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { Graph.qzxy(context) }
    // 已经登录过（比如重复进入引导）就把手机号带出来，省得再敲一遍
    var phone by remember { mutableStateOf(repo.localSession()?.telephone.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sessionInput by remember { mutableStateOf("") }
    // 密码是默认项：验证码要等短信，会话串要先有另一台登录过的设备
    var mode by remember { mutableStateOf(QzxyMode.Password) }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<NoticeFeedback?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }
    // 发送验证码的冷却起点提到引导级（参数传入）：跨步返回冷却不丢。
    // 倒计时按 codeSentAt 派生，发送成功后 onCodeSent 更新起点即自动重启。
    var cooldownLeft by remember { mutableIntStateOf(0) }
    LaunchedEffect(codeSentAt) {
        while (true) {
            val elapsed = System.currentTimeMillis() - codeSentAt
            cooldownLeft = ((60_000 - elapsed + 999) / 1000).toInt().coerceAtLeast(0)
            if (cooldownLeft == 0) break
            delay(1_000)
        }
    }
    val phoneFocus = remember { FocusRequester() }
    val sessionFocus = remember { FocusRequester() }
    // 进屏与切登录方式都把焦点带过去（等转场落定再弹键盘）
    LaunchedEffect(mode) {
        delay(430)
        runCatching {
            when (mode) {
                QzxyMode.Session -> sessionFocus.requestFocus()
                else -> phoneFocus.requestFocus()
            }
        }
    }
    val successHaptic = rememberSuccessHaptic()

    fun sendCode() {
        val p = phone.trim()
        val elapsed = System.currentTimeMillis() - codeSentAt
        if (elapsed < 60_000) {
            notice = NoticeFeedback(
                "验证码已发送，请 ${(60 - elapsed / 1000).toInt()} 秒后再试",
                NoticeTone.Warning,
            )
            errorTick++
            return
        }
        if (p.length != 11) {
            notice = NoticeFeedback("请输入 11 位手机号", NoticeTone.Warning)
            errorTick++
            return
        }
        sending = true
        scope.launch {
            try {
                repo.sendCode(p)
                onCodeSent(System.currentTimeMillis())
                notice = NoticeFeedback("验证码已发送", NoticeTone.Success)
            } catch (e: Exception) {
                notice = NoticeFeedback(e.message ?: "验证码发送失败", NoticeTone.Error)
                errorTick++
            }
            sending = false
        }
    }

    fun submitPhone() {
        val p = phone.trim()
        if (p.length != 11) {
            notice = NoticeFeedback("请输入 11 位手机号", NoticeTone.Warning)
            errorTick++
            return
        }
        if (mode == QzxyMode.Sms && code.isBlank()) {
            notice = NoticeFeedback("请填短信验证码", NoticeTone.Warning)
            errorTick++
            return
        }
        if (mode == QzxyMode.Password && password.isBlank()) {
            notice = NoticeFeedback("请输入密码", NoticeTone.Warning)
            errorTick++
            return
        }
        busy = true
        scope.launch {
            try {
                if (mode == QzxyMode.Sms) repo.loginBySms(p, code) else repo.loginByPassword(p, password)
                busy = false
                successHaptic()
                notice = null
                successMessage = "趣智校园已登录，今日页可开热水"
                delay(SUCCESS_HOLD_MS)
                onDone()
            } catch (e: Exception) {
                busy = false
                notice = NoticeFeedback(e.message ?: "登录失败", NoticeTone.Error)
                errorTick++
            }
        }
    }

    /**
     * 用粘贴的会话串登录，与趣智校园页同一套口径（那边见 `QzxyViewModel.loginWithSession`）。
     *
     * **先验后存**：先拿候选会话调一次只读接口，通了才落盘。否则会留下一个
     * 「已登录但什么都查不到」的会话，用户分不清是会话错还是网络问题。
     */
    fun submitSession() {
        val text = sessionInput.trim()
        if (text.isEmpty()) {
            notice = NoticeFeedback("请先粘贴会话串", NoticeTone.Warning)
            errorTick++
            return
        }
        val candidate = QzxySessionLink.parse(text)
        if (candidate == null) {
            val missing = QzxySessionLink.missingField(text) ?: "loginCode"
            notice = NoticeFeedback(
                "没认出会话串：缺少 $missing。把登录响应里的 loginCode、projectId、" +
                    "accountId、userId、telephone 一起复制过来",
                NoticeTone.Error,
            )
            errorTick++
            return
        }
        busy = true
        scope.launch {
            try {
                repo.validateSession(candidate)
                repo.adoptSession(candidate)
                busy = false
                successHaptic()
                notice = null
                successMessage = "会话串已验证并接管"
                delay(SUCCESS_HOLD_MS)
                onDone()
            } catch (e: Exception) {
                busy = false
                notice = NoticeFeedback(e.message ?: "会话无效或已过期", NoticeTone.Error)
                errorTick++
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            StaggerIn(0) {
                StepHeader(
                    icon = HugeIcons.ShowerHead,
                    title = Step.Qzxy.title,
                    desc = Step.Qzxy.desc,
                    done = successMessage != null,
                )
            }
            Spacer(Modifier.height(16.dp))
            StaggerIn(1) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = mode == QzxyMode.Password,
                        onClick = { mode = QzxyMode.Password; notice = null; successMessage = null },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    ) { Text("密码") }
                    SegmentedButton(
                        selected = mode == QzxyMode.Sms,
                        onClick = { mode = QzxyMode.Sms; notice = null; successMessage = null },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    ) { Text("短信验证码") }
                    SegmentedButton(
                        selected = mode == QzxyMode.Session,
                        onClick = { mode = QzxyMode.Session; notice = null; successMessage = null },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    ) { Text("会话串") }
                }
            }
            Spacer(Modifier.height(14.dp))
            StaggerIn(2) {
                ShakeBox(tick = errorTick) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (mode == QzxyMode.Session) {
                            var sessionVisible by remember { mutableStateOf(false) }
                            OutlinedTextField(
                                value = sessionInput,
                                onValueChange = { sessionInput = it },
                                label = { Text("会话串") },
                                minLines = 3,
                                maxLines = 5,
                                visualTransformation = if (sessionVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { sessionVisible = !sessionVisible }, modifier = Modifier.size(40.dp)) {
                                        Icon(
                                            if (sessionVisible) HugeIcons.EyeOff else HugeIcons.Eye,
                                            contentDescription = if (sessionVisible) "隐藏会话串" else "显示会话串",
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                            modifier = Modifier.size(19.dp),
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(sessionFocus),
                            )
                            Text(
                                "从已登录的设备导出，或把登录响应里的 loginCode、projectId、" +
                                    "accountId、userId、telephone 一起复制过来。这串等于账号通行证，别发给别人。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        } else {
                            OnboardingTextField(
                                label = "手机号",
                                value = phone,
                                onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11) },
                                keyboardType = KeyboardType.Phone,
                                focusRequester = phoneFocus,
                            )
                            if (mode == QzxyMode.Sms) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = code,
                                        onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                                        label = { Text("短信验证码") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = { sendCode() },
                                        enabled = !sending && cooldownLeft == 0 && phone.length == 11,
                                    ) {
                                        Text(
                                            when {
                                                sending -> "发送中"
                                                cooldownLeft > 0 -> "重发 ${cooldownLeft}s"
                                                else -> "发送验证码"
                                            },
                                        )
                                    }
                                }
                            } else {
                                PasswordField(
                                    label = "密码",
                                    value = password,
                                    onValueChange = { password = it },
                                )
                            }
                        }
                        NoticeSlot(notice, successMessage)
                    }
                }
            }
        }
        StaggerIn(3) {
            StepActions(
                primaryText = "登录并保存",
                busy = busy,
                busyText = "正在登录…",
                enabled = !busy && successMessage == null,
                onPrimary = {
                    when (mode) {
                        QzxyMode.Session -> submitSession()
                        else -> submitPhone()
                    }
                },
                skipText = "先跳过，洗澡时再登录",
                onSkip = onSkip,
            )
        }
    }
}

// ---------------------------------------------------------------- 完成

@Composable
private fun DoneStep(
    jwOk: Boolean,
    yktOk: Boolean,
    qiekjOk: Boolean,
    qzxyOk: Boolean,
    bgSyncPhase: BgSyncPhase,
    onFinish: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            StaggerIn(0) { DoneHero() }
            Spacer(Modifier.height(24.dp))
            StaggerIn(1) {
                StatusRow(
                    icon = HugeIcons.GraduationCap,
                    name = "学校统一认证",
                    desc = "教务 · 学工 · 成绩单",
                    ok = jwOk,
                    missText = "未配置（导入课表时手动登录）",
                )
            }
            StaggerIn(2) {
                StatusRow(
                    icon = HugeIcons.CreditCard,
                    name = "一卡通 · 电费",
                    desc = "余额 · 付款码 · 流水",
                    ok = yktOk,
                    missText = "未配置",
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
            StaggerIn(3) {
                StatusRow(
                    icon = HugeIcons.Droplet,
                    name = "胖乖生活",
                    desc = "开水房账号",
                    ok = qiekjOk,
                    missText = "未配置",
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
            StaggerIn(4) {
                StatusRow(
                    icon = HugeIcons.ShowerHead,
                    name = "趣智校园",
                    desc = "洗澡开热水",
                    ok = qzxyOk,
                    missText = "未配置（洗澡开热水时再登录）",
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
            if (jwOk) {
                StaggerIn(5) { BgSyncLine(bgSyncPhase) }
            }
        }
        StepActions(
            primaryText = "进入水贝贝",
            busy = false,
            busyText = "",
            enabled = true,
            onPrimary = onFinish,
            skipText = null,
            onSkip = null,
        )
    }
}

/**
 * 完成页的补抓状态行：Running 转圈、DoneOk 对勾、DoneFailed 提示去「我的 → 学习」重试。
 * [BgSyncPhase.Idle] 不渲染（理论上不会出现：外层已用 jwOk 挡过）。
 */
@Composable
private fun BgSyncLine(phase: BgSyncPhase) {
    when (phase) {
        BgSyncPhase.Idle -> return
        BgSyncPhase.Running -> CenteredNote {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            NoteText("成绩与学业完成情况正在后台自动同步，不用等")
        }
        BgSyncPhase.DoneOk -> CenteredNote {
            Icon(
                HugeIcons.Tick02,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.success,
                modifier = Modifier.size(14.dp),
            )
            NoteText("成绩与学业完成情况已同步到「我的 → 学习」")
        }
        BgSyncPhase.DoneFailed -> CenteredNote {
            Icon(
                HugeIcons.InformationCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.size(14.dp),
            )
            NoteText("后台同步没成功（可能是网络），之后进「我的 → 学习」会自动补抓")
        }
    }
}

@Composable
private fun CenteredNote(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun NoteText(text: String) {
    Spacer(Modifier.width(7.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
    )
}

/** 完成页头部：一次性涟漪 + 大对勾描边 + 标题。 */
@Composable
private fun DoneHero() {
    val success = MaterialTheme.semanticColors.success
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            repeat(3) { i -> OneShotRing(delayMs = i * 350, color = success, modifier = Modifier.fillMaxSize()) }
            DoneCheckCanvas(Modifier.size(96.dp), success)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "配置完成",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            Step.Done.desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

/** 完成页的一次性涟漪：进入页面后从中心扩散一圈，播完即止。 */
@Composable
private fun OneShotRing(delayMs: Int, color: Color, modifier: Modifier = Modifier) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        started = true
    }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(1100, easing = LinearOutSlowInEasing),
        label = "doneRing",
    )
    Canvas(modifier) {
        if (progress > 0f && progress < 1f) {
            val r = size.minDimension / 2f
            drawCircle(
                color = color,
                radius = r * (0.4f + 1.2f * progress),
                alpha = (1f - progress) * 0.5f,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/** 完成页大对勾：淡底圆 + 外圈描边 + 对勾描边，两段连播。 */
@Composable
private fun DoneCheckCanvas(modifier: Modifier, color: Color) {
    val circle = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        circle.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        check.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        drawCircle(color = color.copy(alpha = 0.14f))
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * circle.value,
            useCenter = false,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
        )
        if (check.value > 0f) {
            val path = Path().apply {
                moveTo(size.width * 0.32f, size.height * 0.52f)
                lineTo(size.width * 0.45f, size.height * 0.65f)
                lineTo(size.width * 0.69f, size.height * 0.38f)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val segment = Path()
            measure.getSegment(0f, measure.length * check.value, segment, true)
            drawPath(
                segment,
                color,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/** 完成页的状态行：图标 + 名称 + 状态词，配色即状态。 */
@Composable
private fun StatusRow(
    icon: ImageVector,
    name: String,
    desc: String,
    ok: Boolean,
    missText: String,
    modifier: Modifier = Modifier,
) {
    val stateColor = if (ok) {
        MaterialTheme.semanticColors.success
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(stateColor),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            if (ok) "已配置" else missText,
            style = MaterialTheme.typography.labelMedium,
            color = stateColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
