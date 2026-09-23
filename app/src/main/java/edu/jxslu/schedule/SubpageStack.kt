package edu.jxslu.schedule

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * 二级页的 extra 键。**只有这一份字面量**：`SubpageActivity` 解析它，
 * `MainActivity` 的通知跳板（[MainActivity.subpageIntent]）写它。
 */
internal object SubpageExtras {
    const val SCREEN = "screen"
    const val FOCUS_ITEM = "focus_item"
    const val COURSE_NAME = "course_name"
    const val ITEM_ID = "item_id"
}

/**
 * 一个二级页的完整定位参数（DESIGN §3.1）。
 *
 * 存在的理由：二级页是独立窗口，从桌面图标重新进入 App 时系统会把窗口栈 clear 掉
 * （`MainActivity` 是 `singleTask`，见 AndroidManifest），参数只剩在 [Intent] 里。
 * 要把页面原样放回去，就需要一份**可比较、可重建**的结构。
 *
 * 用 data class 而不是直接存 [Intent]：链的去重靠值相等，
 * [Intent.filterEquals] 只比 action/data/type/component，extras 不参与，
 * 全部二级页共用同一个 component，拿它当身份会把不同页判成同一页。
 */
internal data class SubpageRequest(
    val screen: SubpageScreen,
    val focusItemId: String? = null,
    val courseName: String? = null,
    val itemId: Long = 0L,
) {
    fun applyTo(intent: Intent): Intent = intent.apply {
        putExtra(SubpageExtras.SCREEN, screen.name)
        if (focusItemId != null) putExtra(SubpageExtras.FOCUS_ITEM, focusItemId)
        if (courseName != null) putExtra(SubpageExtras.COURSE_NAME, courseName)
        if (itemId != 0L) putExtra(SubpageExtras.ITEM_ID, itemId)
    }

    companion object {
        /** 枚举名 → 页面。不认识的（脏 extra / 旧版本写下的名字）返回 null。 */
        fun screenOf(name: String?): SubpageScreen? =
            name?.let { raw -> SubpageScreen.entries.firstOrNull { it.name == raw } }

        /**
         * 纯函数形态的解析：没有 `screen` extra、或枚举名不认识时返回 null，
         * 由调用方决定回退到哪一页
         * （[SubpageActivity] 回退课表管理，与历史行为一致；[MainActivity] 视为「不是跳二级页」）。
         */
        fun of(
            screenName: String?,
            focusItemId: String? = null,
            courseName: String? = null,
            itemId: Long = 0L,
        ): SubpageRequest? =
            screenOf(screenName)?.let { screen ->
                SubpageRequest(screen, focusItemId, courseName, itemId)
            }

        fun from(intent: Intent?): SubpageRequest? = of(
            screenName = intent?.getStringExtra(SubpageExtras.SCREEN),
            focusItemId = intent?.getStringExtra(SubpageExtras.FOCUS_ITEM),
            courseName = intent?.getStringExtra(SubpageExtras.COURSE_NAME),
            itemId = intent?.getLongExtra(SubpageExtras.ITEM_ID, 0L) ?: 0L,
        )
    }
}

/**
 * 「离开 App 时正开着哪几层二级页」的记录（DESIGN §3.1）。
 *
 * 主路径不靠它：桌面图标点击时系统多压的那个 MainActivity 实例会自己 `finish()`
 * 让位（见 `MainActivity.onCreate`），二级页窗口原样留在栈里，谁都不用重建。
 * 这里兜的是**页面真被系统拆掉**的情况——有的 ROM 对桌面点击走 clearTop，
 * 窗口连 `finish()` 都没走就没了，用户再回来只能落在根页；此时按记录把层次放回去。
 *
 * 记账分两件事，都挂在 Activity 生命周期上：
 * - [onWindowCreated] / [onWindowDestroyed] 维护「此刻活着哪几层」，重建时按参数接回原位；
 * - [onWindowResumed] 把层次存成「用户离开时看到的画面」，[pendingRestore] 取的就是它。
 *
 * 用户自己退出的判断只看 [onWindowFinished]（`Activity.finish()`）：clearTop 与系统回收
 * 都不走它，拿 `onDestroy` 当退出信号会让记录在 clearTop 场景一起被清掉，恢复永远失效。
 */
internal object SubpageStack {
    // 全部读写都在主线程（Activity 生命周期回调），不加锁
    private val entries = mutableListOf<Entry>()

    /** 最后一次「用户离开 App 时看到的层次」，自下而上。 */
    private var lastVisible: List<SubpageRequest> = emptyList()

    /**
     * 一条窗口记录。`request` 说明是哪一页，`alive` 说明窗口还在不在。
     *
     * 存 `alive` 而不是直接拿 `request` 当身份：同参数的页面可以在链上出现两次
     * （笔记库 → 某课程 → 再点进同一门课的列表），只比参数会把两层当成一层，
     * 重建时还会在链上多留一份。
     */
    private class Entry(val request: SubpageRequest, var alive: Boolean)

    fun onWindowCreated(request: SubpageRequest) {
        // 配置变更/进程回收后的重建：接上刚死掉的那一条，别多占一层
        val reused = entries.firstOrNull { !it.alive && it.request == request }
        if (reused != null) {
            reused.alive = true
            return
        }
        entries.add(Entry(request, true))
    }

    fun onWindowResumed() {
        lastVisible = aliveChain()
    }

    fun onWindowFinished(request: SubpageRequest) {
        kill(request)
        // 用户自己退到主界面了（链空了且是他关的），之后不该再被恢复
        if (aliveChain().isEmpty()) lastVisible = emptyList()
        prune()
    }

    fun onWindowDestroyed(request: SubpageRequest) {
        kill(request)
        prune()
    }

    /**
     * 要恢复的层次，自下而上。**有窗口活着时是空的**——那种情况页面只是被前置，
     * 什么都不用做，照单重建反而会凭空多开一遍。
     */
    fun pendingRestore(): List<SubpageRequest> =
        if (aliveChain().isEmpty()) lastVisible else emptyList()

    /**
     * 全新启动（`savedInstanceState == null`）时清空：task 都重建了，
     * 旧记录只可能是「用户把 App 从最近任务划掉」留下的残影，
     * 留着会让下次点图标凭空落进一个二级页。
     */
    fun clear() {
        entries.clear()
        lastVisible = emptyList()
    }

    private fun aliveChain(): List<SubpageRequest> =
        entries.filter { it.alive }.map { it.request }

    private fun kill(request: SubpageRequest) {
        // 从链尾往前找：同参数的多个窗口里，先没的是更靠上的那个
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            if (entry.alive && entry.request == request) {
                entry.alive = false
                return
            }
        }
    }

    /** 丢掉既已死、又不在「离开画面」里的条目（重建用的占位与 lastVisible 里的保留）。 */
    private fun prune() {
        entries.removeAll { !it.alive && it.request !in lastVisible }
    }
}

/**
 * 二级页跳板的启动意图：**指向 [MainActivity]，不是 [SubpageActivity]**（DESIGN §3.1）。
 *
 * 通知（作业提醒、调课检测）只能拿到 application context，`PendingIntent.getActivity`
 * 启动时会带 `FLAG_ACTIVITY_NEW_TASK`。若直接指向 `SubpageActivity`，App 不在运行时
 * 它会成为新 task 的**根**——用户按返回直接退到桌面，而且这个 task 的根永远不是
 * MainActivity，之后再从桌面图标进来会另开一个 task，两套窗口并存。
 * 走 MainActivity 跳板后，根恒为 MainActivity，二级页只是它上面的一层。
 */
internal fun subpageLaunchIntent(context: Context, request: SubpageRequest): Intent =
    request.applyTo(Intent(context, MainActivity::class.java))
        // CLEAR_TOP：App 已开着的时候点通知，先把主窗口之上那几层旧页面清掉再进目标页，
        // 免得出现「新窗口压在旧二级页上、返回时退进旧栈」的乱栈。没有 task 时它不生效，
        // NEW_TASK 照常建新任务。
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

/**
 * 打开一个二级页（新窗口从右缘推入，退场传 0 = 主窗口原地不动）。
 * [Activity] context 才有窗口动画可言，非 Activity 只保证页面起得来。
 */
internal fun openSubpage(context: Context, request: SubpageRequest) {
    // 不带 NEW_TASK：从 Activity 启动时二级页要留在**当前** task 里（MainActivity 之上），
    // 带上了会先做一次 task 查找，行为虽等价但语义绕
    context.startActivity(request.applyTo(Intent(context, SubpageActivity::class.java)))
    (context as? Activity)?.let {
        @Suppress("DEPRECATION")
        it.overridePendingTransition(R.anim.slide_in_right, 0)
    }
}

/**
 * [openSubpage] 的「要拿返回值」版本：页面得用 `registerForActivityResult` 的 launcher
 * 启动（普通 `startActivity` 收不到结果），所以启动动作交给调用方，转场仍在这里补
 * ——DESIGN §3.1 要求二级页入口一律右滑推入，漏一处就是一次硬切。
 *
 * 目前只有快趣出行码页 → 附近单车地图这一条（要把选中的车号带回出码页）。
 */
internal fun openSubpageForResult(
    context: Context,
    launch: (Intent) -> Unit,
    request: SubpageRequest,
) {
    launch(request.applyTo(Intent(context, SubpageActivity::class.java)))
    (context as? Activity)?.let {
        @Suppress("DEPRECATION")
        it.overridePendingTransition(R.anim.slide_in_right, 0)
    }
}
