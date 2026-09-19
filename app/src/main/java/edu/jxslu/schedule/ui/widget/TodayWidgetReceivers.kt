package edu.jxslu.schedule.ui.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 三个桌面条目（2×2 / 4×2 / 4×4）的 receiver（DESIGN §3.6）。
 *
 * 三条目共用同一个 [TodayWidget]（渲染按实际尺寸分档），但必须**各占一个 receiver**：
 * 桌面小组件选择器是按 `AppWidgetProviderInfo`（即 receiver + 元数据 XML）列条目的，
 * 一个 provider 只能有一个默认尺寸。三个 receiver 的差别只有：
 * - `res/xml/widget_info_*.xml` 里的默认格位与最小尺寸；
 * - `android:label`（选择器里显示「水贝贝 · 2×2」等）。
 *
 * 因此三条目各有**独立的 widget 子类**（[TodayWidgetSmall] 等）与 receiver：
 * Glance 按 widget 类管理实例（`getGlanceIds`），共享同一个实例会让三个条目的
 * 状态互相覆盖。子类不做任何事，逻辑全在 [TodayWidget] 里——不要往这里加分支。
 *
 * 历史说明：中间条目 2026-09-19 由 2×4 竖条改为 4×2 横条（用户拍板），receiver 类随之
 * 更名 Tall→Wide——组件名变化会让更新前放在桌面上的 2×4 条目失效，需要重新添加。
 */
open class TodayWidgetSmall : TodayWidget()
open class TodayWidgetWide : TodayWidget()
open class TodayWidgetLarge : TodayWidget()

/** 三个 receiver 的公共行为：首实例落桌面时接上兜底刷新（DESIGN §3.6 刷新策略）。 */
abstract class TodayWidgetReceiverBase : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        TodayWidgetRefresh.ensurePeriodicWork(context)
        TodayWidgetRefresh.enqueueRefresh(context)
    }
}

class TodayWidgetReceiverSmall : TodayWidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidgetSmall()
}

class TodayWidgetReceiverWide : TodayWidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidgetWide()
}

class TodayWidgetReceiverLarge : TodayWidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidgetLarge()
}

/** 三个条目的 widget 类集合：刷新按类逐个查询实例，避免各处手抄类型列表。 */
internal object TodayWidgetVariants {
    val all = listOf(
        TodayWidgetSmall::class.java,
        TodayWidgetWide::class.java,
        TodayWidgetLarge::class.java,
    )

    /**
     * 每类一个无状态实例，供刷新侧调 `GlanceAppWidget.update()`——
     * 那是实例方法，光有 receiver 不够。子类无状态，构造即安全。
     */
    val widgets: Map<Class<out TodayWidget>, TodayWidget> = all.associateWith {
        it.getDeclaredConstructor().newInstance() as TodayWidget
    }
}
