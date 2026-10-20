package edu.jxslu.schedule.domain

/**
 * 启动页（DESIGN §3.1 / §3.3）：主窗口每次新建时落的那个 Tab。
 *
 * 选项集 = 底栏 Tab 集，**顺序与底栏一致**（`MainActivity` 里的 `tabs`）；
 * 加 Tab 或删 Tab 时这里要同步改。生活与我的已收进左侧栏（2026-10-20），
 * 存着的旧选择由 [effectivePage] 落回今日页——不给用户留一个「选了却连入口都没有」的 Tab。
 *
 * 枚举只带显示名，**不带路由**：路由字符串的唯一来源仍是 `Routes`，
 * 映射写在 `MainActivity.route()` 里，避免第二份字面量。
 */
enum class StartPage(val label: String) {
    Today("今日"),
    Week("课表"),
    Jw("教务"),
    Life("生活"),
    Me("我的"),
    ;

    companion object {
        /**
         * 从存储值还原。认不出的值（旧版残留 / 手改 / 结构改名）一律退回 [Today]：
         * 同主题模式的口径——宁可落在默认页，也不要因为一个认不出的字符串
         * 让用户怀疑 App 打不开。null = 从未设置过，同样是默认页。
         */
        fun fromName(name: String?): StartPage =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Today

        /**
         * 设置页可选项 = 当前底栏 Tab 集（2026-10-20 起：今日 / 课表 / 教务）。
         * 生活与我的不再占 Tab：旧值仍可读，但统一落回今日（见 [effectivePage]）。
         */
        fun visiblePages(): List<StartPage> = listOf(Today, Week, Jw)

        /**
         * 实际生效的启动页：旧的 Life / Me 选择落回今日——对应的 Tab 已经没了。
         * 不写回存储：以后若恢复入口，旧选择还在。
         */
        fun effectivePage(stored: StartPage): StartPage =
            when (stored) {
                Life, Me -> Today
                else -> stored
            }
    }
}
