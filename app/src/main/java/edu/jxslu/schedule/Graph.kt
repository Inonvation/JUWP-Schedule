package edu.jxslu.schedule

import android.content.Context
import edu.jxslu.schedule.data.local.JuwDatabase
import edu.jxslu.schedule.data.kqcx.KqcxBikeClient
import edu.jxslu.schedule.data.power.PowerClient
import edu.jxslu.schedule.data.power.PowerReadingStore
import edu.jxslu.schedule.data.power.PowerRepository
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.qiekj.QiekjOrderHistoryStore
import edu.jxslu.schedule.data.qiekj.QiekjRepository
import edu.jxslu.schedule.data.qiekj.QiekjTokenStore
import edu.jxslu.schedule.data.repo.AttachmentStore
import edu.jxslu.schedule.data.repo.HomeworkRepository
import edu.jxslu.schedule.data.repo.ProfileSync
import edu.jxslu.schedule.data.repo.NoteRepository
import edu.jxslu.schedule.data.repo.ScheduleBackgroundStore
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.data.repo.ScoreRepository
import edu.jxslu.schedule.data.jw.TranscriptClient
import edu.jxslu.schedule.data.jw.JwVpnDetector
import edu.jxslu.schedule.data.repo.TranscriptStore
import edu.jxslu.schedule.data.session.CasSession
import edu.jxslu.schedule.data.session.CredentialVault
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
    private var yktCredentialStore: YktCredentialStore? = null

    @Volatile
    private var credentialVault: CredentialVault? = null

    @Volatile
    private var casSession: CasSession? = null

    @Volatile
    private var profileSync: ProfileSync? = null

    @Volatile
    private var yktRepository: YktRepository? = null

    @Volatile
    private var kqcxBikeClient: KqcxBikeClient? = null

    @Volatile
    private var powerRepository: PowerRepository? = null

    @Volatile
    private var powerReadingStore: PowerReadingStore? = null

    @Volatile
    private var transcriptClient: TranscriptClient? = null

    @Volatile
    private var transcriptStore: TranscriptStore? = null

    /** 进程级 applicationContext（后台协程里落盘等场景复用，免 Activity 引用泄漏）。 */
    val appContext: Context by lazy { contextProvider() }

    /** 由 [JuwApplication.onCreate] 注入；首次访问早于注入说明时序有问题，直接抛错暴露。 */
    internal lateinit var contextProvider: () -> Context

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

    /**
     * 凭据与登录闸门的唯一读写口（DESIGN §4.27）：进程内一份，两套凭证都由它管。
     *
     * 必须是单例：`EncryptedSharedPreferences` 每次 `create` 都是新实例、各持一份内存缓存，
     * 多份实例会出现「一处写、另一处读不到」。
     */
    fun credentialVault(context: Context): CredentialVault =
        credentialVault ?: synchronized(this) {
            credentialVault ?: CredentialVault(context.applicationContext).also { credentialVault = it }
        }

    /** 校园卡凭证存储单例（DESIGN §4.19）：薄适配器，读写全部委托 [credentialVault]。 */
    fun yktCredentialStore(context: Context): YktCredentialStore =
        yktCredentialStore ?: synchronized(this) {
            yktCredentialStore ?: YktCredentialStore(credentialVault(context))
                .also { yktCredentialStore = it }
        }

    /**
     * 学校统一认证会话单例（DESIGN §4.27）：教务 / 学工 / 签章共用这一份 CAS 会话。
     *
     * OkHttp client 与 cookie jar 都挂在这个实例上、进程存活期内复用——不再是旧的
     * 「每次任务全量重登、用完即弃」。
     */
    fun casSession(context: Context): CasSession =
        casSession ?: synchronized(this) {
            casSession ?: CasSession(
                vault = credentialVault(context),
                // 代理/VPN 开着时登录必然失败（学校对代理出口超时或 500，DESIGN §4.27），
                // 先拦下并点名提示，省一次必然失败的往返、也免得把锅记到凭证上
                isProxyActive = { JwVpnDetector.isVpnActive(context.applicationContext) },
            ).also { casSession = it }
        }

    /**
     * 学籍卡补抓（DESIGN §3.3）：会话可用时把姓名 / 班级补上，不用等一次成绩导入。
     * 无状态（闸门在 DataStore 里），但仍做成单例——省一次构造、也便于将来加内存缓存。
     */
    fun profileSync(context: Context): ProfileSync =
        profileSync ?: synchronized(this) {
            profileSync ?: ProfileSync(casSession(context), displayPrefs(context))
                .also { profileSync = it }
        }

    /** 校园卡仓库单例（DESIGN §4.19）：OkHttp client 只建一次；token 只在仓库内存里。 */
    fun yktRepository(context: Context): YktRepository =
        yktRepository ?: synchronized(this) {
            yktRepository ?: YktRepository(YktClient.create(), credentialVault(context))
                .also { yktRepository = it }
        }

    /**
     * 附近单车接口客户端单例（DESIGN §4.23）：OkHttp 连接池只建一次。
     * 无凭证可存——这个接口不需要鉴权，客户端里也没有任何 token 字段。
     */
    fun kqcxBikeClient(context: Context): KqcxBikeClient =
        kqcxBikeClient ?: synchronized(this) {
            kqcxBikeClient ?: KqcxBikeClient.create().also { kqcxBikeClient = it }
        }

    /**
     * 缴费平台（寝室电费）仓库单例（DESIGN §4.24）：OkHttp 连接池只建一次；
     * token 只在仓库内存里，不落盘。
     */
    fun powerRepository(context: Context): PowerRepository =
        powerRepository ?: synchronized(this) {
            powerRepository ?: PowerRepository(
                PowerClient.create(),
                powerReadingStore(context),
            ).also { powerRepository = it }
        }

    /** 电表读数本机记录（DESIGN §3.13「用电统计」）：仓库写入、账单页读取共用一份。 */
    fun powerReadingStore(context: Context): PowerReadingStore =
        powerReadingStore ?: synchronized(this) {
            powerReadingStore ?: PowerReadingStore(JuwDatabase.get(context.applicationContext).powerReadingDao())
                .also { powerReadingStore = it }
        }

    /**
     * 成绩单导出客户端单例（DESIGN §4.25）：OkHttp 连接池只建一次。
     *
     * 不需要 Context，也没有凭证字段——会话 Cookie 每次由调用方从 WebView 的
     * CookieManager 现取（[edu.jxslu.schedule.data.jw.TranscriptCookies]），
     * 这个单例里不驻留任何用户身份。
     */
    fun transcriptClient(): TranscriptClient =
        transcriptClient ?: synchronized(this) {
            transcriptClient ?: TranscriptClient().also { transcriptClient = it }
        }

    /** 导出成绩单的落盘单例（DESIGN §4.25）：应用私有目录 transcripts，只留最近 10 份。 */
    fun transcriptStore(context: Context): TranscriptStore =
        transcriptStore ?: synchronized(this) {
            transcriptStore ?: TranscriptStore(context.applicationContext).also { transcriptStore = it }
        }
}
