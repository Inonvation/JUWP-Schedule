package edu.jxslu.schedule.data.kqcx

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 快趣出行账号凭证加密存储（DESIGN §4.32，2026-09-28）。
 *
 * 形态与 [edu.jxslu.schedule.data.qiekj.QiekjTokenStore] 同款（EncryptedSharedPreferences
 * + Keystore 损坏自愈），但存的是**账号密码**而非 token——快趣的密码登录
 * （`userLoginByPassword`）无签名、凭证即全部，token 过期后用本地凭证静默重登
 * 才能不劳用户重新输入（token 本身仍仅内存，见 `KqcxSessionRepository`）。
 * `secure_kqcx.xml` 已排除出云备份与设备迁移（backup_rules / data_extraction_rules）。
 */
class KqxCredentialStore(context: Context) {
    private val appContext = context.applicationContext

    /** 带自愈的创建：Keystore 损坏先删文件重建（丢一次凭证，重新登录即恢复）。 */
    private val prefs: SharedPreferences by lazy {
        fun create(): SharedPreferences = EncryptedSharedPreferences.create(
            appContext,
            FILE,
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        runCatching { return@lazy create() }
        runCatching {
            appContext.deleteSharedPreferences(FILE)
            return@lazy create()
        }
        android.util.Log.e(TAG, "$FILE unrecoverable, fallback to plain")
        appContext.getSharedPreferences("$FILE.fallback", Context.MODE_PRIVATE)
    }

    /** 凭证缺失（未登录）返回 null。密码落的是**明文原文**，MD5 在请求时现算——服务端口径如此。 */
    fun readCredential(): Pair<String, String>? {
        val mobile = prefs.getString(KEY_MOBILE, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        if (mobile.isBlank() || password.isBlank()) return null
        return mobile to password
    }

    fun saveCredential(mobile: String, password: String) {
        prefs.edit().putString(KEY_MOBILE, mobile).putString(KEY_PASSWORD, password).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_MOBILE).remove(KEY_PASSWORD).apply()
    }

    private companion object {
        const val TAG = "KqxCredentialStore"
        const val FILE = "secure_kqcx"
        const val KEY_MOBILE = "mobile"
        const val KEY_PASSWORD = "password"
    }
}
