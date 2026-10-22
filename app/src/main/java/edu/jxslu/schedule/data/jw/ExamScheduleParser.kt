package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.ExamMapper.ExamEntry
import kotlinx.serialization.json.JsonObject

/**
 * 考试安排抓取（DESIGN §4.14）。
 *
 * 链路：WebView 已在考试安排壳页 `/jsxsd/xsks/xsksap_query` 后，注入 [FETCH_JS] 用同源
 * `fetch` 请求数据接口 `/jsxsd/xsks/xsksap_list`（**不带 .do**），结果写入 `window.__qzJson`，
 * Kotlin 侧轮询读取（evaluateJavascript 不会 await Promise，轮询是最可靠的回收方式）。
 * 分页参数是 `pageNum/pageSize`（强智 initQzTable 自定义），不是 page/limit。
 */
object ExamScheduleParser {

    /** 单页大小：单学期考试远小于 200，超此数由调用方翻页。 */
    const val PAGE_SIZE = 200

    /**
     * 注入脚本：学期由 Kotlin 侧先用 [READ_TERM_JS] 读出后填进 [__TERM__]（与页面展示一致），
     * [__PAGE__] 为页码。结果写入 `window.__qzJson`（字符串或 "ERR:…"）。
     */
    val FETCH_JS: String = """
(function(){
  try {
    window.__qzJson = null;
    var url = '/jsxsd/xsks/xsksap_list?xnxqid=' + encodeURIComponent('__TERM__')
            + '&xqlb=&pageNum=__PAGE__&pageSize=$PAGE_SIZE';
    fetch(url, { credentials: 'same-origin' })
      .then(function(r){ return r.text(); })
      .then(function(t){ window.__qzJson = t; })
      .catch(function(e){ window.__qzJson = 'ERR:' + String(e); });
  } catch (e) {
    window.__qzJson = 'ERR:' + String(e);
  }
})()
""".trim()

    fun fetchJs(term: String, page: Int): String =
        FETCH_JS.replace("__TERM__", term).replace("__PAGE__", page.toString())

    /** [FETCH_JS] 抓完一轮后读取结果的探针。 */
    val READ_RESULT_JS: String = "window.__qzJson === null ? '' : String(window.__qzJson)"

    /** 从壳页学期下拉读当前选中项（value，如 2026-2027-1）；不在壳页时为空串。 */
    val READ_TERM_JS: String =
        "(function(){var s=document.querySelector('select#xnxqid');" +
            "return (s&&s.selectedIndex>=0&&s.options)?(s.options[s.selectedIndex].value||" +
            "s.options[s.selectedIndex].textContent||''):'';})()"

    data class ExamFetch(
        val term: String?,
        val count: Int,
        val exams: List<ExamEntry>,
    )

    /** 解析注入 fetch 返回的 JSON；接口层失败（no-open / code!=0 / 网络错）抛 [IllegalStateException]。 */
    fun parseFetchJson(jsonText: String): ExamFetch {
        val root = JwLayuiJson.parseRoot(jsonText, "考试查询")
        return ExamFetch(
            term = root.json.strOrNull("term"),
            count = root.count,
            exams = root.rows.mapNotNull { rowToEntry(it) },
        )
    }

    /** 接口原始行 → [ExamEntry]；kssj（"2026-05-18 08:30~09:55"）在这里拆成日期与起止时刻。 */
    private fun rowToEntry(row: JsonObject): ExamEntry? {
        val name = row.strOrNull("kskcmc")
        if (name.isNullOrBlank()) return null
        val raw = row.strOrNull("kssj").orEmpty()
        val m = TIME_RE.find(raw)
        return ExamEntry(
            courseNo = row.strOrNull("kch").orEmpty(),
            name = name,
            teacher = row.strOrNull("jsxm").orEmpty(),
            room = row.strOrNull("js_mc").orEmpty(),
            campus = (row.strOrNull("ksxq") ?: row.strOrNull("xqmc")).orEmpty(),
            date = m?.groupValues?.get(1).orEmpty(),
            startTime = m?.groupValues?.get(2).orEmpty(),
            endTime = m?.groupValues?.get(3).orEmpty(),
            seatNo = row.strOrNull("zwh").orEmpty(),
            sessionNo = row.strOrNull("ksccmc").orEmpty(),
        )
    }

    private val TIME_RE = Regex("""(\d{4}-\d{2}-\d{2})\s+(\d{1,2}:\d{2})\s*[~～\-—]\s*(\d{1,2}:\d{2})""")
}
