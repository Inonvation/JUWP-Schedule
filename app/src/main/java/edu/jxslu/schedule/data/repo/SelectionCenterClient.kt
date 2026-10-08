package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.data.jw.SelectionCenterParser
import edu.jxslu.schedule.domain.SelectionCourse
import edu.jxslu.schedule.domain.SelectionQuery

/** 提交选课的结果（DESIGN §4.36）。 */
sealed interface SelectionSubmitResult {
    /** 教务受理（[message] 是教务原话，可能为空）。 */
    data class Success(val message: String = "") : SelectionSubmitResult

    /** 明确被拒（名额已满 / 时间冲突 / 已选过…）：换目标或下轮再试，**不算会话级失败**。 */
    data class Rejected(val reason: String) : SelectionSubmitResult

    /** 需要人工处理（会话失效 / 验证码 / 接口不可用）：**立即停止整场会话**。 */
    data class Fatal(val reason: String) : SelectionSubmitResult
}

/**
 * 选课接口失败。[fatal] = true 表示需要人工（会话失效、验证码、接口不可用），
 * 界面据此把操作报成「已停止」并给出下一步；false = 可重试的传输/服务端异常。
 */
class SelectionClientException(
    val fatal: Boolean,
    message: String,
) : Exception(message)

/**
 * 选课中心的数据接口（DESIGN §4.36）：应用内选课中心与「预选命中」查询唯一依赖的抽象。
 *
 * 真身是 [SelectionCenterHttpClient]（OkHttp + `CasSession`，2026-10-08 窗口期实测接入）；
 * [UnconfiguredSelectionCenterClient] 只在接口不可用的极端场景兜底（如实说明，不假装能选）。
 */
interface SelectionCenterClient {

    /** 是否接了真实接口（页面据此禁用「查询」与「加载更多」并给出原因）。 */
    val isConfigured: Boolean

    /**
     * 拉取候选课程（**预选命中口径**：多关键词逐个查、分页合并去重）；失败抛
     * [SelectionClientException]。
     *
     * [keywords] = 要搜的课程名/编号关键词（选课中心按**预选清单逐条**传课程名关键词，
     * 命中课程优先显示；教师关键词由 `SelectionWishes.matches` 在本地复核）。
     * 教务列表接口要求 关键词/教师/星期 至少一个非空（空查询回 0 行，实测原话
     * 「选课课程默认不加载」）；[keywords] 全空白回空表。
     */
    suspend fun fetchCourses(roundId: String, keywords: List<String>): List<SelectionCourse>

    /**
     * 拉取**一页**课程列表（浏览/筛选/翻页口径，2026-10-08 v2）：[query] 直接对应
     * 教务列表页的完整筛选面（空条件自动换成浏览全量，见 `SelectionCenterParser`）。
     *
     * 与 [fetchCourses] 的区别是**不合并、不自主翻页**——一次调用 = 一次教务请求，
     * 界面自己用 [start] + 返回值的 `total` 驱动「加载更多」。失败抛
     * [SelectionClientException]。
     */
    suspend fun fetchPage(
        roundId: String,
        query: SelectionQuery,
        start: Int = 0,
        pageSize: Int = SelectionCenterParser.PAGE_SIZE,
    ): SelectionCenterParser.CourseListPage

    /** 提交选课。 */
    suspend fun submit(roundId: String, courseId: String): SelectionSubmitResult

    /**
     * 退课。**只由用户在应用内逐次确认触发**（红线：没有任何自动退课路径）。
     */
    suspend fun drop(roundId: String, courseId: String): SelectionSubmitResult
}

/**
 * 未接入实现（2026-10-01 建；真身已接入后留作测试替身与兜底）。所有调用都返回可读的
 * 「未接入」，界面上明说，不假装能选。
 */
object UnconfiguredSelectionCenterClient : SelectionCenterClient {

    const val FATAL_HINT =
        "选课接口不可用：请先登录教务，或稍后重试（DESIGN §4.36）"

    override val isConfigured: Boolean = false

    override suspend fun fetchCourses(roundId: String, keywords: List<String>): List<SelectionCourse> =
        throw SelectionClientException(fatal = true, message = FATAL_HINT)

    override suspend fun fetchPage(
        roundId: String,
        query: SelectionQuery,
        start: Int,
        pageSize: Int,
    ): SelectionCenterParser.CourseListPage =
        throw SelectionClientException(fatal = true, message = FATAL_HINT)

    override suspend fun submit(roundId: String, courseId: String): SelectionSubmitResult =
        SelectionSubmitResult.Fatal(FATAL_HINT)

    override suspend fun drop(roundId: String, courseId: String): SelectionSubmitResult =
        SelectionSubmitResult.Fatal(FATAL_HINT)
}
