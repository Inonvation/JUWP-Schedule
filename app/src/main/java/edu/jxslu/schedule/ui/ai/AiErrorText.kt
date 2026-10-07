package edu.jxslu.schedule.ui.ai

import edu.jxslu.schedule.data.ai.AiScheduleClient
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * AI 调课的网络失败 → 用户可读文案（DESIGN §4.39）。
 *
 * 只做「分类 + 一句话」：不重试、不吞错。401/404 是最常见的两种配错
 * （Key 写错 / 地址少了 `/v1`），单独点名。
 */
internal fun aiErrorText(t: Throwable): String = when (t) {
    is AiScheduleClient.AiHttpException -> when (t.code) {
        401, 403 -> "API Key 无效或没有权限（HTTP ${t.code}）"
        404 -> "接口地址不对（HTTP 404）：检查服务地址是否以 /v1 结尾"
        429 -> "请求过于频繁或额度不足（HTTP 429）"
        // DeepSeek 对不存在的模型名回 400 "Model Not Exist"——历史上预填的
        // 「deepseek-flash」就栽在这，点名官方现行模型名让用户一步改对
        400 -> if (t.detail.contains("model", ignoreCase = true)) {
            "模型名不被该服务接受（HTTP 400）：检查「配置」里的模型名，或点「从上游获取」选一个"
        } else {
            "服务返回 HTTP 400：${t.detail.take(120)}"
        }
        0 -> "服务返回了无法识别的响应：${t.detail.take(120)}"
        else -> "服务返回 HTTP ${t.code}：${t.detail.take(120)}"
    }
    is UnknownHostException -> "无法解析服务地址，检查网络或地址拼写"
    // SocketTimeoutException 是 InterruptedIOException 的子类，必须排在前面
    is SocketTimeoutException -> "请求超时，稍后再试"
    // okhttp 的 callTimeout 触发时抛的就是 InterruptedIOException（不是 SocketTimeout），
    // 不单独认它的话用户看到的是「请求失败：InterruptedIOException」
    is InterruptedIOException -> "上游长时间没结束（超时），稍后再试"
    else -> "请求失败：${t.javaClass.simpleName}"
}
