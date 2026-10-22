package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.InternshipBase
import kotlinx.serialization.json.JsonObject

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
        // count 缺失按 -1（未知）收：当 0 会把「没拿齐」静默吞掉；装载层对 -1 不做差额判定
        val root = JwLayuiJson.parseRoot(jsonText, "实习基地查询", countDefault = -1)
        return Page(count = root.count, bases = root.rows.mapNotNull { rowToBase(it) })
    }

    private fun rowToBase(row: JsonObject): InternshipBase? {
        val name = row.strOrNull("sxjdmc") ?: return null
        return InternshipBase(
            id = row.strOrNull("sj0604id").orEmpty(),
            no = row.strOrNull("sxjdbh").orEmpty(),
            name = name,
            category = row.strOrNull("dmmc").orEmpty(),
            unit = row.strOrNull("sxdwmc").orEmpty(),
            leader = row.strOrNull("sxjdfzr").orEmpty(),
            address = row.strOrNull("sxjddz").orEmpty(),
            phone = row.strOrNull("sxjdlxdh").orEmpty(),
            builtDate = row.strOrNull("jcsj").orEmpty(),
            listedYears = row.strOrNull("gpsx").orEmpty(),
            capacity = row.strOrNull("rnrs").orEmpty(),
            campusContact = row.strOrNull("xnlxr").orEmpty(),
            campusPhone = row.strOrNull("xnlxrdh").orEmpty(),
            college = row.strOrNull("dwmc").orEmpty(),
            intro = row.strOrNull("sxjdjj").orEmpty(),
        )
    }
}
