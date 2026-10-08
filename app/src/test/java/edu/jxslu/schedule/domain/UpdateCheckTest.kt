package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查更新纯逻辑（[UpdateCheck]）的钉子：清单解析 / 版本比对 / 双通道链接拼装。
 */
class UpdateCheckTest {

    // ---------- parseManifest ----------

    @Test
    fun `解析完整清单`() {
        val manifest = UpdateCheck.parseManifest(
            """
            {"version":"2.3.0","apk":"JUWP-Schedule-2.3.0.apk",
             "sha256":"ABCD0123abcd0123abcd0123abcd0123abcd0123abcd0123abcd0123abcd0123",
             "notes":"更新内容：\n- 修复若干",
             "mirrorPrefix":"https://ghproxy.example/","extra":"忽略我"}
            """.trimIndent(),
        )!!
        assertEquals("2.3.0", manifest.version)
        assertEquals("JUWP-Schedule-2.3.0.apk", manifest.apkName)
        // SHA-256 统一转小写
        assertEquals("abcd0123abcd0123abcd0123abcd0123abcd0123abcd0123abcd0123abcd0123", manifest.sha256)
        assertEquals("https://ghproxy.example/", manifest.mirrorPrefix)
        assertTrue(manifest.notes.startsWith("更新内容"))
    }

    @Test
    fun `apk 缺省时按发版口径补资产名`() {
        val manifest = UpdateCheck.parseManifest("""{"version":"2.3.0"}""")!!
        assertEquals("JUWP-Schedule-2.3.0.apk", manifest.apkName)
    }

    @Test
    fun `脏清单返回null - 坏json_版本号不合规_超长响应`() {
        assertNull(UpdateCheck.parseManifest("not json at all"))
        assertNull(UpdateCheck.parseManifest("""{"version":"v2.3.0"}"""))
        assertNull(UpdateCheck.parseManifest("""{"version":"2.3"}"""))
        assertNull(UpdateCheck.parseManifest("""{"version":"2.3.0","apk":"../evil.apk"}"""))
        assertNull(UpdateCheck.parseManifest("""{"version":"2.3.0","apk":"a b.apk"}"""))
        assertNull(UpdateCheck.parseManifest("x".repeat(512 * 1024 + 1)))
    }

    // ---------- parseVersion / isNewer ----------

    @Test
    fun `版本解析去debug后缀`() {
        assertEquals(listOf(2, 2, 0), UpdateCheck.parseVersion("2.2.0"))
        assertEquals(listOf(2, 2, 0), UpdateCheck.parseVersion("2.2.0-debug"))
        assertNull(UpdateCheck.parseVersion("abc"))
    }

    @Test
    fun `逐段数字比较不是字符串比较`() {
        assertTrue(UpdateCheck.isNewer("2.10.0", "2.9.0"))
        assertFalse(UpdateCheck.isNewer("2.9.0", "2.10.0"))
        assertTrue(UpdateCheck.isNewer("3.0.0", "2.9.9"))
        assertTrue(UpdateCheck.isNewer("2.2.1", "2.2.0"))
    }

    @Test
    fun `相等或更低不算更新_两侧解析不了算不更新`() {
        assertFalse(UpdateCheck.isNewer("2.2.0", "2.2.0"))
        assertFalse(UpdateCheck.isNewer("2.1.0", "2.2.0"))
        // 当前版本是 debug 后缀照样比对
        assertFalse(UpdateCheck.isNewer("2.2.0", "2.2.0-debug"))
        assertTrue(UpdateCheck.isNewer("2.3.0", "2.2.0-debug"))
        // 任一侧脏版本：宁可漏报不误报
        assertFalse(UpdateCheck.isNewer("bad", "2.2.0"))
        assertFalse(UpdateCheck.isNewer("2.3.0", "bad"))
    }

    // ---------- 自动检查的忽略过滤（shouldAutoPrompt） ----------

    @Test
    fun `没忽略过任何版本时自动弹`() {
        assertTrue(UpdateCheck.shouldAutoPrompt("2.3.0", ""))
    }

    @Test
    fun `空白忽略值视为未忽略`() {
        assertTrue(UpdateCheck.shouldAutoPrompt("2.3.0", "   "))
    }

    @Test
    fun `忽略的版本不再自动弹`() {
        assertFalse(UpdateCheck.shouldAutoPrompt("2.3.0", "2.3.0"))
    }

    @Test
    fun `出现不同版本恢复自动弹`() {
        // 忽略的是旧版本、清单已到更高版本：恢复弹（手动检查本来就不经过这里）
        assertTrue(UpdateCheck.shouldAutoPrompt("2.4.0", "2.3.0"))
    }

    // ---------- 下载链接 ----------

    private val manifest = UpdateCheck.parseManifest(
        """{"version":"2.3.0","mirrorPrefix":"https://ghproxy.example/"}""",
    )!!
    private val noMirror = UpdateCheck.parseManifest("""{"version":"2.3.0"}""")!!

    @Test
    fun `GitHub直链由发版口径拼出`() {
        assertEquals(
            "https://github.com/Inonvation/JUWP-Schedule/releases/download/2.3.0/JUWP-Schedule-2.3.0.apk",
            UpdateCheck.githubDownloadUrl(manifest),
        )
    }

    @Test
    fun `镜像直链等于前缀加GitHub直链`() {
        assertEquals(
            "https://ghproxy.example/https://github.com/Inonvation/JUWP-Schedule/releases/download/2.3.0/JUWP-Schedule-2.3.0.apk",
            UpdateCheck.mirrorDownloadUrl(manifest),
        )
    }

    @Test
    fun `镜像前缀缺失或非https时镜像通道不可用`() {
        assertNull(UpdateCheck.mirrorDownloadUrl(noMirror))
        val httpPrefix = UpdateCheck.parseManifest(
            """{"version":"2.3.0","mirrorPrefix":"http://ghproxy.example/"}""",
        )!!
        assertNull(UpdateCheck.mirrorDownloadUrl(httpPrefix))
    }

    // ---------- SHA-256 可用性 ----------

    @Test
    fun `64位十六进制才算可用digest`() {
        assertTrue(
            UpdateCheck.hasUsableDigest(
                UpdateCheck.parseManifest(
                    """{"version":"2.3.0","sha256":"${"a".repeat(64)}"}""",
                )!!,
            ),
        )
        assertFalse(UpdateCheck.hasUsableDigest(noMirror))
        assertFalse(
            UpdateCheck.hasUsableDigest(
                UpdateCheck.parseManifest("""{"version":"2.3.0","sha256":"abc"}""")!!,
            ),
        )
    }
}
