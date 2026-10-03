package edu.jxslu.schedule.data.net


import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient

import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * 进程共享的 OkHttp base client（2026-10-02，体检第三批）。
 *
 * 此前 10 个模块各自 `OkHttpClient.Builder().build()`——每个自带一套连接池与 Dispatcher
 * 线程池（默认各 5 空闲线程），空闲内存与线程数成倍翻。收敛成**一份 base + `newBuilder()`
 * 派生**：连接池、线程池、DNS 缓存全进程共享，各模块只覆盖自己的超时 / 拦截器 / 重定向策略。
 *
 * 派生纪律（改任何 client 前读）：
 * - **只加不改共享件**：超时、拦截器、dispatcher、followRedirects 随便覆盖；别动
 *   connectionPool / cache 参数——它们是「共享」的本体，动了就退化回各自为政。
 * - **callTimeout 逐链路加**：读表 / 下单 / 支付这类「慢响应体可挂死」的接口在各自
 *   派生处补 `callTimeout`（默认 base 不加——教务长页面下载可能合法超 60s，一刀切
 *   会误杀）。
 * - **evictAll 注意**：`JwHttpSession` 会清连接池；共享池后这一清会把全 App 的连接
 *   一起清掉——它只在会话销毁时调用、清掉也只是重连成本，已评估可接受，但新增
 *   evictAll 调用点前先想想。
 */
object AppHttp {

    /**
     * IPv4 优先 DNS（原 `JwHttpSession.Ipv4FirstDns` 收编为全进程件）：
     * 校园域名同时有 A 与 AAAA 记录，而校园 IPv6 在移动网络下实测是黑
     * （连接要等 TCP 超时才落回 v4，单步多等十几到三十秒）。
     */
    val Ipv4FirstDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            ipv4First(Dns.SYSTEM.lookup(hostname))
    }

    /** v4 排前、v6 保留原序殿后；无 v4 时原样返回。 */
    fun ipv4First(all: List<InetAddress>): List<InetAddress> {
        val v4 = all.filterIsInstance<Inet4Address>()
        if (v4.isEmpty()) return all
        return v4 + (all - v4.toSet())
    }

    /**
     * base client：零业务配置，IPv4 优先 + 超时兜底 + **宽松总上限**。
     * `callTimeout(120s)` 是全进程的总上限兜底（终审修正）：readTimeout 只管两次读的
     * 间隔，慢滴流响应体可以让它无限重置；120s 不会掐任何合法请求（最长链路
     * TranscriptClient 出单 readTimeout 60s 也罩得住），只封死「无限挂死」。
     * 各模块按需收紧（读表/下单/支付 30s），见各派生处。
     */
    val base: OkHttpClient = OkHttpClient.Builder()
        .dns(Ipv4FirstDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 常用派生快捷式：默认超时 + callTimeout（下单/支付这类要总上限的链路用）。 */
    fun withCallTimeout(seconds: Long): OkHttpClient =
        base.newBuilder().callTimeout(seconds, TimeUnit.SECONDS).build()

    /** Dispatcher 派生快捷式（单 host 高并发的撒点类请求用）。 */
    fun withMaxRequestsPerHost(max: Int): OkHttpClient =
        base.newBuilder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = max })
            .build()

    /** 拦截器派生快捷式（Retrofit 系模块的头部注入用；可变参顺序即 addInterceptor 顺序）。 */
    fun withInterceptors(vararg interceptors: Interceptor): OkHttpClient =
        base.newBuilder().apply { interceptors.forEach { addInterceptor(it) } }.build()
}
