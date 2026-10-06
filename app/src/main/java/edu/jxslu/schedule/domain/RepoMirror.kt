package edu.jxslu.schedule.domain

/**
 * 公开仓库（`docs-public/`）文件的**多源镜像地址**。
 *
 * 2026-10-06 修复两处硬伤后统一收口到这里：
 * ① 分支曾误写 `master`——仓库实际只有 `main`，所有请求 404；
 * ② 无代理环境下 `raw.githubusercontent.com` 直连超时。
 *
 * 源顺序按**实测可达性**排定（2026-10-06，无代理直连网络）：
 * 1. `ghfast.top` / `gh-proxy.com`——gh-proxy 类加速（经 raw），直连最快（~1s）且内容即时；
 * 2. `cdn.jsdelivr.net` / `fastly.jsdelivr.net`——全球 CDN（~3s；分支引用有小时级缓存，
 *    故排在加速源之后）；
 * 3. `raw.githubusercontent.com`——原源，仅作兜底（无代理环境不可达，有代理时可用）。
 *
 * 调用方逐源尝试、首个成功即用。本对象只拼 URL（纯逻辑，JVM 可测）；内容均为
 * 公开仓库的只读文件，不含任何用户数据。
 */
object RepoMirror {

    /** 仓库默认分支。注意：只有 `main`，没有 `master`（踩过坑，见类注释）。 */
    const val BRANCH: String = "main"

    private const val REPO = "Inonvation/JUWP-Schedule"

    /** raw 直链前缀（gh-proxy 类源基于它加前缀，jsdelivr 类源是另一种拼法）。 */
    private const val RAW_BASE = "https://raw.githubusercontent.com/$REPO/$BRANCH"

    /** 按可达性排序的源前缀表（顺序即尝试顺序）。 */
    private val SOURCE_PREFIXES: List<String> = listOf(
        "https://ghfast.top/$RAW_BASE",
        "https://gh-proxy.com/$RAW_BASE",
        "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH",
        "https://fastly.jsdelivr.net/gh/$REPO@$BRANCH",
        RAW_BASE,
    )

    /**
     * 仓库内某文件的按序源地址；[path] 为仓库相对路径（如 `docs-public/update/latest.json`）。
     * 路径由调用方以白名单校验后的值拼接（本对象不做校验）。
     */
    fun urls(path: String): List<String> = SOURCE_PREFIXES.map { "$it/$path" }
}
