package edu.jxslu.schedule.domain

/**
 * 校园卡充值下单签名（DESIGN §4.19「充值」）。
 *
 * 还原自官方前端公开 JS（充值页 chunk `d98c9872` 的 `confirm()` 与收银台 chunk
 * `6affa2d0` 的请求封装，两处一致）：`appid` 与 `SECRET_KEY` 是前端硬编码常量，
 * 平台改版即失效——下单报错时 UI 兜底文案「平台可能已改版」。
 *
 * 算法：
 * 1. 业务参数并上 [APP_ID]/[TIMESTAMP]/[NONCE]/[SIGN_TYPE] 四个元参数；
 * 2. key 字典序排序，跳过空值（`0` 与 `"0"` 保留）与 `SIGN`/`SECRET_KEY` 两键；
 * 3. 依次拼 `k=v&`，末尾拼 `SECRET_KEY=<密钥>`（无 `&`）；
 * 4. UTF-8 SHA256 十六进制**大写** = `SIGN`。
 */
object YktRechargeSign {

    /** 前端硬编码的应用标识（充值页 `appid:"56321"`）。 */
    const val APP_ID_VALUE = CampusPaySign.APP_ID

    /** 前端硬编码的签名密钥（公开 JS 常量，非逆向所得）。 */
    const val SECRET_KEY = CampusPaySign.SECRET_KEY

    /**
     * 给业务参数补元参数与 `SIGN`（返回**新 map**，不改入参）。
     * [timestamp] 可注入（单测定死）；[nonce] 同。
     */
    fun signed(
        params: Map<String, String>,
        timestamp: String = buildTimestamp(),
        nonce: String = buildNonce(),
    ): Map<String, String> = CampusPaySign.signed(params, timestamp, nonce)

    /** 对已含元参数的 map 算 SIGN（算法见 [CampusPaySign]）。 */
    fun signOf(params: Map<String, String>): String = CampusPaySign.signOf(params)

    /** `yyyyMMddHHmmssSSS`（本地时区；前端 `new Date()` 拼接口径）。 */
    fun buildTimestamp(now: Long = System.currentTimeMillis()): String =
        CampusPaySign.buildTimestamp(now)

    /** 随机小写字母数字串（前端 `Math.random().toString(36).substring(2)` 口径，11 位）。 */
    fun buildNonce(): String = CampusPaySign.buildNonce()
}
