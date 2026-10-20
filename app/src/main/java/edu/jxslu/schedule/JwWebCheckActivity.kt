package edu.jxslu.schedule

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import edu.jxslu.schedule.data.jw.JwAutoLogin
import edu.jxslu.schedule.data.jw.JwImportDiagnosis
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.session.SessionStatus
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import edu.jxslu.schedule.ui.jwvw.JwWebFit
import edu.jxslu.schedule.ui.theme.JuwTheme
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Refresh01

/**
 * 教务网页核对（2026-10-20，新 IA 的「网页」二级动作）。
 *
 * 只做展示：把教务/学工/签章系统的原始页面放进 WebView，让用户对 App 里的汇总数字。
 *
 * ## 为什么会「一片空白」（2026-10-20 真机 + 模拟器实测）
 *
 * 教务在没有会话时不跳登录页，而是**就地回一小段 JSON**（`{"flag":2,"msgContent":…}`），
 * WebView 把它当文本渲染 —— 看起来就是一张白页。会话又只在 WebView 自己登录过之后
 * 才在 `CookieManager` 里（把 OkHttp 的 cookie 注入 WebView 走不通，见 `JwAutoLogin`）。
 * 所以这里的流程是：
 *
 * 1. 先开目标页；
 * 2. 落定后探针判定「未登录 / 会话被顶」（[JwImportDiagnosis]，与导入页同一套判据）；
 * 3. 未登录 → 回统一认证入口，用已存凭证**自动填表**（[JwAutoLogin]，只尝试一次）；
 * 4. 登录落回教务后，自动重开第 1 步的目标页。
 *
 * 另外三条与导入页 `configureForJw` 对齐，缺一个都会出现"空白/显示不全"：
 * - `LAYER_TYPE_SOFTWARE`：MIUI 上硬件加速会把教务页合成成整面白屏；
 * - `useWideViewPort + loadWithOverviewMode`：教务页多是没有 `<meta viewport>` 的
 *   固定宽桌面布局，靠这两项整页缩放到屏幕，否则右侧被裁；
 * - 页面加载完注入 [JwWebFit]（登录页收表单、课表页放开横向滚动）。
 */
class JwWebCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enablePredictiveBackTransitions()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "网页核对" }
        setContent {
            JuwTheme {
                JwWebCheckScreen(
                    url = url,
                    title = title,
                    onBack = { finish() },
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        applySubpageCloseTransition(this)
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String, url: String) {
            context.startActivity(
                Intent(context, JwWebCheckActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, title),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun JwWebCheckScreen(
    url: String,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val savedCas = remember { Graph.credentialVault(context).readCas() }

    var progress by remember { mutableFloatStateOf(0f) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // 登录前想看的页：自动登录完成后回到它。
    var pendingTarget by remember { mutableStateOf<String?>(null) }
    // 已经回过一次统一认证入口（挡死循环；凭证缺失时交给用户手登）。
    var loginAttempted by remember { mutableStateOf(false) }
    // 已经自动填过一次表（CAS 页可能反复回调 onPageFinished）。
    var autoLoginTried by remember { mutableStateOf(false) }
    var lastProbedUrl by remember { mutableStateOf("") }
    // 窗口销毁后 JS 回调仍可能晚到一帧：对已 destroy 的 WebView 再 loadUrl 会抛。
    var destroyed by remember { mutableStateOf(false) }

    fun probeSession(view: WebView, pageUrl: String) {
        if (lastProbedUrl == pageUrl) return
        lastProbedUrl = pageUrl
        view.evaluateJavascript(SESSION_PROBE_JS) { raw ->
            if (destroyed) return@evaluateJavascript
            val probe = unquote(raw)
            // 只记探针原文，不记页面 URL（部分接口的地址带一次性参数）。
            Log.d(TAG, "session probe: '$probe'")
            val lost = looksUnauthenticated(probe)
            if (!lost) {
                // 已登录：登录前记下的目标页在这里补开。
                val target = pendingTarget
                if (target != null && target != pageUrl) {
                    pendingTarget = null
                    view.loadUrl(target)
                }
                return@evaluateJavascript
            }
            if (!loginAttempted) {
                loginAttempted = true
                pendingTarget = pageUrl
                view.loadUrl(JwUrls.ENTRY)
            } else {
                pendingTarget = null
                error = "教务会话失效，没能自动恢复。请先在「从教务导入」里登录一次。"
            }
        }
    }

    fun tryAutoLogin(view: WebView, pageUrl: String) {
        if (autoLoginTried) return
        // 没存凭证就停在登录页让用户手登：这条链路的 cookie 只能由 CAS 亲自下发。
        val cred = savedCas ?: return
        if (!SessionStatus.tryAcquireAutoLogin(System.currentTimeMillis())) return
        autoLoginTried = true
        view.evaluateJavascript(JwAutoLogin.fillJs(cred.username, cred.password)) { raw ->
            if (destroyed) return@evaluateJavascript
            // 填表失败不弹错：登录页还在，用户可以直接手输。
            unquote(raw).orEmpty().startsWith(JwAutoLogin.OK_PREFIX)
        }
    }

    val webView = remember {
        WebView(context).apply {
            configureForWebCheck()
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    loading = true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                    val u = url.orEmpty()
                    if (view == null) return
                    // 登录页收表单、课表页放开横向滚动；其余页面返回 null，不注入。
                    JwWebFit.fitScript(u, desktop = false)?.let { js ->
                        view.evaluateJavascript(js, null)
                    }
                    // 补派一次 resize：AndroidView 的首帧宽度可能不是最终值，
                    // 页面会按错误视口定格（导入页同样处理）。
                    view.evaluateJavascript(RESIZE_JS, null)

                    when {
                        JwUrls.isCasHost(u) && JwWebFit.isLoginLikeUrl(u) -> tryAutoLogin(view, u)
                        JwUrls.isJwHost(u) -> probeSession(view, u)
                        else -> Unit
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    err: WebResourceError?,
                ) {
                    // 只处理主文档错误：子资源失败不该把整页判死。
                    if (request?.isForMainFrame != true) return
                    loading = false
                    error = err?.description?.toString().orEmpty().ifBlank { "页面打不开" }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    response: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame != true) return
                    val code = response?.statusCode ?: return
                    if (code >= 500) {
                        loading = false
                        error = "教务返回 HTTP $code，稍后重试或先登录一次。"
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = newProgress / 100f
                    if (newProgress >= 100) loading = false
                }
            }
            loadUrl(url)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            destroyed = true
            webView.destroy()
        }
    }
    BackHandler(enabled = webView.canGoBack()) { webView.goBack() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = pinnedStatusBars(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text(title)
                        Text(
                            text = when {
                                pendingTarget == null -> "网页核对 · 以网页数据为准"
                                savedCas != null -> "正在重新登录教务…"
                                else -> "需要先登录教务"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { webView.reload() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )
            if (loading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }
            if (pendingTarget != null && !loading) {
                // 正走在统一认证链路上：轻提示，别让用户以为卡在空白页。
                // 不铺遮罩——没有存凭证时登录表单要留给用户手输。
                Text(
                    text = if (savedCas != null) "正在重新登录教务…"
                    else "登录后会回到「$title」",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                )
            }
            error?.let { message ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "页面没能打开",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        error = null
                        autoLoginTried = false
                        loginAttempted = false
                        lastProbedUrl = ""
                        webView.loadUrl(url)
                    }) { Text("重新加载") }
                }
            }
        }
    }
}

/**
 * WebView 配置：逐条对齐导入页 `configureForJw`，理由见类注释。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForWebCheck() {
    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    setBackgroundColor(android.graphics.Color.WHITE)

    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        loadsImagesAutomatically = true
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
    isHorizontalScrollBarEnabled = true
    isVerticalScrollBarEnabled = true

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@configureForWebCheck, true)
        // 与 App 内其它 WebView 共用的会话可能还在内存里，先落盘再开新页。
        flush()
    }
}

/**
 * 会话探针：与导入页 `SESSION_PROBE_JS` 同口径，另加强智「就地 JSON」的两种形态。
 *
 * 返回一行标记文本；判据在 [JwImportDiagnosis]（纯逻辑、有单测），这里不重复判定。
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
    return (conflict?'conflict ':'') + (hasLoginDiv?'loginDiv ':'') + (hasPwd?'password ':'') +
        'title=' + title + ' body=' + text.slice(0, 200);
  }catch(e){ return ''; }
})()
""".trimIndent()

private const val TAG = "JwWebCheck"

/**
 * 这一页是不是「没登录」。
 *
 * 三种形态，缺一种就会把白页当正常页放过去：
 * - 教务把未登录就地渲染成登录页（`loginDiv` + 密码框），URL 不变；
 * - 会话被顶：`{"flag1":3,"msgContent":"您的账号在其它地方登录"}`；
 * - **纯未登录：`{"flag1":2,"msgContent":"请先登录系统"}`**（2026-10-20 模拟器实测，
 *   WebView 把这段 JSON 当文本画在左上角，其余全白——就是用户看到的"空白页"）。
 *   注意键名是 `flag1`，不是 `flag`；早先按 `"flag":2` 判会漏。
 */
private fun looksUnauthenticated(probe: String?): Boolean {
    val t = probe ?: return false
    if (JwImportDiagnosis.looksLikeLoginPage(t)) return true
    if (JwImportDiagnosis.looksLikeSessionConflict(t)) return true
    return "\"flag1\":" in t || "\"flag\":" in t
}

private const val RESIZE_JS = """
(function(){ try{ window.dispatchEvent(new Event('resize')); }catch(e){} })()
"""

/** `evaluateJavascript` 的回调给的是 JSON 字面量（`"..."`），去掉引号与转义。 */
private fun unquote(raw: String?): String? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty() || s == "null") return null
    if (!s.startsWith("\"") || !s.endsWith("\"") || s.length < 2) return s
    return runCatching {
        org.json.JSONTokener(s).nextValue() as? String
    }.getOrNull() ?: s.trim('"')
}
