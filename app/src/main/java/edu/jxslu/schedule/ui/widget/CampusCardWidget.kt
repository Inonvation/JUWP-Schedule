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
import edu.jxslu.schedule.ROUTE_CAMPUS_CARD
import edu.jxslu.schedule.ROUTE_PAY_CODE

/**
 * 校园卡小组件（DESIGN §3.6 三条目改版，2026-09-27）。
 *
 * **单条目 + `SizeMode.Exact`**，与课表小组件同一套机制（`ScheduleWidget` 类 KDoc 的
 * 两条 Glance 硬约束这里原样适用：首帧快照捕获进组合；后台「写状态 + `update()`」两步）。
 *
 * 数据：DataStore 权威快照（[LifeWidgetSync]），**渲染路径零网络**——余额由 App 内取数
 * 链路顺手推送（付款码页 / 生活页 / 余额提醒日检）或 15 分钟 tick 过 2 小时闸门后取。
 * 码不预取：小组件只是启动器，出码、`FLAG_SECURE`、亮度、扫码自动退出全部仍只在
 * 付款码页（DESIGN §3.10「只有一处出码」）。
 *
 * 三态（设计稿见 docs/design/widget-settings-mockup.html）：
 * - 凭证已开 + 有快照：正式卡余额大字 + 电子账户副行 + 更新时刻，点击 → 付款码页；
 * - 凭证已开 + 从未取到：「余额待更新」，点击 → 付款码页；
 * - 未开凭证：引导文案，点击 → 校园卡设置页（「一关了之」口径：凭证清掉即回落此态）。
 */
class CampusCardWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 首帧：读权威快照写进实例状态并捕获进组合（会话的状态先于 provideGlance 读取，
        // 这里写入的值首帧读不到——与课表小组件同一坑，见其类 KDoc 第 1 条）
        val initial = CampusCardSnapshotStore.computeAndStore(context, id)

        provideContent {
            // currentState 只承接后续 update() 推来的新状态；解码失败退回首帧快照
            val snapshot = CampusCardSnapshotStore.read() ?: initial
            CampusCardContent(snapshot)
        }
    }
}

class CampusCardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CampusCardWidget()
    // 周期刷新复用课表小组件那条 15 分钟 Worker（JuwApplication 冷启动必排，见
    // WidgetRefreshWorker），这里不需要 onEnabled 自排——按内容拆的三条目不各自养调度。
}

/** 快照在 Glance 状态里的键与读写；编排（何时刷新、取数闸门）在 [LifeWidgetSync]。 */
private val CampusSnapshotKey = stringPreferencesKey("campus_card_snapshot")

internal object CampusCardSnapshotStore {

    @Composable
    fun read(): CampusCardSnapshot? = CampusCardSnapshotCodec.decode(currentState(CampusSnapshotKey))

    /** provideGlance 首帧用：算快照、写状态、原样返回给组合捕获（返回值不可省，理由见上）。 */
    internal suspend fun computeAndStore(context: Context, id: GlanceId): CampusCardSnapshot {
        val snapshot = LifeWidgetSync.campusSnapshot(context)
        write(context, id, snapshot)
        return snapshot
    }

    /** 刷新全部已添加实例：写状态 + `update()` 两步，缺一不可（课表小组件类 KDoc 第 2 条）。 */
    internal suspend fun refreshAll(context: Context, snapshot: CampusCardSnapshot) {
        val manager = GlanceAppWidgetManager(context)
        val widget = CampusCardWidget()
        manager.getGlanceIds(CampusCardWidget::class.java).forEach { id ->
            runCatching {
                write(context, id, snapshot)
                widget.update(context, id)
            }
        }
    }

    private suspend fun write(context: Context, id: GlanceId, snapshot: CampusCardSnapshot) {
        updateAppWidgetState(context, id) { prefs ->
            prefs[CampusSnapshotKey] = CampusCardSnapshotCodec.encode(snapshot)
        }
    }
}

@Composable
private fun CampusCardContent(snapshot: CampusCardSnapshot) {
    val context = LocalContext.current
    // 点击落点跟着凭证态走：没凭证时进设置页开启，而不是把用户丢进付款码页报错
    val route = if (snapshot.hasCredentials) ROUTE_PAY_CODE else ROUTE_CAMPUS_CARD
    val bigFontSp = bigNumberFontSp(LocalSize.current.width.value)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .clickable(actionStartActivityIntent(widgetIntent(context, route))),
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            WidgetHeaderRow("校园卡", LifeWidgetFormat.timeLabel(snapshot.fetchedAtMs))

            when {
                !snapshot.hasCredentials -> {
                    Spacer(GlanceModifier.height(8.dp))
                    Text(
                        text = "未开启校园卡凭证",
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.height(3.dp))
                    Text(
                        text = "点按前往「我的 → 校园卡」开启，开启后这里显示余额",
                        style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                        maxLines = 2,
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Horizontal.End,
                    ) { WidgetPill("去设置") }
                }

                snapshot.cardFen < 0 -> {
                    Spacer(GlanceModifier.height(8.dp))
                    Text(
                        text = "余额待更新",
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.height(3.dp))
                    Text(
                        text = "点按出示付款码，成功取到余额后这里会同步",
                        style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                        maxLines = 2,
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Horizontal.End,
                    ) { WidgetPill("出示付款码") }
                }

                else -> {
                    Spacer(GlanceModifier.height(6.dp))
                    Text(
                        text = if (snapshot.hideBalance) "¥ ••••" else "¥${LifeWidgetFormat.yuan(snapshot.cardFen)}",
                        style = TextStyle(
                            fontSize = bigFontSp.sp,
                            fontWeight = FontWeight.Bold,
                            color = GlanceTheme.colors.onSurface,
                        ),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        val accountText = when {
                            snapshot.hideBalance -> "电子账户 ¥ ••••"
                            snapshot.accountFen != null -> "电子账户 ¥${LifeWidgetFormat.yuan(snapshot.accountFen)}"
                            else -> null
                        }
                        if (accountText != null) {
                            Text(
                                text = accountText,
                                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                                maxLines = 1,
                                modifier = GlanceModifier.defaultWeight(),
                            )
                            Spacer(GlanceModifier.width(6.dp))
                        } else {
                            Spacer(GlanceModifier.defaultWeight())
                        }
                        WidgetPill("出示付款码")
                    }
                }
            }
        }
    }
}
