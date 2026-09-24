package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.jw.ScoreParser
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.session.CasSession
import edu.jxslu.schedule.data.session.ProfileSyncRules
import edu.jxslu.schedule.domain.BalanceAlert
import java.time.LocalDate
import kotlinx.coroutines.flow.first

/**
 * 学籍卡补抓（DESIGN §3.3）：把「我的」页的姓名 / 班级补上。
 *
 * **为什么要有它**：班级原先只在成绩导入链路顺带抓（`StudentCardFetcher` 注入 fetch），
 * 于是刚配好账号的人打开「我的」页只看到一串学号，得先导一次成绩才有班级。会话层能直接
 * 取页面之后，这件事不该再挂在某个无关功能上。
 *
 * 三条纪律：
 * 1. **失败静默**：抓不到就维持现状，不弹错误、不阻塞任何流程——姓名与班级是锦上添花，
 *    不值得为它把「我的」页弄成错误态；
 * 2. **有闸门**：只在班级为空、且今天还没试过时抓（[ProfileSyncRules.shouldAttempt]）；
 * 3. **不覆盖已有值**：落库走 `setProfile`，它对空串不动键（脏解析不会抹掉已有数据）。
 */
class ProfileSync(
    private val cas: CasSession,
    private val prefs: DisplayPrefsStore,
    private val today: () -> LocalDate = LocalDate::now,
) {

    /** @return true = 这次真的抓到并落库了 */
    suspend fun syncOnce(force: Boolean = false): Boolean {
        val todayKey = BalanceAlert.dateKey(today())
        if (!force) {
            val classBlank = prefs.profileClass.first().isBlank()
            val lastAttempt = prefs.profileSyncDate.first()
            if (!ProfileSyncRules.shouldAttempt(classBlank, lastAttempt, todayKey)) return false
        }
        // 「试过」先落日期，不管成不成——抓不到时不落，就变成每次进页都重试
        prefs.setProfileSyncDate(todayKey)

        val html = cas.fetchHtml(JwUrls.STUDENT_CARD) ?: return false
        val card = ScoreParser.parseStudentCard(html) ?: return false
        prefs.setProfile(card.name, card.studentClass)
        return true
    }
}
