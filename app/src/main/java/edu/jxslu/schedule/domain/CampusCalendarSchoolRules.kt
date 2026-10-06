package edu.jxslu.schedule.domain

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 教务处官网校历的取数规则（2026-10-07）——校历的**主源**。
 *
 * 事实源：`jw.juwp.edu.cn` 通知公告每学年发布「20XX-20XX学年校历」文章，正文挂高清原图
 * （泛微 CMS：`orisrc` 为编辑器原图，`src` 是缩放预览）。实测**免登录、公网可直取**——
 * 比「PC 脚本 + 仓库镜像」少一整条维护面，App 冷启动/手动刷新时直接读它。
 * 解析失败由 Store 落仓库镜像兜底、再兜底内置图。
 *
 * 为什么不用教务系统：强智教务只有「教学周历查看」（周次 × 日期表，需登录，且不是海报
 * 内容）——见 2026-10-07 排查。
 *
 * 本对象只放纯逻辑（拼地址、解析、定文件名），网络在
 * [edu.jxslu.schedule.data.repo.CampusCalendarStore]。纯 Kotlin，JVM 可测。
 */
object CampusCalendarSchoolRules {

    const val BASE = "https://jw.juwp.edu.cn"

    /**
     * 通知公告按页地址（页序 = 时间倒序）。只列前 3 页：新发布的「学年校历」必在第一页，
     * 翻页只是防它被后续公告挤走。
     */
    fun listUrls(): List<String> = listOf(
        "$BASE/xxgk/tzgg.htm",
        "$BASE/xxgk/tzgg/1.htm",
        "$BASE/xxgk/tzgg/2.htm",
    )

    private val YEAR_RE = Regex("""(20\d{2})-(20\d{2})学年校历""")

    /** 列表页条目：`<a href="../info/1040/10376.htm"><h3>江西水利电力大学2026-2027学年校历</h3>`。 */
    private val LINK_RE = Regex(
        """href="([^"]+)"[^>]*>\s*(?:<[^>]+>\s*)?[^<]{0,150}?20\d{2}-20\d{2}学年校历""",
    )

    private val ORISRC_RE = Regex("""orisrc="([^"]+)"""")
    private val LOCAL_SRC_RE = Regex("""src="(/__local/[^"]+)"""")

    /**
     * 解析列表页：返回页面里所有「学年校历」文章 (绝对 URL, 学年)。可能跨年多条，
     * 由调用方取学年最大者（又：页序即时间倒序，第一处命中的一页已含最新一条）。
     */
    fun parseArticleLinks(html: String, pageUrl: String): List<Pair<String, String>> =
        LINK_RE.findAll(html).mapNotNull { m ->
            val year = YEAR_RE.find(m.value) ?: return@mapNotNull null
            val url = pageUrl.toHttpUrlOrNull()?.resolve(m.groupValues[1])?.toString()
                ?: return@mapNotNull null
            url to "${year.groupValues[1]}-${year.groupValues[2]}"
        }.toList()

    /** 多条命中里取学年最大的条目（"2026-2027" 与 "2025-2026" 字符串序即年份序）。 */
    fun latest(links: List<Pair<String, String>>): Pair<String, String>? =
        links.maxByOrNull { it.second }

    /** 解析文章页：取高清原图 URL（优先 orisrc，回落 __local 的 src）；失败返回 null。 */
    fun parseImageUrl(html: String, articleUrl: String): String? {
        val raw = ORISRC_RE.find(html)?.groupValues?.get(1)
            ?: LOCAL_SRC_RE.find(html)?.groupValues?.get(1)
            ?: return null
        return articleUrl.toHttpUrlOrNull()?.resolve(raw)?.toString()
    }

    /** 由图片 URL 定本地/缓存文件名：`calendar_<year>.<jpg|png>`（与镜像口径一致）。 */
    fun localFileName(year: String, imageUrl: String): String {
        val ext = if (imageUrl.endsWith(".png", ignoreCase = true)) "png" else "jpg"
        return "calendar_$year.$ext"
    }
}
