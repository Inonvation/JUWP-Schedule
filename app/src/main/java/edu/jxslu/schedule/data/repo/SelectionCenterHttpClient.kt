package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.data.jw.JwHttpSession.JwHttpException
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.jw.SelectionCenterParser
import edu.jxslu.schedule.data.session.AutoSyncRules
import edu.jxslu.schedule.data.session.CasSession
import edu.jxslu.schedule.domain.SelectionCourse
import edu.jxslu.schedule.domain.SelectionQuery
import java.net.URLEncoder
import java.util.Collections
import kotlinx.coroutines.delay

/**
 * 选课中心真实客户端（DESIGN §4.36 C 阶段；2026-10-08 窗口期实测接入）。
 *
 * 覆盖「本学期计划选课」这一类目（自主选课轮次的主列表）：
 * - 列表 = POST `xsxkBxqjhxk`（旧版 DataTables 参数，见 [SelectionCenterParser.listBody]）；
 * - 提交 = GET `bxqjhxkOper`；退课 = GET `xstkOper`（都照教务页面 JS 的请求形状复刻）。
 *
 * 三条纪律：
 * 1. **写操作不自动重试**：提交 / 退课走 `CasSession.fetchForAction`（会话被顶不重登重发），
 *    防重复提交；读路径（列表）走 `CasSession.postForm`，可重登重试一次；
 * 2. **进入轮次是会话前提**（2026-10-08 实测）：先 GET 「进入选课」页 + 列表壳页（校验窗口
 *    状态、读验证码开关），之后进程内复用——见 [prepare]。列表请求撞 404 页 = 会话重登后
 *    丢了这个状态，[fetchPageRetrying] 会重进一次再试（读语义，只重试一次）；
 * 3. 教务要求 关键词/教师/星期 至少一个非空（全空回 0 行）；**浏览全量**由
 *    `SelectionCenterParser.listQuery` 把空关键词换成专用搜索串（见 BROWSE_KEYWORD）。
 *
 * 未覆盖（留档）：跨年级 / 跨专业两个类目各有自己的列表与提交端点
 * （`xsxkKnjxk` / `xsxkFawxk`、`knjxkOper` / `fawxkOper`），本轮不做；
 * 带 `|` 的主键拆分见 [SelectionCenterParser.splitCourseKey]。
 */
class SelectionCenterHttpClient(
    private val cas: CasSession,
    /** 关键词之间的礼貌间隔（默认走项目统一的 200–600ms 随机；仅测试注入用）。 */
    private val pause: suspend () -> Unit = { delay(AutoSyncRules.interRequestDelayMs()) },
    /**
     * debug 包专用：提交/退课应答原文落盘（[SelectionDebugDump]，release 传 null）。
     * 联调期校准三态映射用——只落写操作的应答，列表不落（样本在各处已有）。
     */
    private val debugDump: SelectionDebugDump? = null,
) : SelectionCenterClient {

    override val isConfigured: Boolean = true

    /** 已「进入」过的轮次（进程内）；读写共用——写路径进入失败会清掉重试。 */
    private val prepared: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    override suspend fun fetchCourses(roundId: String, keywords: List<String>): List<SelectionCourse> {
        requireRound(roundId)
        val clean = keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return emptyList()
        try {
            prepare(roundId)
            val merged = LinkedHashMap<String, SelectionCourse>()
            clean.forEachIndexed { index, keyword ->
                if (index > 0) pause()
                fetchKeyword(merged, roundId, keyword)
            }
            return merged.values.toList()
        } catch (e: SelectionClientException) {
            if (e.fatal) prepared.remove(roundId)
            throw e
        }
    }

    override suspend fun fetchPage(
        roundId: String,
        query: SelectionQuery,
        start: Int,
        pageSize: Int,
    ): SelectionCenterParser.CourseListPage {
        requireRound(roundId)
        try {
            prepare(roundId)
            return fetchPageRetrying(roundId, query, start, pageSize)
        } catch (e: SelectionClientException) {
            if (e.fatal) prepared.remove(roundId)
            throw e
        }
    }

    override suspend fun submit(roundId: String, courseId: String): SelectionSubmitResult {
        requireRound(roundId)
        val (jx0404id, jx02id, cfbs) = SelectionCenterParser.splitCourseKey(courseId)
            ?: return SelectionSubmitResult.Fatal("课程标识不合法，请刷新列表后重试")
        val url = buildString {
            append(JwUrls.SELECTION_PLAN_SUBMIT)
            append("?kcid=").append(encode(jx02id))
            // 页面 JS 会把空的 cfbs 拼成字面量 "null"（`"" + null`）——照抄，别自作聪明
            append("&cfbs=").append(encode(cfbs.ifBlank { "null" }))
            append("&jx0404id=").append(encode(jx0404id))
            append("&xkzy=&trjf=&sfsyjc=&sfkvtj=")
        }
        return runAction(roundId, url, action = "submit")
    }

    override suspend fun drop(roundId: String, courseId: String): SelectionSubmitResult {
        requireRound(roundId)
        val (jx0404id, _, _) = SelectionCenterParser.splitCourseKey(courseId)
            ?: return SelectionSubmitResult.Fatal("课程标识不合法，请刷新列表后重试")
        return runAction(roundId, "${JwUrls.SELECTION_DROP}?jx0404id=${encode(jx0404id)}", action = "drop")
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 进入轮次（每个轮次一次）：GET「进入选课」页 + 列表壳页。
     * 校验顺序：窗口未开 → 可读错误；验证码开启 → 直接拒绝（红线：遇验证码停，不绕）。
     */
    private suspend fun prepare(roundId: String) {
        if (roundId in prepared) return
        val entryUrl = JwUrls.selectionRoundUrl(roundId)
            ?: throw SelectionClientException(fatal = true, message = "轮次 id 不合法：$roundId")
        val entry = cas.fetchHtml(entryUrl)
            ?: throw SelectionClientException(fatal = true, message = SESSION_UNAVAILABLE)
        if (SelectionCenterParser.notInWindow(entry)) {
            throw SelectionClientException(fatal = true, message = SelectionCenterParser.NOT_IN_WINDOW_TEXT)
        }
        if (SelectionCenterParser.noOpen(entry)) {
            throw SelectionClientException(fatal = true, message = "教务选课功能暂未开放（no-open 页）")
        }
        val shell = cas.fetchHtml(JwUrls.SELECTION_PLAN_LIST_SHELL)
            ?: throw SelectionClientException(fatal = true, message = SESSION_UNAVAILABLE)
        if (SelectionCenterParser.captchaEnabled(shell)) {
            throw SelectionClientException(
                fatal = true,
                message = "本轮启用了验证码选课，App 内不支持（不绕过验证码）——请在教务页面操作",
            )
        }
        prepared += roundId
    }

    /**
     * 单个关键词抓取（**预选命中口径**：`SelectionQuery` 默认开关 = 保留冲突与已满——
     * 命中就是要看到全部候选班级（含冲突/满员，带说明），滤掉反而看不到）；按
     * `iTotalRecords` 兜底翻页，最多 [MAX_PAGES] 页防失控。
     */
    private suspend fun fetchKeyword(
        merged: MutableMap<String, SelectionCourse>,
        roundId: String,
        keyword: String,
    ) {
        val query = SelectionQuery(keyword = keyword)
        var seen = 0
        var start = 0
        repeat(MAX_PAGES) {
            val page = fetchPageRetrying(roundId, query, start, SelectionCenterParser.PAGE_SIZE)
            page.rows.forEach { merged[it.id] = it }
            seen += page.rows.size
            if (seen >= page.total || page.rows.isEmpty()) return
            start += SelectionCenterParser.PAGE_SIZE
            pause()
        }
    }

    /**
     * 单页拉取 + 「重新进轮次」自愈（2026-10-08 实测）：列表接口要求会话先 GET 过
     * `newXsxkzx` 进轮次——会话被顶后重登（读路径的重登重试）会丢掉这个状态，之后的
     * 列表请求一律回教务 404 错误页。撞 404 就重进轮次再试**一次**（读语义，可重试；
     * 与写路径的「不自动重发」是两条纪律，别并）。
     */
    private suspend fun fetchPageRetrying(
        roundId: String,
        query: SelectionQuery,
        start: Int,
        pageSize: Int,
    ): SelectionCenterParser.CourseListPage {
        val url = listUrl(query)
        var text = cas.postForm(url, SelectionCenterParser.listBody(start = start, pageSize = pageSize))
            ?: throw SelectionClientException(fatal = true, message = SESSION_UNAVAILABLE)
        if (SelectionCenterParser.qzNotFound(text)) {
            prepared.remove(roundId)
            prepare(roundId)
            text = cas.postForm(url, SelectionCenterParser.listBody(start = start, pageSize = pageSize))
                ?: throw SelectionClientException(fatal = true, message = SESSION_UNAVAILABLE)
        }
        return try {
            SelectionCenterParser.parseList(text)
        } catch (e: IllegalStateException) {
            throw SelectionClientException(fatal = true, message = e.message ?: "选课列表无法识别")
        }
    }

    /** 列表请求 URL：查询条件全在 URL 上（`listQuery` 的键序照页面 `queryKxkcList`）。 */
    private fun listUrl(query: SelectionQuery): String {
        val params = SelectionCenterParser.listQuery(query)
            .joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        return "${JwUrls.SELECTION_PLAN_LIST_API}?$params"
    }

    /** 写操作：进入轮次（失败映射 Fatal）→ 单次 GET（不重登重发）→ 三态映射。 */
    private suspend fun runAction(
        roundId: String,
        url: String,
        action: String,
    ): SelectionSubmitResult {
        try {
            prepare(roundId)
        } catch (e: SelectionClientException) {
            prepared.remove(roundId)
            return SelectionSubmitResult.Fatal(e.message ?: SESSION_UNAVAILABLE)
        }
        val text = try {
            cas.fetchForAction(url)
        } catch (e: JwHttpException.SessionInvalid) {
            return SelectionSubmitResult.Fatal("教务会话已失效（可能被别处登录顶掉），请重新登录后再试")
        } catch (e: JwHttpException.Network) {
            return SelectionSubmitResult.Rejected("网络异常，提交结果未知：${e.message}")
        } catch (e: JwHttpException) {
            return SelectionSubmitResult.Fatal("教务链路异常：${e.message}")
        }
        // debug 包留原文（release 为 null）：联调期校准三态映射的唯一一手材料
        debugDump?.dump(action, text)
        val outcome = try {
            SelectionCenterParser.parseAction(text)
        } catch (e: IllegalStateException) {
            return SelectionSubmitResult.Fatal(e.message ?: "教务应答无法识别")
        }
        return when {
            outcome.success && !outcome.conflictPending -> SelectionSubmitResult.Success(outcome.message)
            SelectionCenterParser.isFatalMessage(outcome.message) ->
                SelectionSubmitResult.Fatal(outcome.message)
            else -> SelectionSubmitResult.Rejected(outcome.message)
        }
    }

    private fun requireRound(roundId: String) {
        if (!JwUrls.ROUND_ID_PATTERN.matches(roundId)) {
            throw SelectionClientException(fatal = true, message = "轮次 id 不合法：$roundId")
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val MAX_PAGES = 3
        const val SESSION_UNAVAILABLE = "教务会话不可用，请在导入页重新登录后重试"
    }
}
