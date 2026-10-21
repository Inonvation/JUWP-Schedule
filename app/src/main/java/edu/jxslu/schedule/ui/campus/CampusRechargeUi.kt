package edu.jxslu.schedule.ui.campus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.domain.YktPayment
import edu.jxslu.schedule.ui.common.AmountHeroText
import edu.jxslu.schedule.ui.common.ImeAwareModalBottomSheet

/**
 * 校园卡充值/到账共享 UI（DESIGN §4.19「充值」、§3.10）。
 *
 * 设置页与生活页弹窗共用：[RechargeSheet]（金额弹层+二次确认）与
 * [CampusArrivalDialog]（到账成功弹窗）都是无状态组件，下单/到账逻辑在
 * [CampusCardViewModel]；付款码扣款结果由 [CampusPayResultDialogHost] 一处收尾
 * （MainActivity 根层 + 校园卡设置页两个挂点）。
 *
 * **弹层输入框的键盘遮挡与退场时序见 [ImeAwareModalBottomSheet]**（`skipPartiallyExpanded`
 * 与「先收键盘、键盘收完再滑走」两段退场都收在那份容器里）：内容固定不可滚
 * （2026-09-24 用户拍板），也不要在弹层里再垫键盘高度——M3 自己已经垫过，多垫反而把
 * 按钮顶到键盘下面（DESIGN §4.19「充值弹层的键盘」）。
 */

/** 充值金额弹层 + 二次确认。通过校验后 [onLaunch] 回调元金额（两位小数字符串）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RechargeSheet(
    balanceFen: Long?,
    /**
     * 电子账户余额（分）；null = 未知/不支持。非 null 时显示目标账户选择
     * （默认正式卡，DESIGN §3.10 账户口径）。
     */
    accountFen: Long? = null,
    /**
     * 会充到的那张正式卡的卡号（6 位）。
     *
     * 二次确认弹窗**加粗**展示它（2026-09-28 用户要求：充错账户是钱的事）。
     * 选取口径由调用方用 [`edu.jxslu.schedule.data.ykt.rechargeTargetCard`] 取，
     * 与下单链路同一份判断；取不到就不显示卡号（宁可不显示，也不显示一张可能不对的卡）。
     */
    cardAccount: String? = null,
    onDismiss: () -> Unit,
    /** [toElectricAccount] = true 表示充到电子账户（`yktcard` 走 accinfo type）。 */
    onLaunch: (yuan: String, toElectricAccount: Boolean) -> Unit,
    /** 预选电子账户（生活页「去充值」入口联动，DESIGN §4.24）。 */
    initiallyElectric: Boolean = false,
) {
    var amount by rememberSaveable { mutableStateOf("") }
    var confirmStep by remember { mutableStateOf(false) }
    var toElectric by rememberSaveable { mutableStateOf(initiallyElectric) }

    val parsed = amount.toBigDecimalOrNull()
    val valid = parsed != null && parsed >= java.math.BigDecimal("0.01") &&
        parsed <= java.math.BigDecimal("500.00")

    // 键盘遮挡与退场时序都收在 ImeAwareModalBottomSheet 里（`skipPartiallyExpanded = true`；
    // 退场走「先收键盘、键盘收完再滑走」两段，理由与实测见 ui/common/SheetDismissIme.kt 注释）。
    ImeAwareModalBottomSheet(onDismiss = onDismiss) {
        // **内容固定、不可滚**（2026-09-24 用户拍板）：弹层就这一屏内容，滚动的意义只剩
        // 「键盘起来时把按钮滚出来」，而 M3 自己会把弹层底边与内容底垫到键盘上缘
        // （`Box(fillMaxSize().imePadding())` + `contentWindowInsets = safeDrawing.only(Bottom)`），
        // 再叠 `verticalScroll` 只会引入「首次点击时 maxValue 未更新、滚不到位」的时序坑；
        // 也不要自己垫键盘高度（多垫一份会把内容挤出可视区）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("校园卡充值", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (accountFen == null) {
                balanceFen?.let {
                    Text(
                        "当前卡余额 ¥%.2f".format(it / 100.0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            } else {
                // 目标账户选择（DESIGN §3.10）：正式卡 = 食堂/门禁；电子账户 = 电费等线上缴费
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = !toElectric,
                        onClick = { toElectric = false },
                        label = {
                            Text(
                                balanceFen?.let { "正式卡 ¥%.2f".format(it / 100.0) } ?: "正式卡",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                    )
                    androidx.compose.material3.FilterChip(
                        selected = toElectric,
                        onClick = { toElectric = true },
                        label = {
                            Text(
                                "电子账户 ¥%.2f".format(accountFen / 100.0),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                    )
                }
            }
            androidx.compose.material3.OutlinedTextField(
                value = amount,
                onValueChange = { raw ->
                    // 两位小数内、一位小数点；空串放行
                    val cleaned = raw.filter { c -> c.isDigit() || c == '.' }
                    amount = if (cleaned.count { it == '.' } > 1) amount else {
                        val dot = cleaned.indexOf('.')
                        if (dot >= 0 && cleaned.length - dot - 1 > 2) amount else cleaned
                    }
                },
                label = { Text("充值金额（元）") },
                supportingText = {
                    Text(
                        when {
                            amount.isEmpty() ->
                                if (toElectric) {
                                    "0.01 – 500.00 元；电子账户用于电费等线上缴费"
                                } else {
                                    "0.01 – 500.00 元；将直接拉起微信支付"
                                }
                            !valid -> "金额需在 0.01 – 500.00 元之间"
                            else -> "确认后在微信内完成支付"
                        },
                    )
                },
                isError = amount.isNotEmpty() && !valid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("20", "50", "100", "200").forEach { preset ->
                    androidx.compose.material3.OutlinedButton(
                        onClick = { amount = preset },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp),
                    ) { Text(preset) }
                }
            }
            androidx.compose.material3.Button(
                onClick = { confirmStep = true },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (toElectric) "立即支付" else "下一步") }
        }
    }

    if (confirmStep) {
        AlertDialog(
            onDismissRequest = { confirmStep = false },
            title = { Text("确认充值金额？") },
            text = {
                // 目标账户**加粗**（2026-09-28 用户要求）：充错账户是钱的事，二次确认这一屏
                // 必须让「充到哪」比「充多少」更显眼。正式卡给卡号，电子账户给账户名——
                // 卡号取不到（余额还没回来）就不显示数字，宁缺勿错。
                Text(
                    buildAnnotatedString {
                        append("将为")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            if (toElectric) {
                                append("电子账户")
                            } else {
                                append("正式卡")
                                val account = cardAccount?.takeIf { it.isNotBlank() }
                                if (account != null) append("（卡号 $account）")
                            }
                        }
                        append(" 充值 ¥$amount。\n\n")
                        if (toElectric) {
                            append("电子账户用于电费等线上缴费。")
                        }
                        append("点击「${if (toElectric) "立即支付" else "去支付"}」会直接拉起微信（微信充值渠道），")
                        append("在微信内确认支付；未支付的订单会自动失效，不会扣款。")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStep = false
                    onLaunch(amount, toElectric)
                }) { Text(if (toElectric) "立即支付" else "去支付") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStep = false }) { Text("取消") }
            },
        )
    }
}

/** 充值成功弹窗（到账状态机 Arrived 时显示）；[onOpenPayCode] 一键跳付款码页（可为空则不显示该按钮）。 */
@Composable
fun CampusArrivalDialog(
    arrived: CampusCardViewModel.ArrivalState.Arrived,
    onDismiss: () -> Unit,
    onOpenPayCode: (() -> Unit)? = null,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("充值成功") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AmountHeroText("¥%.2f".format(arrived.orderFen / 100.0))
                Text(
                    text = "已到账",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurface.copy(alpha = 0.75f),
                )
                arrived.newBalanceFen?.let {
                    Text(
                        text = "当前卡余额 ¥%.2f".format(it / 100.0),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onSurface.copy(alpha = 0.75f),
                    )
                }
                Text(
                    text = "本次交易已同步至「消费流水」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onOpenPayCode?.invoke()
            }) { Text("查看付款码") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 「正在确认到账」弹窗（DESIGN §4.19「充值」）：微信支付完成返回后、余额尚未更新时给出
 * 反馈——说明在自动检测（刚充完时最密，之后自动放慢，到账后立即提示），确认到账后会
 * 再弹成功弹窗；用户关闭**不影响**后台轮询。金额同样是大字主角（2026-10-07）。
 */
@Composable
fun CampusPendingConfirmDialog(
    orderFen: Long,
    onDismiss: () -> Unit,
    /** 用户声明「我没有付款」：清等待态与轮询（未支付订单 30 分钟自动失效）。 */
    onNotPaid: (() -> Unit)? = null,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("正在确认充值结果") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AmountHeroText("¥%.2f".format(orderFen / 100.0))
                Text(
                    text = "已发起充值订单。若你已在微信完成支付，余额更新常有数分钟延迟，" +
                        "App 正在自动检测，确认到账后立即提示。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurface.copy(alpha = 0.75f),
                )
                Text(
                    text = "如果你没有付款（在微信里取消或直接返回），点「我没有付款」，" +
                        "未支付订单 30 分钟后自动失效，不会扣款。",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.55f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("我已付款，等通知") }
        },
        dismissButton = {
            if (onNotPaid != null) {
                TextButton(onClick = onNotPaid) { Text("我没有付款") }
            }
        },
    )
}

/**
 * 「支付成功」弹窗（DESIGN §3.10）：付款码页检测到扣款后自动退出，由退出后的页面弹这一张，
 * 金额取流水里的扣款额，另附商户、时间、交易后余额。
 *
 * **金额是这张弹窗唯一的主角**（2026-10-07 用户要求）：大字单行「¥xx.xx」，商户 · 时间
 * 与卡余额退成次级小字，同步说明再低一档。依据是消费流水，所以文案不写「实时」「立即」
 * ——服务端落账有延迟，这笔消费的确切时间以流水为准（[payment] 的 timeText 就是服务端原文）。
 */
@Composable
fun CampusPaymentDialog(
    payment: YktPayment,
    onDismiss: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 商户 · 时间：时间缺失时兜底类型名（旧口径保留）；两者都没有就不占这一行
    val detail = listOfNotNull(
        payment.merchant?.takeIf { it.isNotBlank() },
        payment.timeText.ifBlank { payment.typeText }.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("支付成功") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 大字金额：与趣智/骑行页的大号数字同族（AmountHeroText 唯一口径）
                AmountHeroText("¥%.2f".format(payment.amountFen / 100.0))
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onSurface.copy(alpha = 0.75f),
                    )
                }
                payment.balanceAfterFen?.let {
                    Text(
                        text = "卡余额 ¥%.2f".format(it / 100.0),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onSurface.copy(alpha = 0.75f),
                    )
                }
                Text(
                    text = "本次交易已同步至「消费流水」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

/**
 * 「支付成功」弹窗宿主（2026-10-07）：付款码页检测到扣款后自动退出，由它收尾——
 * 订阅 [PayCodeResultBus]（取值即消费），弹 [CampusPaymentDialog]。
 *
 * 挂点两处，覆盖全部出码入口：
 * - `MainActivity` 根层（四个 Tab 之上）：今日页「+ → 付款码」与侧栏付款码两条路径
 *   共用这一个宿主——此前只有生活页自己弹，从今日页出码付款后没人消费结果，
 *   弹窗会一直挂着、直到下次打开生活页才迟到弹出；
 * - `CampusCardSettingsScreen`（独立 Activity，「我的 → 校园卡」出码）。
 */
@Composable
fun CampusPayResultDialogHost() {
    val payResult by PayCodeResultBus.result.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var paid by remember { mutableStateOf<YktPayment?>(null) }
    LaunchedEffect(payResult) {
        payResult?.let {
            paid = it
            PayCodeResultBus.consume()
            // 钱刚动过：后台强拉一次余额（生活页钱包卡 / 设置页 / 桌面小组件跟着更新），
            // 别让用户回到页面看到的还是扣款前的数字（2026-10-09，见 CampusBalanceSync）
            CampusBalanceSync.refreshAfterPayment(context)
        }
    }
    paid?.let { CampusPaymentDialog(payment = it, onDismiss = { paid = null }) }
}

