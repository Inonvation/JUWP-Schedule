package edu.jxslu.schedule.data.qiekj

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.Response
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * debug 专用：把胖乖几个端点的**响应体原文**落盘（`filesDir/qiekj_debug/`），供补协议样本。
 *
 * 为什么在拦截器层落盘：`SyncData` / `DeviceItem` 只声明了少数字段，`QiekjJson` 又开了
 * `ignoreUnknownKeys`，服务端多给的字段在解码那一步就被丢掉。要看它究竟回了什么，只能拿原始字节。
 * 采集目标见 [isQiekjDumpTarget]；`user/reg` 的响应体带 token，不在名单里。
 *
 * 落盘失败静默：留档是副业，不该影响开水主流程。release 不配目录（`Graph` 侧按
 * `BuildConfig.DEBUG` 传 null），什么都不写。
 */
class QiekjDebugDumpInterceptor(private val dir: File) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val path = chain.request().url.encodedPath.trim('/')
        if (!isQiekjDumpTarget(path)) return response
        runCatching {
            dumpQiekjResponse(
                dir = dir,
                path = path,
                httpStatus = response.code,
                body = response.peekBody(MAX_BODY_BYTES).string(),
            )
        }
        return response
    }

    private companion object {
        /** 单个响应体的落盘上限；胖乖这几个接口的返回都是几百字节的小 JSON。 */
        const val MAX_BODY_BYTES = 256L * 1024
    }
}

/**
 * 采集白名单（只读查询与开水链路）。
 * 不放 `user/reg`（响应含 token）与 `user/balance`（余额与设备状态无关，少一份样本少一份隐私）。
 */
fun isQiekjDumpTarget(path: String): Boolean = path in QIEKJ_DUMP_PATHS

private val QIEKJ_DUMP_PATHS = setOf(
    "goods/latestUsed",
    "goods/normal/details",
    "goods/normal/skus",
    "goods/water/sync",
    "goods/water/unlock",
)

/** 每个端点最多留多少份样本：轮询 sync 一秒一条，不设上限会堆出几千个文件。 */
const val QIEKJ_DUMP_KEEP_PER_ENDPOINT = 40

/** 落一份样本（文件名 `<端点>-<时间戳>.json`），随后按端点裁剪旧文件。 */
fun dumpQiekjResponse(dir: File, path: String, httpStatus: Int, body: String) {
    dir.mkdirs()
    val safe = path.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
    val redacted = redactQiekjDump(body)
    val payload = buildJsonObject {
        put("at", stamp)
        put("path", path)
        put("httpStatus", httpStatus)
        // 正文按 JSON 原样嵌进来（方便直接看结构）；不是合法 JSON 时就当字符串存
        put("body", runCatching { QiekjJson.json.parseToJsonElement(redacted) }
            .getOrElse { JsonPrimitive(redacted) })
    }.toString()
    var file = File(dir, "$safe-$stamp.json")
    var seq = 1
    while (file.exists()) {
        file = File(dir, "$safe-$stamp-${seq++}.json")
    }
    file.writeText(payload)
    pruneQiekjDumps(dir, safe, QIEKJ_DUMP_KEEP_PER_ENDPOINT)
}

/** 响应体里若回显凭证字段，落盘前替换掉；不匹配就原样写。 */
fun redactQiekjDump(text: String): String = text
    .replace(TOKEN_FIELD, """"token":"<redacted>"""")
    .replace(PHONE_FIELD, """"phone":"<redacted>"""")

private val TOKEN_FIELD = Regex(""""token"\s*:\s*"[^"]*"""")
private val PHONE_FIELD = Regex(""""phone"\s*:\s*"[^"]*"""")

/** 只保留该端点最新的 [keep] 份（文件名里的时间戳定宽，字典序即时间序）。 */
fun pruneQiekjDumps(dir: File, safeEndpointPath: String, keep: Int) {
    dir.listFiles { file -> file.isFile && file.name.startsWith("$safeEndpointPath-") }
        ?.sortedBy { it.name }
        ?.dropLast(keep)
        ?.forEach { it.delete() }
}
