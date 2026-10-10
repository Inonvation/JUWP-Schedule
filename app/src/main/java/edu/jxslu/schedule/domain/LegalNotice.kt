package edu.jxslu.schedule.domain

/**
 * 「使用前须知」周期性声明（转私有前的最终版加入）。
 *
 * 与首启那两份（[FirstRunNotices] 的用户须知 / 免责声明）**相互独立**：首启那两份
 * 只在引导里弹一次（同意过就不再弹），这一份是**周期性**的——每 [INTERVAL_MS] 必弹，
 * 没有「不再提醒」的出口（用户口径：不能永久关闭）。
 *
 * 纯逻辑，可 JVM 测（`LegalNoticeTest`）。弹不弹的判断（[shouldShow]）与加粗标记解析
 * （[parseBold]）都在这里，UI 层只负责排版。
 */
object LegalNotice {

    /**
     * 文案版本号。改任何一句都要 +1——与 [FirstRunNotices.VERSION] 同口径，
     * 方便日后核对「用户看的是哪一版」。
     */
    const val VERSION = 1

    /** 弹出周期：3 天。 */
    const val INTERVAL_MS = 3 * 24 * 60 * 60 * 1000L

    /** 弹窗标题。 */
    const val TITLE = "使用前须知"

    /** 开场句。 */
    const val INTRO = "本软件为个人学习项目，请在使用前阅读以下声明。"

    /**
     * 逐条正文。`**…**` 是加粗标记，由 [parseBold] 解析——UI 不写死哪几个字要加粗。
     *
     * 第 1 条保留「24 小时内删除」是用户明确要求；它法律效果弱（见 README 免责声明口径），
     * 保留在此只为如实转达用户的声明意图。
     */
    val ITEMS: List<String> = listOf(
        "本软件为**个人学习项目**，仅供**学习交流**使用，请于下载后 **24 小时内删除**。",
        "**请勿传播**本软件，或将其用于**商业用途**。",
        "使用本软件可能**违反第三方平台的服务条款**，**封号风险自负**。",
        "请妥善保管个人隐私凭据信息，由此造成的损失**开发者概不负责**。",
    )

    /**
     * 现在该不该弹。
     *
     * [lastShownAtMs] 为 null 或 ≤ 0（从没弹过）→ 弹；否则距上次 ≥ [INTERVAL_MS] 才弹。
     * 边界取「正好满 3 天」即弹（`>=`），与 [RechargeDisclaimer.isSuppressed] 的严格大于相反：
     * 那份是「到期就重新提醒」，这份是「满周期就弹」，语义都是「到点即触发」。
     */
    fun shouldShow(lastShownAtMs: Long?, nowMs: Long): Boolean {
        val last = lastShownAtMs ?: 0L
        if (last <= 0L) return true
        return nowMs - last >= INTERVAL_MS
    }

    /** 上次弹出的时刻 → 下一次该弹的时刻。从没弹过返回 0（= 立刻该弹）。 */
    fun nextShownAt(lastShownAtMs: Long): Long =
        if (lastShownAtMs <= 0L) 0L else lastShownAtMs + INTERVAL_MS

    /**
     * 把一行带 `**…**` 标记的文本拆成若干段，标出哪几段加粗。
     *
     * 规则：按 `**` 切分，偶数下标为普通、奇数下标为加粗。**未闭合的尾部标记**照加粗处理
     * （内容由本项目自己维护、始终成对；这里只保证不抛异常、不吞字）。
     * 返回纯数据（[BoldSegment]），由 UI 层转 `AnnotatedString`——domain 层不依赖 Compose。
     */
    fun parseBold(line: String): List<BoldSegment> {
        if (line.isEmpty()) return emptyList()
        return line.split("**")
            // 先按奇偶下标判加粗，再丢掉空段——先 filter 会打乱下标奇偶
            .mapIndexed { index, text -> BoldSegment(text = text, bold = index % 2 == 1) }
            .filter { it.text.isNotEmpty() }
    }
}

/** [LegalNotice.parseBold] 的一段：文本 + 是否加粗。 */
data class BoldSegment(val text: String, val bold: Boolean)
