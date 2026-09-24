package edu.jxslu.schedule.ui.score

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import edu.jxslu.schedule.BuildConfig
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.jw.PtworkTranscript
import edu.jxslu.schedule.data.jw.TranscriptCookies
import edu.jxslu.schedule.data.jw.TranscriptException
import edu.jxslu.schedule.data.jw.TranscriptTerm
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导出成绩单（DESIGN §3.14 / §4.25）：选学期 → 取教务处盖章 PDF → 打开 / 分享 / 另存。
 *
 * 与「成绩查询」页的分工：那里看的是**本地成绩库**（强智 `cjcx_list`），这里出的是
 * **教务处签章系统生成的正式单据**（含姓名学号证件照与教务处成绩专用章，评优评先交的就是它）。
 * 两者数据源不同，所以这一页不读本地成绩、也不往本地写。
 *
 * 会话按需授权：先拿 WebView 里已有的 `sid` 直接调接口（多数情况下一屏就够），失效时才把授权
 * WebView 顶上来（统一认证 TGC 还在时会免密，通常一两秒）。账号密码不落任何存储，
 * 与「教务导入」同一条纪律（DESIGN §4.4）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val client = remember { Graph.transcriptClient() }
    val store = remember { Graph.transcriptStore(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var stage by remember { mutableStateOf<TxStage>(TxStage.Checking) }
    var terms by remember { mutableStateOf<List<TranscriptTerm>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var authVisible by remember { mutableStateOf(false) }
    var savedFile by remember { mutableStateOf<File?>(null) }
    var savedTerms by remember { mutableStateOf<List<String>>(emptyList()) }

    fun notify(message: String, tone: NoticeTone) {
        scope.launch { snackbar.showSnackbar(AppNoticeVisuals(message, tone = tone)) }
    }

    fun loadTerms() {
        scope.launch {
            stage = TxStage.Checking
            val cookie = TranscriptCookies.sessionHeader()
            if (cookie == null) {
                stage = TxStage.NeedLogin("还没登录学校统一认证")
                return@launch
            }
            try {
                val list = client.fetchTerms(cookie)
                terms = list
                // 默认全选：完整成绩单是评优评先的常用形态，少选一个学期交上去比多选更糟
                selected = list.map { it.term }.toSet()
                stage = if (list.isEmpty()) TxStage.Empty else TxStage.Ready
            } catch (e: TranscriptException.SessionExpired) {
                stage = TxStage.NeedLogin("统一认证会话已过期")
            } catch (e: Throwable) {
                stage = TxStage.Failed(friendlyMessage(e), relogin = false)
            }
        }
    }

    fun export() {
        // 按学期倒序取出勾选项：文件名的「等 N 个学期」标签取第一个（= 最新）
        val picked = terms.map { it.term }.filter { it in selected }
        if (picked.isEmpty()) {
            notify("先选至少一个学期", NoticeTone.Warning)
            return
        }
        scope.launch {
            stage = TxStage.Exporting
            try {
                val cookie = TranscriptCookies.sessionHeader()
                    ?: throw TranscriptException.SessionExpired()
                val bytes = client.export(picked, cookie)
                val name = PtworkTranscript.defaultFileName(picked, System.currentTimeMillis())
                val file = withContext(Dispatchers.IO) { store.save(bytes, name) }
                savedFile = file
                savedTerms = picked
                stage = TxStage.Done
            } catch (e: TranscriptException.SessionExpired) {
                stage = TxStage.NeedLogin("统一认证会话已过期")
            } catch (e: Throwable) {
                stage = TxStage.Failed(friendlyMessage(e), relogin = false)
            }
        }
    }

    // 进页先探一次会话：有 sid 直接出学期列表，没有才请用户授权
    LaunchedEffect(Unit) { loadTerms() }

    // 授权层打开时，返回键先收授权层，不直接关窗口
    BackHandler(enabled = authVisible) { authVisible = false }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PDF_MIME),
    ) { uri ->
        val file = savedFile
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val sink = context.contentResolver.openOutputStream(uri)
                        ?: error("无法写入所选位置")
                    sink.use { out -> file.inputStream().use { it.copyTo(out) } }
                }
            }
            if (result.isSuccess) {
                notify("已保存到所选位置", NoticeTone.Success)
            } else {
                notify("保存失败：${result.exceptionOrNull()?.message.orEmpty()}", NoticeTone.Error)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导出成绩单", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val current = stage) {
                TxStage.Checking -> LoadingHint("正在读取可选学期", Modifier.fillMaxSize())

                TxStage.Empty -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    EmptyHint(
                        title = "教务里没有可导出的成绩",
                        body = "签章系统按学期出单，当前查不到任何成绩记录。\n" +
                            "如果刚考完还没出分，等教务录入后再来。",
                        actionLabel = "重新读取",
                        onAction = { loadTerms() },
                    )
                }

                is TxStage.NeedLogin -> NeedLoginContent(
                    message = current.message,
                    onAuthorize = { authVisible = true },
                    onRetry = { loadTerms() },
                )

                TxStage.Ready -> ReadyContent(
                    terms = terms,
                    selected = selected,
                    onToggle = { term ->
                        selected = if (term in selected) selected - term else selected + term
                    },
                    onSelectAll = { selected = terms.map { it.term }.toSet() },
                    onClear = { selected = emptySet() },
                    onExport = { export() },
                )

                TxStage.Exporting -> LoadingHint(
                    title = "正在生成成绩单",
                    modifier = Modifier.fillMaxSize(),
                    subtitle = "教务处出单与签章通常需要几秒",
                )

                TxStage.Done -> DoneContent(
                    file = savedFile,
                    termLabel = termLabel(savedTerms),
                    onOpen = { savedFile?.let { openPdf(context, store.uriOf(it), ::notify) } },
                    onShare = { savedFile?.let { sharePdf(context, store.uriOf(it), ::notify) } },
                    onSaveToDownloads = { savedFile?.let { saveLauncher.launch(it.name) } },
                    onExportAgain = { stage = TxStage.Ready },
                )

                is TxStage.Failed -> FailedContent(
                    message = current.message,
                    relogin = current.relogin,
                    onRetry = { loadTerms() },
                    onAuthorize = { authVisible = true },
                )
            }

            if (authVisible) {
                AuthorizationOverlay(
                    modifier = Modifier.fillMaxSize(),
                    onAuthorized = {
                        authVisible = false
                        loadTerms()
                    },
                    onClose = { authVisible = false },
                )
            }
        }
    }
}

/** 页面状态。学期与勾选分开存（它们跨状态存活，失败重试后仍保留）。 */
private sealed interface TxStage {
    data object Checking : TxStage
    data class NeedLogin(val message: String) : TxStage
    data object Empty : TxStage
    data object Ready : TxStage
    data object Exporting : TxStage
    data object Done : TxStage
    data class Failed(val message: String, val relogin: Boolean) : TxStage
}

@Composable
private fun NeedLoginContent(
    message: String,
    onAuthorize: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "成绩单由教务处签章系统出具，需要一次统一认证登录。\n" +
                "登录只在校内网页里进行，App 不保存账号密码。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAuthorize) { Text("去登录") }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onRetry) { Text("已经登录过了，重试") }
    }
}

@Composable
private fun ReadyContent(
    terms: List<TranscriptTerm>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                AppCard {
                    Text(
                        "江西水利电力大学学生成绩表",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "由教务处签章系统出具，含证件照与教务处成绩专用章。" +
                            "多选学期会合并到同一张成绩表。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已选 ${selected.size} / ${terms.size} 个学期",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSelectAll) { Text("全选") }
                    TextButton(onClick = onClear) { Text("全不选") }
                }
            }
            items(terms, key = { it.term }) { term ->
                TermRow(
                    term = term,
                    checked = term.term in selected,
                    onToggle = { onToggle(term.term) },
                )
            }
            item {
                Text(
                    "签章系统只提供 HTTP 明文通道（校内网），成绩单与登录票据都在明文里传输。" +
                        "导出的文件保存在本应用私有目录，不上传任何地方。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Surface(tonalElevation = 3.dp) {
            Button(
                onClick = onExport,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .height(44.dp),
            ) {
                Text(if (selected.isEmpty()) "先选学期" else "导出成绩单（${selected.size} 个学期）")
            }
        }
    }
}

@Composable
private fun TermRow(term: TranscriptTerm, checked: Boolean, onToggle: () -> Unit) {
    AppCardRow(
        onClick = onToggle,
        onClickLabel = if (checked) "取消选择 ${term.term}" else "选择 ${term.term}",
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                term.term,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${term.courseCount} 门课程",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun DoneContent(
    file: File?,
    termLabel: String,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSaveToDownloads: () -> Unit,
    onExportAgain: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        AppCard {
            Text(
                "成绩单已生成",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(termLabel, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(file?.name, sizeText(file)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        SectionHeader(title = "接下来")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("打开") }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("分享") }
            OutlinedButton(onClick = onSaveToDownloads, modifier = Modifier.weight(1f)) {
                Text("存到下载")
            }
        }
        Spacer(Modifier.height(10.dp))
        InlineNoticeRow(
            "文件在应用私有目录，只保留最近 10 份；要长期留存请「存到下载」或分享出去。",
            NoticeTone.Info,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onExportAgain, modifier = Modifier.fillMaxWidth()) {
            Text("再导一份")
        }
    }
}

@Composable
private fun FailedContent(
    message: String,
    relogin: Boolean,
    onRetry: () -> Unit,
    onAuthorize: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        InlineNoticeRow(message, NoticeTone.Error)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onRetry) { Text("重试") }
            if (relogin) Button(onClick = onAuthorize) { Text("重新登录") }
        }
    }
}

/**
 * 统一认证授权层。
 *
 * 只做一件事：把 CAS 那一跳走完，让签章系统在 WebView 里写下 `sid`。落点一到
 * 「签章系统域且不是它自己的登录页」就自动回调（判定见 [PtworkTranscript.isAuthorizedLanding]）。
 * 底部留一个手动「继续」按钮：自动识别万一失灵（页面结构变化），用户仍能往下走。
 */
@Composable
private fun AuthorizationOverlay(
    modifier: Modifier = Modifier,
    onAuthorized: () -> Unit,
    onClose: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    // 自动识别会连着触发多次（落地后还有后续页面加载与非主框架回调），只认第一次
    var fired by remember { mutableStateOf(false) }
    val authorizeOnce: () -> Unit = {
        if (!fired) {
            fired = true
            onAuthorized()
        }
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "登录学校统一认证",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "登录成功后会自动返回",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭登录页")
            }
        }
        problem?.let {
            InlineNoticeRow(
                message = it,
                tone = NoticeTone.Warning,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        AndroidView(
            modifier = Modifier.weight(1f),
            factory = { ctx ->
                // 与教务导入同一手法：套一层 FrameLayout 当壳，Compose 重排时摘挂的是壳，
                // WebView 本体不被直接摘挂（否则部分机型会丢渲染面）
                FrameLayout(ctx).apply {
                    val view = WebView(ctx).apply {
                        configureForPtworkAuth(this)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                if (PtworkTranscript.isAuthorizedLanding(url)) authorizeOnce()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (PtworkTranscript.isAuthorizedLanding(url)) authorizeOnce()
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                // 只有主框架失败才值得打扰用户：图标/CSS 这类子资源失败不影响登录
                                if (request?.isForMainFrame == true) {
                                    problem = "登录页打不开：${error?.description?.toString().orEmpty()}"
                                }
                            }

                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?,
                            ) {
                                // 只放行学校域（白名单见 JwUrls.TRUSTED_SSL_HOSTS）：这个 WebView
                                // 里就是统一认证的密码表单，无条件 proceed 等于关掉传输层校验
                                val host = error?.url?.let { JwUrls.hostOf(it) }
                                if (host != null && host in JwUrls.TRUSTED_SSL_HOSTS) {
                                    handler?.proceed()
                                } else {
                                    handler?.cancel()
                                    problem = "证书校验失败，已阻止访问（$host）"
                                }
                            }
                        }
                        webChromeClient = WebChromeClient()
                        loadUrl(PtworkTranscript.CAS_ENTRY)
                    }
                    webView = view
                    addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            },
            update = { /* 状态在 Compose 侧持有，不需要主动刷新 */ },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    problem = null
                    webView?.reload()
                },
            ) { Text("重新加载") }
            Spacer(Modifier.weight(1f))
            Button(onClick = authorizeOnce) { Text("已登录，继续") }
        }
    }
}

/**
 * 授权 WebView 的配置。
 *
 * 软件渲染与宽视口两点照抄教务导入窗口（DESIGN §4.4.1）：MIUI 的 WebView 在硬件加速下
 * 会丢合成层导致白屏；统一认证页没有 `<meta viewport>`，不让页面用自身视口会错位。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun configureForPtworkAuth(webView: WebView) {
    webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    webView.setBackgroundColor(android.graphics.Color.WHITE)
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        textZoom = 100
        // CAS 登录页在 HTTPS（eapp2），回跳是 HTTP（jwxyxx），同一跳里混内容
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    if (BuildConfig.DEBUG) {
        // 只给 debug：release 放开等于把用户登录会话暴露给 chrome://inspect
        WebView.setWebContentsDebuggingEnabled(true)
    }
}

private const val PDF_MIME = "application/pdf"

/** 把异常翻成用户能照着做点什么的话。 */
private fun friendlyMessage(e: Throwable): String = when (e) {
    is TranscriptException.NoContent -> e.message ?: "没有可导出的成绩"
    is TranscriptException.Network -> "网络不可达，检查是否开了 VPN 或代理"
    is TranscriptException.Protocol -> e.message ?: "签章系统接口变了"
    is TranscriptException.SessionExpired -> e.message ?: "需要重新登录统一认证"
    else -> e.message ?: "导出失败"
}

/** 「2025-2026-2 等 3 个学期」。标签取第一个（调用方按学期倒序传）。 */
private fun termLabel(terms: List<String>): String = when {
    terms.isEmpty() -> "全部学期"
    terms.size == 1 -> terms.first()
    else -> "${terms.first()} 等 ${terms.size} 个学期"
}

private fun sizeText(file: File?): String? {
    val bytes = file?.length() ?: return null
    return if (bytes >= 1024 * 1024) {
        "%.1f MB".format(bytes / 1024.0 / 1024.0)
    } else {
        "%d KB".format(bytes / 1024)
    }
}

private fun openPdf(context: Context, uri: Uri, notify: (String, NoticeTone) -> Unit) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, PDF_MIME)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val ok = runCatching { context.startActivity(intent) }.isSuccess
    if (!ok) notify("没有能打开 PDF 的应用，可以改用「分享」", NoticeTone.Warning)
}

private fun sharePdf(context: Context, uri: Uri, notify: (String, NoticeTone) -> Unit) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(PDF_MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val ok = runCatching {
        context.startActivity(Intent.createChooser(send, "分享成绩单"))
    }.isSuccess
    if (!ok) notify("没有可用的分享目标", NoticeTone.Warning)
}
