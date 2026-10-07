package edu.jxslu.schedule.data.ai

import edu.jxslu.schedule.data.ai.AiScheduleClient.StreamEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 流解析的行为钉（2026-10-06）。
 *
 * 背景：`readStream` 曾在「delta 里没有 tool_calls」时 `return` 把整个流提前掐断——
 * V4.1-Flash 的输出顺序一变（先吐正文/思考/finish 块再吐工具参数），工具参数就只收到
 * 零个分片，上层解析出空结果，用户看到「发了任何消息都返回无效」。这里用 okio 的
 * `Buffer` 直接喂 SSE 文本，把「不认识的 delta 一律 continue」钉住。
 */
class AiScheduleStreamTest {

    /** 读完整段 SSE，收集吐出的增量。 */
    private fun read(sse: String): List<StreamEvent> = runBlocking {
        val channel = Channel<StreamEvent>(Channel.UNLIMITED)
        val client = AiScheduleClient.create()
        withTimeout(5_000) {
            client.readStreamForTest(Buffer().apply { writeUtf8(sse) }, channel)
        }
        val events = mutableListOf<StreamEvent>()
        while (true) {
            val e = channel.tryReceive().getOrNull() ?: break
            events += e
        }
        channel.close()
        events
    }

    private fun List<StreamEvent>.texts() = filterIsInstance<StreamEvent.Content>().map { it.delta }
    private fun List<StreamEvent>.args() = filterIsInstance<StreamEvent.ToolArgs>().map { it.delta }
    private fun List<StreamEvent>.reasoning() = filterIsInstance<StreamEvent.Reasoning>().map { it.delta }

    private fun sseOf(vararg chunks: String): String =
        chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    @Test
    fun `先正文后工具参数的流不掐断`() {
        // V4.1-Flash 可能先吐一段正文再吐工具调用——旧实现会在第一条 content delta 就 return
        val events = read(
            sseOf(
                """{"choices":[{"delta":{"role":"assistant","content":"好的，正在对照课表。"}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"propose_schedule_changes","arguments":"{\"message\""}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":放假 8 天}"}}]}}]}""",
                """{"choices":[{"delta":{},"finish_reason":null}]}""",
            ),
        )
        assertEquals(listOf("好的，正在对照课表。"), events.texts())
        assertEquals(listOf("{\"message\"", ":放假 8 天}"), events.args())
    }

    @Test
    fun `finish 块与空 delta 不掐断`() {
        val events = read(
            sseOf(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            ),
        )
        assertEquals(1, events.args().size)
    }

    @Test
    fun `别的 index 与别的函数名跳过但不结束`() {
        // 并行 tool_call / 网关注入别的函数：跳过这些分片，继续收 0 号正主
        val events = read(
            sseOf(
                """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"other_tool","arguments":"{\"x\":1}"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"propose_schedule_changes","arguments":"{\"a\":1}"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"something_else","arguments":"{\"b\":2}"}}]}}]}""",
            ),
        )
        assertEquals(listOf("{\"a\":1}"), events.args())
    }

    @Test
    fun `reasoning 与 content 分别吐出`() {
        val events = read(
            sseOf(
                """{"choices":[{"delta":{"reasoning_content":"先看周次…"}}]}""",
                """{"choices":[{"delta":{"content":"结论。"}}]}""",
            ),
        )
        assertEquals(listOf("先看周次…"), events.reasoning())
        assertEquals(listOf("结论。"), events.texts())
    }

    @Test
    fun `非 SSE 响应抛错而不是当成功`() {
        val ex = runCatching {
            read("<html><body>Gateway Error</body></html>")
        }.exceptionOrNull()
        assertTrue(ex is AiScheduleClient.AiHttpException)
        assertTrue((ex as AiScheduleClient.AiHttpException).detail.contains("Gateway"))
    }

    @Test
    fun `finish_reason 为 length 抛错`() {
        val ex = runCatching {
            read(sseOf("""{"choices":[{"delta":{"content":"半截"},"finish_reason":"length"}]}"""))
        }.exceptionOrNull()
        assertTrue(ex is AiScheduleClient.AiHttpException)
    }
}
