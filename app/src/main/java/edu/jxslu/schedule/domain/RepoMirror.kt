package edu.jxslu.schedule.domain

/**
 * 公开仓库（`docs-public/`）文件的**多源镜像地址**。
 *
 * 2026-10-06 统一收口到这里。两处订正（当天实测）：
 * ① ref 统一用真实分支 `main`：旧 URL 写的 `master` 在 raw 与 jsdelivr 会被当默认分支
 *    旧名**兼容解析**（实测能取到内容），但 GitHub API 不认这个 ref（`No commit found`）
 *    ——依赖兼容行为不可靠，已全部替换，测试钉住不留 `master`；
 * ② 无代理环境下 `raw.githubusercontent.com` 直连经常不可达，且旧实现单源网络异常
 *    会中断整轮轮询——源顺序必须把公共加速源排前面、逐源容错。
 *
 * 源顺序按**实测可达性**排定（2026-10-06，无代理直连网络）：
 * 1. `ghfast.top` / `gh-proxy.com`——gh-proxy 类加速（经 raw），直连最快（~1s）且内容即时；
 * 2. `cdn.jsdelivr.net` / `fastly.jsdelivr.net`——全球 CDN（~3s；分支引用有小时级缓存，
 *    故排在加速源之后）；
 * 3. `raw.githubusercontent.com`——原源，仅作兜底（无代理环境经常不可达，有代理时可用）。
 *
 * 调用方逐源尝试、首个成功即用。本对象只拼 URL（纯逻辑，JVM 可测）；内容均为
 * 公开仓库的只读文件，不含任何用户数据。
 */
object RepoMirror {

    /** 仓库默认分支（真实存在；旧 URL 曾写 `master`，别依赖 raw 侧的兼容解析，见类注释）。 */
    const val BRANCH: String = "main"

    private const val REPO = "Inonvation/JUWPHub"

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
