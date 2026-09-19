package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.ScheduleCalculator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 解析强智 newL 课表页 DOM。
 * 星期必须按 tbody 中 td[name=kbDataTd] 的列序 1–7 取，不能用 li.qz-hasCourse-N
 * （实测该 class 几乎恒为 1）。
 */
object QiangzhiScheduleParser {

    data class RawItem(
        val name: String,
        val detail: String,
        val day: Int,
    )

    /**
     * WebView 注入脚本：按表格列序抽课程。
     *
     * 根因：星期只能从 `<td>` 在 `<tr>` 里的列序推（第 0 列是节次标签，第 1–7 列是周一至周日）。
     * `li.qz-hasCourse-N` 不能当星期来源——模板把它当「有课」样式用，实测课表页 33 处 -1、2 处 -3。
     *
     * 列号累加 `colspan`，并维护一张 rowspan 占用表：
     * 强智在「一天内连续两大节上同一门课」时会用 rowspan 合并单元格，
     * 被合并掉的列在后续行里不存在，不补偏移的话那一行之后的星期会整体前移。
     */
    const val EXTRACT_JS: String = """
(function(){
  try {
    var out = [];
    // 学期口径：取学期下拉的当前选中项（带 xnxq01id 参数重载后服务端会渲染对应 selected）
    var term = '';
    var termSel = document.querySelector('select#xnxq01id');
    if (termSel && termSel.selectedIndex >= 0 && termSel.options) {
      term = (termSel.options[termSel.selectedIndex].textContent || '').trim();
    }
    var rows = document.querySelectorAll('tbody tr');
    var carry = {};
    for (var r = 0; r < rows.length; r++) {
      for (var key in carry) {
        var left = carry[key] - 1;
        if (left <= 0) { delete carry[key]; } else { carry[key] = left; }
      }
      var cells = rows[r].children;
      var col = 0;
      for (var c = 0; c < cells.length; c++) {
        var td = cells[c];
        while (carry[col] > 0) { col++; }
        var colspan = parseInt(td.getAttribute('colspan') || '1', 10) || 1;
        var rowspan = parseInt(td.getAttribute('rowspan') || '1', 10) || 1;
        if (rowspan > 1) { carry[col] = rowspan; }
        if (td.getAttribute('name') === 'kbDataTd' && col >= 1 && col <= 7) {
          var day = col;
          var items = td.querySelectorAll('li.courselists-item');
          for (var i = 0; i < items.length; i++) {
            var li = items[i];
            var nameEl = li.querySelector('.qz-hasCourse-title');
            var detailEl = li.querySelector('.qz-hasCourse-abbrinfo');
            var name = nameEl ? (nameEl.textContent || '').replace(/\s+/g, ' ').trim() : '';
            var detail = detailEl ? (detailEl.textContent || '').replace(/\s+/g, ' ').trim() : '';
            if (name) { out.push({ name: name, detail: detail, day: day }); }
          }
        }
        col += colspan;
      }
    }
    return JSON.stringify({ ok: true, items: out, term: term, title: document.title || '', url: location.href });
  } catch (e) {
    return JSON.stringify({ ok: false, error: String(e) });
  }
})()
"""

    fun parseExtractJson(json: String): ImportParseResult {
        return try {
            val items = decodeExtractJson(json)
            if (items.isEmpty()) {
                ImportParseResult.Failure(
                    "当前页面未解析到课程。请先打开「学期理论课表」，再点导入。",
                )
            } else {
                var failed = 0
                val courses = items.mapNotNull { toCourse(it).also { c -> if (c == null) failed++ } }
                if (courses.isEmpty()) {
                    ImportParseResult.Failure(
                        "解析到 ${items.size} 条，但周次/星期字段不完整（失败 $failed 条）",
                    )
                } else {
                    ImportParseResult.Success(courses, term = extractTermField(json))
                }
            }
        } catch (e: Exception) {
            ImportParseResult.Failure("解析失败：${e.message}")
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    internal fun decodeExtractJson(jsonText: String): List<RawItem> {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val ok = root["ok"]?.let { runCatching { it.jsonPrimitive.content.toBoolean() }.getOrNull() }
        if (ok == false) return emptyList()
        val arr = root["items"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            RawItem(
                name = o["name"]?.jsonPrimitive?.content.orEmpty(),
                detail = o["detail"]?.jsonPrimitive?.content.orEmpty(),
                day = o["day"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        }
    }

    fun toCourse(raw: RawItem): Course? {
        if (raw.name.isBlank()) return null
        val day = raw.day.takeIf { it in 1..7 } ?: return null
        val parsed = parseDetail(raw.detail)
        val weeks = parseWeeks(parsed.weeksRaw)
        if (weeks.isEmpty()) return null
        val sections = parsed.sections ?: (1 to 1)
        return Course(
            id = 0,
            name = raw.name,
            teacher = parsed.teacher,
            position = parsed.position,
            day = day,
            startSection = sections.first,
            endSection = sections.second,
            weeks = weeks,
            colorIndex = ScheduleCalculator.colorIndexFor(raw.name),
        )
    }

    data class Detail(
        val teacher: String,
        val weeksRaw: String,
        val sections: Pair<Int, Int>?,
        val position: String,
    )

    fun parseDetail(text: String): Detail {
        val t = text.replace(Regex("\\s+"), "")
        val teacher = Regex("老师[:：]([^;；]*)").find(t)?.groupValues?.get(1)?.trim().orEmpty()
        val weeksRaw = Regex("时间[:：]([^;；\\[（(]*)").find(t)?.groupValues?.get(1)?.trim().orEmpty()
        val sections = parseSections(t)
        // 根因：这里原本是 trim('；',';','）',')')，把地点结尾的右括号也一起剥掉了，
        // 库里的值变成 `教学北大楼(北B102`，界面压缩地点时匹配不到成对括号，
        // 只能原样显示再被截断成 `@教学…`。只去尾部分号，括号必须留着。
        val position = Regex("地点[:：](.+)$")
            .find(t)?.groupValues?.get(1)?.trim('；', ';').orEmpty()
        return Detail(teacher, weeksRaw, sections, position)
    }

    private fun parseSections(t: String): Pair<Int, Int>? {
        Regex("\\[(\\d+)\\s*-\\s*(\\d+)\\s*节\\]").find(t)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            return a to b
        }
        Regex("第(\\d+)\\s*[-—~至]\\s*(\\d+)\\s*节").find(t)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            return a to b
        }
        Regex("第(\\d+)节").find(t)?.let { m ->
            val a = m.groupValues[1].toInt()
            return a to a
        }
        return null
    }

    fun parseWeeks(text: String): Set<Int> {
        val weeks = mutableSetOf<Int>()
        val cleaned = text
            .removeSuffix("周次")
            .removeSuffix("周")
            .trim()
        if (cleaned.isEmpty()) return emptySet()
        for (part in cleaned.split(',', '，', '、', ' ')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val range = p.split('-', '—', '~', '至')
            if (range.size == 2) {
                val a = range[0].trim().toIntOrNull()
                val b = range[1].trim().toIntOrNull()
                if (a != null && b != null && a in 1..40 && b >= a) {
                    weeks += (a..b.coerceAtMost(40))
                }
            } else {
                p.toIntOrNull()?.let { if (it in 1..40) weeks += it }
            }
        }
        return weeks
    }

    /**
     * 单元测试 / 备用路径：从完整 HTML 文本按表格列序抽 li。
     * 列号累加 colspan，并维护 rowspan 占用表（与 [EXTRACT_JS] 同一套算法）。
     */
    fun parseFromHtml(html: String): ImportParseResult {
        val tbodyStart = html.indexOf("<tbody")
        if (tbodyStart < 0) {
            return ImportParseResult.Failure("HTML 中未找到课表表格，请打开学期理论课表后再解析")
        }
        val tbody = html.substring(tbodyStart).substringBefore("</tbody>")
        val rowRegex = Regex("(?is)<tr\\b[^>]*>([\\s\\S]*?)</tr>")
        val cellRegex = Regex("(?is)<td\\b[^>]*>[\\s\\S]*?(?=<td\\b|</tr>)")

        val items = mutableListOf<RawItem>()
        val carry = HashMap<Int, Int>()
        for (row in rowRegex.findAll(tbody)) {
            carry.keys.toList().forEach { col ->
                val left = (carry[col] ?: 0) - 1
                if (left <= 0) carry.remove(col) else carry[col] = left
            }
            // 行内容取自 <tr>([\s\S]*?)</tr>，不含结尾的 </tr>；
            // 补回去，否则单元格正则的 (?=<td|</tr>) 前瞻在最后一列失配，每行会丢掉周日。
            val rowHtml = row.groupValues[1] + "</tr>"
            var col = 0
            for (cell in cellRegex.findAll(rowHtml)) {
                while ((carry[col] ?: 0) > 0) col++
                val openTag = cell.value.substringBefore('>')
                val rowspan = attrInt(openTag, "rowspan")
                if (rowspan > 1) carry[col] = rowspan
                val isDataCell = Regex("(?i)name\\s*=\\s*[\"']?kbDataTd")
                    .containsMatchIn(openTag)
                if (isDataCell && col in 1..7) {
                    val day = col
                    val chunk = cell.value
                    val name = Regex("(?is)qz-hasCourse-title[^>]*>([\\s\\S]*?)</")
                        .find(chunk)?.groupValues?.get(1)?.let { stripTags(it) }?.trim().orEmpty()
                    val detail = Regex("(?is)qz-hasCourse-abbrinfo[^>]*>([\\s\\S]*?)</span>")
                        .find(chunk)?.groupValues?.get(1)?.let { stripTags(it) }?.trim().orEmpty()
                    if (name.isNotBlank()) {
                        items += RawItem(name, detail, day)
                    }
                }
                col += attrInt(openTag, "colspan")
            }
        }
        if (items.isEmpty()) {
            return ImportParseResult.Failure("HTML 中未找到课表单元格，请打开学期理论课表后再解析")
        }
        val courses = items.mapNotNull { toCourse(it) }
        return if (courses.isEmpty()) {
            ImportParseResult.Failure("找到 ${items.size} 条课程，但无法解析周次/节次")
        } else {
            ImportParseResult.Success(courses)
        }
    }

    private fun attrInt(openTag: String, name: String): Int =
        Regex("(?i)$name\\s*=\\s*[\"']?(\\d+)")
            .find(openTag)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")
}
