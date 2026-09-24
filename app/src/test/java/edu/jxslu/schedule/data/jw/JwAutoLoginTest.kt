package edu.jxslu.schedule.data.jw

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动登录脚本（DESIGN §4.27「落登录页自动填表」）。
 *
 * 这里最重要的是**转义**：密码可能含引号或反斜杠，手工拼字符串会把脚本拼坏，
 * 甚至把密码片段泄进页面 DOM。
 */
class JwAutoLoginTest {

    @Test
    fun scriptTargetsUsernameAndPasswordAndSubmits() {
        val js = JwAutoLogin.fillJs("2023001", "hunter2")
        assertTrue(js.contains("input[name=username]"))
        assertTrue(js.contains("input[name=password]"))
        // 提交优先点按钮：form.submit() 会绕过 onsubmit / 按钮上的点击处理
        assertTrue(js.contains("btn.click()"))
        assertTrue(js.contains("'${JwAutoLogin.OK_PREFIX}:click'"))
        assertTrue(js.contains("requestSubmit"))
        assertTrue(js.contains("'${JwAutoLogin.NO_FORM}'"))
    }

    @Test
    fun plainCredentialsAreQuotedLiteralsAndUseNativeSetter() {
        val js = JwAutoLogin.fillJs("2023001", "hunter2")
        assertTrue(js.contains("setValue(u, \"2023001\")"))
        assertTrue(js.contains("setValue(p, \"hunter2\")"))
        // 受控组件要用原生 setter，直接 .value = 不会更新框架状态
        assertTrue(js.contains("HTMLInputElement.prototype"))
        assertTrue(js.contains("new Event('input'"))
    }

    /** 引号 / 反斜杠 / 换行都要被转义掉，不能破坏脚本结构。 */
    @Test
    fun dangerousCharactersAreEscaped() {
        val js = JwAutoLogin.fillJs("""a"b\c""", "line1\nline2")
        assertTrue(js.contains("""setValue(u, "a\"b\\c")"""))
        assertTrue(js.contains("""setValue(p, "line1\nline2")"""))
        // 原始换行不能出现在字面量里（会截断语句）
        assertFalse(js.contains("setValue(p, \"line1\nline2\")"))
    }
}
