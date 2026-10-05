package edu.jxslu.schedule.data.qiekj

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * debug 专用探测接口（2026-10-05）：只回答一个问题——「设备在线状态有没有接口可读」。
 * 不参与任何业务，不调 unlock，不改余额，不写订单。release 不创建它。
 *
 * 端点是门店维度的设备清单，来自社区 Python 实现（`3ryng1um/qiekj`：
 * `shopId` + `machineTypeId` + `orgId` + 分页 + token）。返回结构未知，先按 [JsonElement] 收，
 * 落盘后再看有没有设备状态字段。**故意不进 [QiekjApi]**：那边与参考实现逐端点对过，
 * 不让探测性端点混进主接口面。
 */
interface QiekjProbeApi {
    @FormUrlEncoded
    @POST("appointNew/near/newMachines")
    suspend fun nearMachines(
        @Field("goodsPage") goodsPage: String = "1",
        @Field("pageSize") pageSize: String = "20",
        @Field("shopId") shopId: String,
        @Field("machineTypeId") machineTypeId: String,
        @Field("orgId") orgId: String,
        @Field("token") token: String,
    ): ApiEnvelope<JsonElement>
}

/**
 * 一轮探测的关键字段摘要（原文另由 [QiekjDebugDumpInterceptor] 整包落盘，这里只做便于对照的一页）。
 * 同一台设备在「在线」与「断电」两种状态下各探一次，两份摘要逐字段比即可，
 * 不用翻几百份 sync 样本。
 */
data class QiekjProbeSnapshot(
    val at: String,
    val goodsId: String,
    /** `goods/latestUsed` 里那台设备的 workStatus（进页拉列表时已经拿到）。 */
    val latestUsedWorkStatus: Int?,
    val skuId: String?,
    val syncStatus: Int?,
    val syncWorkStatus: Int?,
    val syncAmount: String?,
    val syncIdentifyPresent: Boolean,
    val shopId: String?,
    val deviceErrorCode: String?,
    val deviceErrorMsg: String?,
    val nearMachinesCode: Int?,
    val nearMachinesMsg: String?,
)

/** 探测摘要落一份，文件名 `probe-<时间戳>.json`；失败静默，不影响主流程。 */
fun dumpQiekjProbe(dir: File, snapshot: QiekjProbeSnapshot) {
    runCatching {
        dir.mkdirs()
        val payload = buildJsonObject {
            put("at", snapshot.at)
            put("goodsId", snapshot.goodsId)
            put("latestUsedWorkStatus", snapshot.latestUsedWorkStatus)
            put("skuId", snapshot.skuId)
            put("syncStatus", snapshot.syncStatus)
            put("syncWorkStatus", snapshot.syncWorkStatus)
            put("syncAmount", snapshot.syncAmount)
            put("syncIdentifyPresent", snapshot.syncIdentifyPresent)
            put("shopId", snapshot.shopId)
            put("deviceErrorCode", snapshot.deviceErrorCode)
            put("deviceErrorMsg", snapshot.deviceErrorMsg)
            put("nearMachinesCode", snapshot.nearMachinesCode)
            put("nearMachinesMsg", snapshot.nearMachinesMsg)
        }.toString()
        File(dir, "probe-${snapshot.at}.json").writeText(payload)
        pruneQiekjDumps(dir, "probe", QIEKJ_DUMP_KEEP_PER_ENDPOINT)
    }
}

/** 探测时间戳（与样本文件名同一套格式，便于两份文件对照）。 */
fun qiekjProbeStamp(): String =
    SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
