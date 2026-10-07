package edu.jxslu.schedule.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

/**
 * 金额大字：钱类弹窗 / 结果步里「这笔钱」的唯一主角样式（2026-10-07 起）。
 *
 * 口径一处定义（`displaySmall` + SemiBold，与趣智计时、骑行倒计时的大号数字同族）：
 * 已接一卡通「支付成功 / 充值成功 / 正在确认充值结果」三张弹窗与电费充值结果步。
 * 改字号 / 字重只动这里；次要信息（商户 · 时间、余额）配 bodyMedium + 低透明度小字，
 * 不要再给金额另写第二种样式。
 */
@Composable
fun AmountHeroText(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
        textAlign = textAlign,
    )
}
