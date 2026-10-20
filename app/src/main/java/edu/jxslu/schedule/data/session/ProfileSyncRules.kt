package edu.jxslu.schedule.data.session

/**
 * 学籍卡补抓的判定（DESIGN §3.3）。
 *
 * 姓名 / 班级 / 院系 / 专业 / 学制来自教务学籍卡 `/jsxsd/grxx/xsxx`，原先只在成绩导入链路顺带抓，
 * 于是刚配好账号的人打开「我的」页只看到一串学号。现在会话可用了就能补抓，
 * 但**不能每次都试**：抓不到时（页面结构变了、学籍卡没开放）每次进页重试就是白打教务。
 */
object ProfileSyncRules {

    /**
     * 该不该现在去抓。两个条件缺一不可：
     * - **学籍字段还有空的**：已经抓全就不用再抓；旧用户升级后新增字段为空，也能补一次；
     * - **今天还没试过**：抓不到时每天最多一次，不是每次进页一次。
     */
    fun shouldAttempt(profileIncomplete: Boolean, lastAttemptDate: String?, today: String): Boolean =
        profileIncomplete && lastAttemptDate != today
}
