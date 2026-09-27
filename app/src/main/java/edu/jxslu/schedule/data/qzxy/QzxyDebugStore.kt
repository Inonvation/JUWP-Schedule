package edu.jxslu.schedule.data.qzxy

import android.content.Context

/**
 * 趣智校园的调试开关（DESIGN §4.30）。
 *
 * 只有一个开关：**记录调试日志**，默认关。开着的时候每次开阀/结算都会把当次的
 * 诊断信息追加进内存列表，供用户一键复制；关着就什么都不留。
 *
 * 默认关是有意的：日志里含设备地址、账号标识与接口原始数据，日常使用没必要留。
 * 排查时才开，复制完关掉。
 *
 * 除调试开关外还承担「记住手机号」（见 [rememberedPhone] 的注释）。
 */
class QzxyDebugStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var logEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOG_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LOG_ENABLED, value).apply()
        }

    /**
     * 上次登录的手机号（**明文**，退出登录也留着）。
     *
     * 与会话不同：手机号不是凭据，存它只为「会话失效后重新登录少打 11 位数字」。
     * 密码与 `loginCode` 永远不落这里——那是 `secure_qzxy` 的事。
     * 顺手放在本 store 而不是另开一个文件：都是本机偏好，分开存反而难找。
     */
    var rememberedPhone: String
        get() = prefs.getString(KEY_PHONE, null).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_PHONE, value).apply()
        }

    private companion object {
        const val FILE_NAME = "qzxy_debug"
        const val KEY_LOG_ENABLED = "debug_log_enabled"
        const val KEY_PHONE = "remembered_phone"
    }
}
