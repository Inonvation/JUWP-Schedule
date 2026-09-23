package edu.jxslu.schedule.ui.life

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 电费充值弹层（DESIGN §4.24）：金额 → 密码 → 受理。
 *
 * - **无状态组件**：流程状态在 [LifeViewModel.powerRecharge]（VM 持有，转屏不丢）；
 * - 电子账户余额与说明常驻（「仅支持电子账户缴费」，用户拍板口径）；
 * - 「去充值电子账户」由 [onOpenCampusRecharge] 承接（打开一卡通充值并预选电子账户）。
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("电费充值", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (roomLabel != null || remainText != null) {
                Text(
                    text = listOfNotNull(roomLabel, remainText).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Text(
                text = "电子账户余额 " + (accountFen?.let { "¥%.2f".format(it / 100.0) } ?: "—"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "当前软件暂时只支持电子账户缴费，请确保电子账户余额充足。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            when (state.step) {
                LifeViewModel.PowerRechargeUi.Step.Amount -> AmountStep(
                    state = state,
                    accountFen = accountFen,
                    onPlaceOrder = onPlaceOrder,
                    onOpenCampusRecharge = onOpenCampusRecharge,
                    onDismiss = onDismiss,
                )

                LifeViewModel.PowerRechargeUi.Step.Password -> PasswordStep(
                    state = state,
                    onSubmitPassword = onSubmitPassword,
                )

                LifeViewModel.PowerRechargeUi.Step.Accepted -> AcceptedStep(onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun AmountStep(
    state: LifeViewModel.PowerRechargeUi,
    accountFen: Long?,
    onPlaceOrder: (String) -> Unit,
    onOpenCampusRecharge: () -> Unit,
    onDismiss: () -> Unit,
) {
    var amount by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val parsed = amount.toBigDecimalOrNull()
    val valid = parsed != null && parsed >= java.math.BigDecimal("0.01") &&
        parsed <= java.math.BigDecimal("500.00")
    val insufficient = accountFen != null && parsed != null &&
        parsed * java.math.BigDecimal(100) > java.math.BigDecimal(accountFen)

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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
        OutlinedButton(onClick = onOpenCampusRecharge, modifier = Modifier.weight(1.6f)) {
            Text("去充值电子账户")
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

@Composable
private fun PasswordStep(
    state: LifeViewModel.PowerRechargeUi,
    onSubmitPassword: (String) -> Unit,
) {
    // 明文数字输入（用户拍板：不要乱序盲键盘）。输入框本身仍不回显数字，
    // 用 6 格点阵代替——安全观感在、数字可见性由系统键盘保证。
    var cipher by rememberSaveable { mutableStateOf("") }
    Text(
        text = "请输入 6 位消费密码",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    // 密码点阵：6 格，已输几位就亮几个
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp, alignment = androidx.compose.ui.Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth(),
    ) {
        repeat(6) { index ->
            val filled = index < cipher.length
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .border(
                        1.dp,
                        if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(8.dp),
                    ),
            )
        }
    }
    OutlinedTextField(
        value = cipher,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }
            if (digits.length <= 6) cipher = digits
        },
        label = { Text("6 位消费密码") },
        supportingText = { Text("6 位数字支付密码，与登录密码相互独立；仅用于本次支付，不保存") },
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        isError = state.error != null,
        modifier = Modifier.fillMaxWidth(),
    )
    state.error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { cipher = "" },
            enabled = cipher.isNotEmpty() && !state.busy,
            modifier = Modifier.weight(1f),
        ) { Text("清空") }
        Button(
            onClick = { onSubmitPassword(cipher) },
            enabled = cipher.length == 6 && !state.busy,
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

@Composable
private fun AcceptedStep(onDismiss: () -> Unit) {
    Text(
        text = "支付已受理，正在确认到账",
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
