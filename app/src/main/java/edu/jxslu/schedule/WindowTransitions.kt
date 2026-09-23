package edu.jxslu.schedule

import android.app.Activity
import android.content.Context
import android.os.Build

/**
 * 二级页窗口的进出场过渡（DESIGN §3.1）。分两套走：
 *
 * - **API 34+（Android 14）**：由窗口自己用 [Activity.overrideActivityTransition] 声明。
 *   系统会把返回手势的进度交给这套动画——滑到一半松手前能看见上一页、也能撤回，
 *   也就是「预测性返回」；
 * - **API 33 及以下**：平台没有这套机制，照旧由启动方 / 关闭方调 `overridePendingTransition`。
 *
 * **别在 34+ 上调 `overridePendingTransition`**：那是已废弃 API，只要调了，系统就认为这个
 * App 没适配预测性返回，把手势预览整个关掉。2026-09-23 在 Redmi K70（Android 16）上对比过：
 * 手势滑到一半只有边缘指示条、没有任何预览，松手才切页。
 */
internal fun Activity.enablePredictiveBackTransitions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, R.anim.slide_in_right, 0)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, R.anim.slide_out_right)
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
