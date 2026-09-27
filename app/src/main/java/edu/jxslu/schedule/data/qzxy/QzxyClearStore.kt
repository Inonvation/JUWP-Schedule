package edu.jxslu.schedule.data.qzxy

import android.content.Context

/**
 * 清除命令（0x86）试出来的可用参数（DESIGN §4.30）。
 *
 * 这条命令的参数没有公开资料，只能按候选表挨个试。试通哪条就记下它的标识
 * （[edu.jxslu.schedule.domain.QzxyProtocol.ClearKey]），下次排到最前面：
 * 每条候选要一次往返加一次回读，命中排在第五位就意味着每次结束用水白等四轮。
 *
 * 存普通 `SharedPreferences`。标识是固定字符串（`record.timeId` 这类），
 * 不含设备地址、账号或记录内容，换学校换设备也只是重新试一遍。
 */
class QzxyClearStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var preferredKey: String?
        get() = prefs.getString(KEY_PREFERRED, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_PREFERRED, value).apply()
        }

    private companion object {
        const val FILE_NAME = "qzxy_clear"
        const val KEY_PREFERRED = "preferred_candidate"
    }
}
