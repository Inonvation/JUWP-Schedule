package edu.jxslu.schedule.ui.jwvw

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.jw.ImportParseResult
import edu.jxslu.schedule.data.jw.JwSchedulePage
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.jw.QiangzhiScheduleParser
import edu.jxslu.schedule.data.jw.SyjxScheduleParser
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.ui.common.ImportTargetDialogHost
import edu.jxslu.schedule.ui.common.resolveImportTarget
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JwImportScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { Graph.repository(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf("学校统一身份认证") }
    var currentUrl by remember { mutableStateOf(JwUrls.ENTRY) }
    var canGoBack by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf<List<Course>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pageState by remember { mutableStateOf<PageState>(PageState.Loading) }
    var statusNote by remember { mutableStateOf("正在打开学校统一身份认证登录…") }
    var autoNavPending by remember { mutableStateOf(false) }

    // 与顶栏返回箭头同语义：WebView 能后退时先回退网页，否则才退出导入页。
    // 根因：系统返回手势默认直接 popBackStack，会把 WebView 的历史连同登录进度一起丢掉，
    // 和左上角箭头的行为不一致。
    BackHandler(enabled = canGoBack) { webView?.goBack() }

    fun runImport(wv: WebView?) {
        val target = wv ?: return
        // 解析器由当前页面决定：两张课表结构完全不同，选错了只会得到空结果
        val pageKind = JwUrls.schedulePageKind(currentUrl)
        val extractJs = when (pageKind) {
            JwSchedulePage.Theory -> QiangzhiScheduleParser.EXTRACT_JS
            JwSchedulePage.Lab -> SyjxScheduleParser.EXTRACT_JS
            JwSchedulePage.None -> {
                val msg = "当前不是课表页。请先点「理论课表」或「实验课表」，打开后再导入。"
                statusNote = msg
                scope.launch { snackbar.showSnackbar(msg) }
                return
            }
        }
        busy = true
        statusNote = if (pageKind == JwSchedulePage.Lab) "正在解析实验课…" else "正在解析课表…"
        target.evaluateJavascript(extractJs) { raw ->
            busy = false
            val payload = unwrapJsString(raw)
            val result = when (pageKind) {
                JwSchedulePage.Lab -> SyjxScheduleParser.parseExtractJson(payload)
                else -> QiangzhiScheduleParser.parseExtractJson(payload)
            }
            when (result) {
                is ImportParseResult.Failure -> {
                    statusNote = result.message
                    scope.launch { snackbar.showSnackbar(result.message) }
                }
                is ImportParseResult.Success -> {
                    val labCount = result.courses.count { it.kind == CourseKind.Lab }
                    statusNote = if (labCount > 0) {
                        "解析到 ${result.courses.size} 条，其中实验课 $labCount 条"
                    } else {
                        "解析到 ${result.courses.size} 条课次，确认后写入"
                    }
                    showMergeDialog = result.courses
                }
            }
        }
    }

    // 独立 Activity 窗口：inset 全部走 M3 默认——TopAppBar 消费状态栏、
    // Scaffold contentWindowInsets 提供底部导航栏 inset（导入按钮区不压手势条）。
    // 此前为「嵌在外层 Scaffold 里」做的双 inset 规避已随窗口拆分一起移除。
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("教务导入 · 统一认证", style = MaterialTheme.typography.titleMedium)
                        Text(
                            pageTitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        val wv = webView
                        if (wv != null && canGoBack) wv.goBack() else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            pageState = PageState.Loading
                            statusNote = "重新加载中…"
                            webView?.reload()
                        },
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
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
                                        canGoBack = view?.canGoBack() ?: false
                                        currentUrl = url ?: currentUrl
                                        statusNote = "加载中…"
                                        Log.d(TAG, "onPageStarted ${view?.width}x${view?.height} $url")
                                    }

                                    /** 首屏内容可见就先注入适配样式；等 onPageFinished 在图片多的页面上太晚。 */
                                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                                        Log.d(TAG, "onPageCommitVisible ${view?.width}x${view?.height} $url")
                                        applyPageFit(view, url)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        canGoBack = view?.canGoBack() ?: false
                                        currentUrl = url ?: currentUrl
                                        pageTitle = view?.title?.takeIf { it.isNotBlank() } ?: pageTitle
                                        Log.d(TAG, "onPageFinished ${view?.width}x${view?.height} $url")

                                        val u = url.orEmpty()
                                        if (u.isBlank() || u == "about:blank") {
                                            pageState = PageState.Error(
                                                title = "页面变为空白",
                                                body = "已加载到空页。可能会话未就绪，请点右上角刷新重试。",
                                            )
                                            statusNote = "空白页，可点右上角刷新重试"
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

                                        // 注意：两张课表的 URL 都含 "xskb"（理论 xskb_list、实验 toXskb），
                                        // 不能用子串判断，必须按页面类型区分，否则会互相误判
                                        val page = JwUrls.schedulePageKind(u)
                                        statusNote = when {
                                            "eapp2.juwp.edu.cn" in u ->
                                                "请使用学校统一身份认证登录"
                                            "xsMainV" in u ->
                                                "已登录教务主页，正在打开学期理论课表…"
                                            page == JwSchedulePage.Lab ->
                                                "实验课表已打开。点下方「导入实验课表」。"
                                            page == JwSchedulePage.Theory ->
                                                "理论课表已打开。点下方「导入理论课表」。"
                                            else -> "已登录教务，可从下方入口打开课表页"
                                        }

                                        if ("xsMainV" in u && !autoNavPending) {
                                            autoNavPending = true
                                            pageState = PageState.Loading
                                            // 500ms 只留一个「已登录」的可见过渡；跳转目标固定，
                                            // postDelayed 内仍会复核 currentUrl，重定向链乱序也不会跳错
                                            view?.postDelayed({
                                                if (currentUrl.contains("xsMainV")) {
                                                    view.loadUrl(JwUrls.SCHEDULE_LIST)
                                                } else {
                                                    autoNavPending = false
                                                }
                                            }, 500)
                                            return
                                        }
                                        if (page != JwSchedulePage.None) {
                                            autoNavPending = false
                                            pageState = PageState.Ready
                                            applyPageFit(view, u)
                                            return
                                        }
                                        applyPageFit(view, u)
                                        pageState = PageState.Ready
                                        // 加载完成 ≠ 渲染出东西：探测一下，白屏时给可操作提示，
                                        // 而不是让用户对着空页猜发生了什么
                                        view?.evaluateJavascript(PAGE_CONTENT_PROBE_JS) { raw ->
                                            val score = raw?.trim()?.removeSurrounding("\"")?.toFloatOrNull()
                                            if (score != null && score < 20f) {
                                                statusNote = "页面已加载但内容为空，可点右上角刷新重试"
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
                                        pageState = PageState.Error(
                                            title = "无法连接认证/教务",
                                            body = buildString {
                                                append("请确认能访问 eapp2.juwp.edu.cn 与 jiaowu.juwp.edu.cn。")
                                                if (desc.isNotBlank()) append("\n错误码 $code：$desc")
                                                append("\n${request?.url}")
                                            },
                                        )
                                        statusNote = "连接失败：$desc（$code）"
                                    }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: SslError?,
                                ) {
                                    handler?.proceed()
                                    statusNote = "已自动放行学校 HTTPS 证书"
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
                                loadUrl(JwUrls.ENTRY)
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
                            statusNote = "重试中…"
                            webView?.loadUrl(JwUrls.ENTRY)
                        },
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }

            val pageKind = JwUrls.schedulePageKind(currentUrl)
            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 两个入口分开：两张课表结构不同，走哪个入口就用哪个解析器，
                    // 不让用户在「导入」时再猜自己开的是哪一页
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ScheduleEntryButton(
                            label = "理论课表",
                            active = pageKind == JwSchedulePage.Theory,
                            enabled = !busy,
                            onClick = {
                                pageState = PageState.Loading
                                statusNote = "打开学期理论课表…"
                                autoNavPending = false
                                webView?.loadUrl(JwUrls.SCHEDULE_LIST)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        ScheduleEntryButton(
                            label = "实验课表",
                            active = pageKind == JwSchedulePage.Lab,
                            enabled = !busy,
                            onClick = {
                                pageState = PageState.Loading
                                statusNote = "打开实验课表（实践实验 → 实验课表查询）…"
                                autoNavPending = false
                                webView?.loadUrl(JwUrls.LAB_SCHEDULE)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = { runImport(webView) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        // 防误触：currentUrl 在 onPageFinished 才更新，页面没就绪就点导入
                        // 会拿旧 URL 判型、误报「当前不是课表页」。只在课表页且加载就绪时可用。
                        enabled = !busy &&
                            pageKind != JwSchedulePage.None &&
                            pageState == PageState.Ready,
                    ) {
                        Text(
                            when {
                                busy -> "解析中…"
                                pageKind == JwSchedulePage.Lab -> "导入实验课表"
                                pageKind == JwSchedulePage.Theory -> "导入理论课表"
                                else -> "打开课表页后可导入"
                            },
                        )
                    }
                }
            }
        }
    }

    showMergeDialog?.let { courses ->
        // 目标课表强制选择（DESIGN §4.9）：多课表之后「导到当前课表」不再是唯一合理解释，
        // 每次都让用户明确选目标（可新建）与覆盖/合并方式，避免静默覆盖正在用的数据
        ImportTargetDialogHost(
            courses = courses,
            title = when {
                courses.any { it.kind == CourseKind.Lab } && courses.all { it.kind == CourseKind.Lab } ->
                    "解析到 ${courses.size} 条实验课"
                courses.any { it.kind == CourseKind.Lab } ->
                    "解析到 ${courses.size} 条（实验课 ${courses.count { it.kind == CourseKind.Lab }} 条）"
                else -> "解析到 ${courses.size} 条课次"
            },
            // 教务实验课表与理论课表是两批数据，合并是更常见的意图
            defaultMerge = courses.any { it.kind == CourseKind.Lab },
            repo = repo,
            onConfirm = { target, merge ->
                showMergeDialog = null
                scope.launch {
                    val targetId = resolveImportTarget(repo, target)
                    repo.importParsedCourses(courses, merge, targetId)
                    // 导入到非当前课表后切过去，返回主界面直接看到结果
                    repo.setCurrentTimetable(targetId)
                    onBack()
                }
            },
            onDismiss = { showMergeDialog = null },
        )
    }
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
    data class Error(val title: String, val body: String) : PageState
}

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
        useWideViewPort = true
        loadWithOverviewMode = true

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
 * 课表页适配：让宽表格能横向滚动，并按屏幕调紧凑度与可读性。
 *
 * **不再改写 viewport meta**。课表页自带 `width=device-width`，改写它没有收益，
 * 反而会和 WebView 的 useWideViewPort / loadWithOverviewMode 两套缩放机制互相打架
 * （旧实现既改 meta 又关掉 useWideViewPort，方向是矛盾的）。
 *
 * 尺寸基准（用户拍板）：格子 min-width 110→96px、表格总宽 900→730px 同步下调——
 * 表格越窄，overview 缩放越接近 1:1，字在屏上就越大；同时教室/时间摘要文字
 * （`.qz-hasCourse-abbrinfo`，理论页为「老师/时间/地点」整段、实验页为地点）
 * 显式放大到 12.5px 并放宽行距，两页共用同一组 class，一并受益。
 */
private val SCHEDULE_FIT_JS: String = """
(function(){
  try{
    if(document.getElementById('juw-schedule-fit')) return;
    var css=document.createElement('style'); css.id='juw-schedule-fit';
    css.textContent=
      'html,body{overflow-x:auto !important;max-width:none !important;}'+
      'table.qz-weeklyTable{min-width:730px !important;}'+
      '#tableDiv,.qz-weeklyTableWrap,.qz-table-wrap{overflow-x:auto !important;}'+
      'td[name=kbDataTd]{min-width:96px !important;}'+
      '.qz-hasCourse-detailitem,.qz-hasCourse-abbrinfo{'+
        'font-size:12.5px !important;line-height:1.5 !important;}';
    (document.head||document.documentElement).appendChild(css);
  }catch(e){}
})()
""".trimIndent()

/**
 * 登录 / SSO 页适配：把固定宽表单收成页面宽度的百分比并居中。
 *
 * 同样不改写 viewport meta——这些页面没有 meta，正好交给 WebView 的
 * 「按页面宽度渲染 → 缩放到屏幕」来处理整页可见性；这里只补两件事：
 * 固定宽容器给百分比上限、表单控件不撑出屏幕。
 *
 * 注意：只对真正的登录页注入。学生端主页等桌面页不要碰，越改越乱。
 */
private val LOGIN_FIT_JS: String = """
(function(){
  try{
    if(document.getElementById('juw-login-fit')) return;
    var css=document.createElement('style'); css.id='juw-login-fit';
    css.textContent=
      '.login{height:auto !important;min-height:0 !important;overflow:visible !important;}'+
      '.form-container,.login-box,.login-container,.layui-form{'+
        'position:static !important;right:auto !important;top:auto !important;'+
        'width:92% !important;max-width:560px !important;height:auto !important;'+
        'overflow:visible !important;margin:28px auto !important;}'+
      'input,select,textarea{box-sizing:border-box !important;max-width:100% !important;}'+
      '.layui-input-wrap>input{width:100% !important;}'+
      'img{max-width:100% !important;height:auto !important;}';
    (document.head||document.documentElement).appendChild(css);
  }catch(e){}
})()
""".trimIndent()

/** 只有真正的登录/SSO 页才需要表单兜底。 */
private fun isLoginLikeUrl(url: String): Boolean =
    "/cas/login" in url || "Logon.do" in url || "/sso.jsp" in url

/** 按页面类型注入适配样式；幂等，重复调用无副作用。 */
private fun applyPageFit(view: WebView?, url: String?) {
    val u = url.orEmpty()
    if (u.isBlank() || u == "about:blank") return
    val js = when {
        JwUrls.schedulePageKind(u) != JwSchedulePage.None -> SCHEDULE_FIT_JS
        isLoginLikeUrl(u) -> LOGIN_FIT_JS
        else -> return
    }
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
 */
private val PAGE_CONTENT_PROBE_JS: String = """
(function(){
  try{
    var b=document.body;
    if(!b) return 0;
    var t=(b.innerText||'').replace(/\s+/g,'').length;
    var n=b.querySelectorAll('table,img,input,button,a').length;
    return t + n*5;
  }catch(e){ return 0; }
})()
""".trimIndent()

private fun unwrapJsString(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    var s = raw.trim()
    if (s == "null") return ""
    if (s.startsWith("\"") && s.endsWith("\"")) {
        s = s.substring(1, s.length - 1)
    }
    return s
        .replace("\\\\", "\\")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
}
