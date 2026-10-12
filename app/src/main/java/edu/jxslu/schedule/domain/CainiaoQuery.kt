package edu.jxslu.schedule.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 菜鸟批量查（DESIGN §4.47）：把用户贴入的运单号拼成菜鸟「三方路由」链接，由菜鸟 App
 * 内部跳转到搜索页预填并批量查询（2026-10-12 对 8.11.126 真机实测：搜索页已预填 →
 * 「去批量查询」→ 添加包裹页全部查完 →「添加到包裹列表」保存）。
 *
 * 链路：`cainiao://startapp/search_alert?...` 由菜鸟导出的 `OpenGuoGuoUrlActivity`
 * （三方路由）接收，前缀被重写成内部路由 `guoguo://go/search_alert?...` 再交 App 内
 * Router 跳转——外部 App 借此能到达平时进不去的内部页面（页面本身未导出）。
 * 模板参数（querySourceId / prePath）照抄其字符串常量，**改任何一段前先在真机上重验**；
 * 通道哪天收紧：换目标链接或整体下线，本对象是唯一改动点。
 *
 * 纯逻辑（可 JVM 测）：清洗拆分输入、拼链接、历史编解码。UI 在 ui/toolbox/CainiaoQueryScreen。
 */
object CainiaoQuery {

    /** 菜鸟 App 包名（拉起与安装检测共用；包可见性声明在 AndroidManifest 的 queries 里）。 */
    const val PKG = "com.cainiao.wireless"

    /** 一次最多送几个单号（防御性上限，菜鸟侧真实上限未公开）。 */
    const val MAX_COUNT = 20

    /** 历史最多记几条。 */
    const val MAX_HISTORY = 5

    /**
     * 单号长度门槛。粘过来的文本常混着说明文字 / 编号，而常见运单号都在 10 位上下
     * （EMS 13 位、顺丰 SF+12 位起、京东 JD+13 位），8 位挡杂项、不误伤。
     */
    const val MIN_LEN = 8

    /** 单号长度上限（防御）：清洗后仍超 40 字符的整段长文不可能是运单号，整段忽略。 */
    const val MAX_LEN = 40

    /** 链接前缀（2026-10-12 抓自 8.11.126 的字符串常量，勿猜改动）。 */
    private const val LINK_PREFIX =
        "cainiao://startapp/search_alert?querySourceId=68719476739&prePath=Pasteboard&content="

    /**
     * 分隔符：半角 / 全角逗号、顿号、半角 / 全角分号、空白。空白里显式补 U+3000（全角空格）
     * 与 U+00A0（不换行空格）——从中文页面 / 邮件抄出来的串常带这两种，正则的 `\s` 只认
     * ASCII 空白，会漏。
     */
    private val SEPARATORS = Regex("[,\uFF0C\u3001;；\\s\u3000\u00A0]+")

    /**
     * 一次拆分 / 清洗的完整结果，页面计数文案直接照它说：[numbers] 是实际要发送的
     * （已去重、已截上限）；其余三个计数解释「为什么少了几条」——[duplicates] 重复、
     * [ignored] 不成形、[overflow] 超上限。
     */
    data class ParseResult(
        val numbers: List<String>,
        val duplicates: Int,
        val ignored: Int,
        val overflow: Int,
    )

    /**
     * 拆分并清洗输入（口径由 `CainiaoQueryTest` 钉住）：
     * 1. 按 [SEPARATORS] 切段，空白段直接跳过（首尾 / 连续的逗号分号不算杂项）；
     * 2. 每段全角转半角，只留字母 / 数字 / 连字符，再去掉首尾多余的连字符
     *    （「运单号：JT…」这类前后缀就是这么洗掉的）；
     * 3. 清洗后长度在 [MIN_LEN]~[MAX_LEN] 且至少含一个数字才算单号，否则计入
     *    [ParseResult.ignored]——被丢掉的在页面上报数，不静默少发；
     * 4. 大小写不敏感去重，先到先得，重复计入 [ParseResult.duplicates]；
     * 5. 超过 [MAX_COUNT] 截断，超出部分计入 [ParseResult.overflow]。
     */
    fun parseInput(text: String): ParseResult {
        val seen = HashSet<String>()
        val kept = ArrayList<String>()
        var duplicates = 0
        var ignored = 0
        var overflow = 0
        for (raw in text.split(SEPARATORS)) {
            if (raw.isBlank()) continue
            val token = sanitize(raw)
            if (token.length < MIN_LEN || token.length > MAX_LEN ||
                token.none { it in '0'..'9' }
            ) {
                ignored++
                continue
            }
            if (!seen.add(token.uppercase())) {
                duplicates++
                continue
            }
            if (kept.size >= MAX_COUNT) {
                overflow++
                continue
            }
            kept.add(token)
        }
        return ParseResult(kept, duplicates, ignored, overflow)
    }

    /** 单段清洗：全角 ASCII 折半角 → 只留 [A-Za-z0-9-] → 去首尾连字符。 */
    private fun sanitize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            // U+FF01..U+FF5E 是全角 ASCII（！..～），折 0xFEE0 回半角
            val c = if (ch.code in 0xFF01..0xFF5E) ch - 0xFEE0 else ch
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-') sb.append(c)
        }
        return sb.toString().trim('-')
    }

    /**
     * 拼拉起的 deeplink。content = 单号按英文逗号连接，逐字符过白名单
     * （字母数字与 `-_.` 原样，其余做 UTF-8 百分号编码）——常规单号原样直出，
     * 编码只是兜底。入参以 [parseInput] 的产出为准，本函数不做清洗（清洗是 [parseInput] 的职责）。
     */
    fun buildLink(numbers: List<String>): String =
        LINK_PREFIX + numbers.joinToString(",") { encodeToken(it) }

    private fun encodeToken(token: String): String {
        val sb = StringBuilder()
        for (ch in token) {
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                ch == '-' || ch == '_' || ch == '.'
            ) {
                sb.append(ch)
            } else {
                for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                    sb.append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
                }
            }
        }
        return sb.toString()
    }

    private const val HEX = "0123456789ABCDEF"

    /** 历史条目 = 一条提交的内容串（单号按逗号连接），与 [buildLink] 的 content 同形。 */
    fun entryOf(numbers: List<String>): String = numbers.joinToString(",")

    /** 历史条目里单号的个数（「最近提交」行尾的小标签用）。 */
    fun entryCount(entry: String): Int = entry.split(',').count { it.isNotBlank() }

    /** 新条目入史：同串去重、最近在前、截到 [MAX_HISTORY]。 */
    fun pushHistory(history: List<String>, entry: String): List<String> =
        (listOf(entry) + history.filter { it != entry }).take(MAX_HISTORY)

    /** 历史 → JSON（DataStore 存储）。 */
    fun encodeHistory(history: List<String>): String = Json.encodeToString(history.toList())

    /**
     * JSON → 历史。脏数据一律回空表：历史是锦上添花的回填入口，
     * 坏了不该让页面读不到（口径同 [EbikeQr.decodeRecent]）。
     */
    fun decodeHistory(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            Json.decodeFromString<List<String>>(json)
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_HISTORY)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
