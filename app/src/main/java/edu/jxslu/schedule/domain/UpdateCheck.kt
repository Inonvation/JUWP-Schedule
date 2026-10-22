package edu.jxslu.schedule.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 检查更新的取数与判定规则（DESIGN §3.3）。
 *
 * 数据源 = 开发者仓库镜像 `docs-public/update/latest.json`（同校历 §4.34 的公开仓库
 * 镜像模式），**不请求 api.github.com**——GitHub API 域名国内直连经常不通。清单走
 * [RepoMirror] 的多源轮询（gh-proxy 加速 → jsdelivr → raw 兜底）后第一个可达的源
 * 就能给出版本、日志与直链；直链本身由发版口径推出（tag = versionName、资产名
 * `JUWPHub-<版本>.apk`，见 `.agents/skills/publish-release/SKILL.md`），
 * 不需要查 API。
 *
 * 镜像下载链接 = `mirrorPrefix` + GitHub 直链（gh-proxy 类服务的标准拼法）。前缀是
 * 时常会变的公共第三方服务，所以放清单里维护：失效时改一个 JSON 值即可，不用发版。
 * 镜像是第三方通道，真实性只能靠 SHA-256 自证——清单里的值来自发版流程对 APK 的
 * `Get-FileHash`，UI 展示出来供用户核对。
 *
 * 本对象只放纯判定（解析、比对、拼链接），网络在 `data/repo/UpdateCheckClient`。
 * 纯 Kotlin，JVM 可测。
 */
object UpdateCheck {

    /** 清单在仓库内的相对路径（多源完整地址由 [RepoMirror] 拼出）。 */
    private const val MANIFEST_PATH = "docs-public/update/latest.json"

    /** App 侧按顺序轮询的清单地址（第一个成功即用；源顺序见 [RepoMirror]）。 */
    val LIST_URLS: List<String> = RepoMirror.urls(MANIFEST_PATH)

    /** GitHub 直链前缀：`releases/download/<版本>/<资产名>`。 */
    private const val DOWNLOAD_BASE: String =
        "https://github.com/Inonvation/JUWPHub/releases/download"

    /** 发版 APK 资产命名（发版流程重命名步骤的口径，纯 ASCII）。 */
    private const val APK_NAME_TEMPLATE: String = "JUWPHub-%s.apk"

    /** 清单限长：远超实际大小（<2KB），只挡被改坏的巨型响应。 */
    private const val MAX_MANIFEST_BYTES = 512 * 1024

    /** latest.json 的形状（宽容解析：GitHub / jsdelivr 侧加字段不影响）。 */
    @Serializable
    internal data class ManifestDto(
        val version: String = "",
        val apk: String = "",
        val sha256: String = "",
        val notes: String = "",
        val mirrorPrefix: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** 版本号白名单：`主.次.修订` 纯数字（tag 不带 v 前缀，发版口径）。 */
    private val VERSION_RE = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

    /** 解析 latest.json；不合法（坏 JSON / 版本号不合规）返回 null。 */
    fun parseManifest(text: String): UpdateManifest? {
        if (text.length > MAX_MANIFEST_BYTES) return null
        val dto = runCatching { json.decodeFromString<ManifestDto>(text) }.getOrNull() ?: return null
        return toManifest(dto)
    }

    private fun toManifest(dto: ManifestDto): UpdateManifest? {
        val apk = dto.apk.ifBlank { APK_NAME_TEMPLATE.format(dto.version) }
        // 版本号与 APK 文件名都过白名单：文件名只用来拼下载链接，防注入怪 URL
        if (!VERSION_RE.matches(dto.version)) return null
        if (!APK_NAME_RE.matches(apk)) return null
        return UpdateManifest(
            version = dto.version,
            apkName = apk,
            sha256 = dto.sha256.trim().lowercase(),
            notes = dto.notes.trim(),
            mirrorPrefix = dto.mirrorPrefix.trim(),
        )
    }

    /** APK 资产名白名单：ASCII 文件名字符，且以 .apk 结尾。 */
    private val APK_NAME_RE = Regex("""^[A-Za-z0-9._-]+\.apk$""")

    /** 把 `BuildConfig.VERSION_NAME`（可能带 `-debug` 等变体后缀）拆成三段数字。 */
    fun parseVersion(versionName: String): List<Int>? {
        val numeric = versionName.substringBefore('-')
        val match = VERSION_RE.find(numeric) ?: return null
        val (major, minor, patch) = match.destructured
        return listOf(major.toInt(), minor.toInt(), patch.toInt())
    }

    /** 逐段数字比较：2.10.0 > 2.9.0；任一侧解析不了返回 false（宁可漏报不误报）。 */
    fun isNewer(latestVersion: String, currentVersionName: String): Boolean {
        val latest = parseVersion(latestVersion) ?: return false
        val current = parseVersion(currentVersionName) ?: return false
        for (i in 0..2) {
            when {
                latest[i] > current[i] -> return true
                latest[i] < current[i] -> return false
            }
        }
        return false
    }

    /**
     * 自动检查（冷启动）是否应弹「发现新版本」：忽略过这个版本就不再弹，
     * 出现更高版本恢复。**手动检查不经过这里**——关于页点「检查更新」永远弹。
     * 空白视为未忽略（偏好缺省 / 脏值）。
     */
    fun shouldAutoPrompt(latestVersion: String, ignoredVersion: String): Boolean =
        ignoredVersion.isBlank() || latestVersion != ignoredVersion

    /** GitHub Releases 直链（tag 与资产名都是发版口径的确定性值，不用查 API）。 */
    fun githubDownloadUrl(manifest: UpdateManifest): String =
        "$DOWNLOAD_BASE/${manifest.version}/${manifest.apkName}"

    /**
     * 镜像下载链接：gh-proxy 类服务 = 前缀 + 完整 GitHub 直链。前缀缺了就给 null，
     * UI 只出「GitHub 下载」一个出口。
     */
    fun mirrorDownloadUrl(manifest: UpdateManifest): String? {
        val prefix = manifest.mirrorPrefix
        if (prefix.isBlank()) return null
        if (!prefix.startsWith("https://")) return null
        return prefix + githubDownloadUrl(manifest)
    }

    /** 清单 SHA-256 是否形如 64 位十六进制（空/缺省视为「未提供」，UI 不显示该行）。 */
    fun hasUsableDigest(manifest: UpdateManifest): Boolean =
        manifest.sha256.matches(Regex("""^[0-9a-f]{64}$"""))
}

/**
 * latest.json 解析结果（已过校验）：一次检查更新需要的全部事实。
 */
data class UpdateManifest(
    /** 最新版本号，`主.次.修订`（发版口径：tag = versionName，不带 v 前缀）。 */
    val version: String,
    /** 发版 APK 资产名，如 `JUWPHub-2.2.0.apk`。 */
    val apkName: String,
    /** APK 的 SHA-256（十六进制小写）；发版时 `Get-FileHash` 写入，镜像通道的核对凭据。 */
    val sha256: String,
    /** 更新日志正文（发版流程用户确认过的那份）。 */
    val notes: String,
    /** 镜像下载前缀（gh-proxy 类，`https://` 开头）；空 = 镜像通道不可用。 */
    val mirrorPrefix: String,
)
