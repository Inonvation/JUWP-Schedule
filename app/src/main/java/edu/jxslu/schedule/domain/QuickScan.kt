package edu.jxslu.schedule.domain

/**
 * 今日页顶栏「+ → 扫一扫」识别出来的去处（2026-10-02，DESIGN §3.3）。
 *
 * 取景窗口只回传二维码原文（`EbikeScanActivity.MODE_RAW`），**去哪一页由这里定**：
 * - [Ebike]：车身码 —— 带完整车号进「骑行」页（`SubpageScreen.RIDE` 的 `focusItemId`）；
 * - [Foreign]：**明确属于别家**的码（微信支付 / 微信 / 支付宝 / QQ），就地提示、不跳页；
 * - [Washer]：其余内容一律当洗衣机机身码，原文交给 U净 页（服务端 `scanWasherCode`
 *   要的就是原文，见 `.agents/rules/ujing.md`）。
 *
 * 先判车身码这条顺序不能反：U净 机身码没有本地可辨的格式（服务端识别），
 * 而车身码有明确形态（[EbikeQr.parseScannedCarNum]），只有先排掉它才不会把车码送错页；
 * [Foreign] 排在 [Washer] 前面，因为它能拦掉「随手扫到别人的码」这条最常见的误跳。
 *
 * **判据偏宽是留着的已知取舍**：`EbikeQr` 认 6~12 位纯数字（别的车队前缀不固定），
 * 一条恰好是这种数字的机身码会被判成车码；[FOREIGN_CODES] 之外的真实机身码格式也没法
 * 本地辨认，只能交给 U净 服务端。要收紧就得拿机身码样本 —— debug 包会把
 * `scanWasherCode` 的原文与响应落 `filesDir/ujing_debug/`（见 `UjingRepository.dumpScanWasher`）。
 */
sealed interface QuickScanTarget {

    /** 快趣出行车身码：[carNum] 是规整后的完整车号。 */
    data class Ebike(val carNum: String) : QuickScanTarget

    /** 洗衣机机身码：[raw] 是二维码原文（已 trim），解析归 U净 服务端。 */
    data class Washer(val raw: String) : QuickScanTarget

    /** 别的 App 的码：[label] 是归属方（「微信支付」等），提示文案用它。 */
    data class Foreign(val label: String) : QuickScanTarget
}

/**
 * 别家 App 的码型（片段 → 归属方）。命中就**不再往 U净 送**：这些码送过去必然
 * 回「未识别到设备信息」，白跳一个页面。
 *
 * 用 `contains` 而不是 `startsWith`：同一种码的前缀形态不止一个
 * （微信名片是 `u.wechat.com`、公众号是 `mp.weixin.qq.com`，都在域名位置）。
 * 表**只收认得准的**：多写一条错的，就是把真机身码判成别人的码，比漏拦严重得多。
 */
private val FOREIGN_CODES: List<Pair<String, String>> = listOf(
    "wxp://" to "微信支付",
    "weixin.qq.com" to "微信",
    "u.wechat.com" to "微信",
    "qr.alipay.com" to "支付宝",
    "alipays://" to "支付宝",
    "qm.qq.com" to "QQ",
)

/**
 * 扫一扫内容 → 去处；空内容（扫到一半退出、相册里没认出码）返回 null，
 * 调用方按「取消」处理，不弹任何失败提示。
 */
fun quickScanTargetOf(raw: String?): QuickScanTarget? {
    val content = raw?.trim().orEmpty()
    if (content.isEmpty()) return null
    EbikeQr.parseScannedCarNum(content)?.let { return QuickScanTarget.Ebike(it) }
    FOREIGN_CODES.firstOrNull { (marker, _) -> content.contains(marker, ignoreCase = true) }
        ?.let { (_, label) -> return QuickScanTarget.Foreign(label) }
    return QuickScanTarget.Washer(content)
}
