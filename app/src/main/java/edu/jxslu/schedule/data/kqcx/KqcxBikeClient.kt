package edu.jxslu.schedule.data.kqcx

import edu.jxslu.schedule.domain.BikeFailure
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 快趣出行「附近车辆」接口客户端（DESIGN §4.23）。裸 OkHttp，一条 form POST，
 * 只做请求与失败分类；响应怎么解析交给 `domain/BikeNearby`（纯 JVM 可测）。
 *
 * 红线：接口不需要鉴权，因此**没有任何凭证**可发；不代客开锁、不触碰计费与签到。
 * 请求里也只有坐标与 `deviceType`，不含账号或设备标识。
 */
class KqcxBikeClient private constructor(private val http: OkHttpClient) {

    companion object {
        const val ENDPOINT =
            "https://api.kvcoogo.com/ManagerApi/api/v1.0.0/queryNearbyCar"

        /** 车队类型：实测只有 `1`（电单车）在返回车辆。 */
        private const val DEVICE_TYPE = "1"

        /** 上游参考实现用的标识。UA 不影响鉴权，写自己包名，别冒充别人的客户端。 */
        private const val UA = "edu.jxslu.schedule"

        /** 一次拖动要撒 8 个采样点，OkHttp 默认「同域名 5 并发」会把它们分两批发。 */
        private const val MAX_REQUESTS_PER_HOST = 12

        private val FORM_MEDIA_TYPE =
            "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

        fun create(): KqcxBikeClient = KqcxBikeClient(
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                // 撒点一次性发完：请求都很小，分两批只是白多一个往返
                .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST })
                .build(),
        )

        /**
         * 异常 → 失败类别。纯函数，JVM 可测。
         *
         * 超时必须是 [SocketTimeoutException]（OkHttp 连接与读取超时都抛它，属于
         * [IOException] 的子类），所以判断顺序不能反——反了超时会被吃成「网络不可达」，
         * 用户按提示反复检查网络也修不好。
         */
        fun classify(error: Throwable): BikeFailure = when (error) {
            is SocketTimeoutException -> BikeFailure.Timeout
            is ServiceHttpException -> BikeFailure.Service
            is IOException -> BikeFailure.Network
            // 意料之外的异常按网络失败提示：用户至少知道"没查到"并能重试。
            // 真出这种事是代码 bug，不该悄悄吞掉（VM 侧只提示、不上报，日志里能看到堆栈）
            else -> BikeFailure.Network
        }
    }

    /**
     * 查 [lat] / [lng] 附近的车辆，返回响应原文。
     *
     * HTTP 状态码非 2xx 与网络异常都抛 [IOException]（前者抛 [ServiceHttpException]），
     * 由调用方经 [classify] 归类；不自动重试——地图页有刷新按钮，重试交给用户。
     */
    suspend fun queryNearbyJson(lat: Double, lng: Double): String {
        val body = "lat=$lat&lng=$lng&deviceType=$DEVICE_TYPE".toRequestBody(FORM_MEDIA_TYPE)
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", UA)
            .post(body)
            .build()
        return executeCancellable(request)
    }

    /**
     * **可取消**的请求（2026-09-28，地图页拖动优化）。
     *
     * 旧写法 `execute()` 是阻塞调用，协程取消**中断不了它**：拖动时一次接一次地重查，
     * 前一批撒点还占着连接（同域名并发上限），新查询排在它们后面才发出——越是急着换地方看，
     * 越慢。改成 `enqueue` + `invokeOnCancellation { call.cancel() }`：协程一取消就断连，
     * 连接立刻让给新的查询。
     */
    private suspend fun executeCancellable(request: Request): String =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    var failure: IOException? = null
                    var text: String? = null
                    try {
                        response.use { res ->
                            if (!res.isSuccessful) throw ServiceHttpException(res.code)
                            text = res.body?.string().orEmpty()
                        }
                    } catch (error: IOException) {
                        failure = error
                    }
                    if (cont.isCancelled) return
                    val body = text
                    if (body != null) {
                        cont.resume(body)
                    } else {
                        cont.resumeWithException(failure ?: IOException("附近车辆接口无响应"))
                    }
                }
            })
        }

    /** 服务端返回了非 2xx。属于 [IOException]，好让调用方只用 try/catch 一层。 */
    class ServiceHttpException(val httpCode: Int) : IOException("附近车辆接口返回 $httpCode")
}
