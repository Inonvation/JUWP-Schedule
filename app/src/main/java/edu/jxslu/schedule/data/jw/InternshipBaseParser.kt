package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.InternshipBase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 实习基地数据解析（DESIGN §4.43）。
 *
 * 链路：壳页 `/jsxsd/sxsxgl/sxjdcx.do` 只是 layui 前端壳，数据在
 * `GET /jsxsd/sxsxgl/sxjd.do`（layui JSON：`code=0` + `count` + `data[]`）。
 * **该接口忽略一切分页参数、固定只回前 10 条**（2026-10-07 实测，详见 [listUrl]）——
 * 单次抓取，差额由界面注明；别按页循环（会拿回重复页，见 `InternshipBasesLoader`）。
 *
 * 三道检查照 `TextbookParser`：失败前缀、no-open 页、非 JSON（未登录时是 HTML 登录页）、
 * `code != 0`。记录缺「基地名称」的丢弃（没名字的行没有展示意义）。
 */
object InternshipBaseParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** 一页解析结果：rows + 教务声明的总数（翻页判定用）。 */
    data class Page(
        val count: Int,
        val bases: List<InternshipBase>,
    )

    /**
     * 每页条数。**服务端当前忽略该参数**（见 [listUrl]），带上只为与教务页面
     * 自己的请求形状一致。
     */
    const val PAGE_SIZE = 20

    /**
     * 数据接口 URL。
     *
     * **2026-10-07 实测：该接口忽略一切分页参数**——`page` / `pageNum` / `pageSize` /
     * `limit`（GET / POST / 大小写混排 / 浏览器请求头，全形态）都返回**同一页前 10 条**，
     * 而 `count` 声明 17；同会话下成绩接口（`cjcx_list`）的 `pageNum=2` 翻页正常
     * （10 → 6 条），说明不是参数名问题，是该接口固定只回首页。所以同步层只发一次请求、
     * 拿到什么展示什么，差额由界面如实注明（见 `InternshipBasesLoader`）。
     * 仍按教务 qzTable 的口径带 `pageNum` / `pageSize`：哪天教务修好翻页，这里不用再动。
     */
    fun listUrl(page: Int): String =
        "${JwUrls.INTERNSHIP_BASES_API}?pageNum=$page&pageSize=$PAGE_SIZE"

    /** 解析一页接口 JSON；失败抛 [IllegalStateException]（由装载器转成结果）。 */
    fun parsePage(jsonText: String): Page {
        if (jsonText.startsWith("ERR:")) {
            throw IllegalStateException("请求失败：${jsonText.removePrefix("ERR:")}")
        }
        if ("系统功能暂未开放" in jsonText) {
            throw IllegalStateException("教务实习基地查询功能暂未开放（no-open 页）")
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
        // count 缺失按 -1（未知）收：当 0 会把「没拿齐」静默吞掉；装载层对 -1 不做差额判定
        val count = root["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        // data 可能是 JSON null（不是缺键）：`.jsonArray` 对 JsonNull 会抛裸 IllegalArgumentException
        // （英文，且不在上面的 try 里）——用 as? 收成空列表
        val rows = (root["data"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        return Page(count = count, bases = rows.mapNotNull { rowToBase(it) })
    }

    private fun rowToBase(row: JsonObject): InternshipBase? {
        val name = row.str("sxjdmc") ?: return null
        return InternshipBase(
            id = row.str("sj0604id").orEmpty(),
            no = row.str("sxjdbh").orEmpty(),
            name = name,
            category = row.str("dmmc").orEmpty(),
            unit = row.str("sxdwmc").orEmpty(),
            leader = row.str("sxjdfzr").orEmpty(),
            address = row.str("sxjddz").orEmpty(),
            phone = row.str("sxjdlxdh").orEmpty(),
            builtDate = row.str("jcsj").orEmpty(),
            listedYears = row.str("gpsx").orEmpty(),
            capacity = row.str("rnrs").orEmpty(),
            campusContact = row.str("xnlxr").orEmpty(),
            campusPhone = row.str("xnlxrdh").orEmpty(),
            college = row.str("dwmc").orEmpty(),
            intro = row.str("sxjdjj").orEmpty(),
        )
    }

    /**
     * 取字符串字段：缺失 / JSON null / 非原始值都按空处理。
     * 教务对空值可能回 JSON null（不是缺键）——JsonNull 虽继承 JsonPrimitive，但 `.content`
     * 会给出字面 `"null"`，不判掉就会被当成真实值（别删这道守卫）。
     */
    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.content?.trim()
            ?.takeIf { it.isNotEmpty() }
}
