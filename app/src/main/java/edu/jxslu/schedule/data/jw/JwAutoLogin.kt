package edu.jxslu.schedule.data.jw

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 在 WebView 里**自动完成一次统一认证登录**（DESIGN §4.27「落登录页自动填表」）。
 *
 * 为什么要有它：把 OkHttp 登录拿到的 cookie 注入 `CookieManager` 这条路在真机上走不通
 * ——cookie 确实写进去了（读回验证通过），但 WebView 发请求时就是不带（2026-09-24 实测：
 * 注入 7/7 条全部落位，85ms 后首跳仍然落到 CAS 登录页）。差别只剩 cookie 属性：注入的
 * 那份缺 `SameSite=None`，Chrome 按 Lax 处理，跨站跳转不带。
 *
 * 换成让 **WebView 自己填表提交**，cookie 就由 CAS 服务端亲自 `Set-Cookie` 给 WebView，
 * 属性完整、与用户手动登录走的是同一条路，从根上没有这个问题。
 *
 * 只做一件事：填 `username` / `password` 并 `submit()`。不碰验证码——命中验证码页时
 * 表单结构不同（或根本没有密码框），会返回 [NO_FORM]，调用方据此退回人工登录。
 */
object JwAutoLogin {

    /**
     * 已尝试提交，前缀固定为 `ok`（后面跟具体方式：`ok:click` / `ok:requestSubmit` / `ok:submit`）。
     * 调用方判前缀即可，不必关心用了哪种方式。等后续导航就行（CAS 302 → sso.jsp → 教务）。
     */
    const val OK_PREFIX = "ok"

    /** 页面上没有账号/密码框（不是登录页、或结构变了、或有验证码）。 */
    const val NO_FORM = "no-form"

    /**
     * 生成注入脚本。
     *
     * **密码用 JSON 字符串字面量**（`Json.encodeToString`）拼接，不手工加引号——
     * CAS 密码可能含 `"` 或 `\`，手工拼会把脚本拼坏、把密码片段泄进页面。
     *
     * **提交优先点按钮，而不是 `form.submit()`**（2026-09-24 真机反馈：值填进去了但没提交）：
     * `form.submit()` 会**绕过** `onsubmit` 与按钮上的点击处理（登录页常在那里做校验、
     * 拼加密字段、改 `_eventId`）。点按钮才是和用户手点完全一样的路径；
     * 按钮找不到时退 `requestSubmit()`（会触发 `onsubmit`），再不行才 `submit()`。
     */
    fun fillJs(username: String, password: String): String = """
(function(){
  try {
    var u = document.querySelector('input[name=username]') || document.querySelector('#username');
    var p = document.querySelector('input[name=password]') || document.querySelector('#password');
    if (!u || !p) return '$NO_FORM';
    var f = u.form || p.form || document.querySelector('form');
    if (!f) return '$NO_FORM';
    setValue(u, ${literal(username)});
    setValue(p, ${literal(password)});
    var btn = f.querySelector('button[type=submit], input[type=submit]')
           || document.querySelector('button[type=submit], input[type=submit], .login-btn, #loginBtn');
    if (btn) { btn.click(); return '$OK_PREFIX:click'; }
    if (typeof f.requestSubmit === 'function') { f.requestSubmit(); return '$OK_PREFIX:requestSubmit'; }
    f.submit();
    return '$OK_PREFIX:submit';
  } catch (e) { return 'err:' + String(e); }

  function setValue(el, val) {
    // 受控组件（React/Vue 等）直接改 .value 不会更新框架状态——必须走原生 setter 再派事件
    var d = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
    if (d && d.set) { d.set.call(el, val); } else { el.value = val; }
    el.dispatchEvent(new Event('input', {bubbles:true}));
    el.dispatchEvent(new Event('change', {bubbles:true}));
  }
})()
""".trim()

    private fun literal(value: String): String = Json.encodeToString(value)
}
