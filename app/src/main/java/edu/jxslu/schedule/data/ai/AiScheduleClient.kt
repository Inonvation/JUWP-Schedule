package edu.jxslu.schedule.data.ai

import android.util.Base64
import android.util.Log
import edu.jxslu.schedule.data.net.AppHttp
import edu.jxslu.schedule.domain.AiScheduleTool
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource

/**
 * AI 调课的 OpenAI 兼容客户端（DESIGN §4.39，2026-10-06）。
 *
 * 对接 `/chat/completions`（**流式 SSE**）与 `/models` 两个端点，baseUrl 由用户在
 * 设置里配（预填 `https://api.deepseek.com/v1`，可清空自定义）。
 *
 * **流式**：`stream: true`，逐条解析 `data:` 行，把三种增量分开吐给上层——
 * `reasoning_content`（思考过程）、`content`（正文）、`tool_calls[].function.arguments`
 * （工具参数分片，需累积拼完整）。用户要看到「边想边出」而不是等一坨。
 *
 * **思考档位与强制工具调用的冲突**（最容易踩的坑）：DeepSeek 的 `thinking` 默认开启，
 * 而**指定函数名的 `tool_choice` 在思考模式下直接返回 400**（官方文档 Tool Calls 节）。
 * 所以：档位 = 关闭 → `thinking: disabled` + 指定函数名；档位 = 开启 → 不带强制，
 * `tool_choice: auto`，靠 system prompt 要求调工具（正文兜底见 `AiScheduleTool.extractJson`）。
 * `thinking` / `reasoning_effort` 只对 `baseUrl` 含 `deepseek` 的端点发——其它 OpenAI
 * 兼容服务不认识，发了可能被判非法参数。
 *
 * **无任何日志拦截器**：API Key 绝不进 Logcat（对齐一卡通 / 电费 / 快趣 / U净）。
 */
class AiScheduleClient private constructor(private val http: OkHttpClient) {

    /** 流式增量。上层累积成三段文本，结束时解析工具参数。 */
    sealed interface StreamEvent {
        /** 思考过程增量（仅思考档位开启时有）。 */
        data class Reasoning(val delta: String) : StreamEvent

        /** 正文增量。 */
        data class Content(val delta: String) : StreamEvent

        /** 工具参数分片，需累积拼接成完整 JSON。 */
        data class ToolArgs(val delta: String) : StreamEvent
    }

    /** HTTP 层失败。[code] 为 0 表示响应体不是预期结构；[detail] 是响应原文截断，供 UI 提示。 */
    class AiHttpException(val code: Int, val detail: String) : IOException("HTTP $code")

    /** 思考档位：关闭时不带思考、强制命名工具调用；开启时反过来。 */
    enum class Effort(val wire: String) {
        NONE("none"),
        LOW("low"),
        HIGH("high"),
        MAX("max");

        val label: String
            get() = when (this) {
                NONE -> "关闭"
                LOW -> "低"
                HIGH -> "高"
                MAX -> "最高"
            }

        companion object {
            fun fromWire(value: String?): Effort =
                entries.firstOrNull { it.wire == value } ?: NONE
        }
    }

    /**
     * 发一次调课请求，流式返回增量。[baseUrl] 结尾有无 `/` 都行。
     * 网络异常与 HTTP 错误都抛 [AiHttpException] / IOException，由上层分类提示，不自动重试。
     *
     * [images] 是随本轮发出的图片文件（已由 `AiImageStore` 落盘：转正、转 JPEG、压到 1600）。
     * **空列表时 content 仍是字符串**，与非视觉模型、老网关保持兼容；非空才升成
     * `[{text}, {image_url}…]` 的多模态数组，**文本在前、图片在后**（顺序影响模型理解）。
     */
    fun chatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        effort: Effort,
        images: List<File> = emptyList(),
    ): Flow<StreamEvent> = callbackFlow {
        val thinking = effort != Effort.NONE
        val isDeepSeek = baseUrl.contains("deepseek", ignoreCase = true)
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            if (isDeepSeek) {
                putJsonObject("thinking") { put("type", if (thinking) "enabled" else "disabled") }
                if (thinking) put("reasoning_effort", effort.wire)
            }
            if (thinking) {
                // 思考模式不支持指定函数名（400），退回 auto，靠 system prompt 要求调工具
                put("tool_choice", "auto")
            } else {
                putJsonObject("tool_choice") {
                    put("type", "function")
                    putJsonObject("function") { put("name", AiScheduleTool.FUNCTION_NAME) }
                }
            }
            putJsonArray("tools") {
                addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", AiScheduleTool.FUNCTION_NAME)
                        put("description", TOOL_DESCRIPTION)
                        put("parameters", format.parseToJsonElement(AiScheduleTool.PARAMETERS_JSON))
                    }
                }
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    val dataUris = images.mapNotNull(::dataUri)
                    if (dataUris.isEmpty()) {
                        put("content", user)
                    } else {
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", user)
                            }
                            dataUris.forEach { uri ->
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", uri) }
                                }
                            }
                        }
                    }
                }
            }
        }.toString()

        val request = Request.Builder()
            .url(endpoint(baseUrl, "chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        // 用 enqueue + callbackFlow，而不是阻塞式 execute：
        // 协程取消打断不了 `readUtf8Line()`，只有 `call.cancel()` 能立刻关 socket。
        // 用户点「停止」的意图是**别再烧额度**，停在协程层是不够的。
        val call = http.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val responseBody = resp.body
                    if (!resp.isSuccessful || responseBody == null) {
                        close(AiHttpException(resp.code, responseBody?.string()?.take(500).orEmpty()))
                        return
                    }
                    try {
                        readStream(responseBody.source(), this@callbackFlow)
                        close()
                    } catch (t: Throwable) {
                        close(t)
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /**
     * 逐行读 SSE。三种增量分开吐；同时兜住三类「静默成功」的坑：
     *
     * 1. **200 但不是 SSE**（网关 HTML / 整体 JSON 错误页）：一行都解析不出时抛错，
     *    而不是让上层把空结果当成「模型没有返回内容」的**成功**气泡。
     * 2. **流中途的 `error` 块**：部分网关这么报（额度 / 内容策略），原样抛出去。
     * 3. **`finish_reason` 是 length / content_filter**：输出被截断，工具参数必然是半截 JSON，
     *    早点报错比让解析层全拒更好。
     *
     * **本方法吃 [BufferedSource] 而不是 ResponseBody**：source 是 okio 纯 JVM 类型，
     * 单测可以用 `Buffer` 直接喂 SSE 文本，钉住「某种 delta 不掐断流」的行为。
     */
    private fun readStream(body: BufferedSource, channel: SendChannel<StreamEvent>) {
        val snippet = StringBuilder()
        var sawEvent = false
        while (true) {
            val line = body.readUtf8Line() ?: break
            if (snippet.length < SNIPPET_MAX) {
                snippet.append(line.take(120)).append('\n')
            }
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") {
                sawEvent = true
                break
            }
            val root = runCatching { format.parseToJsonElement(payload) }.getOrNull() as? JsonObject
                ?: continue
            (root["error"] as? JsonObject)?.let {
                throw AiHttpException(0, it.toString().take(300))
            }
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
            sawEvent = true
            val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
            if (finish == "length" || finish == "content_filter") {
                throw AiHttpException(0, "上游输出被截断（finish_reason=$finish），结果不完整")
            }
            val delta = choice["delta"] as? JsonObject ?: continue
            (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotEmpty() }
                ?.let { channel.trySend(StreamEvent.Reasoning(it)) }
            (delta["content"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotEmpty() }
                ?.let { channel.trySend(StreamEvent.Content(it)) }
            // **本函数最要紧的一行纪律**：不认识的 delta 一律 `continue`，绝不 `return`。
            // 2026-10-06 修复：此前「delta 里没有 tool_calls → return」会把整个流在第一条
            // 正文 / 思考 / finish 块就掐断——工具参数只收到零个分片，上层解析出空结果，
            // 用户看到的就是「发了任何消息都返回无效」。V4.1-Flash 的输出顺序一变必踩。
            // 同理，只认 0 号工具：**跳过**别的 index / 别的函数名的 toolCall，继续读，
            // 而不是结束——并行 tool_call 或网关注入别的函数时不能把正主一起扔掉。
            val toolCalls = delta["tool_calls"] as? JsonArray ?: continue
            for (element in toolCalls) {
                val toolCall = element as? JsonObject ?: continue
                val toolIndex = (toolCall["index"] as? JsonPrimitive)?.intOrNull ?: 0
                if (toolIndex != 0) continue
                val function = toolCall["function"] as? JsonObject
                val functionName = (function?.get("name") as? JsonPrimitive)?.contentOrNull
                if (functionName != null && functionName != AiScheduleTool.FUNCTION_NAME) continue
                (function?.get("arguments") as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { channel.trySend(StreamEvent.ToolArgs(it)) }
            }
        }
        if (!sawEvent) {
            throw AiHttpException(
                0,
                "响应不是流式数据（可能被网关拦截）：${snippet.toString().trim().take(300)}",
            )
        }
    }

    /** 单测入口：与 [readStream] 同一实现，用 okio `Buffer` 直接喂 SSE 文本（JVM 可跑）。 */
    internal suspend fun readStreamForTest(source: BufferedSource, channel: SendChannel<StreamEvent>) {
        readStream(source, channel)
    }

    /** 拉上游模型列表（设置页「获取模型列表」用）。解析失败返回空列表而不是抛错。 */
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint(baseUrl, "models"))
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        val text = execute(request, "models")
        val root = runCatching { format.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return@withContext emptyList()
        (root["data"] as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
            .filter { it.isNotBlank() }
    }

    private fun execute(request: Request, what: String): String = runCatching {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw AiHttpException(response.code, body.take(500))
            body
        }
    }.onFailure { Log.w(TAG, "$what failed: ${it.javaClass.simpleName}") }.getOrThrow()

    /**
     * 文件 → `data:image/jpeg;base64,…`。落盘时已强制转 JPEG（`AiImageStore` 走
     * `ImageImport(forceJpeg = true)`），所以 mime 固定 `image/jpeg`，不需要再嗅探。
     * 单张读不到就跳过这一张（不整批失败），但会记一条 warn。
     */
    private fun dataUri(file: File): String? = runCatching {
        "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }.onFailure { Log.w(TAG, "encode image failed: ${it.javaClass.simpleName}") }.getOrNull()

    companion object {
        private const val TAG = "AiScheduleClient"
        private const val TOOL_DESCRIPTION =
            "把调课通知翻译成对当前课表的改动。课程必须引用课表清单里的编号（existingIndex），" +
                "不要臆造课程；按具体日期发布的放假 / 调休补课安排走 overrides。"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val format = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 非 SSE 响应体截取多少字给用户看。 */
        private const val SNIPPET_MAX = 500

        /** 「上游不吃图」的报错关键词。见 [looksLikeImageRejection]。 */
        private val IMAGE_REJECT_HINTS = listOf(
            "image_url", "image", "vision", "multimodal", "modality", "图片",
        )

        /** baseUrl 结尾的 `/` 归一后拼子路径；已带 `/v1` 的照原样接。 */
        private fun endpoint(baseUrl: String, path: String): String =
            baseUrl.trim().trimEnd('/') + "/" + path

        /**
         * 上游是不是在抱怨「不吃图片」。只作**提示**用（决定失败气泡给不给「去掉图片重发」），
         * 不是硬判定——所以放宽：4xx 且报错里出现图片/模态相关词就算。
         * 有的网关只回一句笼统的 400，那就落到普通失败分支，用户照样能手动去掉图片重发。
         */
        fun looksLikeImageRejection(code: Int, detail: String): Boolean =
            // 413 = 请求体过大：图片场景最典型的失败，也算图片类，用户才有「去掉图片重发」这条路
            (code in 400..422 || code == 413) &&
                IMAGE_REJECT_HINTS.any { detail.contains(it, ignoreCase = true) }

        /**
         * 派生纪律（见 [AppHttp] 注释）：只覆盖超时，不动连接池。
         * readTimeout 只管两次读的间隔——流式响应会持续吐字节，不会触发它；
         * callTimeout 放到 180s 兜住「模型长时间不吐字」的总时长。
         */
        fun create(): AiScheduleClient = AiScheduleClient(
            AppHttp.base.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                // readTimeout(90s) 兜「长时间不吐字」，callTimeout 只需兜「总时长」。
                // 180s 会把 max 档的长思考 + 长输出硬切（实测抛的是 InterruptedIOException）
                .callTimeout(600, TimeUnit.SECONDS)
                .build(),
        )
    }
}
