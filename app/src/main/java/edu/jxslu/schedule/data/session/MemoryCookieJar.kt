package edu.jxslu.schedule.data.session

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 进程内 CookieJar（DESIGN §4.27）。
 *
 * 从 `JwHttpSession` 的私有内部类提出来：会话要跨任务常驻，不能再随某个
 * 「用完即弃」的 client 一起丢掉。按 host 分桶——CAS（`eapp2`）、教务（`jiaowu`）、
 * 签章（`jwxyxx`）的 cookie 同名也不互相污染。
 */
class MemoryCookieJar : CookieJar {

    private val store = HashMap<String, HashMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(store) {
            val bucket = store.getOrPut(url.host) { HashMap() }
            cookies.forEach { bucket[it.name] = it }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) {
        store[url.host]?.values?.filter { it.matches(url) } ?: emptyList()
    }

    /** 全量快照，用于注入 WebView。 */
    fun snapshot(): List<Cookie> = synchronized(store) { store.values.flatMap { it.values } }

    /**
     * 回灌：用户在 WebView 里手登之后，把 `CookieManager` 的 cookie 抄回来。
     *
     * 分桶键用 `domain` 去掉前导点（OkHttp 的 `Cookie.domain` 无前导点，而
     * `Set-Cookie` 常带），保证回灌后再 `loadForRequest` 能命中同一个桶。
     */
    fun adopt(cookies: List<Cookie>) {
        synchronized(store) {
            cookies.forEach { cookie ->
                val host = cookie.domain.removePrefix(".")
                store.getOrPut(host) { HashMap() }[cookie.name] = cookie
            }
        }
    }

    fun clear() = synchronized(store) { store.clear() }

    fun isEmpty(): Boolean = synchronized(store) { store.values.all { it.isEmpty() } }

    fun hosts(): Set<String> = synchronized(store) {
        store.filterValues { it.isNotEmpty() }.keys.toSet()
    }
}
