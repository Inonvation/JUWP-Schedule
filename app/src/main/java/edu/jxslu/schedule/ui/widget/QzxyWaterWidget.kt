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
import edu.jxslu.schedule.ROUTE_QZXY
import edu.jxslu.schedule.ROUTE_QZXY_START
import edu.jxslu.schedule.domain.ThemePalette
import kotlinx.coroutines.flow.first

/**
 * 趣智开水小组件（DESIGN §3.6「开水两卡」，2026-09-28）。
 *
 * **固定 2×2**：provider XML `resizeMode="none"`，与胖乖开水卡同批、同口径。单条目 +
 * `SizeMode.Exact` 机制不变（[ScheduleWidget] 类 KDoc 的两条 Glance 硬约束这里原样适用：
 * 首帧快照捕获进组合；后台「写状态 + `update()`」两步）。
 *
 * 形态：头部 + 主区 + 副行 + 沉底胶囊「去开水」。主区按状态切换——
 * - 用水中（本地记账未过期）：大字「用水中」+ 副行设备名与已用时长，让桌面直接回答
 *   「水关了没」；开阀 / 结算的顺手推送会让它即时切换（[WaterWidgetSync]）；
 * - 空闲且已登录：余额大字 + 上次设备副行；
 * - 未登录 / 从未取到：引导文案。
 *
 * 整卡点击 → 趣智校园页；胶囊「去开水」→ 趣智页并**自动开阀**（`ROUTE_QZXY_START` +
 * 一次性令牌 `WaterAutoStart`，设备口径与今日页面板一致：上次那台 → 唯一绑定那台）。
 * **自动开阀只发生在页面 VM**：趣智开阀要先连蓝牙读设备状态再下单写指令，直达也只是
 * 把「选设备 + 点开阀」两步并成一步，仍发生在页面里、仍要蓝牙权限与设备在场。数据链路
 * 与胖乖卡同构（DataStore 权威快照 + 渲染零网络 + 2 小时闸门，见 [WaterWidgetSync]）；
 * 用水中是本地记账镜像，过期（1 小时）不上桌面。
 */
class QzxyWaterWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 首帧：读权威快照写进实例状态并捕获进组合（会话的状态先于 provideGlance 读取，
        // 这里写入的值首帧读不到——与课表小组件同一坑，见其类 KDoc 第 1 条）
        val initial = QzxyWaterSnapshotStore.computeAndStore(context, id)
        // 强调色跟随所选主题配色（DESIGN §3.3）：纯本地偏好读取，零网络
        val palette = Graph.displayPrefs(context).themePalette.first()

        provideContent {
            // currentState 只承接后续 update() 推来的新状态；解码失败退回首帧快照
            val snapshot = QzxyWaterSnapshotStore.read() ?: initial
            QzxyWaterCardContent(snapshot, palette)
        }
    }
}

class QzxyWaterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QzxyWaterWidget()
    // 周期刷新复用课表小组件那条 15 分钟 Worker（JuwApplication 冷启动必排，见
    // WidgetRefreshWorker），这里不需要 onEnabled 自排——按内容拆的条目不各自养调度。
}

/** 快照在 Glance 状态里的键与读写；编排（何时推送 / 闸门取数）在 [WaterWidgetSync]。 */
private val QzxyWaterSnapshotKey = stringPreferencesKey("qzxy_water_snapshot")

internal object QzxyWaterSnapshotStore {

    @Composable
    fun read(): QzxyWaterSnapshot? =
        QzxyWaterSnapshotCodec.decode(currentState(QzxyWaterSnapshotKey))

    /** provideGlance 首帧用：算快照、写状态、原样返回给组合捕获（返回值不可省，理由见上）。 */
    internal suspend fun computeAndStore(context: Context, id: GlanceId): QzxyWaterSnapshot {
        val snapshot = WaterWidgetSync.qzxySnapshot(context)
        write(context, id, snapshot)
        return snapshot
    }

    /** 刷新全部已添加实例：写状态 + `update()` 两步，缺一不可（课表小组件类 KDoc 第 2 条）。 */
    internal suspend fun refreshAll(context: Context, snapshot: QzxyWaterSnapshot) {
        val manager = GlanceAppWidgetManager(context)
        val widget = QzxyWaterWidget()
        manager.getGlanceIds(QzxyWaterWidget::class.java).forEach { id ->
            runCatching {
                write(context, id, snapshot)
                widget.update(context, id)
            }
        }
    }

    private suspend fun write(context: Context, id: GlanceId, snapshot: QzxyWaterSnapshot) {
        updateAppWidgetState(context, id) { prefs ->
            prefs[QzxyWaterSnapshotKey] = QzxyWaterSnapshotCodec.encode(snapshot)
        }
    }
}

@Composable
private fun QzxyWaterCardContent(snapshot: QzxyWaterSnapshot, palette: ThemePalette) {
    val context = LocalContext.current
    val narrow = isNarrowWidth(LocalSize.current.width.value)
    val bigFontSp = bigNumberFontSp(LocalSize.current.width.value)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .clickable(actionStartActivityIntent(widgetIntent(context, ROUTE_QZXY))),
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            WidgetHeaderRow("趣智开水", LifeWidgetFormat.timeLabel(snapshot.fetchedAtMs))

            // 窄档：三处间隔都是弹性，等分剩余高度 → 内容铺满卡片（见 WidgetGap）
            WidgetGap(narrow, wideDp = 6)
            val watering = snapshot.wateringStartedAtMs
            when {
                !snapshot.loggedIn -> {
                    Text(
                        text = "未登录趣智校园",
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.height(3.dp))
                    Text(
                        text = "点按前往趣智页登录，登录后这里显示余额与用水状态",
                        style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                        maxLines = 2,
                    )
                }

                watering != null -> {
                    // 用水中用强调色大字：这张卡此刻要回答的是「水关了没」，不是余额
                    Text(
                        text = "用水中",
                        style = TextStyle(
                            fontSize = bigFontSp.sp,
                            fontWeight = FontWeight.Bold,
                            color = widgetAccentColor(palette),
                        ),
                        maxLines = 1,
                    )
                }

                snapshot.balanceText != null -> {
                    Text(
                        text = "¥${snapshot.balanceText}",
                        style = TextStyle(
                            fontSize = bigFontSp.sp,
                            fontWeight = FontWeight.Bold,
                            color = GlanceTheme.colors.onSurface,
                        ),
                        maxLines = 1,
                    )
                }

                else -> {
                    Text(
                        text = "余额待更新",
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.height(3.dp))
                    Text(
                        text = "点按进趣智页，刷新账户后这里会同步",
                        style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                        maxLines = 2,
                    )
                }
            }

            WidgetGap(narrow, wideDp = 4)
            val subLine = when {
                watering != null -> WaterWidgetFormat.qzxyWateringLine(
                    deviceName = snapshot.wateringDeviceName,
                    startedAtMs = watering,
                    nowMs = System.currentTimeMillis(),
                )
                snapshot.loggedIn && snapshot.balanceText != null -> snapshot.lastDeviceName
                else -> null
            }
            if (subLine != null) {
                Text(
                    text = subLine,
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
                // 点击 = qzxy_start 路由进页并**自动开阀**；胶囊外圈垫出更大的触控区
                Box(
                    modifier = GlanceModifier
                        .clickable(actionStartActivityIntent(widgetIntent(context, ROUTE_QZXY_START)))
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    WidgetPill("去开水", widgetAccentColor(palette))
                }
            }
        }
    }
}
