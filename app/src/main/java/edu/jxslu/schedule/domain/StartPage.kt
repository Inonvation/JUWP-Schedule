package edu.jxslu.schedule.domain

/**
 * 启动页（DESIGN §3.1 / §3.3）：主窗口每次新建时落的那个 Tab。
 *
 * 选项集 = 底栏 Tab 集，**顺序与底栏一致**（`MainActivity` 里的 `tabs`）；
 * 加 Tab 或删 Tab 时这里要同步改。生活页（§3.13）关掉时 [Life] 这一项从设置里
 * 同步消失（[visiblePages]），存着的旧选择由 [effectivePage] 落回今日页——
 * 不给用户留一个「选了却连入口都没有」的 Tab。
 *
 * 枚举只带显示名，**不带路由**：路由字符串的唯一来源仍是 `Routes`，
 * 映射写在 `MainActivity.route()` 里，避免第二份字面量。
 */
enum class StartPage(val label: String) {
    Today("今日"),
    Week("课表"),
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
         * 设置页可选项。生活页关掉时不列 [Life]：那一项对应的 Tab 已经没了，
         * 让用户选它是给一个死路。
         */
        fun visiblePages(lifeTabEnabled: Boolean): List<StartPage> =
            entries.filter { it != Life || lifeTabEnabled }

        /**
         * 实际生效的启动页：存的是 [Life] 而生活页已关 → [Today]。
         *
         * 设置页选中态与启动落点**共用这一个口径**，否则会出现「设置里显示生活、
         * 打开却落在今日」。不反过来写回存储：用户重新打开生活页时，他的选择应该回来。
         */
        fun effectivePage(stored: StartPage, lifeTabEnabled: Boolean): StartPage =
            if (stored == Life && !lifeTabEnabled) Today else stored
    }
}
