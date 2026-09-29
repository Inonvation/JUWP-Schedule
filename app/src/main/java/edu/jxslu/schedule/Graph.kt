package edu.jxslu.schedule

import android.content.Context
import edu.jxslu.schedule.data.local.JuwDatabase
import edu.jxslu.schedule.data.kqcx.KqcxAuthClient
import edu.jxslu.schedule.data.kqcx.KqcxSessionRepository
import edu.jxslu.schedule.data.kqcx.KqxCredentialStore
import edu.jxslu.schedule.data.kqcx.KqcxBikeClient
import edu.jxslu.schedule.data.kqcx.ZoneCacheStore
import edu.jxslu.schedule.data.power.PowerClient
import edu.jxslu.schedule.data.power.PowerHistoryCache
import edu.jxslu.schedule.data.power.PowerReadingStore
import edu.jxslu.schedule.data.power.PowerRepository
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.qiekj.QiekjOrderHistoryStore
import edu.jxslu.schedule.data.qiekj.QiekjRepository
import edu.jxslu.schedule.data.qiekj.QiekjTokenStore
import edu.jxslu.schedule.data.qzxy.QzxyRepository
import edu.jxslu.schedule.data.qzxy.QzxyDeviceStore
import edu.jxslu.schedule.data.qzxy.QzxyDebugStore
import edu.jxslu.schedule.data.qzxy.QzxyClearStore
import edu.jxslu.schedule.data.qzxy.QzxyWateringStore
import edu.jxslu.schedule.data.qzxy.QzxyGattLink
import edu.jxslu.schedule.data.qzxy.QzxySessionStore
import edu.jxslu.schedule.data.repo.AttachmentStore
import edu.jxslu.schedule.data.repo.HomeworkRepository
import edu.jxslu.schedule.data.repo.ProfileSync
import edu.jxslu.schedule.data.repo.NoteRepository
import edu.jxslu.schedule.data.repo.RideRecordStore
import edu.jxslu.schedule.data.repo.ScheduleBackgroundStore
import edu.jxslu.schedule.data.repo.ScheduleRepository
import kotlinx.coroutines.sync.Mutex
import edu.jxslu.schedule.data.repo.ScholarProgressRepository
import edu.jxslu.schedule.data.repo.ScholarProgressSync
import edu.jxslu.schedule.data.repo.ScoreRepository
import edu.jxslu.schedule.data.repo.ScoreSync
import edu.jxslu.schedule.data.repo.TextbookSync
import edu.jxslu.schedule.data.jw.TranscriptClient
import edu.jxslu.schedule.data.jw.JwVpnDetector
import edu.jxslu.schedule.data.repo.TranscriptStore
import edu.jxslu.schedule.data.session.CasSession
import edu.jxslu.schedule.data.session.CredentialVault
import edu.jxslu.schedule.data.ykt.YktClient
import edu.jxslu.schedule.data.ykt.YktCredentialStore
import edu.jxslu.schedule.data.ykt.YktRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object Graph {
    /**
     * 进程级协程作用域（2026-09-27，DESIGN §4.29）。
     *
     * 给「不该被某个界面生命周期掐断」的后台任务用：首启引导登录成功后要抓的成绩 /
     * 学业完成情况，不能挂在引导页的 `rememberCoroutineScope` 上——用户点「下一步」
     * 跳进 MainActivity，那个 scope 就被取消了，抓取会半路夭折（写一半或干脆不写）。
     * [JuwApplication] 也用它，全进程一份，不新开池。
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var repository: ScheduleRepository? = null

    @Volatile
    private var prefsStore: DisplayPrefsStore? = null

    @Volatile
private var qiekjRepository: QiekjRepository? = null
private var qzxyRepository: QzxyRepository? = null

    @Volatile
    private var kqcxSession: KqcxSessionRepository? = null

    @Volatile
    private var zoneCacheStore: ZoneCacheStore? = null
    private var qzxyDeviceStore: QzxyDeviceStore? = null
    private var qzxyDebugStore: QzxyDebugStore? = null
    private var qzxyClearStore: QzxyClearStore? = null

    @Volatile
    private var qzxyWateringStore: QzxyWateringStore? = null

    @Volatile
    private var qzxyGattLink: QzxyGattLink? = null

    /**
     * 趣智校园「一次只跑一条流程」的进程级互斥（DESIGN §4.30）。
     *
     * **不能放在 ViewModel 里**：今日页那份（面板开阀）与页面、诊断页各持一份 ViewModel，
     * 各自的锁互不相识，两个窗口的流程会同时去用同一条 GATT 链路——一边在等回包、
     * 另一边把链路关掉重连，或者两条指令交替写进设备。
     */
    val qzxyFlowLock: Mutex = Mutex()

    @Volatile
    private var scoreRepository: ScoreRepository? = null

    @Volatile
    private var scholarProgressRepository: ScholarProgressRepository? = null

    @Volatile
    private var scholarProgressSync: ScholarProgressSync? = null

    @Volatile
    private var scoreSync: ScoreSync? = null

    @Volatile
    private var textbookSync: TextbookSync? = null

    @Volatile
    private var noteRepository: NoteRepository? = null

    @Volatile
    private var rideRecordStore: RideRecordStore? = null
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
    private var powerHistoryCache: PowerHistoryCache? = null

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

    /** 学业完成情况仓库单例（DESIGN §4.29）：与课表共用数据库，整体替换。 */
    fun scholarProgressRepository(context: Context): ScholarProgressRepository =
        scholarProgressRepository ?: synchronized(this) {
            scholarProgressRepository ?: ScholarProgressRepository(JuwDatabase.get(context))
                .also { scholarProgressRepository = it }
        }

    /**
     * 学业完成情况抓取单例（DESIGN §4.29）：复用 CAS 会话与同一份仓库/偏好。
     *
     * 无状态，做成单例只是省一次构造——它会被引导页、冷启动、页面刷新三处同时持有，
     * 各自 new 一个也不会错，但没必要。
     */
    fun scholarProgressSync(context: Context): ScholarProgressSync =
        scholarProgressSync ?: synchronized(this) {
            scholarProgressSync ?: ScholarProgressSync(
                cas = casSession(context),
                repo = scholarProgressRepository(context),
                prefs = displayPrefs(context),
            ).also { scholarProgressSync = it }
        }

    /** 成绩自动导入单例（DESIGN §4.29）：与成绩页共用同一份解析与仓库。 */
    fun scoreSync(context: Context): ScoreSync =
        scoreSync ?: synchronized(this) {
            scoreSync ?: ScoreSync(
                cas = casSession(context),
                repo = scoreRepository(context),
                prefs = displayPrefs(context),
            ).also { scoreSync = it }
        }

    /** 教材抓取单例（DESIGN §4.31）：教务导入课表成功后顺带抓对应学期，静默失败。 */
    fun textbookSync(context: Context): TextbookSync =
        textbookSync ?: synchronized(this) {
            textbookSync ?: TextbookSync(
                cas = casSession(context),
                repo = repository(context),
                prefs = displayPrefs(context),
            ).also { textbookSync = it }
        }

    /** 笔记·课件仓库单例（DESIGN §4.20）：归属键是课程名，与课表无关。 */
    fun noteRepository(context: Context): NoteRepository =
        noteRepository ?: synchronized(this) {
            noteRepository ?: NoteRepository(JuwDatabase.get(context)).also { noteRepository = it }
        }

    /** 本机骑行记录单例（DESIGN §3.9「最近骑行」）：只有本机还车成功会写。 */
    fun rideRecordStore(context: Context): RideRecordStore =
        rideRecordStore ?: synchronized(this) {
            rideRecordStore ?: RideRecordStore(JuwDatabase.get(context).rideRecordDao())
                .also { rideRecordStore = it }
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
     * 快趣出行会话单例（DESIGN §4.32）。与胖乖/趣智同为第三方的独立会话：
     * token 仅内存、凭证落加密 prefs（secure_kqcx.xml，已排除备份）。
     */
    fun kqcx(context: Context): KqcxSessionRepository =
        kqcxSession ?: synchronized(this) {
            kqcxSession ?: KqcxSessionRepository(
                KqcxAuthClient.create(),
                KqxCredentialStore(context.applicationContext),
            ).also { kqcxSession = it }
        }

    /**
     * 趣智校园仓库单例（DESIGN §4.30）。同为第三方的独立会话，
     * 与胖乖各存一份凭证，互不牵连。
     */
    fun qzxy(context: Context): QzxyRepository =
        qzxyRepository ?: synchronized(this) {
            qzxyRepository ?: QzxyRepository(QzxySessionStore(context.applicationContext))
                .also { qzxyRepository = it }
        }

    /** 趣智校园已绑定设备（DESIGN §4.30）。纯本地偏好，与登录会话分开存。 */
    fun qzxyDevices(context: Context): QzxyDeviceStore =
        qzxyDeviceStore ?: synchronized(this) {
            qzxyDeviceStore ?: QzxyDeviceStore(context.applicationContext)
                .also { qzxyDeviceStore = it }
        }

    /**
     * 趣智校园蓝牙链路单例（DESIGN §4.30）。
     *
     * **必须是单例**：GATT 连接是进程级资源，设备被连上后通常就停止广播，多半只接受
     * 一个连接。今日页那份 ViewModel（面板开阀）与页面、诊断页各持一份 ViewModel，
     * 各建一条链路的话，「面板里开阀 → 进页面点结束用水」会去抢同一台设备，
     * 后到的那条连不上（表现为「连接设备失败」）。
     */
    fun qzxyLink(context: Context): QzxyGattLink =
        qzxyGattLink ?: synchronized(this) {
            qzxyGattLink ?: QzxyGattLink(context.applicationContext)
                .also { qzxyGattLink = it }
        }

    /** 趣智校园调试开关（DESIGN §4.30）：目前只有「记录调试日志」，默认关。 */
    fun qzxyDebug(context: Context): QzxyDebugStore =
        qzxyDebugStore ?: synchronized(this) {
            qzxyDebugStore ?: QzxyDebugStore(context.applicationContext)
                .also { qzxyDebugStore = it }
        }

    /** 趣智校园清除命令试出来的可用参数（DESIGN §4.30）。纯本地偏好。 */
    fun qzxyClear(context: Context): QzxyClearStore =
        qzxyClearStore ?: synchronized(this) {
            qzxyClearStore ?: QzxyClearStore(context.applicationContext)
                .also { qzxyClearStore = it }
        }

    /**
     * 趣智校园进行中的用水（DESIGN §3.18）。
     *
     * **必须是单例**：今日页卡片与趣智校园页是两个独立 ViewModel 实例，靠它这条
     * [kotlinx.coroutines.flow.StateFlow] 同步「正在用水」状态；各持一份的话，
     * 页面里开阀、今日页卡片不会跟着变。
     */
    fun qzxyWatering(context: Context): QzxyWateringStore =
        qzxyWateringStore ?: synchronized(this) {
            qzxyWateringStore ?: QzxyWateringStore(context.applicationContext)
                .also { qzxyWateringStore = it }
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
     * 还车点 / 禁停区图层的落盘缓存单例（DESIGN §3.9，2026-09-28）：地图页读写、
     * 「地图缓存」卡统计与清除（`EbikeMapCache`）共用一份，避免两份实例各写各的。
     */
    fun zoneCacheStore(context: Context): ZoneCacheStore =
        zoneCacheStore ?: synchronized(this) {
            zoneCacheStore ?: ZoneCacheStore(context.applicationContext).also { zoneCacheStore = it }
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

    /** 电费流水落盘缓存（DESIGN §3.13「最近流水」）：上次成功结果给下次冷启动当种子。 */
    fun powerHistoryCache(context: Context): PowerHistoryCache =
        powerHistoryCache ?: synchronized(this) {
            powerHistoryCache ?: PowerHistoryCache(context.applicationContext)
                .also { powerHistoryCache = it }
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
