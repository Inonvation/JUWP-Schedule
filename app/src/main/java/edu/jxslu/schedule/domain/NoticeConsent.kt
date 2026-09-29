package edu.jxslu.schedule.domain

/**
 * 首启声明的同意判定（DESIGN §3.16）。
 *
 * 纯函数，可 JVM 测（`NoticeConsentTest`）。
 *
 * 为什么要落"同意过哪一版"而不是一个布尔：文案改了要把 [FirstRunNotices.VERSION] +1，
 * 比对版本就能让用户重看新条款，不需要清 prefs；反过来，只存布尔的话"改过文案"这件事
 * 在设备上完全不可见。
 *
 * **关闭锁不在这里**：队列只装"没同意过"的那几份（见 [pendingNotices]），进队列的必然
 * 要锁满，锁时长直接取 [FirstRunNotices.closeLockMs]，不存在"这次要不要锁"的判断。
 */
object NoticeConsent {

    /** 已同意的版本 ≥ 当前版本 = 不必再弹。0/null = 从没确认过。 */
    fun isConsented(consentedVersion: Int?, currentVersion: Int): Boolean =
        (consentedVersion ?: 0) >= currentVersion

    /**
     * 本次该弹哪几份、按什么顺序（[order] 缺省用 [FirstRunNotices.ORDER]）。
     *
     * 返回空列表 = 全部已同意，一个都不用弹。
     */
    fun pendingNotices(
        consents: Map<FirstRunNotice, Int?>,
        currentVersion: Int,
        order: List<FirstRunNotice> = FirstRunNotices.ORDER,
    ): List<FirstRunNotice> = order.filter { !isConsented(consents[it], currentVersion) }
}
