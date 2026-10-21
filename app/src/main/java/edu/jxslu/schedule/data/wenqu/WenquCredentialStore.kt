package edu.jxslu.schedule.data.wenqu

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 问渠登录凭证（DESIGN §4.45）。**密码可选**——「记住登录」默认开；关掉时只落 token，
 * 存了密码才能在 JWT 到期前自动续期（见 [WenquSession.canRefresh]）。
 *
 * [expiresAt] 从 JWT 的 `exp` 解析；[hours] / [starLevel] 是**上次成功取到的展示影子**，
 * 用于「我的」页账号卡一行与问渠页首屏，进页后会再刷一次。
 * 平台响应里的学号 / 姓名 / 负责人姓名电话**一律不落盘**（负责人只在详情弹层展示，见 WenquModels）。
 */
data class WenquSession(
    val phone: String,
    val password: String,
    val token: String,
    val expiresAt: Long,
    val starLevel: Int = 0,
    /** 上次取到的总工时；[hoursFetchedAt] 为 0 表示还没取过。 */
    val hours: Double = 0.0,
    val hoursFetchedAt: Long = 0L,
) {
    /**
     * 会话可用的判据：手机号 + token。**密码不是必要条件**——「记住登录」关掉时只存 token
     * （默认开，2026-10-21），JWT 有效期内照常可用。
     */
    val isComplete: Boolean get() = phone.isNotBlank() && token.isNotBlank()

    /** 存了密码才能自动续期；没存密码的会话在 JWT 到期后要求重新登录。 */
    val canRefresh: Boolean get() = password.isNotBlank()
}

/**
 * 问渠凭证加密存储（DESIGN §4.45）。
 *
 * 与 [edu.jxslu.schedule.data.qzxy.QzxySessionStore] 同方案、**不同文件**（`secure_wenqu`）：
 * 各第三方会话互不相干，混在一个文件里退登录会互相牵连。
 * 新文件同样要在 `backup_rules.xml` / `data_extraction_rules.xml` 里排除
 * （正式件 + `.fallback` 两份），免得跟着云备份或设备迁移跑到别的机子上。
 */
class WenquCredentialStore(context: Context) {
    private val appContext = context.applicationContext

    /**
     * 带自愈的加密 prefs 创建（口径同 [edu.jxslu.schedule.data.qzxy.QzxySessionStore]）：
     * Keystore 损坏（换机 / 云备份恢复后常见）时裸 create 在组合期抛异常 = 启动即循环崩溃；
     * 失败先删文件重建（一次性丢会话，换功能可用），仍失败退明文空 prefs（等同未登录）。
     */
    private val prefs: SharedPreferences by lazy {
        fun create(): SharedPreferences = EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        runCatching { return@lazy create() }
        runCatching {
            appContext.deleteSharedPreferences(FILE_NAME)
            return@lazy create()
        }
        android.util.Log.e("WenquCredentialStore", "$FILE_NAME unrecoverable, fallback to plain")
        appContext.getSharedPreferences("$FILE_NAME.fallback", Context.MODE_PRIVATE)
    }

    fun read(): WenquSession? {
        val phone = prefs.getString(KEY_PHONE, null)?.takeIf { it.isNotBlank() } ?: return null
        val password = prefs.getString(KEY_PASSWORD, "").orEmpty()
        val token = prefs.getString(KEY_TOKEN, "").orEmpty()
        return WenquSession(
            phone = phone,
            password = password,
            token = token,
            expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L),
            starLevel = prefs.getInt(KEY_STAR_LEVEL, 0),
            hours = Double.fromBits(prefs.getLong(KEY_HOURS_BITS, 0L)),
            hoursFetchedAt = prefs.getLong(KEY_HOURS_FETCHED_AT, 0L),
        )
    }

    fun save(session: WenquSession) {
        prefs.edit()
            .putString(KEY_PHONE, session.phone)
            .putString(KEY_PASSWORD, session.password)
            .putString(KEY_TOKEN, session.token)
            .putLong(KEY_EXPIRES_AT, session.expiresAt)
            .putInt(KEY_STAR_LEVEL, session.starLevel)
            .putLong(KEY_HOURS_BITS, session.hours.toBits())
            .putLong(KEY_HOURS_FETCHED_AT, session.hoursFetchedAt)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "secure_wenqu"
        const val KEY_PHONE = "phone"
        const val KEY_PASSWORD = "password"
        const val KEY_TOKEN = "token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_STAR_LEVEL = "star_level"
        const val KEY_HOURS_BITS = "hours_bits"
        const val KEY_HOURS_FETCHED_AT = "hours_fetched_at"
    }
}
