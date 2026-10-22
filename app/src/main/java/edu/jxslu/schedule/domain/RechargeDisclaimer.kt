package edu.jxslu.schedule.domain

/**
 * 充值免责声明弹窗的静默窗口（2026-09-29，DESIGN §3.13 / §4.19）。
 *
 * 电费与一卡通的**每个充值入口**在打开充值弹层前都要过这一道提醒。用户勾选
 * 「一周内不再提醒」落一个截止时刻，窗口内不再弹；不勾 = 下次充值再弹。
 * 两种充值共用同一个窗口——这是同一件事（充值）的同一句提醒，分开存只会让
 * 弹窗节奏变得难预期。
 *
 * 纯逻辑，可 JVM 测（`RechargeDisclaimerTest`）。
 */
object RechargeDisclaimer {

    /** 勾选「一周内不再提醒」后的静默天数。 */
    const val SUPPRESS_DAYS = 7

    /** 静默时长（毫秒）。 */
    const val SUPPRESS_MS = SUPPRESS_DAYS * 24L * 60 * 60 * 1000

    /** 首次弹出（从未确认过）的关闭锁：锁住期间按钮置灰、返回/点遮罩无效。 */
    const val FIRST_CLOSE_DELAY_MS = 5_000L

    /**
     * 正文（2026-10-22 由 5 条压到 3 条，承诺不删）。
     *
     * 放在 domain 而不是弹窗里：弹窗（`ui/common/RechargeDisclaimerDialog`）与
     * 「我的 → 关于」的只读查看入口共用这一份，改一处就够。
     * `**...**` 由 `ui/common/emphasizedText` 渲染成加粗。
     */
    val ITEMS: List<String> = listOf(
        "本应用**只提供学校官方充值入口**，不经手资金；接口可能变更，**请以官方页面为准**。",
        "**先小额充值并确认到账**；充值前核对账户（电费核对楼栋房号），" +
            "**充错账户的损失难以追回**。",
        "**严禁**刷余额、盗刷、诈骗等违法行为；**勿向他人泄露**学号、密码或付款码。" +
            "违规或操作不当造成的损失**由使用者自行承担**。",
    )

    /** 勾选「一周内不再提醒」时落库的截止时刻。 */
    fun suppressUntil(nowMs: Long): Long = nowMs + SUPPRESS_MS

    /**
     * 截止时刻仍在未来 = 静默中，不弹。0/null = 从没勾过（或已过期），要弹。
     * 严格大于：到期那一瞬就该重新弹，不差最后一毫秒。
     */
    fun isSuppressed(suppressUntilMs: Long?, nowMs: Long): Boolean =
        (suppressUntilMs ?: 0L) > nowMs

    /**
     * 本次弹窗的关闭锁时长：**从未确认过**（seenAt = 0）锁 [FIRST_CLOSE_DELAY_MS]，
     * 之后任何一次弹出（继续/取消/静默过期后再弹）都立即可关。
     * 「确认过」以落库的 seenAt 为准（继续或取消都写），进程重启不重置。
     */
    fun closeLockMs(seenAtMs: Long?, nowMs: Long): Long =
        if ((seenAtMs ?: 0L) != 0L) 0L else FIRST_CLOSE_DELAY_MS
}
