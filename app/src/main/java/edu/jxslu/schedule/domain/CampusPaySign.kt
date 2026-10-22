package edu.jxslu.schedule.domain

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * 校园卡 / 电费充值共用的前端签名算法（两套平台的前端 JS 常量与拼接口径一致）。
 *
 * 算法：
 * 1. 业务参数并上 `APP_ID` / `TIMESTAMP` / `NONCE` / `SIGN_TYPE` 四个元参数；
 * 2. key 字典序排序，跳过空值（`0` 与 `"0"` 保留）与 `SIGN` / `SECRET_KEY` 两键；
 * 3. 依次拼 `k=v&`，末尾拼 `SECRET_KEY=<密钥>`（无 `&`）；
 * 4. UTF-8 SHA256 十六进制**大写** = `SIGN`。
 *
 * 平台改版即失效；调用方保留各自的错误文案（「平台可能已改版」）。
 */
object CampusPaySign {

    /** 前端硬编码的应用标识（公开 JS 常量，非逆向所得）。 */
    const val APP_ID = "56321"

    /** 前端硬编码的签名密钥（公开 JS 常量，非逆向所得）。 */
    const val SECRET_KEY = "0osTIhce7uPvDKHz6aa67bhCukaKoYl4"

    private const val KEY_APP_ID = "APP_ID"
    private const val KEY_TIMESTAMP = "TIMESTAMP"
    private const val KEY_NONCE = "NONCE"
    private const val KEY_SIGN_TYPE = "SIGN_TYPE"
    private const val KEY_SIGN = "SIGN"
    private const val KEY_SECRET_KEY = "SECRET_KEY"

    /** 给业务参数补元参数与 [KEY_SIGN]（返回**新 map**，不改入参）。 */
    fun signed(
        params: Map<String, String>,
        timestamp: String = buildTimestamp(),
        nonce: String = buildNonce(),
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String>(params.size + 4)
        merged.putAll(params)
        merged[KEY_APP_ID] = APP_ID
        merged[KEY_TIMESTAMP] = timestamp
        merged[KEY_NONCE] = nonce
        merged[KEY_SIGN_TYPE] = "SHA256"
        merged[KEY_SIGN] = signOf(merged)
        return merged
    }

    /** 对已含元参数的 map 算 SIGN（见类 KDoc 步骤 2–4）。 */
    fun signOf(params: Map<String, String>): String {
        val keys = params.keys
            .filter { it != KEY_SIGN && it != KEY_SECRET_KEY }
            .filter { (params[it] ?: "").isNotEmpty() }
            .sorted()
        val sb = StringBuilder()
        for (k in keys) {
            sb.append(k).append('=').append(params.getValue(k)).append('&')
        }
        sb.append(KEY_SECRET_KEY).append('=').append(SECRET_KEY)
        return sha256Hex(sb.toString()).uppercase()
    }

    /** `yyyyMMddHHmmssSSS`（本地时区；前端 `new Date()` 拼接口径）。 */
    fun buildTimestamp(now: Long = System.currentTimeMillis()): String {
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
        return ldt.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
    }

    /** 11 位小写字母数字（前端 `Math.random().toString(36).substring(2)` 口径）。 */
    fun buildNonce(random: Random = Random): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString { repeat(11) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
