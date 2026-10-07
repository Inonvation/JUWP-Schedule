package edu.jxslu.schedule.data.jw

/**
 * 学生评教直达（DESIGN §4.44）。
 *
 * 评教没有静态 URL：教务主页菜单走 `toZlpt_11319('jwxsjdp')` —— POST 取 token、
 * `window.open(返回地址 + '/jwxsjdp')`。App 在落到 xsMainV 之后注入同款逻辑
 * （**同一个窗口内** `location.href` 跳转，不用 window.open），之后完全交给用户在
 * 评教平台里操作——**App 只导航，不碰任何提交接口**（与退课同一红线）。
 *
 * token 形态（2026-10-07 实测）：`http://jiaowupj.juwp.edu.cn/api/…/authentication/<JWT>`
 * （跨主机、JWT 约 4 小时）→ 拼 `/jwxsjdp` 302 到平台 `/home?xlgToken=…` 的 SPA。
 * `network_security_config` 已放行 `juwp.edu.cn` 全子域，WebView 可直接加载。
 */
object JwTeachingEval {

    /** 取 token 的接口（注入脚本在 xsMainV 上与原页面同源，用相对路径）。 */
    const val TOKEN_PATH = "/jsxsd/qzzlpt/getToken_11319"

    /** 评教平台域（getToken 返回地址的主机）。 */
    const val PLATFORM_HOST = "jiaowupj.juwp.edu.cn"

    /**
     * 注入脚本：取 token 成功就 `location.href` 跳转（**同一窗口**，不 window.open）。
     * 返回 `sent` / `dup` / `no-jquery` 供调用方判断是否真的发起——`evaluateJavascript`
     * 不 await Promise，跳转由页面自带 jQuery 的 `$.ajax` 回调驱动；发起之后没有导航的
     * 情况由调用侧的看门狗兜底。脚本里只有路径常量，**不含任何 token**。
     */
    fun launchJs(): String = """
        (function () {
          if (window.__jwPjStarted) return 'dup';
          window.__jwPjStarted = true;
          if (typeof $ === 'undefined' || !$.ajax) return 'no-jquery';
          $.ajax({
            url: '$TOKEN_PATH',
            type: 'post',
            cache: false,
            dataType: 'text',
            success: function (d) {
              d = (d || '').trim();
              if (d.indexOf('http') === 0) {
                location.href = d + '/jwxsjdp';
              }
            }
          });
          return 'sent';
        })();
    """.trimIndent()

    /** 当前页是否评教平台域（statusNote 分支、白屏探针跳过与日志脱敏共用判据）。 */
    fun isPlatformUrl(url: String?): Boolean = JwUrls.hostOf(url) == PLATFORM_HOST

    /**
     * 日志用 URL：评教域的地址里带 token（`/jwxsjdp` 路径带 JWT、`/home` 查询串带
     * `xlgToken`），**绝不让 token 落进 logcat**——平台域只记主机名。
     */
    fun redactForLog(url: String?): String =
        if (isPlatformUrl(url)) "$PLATFORM_HOST/…（token 已隐去）" else url.toString()
}
