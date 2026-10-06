package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务处官网校历取数（[CampusCalendarSchoolRules]）：列表解析 / 文章取图 / 文件名。
 * fixture 照 2026-10-07 实测页面结构（`jw.juwp.edu.cn` 泛微 CMS）。
 */
class CampusCalendarSchoolRulesTest {

    private val listPage = """
        <div class="tanr780li"><div class="tanr780lilf"><h3>06-23</h3><p>2026</p></div>
        <div class="tanr780lilrr"><a href="../info/1040/10376.htm"><h3>江西水利电力大学2026-2027学年校历</h3>
        <p>江西水利电力大学2026-2027学年校历</p></a></div></div>
        <div class="tanr780li"><div class="tanr780lilf"><h3>01-29</h3><p>2025</p></div>
        <div class="tanr780lilrr"><a href="../info/1040/8456.htm"><h3>江西水利电力大学2024-2025学年校历</h3>
        </a></div></div>
        <a href="../info/1043/10616.htm"><h3>校领导深入教学一线听课巡课并开展安...</h3></a>
    """.trimIndent()

    @Test
    fun `parse list page finds calendar articles with resolved urls`() {
        val hits = CampusCalendarSchoolRules.parseArticleLinks(
            listPage,
            "https://jw.juwp.edu.cn/xxgk/tzgg.htm",
        )
        assertEquals(2, hits.size)
        assertEquals("https://jw.juwp.edu.cn/info/1040/10376.htm" to "2026-2027", hits[0])
        // 相对路径 ../ 也要正确解析到根
        assertEquals("https://jw.juwp.edu.cn/info/1040/8456.htm" to "2024-2025", hits[1])
    }

    @Test
    fun `latest picks the max year`() {
        val hits = CampusCalendarSchoolRules.parseArticleLinks(
            listPage,
            "https://jw.juwp.edu.cn/xxgk/tzgg.htm",
        )
        assertEquals("2026-2027", CampusCalendarSchoolRules.latest(hits)!!.second)
        assertNull(CampusCalendarSchoolRules.latest(emptyList()))
    }

    @Test
    fun `parse article prefers orisrc original over preview src`() {
        val article = """
            <img src="/__local/0/79/B5/D579139626E410047658495468E_753E74C9_95F1D.jpg"
                 width="641" vheight=" 512px" height="512"
                 orisrc="/__local/8/94/F4/C94A19B98873BA0BAD2F53CF710_F4B911C7_11D405.jpg"
                 class="img_vsb_content">
        """.trimIndent()
        assertEquals(
            "https://jw.juwp.edu.cn/__local/8/94/F4/C94A19B98873BA0BAD2F53CF710_F4B911C7_11D405.jpg",
            CampusCalendarSchoolRules.parseImageUrl(article, "https://jw.juwp.edu.cn/info/1040/10376.htm"),
        )
    }

    @Test
    fun `parse article falls back to local src and rejects no-image pages`() {
        val articleOnlySrc = """<img src="/__local/aa/bb/xxx_11D405.jpg" class="img_vsb_content">"""
        assertEquals(
            "https://jw.juwp.edu.cn/__local/aa/bb/xxx_11D405.jpg",
            CampusCalendarSchoolRules.parseImageUrl(articleOnlySrc, "https://jw.juwp.edu.cn/info/1040/1.htm"),
        )
        assertNull(
            CampusCalendarSchoolRules.parseImageUrl(
                "<p>没有图片的公告</p><img src=\"../../images/nybanner.jpg\">",
                "https://jw.juwp.edu.cn/info/1040/1.htm",
            ),
        )
    }

    @Test
    fun `local file name derives from year and image suffix`() {
        assertEquals(
            "calendar_2026-2027.jpg",
            CampusCalendarSchoolRules.localFileName("2026-2027", "https://x/__local/a/b/c.jpg"),
        )
        assertEquals(
            "calendar_2027-2028.png",
            CampusCalendarSchoolRules.localFileName("2027-2028", "https://x/__local/a/b/c.PNG"),
        )
        assertTrue(CampusCalendarSchoolRules.listUrls().first().endsWith("/xxgk/tzgg.htm"))
    }
}
