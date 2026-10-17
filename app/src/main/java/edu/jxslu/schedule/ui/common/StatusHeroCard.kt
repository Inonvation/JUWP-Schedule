package edu.jxslu.schedule.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.ui.theme.semanticColors

/**
 * 卡片观感的**第二种官方规格**：状态英雄卡（DESIGN §3.2）。
 *
 * 与描边卡 [AppCard] 的分工：`AppCard` 是「列表里的一行」——surface 底 + 1dp
 * `outlineVariant` 描边 + 14dp 圆角，克制、可并列；英雄卡是「页面此刻正在发生的事」
 * ——**实心 tonal 底 + 无描边 + 16dp 圆角**，一屏只该有一张，靠底色与体量把层级压出来。
 * 两者不要混用：一屏里出现两张英雄卡就等于没有英雄卡。
 *
 * 本组件只管「底 + 圆角 + 内间距 + 语气色」，**内容排版归调用方**（状态字、大数值、
 * 进度条、动作行怎么摆是页面的事）。语气色的取色口只有 [heroToneAccent] 一处。
 */
enum class HeroTone {
    /** 正在进行 / 需要关注（订单洗涤中）。 */
    Primary,

    /** 已结束 / 中性陈述（订单已完成、已取消）。 */
    Neutral,

    /** 正向结果（洗涤完成）。 */
    Success,

    /** 失败 / 需要用户处置（支付超时、启动失败）。 */
    Error,
}

object StatusHeroCardDefaults {
    /** 16dp——比描边卡的 14dp 大一档，体量上先分出主次。 */
    val Shape = RoundedCornerShape(16.dp)

    /** 内间距：比描边卡（14/12dp）松一档，英雄卡里的字更大、要留得下。 */
    val Padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
}

/**
 * 状态英雄卡容器。[tone] 决定底色（[heroToneAccent] 是配套的强调色）。
 * 语气变化（洗涤中 → 已完成 / 失败）走底色过渡，不硬切。
 */
@Composable
fun StatusHeroCard(
    tone: HeroTone,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = StatusHeroCardDefaults.Padding,
    content: @Composable ColumnScope.() -> Unit,
) {
    val container by animateColorAsState(
        targetValue = heroToneContainer(tone),
        animationSpec = tween(durationMillis = ToneTransitionMillis),
        label = "heroContainer",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(StatusHeroCardDefaults.Shape)
            .background(container)
            .padding(contentPadding),
        content = content,
    )
}

/** 语气过渡时长（与全 App 其它状态过渡同档）。 */
private const val ToneTransitionMillis = 220

/** 语气强调色：状态点 / 状态字 / 大数值都从这里取，别再各写一份 `when`。 */
@Composable
fun heroToneAccent(tone: HeroTone): Color = when (tone) {
    HeroTone.Primary -> MaterialTheme.colorScheme.primary
    HeroTone.Neutral -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    HeroTone.Success -> MaterialTheme.semanticColors.success
    HeroTone.Error -> MaterialTheme.colorScheme.error
}

/** 语气底色：主色 / 中性用 M3 容器色的低透明版，成功色 M3 没有对应容器角色。 */
@Composable
private fun heroToneContainer(tone: HeroTone): Color = when (tone) {
    HeroTone.Primary -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    HeroTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    HeroTone.Success -> MaterialTheme.semanticColors.success.copy(alpha = 0.14f)
    HeroTone.Error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
}
