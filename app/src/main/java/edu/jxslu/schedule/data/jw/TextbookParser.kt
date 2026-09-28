package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.Textbook
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 学生教材确认抓取（DESIGN §4.31）。
 *
 * 链路与成绩自动导入同一套：`GET /jsxsd/nxsjc/xsjcqr?xnxqid=<学期>&pageNum=&pageSize=`
 * 返回 layui JSON（`code=0` + `count` + `data[]`）。**只读抓取**——壳页里另有征订确认
 * 的 POST 接口（xsjcisxy.do），那是学生向教务确认订购的写操作，任何路径都不碰。
 *
 * 字段口径（2026-09-28 实测）：kcmc=课程名称（与课表课名逐字一致，直接当关联键）、
 * jcmc=教材名称、jczz=主编、cbsmc=出版社、jcbc=版次、isbn、jcdj=定价。
 * 没有教材名称的行（教务未定教材）丢弃。
 */
object TextbookParser {

    private val json = Json { ignoreUnknownKeys = true }

    const val PAGE_SIZE = 200

    /**
     * 教材数据接口的完整 URL（OkHttp 直抓用）。
     * 学期先过 [JwUrls.TERM_PATTERN] 白名单：会被拼进 URL，脏值一律拒绝（返回 null），
     * 与考试导入读学期的尺子一致。
     */
    fun listUrl(term: String, page: Int): String? {
        if (!JwUrls.TERM_PATTERN.matches(term)) return null
        return "${JwUrls.TEXTBOOK_LIST_API}?xnxqid=$term&pageNum=$page&pageSize=$PAGE_SIZE"
    }

    /** 一页解析结果：rows + 总数（翻页判定用）。 */
    data class TextbookPage(
        val count: Int,
        val books: List<Textbook>,
    )

    /** 解析一页接口 JSON；[term] 填进每条结果。失败抛 [IllegalStateException]（由同步器转成结果）。 */
    fun parseFetchJson(jsonText: String, term: String): TextbookPage {
        if (jsonText.startsWith("ERR:")) {
            throw IllegalStateException("请求失败：${jsonText.removePrefix("ERR:")}")
        }
        if ("系统功能暂未开放" in jsonText) {
            throw IllegalStateException("教务教材查询功能暂未开放（no-open 页）")
        }
        val root = try {
            json.parseToJsonElement(jsonText).jsonObject
        } catch (e: Exception) {
            throw IllegalStateException("返回的不是有效 JSON（可能未登录教务）：${e.message}")
        }
        val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        if (code != 0) {
            throw IllegalStateException("教务返回 code=$code，msg=${root["msg"]?.jsonPrimitive?.content ?: ""}")
        }
        val count = root["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val rows = root["data"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()
        return TextbookPage(count = count, books = rows.mapNotNull { rowToBook(it, term) })
    }

    private fun rowToBook(row: JsonObject, term: String): Textbook? {
        val course = row.str("kcmc") ?: return null
        val title = row.str("jcmc") ?: return null
        return Textbook(
            term = term,
            courseName = course,
            title = title,
            author = row.str("jczz").orEmpty(),
            press = row.str("cbsmc").orEmpty(),
            edition = row.str("jcbc").orEmpty(),
            isbn = row.str("isbn").orEmpty(),
            price = row.str("jcdj").orEmpty(),
        )
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
}
