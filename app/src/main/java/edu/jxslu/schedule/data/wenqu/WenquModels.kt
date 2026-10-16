package edu.jxslu.schedule.data.wenqu

import edu.jxslu.schedule.data.qiekj.LenientStringNonNullSerializer
import edu.jxslu.schedule.data.qiekj.LenientStringSerializer
import edu.jxslu.schedule.data.qiekj.QiekjJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 问渠实践教育平台的数据模型（DESIGN §4.45）。
 *
 * 平台是**道然科技基于 CRMEB 商城框架定制**的学校系统（后端 `wenqu.juwp.edu.cn`），
 * 统一信封是 `{"status":200,"msg":"ok","data":...}`——注意字段名是 `status` / `msg`
 * （不是 CRMEB 常见的 `code` / `message`），别照 CRMEB 默认口径猜。
 *
 * 两条实测口径（写代码前先看）：
 * - **`register/verify`（发验证码）成功与失败都回 `status:200`**——成功 `msg="发送成功"`，
 *   失败 `msg="接口请求失败:暂无使用次数"`（短信限流）。所以它**不能走 [WenquEnvelope.requireSuccess]**，
 *   见 [WenquEnvelope.sendResult]。
 * - 会话失效是 `status:410000` + `msg="请登录"`，只有它算凭证失效（网络/5xx 都不算）。
 */
@Serializable
data class WenquEnvelope(
    val status: Int = -1,
    val msg: String? = null,
    val data: JsonElement? = null,
) {
    /** 通用成功判定：`status == 200` 即成功（登录 / 设密码 / 取数）。 */
    fun requireSuccess() {
        if (status == STATUS_OK) return
        val text = msg?.takeIf { it.isNotBlank() } ?: "请求失败（$status）"
        if (status == STATUS_NEED_LOGIN) throw WenquSessionExpiredException(text)
        throw WenquApiException(text, status)
    }

    /**
     * 发验证码专用判定：**成功与失败都是 `status:200`**，只能看 `msg`。
     * 返回 true = 短信已发出；false = 平台拒绝（限流 / 号码未注册等），[msg] 里是原因。
     */
    fun sendResult(): Boolean = status == STATUS_OK && msg.orEmpty().contains(SEND_OK_TOKEN)

    companion object {
        const val STATUS_OK = 200

        /** 会话失效（`请登录`）。**只有它算凭证失效**——网络与 5xx 一律不计（对齐一卡通纪律）。 */
        const val STATUS_NEED_LOGIN = 410000

        /** `register/verify` 的成功文案片段。 */
        const val SEND_OK_TOKEN = "发送成功"
    }
}

/** `POST /api/login` 的 `data`。`expires_time` 是平台给的可读时间串，真正到期看 JWT 的 `exp`。 */
@Serializable
data class WenquLoginData(
    val token: String = "",
    @SerialName("expires_time") val expiresTime: String = "",
)

/** `POST /api/volunteer/my` 的 `data`：总工时 + 记录列表。 */
@Serializable
data class WenquVolunteerData(
    /** 总工时（志愿时长 / 劳动教育工时）。平台有时回数字、有时回字符串，走宽容串序列化。 */
    @Serializable(with = LenientStringSerializer::class)
    @SerialName("total_score")
    val totalScore: String? = null,
    val list: List<WenquVolunteerRecord> = emptyList(),
)

/**
 * 一条志愿 / 劳动教育记录（我参加过的）。
 *
 * **只声明界面要用的字段**。`leader_name` / `leader_phone`（活动负责人）**要展示**，
 * 所以声明了（2026-10-08 用户要求详情显示负责人与电话）；`attendance_name` /
 * `attendance_phone`（考勤人）**不声明**——`ignoreUnknownKeys` 下它们连内存都不进。
 *
 * 红线同 [WenquActivity]：负责人姓名与电话只用于详情展示，不进日志、不进持久化、
 * 不进测试夹具与文档。
 */
@Serializable
data class WenquVolunteerRecord(
    /**
     * **活动 id**（不是报名 id）——小程序的 `goToDetail` 就是拿它去
     * `volunteer_detail/index?id=`，所以记录详情与活动详情是同一份数据。
     * 自主（DIY）类记录这里可能是 null，`coerceInputValues` 下收成 0（调用方按「取不到详情」处理）。
     */
    val id: Long = 0,
    val title: String = "",
    val unit: String = "",
    val address: String = "",
    /** 活动负责人姓名（**只展示**，见类注释红线）。 */
    @SerialName("leader_name") val leaderName: String = "",
    /** 活动负责人电话（**只展示**，见类注释红线）。 */
    @SerialName("leader_phone") val leaderPhone: String = "",
    @SerialName("start_time") val startTime: String = "",
    @SerialName("end_time") val endTime: String = "",
    @SerialName("apply_start_time") val applyStartTime: String = "",
    @SerialName("apply_end_time") val applyEndTime: String = "",
    /** 活动说明（记录里通常是一句话，详情接口给的更全）。 */
    val content: String = "",
    /** 本条记录计入的工时。 */
    @Serializable(with = LenientStringSerializer::class)
    val score: String? = null,
    @SerialName("apply_status_text") val statusText: String = "",
    /**
     * **报名 id**（不是活动 id，见 [id]）。取消报名要用它——2026-10-11 实测。
     * 自主（DIY）类记录可能没有，收成 0。
     */
    @SerialName("apply_id") val applyId: Long = 0,
    /**
     * 平台算好的「这条报名现在还能不能取消」。**只有 `volunteer/my` 有这个字段**
     * （活动列表 / 详情都没有），所以记录视角的「取消报名」入口以它为准。
     */
    @SerialName("canCancel") val canCancel: Boolean = false,
)

/**
 * 「平台没有这个状态」——**不要和平台的 0（已取消）混**。
 * 见 [asActivity]：`WenquActivity.status` 的默认值 0 在平台表里是「已取消」。
 */
const val WENQU_STATUS_NOT_APPLICABLE = -1

/**
 * 记录 → 活动（详情弹层共用一件）。
 *
 * 只映射弹层要用的字段，**`status` 显式给 [WENQU_STATUS_NOT_APPLICABLE]**：
 * 记录看的是「我的报名状态」（`apply_status_text`，如「已完成」），不是活动状态；
 * 而 `WenquActivity.status` 的默认值 0 在平台表里是「**已取消**」——照搬默认值会让
 * 记录视角的徽章写「已取消」，所以这里钉成 -1（`WenquRules.statusLabel` 对认不出的值回空串，
 * 即「不显示徽章」而不是「显示一个编出来的状态」）。
 * 记录视角的徽章由调用方直接给 [WenquVolunteerRecord.statusText]。
 */
fun WenquVolunteerRecord.asActivity(): WenquActivity = WenquActivity(
    id = id,
    title = title,
    unit = unit,
    address = address,
    leaderName = leaderName,
    leaderPhone = leaderPhone,
    startTime = startTime,
    endTime = endTime,
    applyStartTime = applyStartTime,
    applyEndTime = applyEndTime,
    content = content,
    score = score,
    status = WENQU_STATUS_NOT_APPLICABLE,
    // 记录 = 已经报过名了；能不能取消看平台给的 canCancel（记录视角的取消入口以它为准）
    applied = true,
    canCancel = canCancel,
    applyId = applyId,
)

/** `POST /api/volunteer/star_cert` 的 `data`：星级证书（总工时 + 星级）。 */
@Serializable
data class WenquStarCertData(
    @Serializable(with = LenientStringSerializer::class)
    @SerialName("volunteer_score_total")
    val scoreTotal: String? = null,
    @SerialName("star_level") val starLevel: Int = 0,
)

/**
 * `GET /api/volunteer/star_cert/download` 的 `data`：**星级证书 PDF**（2026-10-15）。
 *
 * 平台不回文件流，回的是 JSON——`pdf_base64` 是整份 PDF 的 base64，`filename` 是它给的
 * 文件名（**可能含姓名**，只用来命名落盘文件，不进日志）。`size` 是字节数。
 */
@Serializable
data class WenquCertData(
    @SerialName("pdf_base64") val pdfBase64: String = "",
    val filename: String = "",
    val size: Long = 0,
)

/**
 * `POST /api/volunteer/v2` 的 `data`：平台上的**志愿服务活动**（分页）。
 *
 * 与 [WenquVolunteerData] 是两回事：那个是「我参加过的」（`volunteer/my`），
 * 这个是「平台上有的」（可报名列表，就是小程序首页「水利文化 → 劳动教育」进去那张表）。
 * 两个列表的字段名有重叠（`title` / `unit` / `start_time`）但语义不同，别互相复用解析。
 */
@Serializable
data class WenquActivityData(
    val list: List<WenquActivity> = emptyList(),
    /** 我已请假次数（平台按「2 次自动拉黑」算）。本轮不展示——它与报名有关，见 DESIGN §4.45。 */
    @SerialName("qj_count") val leaveCount: Int = 0,
    /** 我已无故缺席次数。同上。 */
    @SerialName("qx_count") val absentCount: Int = 0,
    @SerialName("isBlack") val blacklisted: Boolean = false,
    @SerialName("stop_apply_reason") val stopApplyReason: String = "",
)

/**
 * 一条志愿服务活动。
 *
 * 列表与详情**共用这一个模型**：详情响应是列表的超集（多出 `score` / `group_id` 等），
 * 共用一个类省掉一份几乎逐字重复的声明；列表里没有的字段走默认值。
 *
 * **只声明界面要用的字段**。`leader_name` / `leader_phone`（活动负责人）**要展示**，
 * 所以声明了（2026-10-08 用户要求详情显示负责人与电话）；`attendance_name` /
 * `attendance_phone`（考勤人）**不声明**——`ignoreUnknownKeys` 下它们连内存都不进。
 *
 * 红线：负责人姓名与电话**只用于详情弹层展示**，不进日志、不进任何持久化、
 * **不进测试夹具与文档**（这个仓库是公开的，夹具里一律用假名假号）。
 */
@Serializable
data class WenquActivity(
    val id: Long = 0,
    /** 所属项目（如「某支教志愿服务」）——活动名前面那个大类。 */
    @SerialName("project_name") val projectName: String = "",
    /** 活动名（如「2026年10月13日某学院某支教志愿服务」）。 */
    val title: String = "",
    val unit: String = "",
    val address: String = "",
    /** 活动负责人姓名（**只展示**，见类注释红线）。 */
    @SerialName("leader_name") val leaderName: String = "",
    /** 活动负责人电话（**只展示**，见类注释红线）。 */
    @SerialName("leader_phone") val leaderPhone: String = "",
    @SerialName("apply_start_time") val applyStartTime: String = "",
    @SerialName("apply_end_time") val applyEndTime: String = "",
    @SerialName("start_time") val startTime: String = "",
    @SerialName("end_time") val endTime: String = "",
    /** 招募人数。平台有整格不填的时候（0 = 没写）。 */
    @SerialName("people_num") val peopleNum: Int = 0,
    @SerialName("join_num") val joinNum: Int = 0,
    /** 0 已取消 / 1 招募中 / 2 进行中 / 3 已结束（文案见 [edu.jxslu.schedule.domain.WenquRules.statusLabel]）。 */
    val status: Int = 0,
    /** 活动说明，多行长文。 */
    val content: String = "",
    /** 剩余名额（平台口径）。 */
    @SerialName("balance_num") val balanceNum: Int = 0,
    /** 我报名了没（要登录才准）。 */
    @SerialName("isApply") val applied: Boolean = false,
    /**
     * 平台算好的「这条报名现在还能不能取消」。**只有 `volunteer/my` 的记录有**，
     * 活动列表 / 详情都不回——所以它只在**记录视角**（[asActivity]）有意义；
     * 活动视角的取消入口走「已报名 + 招募中」自己判（见 `WenquRules.canCancel`）。
     */
    @SerialName("canCancel") val canCancel: Boolean = false,
    /** **详情才有**：本活动计多少工时。平台有时回数字有时回字符串。 */
    @Serializable(with = LenientStringSerializer::class)
    val score: String? = null,
    /** **详情才有**：活动群号（平台回裸数字，走宽容串）。 */
    @Serializable(with = LenientStringNonNullSerializer::class)
    @SerialName("group_id")
    val groupId: String = "",
    /**
     * 我对这条活动的**报名 id**。**不是接口字段**——`volunteer/v2` 的列表项没有它，
     * 由仓库在详情（`data.apply.id`）与记录（`apply_id`）里补上，专供取消报名兜底
     * （见 [WenquRepository.cancelApply]）。没报名 / 认不出 = 0。
     */
    @Transient val applyId: Long = 0,
)

/** `POST /api/volunteer/detail/{id}` 的 `data`。 */
@Serializable
data class WenquActivityDetailData(
    val detail: WenquActivity? = null,
    /** **我对这条活动的报名记录**；没报名是 null（平台一直回，2026-10-11 起声明进模型）。 */
    val apply: WenquApply? = null,
    @SerialName("qj_count") val leaveCount: Int = 0,
    @SerialName("qx_count") val absentCount: Int = 0,
    @SerialName("isBlack") val blacklisted: Boolean = false,
    @SerialName("stop_apply_reason") val stopApplyReason: String = "",
)

/**
 * 我对某条活动的**报名记录**（`volunteer/detail/{id}` 的 `apply` 段）。
 *
 * **只声明取消要用到的字段**。它含一堆个人字段（`username` / `student_no` / `college` /
 * `major` / `dormitory_no` …）——那些**一律不声明**，`ignoreUnknownKeys` 下连内存都不进
 * （同 [WenquActivity] 对 `attendance_*` 的处理）。
 */
@Serializable
data class WenquApply(
    /** 报名 id（取消报名的候选 id，见 [WenquApi.volunteerCancel]）。 */
    val id: Long = 0,
    /** 活动 id（与 [WenquActivity.id] 同）。 */
    @SerialName("aid") val aid: Long = 0,
    /** 报名状态（记录列表里的 `apply_status` 同源）。 */
    val status: Int = 0,
)

/** 把 `data` 段解成 [T]；缺失或解析失败回 null（调用方按空处理，不整批失败）。 */
inline fun <reified T> JsonElement?.decodeData(): T? {
    val payload = this ?: return null
    return try {
        QiekjJson.json.decodeFromJsonElement<T>(payload)
    } catch (_: SerializationException) {
        null
    }
}

/** 业务错误（非会话失效）。 */
class WenquApiException(
    message: String,
    val status: Int = -1,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 会话失效（`status:410000 请登录`）：App 侧应重新登录。 */
class WenquSessionExpiredException(message: String) : Exception(message)
