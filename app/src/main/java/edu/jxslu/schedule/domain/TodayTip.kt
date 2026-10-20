package edu.jxslu.schedule.domain

/**
 * 今日页顶栏那一行的提示（DESIGN §3.29）。
 *
 * [text] 已含 emoji 前缀，直接渲染；[kind] 决定点击落点——
 * 天气句进天气页，课表句进课表页（2026-10-20：早先一律跳天气页）。
 */
data class TodayTip(val kind: Kind, val text: String) {

    enum class Kind {
        /** 天气句（含正在下雨 / 18:00 后的明天天气）。 */
        Weather,

        /** 课表句（南北楼 / 实验课 / 快下课有雨）。 */
        Schedule,
    }
}
