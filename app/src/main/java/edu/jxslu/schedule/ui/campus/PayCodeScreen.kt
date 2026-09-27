package edu.jxslu.schedule.ui.campus

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.SkeletonBox
import edu.jxslu.schedule.ui.common.lineHeightDp
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.CreditCard

/**
 * 校园卡付款码页（DESIGN §3.10）：进页自动取码，大 QR + Code128 双展示。
 *
 * **防截屏**：窗口加 `FLAG_SECURE`（截屏/录屏/最近任务缩略图全黑）——付款码等同现金，
 * 网页端 H5 同样防截屏；离开页面即清除。**亮度拉满**：食堂/超市扫码枪对亮度敏感，
 * 进页把 `screenBrightness` 顶到 1f，离开恢复原值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayCodeScreen(
    onBack: () -> Unit = {},
    /** 跳消费流水页（DESIGN §4.19 B4） */
    onOpenStatement: () -> Unit = {},
    viewModel: PayCodeViewModel = viewModel(factory = PayCodeViewModel.Factory(LocalContext.current)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val bitmaps by viewModel.bitmaps.collectAsStateWithLifecycle()
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val payment by viewModel.detectedPayment.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()

    // 进页自动取码
    LaunchedEffect(Unit) { viewModel.load() }

    // 检测到扣款：这一页的使命结束——自动退出，由退出后的页面弹「支付成功」
    // （结果经 PayCodeResultBus 转交，本页不弹：弹在这里会随窗口一起消失）
    LaunchedEffect(payment) {
        if (payment != null) onBack()
    }

    // 事件出口（换批失败等）
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PayCodeEvent.Notice ->
                    snackbar.showSnackbar(AppNoticeVisuals(event.text, tone = event.tone))
            }
        }
    }

    // FLAG_SECURE + 亮度拉满：离开即恢复（两段各管各的窗口属性）
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val previousBrightness =
            window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            }
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = previousBrightness }
            }
        }
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("水宝宝一卡通") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PayCodeBody(
                state = state,
                bitmaps = bitmaps,
                onNext = {
                    haptics.tap()
                    viewModel.next()
                },
                onRetry = {
                    haptics.tap()
                    viewModel.load(force = true)
                },
            )

            // 余额行 + 流水入口（DESIGN §4.19 B3/B4）：取到才显示余额；流水入口恒在
            BalanceRow(balance, onOpenStatement = {
                haptics.tap()
                onOpenStatement()
            }, onRefresh = { viewModel.refreshBalance() })

            Text(
                text = "付款码等同现金，请勿截图或分享给他人。每个码仅可消费一次，" +
                    "用掉后服务端自动递补下一个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 余额行：卡余额（+电费账户非零时附带）+ 「消费流水」入口 + 点击刷新余额。 */
@Composable
private fun BalanceRow(
    balance: PayCodeViewModel.BalanceSnapshot?,
    onOpenStatement: () -> Unit,
    onRefresh: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onRefresh)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (balance == null) {
                Text(
                    text = "余额获取中…（点击刷新）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            } else {
                Text(
                    text = "正式卡 ¥%.2f".format(balance.cardFen / 100.0) +
                        if (balance.accountFen > 0) " · 电子账户 ¥%.2f".format(balance.accountFen / 100.0) else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onOpenStatement) {
            Text("消费流水")
        }
    }
}

@Composable
private fun PayCodeBody(
    state: PayCodeUiState,
    bitmaps: PayCodeBitmaps?,
    onNext: () -> Unit,
    onRetry: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val error = state as? PayCodeUiState.Error
    val success = state as? PayCodeUiState.Success
    val showCode = success != null && bitmaps != null
    val loading = !showCode && error == null
    // 两行文本位的高度取自样式本身，系统字体调大时骨架跟着长，不会又差一截
    val infoLine = lineHeightDp(MaterialTheme.typography.bodySmall)
    val codeLine = lineHeightDp(MaterialTheme.typography.titleMedium)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 1. QR 位：1:1 常驻。空着是描边空框，取码中是流光骨架块，出码原地换图
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (error != null) {
                        Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(14.dp),
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                error != null -> Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        HugeIcons.CreditCard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }

                bitmaps != null -> Image(
                    bitmap = bitmaps.qr.asImageBitmap(),
                    contentDescription = "校园卡付款码二维码",
                    modifier = Modifier.fillMaxSize(),
                )

                else -> SkeletonBox(Modifier.fillMaxSize(), RoundedCornerShape(14.dp))
            }
        }

        // 2. 条码位：6:1（码图 720×120），几何与 QR 一样占死
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(6f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            when {
                error != null -> Unit

                bitmaps != null -> Image(
                    bitmap = bitmaps.barcode.asImageBitmap(),
                    contentDescription = "校园卡付款码条形码",
                    modifier = Modifier.fillMaxSize(),
                )

                else -> SkeletonBox(Modifier.fillMaxSize(), RoundedCornerShape(8.dp))
            }
        }

        // 3. 信息行：固定一行高。取码中这行放加载文案（它就是这一行的占位），出错留空
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(infoLine),
            contentAlignment = Alignment.Center,
        ) {
            when {
                success != null -> Text(
                    text = "${success.accountMasked} · 第 ${success.index + 1}/${success.codes.size} 个 · " +
                        "约 ${success.expiresSeconds / 3600} 小时内有效",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                loading -> Text(
                    text = if (state is PayCodeUiState.Success) {
                        "正在生成付款码…"
                    } else {
                        "正在登录校园卡，连接水宝宝并获取付款码…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                else -> Unit
            }
        }

        // 4. 卡号行：固定一行高（取码中是流光骨架条）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(codeLine),
            contentAlignment = Alignment.Center,
        ) {
            when {
                success != null -> Text(
                    text = success.current,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    maxLines = 1,
                )

                loading -> SkeletonBox(
                    Modifier
                        .width(220.dp)
                        .height(codeLine),
                    RoundedCornerShape(codeLine / 2),
                )

                else -> Unit
            }
        }

        // 5. 按钮行：常显，不可用置灰。**不做条件渲染**——「有下一个码」切换时行出现或消失
        // 会把下方余额行顶动（同生活页出码位那套「按钮常显」口径）
        val actionLabel = if (error != null) "重试" else "下一个码"
        val actionEnabled = if (error != null) error.canRetry else (success != null && success.hasMore)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = if (error != null) onRetry else onNext,
                enabled = actionEnabled,
            ) {
                Text(actionLabel)
            }
        }
    }
}
