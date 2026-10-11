package edu.jxslu.schedule.ui.wenqu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.SkeletonBox
import edu.jxslu.schedule.ui.common.lineHeightDp

/**
 * 问渠的**骨架屏**（2026-10-11）。
 *
 * 口径同 `.agents/rules/ui-common.md`「等数据的地方先给骨架」：几何与成品一致，
 * 换上去的时候不跳。两处——
 * - [WenquActivitySkeleton]：主页的列表区（首屏整份抓快照要几秒，此前只有一句转圈提示）；
 * - [WenquRecordsSkeleton]：记录页。
 *
 * 文字行**必须走 [lineHeightDp]**（不是写死 dp）：用户把系统字体调大时，成品那行会比
 * 写死的骨架高一截，跳动又回来了。条高 = 行高 × [SKELETON_LINE_RATIO]，
 * 因为一行文字的字形本来就只占行盒中间约六成（同生活页流水卡的口径）。
 */
@Composable
internal fun WenquActivitySkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 与真实卡片同几何：首行（徽章 · 名额 · 时间）+ 标题两行 + `单位 · 地点` 一行
        repeat(5) { WenquCardSkeleton() }
    }
}

/** 记录页的骨架：页首条数行 + 三张记录卡（记录卡首行右侧是工时，不是时间）。 */
@Composable
internal fun WenquRecordsSkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SkeletonLine(width = 140.dp, style = MaterialTheme.typography.bodySmall)
        repeat(3) { WenquCardSkeleton() }
    }
}

@Composable
private fun WenquCardSkeleton() {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 状态徽章
            SkeletonBox(Modifier.width(52.dp).height(16.dp), RoundedCornerShape(5.dp))
            Spacer(Modifier.width(8.dp))
            // 名额
            SkeletonBox(Modifier.width(44.dp).height(16.dp), RoundedCornerShape(5.dp))
            Spacer(Modifier.weight(1f))
            // 右侧时刻 / 工时
            SkeletonBox(Modifier.width(72.dp).height(14.dp), RoundedCornerShape(4.dp))
        }
        Spacer(Modifier.height(6.dp))
        // 标题（最多两行）
        SkeletonLine(style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(3.dp))
        SkeletonLine(style = MaterialTheme.typography.bodyLarge, widthFraction = 0.72f)
        Spacer(Modifier.height(3.dp))
        // `单位 · 地点`
        SkeletonLine(style = MaterialTheme.typography.bodySmall, widthFraction = 0.5f)
    }
}

/**
 * 一根骨架条：包在**整行行高**的盒子里垂直居中，条高 = 行高 × [SKELETON_LINE_RATIO]。
 * 实心条按整行行高画的话，上下两条会贴死成一块（2026-09-26 用户报过）。
 */
@Composable
private fun SkeletonLine(
    style: TextStyle,
    widthFraction: Float = 1f,
    width: Dp? = null,
) {
    val line = lineHeightDp(style)
    Box(
        modifier = Modifier.height(line),
        contentAlignment = Alignment.CenterStart,
    ) {
        val box = Modifier.height(line * SKELETON_LINE_RATIO)
        SkeletonBox(
            modifier = if (width != null) box.width(width) else box.fillMaxWidth(widthFraction),
            shape = RoundedCornerShape(4.dp),
        )
    }
}

/** 骨架条占行盒高的比例：一行文字的字形大约只画行盒中间的六成。 */
private const val SKELETON_LINE_RATIO = 0.62f
