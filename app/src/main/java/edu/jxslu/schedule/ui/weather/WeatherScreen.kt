package edu.jxslu.schedule.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.weather.WeatherApi
import edu.jxslu.schedule.domain.WeatherAdvice
import edu.jxslu.schedule.domain.WeatherCodes
import edu.jxslu.schedule.domain.WeatherDay
import edu.jxslu.schedule.domain.WeatherHour
import edu.jxslu.schedule.domain.WeatherSnapshot
import edu.jxslu.schedule.domain.dayLabel
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.SkeletonBox
import edu.jxslu.schedule.ui.common.lineHeightDp
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Sunrise
import me.rerere.hugeicons.stroke.Temperature
import me.rerere.hugeicons.stroke.Umbrella
import me.rerere.hugeicons.stroke.Wind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * 天气详情页（DESIGN §3.29）。落点：今日页顶栏那行提示（功能开启时）/ 天气设置页的「查看天气」。
 *
 * 排版按主流天气页的路子（2026-10-11 用户要求重构）：
 * 实时卡（大字温度）→ 今天 / 明天提醒 → 未来 24 小时 → 未来 7 天（带温度区间条）→
 * 六格明细 → 来源署名。地点名在顶栏副标题（查询点固定为校区，没有反查地名那一步）。
 *
 * 三条已定的口径（改之前先读）：
 * 1. **首屏是骨架不是空态**（`.agents/rules/ui-common.md`）：先铺同几何的骨架、数据回来直接替换；
 * 2. **下拉刷新**：阈值与 `PullToRefreshDefaults.Indicator` **必须同值**，
 *    容器吃 Scaffold 的 padding（否则指示器整条轨迹藏在顶栏后面）；
 * 3. 数据来自 Open-Meteo（CC BY 4.0），**署名不能删**。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WeatherScreen(
    onBack: () -> Unit,
    viewModel: WeatherViewModel = viewModel(
        factory = WeatherViewModel.Factory(Graph.weatherRepository(LocalContext.current)),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = rememberAppHaptics()
    val pullState = rememberPullToRefreshState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("天气")
                        Text(
                            text = WeatherApi.CAMPUS_LABEL,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val snapshot = state.snapshot
        // 关掉本页的触底回弹（stretch overscroll，2026-10-11 用户报「滑到底部再滑会卡一下」）。
        // 机制：拖到头之后系统把**整个滚动内容**做拉伸（31+ 走 RenderEffect 图层），
        // 而本页是一条 `Column + verticalScroll` 的长内容（约 4 屏、30 多个 emoji 字形），
        // 拉伸那一帧要整块重栅格化，就是这个卡顿；今日页/问渠那种 LazyColumn 的图层只有
        // 一屏高，所以没这问题。本页是展示型静态页，边到边回弹没有信息量，直接关掉。
        // `LocalOverscrollConfiguration provides null` 是这套 API 的正规开关（glow 与
        // stretch 都归它管）；**别删**——删了就回到「触底卡一下」。
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .pullToRefresh(
                        isRefreshing = state.refreshing,
                        state = pullState,
                        threshold = WeatherPullRefreshThreshold,
                        onRefresh = {
                            haptics.tap()
                            viewModel.refresh()
                        },
                    ),
            ) {
                when {
                    // 首屏骨架：几何与成品同一套（同卡片、同行高），数据回来直接替换
                    snapshot == null && state.loading -> WeatherSkeleton()

                    // 空态也套一层可滚动容器：指示器靠嵌套滚动接手势，纯静态子节点接不住下拉。
                    // 可滚动容器里 `Arrangement.Center` 不生效（内容高度不受约束），
                    // 用 BoxWithConstraints 把内容最小高度钉到视口高，才真的居中。
                    snapshot == null -> BoxWithConstraints(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .heightIn(min = maxHeight),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            EmptyHint(
                                title = "没能拿到天气",
                                body = "网络或服务异常。天气数据来自 Open-Meteo，仅供参考。" +
                                    "下拉或点下面都可以重试。",
                                actionLabel = "重试",
                                onAction = { viewModel.load(force = true) },
                            )
                        }
                    }

                    else -> WeatherContent(
                        snapshot = snapshot,
                        loadedAtMs = state.loadedAtMs,
                        stale = state.stale,
                    )
                }
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    threshold = WeatherPullRefreshThreshold,
                )
            }
        }
    }
}

/**
 * 下拉刷新阈值。M3 默认 80dp，而拖动里计入的只有实际位移的一半
 * （`DragMultiplier = 0.5f`），即真拖 160dp 才触发；收到 56dp（真拖 112dp），
 * 与今日页同值。modifier 与 Indicator 两处 threshold 必须一致。
 */
private val WeatherPullRefreshThreshold = 56.dp

// ---------------------------------------------------------------------------
// 内容
// ---------------------------------------------------------------------------

@Composable
private fun WeatherContent(
    snapshot: WeatherSnapshot,
    loadedAtMs: Long,
    stale: Boolean,
) {
    val now = LocalDateTime.now()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CurrentCard(snapshot)
        AdviceCard(snapshot)
        HourlyCard(snapshot, now)
        DailyCard(snapshot)
        DetailsGrid(snapshot)
        Attribution(loadedAtMs, stale)
        Spacer(Modifier.height(8.dp))
    }
}

/** 实时卡：大字温度 + 天气 + 今日区间。 */
@Composable
private fun CurrentCard(snapshot: WeatherSnapshot) {
    val current = snapshot.current
    val today = snapshot.day(0)
    AppCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "${current.tempC.roundToInt()}°",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${WeatherCodes.emoji(current.code, current.isDay)} ${WeatherCodes.label(current.code)}",
                style = MaterialTheme.typography.titleMedium,
            )
            if (today != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        today.tempMaxC?.let { append("最高 ${it.roundToInt()}°") }
                        today.tempMinC?.let {
                            if (isNotEmpty()) append(" · ")
                            append("最低 ${it.roundToInt()}°")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 今天 / 明天各一句提醒（与今日页顶栏同一套话术，[WeatherAdvice.tip] 是唯一来源）。
 * 顶栏那行按 18:00 分界只显示其中一条，这里两条都给，方便提前准备。
 */
@Composable
private fun AdviceCard(snapshot: WeatherSnapshot) {
    val today = snapshot.day(0)
    val tomorrow = snapshot.day(1)
    if (today == null && tomorrow == null) return
    AppCard {
        today?.let { day ->
            AdviceLine(WeatherCodes.emoji(day.code, snapshot.current.isDay), WeatherAdvice.tip(snapshot, day, today = true))
        }
        if (today != null && tomorrow != null) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        tomorrow?.let { day ->
            AdviceLine(WeatherCodes.emoji(day.code, isDay = true), WeatherAdvice.tip(snapshot, day, today = false))
        }
    }
}

@Composable
private fun AdviceLine(emoji: String, text: String) {
    Text(
        text = "$emoji $text",
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** 未来 24 小时：横向滚动，重点看降水概率（≥30% 标主色）。 */
@Composable
private fun HourlyCard(snapshot: WeatherSnapshot, now: LocalDateTime) {
    if (snapshot.hourly.isEmpty()) return
    // 「现在」= 不晚于此刻的**最后一个**整点：拿的是半小时前的缓存时也标得对。
    // 一条都不匹配（设备时区 / 时钟与接口口径不一致）时退回第一条——接口给的第一个
    // 小时就是取数那一刻的当前小时，退回它比不标更接近事实。
    val currentHour = now.withMinute(0).withSecond(0).withNano(0)
    val nowIndex = snapshot.hourly
        .indexOfLast { !it.time.isAfter(currentHour) }
        .takeIf { it >= 0 } ?: 0
    AppCard {
        Text("未来 24 小时", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            snapshot.hourly.forEachIndexed { index, hour ->
                HourCell(hour, isNow = index == nowIndex)
            }
        }
    }
}

@Composable
private fun HourCell(hour: WeatherHour, isNow: Boolean) {
    Column(
        modifier = Modifier.width(44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (isNow) "现在" else "%02d时".format(hour.time.hour),
            style = MaterialTheme.typography.labelSmall,
            color = if (isNow) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (isNow) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = WeatherCodes.emoji(hour.code, isDay = hour.isDay ?: true),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(2.dp))
        Text(text = "${hour.tempC.roundToInt()}°", style = MaterialTheme.typography.bodySmall)
        Text(
            text = hour.precipProbability?.let { "$it%" } ?: "—",
            style = MaterialTheme.typography.labelSmall,
            color = if ((hour.precipProbability ?: 0) >= WeatherAdvice.RAIN_HINT_PERCENT) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 未来 7 天：日期 · 天气 · 温度区间条（条的位置 = 该日区间在整周范围里的落点）。 */
@Composable
private fun DailyCard(snapshot: WeatherSnapshot) {
    if (snapshot.daily.isEmpty()) return
    val weekMin = snapshot.daily.mapNotNull { it.tempMinC }.minOrNull()
    val weekMax = snapshot.daily.mapNotNull { it.tempMaxC }.maxOrNull()
    AppCard {
        Text("未来 7 天", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        val today = LocalDate.now()
        snapshot.daily.forEachIndexed { index, day ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            DailyRow(day, today, weekMin, weekMax)
        }
    }
}

@Composable
private fun DailyRow(
    day: WeatherDay,
    today: LocalDate,
    weekMin: Double?,
    weekMax: Double?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = dayNameOf(day.date, today),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = "${WeatherCodes.emoji(day.code)} ${WeatherCodes.label(day.code)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            text = day.tempMinC?.let { "${it.roundToInt()}°" } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(36.dp),
        )
        TempRangeBar(
            day = day,
            weekMin = weekMin,
            weekMax = weekMax,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .width(76.dp)
                .height(6.dp),
        )
        Text(
            text = day.tempMaxC?.let { "${it.roundToInt()}°" } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(36.dp),
        )
    }
}

/** 该日温度区间在整周范围里的落点条（Canvas 画，不用布局算偏移）。 */
@Composable
private fun TempRangeBar(
    day: WeatherDay,
    weekMin: Double?,
    weekMax: Double?,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val fillColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val radius = size.height / 2f
        drawRoundRect(color = trackColor, cornerRadius = CornerRadius(radius, radius))
        val min = day.tempMinC ?: return@Canvas
        val max = day.tempMaxC ?: return@Canvas
        val low = weekMin ?: min
        val high = weekMax ?: max
        val span = (high - low).coerceAtLeast(1.0)
        val startFraction = ((min - low) / span).toFloat().coerceIn(0f, 1f)
        val endFraction = ((max - low) / span).toFloat().coerceIn(0f, 1f)
        val startX = size.width * startFraction
        // 最窄也留一个圆点，免得「一天都没波动」的区间看不见
        val barWidth = (size.width * (endFraction - startFraction)).coerceAtLeast(size.height)
        drawRoundRect(
            color = fillColor,
            topLeft = Offset(startX, 0f),
            size = Size(barWidth, size.height),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}

/** 日期 → 今天 / 明天 / 后天 / 周X。按**日期**判而不是按下标，跨天刷新时不会错位。 */
private fun dayNameOf(date: LocalDate, today: LocalDate): String {
    val diff = ChronoUnit.DAYS.between(today, date)
    return when (diff) {
        0L -> "今天"
        1L -> "明天"
        2L -> "后天"
        else -> "周" + dayLabel(date.dayOfWeek.value)
    }
}

/** 六格明细：体感 / 湿度 / 风 / 紫外线 / 降水概率 / 日出日落。 */
@Composable
private fun DetailsGrid(snapshot: WeatherSnapshot) {
    val current = snapshot.current
    val today = snapshot.day(0)
    val cells = listOf(
        Triple(HugeIcons.Temperature, "体感温度", current.apparentC?.let { "${it.roundToInt()}°" } ?: "—"),
        Triple(HugeIcons.Droplet, "湿度", current.humidity?.let { "$it%" } ?: "—"),
        Triple(
            HugeIcons.Wind,
            "风",
            buildString {
                append(WeatherCodes.windLabel(current.windDirection) ?: "风向 —")
                current.windKmh?.let { append(" ").append(it.roundToInt()).append(" km/h") }
            },
        ),
        Triple(
            HugeIcons.Sun01,
            "紫外线",
            today?.uvIndexMax?.let { value ->
                "${value.roundToInt()} ${WeatherCodes.uvLabel(value)}"
            } ?: "—",
        ),
        Triple(
            HugeIcons.Umbrella,
            "降水概率",
            today?.precipProbabilityMax?.let { "今天 $it%" } ?: "—",
        ),
        Triple(
            HugeIcons.Sunrise,
            "日出 · 日落",
            if (today?.sunrise != null && today.sunset != null) {
                "${today.sunrise} · ${today.sunset}"
            } else {
                "—"
            },
        ),
    )
    // 两列三行；下一行按钮行常显的口径在这里不适用（明细是数据，没有该不该出现的问题）
    cells.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { (icon, label, value) ->
                DetailCell(
                    icon = icon,
                    label = label,
                    value = value,
                    modifier = Modifier.weight(1f),
                )
            }
            // 奇数个时补一格空位，保持左对齐不留半宽
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun DetailCell(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    AppCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 数据来源与时效。CC BY 4.0 要求署名，**这段不能删**。 */
@Composable
private fun Attribution(loadedAtMs: Long, stale: Boolean) {
    val updated = updatedAtText(loadedAtMs)
    Column {
        Text(
            text = buildString {
                append("数据来源 Open-Meteo（CC BY 4.0）")
                if (updated != null) append(" · ").append(updated)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (stale) "刚才没刷新成功，显示的可能是旧数据，下拉可重试" else "预报仅供参考，与实况可能有出入",
            style = MaterialTheme.typography.labelSmall,
            color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun updatedAtText(millis: Long): String? {
    if (millis <= 0L) return null
    val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
    return "%02d:%02d 更新".format(time.hour, time.minute)
}

// ---------------------------------------------------------------------------
// 骨架：与成品同一套卡片与行高，数据回来直接替换、不跳
// ---------------------------------------------------------------------------

/** 骨架条高占行盒的比例：一行文字的字形本来只占行盒中间约六成（见 ui-common 规则）。 */
private const val SKELETON_LINE_RATIO = 0.62f

@Composable
private fun WeatherSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 实时卡：大字温度 + 天气行 + 区间行
        AppCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SkeletonLine(width = 128.dp, style = MaterialTheme.typography.displayMedium)
                SkeletonLine(width = 120.dp, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                SkeletonLine(width = 176.dp, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // 提醒卡：两行 + 中缝
        AppCard {
            SkeletonLine(width = 232.dp, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            SkeletonLine(width = 204.dp, style = MaterialTheme.typography.bodyMedium)
        }
        // 小时卡：标题 + 六个格位（成品首屏也是六格可见）
        AppCard {
            SkeletonLine(width = 92.dp, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(6) {
                    Column(
                        modifier = Modifier.width(44.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        SkeletonLine(width = 28.dp, style = MaterialTheme.typography.labelSmall, centered = true)
                        Spacer(Modifier.height(4.dp))
                        SkeletonBox(Modifier.size(20.dp), RoundedCornerShape(6.dp))
                        Spacer(Modifier.height(4.dp))
                        SkeletonLine(width = 24.dp, style = MaterialTheme.typography.bodySmall, centered = true)
                        SkeletonLine(width = 20.dp, style = MaterialTheme.typography.labelSmall, centered = true)
                    }
                }
            }
        }
        // 日预报卡：标题 + 七行（日期 / 天气 / 温度条）
        AppCard {
            SkeletonLine(width = 80.dp, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            repeat(7) { index ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkeletonLine(width = 44.dp, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    SkeletonLine(width = 64.dp, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    SkeletonLine(width = 28.dp, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    SkeletonBox(
                        Modifier.width(76.dp).height(6.dp),
                        RoundedCornerShape(3.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    SkeletonLine(width = 28.dp, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        // 明细：两列三行
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonDetailCell(Modifier.weight(1f))
                SkeletonDetailCell(Modifier.weight(1f))
            }
        }
        // 署名两行
        Column {
            SkeletonLine(width = 220.dp, style = MaterialTheme.typography.labelSmall)
            SkeletonLine(width = 180.dp, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SkeletonDetailCell(modifier: Modifier = Modifier) {
    AppCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonBox(Modifier.size(18.dp), RoundedCornerShape(4.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                SkeletonLine(width = 48.dp, style = MaterialTheme.typography.labelSmall)
                SkeletonLine(width = 68.dp, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * 行内一根骨架条：包在**整行行高**的盒子里垂直居中，条高 = 行高 × [SKELETON_LINE_RATIO]。
 * 行高走 [lineHeightDp]（跟随当前生效的字体缩放），不写死 dp。
 */
@Composable
private fun SkeletonLine(
    width: Dp,
    style: TextStyle,
    centered: Boolean = false,
) {
    val line = lineHeightDp(style, 16.dp)
    Box(
        modifier = Modifier.height(line),
        contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart,
    ) {
        SkeletonBox(
            Modifier.width(width).height(line * SKELETON_LINE_RATIO),
            RoundedCornerShape(4.dp),
        )
    }
}
