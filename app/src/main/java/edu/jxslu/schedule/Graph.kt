package edu.jxslu.schedule

import android.content.Context
import edu.jxslu.schedule.data.local.JuwDatabase
import edu.jxslu.schedule.data.jw.JwCredentialStore
import edu.jxslu.schedule.data.kqcx.KqcxBikeClient
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.qiekj.QiekjOrderHistoryStore
import edu.jxslu.schedule.data.qiekj.QiekjRepository
import edu.jxslu.schedule.data.qiekj.QiekjTokenStore
import edu.jxslu.schedule.data.repo.AttachmentStore
import edu.jxslu.schedule.data.repo.HomeworkRepository
import edu.jxslu.schedule.data.repo.NoteRepository
import edu.jxslu.schedule.data.repo.ScheduleBackgroundStore
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.data.repo.ScoreRepository
import edu.jxslu.schedule.data.ykt.YktClient
import edu.jxslu.schedule.data.ykt.YktCredentialStore
import edu.jxslu.schedule.data.ykt.YktRepository

object Graph {
    @Volatile
    private var repository: ScheduleRepository? = null

    @Volatile
    private var prefsStore: DisplayPrefsStore? = null

    @Volatile
    private var qiekjRepository: QiekjRepository? = null

    @Volatile
    private var scoreRepository: ScoreRepository? = null

    @Volatile
    private var noteRepository: NoteRepository? = null

    @Volatile
    private var homeworkRepository: HomeworkRepository? = null

    @Volatile
    private var attachmentStore: AttachmentStore? = null

    @Volatile
    private var scheduleBackgroundStore: ScheduleBackgroundStore? = null

    @Volatile
    private var jwCredentialStore: JwCredentialStore? = null

    @Volatile
    private var yktCredentialStore: YktCredentialStore? = null

    @Volatile
    private var yktRepository: YktRepository? = null

    @Volatile
    private var kqcxBikeClient: KqcxBikeClient? = null

    /** 进程级 applicationContext（后台协程里落盘等场景复用，免 Activity 引用泄漏）。 */
    val appContext: Context by lazy { contextProvider() }

    /** 由 [JuwApplication.onCreate] 注入；首次访问早于注入说明时序有问题，直接抛错暴露。 */
    internal lateinit var contextProvider: () -> Context

    /** 教务登录凭证存储单例（DESIGN §4.17）：EncryptedSharedPreferences 创建有开销，进程内一份。 */
    fun jwCredentialStore(context: Context): JwCredentialStore =
        jwCredentialStore ?: synchronized(this) {
            jwCredentialStore ?: JwCredentialStore(context.applicationContext).also { jwCredentialStore = it }
        }

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

    /** 成绩仓库单例（DESIGN §4.15）：与课表共用数据库，按学期整体替换。 */
    fun scoreRepository(context: Context): ScoreRepository =
        scoreRepository ?: synchronized(this) {
            scoreRepository ?: ScoreRepository(JuwDatabase.get(context)).also { scoreRepository = it }
        }

    /** 笔记·课件仓库单例（DESIGN §4.20）：归属键是课程名，与课表无关。 */
    fun noteRepository(context: Context): NoteRepository =
        noteRepository ?: synchronized(this) {
            noteRepository ?: NoteRepository(JuwDatabase.get(context)).also { noteRepository = it }
        }

    /** 作业仓库单例（DESIGN §4.20）：与笔记共用一套附件与渲染口径。 */
    fun homeworkRepository(context: Context): HomeworkRepository =
        homeworkRepository ?: synchronized(this) {
            homeworkRepository ?: HomeworkRepository(JuwDatabase.get(context)).also { homeworkRepository = it }
        }

    /** 图片附件存储单例（DESIGN §4.20）：应用私有目录 notes_img，无网络出口。 */
    fun attachmentStore(context: Context): AttachmentStore =
        attachmentStore ?: synchronized(this) {
            attachmentStore ?: AttachmentStore(context.applicationContext).also { attachmentStore = it }
        }

    /**
     * 课表页背景图存储单例（DESIGN §4.21）：应用私有目录 schedule_bg，同时只留一张。
     * 与笔记附件分开的原因是生命周期完全不同（附件按正文引用清扫，背景图只认偏好里的文件名）。
     */
    fun scheduleBackground(context: Context): ScheduleBackgroundStore =
        scheduleBackgroundStore ?: synchronized(this) {
            scheduleBackgroundStore ?: ScheduleBackgroundStore(context.applicationContext)
                .also { scheduleBackgroundStore = it }
        }

    /** 胖乖仓库单例（DESIGN §4.10）：Retrofit client 只建一次，token 存加密 prefs。 */
    fun qiekj(context: Context): QiekjRepository =
        qiekjRepository ?: synchronized(this) {
            qiekjRepository ?: QiekjRepository(
                QiekjTokenStore(context.applicationContext),
                QiekjOrderHistoryStore(context.applicationContext),
            ).also { qiekjRepository = it }
        }

    /** 校园卡凭证存储单例（DESIGN §4.19）：EncryptedSharedPreferences，进程内一份。 */
    fun yktCredentialStore(context: Context): YktCredentialStore =
        yktCredentialStore ?: synchronized(this) {
            yktCredentialStore ?: YktCredentialStore(context.applicationContext).also { yktCredentialStore = it }
        }

    /** 校园卡仓库单例（DESIGN §4.19）：OkHttp client 只建一次；token 只在仓库内存里。 */
    fun yktRepository(context: Context): YktRepository =
        yktRepository ?: synchronized(this) {
            yktRepository ?: YktRepository(YktClient.create()).also { yktRepository = it }
        }

    /**
     * 附近单车接口客户端单例（DESIGN §4.23）：OkHttp 连接池只建一次。
     * 无凭证可存——这个接口不需要鉴权，客户端里也没有任何 token 字段。
     */
    fun kqcxBikeClient(context: Context): KqcxBikeClient =
        kqcxBikeClient ?: synchronized(this) {
            kqcxBikeClient ?: KqcxBikeClient.create().also { kqcxBikeClient = it }
        }
}
