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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import edu.jxslu.schedule.ROUTE_POWER_BILL
import edu.jxslu.schedule.domain.BalanceAlert

/**
 * 寝室电费小组件（DESIGN §3.6 三条目改版，2026-09-27）。
 *
 * **单条目 + `SizeMode.Exact`**，机制与课表小组件同一套（`ScheduleWidget` 类 KDoc 的
 * 两条 Glance 硬约束原样适用）。
 *
 * **渲染路径零网络（红线，DESIGN §3.13）**：数据 = Room `power_readings` 最新一条的
 * 本地镜像（[LifeWidgetSync.refreshPowerWidgets]，读数唯一写入处仍是
 * `PowerRepository.snapshot()`），由生活页刷新 / 充值 / 余额提醒日检等 App 内动作
 * 成功后顺手推送，外加 15 分钟 tick 的本地镜像兜底。小组件自身**绝不**向缴费平台发请求
 * ——读数密度 = 打开 App 的密度，小组件不许变相轮询第三方平台。
 *
 * 两态：
 * - 有读数：剩余电量（度）大字 + 折合金额（走 `BalanceAlert.remainingYuan` 唯一换算处）
 *   + 房号 + 更新时刻，点击 → 缴费账单·用电统计（`SubpageScreen.POWER_BILL`）；
 * - 无读数：引导文案，点击仍是用电统计页（页内自会引导开凭证）。
 */
class PowerWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 首帧：读 Room 最新读数写进实例状态并捕获进组合（会话状态先于 provideGlance
        // 读取，这里写入的值首帧读不到——课表小组件类 KDoc 第 1 条）
        val initial = PowerWidgetSnapshotStore.computeAndStore(context, id)

        provideContent {
            val snapshot = PowerWidgetSnapshotStore.read() ?: initial
            PowerCardContent(snapshot)
        }
    }
}

class PowerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PowerWidget()
    // 周期刷新复用课表小组件那条 15 分钟 Worker（WidgetRefreshWorker 里的本地镜像兜底）；
    // 数据更新由各推送点实时推，这里不需要自排调度。
}

private val PowerSnapshotKey = stringPreferencesKey("power_widget_snapshot")

internal object PowerWidgetSnapshotStore {

    @Composable
    fun read(): PowerWidgetSnapshot? = PowerWidgetSnapshotCodec.decode(currentState(PowerSnapshotKey))

    internal suspend fun computeAndStore(context: Context, id: GlanceId): PowerWidgetSnapshot {
        val snapshot = LifeWidgetSync.powerSnapshot(context)
        write(context, id, snapshot)
        return snapshot
    }

    /** 写状态 + `update()` 两步，缺一不可（课表小组件类 KDoc 第 2 条）。 */
    internal suspend fun refreshAll(context: Context, snapshot: PowerWidgetSnapshot) {
        val manager = GlanceAppWidgetManager(context)
        val widget = PowerWidget()
        manager.getGlanceIds(PowerWidget::class.java).forEach { id ->
            runCatching {
                write(context, id, snapshot)
                widget.update(context, id)
            }
        }
    }

    private suspend fun write(context: Context, id: GlanceId, snapshot: PowerWidgetSnapshot) {
        updateAppWidgetState(context, id) { prefs ->
            prefs[PowerSnapshotKey] = PowerWidgetSnapshotCodec.encode(snapshot)
        }
    }
}

@Composable
private fun PowerCardContent(snapshot: PowerWidgetSnapshot) {
    val context = LocalContext.current
    val bigFontSp = bigNumberFontSp(LocalSize.current.width.value)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .clickable(actionStartActivityIntent(widgetIntent(context, ROUTE_POWER_BILL))),
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            WidgetHeaderRow(
                title = if (snapshot.roomId.isBlank()) "寝室电费" else "寝室电费 · ${snapshot.roomId}",
                updatedLabel = LifeWidgetFormat.timeLabel(snapshot.fetchedAtMs),
            )

            val remain = snapshot.remainKwh
            if (remain == null) {
                Spacer(GlanceModifier.height(8.dp))
                Text(
                    text = "还没有读数",
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(3.dp))
                Text(
                    text = "打开 App「生活」页刷新一次后，这里显示剩余电量与折合金额",
                    style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                    maxLines = 2,
                )
            } else {
                Spacer(GlanceModifier.height(6.dp))
                Row(verticalAlignment = Alignment.Vertical.Bottom) {
                    Text(
                        text = LifeWidgetFormat.kwh(remain),
                        style = TextStyle(
                            fontSize = bigFontSp.sp,
                            fontWeight = FontWeight.Bold,
                            color = GlanceTheme.colors.onSurface,
                        ),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.width(3.dp))
                    Text(
                        text = "度",
                        style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
                        maxLines = 1,
                    )
                }
                Spacer(GlanceModifier.defaultWeight())
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    // 折算只走 BalanceAlert.remainingYuan（全 App 唯一换算处，DESIGN §3.13），
                    // 单价缺失就整行省略——不按默认单价编
                    val price = snapshot.priceYuan
                    val yuan = BalanceAlert.remainingYuan(remain, price)
                    if (yuan != null && price != null) {
                        Text(
                            text = "折合 ¥${LifeWidgetFormat.yuanAmount(yuan)}" +
                                "（${LifeWidgetFormat.price(price)} 元/度）",
                            style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                            maxLines = 1,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        Spacer(GlanceModifier.width(6.dp))
                    } else {
                        Spacer(GlanceModifier.defaultWeight())
                    }
                    WidgetPill("用电统计")
                }
            }
        }
    }
}
