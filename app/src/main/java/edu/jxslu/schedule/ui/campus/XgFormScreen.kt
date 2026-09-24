package edu.jxslu.schedule.ui.campus

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.jw.JwAutoLogin
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.jw.unwrapJsString
import edu.jxslu.schedule.data.session.CasEnsureResult
import edu.jxslu.schedule.data.xg.XgForm
import edu.jxslu.schedule.data.xg.XgUrls
import kotlinx.coroutines.launch

private const val TAG = "XgForm"

/**
 * 学工表单窗口（DESIGN §3.15 / §4.26）：把学工系统的官方移动页装进一个窗口。
 *
 * 报修、请假共用这一页，差别只有 [form] 里的表单标识。填表、传附件、提交、查流程
 * 都由官方页面自己完成，App 不代劳：申请请求里带着一组服务端下发的动态值
 * （`pageEnc` / `traceId` / `nodeUniqueId`），复刻一遍的代价与失效风险都远大于收益。
 *
 * 起点是统一认证入口而不是表单页本身，顺序不能反：表单页匿名可访问，它的登录引导
 * 指向超星 passport（手机号 + 学习通密码），先开表单会把人带到一套他从没用过的账号上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XgFormScreen(form: XgForm, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 会话层单例（DESIGN §4.27）：打开前准备好 CAS 会话，学工那一跳就是免密的
    val cas = remember { Graph.casSession(context) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var statusNote by remember { mutableStateOf(XgUrls.statusHint(null, form.title)) }
    // 落在统一认证登录页时用保存的凭证自动登一次（DESIGN §4.4.1）。只试一次：
    // 失败（结构变了 / 有验证码）就交给用户手登，反复试只会撞风控。
    val savedCas = remember { Graph.credentialVault(context).readCas() }
    var autoLoginTried by remember { mutableStateOf(false) }
    // 直达表单页只尝试一次：落到学工首页之后还要再跳一层。只在「第一次落到学工域」时跳，
    // 之后用户点右上角回首页不会再被弹进表单。
    var directAttempted by remember { mutableStateOf(false) }

    // 网页里点「上传附件」时，WebView 会把文件选择回调交出来，等系统选择器的结果。
    // 期间必须一直握着它：提前丢掉页面会永远停在「上传中」；而结果回来后又必须立刻
    // 清掉——同一个 callback 消费两次会抛 IllegalStateException。
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = fileCallback
        fileCallback = null
        callback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
    }

    // 与左上角箭头同语义：网页能后退就先回退，退不动了才关窗口。表单流程要连着走
    // 「首页 → 服务 → 列表 → 表单」好几层，直接关窗口会让用户以为自己的操作丢了。
    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(form.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 直达就没有网页历史可退。这张表单若被管理员改过（页面会提示
                    // 「表单信息不存在」），或者用户要去看已提交的流程，得有路回学工首页。
                    IconButton(onClick = { webView?.loadUrl(XgUrls.HOME) }) {
                        Icon(Icons.Filled.Home, contentDescription = "学工首页")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = statusNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AndroidView(
                // weight 而不是 fillMaxSize：Column 里 fillMaxSize 请求的是父约束的整个高度，
                // 会把上面那条状态文案挤出可视区（教务导入页也是这么处理的）。
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { ctx ->
                    // 套一层 FrameLayout 作壳：Compose 重排时摘挂的是壳，WebView 本体
                    // 不再被直接摘挂/重挂（与教务导入同一处理，见 JwImportScreen）。
                    FrameLayout(ctx).apply {
                        val wv = WebView(ctx).apply {
                            configureForXg(this)
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
                                    statusNote = XgUrls.statusHint(url, form.title)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    canGoBack = view?.canGoBack() ?: false
                                    statusNote = XgUrls.statusHint(url, form.title)
                                    // 停在统一认证登录页 → 用保存的凭证自动登一次。
                                    // 走「WebView 自己填表提交」而不是注入 cookie：注入的
                                    // cookie 在真机上不被采用（属性缺 SameSite，跨站跳转不带，
                                    // DESIGN §4.4.1），而填表提交的 cookie 由 CAS 亲自下发。
                                    if (!autoLoginTried && XgUrls.isCasHost(url)) {
                                        savedCas?.let { cred ->
                                            autoLoginTried = true
                                            view?.evaluateJavascript(
                                                JwAutoLogin.fillJs(cred.username, cred.password),
                                            ) { raw ->
                                                val r = unwrapJsString(raw)
                                                Log.d(TAG, "auto-login on CAS page: $r")
                                                if (r?.startsWith(JwAutoLogin.OK_PREFIX) != true) {
                                                    statusNote = "自动登录没走通，请在下方页面手动登录一次"
                                                }
                                            }
                                        }
                                    }
                                    // 报修单/请假单本身匿名可访问，它的登录引导指向超星 passport，
                                    // 不是学校统一认证。所以顺序必须是：先走 /sfrz/ 让学工把
                                    // office 的会话 cookie 写好，再进表单页。
                                    if (!directAttempted && XgUrls.shouldEnterForm(url)) {
                                        directAttempted = true
                                        view?.loadUrl(form.applyUrl)
                                    }
                                }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: SslError?,
                                ) {
                                    // 只放行学校域的证书错误（白名单唯一实现在
                                    // JwUrls.TRUSTED_SSL_HOSTS），其余一律取消。
                                    // 无条件 proceed 等于关掉整个 WebView 的传输层校验，
                                    // 而统一认证的登录表单就在这个 WebView 里。
                                    val host = error?.url?.let { JwUrls.hostOf(it) }
                                    if (host != null && host in JwUrls.TRUSTED_SSL_HOSTS) {
                                        handler?.proceed()
                                        statusNote = "已放行学校 HTTPS 证书"
                                    } else {
                                        handler?.cancel()
                                        Log.w(TAG, "ssl error cancelled: host=$host url=${error?.url}")
                                    }
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onShowFileChooser(
                                    view: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?,
                                ): Boolean {
                                    if (filePathCallback == null || fileChooserParams == null) {
                                        return false
                                    }
                                    // 上一个还没消费就来了新的：把旧的按「取消」了结，
                                    // 否则页面永远等在那里（系统选择器不支持并发）。
                                    fileCallback?.onReceiveValue(null)
                                    fileCallback = filePathCallback
                                    return try {
                                        filePicker.launch(fileChooserParams.createIntent())
                                        true
                                    } catch (e: Exception) {
                                        // 设备上没有能处理该 Intent 的选择器时退回 false，
                                        // 让页面自己报「不支持上传」，而不是卡在打不开的选择器上
                                        Log.w(TAG, "file chooser unavailable", e)
                                        fileCallback = null
                                        false
                                    }
                                }
                            }
                            // 起点必须是统一认证入口，不能直接开表单页（理由见上面 onPageFinished）。
                            // 未登录时停在 CAS 登录页，登录完自动落到学工，再自动进表单。
                            //
                            // 会话准备放在 loadUrl 之前（DESIGN §4.27）：存过凭证时会把 CAS 登录
                            // 做完、cookie 注入 CookieManager；没凭证时什么都不做，退化成旧行为。
                            scope.launch {
                                // 与教务导入同一套快 / 慢路径（DESIGN §4.27）：有现成会话就本地注入后
                                // 直接加载；没有就先加载（不能白屏等登录），后台补完再重载一次。
                                if (cas.cookies().isNotEmpty()) {
                                    runCatching { cas.injectToWebView() }
                                    loadUrl(XgUrls.SSO_LOGIN)
                                    return@launch
                                }
                                loadUrl(XgUrls.SSO_LOGIN)
                                val outcome = runCatching { cas.prepareWebView() }.getOrNull()
                                when {
                                    outcome is CasEnsureResult.Ready && outcome.loggedInNow ->
                                        loadUrl(XgUrls.SSO_LOGIN)

                                    outcome is CasEnsureResult.Failed -> statusNote = outcome.message
                                    outcome == CasEnsureResult.Suspended ->
                                        statusNote = "教务登录已停用，请在「我的」页更新账号密码"

                                    else -> Unit
                                }
                            }
                        }
                        addView(
                            wv,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        webView = wv
                    }
                },
            )
        }
    }
}

/**
 * 学工页面的 WebView 配置。
 *
 * 与教务页的两处差别：
 * - **不开软件渲染**。教务页的 `LAYER_TYPE_SOFTWARE` 是 MIUI 合成层丢失的兜底
 *   （DESIGN §4.4），但学工是 Vue SPA，软件渲染会明显拖慢滚动。真机若出现
 *   「DOM 正常却整面白」，再按教务页那套加回来。
 * - `domStorageEnabled` 是**必须**的：学工平台把登录 token 放在 localStorage，
 *   关掉它页面会一直停在登录态判定上（不会报错，只是永远登不进去）。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun configureForXg(webView: WebView) {
    webView.setBackgroundColor(android.graphics.Color.WHITE)
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        loadsImagesAutomatically = true

        // 统一认证的登录页是固定宽的桌面布局、没有 <meta viewport>；
        // 允许页面用自身 viewport 再整体缩放到屏幕，先保证「看得全」。
        // 学工自身的页面带 width=device-width，在这套设置下同样正常。
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
    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
    // 学工的页面和接口不一定同域（表单引擎挂在 /office 下），第三方 cookie 要放行，
    // 否则登录态会话 cookie 带不过去。
    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
}
