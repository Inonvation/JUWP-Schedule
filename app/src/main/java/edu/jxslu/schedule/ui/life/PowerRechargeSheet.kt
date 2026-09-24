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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.ui.common.ImeAwareModalBottomSheet
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01

/**
 * 电费充值弹层（DESIGN §4.24）：金额 → 密码 → 受理。
 *
 * - **无状态组件**：流程状态在 [LifeViewModel.powerRecharge]（VM 持有，转屏不丢）；
 * - 金额步的右上角是一小块信息区（2026-09-24 四改）：上行「电子账户余额 ¥X」，
 *   下行「去充值电子账户 ›」入口（由 [onOpenCampusRecharge] 承接，打开一卡通充值并预选
 *   电子账户）。余额与它要解决的问题挨着：不足时一眼看到去哪充。原来那排
 *   「取消 / 去充值电子账户」占掉一整行、和底部主按钮抢注意力，已删——关弹层交给
 *   下滑 / 点遮罩 / 系统返回（M3 弹层的既有手势）。
 * - 「当前软件暂时只支持电子账户缴费…」的说明在金额步、输入框上方：默认中性灰，
 *   只有输入金额超过电子账户余额才转红。
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
    onPlaceOrder: (yuan: String) -> Unit,
    onLoadChallenge: () -> Unit,
    onSubmitPassword: (cipher: String) -> Unit,
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
                if (state.step == LifeViewModel.PowerRechargeUi.Step.Amount) {
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
                    onPlaceOrder = onPlaceOrder,
                )

                LifeViewModel.PowerRechargeUi.Step.Password -> PasswordStep(
                    state = state,
                    onSubmitPassword = onSubmitPassword,
                )

                LifeViewModel.PowerRechargeUi.Step.Accepted -> AcceptedStep(
                    paidStatus = state.paidStatus,
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
    onPlaceOrder: (String) -> Unit,
) {
    var amount by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val parsed = amount.toBigDecimalOrNull()
    val valid = parsed != null && parsed >= java.math.BigDecimal("0.01") &&
        parsed <= java.math.BigDecimal("500.00")
    val insufficient = accountFen != null && parsed != null &&
        parsed * java.math.BigDecimal(100) > java.math.BigDecimal(accountFen)

    // 说明文案默认中性灰；**输入金额超过电子账户余额**才转红（2026-09-24 用户要求）。
    // 常驻红色的写法会把「只是提示」读成「已经出错」，而这一步本来就是提示。
    Text(
        text = "当前软件暂时只支持电子账户缴费，请确保电子账户余额充足。",
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
                    amount.isEmpty() -> "0.01 – 500.00 元；充值到电子账户后用于电费"
                    !valid -> "金额需在 0.01 – 500.00 元之间"
                    insufficient -> "超出电子账户余额，请先去充值"
                    else -> "确认后下单，再用 6 位消费密码支付"
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
                Text(
                    "将为房间电费（电子账户）缴纳 ¥$amount。\n\n" +
                        "下单后用 6 位消费密码支付；未支付的订单 30 分钟自动失效，不会扣款。",
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
private fun AcceptedStep(paidStatus: Int?, onDismiss: () -> Unit) {
    Text(
        // 查单确认过才敢说「已扣款」；没确认到就如实说「已受理」（不编）
        text = if (paidStatus == 1) "支付成功，已确认扣款" else "支付已受理，正在确认到账",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Text(
        text = "电费读数可能有几分钟延迟；稍后在生活页点电费卡刷新即可看到最新剩余电量。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("完成") }
}
