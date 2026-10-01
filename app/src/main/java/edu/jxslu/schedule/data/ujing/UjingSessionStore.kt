package edu.jxslu.schedule.data.ujing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** U净 本机会话（DESIGN §4.37）：手机号 + JWT。 */
data class UjingSession(
    val mobile: String,
    val token: String,
    val userId: String = "",
) {
    val isComplete: Boolean get() = mobile.isNotBlank() && token.isNotBlank()
}

/**
 * U净 登录凭证加密存储（DESIGN §4.37）。
 *
 * 与胖乖 / 趣智 / 快趣同方案、**不同文件**（`secure_ujing`）：各自会话互不相干，
 * 混在一个文件里退登录会互相牵连。新文件同样要排除两套备份规则
 * （`backup_rules.xml` / `data_extraction_rules.xml`），不跟云备份与设备迁移走。
 */
class UjingSessionStore(context: Context) {
    private val appContext = context.applicationContext

    /**
     * 带自愈的加密 prefs 创建（口径同 QzxySessionStore / QiekjTokenStore）：
     * Keystore 损坏时裸 create 在组合期抛异常 = 启动即循环崩溃；失败先删文件重建
     * （一次性丢会话，换功能可用），仍失败退明文空 prefs（等同未登录）。
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
        android.util.Log.e("UjingSessionStore", "$FILE_NAME unrecoverable, fallback to plain")
        appContext.getSharedPreferences("$FILE_NAME.fallback", Context.MODE_PRIVATE)
    }

    fun read(): UjingSession? {
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return UjingSession(
            mobile = prefs.getString(KEY_MOBILE, "").orEmpty(),
            token = token,
            userId = prefs.getString(KEY_USER_ID, "").orEmpty(),
        )
    }

    /** 登录表单预填用：上次登录的手机号。 */
    fun lastMobile(): String = prefs.getString(KEY_MOBILE, "").orEmpty()

    fun save(session: UjingSession) {
        prefs.edit()
            .putString(KEY_MOBILE, session.mobile)
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_USER_ID, session.userId)
            .apply()
    }

    /** 会话过期：只清 token、保留手机号（重新登录少输一次号码）。 */
    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "secure_ujing"
        const val KEY_MOBILE = "mobile"
        const val KEY_TOKEN = "token"
        const val KEY_USER_ID = "user_id"
    }
}
