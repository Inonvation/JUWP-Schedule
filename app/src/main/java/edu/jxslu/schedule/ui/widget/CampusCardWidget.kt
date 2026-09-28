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
import edu.jxslu.schedule.ROUTE_CAMPUS_CARD
import edu.jxslu.schedule.ROUTE_PAY_CODE
import edu.jxslu.schedule.ROUTE_POWER_BILL
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.ThemePalette
import kotlinx.coroutines.flow.first

/**
 * 生活小组件（校园卡余额 + 寝室电费，DESIGN §3.6 二条目改版，2026-09-27）。
 *
 * **单条目 + `SizeMode.Exact`**，与课表小组件同一套机制（`ScheduleWidget` 类 KDoc 的
 * 两条 Glance 硬约束这里原样适用：首帧快照捕获进组合；后台「写状态 + `update()`」两步）。
 *
 * 2026-09-27 当天先从一条拆成三条、再合并回一条：拆开后 2 格宽（真机实测 150dp）的卡片
 * 只放得下一块信息，桌面看着空。现在的形态是**一张卡两行信息**——余额大字（主区）+
 * 电费小字（副行），点击分区：整卡 → 付款码页（无凭证 → 校园卡设置页），
 * 电费副行 → 用电统计页（DESIGN §3.6「合并卡」）。
 *
 * 数据：DataStore 权威快照（[LifeWidgetSync]），**渲染路径零网络**——余额由 App 内取数
 * 链路顺手推送（付款码页 / 生活页 / 余额提醒日检）或 15 分钟 tick 过 2 小时闸门后取。
 * 电费只镜像 Room `power_readings` 最新一条，读数密度仍是「打开 App 的密度」。
 * 码不预取：小组件只是启动器，出码、`FLAG_SECURE`、亮度、扫码自动退出全部仍只在
 * 付款码页（DESIGN §3.10「只有一处出码」）。
 *
 * 主区三态（设计稿见 docs/design/widget-settings-mockup.html）：
 * - 凭证已开 + 有快照：正式卡余额大字 + 更新时刻，点击 → 付款码页；
 * - 凭证已开 + 从未取到：「余额待更新」，点击 → 付款码页；
 * - 未开凭证：引导文案，点击 → 校园卡设置页（「一关了之」口径：凭证清掉即回落此态）。
 *
 * 电子账户余额不再上卡片（2026-09-27 合并时去掉）：副行让给电费，账户余额在付款码页
 * 与生活页都能看。
 */
class CampusCardWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 首帧：读权威快照写进实例状态并捕获进组合（会话的状态先于 provideGlance 读取，
        // 这里写入的值首帧读不到——与课表小组件同一坑，见其类 KDoc 第 1 条）
        val initial = LifeCardSnapshotStore.computeAndStore(context, id)
        // 胶囊色跟随所选主题配色（DESIGN §3.3）：纯本地偏好读取，零网络；
        // provideGlance 在每次渲染（首帧 + update 重跑）时现读，改完配色重渲染即跟上
        val palette = Graph.displayPrefs(context).themePalette.first()

        provideContent {
            // currentState 只承接后续 update() 推来的新状态；解码失败退回首帧快照
            val snapshot = LifeCardSnapshotStore.read() ?: initial
            CampusCardContent(snapshot, palette)
        }
    }
}

class CampusCardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CampusCardWidget()
    // 周期刷新复用课表小组件那条 15 分钟 Worker（JuwApplication 冷启动必排，见
    // WidgetRefreshWorker），这里不需要 onEnabled 自排——按内容拆的条目不各自养调度。
}

/** 快照在 Glance 状态里的键与读写；编排（何时刷新、取数闸门）在 [LifeWidgetSync]。 */
private val LifeCardSnapshotKey = stringPreferencesKey("life_card_snapshot")

internal object LifeCardSnapshotStore {

    @Composable
    fun read(): LifeCardSnapshot? = LifeCardSnapshotCodec.decode(currentState(LifeCardSnapshotKey))

    /** provideGlance 首帧用：算快照、写状态、原样返回给组合捕获（返回值不可省，理由见上）。 */
    internal suspend fun computeAndStore(context: Context, id: GlanceId): LifeCardSnapshot {
        val snapshot = LifeWidgetSync.lifeCardSnapshot(context)
        write(context, id, snapshot)
        return snapshot
    }

    /** 刷新全部已添加实例：写状态 + `update()` 两步，缺一不可（课表小组件类 KDoc 第 2 条）。 */
    internal suspend fun refreshAll(context: Context, snapshot: LifeCardSnapshot) {
        val manager = GlanceAppWidgetManager(context)
        val widget = CampusCardWidget()
        manager.getGlanceIds(CampusCardWidget::class.java).forEach { id ->
            runCatching {
                write(context, id, snapshot)
                widget.update(context, id)
            }
        }
    }

    private suspend fun write(context: Context, id: GlanceId, snapshot: LifeCardSnapshot) {
        updateAppWidgetState(context, id) { prefs ->
            prefs[LifeCardSnapshotKey] = LifeCardSnapshotCodec.encode(snapshot)
        }
    }
}

@Composable
private fun CampusCardContent(snapshot: LifeCardSnapshot, palette: ThemePalette) {
    val context = LocalContext.current
    val campus = snapshot.campus
    // 点击落点跟着凭证态走：没凭证时进设置页开启，而不是把用户丢进付款码页报错
    // （电费副行另有落点：用电统计页，见 PowerLine）
    val route = if (campus.hasCredentials) ROUTE_PAY_CODE else ROUTE_CAMPUS_CARD
    val widthDp = LocalSize.current.width.value
    // 窄档（2 格宽，实测约 150dp）：两块弹性占位把内容夹在中间，见 WidgetNarrowWidthDp
    val narrow = isNarrowWidth(widthDp)
    val bigFontSp = bigNumberFontSp(widthDp)

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
            WidgetHeaderRow("校园卡", LifeWidgetFormat.timeLabel(campus.fetchedAtMs))

            // 窄档：这三处间隔都是弹性，等分剩余高度 → 内容铺满卡片（见 WidgetGap）
            WidgetGap(narrow, wideDp = 6)
            when {
                !campus.hasCredentials -> {
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
                }

                campus.cardFen < 0 -> {
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
                }

                else -> {
                    Text(
                        text = if (campus.hideBalance) "¥ ••••" else "¥${LifeWidgetFormat.yuan(campus.cardFen)}",
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
            PowerLine(snapshot.power, narrow, context)

            // 末段：窄档与上面两处等分；宽档把按钮推到卡片底部
            Spacer(GlanceModifier.defaultWeight())
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Horizontal.End,
            ) {
                WidgetPill(if (campus.hasCredentials) "出示付款码" else "去设置", widgetAccentColor(palette))
            }
        }
    }
}

/**
 * 电费副行：一行小字 + 独立点击落点（用电统计页），DESIGN §3.6「合并卡」。
 *
 * 点击分区靠 Glance 的 PendingIntent 覆盖：子元素的 clickable 会盖住整卡那层
 * （课表小组件的周网格区用同一手法）。外层套 Box 是为了点击高度——11sp 一行文字
 * 本身只有约 15dp，手指点不准，上下各垫 4dp 到 23dp。
 */
@Composable
private fun PowerLine(power: PowerWidgetSnapshot, narrow: Boolean, context: Context) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(actionStartActivityIntent(widgetIntent(context, ROUTE_POWER_BILL)))
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = LifeWidgetFormat.powerLineText(power, narrow),
            style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
            maxLines = 1,
        )
    }
}
