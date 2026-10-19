package edu.jxslu.schedule.data.xg

import edu.jxslu.schedule.domain.DormRepairFlowNode
import edu.jxslu.schedule.domain.DormRepairRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 学工「个人提交记录」应答解析（DESIGN §4.49）。
 *
 * 接口：`POST /office/data/approve/apps/forms/fore/user/list`（只读；提交/审批接口一律不碰）。
 * 只取用户要看的东西：
 * - 审批状态 `aprvStatusType` / `aprvStatusTypeId`
 * - 提交/更新时间 `inserttime` / `updatetime`
 * - 当前节点与审批人 `currentApproveUserList`
 * - 流程节点 `flowUser`（只取 `cardType == 1` 的审批节点）
 * - 表单字段 `formData` + `formsConfig`：按 **label** 取「当前状态 / 宿舍信息 / 宿舍 /
 *   故障类型 / 详情描述 / 最终完成时间」，不写死字段 id（管理员改表单也不会立刻失效）
 *
 * **姓名/学号/院系/专业/班级/手机号等个人字段在 [parseFormFields] 里直接丢弃**，
 * 既不进返回值也不落盘。
 */
object DormRepairParser {

    /** 与卡片/详情页无关的个人字段，解析阶段就丢掉。 */
    private val PERSONAL_LABELS = setOf(
        "姓名", "学号", "院系", "专业", "班级", "手机号", "电话",
        "提交人", "发起人", "提交人姓名", "学工号",
    )

    private val DATE_TIME_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
    )

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * 解析列表应答，按提交时间倒序返回（最多 [MAX_RECORDS] 条）。
     *
     * 失败抛 [IllegalStateException]（由 `DormRepairSync` 转成静默失败，不打断今日页）。
     */
    fun parse(jsonText: String): List<DormRepairRecord> {
        if (jsonText.isBlank()) throw IllegalStateException("学工返回了空应答")
        val root = try {
            Json.parseToJsonElement(jsonText).jsonObject
        } catch (e: Exception) {
            throw IllegalStateException("学工报修列表不是有效 JSON（可能学工会话已失效）")
        }
        if (root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            val msg = root["msg"]?.jsonPrimitive?.contentOrNull
            throw IllegalStateException(msg?.takeIf { it.isNotBlank() } ?: "学工报修列表返回失败")
        }
        val data = root["data"] as? JsonObject ?: return emptyList()
        val labels = parseLabels(data["formsConfig"])
        val list = data["formsUserList"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { element ->
            (element as? JsonObject)?.let { parseRecord(it, labels) }
        }
            .sortedByDescending { it.submittedAt }
            .take(MAX_RECORDS)
    }

    /** config 是 JSON 字符串（正常形态），也兼容直接给数组的形态。 */
    private fun parseLabels(element: JsonElement?): Map<Long, String> {
        if (element == null) return emptyMap()
        val array = when (element) {
            is JsonArray -> element
            is JsonPrimitive -> element.contentOrNull?.let { text ->
                runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull()
            }
            else -> null
        } ?: return emptyMap()
        return array.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val label = (obj["fields"] as? JsonArray)
                ?.firstOrNull()
                ?.let { it as? JsonObject }
                ?.get("label")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            id to label
        }.toMap()
    }

    private fun parseRecord(obj: JsonObject, labels: Map<Long, String>): DormRepairRecord? {
        val formUserId = obj["id"]?.jsonPrimitive?.longOrNull ?: return null
        val formId = obj["formId"]?.jsonPrimitive?.longOrNull ?: 0
        val submittedAt = obj["inserttime"]?.jsonPrimitive?.longOrNull ?: 0
        val updatedAt = obj["updatetime"]?.jsonPrimitive?.longOrNull ?: submittedAt
        val fields = parseFormFields(obj["formData"] as? JsonArray, labels)

        val approverNodes = (obj["currentApproveUserList"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?: emptyList()
        val currentNodeIds = approverNodes
            .mapNotNull { it["nodeUniqueId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) }
            .toSet()
        val currentApprovers = approverNodes
            .mapNotNull { it["aprvUserName"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) }
            .distinct()
            .ifEmpty {
                obj["currentAprvUser"]?.jsonPrimitive?.contentOrNull
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?: emptyList()
            }

        return DormRepairRecord(
            formUserId = formUserId,
            formId = formId,
            title = obj["customTitle"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: XgUrls.REPAIR.title,
            approvalStatus = obj["aprvStatusType"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            approvalStatusId = obj["aprvStatusTypeId"]?.jsonPrimitive?.intOrNull ?: 0,
            repairStatus = fields["当前状态"].orEmpty(),
            dorm = fields["宿舍信息"]?.takeIf { it.isNotBlank() } ?: fields["宿舍"].orEmpty(),
            fault = fields["故障类型"].orEmpty().trimEnd('-', ' ', '、'),
            description = fields["详情描述"].orEmpty(),
            submittedAt = submittedAt,
            updatedAt = updatedAt,
            finishedAt = parseDateTime(fields["最终完成时间"]),
            currentApprovers = currentApprovers,
            flowNodes = parseFlowNodes(obj["flowUser"], currentNodeIds),
        )
    }

    /**
     * `formData`：每条是 { id, compt, fields: [ { values: [...] } ] }。
     * 按 `formsConfig` 的 id→label 映射取文字；label 认不出、值为空、个人字段都跳过。
     */
    private fun parseFormFields(formData: JsonArray?, labels: Map<Long, String>): Map<String, String> {
        if (formData == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (element in formData) {
            val obj = element as? JsonObject ?: continue
            val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
            val label = labels[id] ?: continue
            if (label in PERSONAL_LABELS) continue
            val values = ((obj["fields"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.get("values") as? JsonArray
                ?: continue
            val text = values.mapNotNull { value ->
                val valueObj = value as? JsonObject ?: return@mapNotNull null
                valueObj["val"]?.jsonPrimitive?.contentOrNull
                    ?: valueObj["uname"]?.jsonPrimitive?.contentOrNull
                    ?: valueObj["departmentName"]?.jsonPrimitive?.contentOrNull
            }.filter { it.isNotBlank() }.joinToString("、")
            if (text.isNotBlank()) result[label] = text
        }
        return result
    }

    /**
     * `flowUser` 是 JSON 字符串（也兼容数组）：只取 `cardType == 1` 的审批节点，
     * 抄送节点（2）不进入进度条。`current` 由 `currentApproveUserList[].nodeUniqueId` 匹配。
     */
    private fun parseFlowNodes(element: JsonElement?, currentNodeIds: Set<String>): List<DormRepairFlowNode> {
        val array = when (element) {
            is JsonArray -> element
            is JsonPrimitive -> element.contentOrNull?.let { text ->
                runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull()
            }
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            if (obj["cardType"]?.jsonPrimitive?.intOrNull != 1) return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val nodeId = obj["nodeUniqueId"]?.jsonPrimitive?.contentOrNull
                ?: obj["nodeUniqueId"]?.jsonPrimitive?.longOrNull?.toString()
                ?: ""
            val userList = (obj["userList"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.get("uname")?.jsonPrimitive?.contentOrNull }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val fallback = (obj["defaultUserList"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.get("uname")?.jsonPrimitive?.contentOrNull }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            DormRepairFlowNode(
                title = title,
                nodeUniqueId = nodeId,
                approvers = (userList.ifEmpty { fallback }).distinct(),
                current = nodeId.isNotBlank() && nodeId in currentNodeIds,
            )
        }
    }

    /** 表单的完成时间可能是 `yyyy-MM-dd HH:mm`，也可能是 epoch millis 字符串。 */
    private fun parseDateTime(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        for (format in DATE_TIME_FORMATS) {
            runCatching {
                return LocalDateTime.parse(text, format)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
        }
        runCatching {
            return LocalDate.parse(text, DATE_FORMAT)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }
        return null
    }

    /** 一次最多保留多少条（列表接口的 `pageSize` 也按这个值取）。 */
    const val MAX_RECORDS = 20
}
