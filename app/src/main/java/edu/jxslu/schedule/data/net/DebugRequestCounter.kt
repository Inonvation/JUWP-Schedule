package edu.jxslu.schedule.data.net

import android.util.Log
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.EventListener

/**
 * debug 包的请求计数（2026-10-22 体检）：只按 host 计数并打 logcat，不记录 URL/query。
 *
 * 用途是验证「冷启动少发几条请求」「侧栏展开才取余额」这类改动，release 不挂。
 */
internal object DebugRequestCounter : EventListener() {
    private const val TAG = "AppHttp"

    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    override fun callEnd(call: Call) {
        val host = call.request().url.host
        val total = counts.getOrPut(host) { AtomicInteger() }.incrementAndGet()
        Log.d(TAG, "request host=$host total=$total")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        // callFailed 之后仍会走到 callEnd，计数只在那里做，避免一条失败请求算两次。
        Log.d(TAG, "request failed host=${call.request().url.host} error=${ioe.javaClass.simpleName}")
    }
}
