package edu.jxslu.schedule.data.jw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 教务 layui JSON 的统一外壳解析（成绩 / 考试 / 教材 / 实习基地 / 选课共用）。
 *
 * 检查顺序与文案固定：`ERR:` 前缀 → no-open 页 → 未登录页 → 非 JSON → `code != 0`。
 * `data` 可能是 JSON null（不是缺键），统一收成空列表，避免 `.jsonArray` 抛裸异常。
 */
internal object JwLayuiJson {

    data class Root(
        val count: Int,
        val rows: List<JsonObject>,
        val json: JsonObject,
    )

    fun parseRoot(jsonText: String, what: String, countDefault: Int = 0): Root {
        if (jsonText.startsWith("ERR:")) {
            throw IllegalStateException("请求失败：${jsonText.removePrefix("ERR:")}")
        }
        if ("系统功能暂未开放" in jsonText) {
            throw IllegalStateException("教务${what}功能暂未开放（no-open 页）")
        }
        if ("用户没有登录" in jsonText) {
            throw IllegalStateException("教务会话已失效（用户没有登录），请重新登录")
        }
        val root = try {
            Json.parseToJsonElement(jsonText).jsonObject
        } catch (e: Exception) {
            throw IllegalStateException("返回的不是有效 JSON（可能未登录教务）：${e.message}")
        }
        val code = root.intOrNull("code") ?: -1
        if (code != 0) {
            throw IllegalStateException("教务返回 code=$code，msg=${root.strOrNull("msg") ?: ""}")
        }
        val count = root.intOrNull("count") ?: countDefault
        val rows = (root["data"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        return Root(count, rows, root)
    }
}

/**
 * 字符串字段：缺失 / JSON null / 非原始值 / 空白都按 null。
 * JsonNull 虽继承 JsonPrimitive，但 `.content` 是字面量 `"null"`，必须显式挡掉。
 */
internal fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it !is JsonNull }
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

internal fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it !is JsonNull }
        ?.contentOrNull
        ?.toIntOrNull()

internal fun JsonObject.doubleOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it !is JsonNull }
        ?.contentOrNull
        ?.toDoubleOrNull()
