package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库镜像多源清单（[RepoMirror]）的钉子——2026-10-06 两处硬伤的回归防线：
 * ① 分支名错（曾写 `master`，仓库只有 `main`，所有请求 404）；
 * ② raw 直连不可达（源顺序须把无代理可达的加速源排在前面）。
 */
class RepoMirrorTest {

    @Test
    fun `branch is main and no source uses master`() {
        assertEquals("main", RepoMirror.BRANCH)
        val urls = RepoMirror.urls("docs-public/update/latest.json")
        assertTrue(urls.isNotEmpty())
        assertTrue(urls.none { it.contains("master") })
    }

    @Test
    fun `all urls are https and keep the given path suffix`() {
        val urls = RepoMirror.urls("docs-public/calendar/index.json")
        assertTrue(urls.all { it.startsWith("https://") })
        assertTrue(urls.all { it.endsWith("/docs-public/calendar/index.json") })
    }

    @Test
    fun `proxy accelerators come before raw fallback`() {
        val urls = RepoMirror.urls("a/b.json")
        // 首个源须是 gh-proxy 加速（无代理直连实测可达 ~1s）
        assertTrue(urls.first().startsWith("https://ghfast.top/https://raw.githubusercontent.com/"))
        assertTrue(urls.any { it.startsWith("https://gh-proxy.com/https://raw.githubusercontent.com/") })
        // jsdelivr 次之；raw 原源兜底最后
        assertTrue(urls.any { it.contains("cdn.jsdelivr.net") })
        assertTrue(urls.last().startsWith("https://raw.githubusercontent.com/"))
    }

    @Test
    fun `update check list comes from the same source table`() {
        // 「检查更新」与校历共用一份源表：防某一处再退化成单源直链
        assertEquals(
            RepoMirror.urls("docs-public/update/latest.json"),
            UpdateCheck.LIST_URLS,
        )
        assertFalse(UpdateCheck.LIST_URLS.isEmpty())
    }
}
