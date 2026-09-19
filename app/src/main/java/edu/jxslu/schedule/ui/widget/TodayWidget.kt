package edu.jxslu.schedule.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.MainActivity
import edu.jxslu.schedule.domain.LocalTimeLike
import edu.jxslu.schedule.domain.buildTodayState
import edu.jxslu.schedule.ui.common.courseColor
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * 今日课表小组件（DESIGN §3.6）。
 *
 * 三个桌面条目（2×2 / 2×4 / 4×4）共用这一个 [GlanceAppWidget]，靠 [SizeMode.Responsive]
 * 声明尺寸档、渲染时按 [LocalSize] 分档控制信息密度。
 *
 * 数据来源与今日页**完全同源**：Room → `buildTodayState()` → [buildWidgetSnapshot]。
 *
 * ## 刷新机制（重要：别改成「在组合里起协程」）
 *
 * Glance 不是完整的 Compose 运行时：`provideContent` 会**挂起到会话关闭**，组合里既没有
 * `LaunchedEffect` 也没有 `rememberCoroutineScope`。而会话（`AppWidgetSession`）对状态的
 * 处理有两个硬约束（反编译 1.2.0 源码核实，是桌面不显示课程的根因）：
 *
 * 1. **会话的 `LocalState` 只在组合开始时读一次 DataStore，且先于 `provideGlance`**——
 *    在 `provideGlance` 里写状态，首帧组合读不到，要等下一次 `update()` 推
 *    `UpdateGlanceState` 事件。所以首帧数据必须由 `provideGlance` 直接捕获进组合；
 * 2. **`updateAppWidgetState` 只是写 DataStore，不推送 RemoteViews**——没有活跃会话时
 *    光写状态桌面纹丝不动。所以后台刷新必须「写状态 + `widget.update()`」两步
 *    （活跃会话收事件后重读状态重组；已关闭的会话重跑 `provideGlance` 产出新 RemoteViews）。
 *
 * 第 1 步的触发者见 [TodayWidgetRefresh]：边界闹钟（上下课时刻）+ WorkManager 15 分钟兜底
 * + 冷启动/数据变更时主动刷。三层都为「后台及时性」，设置页会引导用户开电池优化白名单。
 */
open class TodayWidget : GlanceAppWidget() {

    /**
     * 声明四档尺寸。
     *
     * 三个条目默认 110×110 / 110×250 / 250×250，另外把「2×4 被横向拉扁」的 250×110
     * 也列上——用户在桌面拖动改比例后系统按最接近的档给布局；不声明的话横条上会只剩一个卡
     * （竖条布局硬塞进横条）。
     */
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 110.dp),
            DpSize(250.dp, 110.dp),
            DpSize(110.dp, 250.dp),
            DpSize(250.dp, 250.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 首帧：算一次快照写进状态并**捕获进组合**（会话的状态先于 provideGlance 读取，
        // 这里写入的值首帧读不到，见类 KDoc）——首帧内容不依赖 DataStore 回读。
        val initial = WidgetSnapshotStore.computeAndStore(context, id)

        provideContent {
            // currentState 只承接后续 update() 推来的新状态；空了（写入/解码失败）退回首帧快照
            val snapshot = WidgetSnapshotStore.read() ?: initial
            val size = widgetSizeFor(
                widthDp = LocalSize.current.width.value.toInt(),
                heightDp = LocalSize.current.height.value.toInt(),
            )
            WidgetContent(snapshot.forSize(size), size)
        }
    }
}

/**
 * 快照在 Glance 状态里的键。
 *
 * 存 JSON 字符串而不是结构化 Preferences：快照有嵌套（焦点 / 明日 / 行列表），
 * 拆成多个 key 只会把编解码散到两处（见 [WidgetSnapshotCodec]）。
 */
private val SnapshotKey = stringPreferencesKey("today_snapshot")

/** 快照读写；widget 组合与 [TodayWidgetRefresh] 共用。 */
internal object WidgetSnapshotStore {

    /** 读当前实例的快照。解码失败返回 null（组合显示兜底文案，不崩）。 */
    @Composable
    fun read(): WidgetSnapshot? = WidgetSnapshotCodec.decode(currentState(SnapshotKey))

    /** 算一份当前时刻的快照（Room 三路 + now）。 */
    private suspend fun computeSnapshot(context: Context): WidgetSnapshot {
        val repo = Graph.repository(context)
        // widget 可能先于任何界面被拉起（桌面重启、系统重启），这里自负初始化
        repo.ensureDefaults()
        return buildWidgetSnapshot(
            buildTodayState(
                semester = repo.semester.first(),
                slots = repo.timeSlots.first(),
                courses = repo.courses.first(),
                today = LocalDate.now(),
                now = LocalTimeLike.now(),
            ),
        )
    }

    /**
     * provideGlance 首帧用：算快照、写进该实例状态，并**原样返回**给组合捕获。
     *
     * 返回值不是可有可无——会话的 `LocalState` 先于 `provideGlance` 读取，这里写进
     * DataStore 的快照首帧读不到（见 [TodayWidget] 的类 KDoc），组合必须直接用这份返回值。
     */
    internal suspend fun computeAndStore(context: Context, id: GlanceId): WidgetSnapshot {
        val snapshot = computeSnapshot(context)
        write(context, id, snapshot)
        return snapshot
    }

    /**
     * 刷新**全部**已添加的实例（三个条目逐类查询）。
     *
     * 必须「写状态 + `update()`」两步，缺一不可：
     * - 光写状态：活跃会话**不观察** DataStore，不会重组；没有会话时更不会推到桌面；
     * - `update()`：活跃会话收到 `UpdateGlanceState` 事件、重读刚写进的状态重组；
     *   已关闭的会话则重跑 `provideGlance` 产出新 RemoteViews（此时快照会算两遍——
     *   一次这里、一次 `provideGlance` 里。刷新频率低，可接受）。
     *
     * 实例若已被用户删掉，`update()` 会失败——`runCatching` 吞掉即可，
     * 反正下次遍历就查不到了。
     */
    suspend fun refreshAll(context: Context) {
        val snapshot = computeSnapshot(context)
        val manager = GlanceAppWidgetManager(context)
        TodayWidgetVariants.widgets.forEach { (cls, widget) ->
            manager.getGlanceIds(cls).forEach { id ->
                runCatching {
                    write(context, id, snapshot)
                    widget.update(context, id)
                }
            }
        }
    }

    /** 是否还有任一实例绑在桌面上（无实例时刷新与闹钟调度都是空跑）。 */
    suspend fun hasAnyBound(context: Context): Boolean {
        val manager = GlanceAppWidgetManager(context)
        return TodayWidgetVariants.all.any { manager.getGlanceIds(it).isNotEmpty() }
    }

    private suspend fun write(context: Context, id: GlanceId, snapshot: WidgetSnapshot) {
        updateAppWidgetState(context, id) { prefs ->
            prefs[SnapshotKey] = WidgetSnapshotCodec.encode(snapshot)
        }
    }
}

/**
 * 小组件内容。三档信息密度：
 * - [WidgetSize.Small]：只有焦点卡（110dp 高，标题行会把卡片挤没）
 * - [WidgetSize.Wide] / [WidgetSize.Tall]：标题 + 焦点 + 若干行
 * - [WidgetSize.Large]：再加明日块
 */
@Composable
private fun WidgetContent(model: WidgetModel, size: WidgetSize) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground)
            // 整卡点击进 App 今日页。不做「点某一节就编辑」：小组件里没有键盘与校验，
            // 误触成本高于便利（DESIGN §3.6 明确不做）
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (size.showHeader) {
                Text(
                    text = model.header,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(6.dp))
            }

            FocusBlock(model.focus, size)

            model.rows.forEach { row ->
                Spacer(GlanceModifier.height(5.dp))
                CourseRow(row)
            }
            if (model.rowsMoreLabel != null) {
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    text = model.rowsMoreLabel,
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
            }

            // 明日块推到卡片底部：4×4 里今日在上、明日在下，层级一眼可辨
            model.tomorrow?.let { tomorrow ->
                Spacer(GlanceModifier.defaultWeight())
                TomorrowBlock(tomorrow)
            }
        }
    }
}

@Composable
private fun FocusBlock(focus: WidgetFocus, size: WidgetSize) {
    when (focus) {
        is WidgetFocus.Course -> {
            val accent = courseColor(focus.colorIndex)
            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .cornerRadius(12.dp)
                    .background(accent.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    Text(
                        text = focus.label,
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = ColorProvider(accent),
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    val note = focus.countdown ?: focus.fallbackNote
                    if (note != null) {
                        Text(
                            text = note,
                            style = TextStyle(fontSize = 11.sp, color = ColorProvider(accent)),
                            maxLines = 1,
                        )
                    }
                }
                Spacer(GlanceModifier.height(3.dp))
                Text(
                    text = focus.name,
                    style = TextStyle(
                        fontSize = if (size == WidgetSize.Small) 15.sp else 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onSurface,
                    ),
                    maxLines = 2,
                )
                if (focus.meta.isNotBlank()) {
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        text = focus.meta,
                        style = TextStyle(
                            fontSize = 10.sp,
                            color = GlanceTheme.colors.onSurfaceVariant,
                        ),
                        maxLines = 1,
                    )
                }
                val progress = focus.progress
                if (progress != null && size != WidgetSize.Small) {
                    Spacer(GlanceModifier.height(6.dp))
                    ProgressLine(progress, accent)
                }
            }
        }

        is WidgetFocus.Idle -> Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.Horizontal.Start,
        ) {
            Text(
                text = focus.title,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.onSurface,
                ),
                maxLines = 1,
            )
            // 小尺寸放不下副行，宁可不显示，也不截断成「去「课…」
            if (size != WidgetSize.Small) {
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = focus.detail,
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** 进度条分段数：Glance 没有 `fillMaxWidth(fraction)`，用 N 段拼接表达比例。 */
private const val ProgressSegments = 12

/**
 * 整门课进度条。
 *
 * 为什么是分段而不是连续条：Glance 的 `fillMaxWidth` 没有按比例的重载（只有整宽），
 * 而 `LinearProgressIndicator` 在 glance-appwidget 里只提供不定态实现，
 * 且宽度要等测量后才知道（无法按 fraction 给内层定宽）。分段是唯一既准确又不依赖测量的画法。
 */
@Composable
private fun ProgressLine(progress: Float, accent: Color) {
    val filled = (progress.coerceIn(0f, 1f) * ProgressSegments).toInt()
    Row(modifier = GlanceModifier.fillMaxWidth().height(3.dp)) {
        repeat(ProgressSegments) { index ->
            if (index > 0) Spacer(GlanceModifier.width(1.dp))
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxSize()
                    .background(if (index < filled) accent else accent.copy(alpha = 0.22f)),
            ) {}
        }
    }
}

/** 一行剩余课程：行首时刻 + 色卡（课名 / 副行）。 */
@Composable
private fun CourseRow(row: WidgetCourseRow) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = row.clock,
            style = TextStyle(
                fontSize = 11.sp,
                color = GlanceTheme.colors.onSurfaceVariant,
            ),
            maxLines = 1,
            modifier = GlanceModifier.width(38.dp),
        )
        Spacer(GlanceModifier.width(6.dp))
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .cornerRadius(8.dp)
                .background(courseColor(row.colorIndex).copy(alpha = 0.16f))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text(
                text = row.name,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = GlanceTheme.colors.onSurface,
                ),
                maxLines = 1,
            )
            if (row.meta.isNotBlank()) {
                Text(
                    text = row.meta,
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** 明日块：有课列前几节，没课给一句休息提示（与今日页明日预告同款文案）。 */
@Composable
private fun TomorrowBlock(tomorrow: WidgetTomorrow) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        when (tomorrow) {
            is WidgetTomorrow.Courses -> {
                Text(
                    text = tomorrow.title,
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
                tomorrow.rows.take(3).forEachIndexed { index, row ->
                    Spacer(GlanceModifier.height(if (index == 0) 4.dp else 5.dp))
                    CourseRow(row)
                }
            }

            is WidgetTomorrow.Rest -> {
                Text(
                    text = tomorrow.title,
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = tomorrow.detail,
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}
