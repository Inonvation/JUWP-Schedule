package edu.jxslu.schedule.ui.widget

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.GlanceAppWidgetManager
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.qiekj.BalanceData
import edu.jxslu.schedule.data.qzxy.QzxyBalance
import edu.jxslu.schedule.domain.QzxyWatering
import kotlinx.coroutines.flow.first

/**
 * 开水两卡小组件（胖乖开水 + 趣智开水）的数据同步（DESIGN §3.6「开水两卡」）。
 *
 * 与生活合并卡（[LifeWidgetSync]）同一套机制：
 *
 * - **权威快照在 DataStore**（`water_widget_prefs`），Glance 状态只是渲染镜像——新添加
 *   的实例首帧就能显示上次已知值；登录态不落盘，每次构建快照时现算（token / 会话是
 *   唯一真相源）；
 * - **渲染路径零网络**：取数只发生在 App 内成功取到数据的顺手推送（开水页 / 今日页
 *   卡片的余额刷新、趣智的开阀 / 结算 / 账户刷新）与后台 2 小时闸门（[CampusBalanceGate]，
 *   冷启动 + 15 分钟 tick）；
 * - **失败不重试、不落时刻**（与校园卡余额同口径，防撞第三方风控）；
 * - **自动开水的动作全在页面 VM**（见 `WaterViewModel.requestAutoUnlock` /
 *   `QzxyViewModel.requestAutoOpen`，`WaterAutoStart` 令牌武装）：胖乖是多步下单链、
 *   趣智要现场连蓝牙读设备状态，编排层不做任何操作入口，只镜像与启动。
 *
 * Glance 两条硬约束与课表小组件同一套（首帧快照捕获进组合；后台「写状态 + `update()`」
 * 两步），都在各 widget 文件的 `*SnapshotStore` 里，本类只编排。
 */
internal object WaterWidgetSync {

    private val Context.waterWidgetPrefs by preferencesDataStore(name = "water_widget_prefs")

    private val QiekjTicketYuanKey = doublePreferencesKey("qiekj_ticket_yuan")
    private val QiekjPointsKey = stringPreferencesKey("qiekj_points")
    private val QiekjFetchedAtMsKey = longPreferencesKey("qiekj_fetched_at_ms")
    private val QzxyBalanceTextKey = stringPreferencesKey("qzxy_balance_text")
    private val QzxyFetchedAtMsKey = longPreferencesKey("qzxy_fetched_at_ms")

    // -1 / 空串在 DataStore 里表示「从未取到 / 未知」，读出来转成 null（快照口径见
    // WaterWidgetModels；与 LifeWidgetSync 的 UNKNOWN_FEN 同一手法）
    private const val UNKNOWN = -1.0

    // ------------------------------------------------------------------
    // 快照构建（渲染首帧、推送共用；全程零网络）
    // ------------------------------------------------------------------

    suspend fun qiekjSnapshot(context: Context): QiekjWaterSnapshot {
        val appContext = context.applicationContext
        val prefs = appContext.waterWidgetPrefs.data.first()
        val loggedIn = runCatching { Graph.qiekj(appContext).loggedIn.value }.getOrDefault(false)
        return QiekjWaterSnapshot(
            loggedIn = loggedIn,
            ticketYuan = prefs[QiekjTicketYuanKey]?.takeIf { it >= 0 },
            points = prefs[QiekjPointsKey]?.takeIf { it.isNotEmpty() },
            fetchedAtMs = prefs[QiekjFetchedAtMsKey] ?: 0L,
        )
    }

    /** 趣智快照：DataStore 余额 + 本地用水记账 + 上次设备（零网络）。 */
    suspend fun qzxySnapshot(context: Context): QzxyWaterSnapshot {
        val appContext = context.applicationContext
        val prefs = appContext.waterWidgetPrefs.data.first()
        val loggedIn = runCatching { Graph.qzxy(appContext).localSession() != null }.getOrDefault(false)
        // 用水中的过期判定与趣智页 applyWatering 同一条规则（1 小时，QzxyWatering.EXPIRE_MILLIS）：
        // 过期的记账是残留，不上桌面；页面会在下次打开时清掉并提示
        val watering = runCatching { Graph.qzxyWatering(appContext).watering.value }
            .getOrNull()
            ?.takeIf { !it.isExpired(System.currentTimeMillis()) }
        val lastUsed = runCatching { Graph.qzxyDevices(appContext).lastUsed() }.getOrNull()
        return QzxyWaterSnapshot(
            loggedIn = loggedIn,
            balanceText = prefs[QzxyBalanceTextKey]?.takeIf { it.isNotEmpty() },
            fetchedAtMs = prefs[QzxyFetchedAtMsKey] ?: 0L,
            wateringStartedAtMs = watering?.startedAtMillis,
            wateringDeviceName = watering?.deviceName,
            lastDeviceName = lastUsed?.name?.takeIf { it.isNotBlank() },
        )
    }

    // ------------------------------------------------------------------
    // 推送点（App 内成功取数的地方顺手调，见类 KDoc）
    // ------------------------------------------------------------------

    /** 开水页 / 今日页开水卡取到余额时调用（数据在手上，零额外请求）。 */
    suspend fun pushQiekjBalance(context: Context, balance: BalanceData) {
        val appContext = context.applicationContext
        appContext.waterWidgetPrefs.edit { prefs ->
            prefs[QiekjTicketYuanKey] = WaterWidgetFormat.qiekjTicketYuan(balance.tokenCoin) ?: UNKNOWN
            prefs[QiekjPointsKey] = balance.integral?.trim().orEmpty()
            prefs[QiekjFetchedAtMsKey] = System.currentTimeMillis()
        }
        runCatching { refreshQiekjWidget(appContext) }
    }

    /** 趣智页 / 今日页卡片刷新账户取到余额时调用。 */
    suspend fun pushQzxyAccount(context: Context, balance: QzxyBalance) {
        val appContext = context.applicationContext
        appContext.waterWidgetPrefs.edit { prefs ->
            prefs[QzxyBalanceTextKey] = WaterWidgetFormat.qzxyBalanceText(balance.text).orEmpty()
            prefs[QzxyFetchedAtMsKey] = System.currentTimeMillis()
        }
        runCatching { refreshQzxyWidget(appContext) }
    }

    /** 本地登录态翻转（登录 / 退出 / 会话失效）后重渲染，桌面立即跟着切换形态。零网络。 */
    suspend fun refreshQiekjWidget(context: Context) {
        QiekjWaterSnapshotStore.refreshAll(context, qiekjSnapshot(context))
    }

    /** 本地用水状态翻转（开阀 / 结算 / 手动标记）后重渲染。零网络。 */
    suspend fun refreshQzxyWidget(context: Context) {
        QzxyWaterSnapshotStore.refreshAll(context, qzxySnapshot(context))
    }

    // ------------------------------------------------------------------
    // 后台触发点（冷启动 / 15 分钟 tick；内部各自吞错）
    // ------------------------------------------------------------------

    suspend fun onColdStart(context: Context) {
        periodic(context)
    }

    /** 15 分钟兜底 tick：与冷启动同一套（本地镜像 + 两路闸门取数）。 */
    suspend fun onPeriodicTick(context: Context) {
        periodic(context)
    }

    private suspend fun periodic(context: Context) {
        val appContext = context.applicationContext
        runCatching { refreshQiekjWidget(appContext) }
        runCatching { refreshQzxyWidget(appContext) }
        runCatching { maybeFetchQiekjBalance(appContext) }
        runCatching { maybeFetchQzxyBalance(appContext) }
    }

    /**
     * 胖乖余额的后台取数：实例 + 登录 + 2 小时闸门三重门。**失败不重试、不落时刻**。
     * 返回是否真的取到了。
     */
    suspend fun maybeFetchQiekjBalance(context: Context): Boolean {
        val appContext = context.applicationContext
        val hasInstance = hasInstance<QiekjWaterWidget>(appContext)
        if (!hasInstance) return false
        val repo = Graph.qiekj(appContext)
        if (!repo.loggedIn.value) return false
        val lastSuccessMs = runCatching { qiekjSnapshot(appContext).fetchedAtMs }
            .getOrNull()?.takeIf { it > 0 }
        if (!CampusBalanceGate.shouldFetch(lastSuccessMs, System.currentTimeMillis())) return false
        return runCatching {
            val balance = repo.queryBalance()
            pushQiekjBalance(appContext, balance)
            true
        }.getOrDefault(false)
    }

    /** 趣智余额的后台取数：实例 + 会话 + 2 小时闸门。会话过期只当失败，**不代用户登出**。 */
    suspend fun maybeFetchQzxyBalance(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!hasInstance<QzxyWaterWidget>(appContext)) return false
        val repo = Graph.qzxy(appContext)
        if (runCatching { repo.localSession() }.getOrNull() == null) return false
        val lastSuccessMs = runCatching { qzxySnapshot(appContext).fetchedAtMs }
            .getOrNull()?.takeIf { it > 0 }
        if (!CampusBalanceGate.shouldFetch(lastSuccessMs, System.currentTimeMillis())) return false
        return runCatching {
            val balance = repo.balance()
            pushQzxyAccount(appContext, balance)
            true
        }.getOrDefault(false)
    }

    private suspend inline fun <reified T : androidx.glance.appwidget.GlanceAppWidget> hasInstance(
        context: Context,
    ): Boolean = runCatching {
        GlanceAppWidgetManager(context).getGlanceIds(T::class.java).isNotEmpty()
    }.getOrDefault(false)
}
