package edu.jxslu.schedule.ui.life

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.data.power.PowerEntryState
import edu.jxslu.schedule.ui.common.ImeAwareModalBottomSheet
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01

/**
 * 电费充值弹层（DESIGN §4.24）：渠道（电子账户 / 农行支付）→ 金额 → 密码或农行支付页 → 受理。
 *
 * - **无状态组件**：流程状态在 [LifeViewModel.powerRecharge]（VM 持有，转屏不丢）；
 * - 金额步的右上角是一小块信息区（2026-09-24 四改）：上行「电子账户余额 ¥X」，
 *   下行「去充值电子账户 ›」入口（由 [onOpenCampusRecharge] 承接，打开一卡通充值并预选
 *   电子账户）。余额与它要解决的问题挨着：不足时一眼看到去哪充。原来那排
 *   「取消 / 去充值电子账户」占掉一整行、和底部主按钮抢注意力，已删——关弹层交给
 *   下滑 / 点遮罩 / 系统返回（M3 弹层的既有手势）。**只属于电子账户渠道**（2026-09-28）：
 *   农行支付与电子账户余额无关，那条渠道下这块整个不出现。
 * - **渠道选择在金额步**（2026-09-28 加农行，两枚 FilterChip）：下单之后不许换
 *   （订单与渠道绑定，[LifeViewModel.selectPowerChannel] 在非金额步直接忽略）。
 * - 农行渠道的付款在 **App 内嵌的农行手机版收银台**里完成（`PowerBankPayActivity`，
 *   [BankStep] 只等结果）：手机号、短信验证码、支付密码只进农行页面，App 不读、不存
 *   ——别在这条链路上加任何输入框。
 * - 键盘遮挡与退场时序走 [ImeAwareModalBottomSheet]（`skipPartiallyExpanded = true` + 「先收
 *   键盘、键盘收完再滑走」两段退场）：金额步有输入框，键盘弹起后 M3 会把弹层改判到半高
 *   锚点、底部按钮被盖住——与校园卡充值弹层同一坑（定位过程见 DESIGN §4.19）。
 *   不要自己再垫键盘高度。
 * - **密码步不调系统输入法**（2026-09-24 用户反馈「输密码体验太差」）：改成自绘数字键盘
 *   （[NumberPad]），点哪填哪、6 格点阵就地反馈、退格可改。没有输入框就没有 IME 与光标，
 *   也不会再被键盘顶到半高——密码步的可用高度与金额步无关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerRechargeSheet(
    state: LifeViewModel.PowerRechargeUi,
    /** 电子账户余额（分）；null = 未取到（按钮仍可用，支付失败由服务端兜底）。 */
    accountFen: Long?,
    roomLabel: String?,
    remainText: String?,
    onDismiss: () -> Unit,
    onOpenCampusRecharge: () -> Unit,
    onSelectChannel: (LifeViewModel.PowerRechargeUi.Channel) -> Unit,
    onPlaceOrder: (yuan: String) -> Unit,
    onLoadChallenge: () -> Unit,
    onSubmitPassword: (cipher: String) -> Unit,
    onReopenCashier: () -> Unit,
    onCheckPaid: () -> Unit,
) {
    // 键盘遮挡与退场时序都在 ImeAwareModalBottomSheet 里（`skipPartiallyExpanded = true`；
    // 退场「先收键盘、键盘收完再滑走」两段，见 ui/common/SheetDismissIme.kt）
    ImeAwareModalBottomSheet(onDismiss = onDismiss) {
        // 收键盘必须用弹窗窗口这一份（写在弹层内容里）——见 ui/common/SheetDismissIme.kt
        val keyboard = LocalSoftwareKeyboardController.current
        // 受理成功后自动收起键盘：这一步没有输入，键盘留着只会挡住「完成」
        LaunchedEffect(state.step) {
            if (state.step == LifeViewModel.PowerRechargeUi.Step.Accepted) keyboard?.hide()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 标题行右上角（金额步才出现——下单之后再充值没有意义）：
            // 上行是电子账户余额（数值，稍大），下行是可点的「去充值电子账户 ›」（入口，稍小）。
            // 余额原来在左边单独占一行，挪到这里与它要解决的问题挨着：余额不足时一眼看到去哪充。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "电费充值",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (state.step == LifeViewModel.PowerRechargeUi.Step.Amount &&
                    state.channel == LifeViewModel.PowerRechargeUi.Channel.Account
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "电子账户余额 " +
                                (accountFen?.let { "¥%.2f".format(it / 100.0) } ?: "—"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        TextButton(
                            onClick = onOpenCampusRecharge,
                            // 压到 32dp：默认 48dp 会把标题行撑到近 70dp，这只是一枚链接
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Text("去充值电子账户", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.size(4.dp))
                            Icon(
                                HugeIcons.ArrowRight01,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
            if (roomLabel != null || remainText != null) {
                Text(
                    text = listOfNotNull(roomLabel, remainText).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            // 打开弹层时清掉的未支付单（平台不自动清，堆积会让新下单 500）。
            // 提示落在弹层里：页面 Scaffold 的 Snackbar 会被这个独立窗口盖住。
            if (state.cleanedOrders > 0) {
                Text(
                    text = "已清理 ${state.cleanedOrders} 笔未支付订单",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            when (state.step) {
                LifeViewModel.PowerRechargeUi.Step.Amount -> AmountStep(
                    state = state,
                    accountFen = accountFen,
                    roomLabel = roomLabel,
                    onSelectChannel = onSelectChannel,
                    onPlaceOrder = onPlaceOrder,
                )

                LifeViewModel.PowerRechargeUi.Step.Password -> PasswordStep(
                    state = state,
                    onSubmitPassword = onSubmitPassword,
                )

                LifeViewModel.PowerRechargeUi.Step.BankPay -> BankStep(
                    state = state,
                    roomLabel = roomLabel,
                    onReopenCashier = onReopenCashier,
                    onCheckPaid = onCheckPaid,
                )

                LifeViewModel.PowerRechargeUi.Step.Accepted -> AcceptedStep(
                    paidStatus = state.paidStatus,
                    entryStatus = state.entryStatus,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun AmountStep(
    state: LifeViewModel.PowerRechargeUi,
    accountFen: Long?,
    /** 绑定房间号（读数里的 room，如 13B309）；二次确认弹窗要加粗它。 */
    roomLabel: String?,
    onSelectChannel: (LifeViewModel.PowerRechargeUi.Channel) -> Unit,
    onPlaceOrder: (String) -> Unit,
) {
    var amount by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val parsed = amount.toBigDecimalOrNull()
    val valid = parsed != null && parsed >= java.math.BigDecimal("0.01") &&
        parsed <= java.math.BigDecimal("500.00")
    val isAccount = state.channel == LifeViewModel.PowerRechargeUi.Channel.Account
    // 余额够不够只对电子账户有意义：农行支付走银行卡，与电子账户余额无关
    val insufficient = isAccount && accountFen != null && parsed != null &&
        parsed * java.math.BigDecimal(100) > java.math.BigDecimal(accountFen)

    // 渠道选择（2026-09-28）：电子账户 = 扣电子账户余额、App 内输 6 位消费密码；
    // 农行支付 = 手机号 + 短信验证码在农行页面里完成（同款两枚 FilterChip，见 campus 充值弹层）。
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LifeViewModel.PowerRechargeUi.Channel.entries.forEach { channel ->
            FilterChip(
                selected = state.channel == channel,
                onClick = { onSelectChannel(channel) },
                enabled = !state.busy,
                label = {
                    Text(
                        when (channel) {
                            LifeViewModel.PowerRechargeUi.Channel.Account -> "电子账户"
                            LifeViewModel.PowerRechargeUi.Channel.Bank -> "农行支付"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
        }
    }

    // 说明文案默认中性灰；**输入金额超过电子账户余额**才转红（2026-09-24 用户要求）。
    // 常驻红色的写法会把「只是提示」读成「已经出错」，而这一步本来就是提示。
    Text(
        text = if (isAccount) {
            "从电子账户余额扣款，下一步在 App 内输 6 位消费密码。"
        } else {
            "用银行卡付款（手机号 + 短信验证码），下一步在 App 内打开农行支付页。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (insufficient) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        },
    )
    OutlinedTextField(
        value = amount,
        onValueChange = { raw ->
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
                    amount.isEmpty() -> if (isAccount) {
                        "0.01 – 500.00 元；充值到电子账户后用于电费"
                    } else {
                        "0.01 – 500.00 元；直接缴房间电费"
                    }

                    !valid -> "金额需在 0.01 – 500.00 元之间"
                    insufficient -> "超出电子账户余额，请先去充值"
                    isAccount -> "确认后下单，再用 6 位消费密码支付"
                    else -> "确认后下单，再在农行页面用银行卡支付"
                },
            )
        },
        isError = (amount.isNotEmpty() && !valid) || insufficient,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("10", "50", "100").forEach { preset ->
            OutlinedButton(
                onClick = { amount = preset },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp),
            ) { Text(preset) }
        }
    }
    state.error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Button(
        onClick = { confirm = true },
        enabled = valid && !insufficient && !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
        } else {
            Text("下一步")
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("确认充值金额？") },
            text = {
                // 房间号**加粗**（2026-09-28 用户要求）：这一屏盖住了弹层头部的房间行，
                // 而「充到哪个寝室」比「充多少」更容易错——一个房间一个电表，充错就是别人的。
                // 房间号取不到（读数没回来）时不显示数字，宁缺勿错。
                Text(
                    buildAnnotatedString {
                        append("将为")
                        if (roomLabel.isNullOrBlank()) {
                            append("当前绑定房间")
                        } else {
                            append("房间 ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(roomLabel) }
                        }
                        append(
                            if (isAccount) {
                                " 的电费缴纳 ¥$amount（电子账户）。\n\n"
                            } else {
                                " 的电费缴纳 ¥$amount（农行支付）。\n\n"
                            },
                        )
                        if (isAccount) {
                            append("下单后用 6 位消费密码支付；未支付的订单 30 分钟自动失效，不会扣款。")
                        } else {
                            append("下一步在 App 内打开农行支付页，用手机号 + 短信验证码付款；")
                            append("没付款就离开不会扣款，订单 30 分钟自动失效。")
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onPlaceOrder(amount)
                }) { Text("去下单") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}

/**
 * 农行支付步（2026-09-28）：收银台已在 App 内嵌页打开，这里只等支付结果。
 *
 * 三件事：说清「在哪付、付完做什么」；给一个**重开支付页**的兜底（链接是农行侧一次性
 * 会话，重开走 VM 现取新链接）；支付完成后点「检查到账」——从内嵌页回到本页也会
 * 自动查一次（`LifeScreen` 的 resume 计数）。
 *
 * 手机号 / 短信验证码 / 支付密码不经过本 App：这一页只有状态与按钮，没有任何输入框
 * （口径见 DESIGN §4.24）。
 */
@Composable
private fun BankStep(
    state: LifeViewModel.PowerRechargeUi,
    /** 绑定房间号；付款前再报一次「充的是哪个寝室」。 */
    roomLabel: String?,
    onReopenCashier: () -> Unit,
    onCheckPaid: () -> Unit,
) {
    Text(
        text = "请在农行支付页完成付款",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    state.amountYuan?.let {
        Text(
            text = buildAnnotatedString {
                roomLabel?.takeIf { label -> label.isNotBlank() }?.let { label ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label) }
                    append(" · ")
                }
                append("本次充值 ¥$it")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
    Text(
        text = "农行支付页已在 App 内打开：填手机号 → 获取短信验证码 → 验证码（如需再输支付密码）。" +
            "页面关了就点下面的「重新打开」。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
    state.bankNote?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
    state.error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onReopenCashier,
            enabled = !state.busy,
            modifier = Modifier.weight(1f),
        ) { Text("重新打开") }
        Button(
            onClick = onCheckPaid,
            enabled = !state.busy,
            modifier = Modifier.weight(1f),
        ) {
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            } else {
                Text("检查到账")
            }
        }
    }
}

/**
 * 密码步（2026-09-24 改版，用户反馈「那个矩形输入框体验太差」）。
 *
 * **没有输入框**：6 格点阵就是输入位，点它（或点它周围那条带）弹系统数字键盘，
 * 输入直接填进格子——键盘还是系统的，只是不再摆一个矩形框、不再显示光标与文本。
 * 做法是透明 [BasicTextField]（`decorationBox` 丢掉内部渲染、字色与光标都透明）
 * 铺满点阵那条带，点阵叠在上面但**不带点击处理**，触摸自然落到输入框上。
 *
 * 进这一步就自动聚焦（不用先点一下），提交失败自动清空重来。
 */
@Composable
private fun PasswordStep(
    state: LifeViewModel.PowerRechargeUi,
    onSubmitPassword: (String) -> Unit,
) {
    // **不能用 rememberSaveable**：那是要写进系统 saved-state 的，6 位支付密码不该留在那里
    // （转屏就当输了一半丢掉，重输即可）。
    var digits by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // 进密码步即弹键盘；出这一步（受理）收起键盘
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // 服务端拒绝后清空，别让用户自己按 6 次退格。
    // 信号用 rejectedCount（自增计数），不用 error 文案：两次失败文案相同的话
    // StateFlow 前后相等、不会发射，格子就清不掉（2026-09-24 复审发现）。
    LaunchedEffect(state.rejectedCount) {
        if (state.rejectedCount > 0) digits = ""
    }

    Text(
        text = "请输入 6 位消费密码",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    state.amountYuan?.let {
        Text(
            text = "本次充值 ¥$it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }

    // 点阵 + 透明输入框：同一个 Box，输入框铺满、点阵居中叠放（点阵不拦触摸）
    Box(modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            value = digits,
            onValueChange = { raw -> digits = raw.filter { it.isDigit() }.take(PASSWORD_LENGTH) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            cursorBrush = SolidColor(Color.Transparent),
            textStyle = TextStyle(color = Color.Transparent),
            singleLine = true,
            // 丢掉内部渲染：不要框、不要光标、不要任何字
            decorationBox = { },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                // 无可见框的输入位，给读屏一个名字
                .semantics { contentDescription = "6 位消费密码" }
                .focusRequester(focusRequester),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(
                10.dp,
                alignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            repeat(PASSWORD_LENGTH) { index ->
                val filled = index < digits.length
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .border(
                            1.dp,
                            if (filled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (filled) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
    }

    // 提示与错误同一行（错误时替换提示，不新起一行，弹层高度不跳）
    Text(
        // 密码口径（2026-09-24 用户纠正 + 实测）：平台登录用的就是这个 6 位密码，
        // 不存在「另一套支付密码」。之前「与登录密码相互独立」的说法是错的。
        text = state.error ?: "点上面格子输入，登录缴费平台用的那个 6 位密码；不保存",
        style = MaterialTheme.typography.bodySmall,
        color = if (state.error != null) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        },
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { digits = "" },
            enabled = digits.isNotEmpty() && !state.busy,
            modifier = Modifier.weight(1f),
        ) { Text("清空") }
        Button(
            onClick = { onSubmitPassword(digits) },
            enabled = digits.length == PASSWORD_LENGTH && !state.busy,
            modifier = Modifier.weight(2f),
        ) {
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            } else {
                Text("确认支付")
            }
        }
    }
}

/** 消费密码位数（6 位，与平台一致）。 */
private const val PASSWORD_LENGTH = 6

@Composable
private fun AcceptedStep(paidStatus: Int?, entryStatus: PowerEntryState?, onDismiss: () -> Unit) {
    Text(
        // 查单确认过才敢说「已扣款」；没确认到就如实说「已受理」（不编）
        text = if (paidStatus == 1) "支付成功，已确认扣款" else "支付已受理，正在确认到账",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    when (entryStatus) {
        // 2026-09-29 事故（三笔「支付成功」的订单永远没入账）后，扣款与入账分开报：
        // 入账位有结论就说结论，还在处理就如实说「处理中」，失败就明说找管理员。
        PowerEntryState.ENTERED -> EntryLine("电量已入账。")
        PowerEntryState.PENDING -> EntryLine("电量入账处理中——稍后在生活页刷新电费卡或到「缴费账单」核对。")
        PowerEntryState.FAILED -> Text(
            text = "扣款已成功，但电量入账失败——请联系缴费平台管理员处理。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        // 入账位没查到：维持原口径的通用提示
        null -> Text(
            text = "电费读数可能有几分钟延迟；稍后在生活页点电费卡刷新即可看到最新剩余电量。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("完成") }
}

/** 受理步的入账状态行（灰色小字，与通用提示同款式）。 */
@Composable
private fun EntryLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
}
