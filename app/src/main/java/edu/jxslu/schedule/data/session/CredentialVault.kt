package edu.jxslu.schedule.data.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 凭据与登录闸门的唯一读写口（DESIGN §4.27）。
 *
 * 两份凭证各自独立成文件，**互不牵连**（清一卡通不该动教务）：
 * - `jw_credentials.xml`：学号 + 统一认证密码（CAS）。备份排除规则在
 *   `backup_rules.xml` / `data_extraction_rules.xml` 里已经有了（当初为调课检测加的，
 *   一直保留着），不需要新增。
 * - `ykt_credentials.xml`：学号 + 查询密码（一卡通 / 缴费平台）。**文件与键名沿用旧版**
 *   （`username` / `password`），升级用户的凭证原样可读。
 *
 * 闸门状态（连续失败次数、停用标记）放普通 prefs：它不是机密，省一次 Keystore 调用。
 * 但**必须落盘**——只存内存的话进程一重启闸门就忘了，用户改密码前每次都再撞两次风控。
 */
class CredentialVault(context: Context) : CredentialStore, YktTokenCache {

    private val appContext = context.applicationContext

    private val casPrefs: SharedPreferences by lazy { encrypted(FILE_CAS) }
    private val yktPrefs: SharedPreferences by lazy { encrypted(FILE_YKT) }
    private val gatePrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(FILE_GATE, Context.MODE_PRIVATE)
    }
    private val tokenPrefs: SharedPreferences by lazy { encrypted(FILE_YKT_TOKENS) }

    private fun encrypted(name: String): SharedPreferences = EncryptedSharedPreferences.create(
        appContext,
        name,
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // ---------------------------------------------------------------- CAS

    // 两份凭证各留一份内存缓存。`EncryptedSharedPreferences` 的读要走 Keystore 解密
    // （首次几十毫秒），而三个 WebView 入口 + 「我的」页账户卡会各读一次，都在主线程。
    // 并发首次读最多多读一次盘、写入同样的值，无害。
    @Volatile
    private var casCache: SessionCredentials? = null

    @Volatile
    private var casCached = false

    override fun readCas(): SessionCredentials? {
        if (!casCached) {
            casCache = read(casPrefs)
            casCached = true
        }
        return casCache
    }

    fun saveCas(username: String, password: String) {
        write(casPrefs, username, password)
        casCache = SessionCredentials(username.trim(), password)
        casCached = true
    }

    override fun clearCas() {
        casPrefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
        casCache = null
        casCached = true
    }

    // ------------------------------------------------------------ 一卡通

    @Volatile
    private var yktCache: SessionCredentials? = null

    @Volatile
    private var yktCached = false

    fun readYkt(): SessionCredentials? {
        if (!yktCached) {
            yktCache = read(yktPrefs)
            yktCached = true
        }
        return yktCache
    }

    fun saveYkt(username: String, password: String) {
        write(yktPrefs, username, password)
        yktCache = SessionCredentials(username.trim(), password)
        yktCached = true
    }

    /** 清一卡通凭证时**连 token 一起清**：凭证没了，那个 token 也不该留着。 */
    fun clearYkt() {
        yktPrefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
        yktCache = null
        yktCached = true
        clearToken()
    }

    // ------------------------------------------------------------ 一卡通 token

    /**
     * 落盘的一卡通 token（DESIGN §4.27「一卡通 token 是否落盘」）。
     *
     * 用户拍板接受落盘：密码都已经存了，token 的泄露面并不更大，换来的却是每次冷启动
     * 少一次登录（取键盘 + 换 token 两条请求）。新鲜度判定见 [TokenFreshness]。
     */
    override fun readToken(nowMs: Long): String? {
        val token = tokenPrefs.getString(KEY_TOKEN, null).orEmpty()
        val savedAt = tokenPrefs.getLong(KEY_TOKEN_AT, 0L)
        if (token.isEmpty() || !TokenFreshness.isFresh(savedAt, nowMs)) return null
        return token
    }

    override fun saveToken(token: String, nowMs: Long) {
        tokenPrefs.edit().putString(KEY_TOKEN, token).putLong(KEY_TOKEN_AT, nowMs).apply()
    }

    override fun clearToken() {
        tokenPrefs.edit().remove(KEY_TOKEN).remove(KEY_TOKEN_AT).apply()
    }

    // ------------------------------------------------------------ 闸门

    override fun readGate(target: LoginTarget): LoginGateState = LoginGateState(
        consecutiveCredentialFailures = gatePrefs.getInt(key(target, SUFFIX_FAILURES), 0),
        suspended = gatePrefs.getBoolean(key(target, SUFFIX_SUSPENDED), false),
        lastSuccessMs = gatePrefs.getLong(key(target, SUFFIX_LAST_SUCCESS), 0L),
        lastAttemptMs = gatePrefs.getLong(key(target, SUFFIX_LAST_ATTEMPT), 0L),
    )

    override fun writeGate(target: LoginTarget, state: LoginGateState) {
        gatePrefs.edit()
            .putInt(key(target, SUFFIX_FAILURES), state.consecutiveCredentialFailures)
            .putBoolean(key(target, SUFFIX_SUSPENDED), state.suspended)
            .putLong(key(target, SUFFIX_LAST_SUCCESS), state.lastSuccessMs)
            .putLong(key(target, SUFFIX_LAST_ATTEMPT), state.lastAttemptMs)
            .apply()
    }

    // ---------------------------------------------------------------- 内部

    private fun read(prefs: SharedPreferences): SessionCredentials? {
        val user = prefs.getString(KEY_USERNAME, null)?.trim().orEmpty()
        val pwd = prefs.getString(KEY_PASSWORD, null).orEmpty()
        if (user.isEmpty() || pwd.isEmpty()) return null
        return SessionCredentials(user, pwd)
    }

    private fun write(prefs: SharedPreferences, username: String, password: String) {
        prefs.edit()
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    private fun key(target: LoginTarget, suffix: String) = "${target.name.lowercase()}_$suffix"

    private companion object {
        const val FILE_CAS = "jw_credentials"
        const val FILE_YKT = "ykt_credentials"
        const val FILE_GATE = "login_gate"
        const val FILE_YKT_TOKENS = "ykt_tokens"

        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_TOKEN = "token"
        const val KEY_TOKEN_AT = "token_at"

        const val SUFFIX_FAILURES = "failures"
        const val SUFFIX_SUSPENDED = "suspended"
        const val SUFFIX_LAST_SUCCESS = "last_success"
        const val SUFFIX_LAST_ATTEMPT = "last_attempt"
    }
}
