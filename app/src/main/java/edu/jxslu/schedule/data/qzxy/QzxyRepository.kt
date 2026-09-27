package edu.jxslu.schedule.data.qzxy

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import edu.jxslu.schedule.data.qiekj.QiekjJson
import edu.jxslu.schedule.domain.QzxyCredential
import edu.jxslu.schedule.domain.QzxySign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 蓝牙下单所需的一组现场参数（DESIGN §4.30）。
 *
 * 全部来自蓝牙侧的设备查询响应，少一个服务端就不认。字段命名沿用官方两套口径：
 * [useLegacyFieldNames] = true 对应 `macAddress / bigTypeId`，签名里放 `telephone`；
 * false 对应 `deviceMac / mainTypeId`，签名里放 `telPhone`。App 用哪套由它内部的
 * `MyApp.notBLE` 开关决定，我们这边做成可切换，真机试哪套通就固定哪套。
 */
data class QzxyRateOrderRequest(
    val macAddress: String,
    val macTypeHex: String,
    val protocolType: String,
    val deviceId: String,
    val bigTypeId: String,
    val smallTypeId: String,
    val randomNumber: String,
    val useLegacyFieldNames: Boolean = true,
)

/**
 * 趣智校园仓库（DESIGN §4.30）：登录态、账号信息、余额、账单，
 * 以及蓝牙型设备的两段式下单。
 *
 * 蓝牙开阀的实际链路是「先蓝牙读设备、再 HTTP 下单、再把 downData 写回设备」，
 * 蓝牙那一半在 [edu.jxslu.schedule.data.qzxy.link.QzxyLink] 里，本类只负责 HTTP 这一段，
 * 参数由流程层从设备响应里取好后传进来。这样职责边界清楚：
 * 签名或字段错了改这里，蓝牙连接错了改那边。
 */
class QzxyRepository(private val sessionStore: QzxySessionStore) {

    private val api: QzxyApi

    /**
     * 登录态流（对齐胖乖仓库的 [edu.jxslu.schedule.data.qiekj.QiekjRepository.loggedIn]）。
     * 页面直接订阅它，登录/退出在任何窗口发生都能即时反映；
     * 用 `ON_RESUME` 重读一次的老写法只覆盖「从子页返回」那一条路径。
     */
    private val _loggedIn = MutableStateFlow(sessionStore.read() != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    init {
        // BASIC 级日志只打请求行，不打 header 与 body：loginCode、signature 不进 Logcat
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
        api = Retrofit.Builder()
            .baseUrl(QzxyApiConfig.BASE_URL)
            .client(client)
            .addConverterFactory(QiekjJson.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(QzxyApi::class.java)
    }

    // ── 登录态 ──

    fun localSession(): QzxySession? = sessionStore.read()

    fun logout() {
        sessionStore.clear()
        _loggedIn.value = false
    }

    private fun requireSession(): QzxySession =
        sessionStore.read()?.takeIf { it.isComplete }
            ?: throw QzxySessionExpiredException("登录信息不完整，请重新登录")

    // ── 登录 ──

    suspend fun sendCode(phone: String) = withContext(Dispatchers.IO) {
        api.sendCode(telephone = phone, secret = QzxyCredential.smsSecret(phone)).requireSuccess()
    }

    suspend fun loginByPassword(phone: String, password: String): QzxySession = withContext(Dispatchers.IO) {
        val envelope = api.login(
            telephone = phone,
            password = QzxyCredential.passwordValue(password),
        )
        envelope.requireSuccess()
        persistLogin(envelope.data, phone)
    }

    suspend fun loginBySms(phone: String, smsCode: String): QzxySession = withContext(Dispatchers.IO) {
        val envelope = api.loginBySms(telephone = phone, smsCode = smsCode)
        envelope.requireSuccess()
        persistLogin(envelope.data, phone)
    }

    private fun persistLogin(data: JsonElement?, phone: String): QzxySession {
        val loginData = decode(data, QzxyLoginData.serializer())
            ?: throw QzxyApiException("登录响应缺少用户信息")
        val session = QzxySession.from(loginData, phone)
        if (!session.isComplete) {
            throw QzxyApiException("登录响应缺少项目或账号标识，可能是新学校未接入")
        }
        sessionStore.save(session)
        _loggedIn.value = true
        return session
    }

    // ── 账号 / 项目 ──

    /** 学校名。用它确认登录确实落在本校项目上，而不是别的学校。 */
    suspend fun projectInfo(): QzxyProjectInfo = withContext(Dispatchers.IO) {
        val envelope = api.projectInfo(requireSession().authFields())
        envelope.requireSuccess()
        decode(envelope.data, QzxyProjectInfo.serializer()) ?: QzxyProjectInfo()
    }

    /**
     * 验证一份外部粘进来的会话：拿它调一次只读接口（学校名）。
     *
     * **先验后存**：一落盘登录态就翻，无效的会话必须挡在存之前，否则用户会看到
     * 「已登录但什么都查不到」。与胖乖的 token 粘贴登录同构（那边验的是余额）。
     */
    suspend fun validateSession(candidate: QzxySession): QzxyProjectInfo = withContext(Dispatchers.IO) {
        val envelope = api.projectInfo(candidate.authFields())
        envelope.requireSuccess()
        decode(envelope.data, QzxyProjectInfo.serializer()) ?: QzxyProjectInfo()
    }

    /** 把一份已经确认有效的会话落盘并翻转登录态。 */
    fun adoptSession(session: QzxySession) {
        sessionStore.save(session)
        _loggedIn.value = true
    }

    suspend fun balance(): QzxyBalance = withContext(Dispatchers.IO) {
        val envelope = api.wallet(requireSession().authFields())
        envelope.requireSuccess()
        decode(envelope.data, QzxyBalance.serializer()) ?: QzxyBalance()
    }

    // ── 设备 / 账单 ──

    /**
     * 按 MAC 查设备详情。返回里的 `communicationTypeId`、`onlineStatusId`、`isMigrated`
     * 是判断「这台设备走网络还是走蓝牙」的现场证据，诊断页要把原样值展示出来。
     *
     * 会依次试 [macLookupValues] 给出的几个候选值：服务端对同一台设备可能只认其中一种写法，
     * 查不到时换一个再试一次，比让用户对着「设备不存在」猜要省事。
     */
    suspend fun deviceInfo(macAddress: String): QzxyDeviceInfo? = withContext(Dispatchers.IO) {
        val session = requireSession()
        for (candidate in macLookupValues(macAddress)) {
            val params = session.authFields() + mapOf(
                "macAddress" to candidate,
                "isNew" to "1",
            )
            val envelope = api.deviceInfo(params)
            // 会话问题直接上抛；「这台设备查不到」只是这个候选不对，换下一个
            if (QzxySessionExpiredException.matches(envelope.errorCode, envelope.text)) {
                throw QzxySessionExpiredException(envelope.text ?: "登录已失效，请重新登录")
            }
            if (!envelope.success) continue
            decode(envelope.data, QzxyDeviceInfo.serializer())?.let { return@withContext it }
        }
        null
    }

    /**
     * 设备查询的 MAC 候选值，顺序即尝试顺序。
     *
     * **C0 开头要额外试一次前两位置换成 00**：这条来自 quzhi-lite 的实现
     * （它在查设备前对 `C0` 前缀做了同样替换，两个参考项目只有它这么做），
     * 推测原因是 Android 上报的蓝牙地址与设备实际登记的 MAC 头一字节不一致。
     * 实测本校设备的广播地址都以 `C0` 开头（服务端登记的是 `00` 开头），所以这不是理论问题。
     */
    private fun macLookupValues(raw: String): List<String> {
        val hex = raw.replace(":", "").replace("-", "").uppercase(Locale.ROOT)
        val variants = if (hex.length == 12 && hex.startsWith("C0")) {
            listOf("00" + hex.drop(2), hex)
        } else {
            listOf(hex)
        }
        return variants.distinct()
    }

    suspend fun billList(month: String): List<QzxyBill> = withContext(Dispatchers.IO) {
        val params = requireSession().authFields() + mapOf(
            "month" to month,
            "billRequestType" to "2",
        )
        val envelope = api.billList(params)
        envelope.requireSuccess()
        val element = envelope.data ?: return@withContext emptyList()
        runCatching {
            QiekjJson.json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(QzxyBillList.serializer()),
                element,
            )
        }.getOrDefault(emptyList()).mapNotNull { it.consumeBillDTO }
    }

    // ── 蓝牙开阀（要签名）──

    /**
     * 蓝牙下单：把现场参数换成服务端签发的 `downData`。
     *
     * **签名字段集合与请求字段集合不是一回事**：签名只放 `telephone`（或 `telPhone`）、
     * `deviceId`、`xfModel`、`randomNumber` 四项，见 [QzxySign] 的说明。
     */
    suspend fun rateOrder(
        request: QzxyRateOrderRequest,
        /** 试签名时由调用方指定；正常流程留空，走 [rateOrderSignFields] 与 [QzxySign] 的默认实现。 */
        signatureOverride: String? = null,
    ): QzxyRateOrderData = withContext(Dispatchers.IO) {
        val session = requireSession()
        val signature = signatureOverride ?: QzxySign.sign(
            fields = rateOrderSignFields(session, request),
            // 第一次 &key= 后挂会话值；固定后缀由 QzxySign 自己追加（见 SIGN_SUFFIX）
            key = session.loginCode,
        )
        val form = buildMap {
            putAll(session.authFields())
            put("xfModel", "0")
            put("deviceId", request.deviceId)
            put("macType", request.macTypeHex)
            put("protocolType", request.protocolType)
            put("randomNumber", request.randomNumber)
            put("signature", signature)
            put("smallTypeId", request.smallTypeId)
            if (request.useLegacyFieldNames) {
                put("macAddress", request.macAddress)
                put("bigTypeId", request.bigTypeId)
            } else {
                put("deviceMac", request.macAddress)
                put("mainTypeId", request.bigTypeId)
            }
        }
        val envelope = api.rateOrder(form)
        envelope.requireSuccess()
        decode(envelope.data, QzxyRateOrderData.serializer())
            ?: throw QzxyApiException("下单成功但未返回 downData，无法下发到设备")
    }

    /**
     * 下单接口参与签名的字段集合。公开资料与反编译片段一致：只有这四项，
     * 不是请求里的全部参数。试签名时也用这一份，保证试的就是真在用的输入。
     */
    fun rateOrderSignFields(
        session: QzxySession,
        request: QzxyRateOrderRequest,
    ): Map<String, String> = buildMap {
        put(if (request.useLegacyFieldNames) "telephone" else "telPhone", session.telephone)
        put("deviceId", request.deviceId)
        put("xfModel", "0")
        put("randomNumber", request.randomNumber)
    }

    /**
     * 下单签名的全部候选（见 [QzxySign.candidates]）。登录态不完整时返回空表。
     *
     * 两组都生成：官方 App 内部按 `MyApp.notBLE` 开关决定签名里放 `telephone` 还是
     * `telPhone`，我们不知道本校设备落在哪一支，标签前缀标出来，试通哪组就固定哪组。
     */
    fun rateOrderSignCandidates(request: QzxyRateOrderRequest): List<QzxySign.Candidate> {
        val session = sessionStore.read() ?: return emptyList()
        val telephone = QzxySign.candidates(
            rateOrderSignFields(session, request.copy(useLegacyFieldNames = true)),
            session.loginCode,
        ).map { it.copy(label = "[telephone] ${it.label}") }
        val telPhone = QzxySign.candidates(
            rateOrderSignFields(session, request.copy(useLegacyFieldNames = false)),
            session.loginCode,
        ).map { it.copy(label = "[telPhone] ${it.label}") }
        return (telephone + telPhone).distinctBy { it.signature }
    }

    /**
     * 上报消费数据结算。`xfData` 是从设备读回的消费记录（十六进制文本）。
     * 返回的 `consumeMoney` 单位是**厘**，展示前除 1000。
     */
    suspend fun uploadConsume(
        xfData: String,
        randomNumber: String,
        protocolType: String,
    ): QzxyConsumeResult = withContext(Dispatchers.IO) {
        val session = requireSession()
        val signature = QzxySign.sign(
            fields = mapOf(
                "loginCode" to session.loginCode,
                "telephone" to session.telephone,
                "xfData" to xfData,
            ),
            key = session.loginCode,
        )
        val form = buildMap {
            putAll(session.authFields())
            put("xfData", xfData)
            put("randomNumber", randomNumber)
            put("protocolType", protocolType)
            put("signature", signature)
        }
        val envelope = api.uploadConsume(form)
        envelope.requireSuccess()
        decode(envelope.data, QzxyConsumeResult.serializer()) ?: QzxyConsumeResult()
    }

    private fun <T> decode(element: JsonElement?, serializer: KSerializer<T>): T? {
        if (element == null) return null
        return runCatching { QiekjJson.json.decodeFromJsonElement(serializer, element) }
            .getOrElse { throw QzxyApiException("接口响应格式无法解析：${it.message}") }
    }
}
