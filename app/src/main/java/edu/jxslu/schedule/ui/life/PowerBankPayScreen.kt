package edu.jxslu.schedule.ui.life

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import edu.jxslu.schedule.startActivityOutsideApp
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Browser
import me.rerere.hugeicons.stroke.Refresh

/**
 * 农行支付收银台（**App 内嵌**，DESIGN §4.24「农行支付」）。
 *
 * 打开的是农行**手机版收银台**（`mobile.abchina.com/mpaynew/mpay/index?TOKEN=…`，
 * 由 `PowerRepository.bankCashier` 用微信 UA 向平台换来——平台按 UA 分三支，只有微信那条
 * 给手机版，见 `PowerClient.MOBILE_CASHIER_UA`）。页面里就是用户平时在微信里用的那套：
 * **手机号 → 获取短信验证码 → 验证码（如需再输支付密码）**。页面是纯 H5 表单，不依赖
 * 微信 JSAPI（实测 `WeixinJSBridge`/`jweixin` 0 处），普通手机 UA 下渲染正常。
 *
 * **身份边界（不许越）**：手机号、短信验证码、支付密码、卡号全部只进农行页面。
 * 本页**不注入任何脚本、不读任何输入、不落盘**——只有加载、缩放、重试、外链转发。
 * 想「把这些步骤画成本 App 的界面」= 自己复刻农行的支付接口、让这些凭证经手本 App，
 * 那是另一回事，不要在这条链路上做（口径见 DESIGN §4.24）。
 *
 * 用户可见的兜底：顶栏「用浏览器打开」（把同一个链接交给系统浏览器）、加载失败浮层
 * （重试 / 用浏览器打开）。
 *
 * [onMerchantReturn]：页面回到商户域（`charge.juwp.edu.cn`）时调用——收银台付完会
 * 「返回商户」，那一跳之后该由 App 的等待步接管（查单），不该再让用户看商户的网页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PowerBankPayScreen(
    url: String,
    /** 支付完成回到商户域时关闭本页（由调用方决定，见 KDoc）。 */
    onMerchantReturn: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }

    // 打开系统浏览器（顶栏入口与失败浮层共用一份）
    val openInBrowser: () -> Unit = {
        runCatching { context.startActivityOutsideApp(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        Unit
    }

    // 返回键：网页还能后退就先退网页，退不动才关窗口（与教务导入页同一手势口径）
    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = pinnedStatusBars(),
                // 标题固定「农行支付」：页面自身的 <title> 是主机名（实测
                // `mobile.abchina.com/`），拿来当标题只会让人误以为进了浏览器。
                title = { Text("农行支付", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "关闭")
                    }
                },
                actions = {
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(HugeIcons.Refresh, contentDescription = "刷新")
                    }
                    // 兜底出口：同一个链接交给系统浏览器（已验证可用），页面在 WebView 里
                    // 出任何幺蛾子时用户都有一条确定能走的路
                    IconButton(onClick = openInBrowser) {
                        Icon(HugeIcons.Browser, contentDescription = "用浏览器打开")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        // 套一层 FrameLayout 作壳（Compose 重排时摘挂的是壳，WebView 本体不
                        // 被直接摘挂/重挂，与教务导入页同一处置）
                        FrameLayout(ctx).apply {
                            val wv = WebView(ctx).apply {
                                configureForBankPay()
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val target = request?.url?.toString().orEmpty()
                                        // 商户域 = 支付流程回来了：交给 App 的等待步（查单）
                                        if (isMerchantReturn(target)) {
                                            onMerchantReturn()
                                            return true
                                        }
                                        return if (target.startsWith("http://") ||
                                            target.startsWith("https://")
                                        ) {
                                            false
                                        } else {
                                            launchExternal(target)
                                        }
                                    }

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        progress = 0
                                        failed = false
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        progress = 100
                                        // 布局 reflow 兜底：页面在视口未稳定时完成布局会按错误宽度
                                        // 定格（2026-09-28 真机实测：首屏被裁掉左右两边，刷新一下就好，
                                        // 与教务导入页那条 MIUI/WebView 顽疾同源）。等布局稳定后强制
                                        // 重排一次——只 requestLayout/invalidate，**不注入脚本**。
                                        view?.post {
                                            view.requestLayout()
                                            view.invalidate()
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?,
                                    ) {
                                        // 只有主框架失败才算「打不开」：子资源（图片/统计脚本）
                                        // 失败不该把整页盖成错误浮层
                                        if (request?.isForMainFrame == true) failed = true
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress
                                    }
                                }
                                loadUrl(url)
                            }
                            addView(
                                wv,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                            webView = wv
                        }
                    },
                )
                if (failed) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp),
                        ) {
                            Text(
                                "农行支付页没打开（网络或页面改版）",
                                style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "可以重试，或改用系统浏览器打开——付款链接在本次下单里一直有效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { failed = false; webView?.reload() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("重试") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = openInBrowser, modifier = Modifier.fillMaxWidth()) {
                                Text("用浏览器打开")
                            }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 收银台页面 30 分钟会话、支付完即弃：退出就销毁，不给下一个窗口留残留
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
}

/**
 * 是不是回到了商户域（缴费平台）。**只认这个域**：收银台页面里连回商户的只有
 * 「返回商户」那一跳，认错会把用户从收银台里踢出去。
 */
private fun isMerchantReturn(url: String): Boolean = runCatching {
    val host = Uri.parse(url).host.orEmpty()
    host == "charge.juwp.edu.cn"
}.getOrDefault(false)

/**
 * 非 http(s) 的跳转交给系统（农行掌银的 scheme、`intent://`、微信/支付宝拉起）。
 *
 * 返回 true = 已接管（无论成功），让 WebView 别自己去加载它——WebView 遇到未知 scheme
 * 只会显示 ERR_UNKNOWN_URL_SCHEME 错误页，等于把用户卡在收银台里。
 */
private fun WebView.launchExternal(target: String): Boolean {
    val ctx = context ?: return true
    val intent = when {
        target.startsWith("intent://") ->
            runCatching { Intent.parseUri(target, Intent.URI_INTENT_SCHEME) }.getOrNull()

        else -> runCatching { Intent(Intent.ACTION_VIEW, Uri.parse(target)) }.getOrNull()
    } ?: return true
    runCatching { ctx.startActivityOutsideApp(intent) }
    return true
}

/**
 * 收银台页面的 WebView 配置。
 *
 * 与教务导入页（`configureForJw`）的三点不同，都是银行页面的要求：
 * 1. **UA 用普通移动 Chrome**：WebView 默认 UA 多带一个 `; wv` 标记，银行页面据此
 *    可能被判成「内嵌浏览器」而拒绝或走到别的分支；用户真机的浏览器就是这类移动 UA。
 * 2. **不禁用第三方 Cookie**：收银台与农行域之间靠它维持会话。
 * 3. **开缩放**：K 码支付表单（卡号 / 验证码 / 密码）在手机上是桌面式排版，
 *    双指放大是用户唯一的自救手段。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForBankPay() {
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
        cacheMode = WebSettings.LOAD_NO_CACHE
        mediaPlaybackRequiresUserGesture = false
        // 收银台的 target=_blank（「返回商户」「帮助」）：留在本 WebView 里打开，
        // 别弹新窗口——新窗口没人接，会变成看不见的页
        setSupportMultipleWindows(false)
        userAgentString = BANK_UA
    }
    isVerticalScrollBarEnabled = true
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
}

/** 收银台页面用的移动 UA（与 `PowerClient` 里那份同类；`; wv` 必须没有）。 */
private const val BANK_UA =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"
