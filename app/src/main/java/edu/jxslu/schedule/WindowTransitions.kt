package edu.jxslu.schedule

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 二级页窗口的进出场过渡（DESIGN §3.1）。分两套走：
 *
 * - **API 34+（Android 14）**：由窗口自己用 [Activity.overrideActivityTransition] 声明。
 *   系统会把返回手势的进度交给这套动画——滑到一半松手前能看见上一页、也能撤回，
 *   也就是「预测性返回」；
 * - **API 33 及以下**：平台没有这套机制，照旧由启动方 / 关闭方调 `overridePendingTransition`。
 *
 * **不动的那一侧要给 [R.anim.stay_still]，不能传 0**（2026-09-26 修：二级页返回没有
 * 跟手预览）。覆盖语义只需要那一侧「不动」，但 0 的含义是「这一侧没有动画资源」，
 * 跟手预览两边都得有可驱动的动画才接力得出来；缺一侧时手势全程没有预览、松手才切页。
 * 静止动画是 0 → 0 位移，与传 0 的观感一致。
 *
 * **别在 34+ 上调 `overridePendingTransition`**：那是已废弃 API，只要调了，系统就认为这个
 * App 没适配预测性返回，把手势预览整个关掉。2026-09-23 在 Redmi K70（Android 16）上对比过：
 * 手势滑到一半只有边缘指示条、没有任何预览，松手才切页。
 */
internal fun Activity.enablePredictiveBackTransitions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // 打开：新窗口从右缘推入，主窗口原地不动
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_OPEN,
            R.anim.slide_in_right,
            R.anim.stay_still,
        )
        // 关闭：被露出的主窗口原地不动，本窗口向右滑出
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_CLOSE,
            R.anim.stay_still,
            R.anim.slide_out_right,
        )
    }
}

/** 打开二级页时父窗口侧的过渡。34+ 什么都不做，交给新窗口自己声明。 */
internal fun applySubpageOpenTransition(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    (context as? Activity)?.let {
        @Suppress("DEPRECATION")
        it.overridePendingTransition(R.anim.slide_in_right, 0)
    }
}

/** 关闭二级页时窗口侧的过渡。34+ 同上。 */
internal fun applySubpageCloseTransition(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    @Suppress("DEPRECATION")
    activity.overridePendingTransition(0, R.anim.slide_out_right)
}

/**
 * 拉起 App 之外的界面（微信扫一扫 / 微信支付 / 浏览器 / 快趣出行 / 系统设置页…），
 * 统一走这一个出口。异常原样抛出（`ActivityNotFoundException` 等），调用方按各自的
 * 兜底链处理；非 Activity context 自动补 `FLAG_ACTIVITY_NEW_TASK`。
 *
 * 2026-09-26 修「打开微信后页面跳动」：二级页声明的 OPEN 过渡 enterAnim（右推入）不只
 * 在本窗口自己被启动时生效——从外部应用回到本窗口时，本窗口就是「被打开」的一侧，
 * slide_in_right 会被整个重放一遍，观感是页面凭空再滑一次（顶栏最先动，用户报「界面跳动」）。
 * 只有跨 task 的往返才会重放，而微信是 singleTask、永远开不进调用方的 task，所以
 * 「Activity context 不设 NEW_TASK 让外部应用留在本 task」只救了浏览器，救不了微信。
 * 外部应用不属于二级页导航，不该复用右推动画：拉起前把本窗口的开/关过渡全部换成
 * [R.anim.stay_still]，返回时重放的就只是静止动画；返回过渡放完（窗口重新拿到焦点）
 * 后由 [SubpageActivity.onWindowFocusChanged] 还原推入/滑出。
 * 只有声明了过渡覆盖的 [SubpageActivity] 需要抑制——MainActivity 没有覆盖，
 * 顺手抑制反而会凭空加上一套，连带哑掉主窗口自己的预测性返回。
 */
internal fun Context.startActivityOutsideApp(intent: Intent) {
    val subpage = this as? SubpageActivity
    try {
        subpage?.suppressTransitionsForExternalLaunch()
        if (this is Activity) {
            startActivity(intent)
        } else {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    } catch (e: Exception) {
        // 没真离开（未装微信 / 目标解析失败）：立刻还原，别让本页返回动画一直哑着
        subpage?.enablePredictiveBackTransitions()
        throw e
    }
}

/** 拉起外部应用期间，把本窗口的开/关过渡全部换成静止（见 [startActivityOutsideApp]）。 */
private fun SubpageActivity.suppressTransitionsForExternalLaunch() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    overrideActivityTransition(
        Activity.OVERRIDE_TRANSITION_OPEN,
        R.anim.stay_still,
        R.anim.stay_still,
    )
    overrideActivityTransition(
        Activity.OVERRIDE_TRANSITION_CLOSE,
        R.anim.stay_still,
        R.anim.stay_still,
    )
}
