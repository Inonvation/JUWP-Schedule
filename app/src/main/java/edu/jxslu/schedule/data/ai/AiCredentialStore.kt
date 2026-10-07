package edu.jxslu.schedule.data.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * AI 调课服务的 API Key 加密存储（DESIGN §4.39，2026-10-06）。
 *
 * 形态与 [edu.jxslu.schedule.data.kqcx.KqxCredentialStore] 同款（EncryptedSharedPreferences
 * + Keystore 损坏自愈）。baseUrl / 模型名 / 脱敏开关都不是机密，走 DataStore；
 * **只有 API Key 落这里**，因为它就是用户 DeepSeek 账号的凭据。
 * `secure_ai.xml` 已排除出云备份与设备迁移（backup_rules / data_extraction_rules）。
 */
class AiCredentialStore(context: Context) {
    private val appContext = context.applicationContext

    /** 带自愈的创建：Keystore 损坏先删文件重建（丢一次 Key，重新填即恢复）。 */
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

    /** 未配置返回 null。 */
    fun readApiKey(): String? = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    private companion object {
        const val TAG = "AiCredentialStore"
        const val FILE = "secure_ai"
        const val KEY_API_KEY = "api_key"
    }
}
