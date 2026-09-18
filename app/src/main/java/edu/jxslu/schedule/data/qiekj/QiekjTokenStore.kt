package edu.jxslu.schedule.data.qiekj

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 胖乖登录凭证加密存储（DESIGN §4.10 决策 2）。
 *
 * 方案与参考实现 light-life 一致：EncryptedSharedPreferences（密钥在 Android Keystore，
 * 主数据 AES256-GCM），配合 backup_rules / data_extraction_rules 把 secure_token.xml
 * 排除出云备份与设备迁移，避免 token 泄漏到备份体系。
 */
class QiekjTokenStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_token",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun readToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun readPhone(): String? = prefs.getString(KEY_PHONE, null)

    fun savePhone(phone: String) {
        prefs.edit().putString(KEY_PHONE, phone).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_PHONE).apply()
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_PHONE = "phone"
    }
}
