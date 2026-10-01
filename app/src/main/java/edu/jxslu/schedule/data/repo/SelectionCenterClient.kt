package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.domain.SelectionCourse

/** 提交选课的结果（DESIGN §4.36）。 */
sealed interface SelectionSubmitResult {
    /** 教务受理（[message] 是教务原话，可能为空）。 */
    data class Success(val message: String = "") : SelectionSubmitResult

    /** 明确被拒（名额已满 / 时间冲突 / 已选过…）：换目标或下轮再试，**不算会话级失败**。 */
    data class Rejected(val reason: String) : SelectionSubmitResult

    /** 需要人工处理（会话失效 / 验证码 / 接口未接入）：**立即停止整场会话**。 */
    data class Fatal(val reason: String) : SelectionSubmitResult
}

/**
 * 选课接口失败。[fatal] = true 表示需要人工（会话失效、验证码、接口未接入），
 * 抢课引擎据此收场；false = 可重试的传输/服务端异常，计入连续失败。
 */
class SelectionClientException(
    val fatal: Boolean,
    message: String,
) : Exception(message)

/**
 * 选课中心的数据接口（DESIGN §4.36）：抢课引擎唯一依赖的抽象。
 *
 * 真实实现要等**选课窗口期**实测（课程列表页是服务端渲染还是 JSON、提交接口的参数与
 * 返回码、限流口径），在那之前用 [UnconfiguredSelectionCenterClient]——所有调用都返回
 * 可读的「未接入」，界面上明说，不假装能抢。
 */
interface SelectionCenterClient {

    /** 是否接了真实接口（页面据此禁用「开始抢课」）。 */
    val isConfigured: Boolean

    /** 拉取轮次下的候选课程；失败抛 [SelectionClientException]。 */
    suspend fun fetchCourses(roundId: String): List<SelectionCourse>

    /** 提交选课。 */
    suspend fun submit(roundId: String, courseId: String): SelectionSubmitResult

    /**
     * 退课。**只由用户在应用内逐次确认触发**——抢课引擎不调用它（红线：只加课不自动退课）。
     */
    suspend fun drop(roundId: String, courseId: String): SelectionSubmitResult
}

/**
 * 未接入实现（2026-10-01 建）：窗口期联调后由真实 client 替换，替换点只有
 * `Graph.selectionCenterClient` 一处。
 */
object UnconfiguredSelectionCenterClient : SelectionCenterClient {

    const val FATAL_HINT =
        "选课接口未接入：课程列表与提交接口要等选课窗口期实测后才能启用（DESIGN §4.36）"

    override val isConfigured: Boolean = false

    override suspend fun fetchCourses(roundId: String): List<SelectionCourse> =
        throw SelectionClientException(fatal = true, message = FATAL_HINT)

    override suspend fun submit(roundId: String, courseId: String): SelectionSubmitResult =
        SelectionSubmitResult.Fatal(FATAL_HINT)

    override suspend fun drop(roundId: String, courseId: String): SelectionSubmitResult =
        SelectionSubmitResult.Fatal(FATAL_HINT)
}
