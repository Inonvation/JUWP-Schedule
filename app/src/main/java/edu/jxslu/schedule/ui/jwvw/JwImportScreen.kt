package edu.jxslu.schedule.ui.jwvw

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.BuildConfig
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.jw.ExamScheduleParser
import edu.jxslu.schedule.data.jw.ImportParseResult
import edu.jxslu.schedule.data.jw.JwImportDiagnosis
import edu.jxslu.schedule.data.jw.JwSchedulePage
import edu.jxslu.schedule.data.jw.JwTeachingEval
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.jw.JwVpnDetector
import edu.jxslu.schedule.data.jw.JwAutoLogin
import edu.jxslu.schedule.data.jw.OneClickImport
import edu.jxslu.schedule.data.jw.QiangzhiScheduleParser
import edu.jxslu.schedule.data.jw.ScoreParser
import edu.jxslu.schedule.data.jw.SyjxScheduleParser
import edu.jxslu.schedule.data.jw.TermOption
import edu.jxslu.schedule.data.jw.unwrapJsString
import edu.jxslu.schedule.data.repo.TeachingCalendarSync
import edu.jxslu.schedule.data.repo.TextbookSync
import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.domain.ExamMapper
import edu.jxslu.schedule.domain.ExamMapper.ExamEntry
import edu.jxslu.schedule.domain.ScoreRecord
import edu.jxslu.schedule.data.session.CasEnsureResult
import edu.jxslu.schedule.data.session.CredentialVault
import edu.jxslu.schedule.data.session.NetworkHint
import edu.jxslu.schedule.data.session.SessionStatus
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.CasLoginDialog
import edu.jxslu.schedule.ui.common.ImportTargetDialogHost
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.resolveImportTarget
import edu.jxslu.schedule.ui.common.showNotice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 教务导入窗口的模式（DESIGN §4.14 / §4.15）。 */
enum class JwImportMode {
    /** 课表导入：理论 / 实验 / 考试安排，结果写进课表 Course。 */
    Schedule,
    /** 成绩导入：抓全部学期成绩，按学期替换入库（DESIGN §4.15）。 */
    Scores,
    /**
     * 选课（DESIGN §4.35）：登录后落到学生选课中心 `/jsxsd/xsxk/xklc_list`，
     * **只当浏览器用**——选课/退课操作由用户在教务页面上自己完成，App 不代选、
     * 不注入、不写库。没有导入动作，底部只给「回到选课中心」一个入口。
     */
    Selection,
    /**
     * 学生评教（DESIGN §4.44）：登录后落教务主页，注入「取 token 再跳」
     * （[JwTeachingEval]）到评教平台（`jiaowupj.juwp.edu.cn` 的 SPA）。
     * **只当浏览器用第二例**——评教全部由用户在平台上自己完成，App 不代评、
     * 不提交任何接口；没有导入动作，底部不出现任何按钮。
     */
    TeachingEval,
    /**
     * 只读网页核对：从各业务页顶部的「网页」图标进来，登录后直接打开目标页。
     * **只当浏览器用第三例**——没有导入动作、不写库，复用本窗口的电脑版预览、
     * 统一认证、返回语义与错误态。
     */
    Browse,
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JwImportScreen(
    onBack: () -> Unit,
    mode: JwImportMode = JwImportMode.Schedule,
    // 课表模式下开在考试安排查询页（DESIGN §4.33）。只改「登录后的自动落页」，
    // 不动抽取/写库任何一环；成绩模式忽略它。
    startAtExam: Boolean = false,
    // 选课模式下直达某个轮次（DESIGN §4.35）：轮次 id 过白名单后拼「进入选课」页；
    // 空/不合法 = 落选课中心列表。其余模式忽略它。
    selectionRoundId: String? = null,
    // Browse 模式：要打开的目标页与顶栏标题。
    browseUrl: String = "",
    browseTitle: String = "",
) {
    val context = LocalContext.current
    val repo = remember { Graph.repository(context) }
    val scoreRepo = remember { Graph.scoreRepository(context) }
    val prefs = remember { Graph.displayPrefs(context) }
    val scope = rememberCoroutineScope()
    // 会话层单例（DESIGN §4.27）：打开前准备、登录后回灌都走它
    val cas = remember { Graph.casSession(context) }
    // 存下来的统一认证凭证：落在登录页时用它自动登一次（读一次就够，别在回调里重复读加密存储）
    var savedCas by remember { mutableStateOf(Graph.credentialVault(context).readCas()) }
    /**
     * 统一认证登录弹窗（DESIGN §4.27，2026-09-28 用户提议的前置口径）：
     *
     * 两层闭环：① 进窗时本地没有凭据 → 先弹 [edu.jxslu.schedule.ui.common.CasLoginDialog]
     * （common 共享组件），输入学号密码验证落库，之后 WebView 落 CAS 页自动填表提交，
     * 全程免手输；② 用户跳过 ①、在网页里手登成功 → 手登成功处再给一次补存机会（同组件）。
     * 「先跳过」只挡本窗口，下个导入窗口还会再问。
     */
    var showCasLoginDialog by remember { mutableStateOf(false) }
    /** 本窗口已拒绝过登录弹窗：不再重复弹，打扰比缺失更糟。 */
    var casLoginDeclined by remember { mutableStateOf(false) }
    /** 手登成功后的补存弹窗（同一组件，文案不同）；进窗时跳过了前置层才会用到。 */
    var showSaveCredentialPrompt by remember { mutableStateOf(false) }
    /** 自动登录只做一次：失败就交给用户手登，反复试只会撞风控。 */
    var autoLoginTried by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    /**
     * 电脑版预览（DESIGN §4.4，2026-10-08）：默认开、顶栏可切、选择落盘。
     *
     * 开 = 教务业务页按桌面宽度排版（[JwWebFit.DESKTOP_JS]）；关 = 与引入它之前逐字一致。
     * **登录 / SSO 页不受影响**（判据在 `JwWebFit.fitScript`：那几页本来就没 viewport meta，
     * 自动填表与会话链都挂在它们身上）。
     */
    val desktopMode by prefs.jwDesktopMode.collectAsStateWithLifecycle(initialValue = true)

    /**
     * 首屏 URL。教务目标仍从预热入口进（先写 `bzb_njw`，避免第一次 sso.jsp 的 500）；
     * 非教务目标（学工 / 签章等）直接开目标页，让服务端自己的 CAS 回跳接管。
     */
    val startUrl: String =
        if (mode == JwImportMode.Browse && browseUrl.isNotBlank() && !JwUrls.isJwHost(browseUrl)) {
            browseUrl
        } else {
            JwUrls.SSO_WARMUP
        }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf("学校统一身份认证") }
    var currentUrl by remember { mutableStateOf(startUrl) }
    // 弹窗直接持有解析草稿：courses 之外还要带上页面学期（term）与识别分项供确认弹窗展示
    var importDraft by remember { mutableStateOf<ImportDraft?>(null) }
    /** 弹窗里切换学期后的重爬进行中：期间「导入」禁用、学期行不可点。 */
    var termSwitching by remember { mutableStateOf(false) }
    /** 一键导入的页面加载闸门：`loadUrl` 之后等目标页面加载完（或失败），见 [PageLoadGate]。 */
    var pageLoadGate by remember { mutableStateOf<PageLoadGate?>(null) }
    /** 考试导入的原始行 + 学期：确认弹窗选定目标课后按**目标课表**的开学日重映射（DESIGN §4.14）。 */
    var pendingExamImport by remember { mutableStateOf<Pair<List<ExamEntry>, String>?>(null) }
    /**
     * 「教务这一学期没有考试安排」的弹窗入参（学期号）。
     *
     * 一键式口径（2026-09-30 用户要求，与导入课表同构）：落到考试安排查询页就自动抓，
     * 抓不到安排**也要给一个明确的终态**——只留状态条会被当成「什么都没发生」。
     */
    var emptyExamTerm by remember { mutableStateOf<String?>(null) }
    /** 成绩模式：待确认的导入（学期 → 条数），确认后按学期替换入库。 */
    var pendingScores by remember { mutableStateOf<List<Pair<String, List<edu.jxslu.schedule.domain.ScoreRecord>>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pageState by remember { mutableStateOf<PageState>(PageState.Loading) }
    var statusNote by remember { mutableStateOf("正在打开学校统一身份认证登录…") }
    // 最近一次同源 fetch 是不是因为「会话被顶」失败的：调用方的超时分支要先看它，
    // 否则会用「请求超时」把已经报出来的会话诊断盖掉（见 fetchJsonInWebView）
    var lastFetchConflict by remember { mutableStateOf(false) }
    var autoNavPending by remember { mutableStateOf(false) }
    /**
     * 自动落页已经跳过一次（成功或放弃）：之后再回到教务主页不再自动跳，
     * 免得抢用户自己的导航（原来的语义藏在「autoNavPending 成功后不复位」里，显式写出来）。
     */
    var autoNavDone by remember { mutableStateOf(false) }
    /**
     * 评教注入的「尝试代次」（DESIGN §4.44）：每次注入自增，15s 看门狗只对同代次生效——
     * 刷新重试会开启新代次，旧代次的看门狗到点即废（否则重试后它会给一条与事实相反的
     * 失败提示，2026-10-07 复查修）。
     */
    var evalAttempt by remember { mutableStateOf(0) }
    /**
     * 登录落地（教务主页）后自动打开的目标页与提示文案，按模式固定。
     * 触发在 [startAutoNavOnLanding]，`onPageCommitVisible` 与 `onPageFinished` 两个入口
     * 共用这一份口径。
     *
     * 注意：两张课表的 URL 都含 "xskb"（理论 xskb_list、实验 toXskb），不能用子串判断。
     */
    val autoNavTarget: String = when (mode) {
        JwImportMode.Scores -> JwUrls.SCORE_FRM
        JwImportMode.Selection -> selectionRoundId
            ?.let { JwUrls.selectionRoundUrl(it) }
            ?: JwUrls.SELECTION_CENTER
        // 评教没有静态目标页：落主页后由 [startAutoNav] 注入「取 token 再跳」脚本
        // （DESIGN §4.44）。空串只是占位，走不到 loadUrl
        JwImportMode.TeachingEval -> ""
        // 从考试页进来的窗口（DESIGN §4.33）：落理论课表页会触发 autoImportOnTheoryReady，
        // 一键导入理论+实验跑起来，跟入口写的「导入考试安排」不是一回事
        JwImportMode.Schedule -> if (startAtExam) JwUrls.EXAM_QUERY else JwUrls.SCHEDULE_LIST
        JwImportMode.Browse -> browseUrl
    }
    val autoNavNote: String = when (mode) {
        JwImportMode.Scores -> "已登录教务主页，正在打开成绩查询页…"
        JwImportMode.Selection -> "已登录教务主页，正在打开选课中心…"
        JwImportMode.TeachingEval -> "已登录教务主页，正在打开学生评教…"
        JwImportMode.Schedule -> if (startAtExam) {
            "已登录教务主页，正在打开考试安排查询…"
        } else {
            "已登录教务主页，正在打开学期理论课表…"
        }
        JwImportMode.Browse -> "已登录教务主页，正在打开${browseTitle.ifBlank { "教务网页" }}…"
    }
    /**
     * 一键导入是否已经跑过（自动触发只做第一次，见 [autoImportOnTheoryReady] /
     * [autoImportOnScoreReady]；两种模式各开一个窗口，共用这一个标记不会互相干扰）。
     * 手动点按钮也算用掉：导过一次之后再回到课表页 / 成绩页，不该自己又跑一轮。
     */
    var autoImportTried by remember { mutableStateOf(false) }
    // 主 frame 最近一次失败的 URL，由失败回调写入、由 URL 相同的 onPageFinished 消费。
    //
    // 为什么必须按 URL 匹配 + 一次性消费（2026-09-18 手机日志实证）：500 响应体与
    // Chromium 失败页都算「加载完成」，失败后 onPageFinished 照常到达，会把错误态
    // 洗成 Ready；而回调顺序是 onReceivedHttpError → onPageStarted → onPageFinished
    // （**错误先于 onPageStarted**，与直觉相反）。因此：
    // - 不能改在 onPageStarted 里清标记（实测那样等于守卫被下一次回调拆掉，
    //   错误浮层被洗掉、状态条写成「已登录教务…」）；
    // - 也不能简单用「非空即拦截」，否则同名 URL 重试成功会被上一轮的失败拦住；
    // - 按 URL 相等消费，既能顶住重复失败轮次，也不误伤新导航/重试。
    var lastFailedUrl by remember { mutableStateOf<String?>(null) }
    // 自动重试只做一次：sso.jsp 的 500 是自愈型（重走预热入口即通过），
    // 但真故障时不能无限重试打服务端。
    var autoRetryUsed by remember { mutableStateOf(false) }

    /**
     * 返回：**先关教务页上的弹层，再退页，退无可退才关窗口**（DESIGN §4.4.4）。
     *
     * 三件事各自的根因：
     * ① 页面弹层（layui layer）是页面里的 DOM 覆盖层，返回直接退页时用户看到的是
     *    「弹窗没关、背后的页面却动了」——所以先点弹层自己的关闭按钮（与手点同一入口），
     *    关掉了就到此为止（用户报的正是这条）；
     * ② 历史里混着重定向留下的**同址条目**与 **CAS/SSO 认证链**，退过去一个长得一模一样、
     *    一个是登录页，用户看到的就是「按返回没有用」——判据见 [JwWebBack.backDelta]（纯函数、有单测）；
     * ③ 都没得退就 `onBack()` 关窗口（等于系统默认的退窗语义）。
     *
     * **只给系统返回（手势 / 三键）用**（2026-10-08 用户口径）：系统返回手势默认直接 popBackStack，
     * 会把 WebView 的历史连同登录进度一起丢掉，所以必须接管；而**顶栏左上角那枚箭头改成直接退出窗口**
     * ——它管"离开这个窗口"，逐页回退交给系统返回（两边语义分开后，想一步退出的人有明确出口，
     * 想回上一页的人用系统返回，不会互相打架）。
     */
    fun backOrClose() {
        val wv = webView ?: return onBack()
        wv.evaluateJavascript(JwWebFit.CLOSE_TOP_LAYER_JS) { raw ->
            // 关掉了一个页面弹层 → 这一下返回就用掉了，别顺带退页
            if (raw?.trim()?.removeSurrounding("\"") == "1") return@evaluateJavascript
            val delta = JwWebBack.backDelta(wv.copyBackForwardList())
            if (delta != null) wv.goBackOrForward(delta) else onBack()
        }
    }
    BackHandler(enabled = webView != null) { backOrClose() }

    // 前置登录（DESIGN §4.27，2026-09-28）：本地没有凭据时进窗先问一次。
    // 用户在引导里跳过教务、或从没填过密码的，从此不必先撞一次 CAS 登录页才知道要存。
    LaunchedEffect(Unit) {
        if (savedCas == null && !casLoginDeclined) showCasLoginDialog = true
    }

    /**
     * 顶栏切「电脑版 / 手机版」：**当场重注入，不重载页面**。
     *
     * 为什么不重载：实测改 viewport meta 会触发重新布局（2026-10-08 模拟器实测
     * `iw=412 → 980`），重载只会白等一次网络；而选课中心的列表与筛选状态在 iframe 里，
     * 重载会把它清掉——用户要的恰恰是"看一眼另一种口径再决定"。
     *
     * 首次组合不重跑（[lastDesktopMode] 记着初值）：进窗时页面还没开始加载，
     * 每页的注入由 [applyPageFit] 负责，这里只处理"用户真的切了一下"。
     */
    var lastDesktopMode by remember { mutableStateOf(desktopMode) }
    LaunchedEffect(desktopMode) {
        if (desktopMode == lastDesktopMode) return@LaunchedEffect
        lastDesktopMode = desktopMode
        webView?.evaluateJavascript(
            if (desktopMode) JwWebFit.DESKTOP_JS else JwWebFit.DESKTOP_REVERT_JS,
            null,
        )
    }

    /**
     * 统一失败出口：置错误浮层 + 状态条，并记下失败 URL。
     *
     * 记 URL 的用途见 [lastFailedUrl]：失败页的 `onPageFinished` 仍会到达，
     * 需要在那里认出「这次是失败」而不是把错误态洗成 Ready。
     * VPN 探测放在失败路径上做——正常路径不需要这个信息，也不该有额外开销。
     *
     * 认证链上的 500 会先**自动重试一次**：实测该 500 是自愈型（响应同时写下
     * `bzb_njw`，重新走一遍预热入口即可通过），用户此前要靠「过一会手动刷新」，
     * 现在由 App 完成。只自动重试一次，避免在真故障时反复打服务端。
     */
    fun reportFailure(failedUrl: String?, diag: JwImportDiagnosis.Diagnosis, statusCode: Int? = null) {
        lastFailedUrl = failedUrl
        if (statusCode != null && JwImportDiagnosis.shouldAutoRetry(statusCode, failedUrl) && !autoRetryUsed) {
            autoRetryUsed = true
            Log.d(TAG, "auto-retry after HTTP $statusCode at ${JwTeachingEval.redactForLog(failedUrl)}")
            statusNote = "教务返回 $statusCode，正在自动重试…"
            pageState = PageState.Loading
            webView?.loadUrl(startUrl)
            return
        }
        // 自动重试那一轮**不能**放行闸门：它换成了认证入口，目标页面之后才到。
        // 真失败才叫醒等待中的一键导入，让它带着这句诊断结果收场。
        pageLoadGate?.let { gate ->
            if (!gate.done.isCompleted) {
                pageLoadGate = null
                gate.done.complete(false)
            }
        }
        pageState = PageState.Error(title = diag.title, body = diag.body, retryUrl = diag.retryUrl)
        statusNote = diag.title
        Log.d(TAG, "failure: ${diag.title} retry=${diag.retryUrl ?: "reload"} ${JwTeachingEval.redactForLog(failedUrl)}")
    }

    /**
     * 页面就绪：放行等待中的一键导入闸门。
     *
     * 匹配用 URL 片段而不是整串——两张课表的 URL 都含 `xskb`（理论 `xskb_list.do`、
     * 实验 `syjx/toXskb`），整串比较会互相误判（DESIGN §4.8 记过同一个坑）。
     */
    fun releaseLoadGate(url: String) {
        val gate = pageLoadGate ?: return
        if (!url.contains(gate.urlPart) || gate.done.isCompleted) return
        pageLoadGate = null
        gate.done.complete(true)
    }

    /**
     * 落在统一认证登录页时，用保存的凭证**自动登一次**（DESIGN §4.27「落登录页自动填表」）。
     *
     * **为什么不注入 cookie 了**（2026-09-24 真机定性）：OkHttp 登录拿到的 cookie 注入
     * `CookieManager` 这条路走不通——cookie 确实写进去了（逐条读回验证通过），但 WebView
     * 发请求时就是不带，首跳仍然落到 CAS 登录页。差别只剩 cookie 属性：注入的那份缺
     * `SameSite=None`，Chrome 按 Lax 处理，跨站跳转不带。
     *
     * 让 **WebView 自己填表提交**，cookie 就由 CAS 服务端亲自 `Set-Cookie`，属性完整、
     * 与用户手动登录走同一条路，从根上没有这个问题。
     *
     * 只做一次：页面结构变了、或者命中验证码，脚本会返回 `no-form`，那时退回人工登录。
     *
     * 除了页面级的 [autoLoginTried]，还要过一道**进程级节流**（[SessionStatus]）：页面级的
     * 标记挡不住重开窗口，密码改过而 App 还存着旧的时候，每开一次导入页就撞一次 CAS。
     */
    fun tryAutoLoginOnCasPage(view: WebView?, url: String?) {
        if (autoLoginTried) return
        val u = url.orEmpty()
        if (!JwUrls.isCasHost(u) || !JwWebFit.isLoginLikeUrl(u)) return
        val cred = savedCas ?: return
        if (!SessionStatus.tryAcquireAutoLogin(System.currentTimeMillis())) return
        autoLoginTried = true
        view?.evaluateJavascript(JwAutoLogin.fillJs(cred.username, cred.password)) { raw ->
            // 只记结果，**不记凭证**
            val r = unwrapJsString(raw)
            Log.d(TAG, "auto-login on CAS page: $r @ $u")
            if (r?.startsWith(JwAutoLogin.OK_PREFIX) != true) {
                statusNote = "自动登录没走通，请在下方页面手动登录一次"
            }
        }
    }

    /**
     * 教务把「未登录」就地渲染成登录页（URL 不变），必须在任何后续自动导航之前拦下。
     *
     * 根因（2026-09-18 实测）：未登录访问 `xsMainV` / `xskb_list` 时教务不跳转，
     * 而是以 HTTP 200 返回登录页（含 `#loginDiv` 与密码框）。旧逻辑按 URL 把
     * `xsMainV` 当「已登录主页」并自动跳课表，于是主页登录页 → 课表登录页 →
     * 又认出 xsMainV… 互跳成环（日志里 18 次导航、约 1 秒一轮）。
     *
     * ⚠️ 只在教务域（[JwUrls.isJwHost]）判定。统一认证 CAS（eapp2）的登录页**本来就该**
     * 有密码框，在那里判定会把「用户正在登录」误报成「会话失效」，直接毁掉登录流程。
     */
    fun checkSessionLost(view: WebView?, url: String?, onDone: () -> Unit = {}) {
        if (!JwUrls.isJwHost(url)) {
            Log.d(TAG, "session probe skipped (non-jw host) ${JwTeachingEval.redactForLog(url)}")
            onDone()
            return
        }
        view?.evaluateJavascript(SESSION_PROBE_JS) { raw ->
            val probe = unwrapJsString(raw)
            // 无条件记录探针原始值：上一轮排查时无法回答「探测到底跑没跑、看到了什么」，
            // 这个日志让下次复现能直接读出判定依据，而不是靠猜。
            Log.d(TAG, "session probe: '$probe' @ $url")
            // 会话被别处登录顶掉（强智单会话）：先判它，再判登录页——两者的出口相同，
            // 但文案不同，用户看到的「为什么」必须对得上事实（2026-10-06）。
            if (JwImportDiagnosis.looksLikeSessionConflict(probe)) {
                Log.d(TAG, "session conflict detected at $url (probe=$probe)")
                reportFailure(url, JwImportDiagnosis.sessionConflict(JwVpnDetector.isVpnActive(context)))
                return@evaluateJavascript
            }
            if (JwImportDiagnosis.looksLikeLoginPage(probe)) {
                Log.d(TAG, "session lost detected at $url (probe=$probe)")
                reportFailure(url, JwImportDiagnosis.sessionLost(JwVpnDetector.isVpnActive(context)))
                return@evaluateJavascript
            }
            onDone()
        }
    }

    /** 真正发起自动跳转（会话探针已过；两处入口共用）。 */
    fun startAutoNav(wv: WebView) {
        if (autoNavPending || autoNavDone) return
        autoNavPending = true
        pageState = PageState.Loading
        statusNote = autoNavNote
        // 复核放在延迟里：重定向链乱序时 currentUrl 可能已经不在主页，就不跳了；
        // 但**不能**把状态留在 Loading——那是「卡在加载中」的另一半根因
        wv.postDelayed({
            // 期间用户自己发了新导航（手动按钮/刷新会把 autoNavPending 清掉）：这一跳作废
            if (!autoNavPending) return@postDelayed
            if (!autoNavDone && currentUrl.contains("xsMainV")) {
                autoNavDone = true
                if (mode == JwImportMode.TeachingEval) {
                    // 评教没有静态目标页（DESIGN §4.44）：注入取 token 脚本、同窗口跳转。
                    // 回调只判「有没有真的发起」；发起后未必有导航（ajax 静默失败），
                    // 由下面的原地看门狗兜底——任何路径不允许状态悬在 Loading。
                    evalAttempt++
                    val attempt = evalAttempt
                    wv.evaluateJavascript(JwTeachingEval.launchJs()) { outcome ->
                        if (outcome?.contains("sent") != true && outcome?.contains("dup") != true) {
                            statusNote = if (outcome?.contains("no-jquery") == true) {
                                "教务页脚本未就绪：可点右上角「刷新」重试"
                            } else {
                                "没能打开评教页：可点右上角「刷新」重试"
                            }
                            if (pageState is PageState.Loading) pageState = PageState.Ready
                        }
                    }
                    wv.postDelayed({
                        // 只对同代次生效：刷新重试后旧看门狗作废，不再补一条假告警；
                        // 另要求 autoNavDone（本轮注入仍生效）——刷新会把它复位，
                        // 「刷新后到重新注入前」的间隙里旧看门狗据此也不再命中（2026-10-07 复查补）
                        if (attempt == evalAttempt &&
                            autoNavDone &&
                            currentUrl.contains("xsMainV") &&
                            pageState is PageState.Loading
                        ) {
                            statusNote = "还没打开评教页：可点右上角「刷新」重试"
                            pageState = PageState.Ready
                        }
                    }, AUTO_EVAL_TIMEOUT_MS)
                } else {
                    wv.loadUrl(autoNavTarget)
                }
            } else {
                autoNavPending = false
                autoNavDone = true
                if (pageState is PageState.Loading) pageState = PageState.Ready
            }
        }, AUTO_NAV_DELAY_MS)
        // 看门狗：探针回调万一不来（页面卡死），到点把状态解锁成可操作，不让用户干等
        wv.postDelayed({
            if (autoNavPending && !autoNavDone) {
                autoNavPending = false
                autoNavDone = true
                statusNote = "打开教务页面超时：可点右上角「刷新」重试"
                if (pageState is PageState.Loading) pageState = PageState.Ready
            }
        }, AUTO_NAV_TIMEOUT_MS)
    }

    /**
     * 落在教务主页（登录落点）→ 自动去目标页 [autoNavTarget]。
     *
     * **为什么不再只挂在 `onPageFinished` 上**（2026-10-01 用户报障「进入选课中心卡在
     * 教务主页面显示加载中」的根因）：`onPageFinished` 会被挂住的子资源拖住，迟迟不来时
     * 旧实现就一直停在 `onPageStarted` 写的「加载中…」，且没有任何后续触发点。
     * 现在首选触发点是 `onPageCommitVisible`——**内容已可见就算到了**，不依赖「加载完成」。
     *
     * 两条纪律不变：
     * 1. 先过会话探针：教务未登录时会把登录页**就地渲染**在 `xsMainV` 这个 URL 上
     *    （HTTP 200、URL 不变），不看内容就跳会跳到登录页上；
     * 2. 只跳一次（[autoNavDone]），用户之后自己回到主页不打扰。
     *
     * 另配 [AUTO_NAV_TIMEOUT_MS] 看门狗与「复核失败」分支：任何路径都不允许把页面
     * 悬在「加载中…」且无出口（这是本 bug 的另一半）。
     *
     * 注意：两者是**局部函数，不能前向引用**（Kotlin 口径，见 `.agents/rules/import-jw.md`），
     * [startAutoNav] 必须定义在本函数之前。
     */
    fun startAutoNavOnLanding(view: WebView?, url: String?) {
        val wv = view ?: return
        if (url == null || "xsMainV" !in url) return
        if (autoNavPending || autoNavDone) return
        checkSessionLost(wv, url) { startAutoNav(wv) }
    }

    /**
     * 打开 [url] 并等它加载完。超时或失败返回 false——失败时错误浮层已由 [reportFailure]
     * 铺好，调用方只管收场。
     */
    suspend fun openPageAndWait(url: String, urlPart: String, label: String): Boolean {
        pageState = PageState.Loading
        statusNote = "正在打开${label}…"
        autoNavPending = false
        // 用户显式发起的新一轮导航：自动重试闸门重置
        autoRetryUsed = false
        val gate = PageLoadGate(urlPart)
        pageLoadGate = gate
        webView?.loadUrl(url)
        val done = withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { gate.done.await() } ?: false
        if (pageLoadGate === gate) pageLoadGate = null
        if (!done && pageState !is PageState.Error) {
            statusNote = "打开${label}超时：${NetworkHint.briefOf(JwVpnDetector.isVpnActive(context))}"
        }
        return done
    }

    /** 注入脚本并回收返回值（`evaluateJavascript` 不 await Promise，这里只要同步返回值）。 */
    suspend fun evalJs(wv: WebView, js: String): String = suspendCancellableCoroutine { cont ->
        wv.evaluateJavascript(js) { r -> if (cont.isActive) cont.resume(r ?: "") }
    }

    /**
     * 一键导入（DESIGN §4.4）：依次打开理论课表页与实验课表页，各抽一次，合成一批后弹一次
     * 识别结果，确认后写进同一张课表。
     *
     * 两张表连着抽，于是有两条与单张导入不同的口径：
     * 1. **实验课表 0 条不算失败**——前期学期本来就没有实验课，中断会让用户在没排实验课的
     *    学期根本导不进来。区分「这张表没课」与「拿到的不是这张表」由 [OneClickImport]
     *    按页面形态判定，结论写进识别结果弹窗，让用户在写库前看到。
     * 2. **实验页跟着理论页的学期走**——两页各有一套默认学期，不一致时合并出来的表会跨学期。
     *
     * [forceTerm] 非空 = 确认弹窗里切换了学期（DESIGN §4.31 前置的学期选择）：带着
     * `xnxq01id` 参数强制重载理论页（此时不能沿用「已在理论页就不重载」的捷径——
     * 页面要渲染的就是这个学期的数据），实验页同样跟这个学期。
     */
    suspend fun runOneClickImport(forceTerm: String? = null) {
        val wv = webView ?: return
        if (busy) return
        // 自动触发与手动点击共用这一条「跑过了」的标记
        autoImportTried = true
        busy = true
        try {
            // 带学期参数的 URL：学期已过调用方白名单校验，这里直接拼
            val theoryUrl = forceTerm
                ?.takeIf { JwUrls.TERM_PATTERN.matches(it) }
                ?.let { "${JwUrls.SCHEDULE_LIST}&xnxq01id=$it" }
            // 没指定学期且已在理论课表页就不重复加载：用户可能自己在下拉里选了学期，
            // 重新加载会把这个选择打回教务默认，还白等一次页面加载
            if (theoryUrl != null || JwUrls.schedulePageKind(currentUrl) != JwSchedulePage.Theory) {
                if (!openPageAndWait(theoryUrl ?: JwUrls.SCHEDULE_LIST, THEORY_URL_PART, "学期理论课表")) return
            }
            statusNote = "正在读取学期理论课表…"
            val theory = OneClickImport.theorySource(
                unwrapJsString(evalJs(wv, QiangzhiScheduleParser.EXTRACT_JS)).orEmpty(),
            )

            statusNote = "正在读取实验课表…"
            if (!openPageAndWait(JwUrls.labScheduleUrl(forceTerm ?: theory.term), LAB_URL_PART, "实验课表")) return
            val lab = OneClickImport.labSource(
                unwrapJsString(evalJs(wv, SyjxScheduleParser.EXTRACT_JS)).orEmpty(),
            )

            val result = OneClickImport.combine(theory, lab)
            if (result.blocked != null) {
                statusNote = result.blocked
                snackbar.showNotice(result.blocked)
                return
            }
            statusNote = result.breakdown.joinToString("，") { (label, count) -> "$label $count 条" } +
                "，确认后写入"
            importDraft = ImportDraft(
                result = ImportParseResult.Success(result.courses, term = result.term, note = result.note),
                breakdown = result.breakdown,
                // 两张表的数据一起进来，覆盖会连用户自建/调课过的行一起清掉：默认合并
                defaultMerge = true,
                termOptions = result.termOptions,
            )
        } finally {
            busy = false
        }
    }

    /**
     * 落在理论课表页就自动跑一次一键导入（DESIGN §4.4.2）。
     *
     * 课表模式下打开这个窗口的目的就是导入，落哪一页 App 已经自己决定了
     * （xsMainV → 理论课表），中间那次「点一下按钮」是「按当前页选解析器」时代的遗留。
     *
     * 调用点在 onPageFinished 的「页面已就绪」分支里，所以只再判三件事：课表模式、页面类型是
     * **理论课表**、没有别的活在跑。判页面类型而不是判「URL 含 xskb」：实验页 URL 也含 `xskb`，
     * 认错会在实验页就绪时再触发一轮，而一键导入自己就会跳到实验页，等于自动死循环。
     *
     * 标记**同步置位**、不放进协程：onPageFinished 可能连着来两次，进协程再置会漏一次。
     */
    fun autoImportOnTheoryReady(page: JwSchedulePage) {
        if (autoImportTried || busy) return
        if (mode != JwImportMode.Schedule || page != JwSchedulePage.Theory) return
        autoImportTried = true
        scope.launch { runOneClickImport() }
    }

    /**
     * 同源 fetch JSON 的通用执行：注入脚本（写 `window.__qzJson`），轮询回收。
     * evaluateJavascript 不会 await Promise，轮询是最可靠的回收方式；
     * 页面不跳转所以 window 变量不会丢。超时返回 null。
     */
    suspend fun fetchJsonInWebView(
        wv: WebView?,
        fetchJs: String,
        readJs: String,
        timeoutLoops: Int = 60,
    ): String? {
        if (wv == null) return null
        lastFetchConflict = false
        wv.evaluateJavascript(fetchJs, null)
        repeat(timeoutLoops) {
            delay(300)
            val raw = suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(readJs) { r -> if (cont.isActive) cont.resume(r ?: "") }
            }
            val value = unwrapJsString(raw)
            if (!value.isNullOrBlank()) {
                // 会话被顶时 layui 接口回的是那段 JSON，直接交给解析器只会变成
                // 「教务返回 code=-1」——既没说清原因，也没走 sessionConflict 诊断。
                // 在这里一次性判掉，调用方按 lastFetchConflict 决定要不要再用超时文案盖。
                if (JwImportDiagnosis.looksLikeSessionConflictBody(value)) {
                    lastFetchConflict = true
                    reportFailure(
                        currentUrl,
                        JwImportDiagnosis.sessionConflict(JwVpnDetector.isVpnActive(context)),
                    )
                    return null
                }
                return value
            }
        }
        return null
    }

    /**
     * 考试导入（DESIGN §4.14）：壳页读学期 → 分页 fetch `xsksap_list` → 转
     * `Course(kind = Exam)`。定位两级：先按学期配置，失败且学期号可识别时按估算开学日
     * 兜底（历史/未来学期）；仍无法定位的跳过并计数。
     */
    suspend fun runExamImport(wv: WebView?) {
        if (wv == null) return
        if (!JwUrls.isJwHost(currentUrl)) {
            val msg = "请先打开考试安排查询页（教务域）再导入。"
            snackbar.showNotice(msg); return
        }
        busy = true
        try {
            // 学期取壳页下拉的当前选中项：与用户在页面上看到的一致
            val termRaw = suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(ExamScheduleParser.READ_TERM_JS) { r ->
                    if (cont.isActive) cont.resume(r ?: "")
                }
            }
            val term = unwrapJsString(termRaw).orEmpty().trim()
            // 白名单校验：term 会被拼进注入 JS 的单引号字符串里，非学期格式一律拒绝，
            // 既防脏值落库，也从根上杜绝引号注入（白名单口径在 JwUrls.TERM_PATTERN，
            // 一键导入给实验页拼学期号时用的是同一把尺子）
            if (!JwUrls.TERM_PATTERN.matches(term)) {
                statusNote = "取不到学期：请在考试安排查询页选择学期后重试"
                snackbar.showNotice(statusNote)
                return
            }
            statusNote = "正在请求 $term 考试安排…"
            val rows = mutableListOf<ExamEntry>()
            var count = 0
            var page = 1
            do {
                val body = fetchJsonInWebView(
                    wv,
                    ExamScheduleParser.fetchJs(term = term, page = page),
                    ExamScheduleParser.READ_RESULT_JS,
                ) ?: run {
                    // 会话被顶的 JSON 已经在 fetchJsonInWebView 里报成 Error 页了，别盖掉
                    if (lastFetchConflict) return
                    statusNote = "请求考试安排超时：${NetworkHint.briefOf(JwVpnDetector.isVpnActive(context))}"
                    snackbar.showNotice(statusNote)
                    return
                }
                val parsed = try {
                    ExamScheduleParser.parseFetchJson(body)
                } catch (e: Exception) {
                    statusNote = e.message ?: "解析失败"
                    snackbar.showNotice(statusNote)
                    return
                }
                count = parsed.count
                rows += parsed.exams
                page++
            } while (rows.size < count && page <= 10)

            val slots = repo.timeSlots.first()
            val semester = repo.semester.first()
            val mapping = ExamMapper.toExamCourses(rows, slots, semester, term)
            if (rows.isEmpty()) {
                statusNote = "$term 暂无考试安排（教务通常考前数周才录入）"
                emptyExamTerm = term
                return
            }
            if (mapping.courses.isEmpty()) {
                val msg = buildString {
                    append("$term 的 ${rows.size} 场考试都无法定位")
                    // 学期号无法识别时估算口径不可用，只剩「配置开学日」一条出路；
                    // 估算可用还定位失败，只能是日期本身异常
                    if (ExamMapper.estimatedTermStart(term) == null && semester == null) {
                        append("：请先在「我的 → 课表设置」配置开学日")
                    } else {
                        append("：日期无法映射到学期周次")
                    }
                }
                statusNote = msg
                snackbar.showNotice(msg)
                return
            }
            // 历史/未来学期靠估算开学日定位（DESIGN §4.14）：估算可能与实际相差一两周，
            // 必须让用户在确认弹窗里知情，而不是静默落表
            val estimateNote = if (mapping.estimated > 0) {
                val start = ExamMapper.estimatedTermStart(term)
                "${mapping.estimated} 场考试不属于当前学期配置，已按「$term」的估算开学日" +
                    "（$start）定位周次，可能与实际相差一两周。"
            } else {
                null
            }
            statusNote = buildString {
                append("解析到 ${mapping.courses.size} 场考试")
                if (mapping.skipped > 0) append("（跳过无法定位的 ${mapping.skipped} 场）")
                if (mapping.estimated > 0) append("，其中 ${mapping.estimated} 场按估算周次定位")
                append("，确认后写入")
            }
            pendingExamImport = rows to term
            importDraft = ImportDraft(
                ImportParseResult.Success(mapping.courses, term = term, note = estimateNote),
            )
        } finally {
            busy = false
        }
    }

    /**
     * 考试安排的一键式入口（2026-09-30，用户口径：跟导入课表一样）。
     *
     * 落到考试安排查询页就自动跑一次 [runExamImport]：有安排 → 识别结果确认弹窗，
     * 没安排 → 「暂时没有考试安排」弹窗，两条都是明确的终态。共用理论课表那条的
     * `autoImportTried`（同一窗口一次导一轮），`busy` 挡住导入期间的重复触发。
     *
     * 定义在 [runExamImport] **之后**：Kotlin 局部函数不能前向引用（同
     * `autoImportOnScoreReady` 的理由）。
     */
    fun autoImportOnExamReady(page: JwSchedulePage) {
        if (autoImportTried || busy) return
        if (mode != JwImportMode.Schedule || page != JwSchedulePage.Exam) return
        autoImportTried = true
        scope.launch { runExamImport(webView) }
    }

    /** 成绩导入（DESIGN §4.15）：全部学期分页 fetch → 按学期分组 → 确认后逐学期替换入库。 */
    suspend fun runScoreImport(wv: WebView?) {
        if (wv == null) return
        if (!JwUrls.isJwHost(currentUrl)) {
            val msg = "请先登录教务（点击上方入口打开成绩查询页）再导入。"
            snackbar.showNotice(msg); return
        }
        busy = true
        try {
            statusNote = "正在请求全部学期成绩…"
            val records = mutableListOf<ScoreRecord>()
            var count = 0
            var page = 1
            do {
                val body = fetchJsonInWebView(
                    wv,
                    ScoreParser.fetchJs(term = "", page = page),
                    ScoreParser.READ_RESULT_JS,
                ) ?: run {
                    // 会话被顶的 JSON 已经在 fetchJsonInWebView 里报成 Error 页了，别盖掉
                    if (lastFetchConflict) return
                    statusNote = "请求成绩超时：${NetworkHint.briefOf(JwVpnDetector.isVpnActive(context))}"
                    snackbar.showNotice(statusNote)
                    return
                }
                val parsed = try {
                    ScoreParser.parseFetchJson(body)
                } catch (e: Exception) {
                    statusNote = e.message ?: "解析失败"
                    snackbar.showNotice(statusNote)
                    return
                }
                count = parsed.count
                records += parsed.records
                page++
            } while (records.size < count && page <= 20)

            if (records.isEmpty()) {
                statusNote = "教务没有返回任何成绩"
                snackbar.showNotice(statusNote)
                return
            }
            // 账号条资料（DESIGN §3.3）：学籍卡抓不到只降级，不挡成绩导入。
            // cardHtml 以 "ERR:" 开头时 parseStudentCard 会因缺标签返回 null，同一出口降级。
            try {
                val cardHtml = fetchJsonInWebView(
                    wv,
                    StudentCardFetcher.fetchJs(JwUrls.STUDENT_CARD),
                    StudentCardFetcher.READ_RESULT_JS,
                    timeoutLoops = 20,
                )
                val card = cardHtml?.let { ScoreParser.parseStudentCard(it) }
                if (card != null) {
                    prefs.setProfile(
                        name = card.name,
                        className = card.studentClass,
                        studentId = card.studentId,
                        college = card.college,
                        major = card.major,
                        duration = card.duration,
                    )
                }
            } catch (_: Exception) {
                // 学籍卡失败不影响成绩导入结果
            }
            val grouped = records.groupBy { it.term }.toSortedMap(compareByDescending { it })
            statusNote = "解析到 ${grouped.size} 个学期共 ${records.size} 条成绩，确认后写入"
            pendingScores = grouped.entries.map { (term, list) -> term to list }
        } finally {
            busy = false
        }
    }

    /**
     * 落在成绩查询页就自动跑一次成绩导入（DESIGN §4.15）。
     *
     * 与课表模式的 [autoImportOnTheoryReady] 同一个道理：成绩模式下打开这个窗口的目的就是
     * 导入，而 `xsMainV` → `cjcx_frm` 的导航由 App 自己完成，中间那次「点一下导入成绩」
     * 是多余的。成绩页不属于 [JwSchedulePage]，所以判 [JwUrls.isScoreQueryUrl]。
     *
     * 标记同步置位、与课表模式共用 `autoImportTried`：同一窗口只会是其中一种模式，不会互相干扰；
     * `busy` 挡住成绩导入自己期间的重复触发，`autoImportTried` 挡住页面重载后的二次触发。
     * 只省点击，不绕过确认：写库仍要过「确认导入成绩」弹窗。
     *
     * 放在 [runScoreImport] 之后：Kotlin 局部函数不能前向引用。
     */
    fun autoImportOnScoreReady(u: String) {
        if (autoImportTried || busy) return
        if (mode != JwImportMode.Scores || !JwUrls.isScoreQueryUrl(u)) return
        autoImportTried = true
        scope.launch { runScoreImport(webView) }
    }

    // 独立 Activity 窗口：inset 全部走 M3 默认——TopAppBar 消费状态栏、
    // Scaffold contentWindowInsets 提供底部导航栏 inset（导入按钮区不压手势条）。
    // 此前为「嵌在外层 Scaffold 里」做的双 inset 规避已随窗口拆分一起移除。
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (mode) {
                                JwImportMode.Scores -> "成绩导入 · 统一认证"
                                JwImportMode.Selection -> "选课 · 统一认证"
                                JwImportMode.TeachingEval -> "学生评教 · 统一认证"
                                JwImportMode.Schedule -> "教务导入 · 统一认证"
                                JwImportMode.Browse -> browseTitle.ifBlank { "网页核对" }
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (mode == JwImportMode.Browse) {
                                "网页核对 · 以网页数据为准"
                            } else {
                                pageTitle
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                    }
                },
                navigationIcon = {
                    // 左上角箭头 = **直接退出窗口**（用户口径 2026-10-08）：它管"离开这个窗口"，
                    // 逐页回退 / 先关弹层那套交给系统返回（见 backOrClose），两边语义分开。
                    IconButton(onClick = { onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出")
                    }
                },
                actions = {
                    // 电脑版 / 手机版（DESIGN §4.4）：效果就在眼前，所以开关放在这里而不是设置页。
                    // 顶栏空间有限，用纯文字 chip（骑行页使用方式同款形态）而不是再加一枚图标。
                    JwDesktopModeChip(
                        desktop = desktopMode,
                        onToggle = {
                            val next = !desktopMode
                            scope.launch {
                                prefs.setJwDesktopMode(next)
                                snackbar.showNotice(
                                    if (next) "已切换为电脑版；双指可放大缩小" else "已切换为手机版",
                                )
                            }
                        },
                    )
                    IconButton(
                        onClick = {
                            // 先取出错误态里的重试目标，再改状态（顺序反了就永远读到 Loading）
                            val retry = (pageState as? PageState.Error)?.retryUrl
                            pageState = PageState.Loading
                            autoNavPending = false
                            // 刷新 = 新的一轮：落地页的自动跳转也放开重试一次（看门狗提示就是这么说的）
                            autoNavDone = false
                            // 用户显式重试 = 新的一轮，自动重试闸门重新打开
                            autoRetryUsed = false
                            // 错误态下刷新不是无脑 reload：失败 URL 若是 CAS/SSO 页（含一次性 ticket），
                            // 重放它必然失败，只能回入口重新走认证
                            if (retry.isNullOrBlank()) {
                                statusNote = "重新加载中…"
                                webView?.reload()
                            } else {
                                statusNote = "重新打开统一认证…"
                                webView?.loadUrl(retry)
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            StatusStrip(note = statusNote)

            Box(Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        // 套一层 FrameLayout 作壳（StackOverflow 上「Compose + WebView 白屏/不渲染」
                        // 的验证修法）：Compose 重排时摘挂的是壳，WebView 本体不再被直接摘挂/重挂。
                        // 直接托管 WebView 在部分机型上会在 AndroidView 生命周期里丢渲染面。
                        FrameLayout(ctx).apply {
                            val wv = WebView(ctx).apply {
                                configureForJw(this)
                                if (BuildConfig.DEBUG) {
                                    // 远程调试只留给 debug 包（minSdk 26 > KITKAT，无需再判版本）：
                                    // release 放开等于把用户登录会话暴露给 chrome://inspect
                                    WebView.setWebContentsDebuggingEnabled(true)
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean = false

                                    override fun onPageStarted(
                                        view: WebView?,
                                        url: String?,
                                        favicon: Bitmap?,
                                    ) {
                                        progress = 0
                                        currentUrl = url ?: currentUrl
                                        statusNote = "加载中…"
                                        // 这里**不要**清 lastFailedUrl。实测回调顺序为
                                        // onReceivedHttpError → onPageStarted → onPageFinished：
                                        // 失败先到、start 后到，在此清除等于守卫被下一次回调拆掉
                                        // （曾因此让 500 错误浮层被洗成「已登录教务…」）。
                                        // 标记由 onPageFinished 在「URL 相同」时一次性消费。
                                        Log.d(TAG, "onPageStarted ${view?.width}x${view?.height} ${JwTeachingEval.redactForLog(url)}")
                                    }

                                    /** 首屏内容可见就先注入适配样式；等 onPageFinished 在图片多的页面上太晚。 */
                                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                                        Log.d(TAG, "onPageCommitVisible ${view?.width}x${view?.height} ${JwTeachingEval.redactForLog(url)}")
                                        applyPageFit(view, url, desktopMode)
                                        // 内容已可见 = 这一页真的到了（不被挂住的子资源拖住）——
                                        // 登录落地在教务主页就趁这里自动去目标页，见 [startAutoNavOnLanding]
                                        startAutoNavOnLanding(view, url)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        currentUrl = url ?: currentUrl
                                        pageTitle = view?.title?.takeIf { it.isNotBlank() } ?: pageTitle
                                        Log.d(TAG, "onPageFinished ${view?.width}x${view?.height} ${JwTeachingEval.redactForLog(url)}")

                                        // 本次导航已经失败过（onReceivedError/onReceivedHttpError 先到）：
                                        // 保持错误浮层，不能再往下走把它洗成 Ready —— 旧实现无条件
                                        // 置 Ready，实测会把 ERR_CONNECTION_TIMED_OUT 的浮层盖掉，
                                        // 只剩 Chromium 原生错误页 + 下面探针写的「内容为空」提示。
                                        //
                                        // 按 URL 相等消费（而非「非空即拦」）：回调顺序实测为
                                        // onReceivedHttpError → onPageStarted → onPageFinished，
                                        // 同一个失败 URL 会重复走这几步；用相等匹配既能顶住重复轮次，
                                        // 又不会把用户的成功重试拦成错误。
                                        if (lastFailedUrl != null && lastFailedUrl == url) {
                                            Log.d(TAG, "onPageFinished after failure, keep error state (${JwTeachingEval.redactForLog(url)})")
                                            lastFailedUrl = null
                                            return
                                        }

                                        val u = url.orEmpty()
                                        if (u.isBlank() || u == "about:blank") {
                                            reportFailure(u, JwImportDiagnosis.blankPage(JwVpnDetector.isVpnActive(context)))
                                            return
                                        }

                                        // 布局后 reflow 兜底（已知案例：Compose 首测 0×0 再拉满，
                                        // 页面在视口不稳定时完成布局就会按错误视口定格 → 白屏但 JS 正常）。
                                        // 等布局稳定后派发 resize 强制页面按真实视口重排，并回读页面
                                        // 自身的布局数据进日志，白屏复现时直接看 logcat 定位。
                                        view?.post {
                                            view.requestLayout()
                                            view.invalidate()
                                            view.evaluateJavascript(VIEWPORT_PROBE_JS) { raw ->
                                                Log.d(TAG, "viewport probe: $raw")
                                            }
                                        }

                                        // 落在统一认证登录页 → 用保存的凭证自动登一次（DESIGN §4.27）。
                                        // 放在会话检查之前：它一提交就触发导航，这一轮不必再往下走。
                                        tryAutoLoginOnCasPage(view, u)

                                        // 会话检查必须**先于所有自动导航**：未登录时教务把登录页
                                        // 就地渲染在 xsMainV / xskb_list 这些 URL 上（HTTP 200、URL 不变），
                                        // 若先按 URL 自动跳课表，就会与课表页的「登录页」互跳成环。
                                        // 探针是异步的，故以下所有「就绪/自动跳转」逻辑都挪进它的回调。
                                        // CAS（eapp2）域内的登录页是正常流程，helper 内部已排除。
                                        checkSessionLost(view, u) {
                                            // 走到这里说明不是登录页（登录页已被 checkSessionLost 拦下）
                                            // ——WebView 手里那份会话是真的能用。把它的 cookie 抄回会话层：
                                            // 服务端可能在这期间轮换过 session，网页里那份才是最新的。
                                            // **不要**再加「jar 已有时就不抄」的条件：回灌是唯一方向
                                            // （没有注入侧了），加了网页里的新值就永远回不来。
                                            if (JwUrls.isJwHost(u)) {
                                                scope.launch { runCatching { cas.adoptFromWebView() } }
                                            }
                                            // 手登成功且本地没有凭据：给一次「保存账号密码」的机会
                                            //（第二层兜底；进窗时已弹过前置层、用户跳过才走到这）。
                                            // 教务域（会话在教务手里才叫登录成功）+ 用户没存过 + 没拒绝过
                                            if (JwUrls.isJwHost(u) &&
                                                savedCas == null &&
                                                !casLoginDeclined
                                            ) {
                                                showSaveCredentialPrompt = true
                                            }
                                            // 注意：两张课表的 URL 都含 "xskb"（理论 xskb_list、实验 toXskb），
                                            // 不能用子串判断，必须按页面类型区分，否则会互相误判
                                            val page = JwUrls.schedulePageKind(u)
                                            statusNote = when {
                                                "eapp2.juwp.edu.cn" in u ->
                                                    "请使用学校统一身份认证登录"
                                                // 落地主页的文案与自动跳转共用一份口径（见 autoNavNote）
                                                "xsMainV" in u -> autoNavNote
                                                mode == JwImportMode.Browse &&
                                                    JwUrls.isScoreQueryUrl(u) ->
                                                    "已打开成绩查询页；点页面上的「查询」加载成绩。"
                                                mode == JwImportMode.Browse &&
                                                    JwUrls.isExamQueryUrl(u) ->
                                                    "已打开考试安排页；点页面上的「查询」加载安排。"
                                                mode == JwImportMode.Browse ->
                                                    "已打开${browseTitle.ifBlank { "教务网页" }}。"
                                                JwUrls.isSelectionCenterUrl(u) ->
                                                    "选课中心已打开。点「进入选课」在教务页面上完成选课，选好后返回即可。"
                                                // 评教平台（DESIGN §4.44）：SPA 页面，进页即静候用户操作
                                                JwTeachingEval.isPlatformUrl(u) ->
                                                    "学生评教页已打开，评教完成后返回即可。"
                                                page == JwSchedulePage.Exam ->
                                                    // 首次落页由 autoImportOnExamReady 自动跑，
                                                    // 跑过之后再回来就只剩手动入口
                                                    if (autoImportTried) {
                                                        "考试安排查询已打开。点下方「导入考试安排」可重新导入。"
                                                    } else {
                                                        "考试安排查询已打开，正在自动导入…"
                                                    }
                                                page == JwSchedulePage.Lab ->
                                                    "实验课表已打开。"
                                                page == JwSchedulePage.Theory ->
                                                    "理论课表已打开。"
                                                JwUrls.isScoreQueryUrl(u) ->
                                                    // 首次落页由 autoImportOnScoreReady 自动跑，
                                                    // 跑过之后再回来就只剩手动入口
                                                    if (autoImportTried) "成绩查询页已打开。点下方「导入成绩」。"
                                                    else "成绩查询页已打开，正在自动导入…"
                                                // 评教模式的普通页兜底：这窗口没有导入按钮，别指向它
                                                mode == JwImportMode.TeachingEval ->
                                                    "已登录教务。评教页打不开时可点右上角「刷新」重试"
                                                else -> "已登录教务，点下方「一键导入课表」可同步理论与实验课表"
                                            }

                                            // 落地主页 → 自动去目标页。与 onPageCommitVisible 那条路
                                            // 共用同一个闸门与实现（见 startAutoNavOnLanding）；这里是兜底，
                                            // 也负责「内容可见那条路没触发」时的第二次机会。
                                            // autoNavDone 之后不再拦：用户自己回到主页时按普通页处理（Ready）
                                            if ("xsMainV" in u && !autoNavDone) {
                                                view?.let { startAutoNav(it) }
                                                return@checkSessionLost
                                            }
                                            if (page != JwSchedulePage.None) {
                                                autoNavPending = false
                                                pageState = PageState.Ready
                                                applyPageFit(view, u, desktopMode)
                                                // 一键导入在等这一页：就绪即放行（DOM 已完整，可以注入抽取）
                                                releaseLoadGate(u)
                                                // 理论课表就绪 → 自动跑一次一键导入（DESIGN §4.4.2），
                                                // 用户不必再点「一键导入课表」
                                                autoImportOnTheoryReady(page)
                                                // 考试安排查询页就绪 → 自动跑一次考试导入
                                                //（2026-09-30 用户口径：考试也做成一键式）
                                                autoImportOnExamReady(page)
                                                return@checkSessionLost
                                            }
                                            // 成绩查询页不是课表页（pageKind = None），单独一条：
                                            // 成绩模式落到这里就自动跑一次导入（DESIGN §4.15），
                                            // 与课表模式的 autoImportOnTheoryReady 同构。
                                            // 提前 return，免得下面的白屏探针把导入中的状态条洗掉。
                                            if (JwUrls.isScoreQueryUrl(u)) {
                                                autoNavPending = false
                                                pageState = PageState.Ready
                                                applyPageFit(view, u, desktopMode)
                                                releaseLoadGate(u)
                                                autoImportOnScoreReady(u)
                                                return@checkSessionLost
                                            }
                                            applyPageFit(view, u, desktopMode)
                                            pageState = PageState.Ready
                                            releaseLoadGate(u)
                                            // 平台落地 = 评教这一跳完成：autoNavPending 归零（否则它一直
                                            // 挂着 true——今天无功能影响，但别把「在途」状态留给将来）
                                            if (JwTeachingEval.isPlatformUrl(u)) autoNavPending = false
                                            // 加载完成 ≠ 渲染出东西：探测一下，白屏时给可操作提示，
                                            // 而不是让用户对着空页猜发生了什么。
                                            // 评教平台是 SPA 空壳（内容全由 JS 渲染），探针必然误判
                                            // ——平台域跳过，状态条由上面的平台分支负责（DESIGN §4.44）
                                            if (!JwTeachingEval.isPlatformUrl(u)) {
                                                view?.evaluateJavascript(PAGE_CONTENT_PROBE_JS) { scoreRaw ->
                                                    val score = scoreRaw?.trim()?.removeSurrounding("\"")?.toFloatOrNull()
                                                    if (score != null && score < 20f) {
                                                        statusNote = "页面已加载但内容为空，可点「重试」重新认证"
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?,
                                    ) {
                                        if (request?.isForMainFrame != true) return
                                        val code = error?.errorCode ?: -1
                                        val desc = error?.description?.toString().orEmpty()
                                        val failedUrl = request?.url?.toString()
                                        Log.d(TAG, "onReceivedError $code $desc ${JwTeachingEval.redactForLog(failedUrl)}")
                                        reportFailure(
                                            failedUrl,
                                            JwImportDiagnosis.networkFailure(
                                                errorCode = code,
                                                description = desc,
                                                // 平台域 URL 带 token（DESIGN §4.44）：诊断/错误浮层用
                                                // 脱敏串；failedUrl 原文留给 lastFailedUrl 做等值匹配
                                                url = JwTeachingEval.redactForLog(failedUrl),
                                                vpnActive = JwVpnDetector.isVpnActive(context),
                                            ),
                                        )
                                    }

                                    /**
                                     * HTTP 4xx/5xx（**只有它能看到服务端错误页**）：
                                     * Chromium 把 5xx 的响应体当普通页面渲染，`onPageFinished`
                                     * 照常回调，而 `onReceivedError` 只管传输层失败、永远不触发。
                                     * 旧实现缺这个回调，所以「500 error System Error」页会被当成
                                     * 正常页面，状态条还显示「已登录教务…」。
                                     */
                                    override fun onReceivedHttpError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        errorResponse: WebResourceResponse?,
                                    ) {
                                        if (request?.isForMainFrame != true) return
                                        val code = errorResponse?.statusCode ?: -1
                                        if (code < 400) return
                                        val failedUrl = request?.url?.toString()
                                        Log.d(TAG, "onReceivedHttpError $code ${JwTeachingEval.redactForLog(failedUrl)}")
                                        reportFailure(
                                            failedUrl,
                                            JwImportDiagnosis.httpFailure(
                                                statusCode = code,
                                                // 同 networkFailure：浮层里的 URL 必须是脱敏串
                                                url = JwTeachingEval.redactForLog(failedUrl),
                                                vpnActive = JwVpnDetector.isVpnActive(context),
                                            ),
                                            statusCode = code,
                                        )
                                    }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: SslError?,
                                ) {
                                    // 只放行学校域的证书错误（白名单见 JwUrls.TRUSTED_SSL_HOSTS），
                                    // 其余一律取消。无条件 proceed 会关掉整个 WebView 的传输层校验，
                                    // 而统一认证登录表单就在这个 WebView 里，等于把凭证暴露给中间人。
                                    val host = error?.url?.let { JwUrls.hostOf(it) }
                                    if (host != null && host in JwUrls.TRUSTED_SSL_HOSTS) {
                                        handler?.proceed()
                                        statusNote = "已放行学校 HTTPS 证书"
                                    } else {
                                        handler?.cancel()
                                        Log.w(TAG, "ssl error cancelled: host=$host url=${JwTeachingEval.redactForLog(error?.url)}")
                                    }
                                }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress
                                        if (newProgress in 1..99 && pageState !is PageState.Error) {
                                            pageState = PageState.Loading
                                        }
                                    }

                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        if (!title.isNullOrBlank()) pageTitle = title
                                    }
                                }
                                // 从预热入口进（而不是 CAS 直链）：先落教务域写 `bzb_njw`，
                                // 再由页面自带 JS 跳统一认证——目标与 ENTRY 完全相同，
                                // 但消除了「第一次 sso.jsp?ticket 必 500」的问题。
                                // 根因见 JwUrls.SSO_WARMUP 注释与 DESIGN §4.4.1。
                                //
                                // 页面**先加载**，会话准备放在它后面（DESIGN §4.27）：等会话再
                                // loadUrl 会让 WebView 白屏几十秒，预注入 cookie 在真机又不被采用。
                                // 一次登录失败不该把整个导入入口挡死——结果只用来写状态条。
                                scope.launch {
                                    // **先让页面出来**：登录由页面上的自动填表完成（DESIGN §4.27），
                                    // 这里只把 OkHttp 侧的会话准备好、拿结果写状态条。
                                    // 两件事都别反过来做：等会话再 loadUrl 会让 WebView 白屏几十秒
                                    // （2026-09-24 真机：日志里连一条 onPageStarted 都没有）；
                                    // 预注入 cookie 也无效（属性缺 SameSite，WebView 不采用）。
                                    loadUrl(startUrl)
                                    when (val outcome = runCatching { cas.ensureValid() }.getOrNull()) {
                                        is CasEnsureResult.Failed -> statusNote = outcome.message
                                        CasEnsureResult.Suspended ->
                                            statusNote = "教务登录已停用，请在「我的」页更新账号密码"

                                        else -> Unit
                                    }
                                }
                            }
                            // 必须在壳里把实例交回 Compose 状态：刷新/切换课表/导入/返回
                            // 全部经由 `webView` 引用调用，丢了就是「按钮全无反应」。
                            webView = wv
                            addView(
                                wv,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    },
                    update = { /* state held in compose */ },
                    onRelease = { shell ->
                        (0 until shell.childCount).mapNotNull { shell.getChildAt(it) as? WebView }
                            .forEach { wv ->
                                wv.stopLoading()
                                wv.destroy()
                            }
                    },
                )

                // 进度条必须是 overlay：放进 Column 会把 WebView 挤上挤下——
                // 页面刚画完（progress 100）时指示条消失、容器高度突变，
                // 硬件加速 surface 重建在部分机型（MIUI 实测）上表现为
                // 「课表正常显示闪一下然后全白」。overlay 不占布局，WebView 尺寸恒定。
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                    )
                }

                (pageState as? PageState.Error)?.let { err ->
                    ErrorOverlay(
                        error = err,
                        onRetry = {
                            pageState = PageState.Loading
                            autoNavPending = false
                            // 重试同样是新的一轮：落地页的自动跳转放开（同顶栏刷新的口径）
                            autoNavDone = false
                            // 用户显式重试 = 新的一轮，自动重试闸门重新打开
                            autoRetryUsed = false
                            // err.retryUrl 为空 = 失败页可安全重放（如课表页自己 5xx）；
                            // 非空 = 必须回入口重新认证（CAS ticket 一次性）
                            val target = err.retryUrl
                            if (target.isNullOrBlank()) {
                                statusNote = "重试中…"
                                webView?.reload()
                            } else {
                                statusNote = "重新打开统一认证…"
                                webView?.loadUrl(target)
                            }
                        },
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }

            val pageKind = JwUrls.schedulePageKind(currentUrl)
            // 一键导入的可用前置：已在教务域、且不在登录页上。它自己会打开两张课表页，
            // 所以不再要求「当前页是课表页」；但 CAS 域或教务登录页上按下去只会白跳两次。
            val canOneClick = JwUrls.isJwHost(currentUrl) && !JwWebFit.isLoginLikeUrl(currentUrl)
            // 评教模式底部没有任何按钮（DESIGN §4.44）：整块不渲染——只留空分支的话，
            // Surface 的高程条与内边距会以一条空带留在页底
            if (mode != JwImportMode.TeachingEval && mode != JwImportMode.Browse) {
            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 底部只留「考试安排」一个入口：理论/实验两张表由一键导入自己依次打开，
                    // 不再需要「走哪个入口就用哪个解析器」那套按当前页选手的按钮
                    when (mode) {
                        JwImportMode.Schedule -> ScheduleEntryButton(
                            label = "考试安排",
                            active = pageKind == JwSchedulePage.Exam,
                            enabled = !busy,
                            onClick = {
                                pageState = PageState.Loading
                                statusNote = "打开考试安排查询…"
                                autoNavPending = false
                                autoRetryUsed = false
                                webView?.loadUrl(JwUrls.EXAM_QUERY)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // 选课模式（DESIGN §4.35）：只提供「回到选课中心」一个入口，
                        // 选课/退课都在教务页面上完成——App 不代选、不注入、不写库
                        JwImportMode.Selection -> ScheduleEntryButton(
                            label = "选课中心",
                            active = JwUrls.isSelectionCenterUrl(currentUrl),
                            enabled = !busy,
                            onClick = {
                                pageState = PageState.Loading
                                statusNote = "打开选课中心…"
                                autoNavPending = false
                                autoRetryUsed = false
                                webView?.loadUrl(JwUrls.SELECTION_CENTER)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        JwImportMode.Scores -> ScheduleEntryButton(
                            label = "打开成绩查询页",
                            active = JwUrls.isScoreQueryUrl(currentUrl),
                            enabled = !busy,
                            onClick = {
                                pageState = PageState.Loading
                                statusNote = "打开成绩查询页…"
                                autoNavPending = false
                                autoRetryUsed = false
                                webView?.loadUrl(JwUrls.SCORE_FRM)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // 评教模式没有底部按钮（DESIGN §4.44）：动作全在注入脚本与平台页面里，
                        // 失败的出口是顶栏「刷新」（看门狗提示就指向它）
                        JwImportMode.TeachingEval -> Unit
                        JwImportMode.Browse -> Unit
                    }
                    // 选课 / 评教 / 网页核对模式没有导入动作：这一条整块不出现
                    if (mode != JwImportMode.Selection &&
                        mode != JwImportMode.TeachingEval &&
                        mode != JwImportMode.Browse
                    ) {
                        Button(
                            onClick = {
                                when {
                                    mode == JwImportMode.Scores -> scope.launch { runScoreImport(webView) }
                                    pageKind == JwSchedulePage.Exam -> scope.launch { runExamImport(webView) }
                                    else -> scope.launch { runOneClickImport() }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            // 防误触：currentUrl 在 onPageFinished 才更新，页面没就绪就点会拿旧 URL
                            // 判型。考试/成绩两条仍要求停在对应页；一键导入自己会翻页，只要求已登录。
                            enabled = !busy && pageState == PageState.Ready &&
                                when {
                                    mode == JwImportMode.Scores -> true
                                    pageKind == JwSchedulePage.Exam -> true
                                    else -> canOneClick
                                },
                        ) {
                            Text(
                                when {
                                    busy -> if (mode == JwImportMode.Scores || pageKind == JwSchedulePage.Exam) {
                                        "解析中…"
                                    } else {
                                        "识别中…"
                                    }
                                    mode == JwImportMode.Scores -> "导入成绩"
                                    pageKind == JwSchedulePage.Exam -> "导入考试安排"
                                    canOneClick -> "一键导入课表"
                                    else -> "登录教务后可一键导入"
                                },
                            )
                        }
                    }
                }
            }
            }
        }
    }

    importDraft?.let { draft ->
        val courses = draft.result.courses
        // 目标课表强制选择（DESIGN §4.9）：多课表之后「导到当前课表」不再是唯一合理解释，
        // 每次都让用户明确选目标（可新建）与覆盖/合并方式，避免静默覆盖正在用的数据
        ImportTargetDialogHost(
            courses = courses,
            term = draft.result.term,
            note = draft.result.note,
            breakdown = draft.breakdown,
            availableTerms = draft.termOptions.takeIf { it.isNotEmpty() },
            switching = termSwitching,
            onTermSelected = { opt ->
                // 切学期 = 带着新学期参数重爬两张表（DESIGN §4.4）：
                // 重爬成功时 importDraft 被替换（弹窗数据随之刷新），失败时保持原草稿
                // （失败提示由 runOneClickImport 内部的错误浮层/状态条给出）
                termSwitching = true
                scope.launch {
                    runOneClickImport(forceTerm = opt.value)
                    termSwitching = false
                }
            },
            title = when {
                // 一键导入：识别的来源是两张课表，分项条数在弹窗正文里逐项列出
                draft.breakdown != null -> "识别到 ${courses.size} 条课次"
                courses.all { it.kind == CourseKind.Exam } ->
                    "解析到 ${courses.size} 场考试"
                courses.all { it.kind == CourseKind.Lab } ->
                    "解析到 ${courses.size} 条实验课"
                courses.any { it.kind == CourseKind.Lab } ->
                    "解析到 ${courses.size} 条（实验课 ${courses.count { it.kind == CourseKind.Lab }} 条）"
                else -> "解析到 ${courses.size} 条课次"
            },
            // 实验/考试与理论课表是两批数据，合并是更常见的意图；覆盖会连普通课程一起清空。
            // 一键导入两批数据一起进来，[ImportDraft.defaultMerge] 直接给 true。
            defaultMerge = draft.defaultMerge || courses.any { it.kind != CourseKind.Theory },
            repo = repo,
            onConfirm = { target, merge ->
                importDraft = null
                val examDraft = pendingExamImport
                pendingExamImport = null
                // 导入学期：一键导入的数据学期（重爬切换后就是用户选中的学期）；
                // 它会写进目标课表（Timetable.term），成为课程详情查教材的钥匙
                val importTerm = draft.result.term
                scope.launch {
                    val targetId = resolveImportTarget(repo, target)
                    // 考试导入按目标课表的开学日重算周次（DESIGN §4.14）：
                    // 解析时用当前课表配置出的映射只作预览，落表前必须换成目标课表的口径
                    val toImport = if (examDraft != null) {
                        val (rows, examTerm) = examDraft
                        ExamMapper.toExamCourses(
                            rows,
                            repo.timeSlots.first(),
                            repo.semesterFor(targetId),
                            examTerm,
                        ).courses
                    } else {
                        courses
                    }
                    val imported = repo.importParsedCourses(toImport, merge, targetId, importTerm)
                    // 导入到非当前课表后切过去，返回主界面直接看到结果
                    repo.setCurrentTimetable(targetId)
                    // 目标课表名：返回后那条气泡要报「导到了哪张表」
                    val targetName = repo.timetables.first()
                        .firstOrNull { it.id == targetId }?.name.orEmpty()
                    // 终态反馈 = 落回课表页后下方那条气泡（DESIGN §3.3，2026-09-29 用户口径）。
                    // 这里**不弹「导入完成」弹窗**：弹窗在本窗口里，点完就 finish，用户在自己
                    // 那一页看不到；写成气泡后「导没导成、导了几门、导到哪张表」在落点上直接可见。
                    JwImportResultBus.publish(
                        scheduleOutcomeText(count = imported, merge = merge, timetableName = targetName),
                    )
                    // 教材顺带抓（DESIGN §4.31）：挂进程级作用域，不随本页面销毁中断。
                    // 抓取本身仍然「失败不挡导入」，但**不再无声**（2026-10-06）：失败时补一条
                    // 气泡说明原因——此前用户只看到「导入成功、详情里没有教材」，分不清是教务
                    // 没数据还是会话被别处登录顶掉。冷启动还会补抓（TextbookSync.backfillIfMissing）。
                    // **必须排在上面那条 publish 之后**：两条进的是同一个队列，先发的先显示。
                    Graph.appScope.launch {
                        val result = Graph.textbookSync(context).syncForTerm(importTerm)
                        if (result is TextbookSync.Result.Failed) {
                            JwImportResultBus.publish(
                                "教材未同步：${result.reason}",
                                tone = NoticeTone.Warning,
                            )
                        }
                    }
                    // 开学日校准（DESIGN §4.42）：按导入学期抓教务教学日历，把「第 1 周周一」
                    // 写进**目标课表**的开学日（教务为准；用户可在课表设置手改，下次导入再校准）。
                    // 与教材同款：挂进程级作用域、不挡导入；只有真的改了值才弹一条气泡。
                    Graph.appScope.launch {
                        val result = Graph.teachingCalendarSync(context)
                            .applyAfterImport(importTerm, targetId)
                        if (result is TeachingCalendarSync.Result.Applied && result.changed) {
                            JwImportResultBus.publish(
                                "开学日已按教务教学日历设为 ${result.startDate}",
                            )
                        }
                    }
                    onBack()
                }
            },
            onDismiss = {
                importDraft = null
                pendingExamImport = null
            },
        )
    }

    // 「这一学期教务还没排考」的终态弹窗（2026-09-30）：一键式的另一条出口。
    // 考试由教务考前数周才录入，平时 count=0 是**正常空态不是错误**，所以文案只陈述
    // 事实 + 给出下次再来的路，不用 warning/error 语气。
    emptyExamTerm?.let { term ->
        AlertDialog(
            onDismissRequest = { emptyExamTerm = null },
            title = { Text("暂时没有考试安排") },
            text = {
                Text(
                    "$term 教务还没有录入考试安排。教务通常考前数周才排考，" +
                        "过段时间再来查一次即可。",
                )
            },
            confirmButton = {
                TextButton(onClick = { emptyExamTerm = null }) { Text("知道了") }
            },
        )
    }

    pendingScores?.let { grouped ->
        val total = grouped.sumOf { it.second.size }
        AlertDialog(
            onDismissRequest = { pendingScores = null },
            title = { Text("确认导入成绩") },
            text = {
                Column {
                    Text("将导入 ${grouped.size} 个学期共 $total 条成绩，相同学期的旧数据会被替换：")
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        grouped.forEach { (term, list) ->
                            Text(
                                "$term · ${list.size} 条",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pending = grouped
                        pendingScores = null
                        scope.launch {
                            pending.forEach { (term, list) -> scoreRepo.replaceTerm(term, list) }
                            // 终态反馈与课表导入同一口径：气泡落在返回的那一页（DESIGN §3.3）
                            JwImportResultBus.publish("已导入 ${pending.size} 个学期共 $total 条成绩")
                            onBack()
                        }
                    },
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingScores = null }) { Text("取消") }
            },
        )
    }

    // 前置登录弹窗（进窗触发，见 LaunchedEffect）：保存成功 = 凭据落库 + OkHttp 会话就绪。
    // **保存后立刻重载预热入口**：WebView 可能已停在 CAS 登录页，凭据是刚存进去的，
    // 不重载它就一直停在那（用户要退出重进才生效——2026-09-28 用户实测）。
    // 重载后落 CAS 页时自动填表（读新存的凭据）接管提交，全程免手输。
    if (showCasLoginDialog) {
        CasLoginDialog(
            cas = cas,
            vault = Graph.credentialVault(context),
            title = "登录学校统一认证",
            description = "保存后，课表导入、报修、成绩单等教务功能会自动登录，" +
                "不再需要每次输入。密码加密存本机，可随时在「我的 → 学校统一认证」退出并清除。",
            onSaved = {
                savedCas = Graph.credentialVault(context).readCas()
                showCasLoginDialog = false
                // 自动填表的页面级标记要复位：本轮导航还没用它
                // 清自动填表的 5 分钟进程级节流：自动填表失败一次就消耗窗口，
                // 用户刚验证保存的正确密码不该被它压住（对齐引导页 onCredentialsUpdated 口径）
                cas.onCredentialsUpdated()
                autoLoginTried = false
                webView?.loadUrl(startUrl)
            },
            onSkip = {
                casLoginDeclined = true
                showCasLoginDialog = false
            },
        )
    }

    // 手登成功后的补存弹窗（第二层兜底，见手登成功处注释）。
    // 保存成功同样重载：此时 WebView 停在教务域内、会话本身是好的，但凭据已入库，
    // 重载预热入口让下一次会话失效时自动填表立即可用；当前页面不强制打断
    if (showSaveCredentialPrompt) {
        CasLoginDialog(
            cas = cas,
            vault = Graph.credentialVault(context),
            title = "保存账号密码？",
            description = "刚刚登录成功。保存后，会话过期时 App 会自动重新登录，" +
                "不再需要手动输入。密码加密存本机，可随时在「我的 → 学校统一认证」退出并清除。",
            onSaved = {
                // 立刻刷新：同一窗口内再落登录页时自动填表就能用上新存的凭据
                savedCas = Graph.credentialVault(context).readCas()
                showSaveCredentialPrompt = false
                autoLoginTried = false
            },
            onSkip = {
                casLoginDeclined = true
                showSaveCredentialPrompt = false
            },
        )
    }
}

/**
 * 学籍卡抓取脚本（DESIGN §3.3 账号条资料源）：整页 HTML 原样回传，Kotlin 正则解析。
 * 页面是强智新模板的表单，没有现成 JSON 接口，注入 fetch 取 HTML 是最小改动路径。
 */
private object StudentCardFetcher {
    const val READ_RESULT_JS: String = "window.__qzJson === null ? '' : String(window.__qzJson)"

    fun fetchJs(url: String): String = """
(function(){
  try {
    window.__qzJson = null;
    fetch('$url', { credentials: 'same-origin' })
      .then(function(r){ return r.text(); })
      .then(function(t){ window.__qzJson = t; })
      .catch(function(e){ window.__qzJson = 'ERR:' + String(e); });
  } catch (e) {
    window.__qzJson = 'ERR:' + String(e);
  }
})()
""".trim()
}

/** 课表入口按钮：当前所在那张课表用实心，另一张描边，一眼看出「导入」会导哪一张。 */
@Composable
private fun ScheduleEntryButton(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonModifier = modifier.height(40.dp)
    if (active) {
        Button(onClick = onClick, modifier = buttonModifier, enabled = enabled) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = buttonModifier, enabled = enabled) { Text(label) }
    }
}

private sealed interface PageState {
    data object Loading : PageState
    data object Ready : PageState

    /**
     * 失败态。`retryUrl` 非空时必须改走该 URL——CAS ticket 是一次性票，
     * 对失败页 reload 只会重放已消费的票据（见 [JwImportDiagnosis.retryUrl]）。
     */
    data class Error(val title: String, val body: String, val retryUrl: String? = null) : PageState
}

/**
 * 待确认的导入草稿。考试安排与一键导入共用同一个弹窗，差别只在 [breakdown] 与 [defaultMerge]：
 * 一键导入要逐项列出「理论课表 N 条 / 实验课表 M 条」，并默认合并。
 */
private data class ImportDraft(
    val result: ImportParseResult.Success,
    val breakdown: List<Pair<String, Int>>? = null,
    val defaultMerge: Boolean = false,
    /** 理论课表页学期下拉的选项；确认弹窗的学期切换数据源（JSON/考试路径没有）。 */
    val termOptions: List<TermOption> = emptyList(),
)

/**
 * 落回主界面后那条气泡的文案（[JwImportResultBus]，DESIGN §3.3）。
 *
 * 一句话说清三件事：导了几门、覆盖还是合并、导到哪张表。[timetableName] 读不到时省掉那一截
 * （旧数据或课表刚被删）。
 */
private fun scheduleOutcomeText(count: Int, merge: Boolean, timetableName: String): String {
    val target = timetableName.takeIf { it.isNotBlank() }?.let { "到「$it」" }.orEmpty()
    return if (merge) {
        "已合并导入 $count 门新课$target（重复课程已跳过）"
    } else {
        // 覆盖写的是全部条数，说成「新课」会让人以为只是追加
        "已覆盖写入 $count 条课次$target，原有课程已清空"
    }
}

/**
 * 一键导入的页面加载闸门：`loadUrl` 之后等目标页面加载完（或失败）。
 *
 * 为什么需要它：`loadUrl` 是异步的，`onPageFinished` 才代表 DOM 可用；紧接着注入抽取脚本
 * 会在旧页面的 DOM 上跑（抽到上一次的内容，或什么都没有）。[urlPart] 用片段而不是整串——
 * 两张课表 URL 都含 `xskb`，整串比较会互相误判（DESIGN §4.8 记过同一个坑）。
 * 失败由 `reportFailure` 放行（false），超时由调用方兜底，两条路都不会把界面卡在「识别中」。
 */
private class PageLoadGate(val urlPart: String) {
    val done = CompletableDeferred<Boolean>()
}

/** 课表页 URL 的匹配片段（`JwUrls.SCHEDULE_LIST` / `JwUrls.LAB_SCHEDULE` 各自的特征段）。 */
private const val THEORY_URL_PART = "xskb_list.do"
private const val LAB_URL_PART = "syjx/toXskb"

/**
 * 单张课表页的加载超时。两页连着等，所以要短一些——教务本身两秒内就有响应，
 * 卡住多半是出口不通，早点报错比让用户对着「识别中…」发呆好。
 */
private const val PAGE_LOAD_TIMEOUT_MS = 20_000L

/**
 * 登录落地（xsMainV）→ 目标页之间留的可见过渡（同旧实现的 500ms 口径，微调为 400ms）。
 * 期间若用户已经离开主页（重定向链乱序）就不再跳。
 */
private const val AUTO_NAV_DELAY_MS = 400L

/**
 * 自动落页的看门狗：到点还没跳出去（会话探针回调没来 / 页面卡死）就把状态解锁成可操作。
 * 取 10 秒——正常落地后目标页早该开始加载了。
 */
private const val AUTO_NAV_TIMEOUT_MS = 10_000L

/**
 * 评教注入的原地看门狗（DESIGN §4.44）：注入脚本是「发起即返回」，ajax 静默失败时
 * 不会有任何页面事件。取 15 秒（比 [AUTO_NAV_TIMEOUT_MS] 宽）——token 往返 + 302 +
 * SPA 首屏都在这一段里；到点仍停在教务主页就往状态条给出路（刷新可重试）。
 */
private const val AUTO_EVAL_TIMEOUT_MS = 15_000L

/**
 * 顶部提示条：只留一行状态文字。
 *
 * 根因（也是白屏修复的一半）：旧版还有 URL 行 + 「统一认证/强智直登」两个入口按钮，
 * 文字换行数变化会让这一条的高度跟着变，挤压下方 WebView——容器被 resize 会触发
 * 硬件 surface 重建，表现就是页面闪一下变白。现在固定单行 + 省略，高度恒定；
 * 强智直登入口已移除（默认走学校统一认证，备用入口没有存在场景）。
 */
@Composable
private fun StatusStrip(note: String) {
    Text(
        note,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun ErrorOverlay(
    error: PageState.Error,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(error.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            error.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

/**
 * 电脑版 / 手机版开关（DESIGN §4.4）。
 *
 * 形态照骑行页「使用方式」chip：纯文字胶囊、**不用图标**（这是两态开关不是动作，
 * 当前态直接写在胶囊上；也免了猜 HugeIcons 名）。开 = 主色底「电脑版」，
 * 关 = 中性底「手机版」，点一下切到另一态。
 *
 * 命中区 48dp（胶囊本体约 32dp 高，外面套一层 [heightIn] + 点击），
 * 与右侧刷新 IconButton 的触摸高度对齐。
 */
@Composable
private fun JwDesktopModeChip(desktop: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(
                    if (desktop) scheme.primaryContainer else scheme.onSurface.copy(alpha = 0.07f),
                )
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                text = if (desktop) "电脑版" else "手机版",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (desktop) {
                    scheme.onPrimaryContainer
                } else {
                    scheme.onSurface.copy(alpha = 0.7f)
                },
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureForJw(webView: WebView) {
    // 根因（Redmi K70 / MIUI 实测）：硬件加速下课表页渲染完成后整面变白——
    // 但 DOM 正常、evaluateJavascript 能跑、导入功能完好，说明死的是 GPU 合成/显示层，
    // 不是加载流程。这是 MIUI WebView 合成层丢失的已知顽疾（上轮去掉容器 resize 只除掉了
    // 一个触发面，合成层照丢）。软件渲染绕开 GPU 合成，是这类「功能正常但显示全白」的
    // 确定性兜底；教务页是简单表格，CPU 绘制的性能足够。
    webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    webView.setBackgroundColor(android.graphics.Color.WHITE)

    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        loadsImagesAutomatically = true

        // 根因：教务登录页（/jsxsd/、Logon.do）**没有 <meta viewport>**，是 400–480px 固定宽的
        // 桌面布局；而原先 useWideViewPort = false 会忽略页面声明的 viewport，把它硬塞进
        // 屏幕宽度的视口里，桌面布局于是错位、被裁，看着就是「界面渲染不出来」。
        //
        // 改成允许页面用自身 viewport（无 meta 时退化为 980px），再由 WebView 整体缩放到屏幕：
        // 先保证「看得全」，细节交给双指放大。课表页/主页自带 width=device-width，同样受益。
        //
        // 2026-10-08 起业务页的 viewport 由 [JwWebFit.DESKTOP_JS] 覆盖成桌面宽度（电脑版预览，
        // 默认开、顶栏可切）——这两项是它的底座：有它们，"width=980" 才会被缩放到屏幕。
        useWideViewPort = true
        loadWithOverviewMode = true

        // 双指缩放的开关。注意：真正卡住"缩小"的是页面 meta 里的 initial-scale=1
        // （最小缩放被钳在 1.0），光开这两项不够——由电脑版覆盖脚本整串换掉 content 解决。
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        textZoom = 100
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
        mediaPlaybackRequiresUserGesture = false
    }
    webView.isHorizontalScrollBarEnabled = true
    webView.isVerticalScrollBarEnabled = true
    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
}

/**
 * 按页面类型注入适配样式（课表 / 登录表单 / 电脑版视口）；幂等，重复调用无副作用。
 *
 * 判据与三段脚本都在 [JwWebFit]（纯 Kotlin、有单测）；这里只负责"什么时候注"。
 * [desktop] = 窗口当前的电脑版开关，由调用点从 Compose 状态读出来传进来——
 * 本函数是文件级私有函数，读不到 Compose 状态。
 */
private fun applyPageFit(view: WebView?, url: String?, desktop: Boolean) {
    val js = JwWebFit.fitScript(url, desktop) ?: return
    view?.evaluateJavascript(js, null)
}

private const val TAG = "JwWebView"

/**
 * 布局后 reflow + 视口探针：给页面补派一次 resize（Compose 0×0 → 全屏的测量时序
 * 可能把页面按错误视口定格成白屏），并回读页面自身的布局数据用于 logcat 定位。
 */
private val VIEWPORT_PROBE_JS: String = """
(function(){
  try{
    window.dispatchEvent(new Event('resize'));
    return JSON.stringify({
      iw:window.innerWidth, ih:window.innerHeight,
      dw:document.documentElement.scrollWidth, dh:document.documentElement.scrollHeight,
      sx:window.scrollX, sy:window.scrollY,
      dpr:window.devicePixelRatio
    });
  }catch(e){ return 'err'; }
})()
""".trimIndent()

/**
 * 判断页面是不是「加载完了但什么都没渲染」。
 *
 * 返回文本长度 + 5 × 关键元素数，正常页面是三位数以上；
 * 小于 20 基本可以断定是空白页（而不是"只是排版乱"），这样可以给出准确的提示，
 * 而不是让用户面对白屏猜发生了什么。
 *
 * **有 iframe 的页面一律不算空**（2026-10-08 修）：选课中心是「壳页 + 三个 iframe」，
 * 真正的内容全在子文档里，顶层文档既没文字也没元素——旧口径必判「内容为空」，
 * 用户在选课中心就永远挂着一条「页面已加载但内容为空，可点重试」的假告警。
 * 探针是「白屏排查」用的启发式，不是「内容存在」的证明，宁可漏报不可误报。
 */
private val PAGE_CONTENT_PROBE_JS: String = """
(function(){
  try{
    var b=document.body;
    if(!b) return 0;
    if(b.querySelectorAll('iframe').length>0) return 999;
    var t=(b.innerText||'').replace(/\s+/g,'').length;
    var n=b.querySelectorAll('table,img,input,button,a').length;
    return t + n*5;
  }catch(e){ return 0; }
})()
""".trimIndent()

/**
 * 判定当前页是不是教务登录页（= 未登录）。
 *
 * 特征取自实测（2026-09-18，手机 5G 无代理）：
 * - 未登录的 `xsMainV` / `xskb_list` / `Logon.do` 都是 HTTP 200 就地渲染登录页，
 *   含 `id="loginDiv"` 与 `type="password"` 输入框，`<title>登录</title>`；
 * - 已登录的课表/主页快照里这两者均为 0 处（`scripts/out/xskb_vt0.html`、`syxkb.html`）。
 *
 * ⚠️ 不要改回按文案判定。曾用过「用户没有登录」，而实测该页面上此文案出现 **0 次**，
 * 那种判据永远不会命中（旧实现的「会话失效」提示因此从未生效过）。
 *
 * 2026-10-06 补 `conflict`：账号在别处登录时强智回一段**裸 JSON**
 * （`{"flag1":3,"msgContent":"您的账号在其它地方登录"}`，页面很小），
 * 登录页那两个特征都不出现，不单独认就会把它当正常页一路放行。
 * token 与 `JwImportDiagnosis.SESSION_CONFLICT_TOKEN` 逐字对齐。
 */
private val SESSION_PROBE_JS: String = """
(function(){
  try{
    var hasLoginDiv = !!document.getElementById('loginDiv');
    var hasPwd = !!document.querySelector('input[type=password]');
    var title = (document.title||'').trim();
    var text = (document.body && document.body.textContent) || '';
    var conflict = text.length < 4000 &&
        text.indexOf('其它地方登录') >= 0 && text.indexOf('flag1') >= 0;
    return (conflict?'conflict ':'') + (hasLoginDiv?'loginDiv ':'') + (hasPwd?'password ':'') + 'title=' + title;
  }catch(e){ return ''; }
})()
""".trimIndent()
