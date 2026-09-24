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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.xg.XgUrls

private const val TAG = "DormRepair"

/**
 * 宿舍报修（DESIGN §3.15 / §4.26）：把学工系统的官方移动页装进一个窗口。
 *
 * 页面从统一认证入口进（[XgUrls.SSO_LOGIN]）——CAS 那边已有会话就直接落到学工，
 * 没有就地登录。填表、传附件、提交、查流程都由官方页面自己完成，App 不代劳：
 * 报修的提交请求里带着一组服务端下发的动态值（pageEnc / traceId / nodeUniqueId 等），
 * 复刻一遍的代价和失效风险都远大于收益，理由写在 DESIGN §4.26。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DormRepairScreen(onBack: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var statusNote by remember { mutableStateOf(XgUrls.statusHint(null)) }

    // 网页里点「上传附件」时，WebView 会把文件选择回调交出来，等系统选择器的结果。
    // 期间必须一直握着它：提前丢掉页面会永远停在「上传中」；而结果回来后又必须立刻
    // 清掉——同一个 callback 消费两次会抛 IllegalStateException，且只清一处容易漏。
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

    // 与左上角箭头同语义：网页能后退就先回退，退不动了才关窗口。
    // 报修要连着走「首页 → 宿管服务 → 列表 → 表单」好几层，直接关窗口会让用户
    // 以为自己的操作丢了。
    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("宿舍报修") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                                    statusNote = XgUrls.statusHint(url)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    canGoBack = view?.canGoBack() ?: false
                                    statusNote = XgUrls.statusHint(url)
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
                                        // 设备上没有能处理该 Intent 的选择器时，退回 false，
                                        // 让页面自己报「不支持上传」，而不是卡在一个打不开的选择器上
                                        Log.w(TAG, "file chooser unavailable", e)
                                        fileCallback = null
                                        false
                                    }
                                }
                            }
                            loadUrl(XgUrls.SSO_LOGIN)
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
