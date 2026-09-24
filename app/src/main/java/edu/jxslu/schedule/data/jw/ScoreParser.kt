package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.ScoreRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 课程成绩抓取（DESIGN §4.15）。
 *
 * 链路与考试一致：WebView 在成绩查询页 `/jsxsd/kscj/cjcx_frm`（或教务任意页面）注入
 * [FETCH_JS] 同源 `fetch` `/jsxsd/kscj/cjcx_list`（**不带 .do**，带 .do 是 no-open 页），
 * `kksj` 传空 = 全部学期。分页 `pageNum/pageSize`；`count` 超过单页时由调用方翻页。
 */
object ScoreParser {

    private val json = Json { ignoreUnknownKeys = true }

    const val PAGE_SIZE = 200

    /**
     * 注入脚本：[__TERM__] 为学期（空串 = 全部学期）、[__PAGE__] 为页码。
     * `kcxz/kcsx/kcmc/xsfs` 留空即不筛选。结果写 `window.__qzJson`。
     */
    val FETCH_JS: String = """
(function(){
  try {
    window.__qzJson = null;
    var url = '/jsxsd/kscj/cjcx_list?kksj=' + encodeURIComponent('__TERM__')
            + '&kcxz=&kcsx=&kcmc=&xsfs=&pageNum=__PAGE__&pageSize=$PAGE_SIZE';
    fetch(url, { credentials: 'same-origin' })
      .then(function(r){ return r.text(); })
      .then(function(t){ window.__qzJson = t; })
      .catch(function(e){ window.__qzJson = 'ERR:' + String(e); });
  } catch (e) {
    window.__qzJson = 'ERR:' + String(e);
  }
})()
""".trim()

    val READ_RESULT_JS: String = "window.__qzJson === null ? '' : String(window.__qzJson)"

    fun fetchJs(term: String, page: Int): String =
        FETCH_JS.replace("__TERM__", term).replace("__PAGE__", page.toString())

    /** 一页解析结果：rows + 总数（翻页判定用）。 */
    data class ScorePage(
        val count: Int,
        val records: List<ScoreRecord>,
    )

    /** 解析一页接口 JSON；失败抛 [IllegalStateException]（由 UI 转成可读提示）。 */
    fun parseFetchJson(jsonText: String): ScorePage {
        if (jsonText.startsWith("ERR:")) {
            throw IllegalStateException("请求失败：${jsonText.removePrefix("ERR:")}")
        }
        if ("系统功能暂未开放" in jsonText) {
            throw IllegalStateException("教务成绩查询功能暂未开放（no-open 页）")
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
        return ScorePage(count = count, records = rows.mapNotNull { rowToRecord(it) })
    }

    private fun rowToRecord(row: JsonObject): ScoreRecord? {
        val name = row.str("kc_mc")
        if (name.isNullOrBlank()) return null
        return ScoreRecord(
            term = row.str("xnxqid").orEmpty(),
            courseNo = row.str("kch").orEmpty(),
            name = name,
            unit = row.str("ksdw").orEmpty(),
            credit = row.dbl("xf") ?: 0.0,
            hours = row.dbl("zxs") ?: 0.0,
            examForm = row.str("ksfs").orEmpty(),
            courseAttr = row.str("kcsx").orEmpty(),
            category = row.str("kcxzmc").orEmpty(),
            score = row.dbl("zcj"),
            scoreStr = row.str("zcjstr").orEmpty(),
            gradePoint = row.dbl("jd"),
            status = row.str("ksxz").orEmpty(),
            pendingReview = row.str("kz") == "1",
        )
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.dbl(key: String): Double? = try {
        this[key]?.jsonPrimitive?.content?.toDoubleOrNull()
    } catch (_: Exception) {
        null
    }

    /**
     * 学籍卡片解析（「我的」账号条数据源，DESIGN §3.3）。
     *
     * 姓名走 form 的 `<label>姓名</label>…<input value="…">`（form 里没有班级/学号两项）；
     * 班级与学号走顶栏 `detiailtextItem` 明文标签（`班级：xxx` / `学号：xxx`）。
     * 标签与值的顺序按页面结构写死，解析失败返回 null 不抛。
     * 任何字段解析失败都返回 null，**不抛**——账号条只是锦上添花，绝不能挡成绩导入。
     */
    fun parseStudentCard(html: String): StudentCard? {
        fun inputAfter(label: String): String? {
            val escaped = Regex.escape(label)
            val re = Regex(
                "(?is)<label[^>]*>\\s*$escaped\\s*</label>[\\s\\S]{0,400}?value\\s*=\\s*\"([^\"]*)\"",
            )
            return re.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        }

        fun detailItem(label: String): String? {
            val escaped = Regex.escape(label)
            val re = Regex(
                "(?is)detiailtextItem[^>]*>\\s*$escaped\\s*[：:]\\s*([^<]+?)\\s*<",
            )
            return re.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        }

        val name = inputAfter("姓名") ?: return null
        val studentClass = detailItem("班级") ?: return null
        val studentId = detailItem("学号") ?: return null
        return StudentCard(name = name, studentClass = studentClass, studentId = studentId)
    }
}

/** 学籍卡片里账号条需要的三个字段（DESIGN §3.3）。 */
data class StudentCard(
    val name: String,
    val studentClass: String,
    val studentId: String,
)
