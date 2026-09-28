package edu.jxslu.schedule.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
// Intent 版：整卡走自家 widgetIntent（带 CLEAR_TASK），理由见 widgetIntent 的 KDoc
import androidx.glance.appwidget.action.actionStartActivity as actionStartActivityIntent
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.ROUTE_WATER
import edu.jxslu.schedule.ROUTE_WATER_START
import edu.jxslu.schedule.domain.ThemePalette
import kotlinx.coroutines.flow.first

/**
 * 胖乖开水小组件（DESIGN §3.6「开水两卡」，2026-09-28）。
 *
 * **固定 2×2**：provider XML `resizeMode="none"`，用户要求这张卡不给拖动（与可拖的
 * 课表 / 校园卡条目不同）。单条目 + `SizeMode.Exact` 机制不变（[ScheduleWidget] 类 KDoc
 * 的两条 Glance 硬约束这里原样适用：首帧快照捕获进组合；后台「写状态 + `update()`」两步）。
 *
 * 形态：头部（条目名 + 更新时刻）+ 主区（小票余额大字）+ 副行（小票 · 积分）+ 沉底胶囊
 * 「去开水」。**点击分区**：胶囊 → 开水页并**自动开水**（`ROUTE_WATER_START` + 一次性
 * 令牌 `WaterAutoStart`，未登录 / 无设备时页面自行引导或提示）；整卡其余位置 → 只进
 * 开水页（未登录也是它——登录表单就在那页，没有单独的设置页）。
 *
 * 数据：DataStore 权威快照（[WaterWidgetSync]），**渲染路径零网络**——余额由开水页 /
 * 今日页开水卡的余额刷新顺手推送，后台另有 2 小时闸门兜底（复用 [CampusBalanceGate]）。
 * **自动开水只发生在页面 VM**：开水是一条多步下单链（SKU → 风控 → 后付 → 开阀 →
 * 轮询结算），胶囊直达只是把入口搬上桌面，下单链仍由页面里的协程跑完。
 *
 * 主区三态：
 * - 未登录：引导文案，点击 → 开水页登录；
 * - 已登录 + 从未取到：「余额待更新」，点击 → 开水页；
 * - 已登录 + 有数据：小票余额大字 + 积分副行。
 */
class QiekjWaterWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 首帧：读权威快照写进实例状态并捕获进组合（会话的状态先于 provideGlance 读取，
        // 这里写入的值首帧读不到——与课表小组件同一坑，见其类 KDoc 第 1 条）
        val initial = QiekjWaterSnapshotStore.computeAndStore(context, id)
        // 胶囊色跟随所选主题配色（DESIGN §3.3）：纯本地偏好读取，零网络
        val palette = Graph.displayPrefs(context).themePalette.first()

        provideContent {
            // currentState 只承接后续 update() 推来的新状态；解码失败退回首帧快照
            val snapshot = QiekjWaterSnapshotStore.read() ?: initial
            QiekjWaterCardContent(snapshot, palette)
        }
    }
}

class QiekjWaterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QiekjWaterWidget()
    // 周期刷新复用课表小组件那条 15 分钟 Worker（JuwApplication 冷启动必排，见
    // WidgetRefreshWorker），这里不需要 onEnabled 自排——按内容拆的条目不各自养调度。
}

/** 快照在 Glance 状态里的键与读写；编排（何时推送 / 闸门取数）在 [WaterWidgetSync]。 */
private val QiekjWaterSnapshotKey = stringPreferencesKey("qiekj_water_snapshot")

internal object QiekjWaterSnapshotStore {

    @Composable
    fun read(): QiekjWaterSnapshot? =
        QiekjWaterSnapshotCodec.decode(currentState(QiekjWaterSnapshotKey))

    /** provideGlance 首帧用：算快照、写状态、原样返回给组合捕获（返回值不可省，理由见上）。 */
    internal suspend fun computeAndStore(context: Context, id: GlanceId): QiekjWaterSnapshot {
        val snapshot = WaterWidgetSync.qiekjSnapshot(context)
        write(context, id, snapshot)
        return snapshot
    }

    /** 刷新全部已添加实例：写状态 + `update()` 两步，缺一不可（课表小组件类 KDoc 第 2 条）。 */
    internal suspend fun refreshAll(context: Context, snapshot: QiekjWaterSnapshot) {
        val manager = GlanceAppWidgetManager(context)
        val widget = QiekjWaterWidget()
        manager.getGlanceIds(QiekjWaterWidget::class.java).forEach { id ->
            runCatching {
                write(context, id, snapshot)
                widget.update(context, id)
            }
        }
    }

    private suspend fun write(context: Context, id: GlanceId, snapshot: QiekjWaterSnapshot) {
        updateAppWidgetState(context, id) { prefs ->
            prefs[QiekjWaterSnapshotKey] = QiekjWaterSnapshotCodec.encode(snapshot)
        }
    }
}

@Composable
private fun QiekjWaterCardContent(snapshot: QiekjWaterSnapshot, palette: ThemePalette) {
    val context = LocalContext.current
    // 固定 2×2（真机实测约 150×178dp）天然落在窄档；仍走 isNarrowWidth 分档，
    // 防 ROM 给出更大的初始尺寸时排版散架
    val narrow = isNarrowWidth(LocalSize.current.width.value)
    val bigFontSp = bigNumberFontSp(LocalSize.current.width.value)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .clickable(actionStartActivityIntent(widgetIntent(context, ROUTE_WATER))),
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            WidgetHeaderRow("胖乖开水", LifeWidgetFormat.timeLabel(snapshot.fetchedAtMs))

            // 窄档：三处间隔都是弹性，等分剩余高度 → 内容铺满卡片（见 WidgetGap）
            WidgetGap(narrow, wideDp = 6)
            when {
                !snapshot.loggedIn -> {
                    Text(
                        text = "未登录胖乖生活",
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.height(3.dp))
                    Text(
                        text = "点按前往开水页登录，登录后这里显示小票余额",
                        style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                        maxLines = 2,
                    )
                }

                snapshot.ticketYuan == null -> {
                    Text(
                        text = "余额待更新",
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.height(3.dp))
                    Text(
                        text = "点按进开水页，取到余额后这里会同步",
                        style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                        maxLines = 2,
                    )
                }

                else -> {
                    Text(
                        text = "¥${LifeWidgetFormat.yuanAmount(snapshot.ticketYuan)}",
                        style = TextStyle(
                            fontSize = bigFontSp.sp,
                            fontWeight = FontWeight.Bold,
                            color = GlanceTheme.colors.onSurface,
                        ),
                        maxLines = 1,
                    )
                }
            }

            WidgetGap(narrow, wideDp = 4)
            if (snapshot.ticketYuan != null && snapshot.loggedIn) {
                Text(
                    text = WaterWidgetFormat.qiekjSubLine(snapshot.points),
                    style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                    maxLines = 1,
                )
            }

            // 末段：窄档与上面两处等分；宽档把按钮推到卡片底部
            Spacer(GlanceModifier.defaultWeight())
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Horizontal.End,
            ) {
                // 点击分区（合并卡 PowerLine 同款手法）：胶囊自己可点、盖住整卡那层，
                // 点击 = water_start 路由进页并**自动开水**；胶囊外圈垫出更大的触控区
                Box(
                    modifier = GlanceModifier
                        .clickable(actionStartActivityIntent(widgetIntent(context, ROUTE_WATER_START)))
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    WidgetPill("去开水", widgetAccentColor(palette))
                }
            }
        }
    }
}
