package edu.jxslu.schedule

import android.content.Context
import edu.jxslu.schedule.data.local.JuwDatabase
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.qiekj.QiekjOrderHistoryStore
import edu.jxslu.schedule.data.qiekj.QiekjRepository
import edu.jxslu.schedule.data.qiekj.QiekjTokenStore
import edu.jxslu.schedule.data.repo.ScheduleRepository

object Graph {
    @Volatile
    private var repository: ScheduleRepository? = null

    @Volatile
    private var prefsStore: DisplayPrefsStore? = null

    @Volatile
    private var qiekjRepository: QiekjRepository? = null

    /** 显示偏好用 applicationContext 建，保证与 Activity 生命周期无关。 */
    fun displayPrefs(context: Context): DisplayPrefsStore =
        prefsStore ?: synchronized(this) {
            prefsStore ?: DisplayPrefsStore(context.applicationContext).also { prefsStore = it }
        }

    fun repository(context: Context): ScheduleRepository =
        repository ?: synchronized(this) {
            repository ?: ScheduleRepository(
                JuwDatabase.get(context),
                displayPrefs(context),
            ).also { repository = it }
        }

    /** 胖乖仓库单例（DESIGN §4.10）：Retrofit client 只建一次，token 存加密 prefs。 */
    fun qiekj(context: Context): QiekjRepository =
        qiekjRepository ?: synchronized(this) {
            qiekjRepository ?: QiekjRepository(
                QiekjTokenStore(context.applicationContext),
                QiekjOrderHistoryStore(context.applicationContext),
            ).also { qiekjRepository = it }
        }
}
