package edu.jxslu.schedule.ui.widget

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.GlanceAppWidgetManager
import edu.jxslu.schedule.Graph
import kotlinx.coroutines.flow.first

/**
 * 生活小组件（校园卡 + 寝室电费，合并成一张卡）的数据同步（DESIGN §3.6 二条目改版，
 * 2026-09-27）。
 *
 * 两条红线（改前先读，合并后各自照旧）：
 *
 * 1. **电费那一行的任何刷新路径都不发网络请求**。数据 = Room `power_readings` 最新一条
 *    （`PowerRepository.snapshot()` 仍是读数唯一写入处），本类只做「读最新 → 写 Glance 状态
 *    → `update()`」的本地镜像。读数密度 = 打开 App 的密度（DESIGN §3.13），小组件不许
 *    变相轮询第三方平台；
 * 2. **校园卡余额取数有 2 小时闸门**（[CampusBalanceGate]）+ 失败不重试、不落时刻
 *    （与余额提醒同口径）；验证码接口（8002/8003）在这条链路上绝不触碰。取数只发生在：
 *    App 内成功取到余额的顺手推送（零额外请求）、15 分钟兜底 tick 过闸门、冷启动过闸门。
 *
 * Glance 两条硬约束与课表小组件同一套（见 [ScheduleWidget] 类 KDoc）：首帧快照由
 * `provideGlance` 直接捕获进组合；后台刷新必须「写状态 + `update()`」两步——
 * 两步都在各 widget 文件的 `*SnapshotStore` 里，本类只编排。
 *
 * 快照的**权威数据源是 DataStore**（本类持有）：新添加的实例首帧就能显示上次已知值；
 * 各实例的 Glance 状态只是渲染镜像。
 */
internal object LifeWidgetSync {

    private val Context.lifeWidgetPrefs by preferencesDataStore(name = "life_widget_prefs")

    private val CampusCardFenKey = longPreferencesKey("campus_card_fen")
    private val CampusFetchedAtMsKey = longPreferencesKey("campus_fetched_at_ms")
    private val CampusHideBalanceKey = booleanPreferencesKey("campus_hide_balance")

    // -1 在 DataStore 里表示「从未取到 / 未知」，读出来转成 null（快照口径见 LifeWidgetModels）
    private const val UNKNOWN_FEN = -1L

    // ------------------------------------------------------------------
    // 快照读取（渲染首帧、设置页开关共用）
    // ------------------------------------------------------------------

    suspend fun campusSnapshot(context: Context): CampusCardSnapshot {
        val appContext = context.applicationContext
        val prefs = appContext.lifeWidgetPrefs.data.first()
        return CampusCardSnapshot(
            cardFen = prefs[CampusCardFenKey] ?: UNKNOWN_FEN,
            fetchedAtMs = prefs[CampusFetchedAtMsKey] ?: 0L,
            hasCredentials = runCatching { Graph.yktCredentialStore(appContext).read() != null }
                .getOrDefault(false),
            hideBalance = prefs[CampusHideBalanceKey] ?: false,
        )
    }

    /** 合并快照：DataStore 里的校园卡数据 + Room 最新读数（全程零网络）。 */
    suspend fun lifeCardSnapshot(context: Context): LifeCardSnapshot =
        LifeCardSnapshot(campus = campusSnapshot(context), power = powerSnapshot(context))

    // ------------------------------------------------------------------
    // 推送点（全部搭现有链路的便车，见类 KDoc）
    // ------------------------------------------------------------------

    /**
     * App 内成功取到一卡通余额时调用（付款码页 / 生活页 / 余额提醒日检）。
     *
     * 写完顺手刷新全部合并卡实例；无实例时 update 是空跑。
     */
    suspend fun pushCampusBalance(context: Context, cardFen: Long) {
        val appContext = context.applicationContext
        appContext.lifeWidgetPrefs.edit { prefs ->
            prefs[CampusCardFenKey] = cardFen
            prefs[CampusFetchedAtMsKey] = System.currentTimeMillis()
        }
        runCatching { refreshLifeWidgets(appContext) }
    }

    /** 「在小组件中隐藏余额」开关（设置页）。写完立即重渲染，桌面马上变样。 */
    suspend fun setCampusHideBalance(context: Context, hide: Boolean) {
        context.applicationContext.lifeWidgetPrefs.edit { prefs ->
            prefs[CampusHideBalanceKey] = hide
        }
        runCatching { refreshLifeWidgets(context.applicationContext) }
    }

    /**
     * 主题配色切换（通用设置，DESIGN §3.3）：胶囊色跟随所选配色，切换后立即重渲染
     * （零网络重跑同一份快照；配色偏好先落盘，`provideGlance` 重跑时读到新值）。
     */
    suspend fun onThemePaletteChanged(context: Context) {
        runCatching { refreshLifeWidgets(context.applicationContext) }
    }

    // ------------------------------------------------------------------
    // 刷新（本地数据 → Glance 状态 → update；零网络）
    // ------------------------------------------------------------------

    /** 本地数据 → 合并快照 → Glance 状态 → `update()`。校园卡与电费一起推。 */
    suspend fun refreshLifeWidgets(context: Context) {
        LifeCardSnapshotStore.refreshAll(context, lifeCardSnapshot(context))
    }

    /** Room 最新读数 → 电费快照（本地镜像，零网络；无读数给空快照走引导态）。 */
    suspend fun powerSnapshot(context: Context): PowerWidgetSnapshot {
        val latest = runCatching { Graph.powerReadingStore(context).latest() }.getOrNull()
        return PowerWidgetSnapshot(
            roomId = latest?.roomId.orEmpty(),
            remainKwh = latest?.remainKwh,
            priceYuan = latest?.priceYuan?.takeIf { it > 0 },
            fetchedAtMs = latest?.epochMs ?: 0L,
        )
    }

    // ------------------------------------------------------------------
    // 后台触发点
    // ------------------------------------------------------------------

    /**
     * 冷启动（`JuwApplication`）：电费镜像刷一次（零网络）；校园卡过闸门取数。
     * 两个分支各自吞错——后台协程不许把启动流程打崩。
     */
    suspend fun onColdStart(context: Context) {
        val appContext = context.applicationContext
        runCatching { refreshLifeWidgets(appContext) }
        runCatching { maybeFetchCampusBalance(appContext) }
    }

    /**
     * 15 分钟兜底 tick（`WidgetRefreshWorker`）：电费镜像 + 校园卡闸门取数。
     * 无实例 / 无凭证 / 闸门未到都是廉价空跑。
     */
    suspend fun onPeriodicTick(context: Context) {
        val appContext = context.applicationContext
        runCatching { refreshLifeWidgets(appContext) }
        runCatching { maybeFetchCampusBalance(appContext) }
    }

    /**
     * 校园卡余额的后台取数：2 小时闸门 + 凭证 + 实例三重门。**失败不重试、不落时刻**
     * （闸门时间基点是「上次成功取数」，失败后下个 tick 仍会尝试，但没有重试风暴）。
     * 返回是否真的取到了。
     */
    suspend fun maybeFetchCampusBalance(context: Context): Boolean {
        val appContext = context.applicationContext
        val hasInstance = runCatching {
            GlanceAppWidgetManager(appContext)
                .getGlanceIds(CampusCardWidget::class.java).isNotEmpty()
        }.getOrDefault(false)
        if (!hasInstance) return false
        val credentials = runCatching { Graph.yktCredentialStore(appContext).read() }.getOrNull() ?: return false
        val lastSuccessMs = runCatching { campusSnapshot(appContext).fetchedAtMs }
            .getOrNull()?.takeIf { it > 0 }
        if (!CampusBalanceGate.shouldFetch(lastSuccessMs, System.currentTimeMillis())) return false
        return runCatching {
            val cards = Graph.yktRepository(appContext).cards(credentials.username, credentials.password)
            if (cards.isEmpty()) return@runCatching false
            pushCampusBalance(appContext, cards.sumOf { it.cardBalanceFen })
            true
        }.getOrDefault(false)
    }
}
