package edu.jxslu.schedule.ui.jwvw

import edu.jxslu.schedule.data.jw.JwSchedulePage
import edu.jxslu.schedule.data.jw.JwUrls

/**
 * 教务 WebView 的页面适配脚本（DESIGN §4.4「电脑版预览」）。
 *
 * 纯 Kotlin（**不 import android.\***）→ 判据 [fitScript] 与三段脚本的字符串契约都能在 JVM 单测里钉住，
 * 注入时机与调用点留在 `JwImportScreen`（那边有 WebView，测不了）。
 *
 * 三段脚本各管一件事，互不重叠：
 * - [SCHEDULE_FIT_JS]：课表页横向可滚 + 紧凑度（2026-09-17 起就有，文本未改）
 * - [LOGIN_FIT_JS]：登录 / SSO 页的固定宽表单收成百分比（同上）
 * - [DESKTOP_JS]：**电脑版**——把页面的 viewport 换成桌面宽度（2026-10-08 新增）
 */
object JwWebFit {

    /**
     * 电脑版的桌面布局宽度（CSS px）。
     *
     * 980 = 桌面浏览器的默认视口宽，也正是 WebView 自己「页面没有 viewport meta 时」用的值
     * （Chromium《Web Page Layout》：No viewport tag → Layout Width 980px + Initial Scale
     * Fit on Screen）——**教务登录页今天就是这个样子**，电脑版只是让业务页跟它同一口径。
     * 嫌小/嫌大只改这一个常量。
     */
    const val DESKTOP_WIDTH_PX = 980

    /** 页面自身的 viewport content 存在这个属性上（撤销时原样还回去）。 */
    const val ATTR_ORIG = "data-juw-orig"

    /** 页面本来没有 meta、由我们新建时打的标记（撤销时整节点删掉）。 */
    const val ATTR_CREATED = "data-juw-created"

    /** 注入脚本里用的宽度字面量，与 [DESKTOP_WIDTH_PX] 单一来源。 */
    private const val DESKTOP_CONTENT = "width=$DESKTOP_WIDTH_PX"

    /**
     * 电脑版：把页面的 `<meta name=viewport>` **整串换成** [DESKTOP_CONTENT]，并**自愈**。
     *
     * 三条都是实测/踩出来的口径：
     *
     * ① **必须整串替换，不能只改 width**。教务业务页的 meta 是
     * `width=device-width, initial-scale=1.0`（实测快照 `scripts/out/jw_xs_main.html`、
     * `xsxk_c_new_xsxkzx_*.html`）：留着 `initial-scale=1` 就只能放大不能缩小（最小缩放被钳在 1.0，
     * 这正是用户报的「不支持放大缩小」）；个别页还带 `user-scalable=no`，留着连双指放大都没了。
     * 换成单值 `width=980` 后初始即整页可见（约 0.42 倍），双指可放大到 5 倍、可缩回整页。
     *
     * ② **改 meta 会触发重新布局**（2026-10-08 模拟器实测：`iw=412` → 改 content → `iw=980`，
     * Chromium 文档只写了首次加载的规则，这条是实测补的）。所以注入点沿用既有
     * `applyPageFit`（`onPageCommitVisible` 首选、就绪路径兜底），**不需要重载页面**——
     * 切换电脑版 / 手机版时选课页的 iframe 状态不会丢。
     *
     * ③ **自愈**（2026-10-08 补，模拟器上真踩到过一次）：注入可能跑在页面自己的 meta
     * **之前**（`onPageCommitVisible` 早于 head 解析完），那样我们新建的 meta 会被页面后到的
     * meta 盖掉，页面按移动视口渲染 —— 表现是「电脑版开着却还是挤压的」。所以除了幂等早退，
     * 还挂一个 MutationObserver 盯着 head：viewport meta 一出现/一改 content 就重新压回
     * `width=980`。自愈回调里靠 `__juwViewportApplying` 防自触发死循环（我们自己的
     * `setAttribute` 也会被观测到，但那时 content 已是目标值，直接早退）。
     *
     * ④ **弹层宽度也要收**（见 [LAYER_FIT_CSS]，2026-10-08 用户报「选课结果弹窗显示不全」）。
     */
    val DESKTOP_JS: String = """
(function(){
  function apply(){
    if (window.__juwViewportApplying) return;
    window.__juwViewportApplying = true;
    try{
      var all = document.querySelectorAll('meta[name=viewport]');
      var m = null;
      for (var i = 0; i < all.length; i++) {
        if (!all[i].hasAttribute('$ATTR_CREATED')) { m = all[i]; break; }
      }
      if (!m) m = document.querySelector('meta[name=viewport]');
      if (!m) {
        m = document.createElement('meta');
        m.setAttribute('name', 'viewport');
        m.setAttribute('$ATTR_CREATED', '1');
        (document.head || document.documentElement).appendChild(m);
      }
      if (m.getAttribute('content') !== '$DESKTOP_CONTENT') {
        if (!m.hasAttribute('$ATTR_ORIG')) {
          m.setAttribute('$ATTR_ORIG', m.getAttribute('content') || '');
        }
        m.setAttribute('content', '$DESKTOP_CONTENT');
        void document.documentElement.offsetWidth;
      }
      if (!document.getElementById('$LAYER_STYLE_ID')) {
        var css = document.createElement('style');
        css.id = '$LAYER_STYLE_ID';
        css.textContent = "$LAYER_FIT_CSS";
        (document.head || document.documentElement).appendChild(css);
      }
    }catch(e){}finally{ window.__juwViewportApplying = false; }
  }
  apply();
  if (!window.__juwViewportGuard) {
    window.__juwViewportGuard = true;
    try{
      new MutationObserver(apply).observe(document.head || document.documentElement, {
        childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name']
      });
    }catch(e){}
    window.addEventListener('DOMContentLoaded', apply);
    window.addEventListener('load', apply);
    window.addEventListener('pageshow', apply);
  }
})()
""".trimIndent()

    /** 弹层适配样式的 id（幂等判据与撤销脚本共用）。 */
    const val LAYER_STYLE_ID = "juw-layer-fit"

    /**
     * 教务弹层的宽度收口（2026-10-08 用户报「选课结果等弹窗显示不全」）。
     *
     * **根因在教务自己的 JS 里**（`assets_newL/js/qzDialog.js`，实测复现：
     * `layer0=-260,451 1500x720 inlineLeft=-260px inlineW=1500px winW=980`）：
     * 弹层尺寸是**硬编码像素**（`选课结果` = `area: ['1500px','720px']`，见
     * `scripts/out/xsxk_c_iframe_selectBottom_*.html` 的 `xkrz()`），定位却是
     * `left = (视口宽 - 弹窗宽) / 2` —— 视口 980 时算出 **-260px**，弹窗左边 260px 跑到
     * 屏幕外（够不着），右边又溢出 260px，两头都被切。手机版（412）更糟（left=-544）。
     *
     * 收口办法：`max-width` 压过 layui 写的行内 `width`（CSS 里 max-width 恒定胜过 width），
     * 再用 `left/right: 0 + margin: auto` 重新居中——**不动 `top`**，也不碰弹窗内部结构。
     *
     * **排除 `layui-layer-tips`**：那是"贴着某个元素"的提示气泡（教务用它显示被截断格子的全文，
     * 见 `qzDialog.js` 的 `layui.layer.tips`），居中会把它的锚点关系毁掉。
     */
    const val LAYER_FIT_CSS =
        ".layui-layer:not(.layui-layer-tips){max-width:calc(100vw - 8px) !important;" +
            "left:0 !important;right:0 !important;" +
            "margin-left:auto !important;margin-right:auto !important;}"

    /**
     * 关掉页面上最上层的教务弹层（layui layer）；返回 `1` = 关掉了一个，`0` = 页面上没有弹层。
     *
     * 用途：返回键**先关弹层**再谈退页——用户报「按返回不会关闭弹窗，实则是窗口后的界面已经
     * 返回成功了」，那是当时的行为：返回直接退页，而弹层是页面里的 DOM 覆盖层，用户感觉
     * "弹窗没关"。现在先点弹层自己的关闭按钮（`.layui-layer-close`，与用户手点同一个入口），
     * 关掉了就到此为止；页面上没弹层才走 [JwWebBack] 那套退页 / 关窗口。
     */
    val CLOSE_TOP_LAYER_JS: String = """
(function(){
  try{
    var ls = document.querySelectorAll('.layui-layer');
    if (!ls.length) return 0;
    var top = ls[ls.length - 1];
    var btn = top.querySelector('.layui-layer-close');
    if (!btn) return 0;
    btn.click();
    return 1;
  }catch(e){ return 0; }
})()
""".trimIndent()

    /**
     * 撤销电脑版：把 viewport 还原成页面自己的那份（自己新建的则整节点删掉），
     * 并撤掉弹层收口样式——**关掉电脑版必须与引入之前逐字一致**（回归闸）。
     *
     * 顶栏切回手机版时当场注入，**不重载页面**——用户能立刻看到两种口径的对比，
     * 选课页的列表 / 筛选状态也留着。页面没被覆盖过时它是空操作。
     */
    val DESKTOP_REVERT_JS: String = """
(function(){
  try{
    var s = document.getElementById('$LAYER_STYLE_ID');
    if (s && s.parentNode) s.parentNode.removeChild(s);
    var m = document.querySelector(
      'meta[name=viewport][$ATTR_ORIG], meta[name=viewport][$ATTR_CREATED]');
    if (!m) return;
    if (m.hasAttribute('$ATTR_CREATED')) {
      if (m.parentNode) m.parentNode.removeChild(m);
    } else {
      m.setAttribute('content', m.getAttribute('$ATTR_ORIG') || '');
      m.removeAttribute('$ATTR_ORIG');
    }
    void document.documentElement.offsetWidth;
  }catch(e){}
})()
""".trimIndent()

    /**
     * 课表页适配：让宽表格能横向滚动，并按屏幕调紧凑度与可读性。
     *
     * **不再改写 viewport meta**。课表页自带 `width=device-width`，改写它没有收益，
     * 反而会和 WebView 的 useWideViewPort / loadWithOverviewMode 两套缩放机制互相打架
     * （旧实现既改 meta 又关掉 useWideViewPort，方向是矛盾的）。
     *
     * 尺寸基准（用户拍板）：格子 min-width 110→96px、表格总宽 900→730px 同步下调——
     * 表格越窄，overview 缩放越接近 1:1，字在屏上就越大；同时教室/时间摘要文字
     * （`.qz-hasCourse-abbrinfo`，理论页为「老师/时间/地点」整段、实验页为地点）
     * 显式放大到 12.5px 并放宽行距，两页共用同一组 class，一并受益。
     *
     * 电脑版下这段照旧生效（两段脚本各管一层：这段管表格内部可滚，[DESKTOP_JS] 管整页视口），
     * 若桌面版下嫌格子小，**唯一调优点就是这里的 min-width**。
     */
    val SCHEDULE_FIT_JS: String = """
(function(){
  try{
    if(document.getElementById('juw-schedule-fit')) return;
    var css=document.createElement('style'); css.id='juw-schedule-fit';
    css.textContent=
      'html,body{overflow-x:auto !important;max-width:none !important;}'+
      'table.qz-weeklyTable{min-width:730px !important;}'+
      '#tableDiv,.qz-weeklyTableWrap,.qz-table-wrap{overflow-x:auto !important;}'+
      'td[name=kbDataTd]{min-width:96px !important;}'+
      '.qz-hasCourse-detailitem,.qz-hasCourse-abbrinfo{'+
        'font-size:12.5px !important;line-height:1.5 !important;}';
    (document.head||document.documentElement).appendChild(css);
  }catch(e){}
})()
""".trimIndent()

    /**
     * 登录 / SSO 页适配：把固定宽表单收成页面宽度的百分比并居中。
     *
     * 同样不改写 viewport meta——这些页面没有 meta，正好交给 WebView 的
     * 「按页面宽度渲染 → 缩放到屏幕」来处理整页可见性；这里只补两件事：
     * 固定宽容器给百分比上限、表单控件不撑出屏幕。
     *
     * 注意：只对真正的登录页注入。学生端主页等桌面页不要碰，越改越乱。
     */
    val LOGIN_FIT_JS: String = """
(function(){
  try{
    if(document.getElementById('juw-login-fit')) return;
    var css=document.createElement('style'); css.id='juw-login-fit';
    css.textContent=
      '.login{height:auto !important;min-height:0 !important;overflow:visible !important;}'+
      '.form-container,.login-box,.login-container,.layui-form{'+
        'position:static !important;right:auto !important;top:auto !important;'+
        'width:92% !important;max-width:560px !important;height:auto !important;'+
        'overflow:visible !important;margin:28px auto !important;}'+
      'input,select,textarea{box-sizing:border-box !important;max-width:100% !important;}'+
      '.layui-input-wrap>input{width:100% !important;}'+
      'img{max-width:100% !important;height:auto !important;}';
    (document.head||document.documentElement).appendChild(css);
  }catch(e){}
})()
""".trimIndent()

    /** 只有真正的登录/SSO 页才需要表单兜底。 */
    fun isLoginLikeUrl(url: String?): Boolean {
        val u = url.orEmpty()
        return "/cas/login" in u || "Logon.do" in u || "/sso.jsp" in u
    }

    /**
     * 这一页该注入什么（null = 什么都不注入）。**唯一判据入口**，两处注入点共用。
     *
     * - **登录 / SSO 页**：只给 [LOGIN_FIT_JS]，**绝不覆盖 viewport**。它们本来就没有
     *   meta viewport，WebView 已经按 980 + Fit on Screen 渲染（`configureForJw` 的注释记过
     *   这条实测），电脑版对它们零收益；而自动填表（`JwAutoLogin`）与整条统一认证会话链
     *   都挂在这几页的 DOM 上，不去动它。
     * - **课表页**：既有适配 + （开着电脑版时）桌面覆盖。
     * - **其余**（教务主页 / 选课中心 / 进选课页 / 成绩页 / 评教平台）：开着电脑版时覆盖。
     * - **关掉电脑版时**：与引入电脑版之前**逐字一致**（回归闸，单测钉住）。
     */
    fun fitScript(url: String?, desktop: Boolean): String? {
        val u = url.orEmpty()
        if (u.isBlank() || u == "about:blank") return null
        if (isLoginLikeUrl(u)) return LOGIN_FIT_JS
        val parts = ArrayList<String>(2)
        if (JwUrls.schedulePageKind(u) != JwSchedulePage.None) parts += SCHEDULE_FIT_JS
        if (desktop) parts += DESKTOP_JS
        return if (parts.isEmpty()) null else parts.joinToString("\n;\n")
    }
}
