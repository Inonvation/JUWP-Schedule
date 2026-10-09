package edu.jxslu.schedule.domain

/**
 * 应用内字体缩放策略（「固定字体大小」开关，默认开）。
 *
 * 根因：不同机器的系统「字体大小」档位差异大（0.85×–2.0×），跟随会让组件文字互相挤占
 * （换行、裁切、按钮溢出）。**默认锁定**：App 内文字按固定倍率 [LockedScale]（0.9×）渲染，
 * 与系统设置脱钩。
 *
 * 为什么是 0.9× 而不是 1.0×：整套 UI 的观感是在 0.9× 倍率下真机调校并被接受的口径
 * （2026-10-09 真机核定：固定 1.0× 观感偏大，收到 0.9×）。倍率是**观感基准**，
 * 要改先想清楚——它一动，全 App 的字号一起动。
 *
 * 关闭锁定 = 完全回到旧行为：全 App 跟随系统缩放（课表网格另有自己的收敛，见 [GridFont]，
 * 它的「跟随」档届时跟随系统；锁定时则等于课名基准值）。
 *
 * 落点只有一处：Compose 根（`JuwRoot`）把 [effectiveFontScale] 的结果盖进 `LocalDensity`
 * 的 `fontScale`——布局密度（dp/px）原样透传，图标、间距、命中区都不受影响。
 */
object FontScalePolicy {

    /**
     * 锁定开启时的固定倍率（2026-10-09 真机核定，0.9×）。
     *
     * 不改写 `sp` 数值本身——[edu.jxslu.schedule.ui.theme.JuwTypography] 里的字号
     * 仍是设计值，倍率在渲染层统一乘上（与原「跟随系统」同一乘法点）。
     */
    const val LockedScale = 0.9f

    /** 当前生效的字体缩放：锁定 → [LockedScale]；未锁定 → 透传系统值。 */
    fun effectiveFontScale(locked: Boolean, systemFontScale: Float): Float =
        if (locked) LockedScale else systemFontScale
}
