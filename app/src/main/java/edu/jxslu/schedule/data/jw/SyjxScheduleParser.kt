package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.domain.ScheduleCalculator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 解析强智「实验课表」页（`/jsxsd/syjx/toXskb.do`，教务 → 实践实验 → 实验课表查询）。
 *
 * 根因：这张页面与理论课表页（[QiangzhiScheduleParser]）**没有任何可复用之处**，实测差异有三：
 *
 * 1. **结构与字段不同**。该页没有 `td[name=kbDataTd]`，也没有
 *    `老师:X;时间:Y;地点:Z` 那种合并 detail 文本——`qz-hasCourse-abbrinfo` 里只有地点，
 *    页面**根本不提供教师字段**。所以理论课表那套抽取脚本与字段正则全部不适用。
 *
 * 2. **周次不在课块里**。表头是 `周次 | 节次 | 星期一…星期日` 共 9 列，
 *    每个周次占 6 行：首行 9 个 td = `[周次标签 rowspan=6][节次标签][7 天]`，
 *    其余 5 行 8 个 td = `[节次标签][7 天]`。课块不知道自己属于哪一周，
 *    必须向上找带 `rowspan` 的周次标签单元格。
 *
 * 3. **同一门课按周拆成多块**。第 1、3 周各上一块的实验课，页面上就是两个独立的 li。
 *    因此必须按 `(名称, 星期, 起止节次, 地点)` 聚合、`weeks` 取并集，
 *    否则导入后一门课会变成一堆各带一周的碎片。
 *
 * 另注：tooltip 里的 `节次：60304` 是页面内部编码，**不是**真实节次，只能取行标签。
 */
object SyjxScheduleParser {

    /** 「节次标签 + 7 天」的基准列数；周次首行会多出 1 列周次标签。 */
    private const val BASE_TD_COUNT = 8

    data class RawBlock(
        val name: String,
        val position: String,
        val day: Int,
        val week: Int,
        val startSection: Int,
        val endSection: Int,
    )

    /**
     * WebView 注入脚本：按行形态换算星期，并跨行记住当前周次。
     *
     * 找不到课表容器**不当作异常**：返回 `ok:true, container:false` 的空结果，
     * 让 Kotlin 侧能区分「本学期没有实验课」（容器在、课块 0 个）与
     * 「拿到的不是这张课表」（容器都没有）——一键导入靠这个判断要不要给用户警示
     * （[ExtractMeta]）。改成 ok:false 会让两种情形重新混在一起。
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
    var tbody = document.querySelector('tbody.qz-weeklyTable-thbody')
             || document.querySelector('table.qz-weeklyTable tbody');
    if (!tbody) {
      return JSON.stringify({ ok:true, items:[], term: term, container: false,
        title: document.title || '', url: location.href });
    }
    var BASE = 8;
    var rows = tbody.querySelectorAll('tr');
    var week = 0;
    for (var r = 0; r < rows.length; r++) {
      var tds = rows[r].querySelectorAll('td');
      if (!tds.length) continue;
      var first = tds[0];
      var firstCls = first.className || '';
      if (first.getAttribute('rowspan') && firstCls.indexOf('qz-weeklyTable-label') >= 0) {
        var w = (first.textContent || '').replace(/\s+/g, '').trim();
        week = /^\d+$/.test(w) ? parseInt(w, 10) : 0;
      }
      var secIdx = tds.length - BASE;
      if (secIdx < 0) secIdx = 0;
      var sec = (tds[secIdx] ? tds[secIdx].textContent : '').replace(/\s+/g, '').trim();
      if (!week || !sec) continue;
      for (var i = 0; i < tds.length; i++) {
        var td = tds[i];
        if ((td.className || '').indexOf('qz-hasCourse') < 0) continue;
        var day = i - (tds.length - BASE);
        if (day < 1 || day > 7) continue;
        var lis = td.querySelectorAll('li.courselists-item');
        for (var k = 0; k < lis.length; k++) {
          var t = lis[k].querySelector('.qz-hasCourse-title');
          var p = lis[k].querySelector('.qz-hasCourse-detailitem');
          var name = t ? (t.textContent || '').replace(/\s+/g, ' ').trim() : '';
          if (!name) continue;
          out.push({
            name: name,
            position: p ? (p.textContent || '').replace(/\s+/g, ' ').trim() : '',
            day: day, week: week, sections: sec
          });
        }
      }
    }
    return JSON.stringify({ ok: true, items: out, term: term, container: true,
      title: document.title || '', url: location.href });
  } catch (e) {
    return JSON.stringify({ ok: false, error: String(e) });
  }
})()
"""

    private val json = Json { ignoreUnknownKeys = true }

    fun parseExtractJson(jsonText: String): ImportParseResult = try {
        val blocks = decodeExtractJson(jsonText)
        when {
            blocks.isEmpty() -> ImportParseResult.Failure(
                "当前页面未解析到实验课。请先打开「实验课表查询」页，再点导入。",
            )
            else -> {
                val courses = toCourses(blocks)
                if (courses.isEmpty()) {
                    ImportParseResult.Failure("解析到 ${blocks.size} 个课块，但星期/节次/周次不完整")
                } else {
                    ImportParseResult.Success(courses, term = extractTermField(jsonText))
                }
            }
        }
    } catch (e: Exception) {
        ImportParseResult.Failure("解析失败：${e.message}")
    }

    internal fun decodeExtractJson(jsonText: String): List<RawBlock> {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val ok = root["ok"]?.let { runCatching { it.jsonPrimitive.content.toBoolean() }.getOrNull() }
        if (ok == false) return emptyList()
        val arr = root["items"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val sections = parseSections(o["sections"]?.jsonPrimitive?.content.orEmpty())
                ?: return@mapNotNull null
            RawBlock(
                name = o["name"]?.jsonPrimitive?.content.orEmpty(),
                position = o["position"]?.jsonPrimitive?.content.orEmpty(),
                day = o["day"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                week = o["week"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                startSection = sections.first,
                endSection = sections.second,
            )
        }
    }

    /**
     * 课块 → 课程。同名同星期同节次同地点的多个周次块合并成一条，`weeks` 取并集。
     *
     * 聚合键**必须含地点**：工程训练按批次分周上课，同一门课会安排在不同实训室
     * （实测「机械制造基础A」出现在 212 / 105 / 403 三个房间），
     * 只按课程名合并会把这些批次错误地并成一条、丢掉地点差异。
     *
     * 教师恒为空串——页面不提供该字段，保持空而不是瞎猜。
     */
    fun toCourses(blocks: List<RawBlock>): List<Course> {
        val merged = LinkedHashMap<BlockKey, MutableSet<Int>>()
        for (b in blocks) {
            if (b.name.isBlank()) continue
            if (b.day !in 1..7) continue
            if (b.week !in 1..40) continue
            if (b.startSection < 1 || b.endSection < b.startSection) continue
            val key = BlockKey(b.name, b.day, b.startSection, b.endSection, b.position)
            merged.getOrPut(key) { mutableSetOf() }.add(b.week)
        }
        return merged.entries
            .sortedWith(
                compareBy({ it.key.day }, { it.key.startSection }, { it.key.name }, { it.key.position }),
            )
            .map { (key, weeks) ->
                Course(
                    id = 0,
                    name = key.name,
                    teacher = "",
                    position = key.position,
                    day = key.day,
                    startSection = key.startSection,
                    endSection = key.endSection,
                    weeks = weeks.toSet(),
                    // 仅占位；入库时由 ScheduleRepository 按课程名重排，
                    // 保证整批导入的颜色稳定可复现
                    colorIndex = ScheduleCalculator.colorIndexFor(key.name),
                    kind = CourseKind.Lab,
                )
            }
    }

    private data class BlockKey(
        val name: String,
        val day: Int,
        val startSection: Int,
        val endSection: Int,
        val position: String,
    )

    /** `3-4` → (3,4)；`11` → (11,11)。 */
    internal fun parseSections(text: String): Pair<Int, Int>? {
        val t = text.trim()
        Regex("^(\\d+)\\s*-\\s*(\\d+)$").find(t)?.let { m ->
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        Regex("^(\\d+)$").find(t)?.let { m ->
            val v = m.groupValues[1].toInt()
            return v to v
        }
        return null
    }

    /**
     * 单元测试用：从完整 HTML 文本粗提课块。
     * 真机上走 [EXTRACT_JS]（有完整 DOM API），这里用正则复刻同一套判定规则。
     */
    fun parseFromHtml(html: String): ImportParseResult {
        val blocks = extractFromHtml(html)
        if (blocks.isEmpty()) {
            return ImportParseResult.Failure("HTML 中未找到实验课块，请打开实验课表查询页后再解析")
        }
        val courses = toCourses(blocks)
        return if (courses.isEmpty()) {
            ImportParseResult.Failure("找到 ${blocks.size} 个课块，但无法解析星期/节次/周次")
        } else {
            ImportParseResult.Success(courses)
        }
    }

    internal fun extractFromHtml(html: String): List<RawBlock> {
        val out = mutableListOf<RawBlock>()
        var week = 0
        val trRegex = Regex("(?is)<tr\\b[^>]*>[\\s\\S]*?</tr>")
        val tdRegex = Regex("(?is)<td\\b[^>]*>[\\s\\S]*?(?=<td\\b|</td|</tr>|</tbody>)")
        for (trMatch in trRegex.findAll(html)) {
            val tds = tdRegex.findAll(trMatch.value).map { it.value }.toList()
            if (tds.isEmpty()) continue

            val firstTag = openTag(tds.first())
            if (Regex("(?i)rowspan\\s*=\\s*[\"']?\\d").containsMatchIn(firstTag) &&
                "qz-weeklyTable-label" in firstTag
            ) {
                stripTags(cellBody(tds.first())).trim().toIntOrNull()?.let { week = it }
            }

            val secIdx = (tds.size - BASE_TD_COUNT).coerceIn(0, tds.lastIndex)
            val sections = parseSections(stripTags(cellBody(tds[secIdx]))) ?: continue

            tds.forEachIndexed { idx, td ->
                if ("qz-hasCourse" !in openTag(td)) return@forEachIndexed
                val day = idx - (tds.size - BASE_TD_COUNT)
                if (day !in 1..7 || week !in 1..40) return@forEachIndexed
                val body = cellBody(td)
                val name = divText(body, "qz-hasCourse-title") ?: return@forEachIndexed
                val position = divText(body, "qz-hasCourse-detailitem").orEmpty()
                out += RawBlock(
                    name = name,
                    position = position,
                    day = day,
                    week = week,
                    startSection = sections.first,
                    endSection = sections.second,
                )
            }
        }
        return out
    }

    private fun openTag(td: String): String = td.substringBefore('>')

    private fun cellBody(td: String): String = td.substringAfter('>', "")

    /** 取指定 class 的 div 文本；只认精确类名，避免误抓 tooltip 里的 `qz-tooltipContent-*`。 */
    private fun divText(body: String, className: String): String? {
        val m = Regex("(?is)$className[^>]*>([\\s\\S]*?)<").find(body) ?: return null
        val text = stripTags(m.groupValues[1]).replace(Regex("\\s+"), " ").trim()
        return text.ifBlank { null }
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")
}
