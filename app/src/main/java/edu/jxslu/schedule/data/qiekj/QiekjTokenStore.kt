package edu.jxslu.schedule.data.qiekj

import android.content.Context
import android.util.Log
import android.content.SharedPreferences
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
    private val appContext = context.applicationContext

    /**
     * 带自愈的加密 prefs 创建（2026-09-28 审查补）：Keystore 损坏时（换机/云备份恢复后
     * 常见）裸 create 会抛异常且调用点在组合期——App 启动即循环崩溃。失败先删文件重建
     * （一次性丢 token，换功能可用），仍失败退明文空 prefs（等同未登录）。
     */
    private val prefs: SharedPreferences by lazy {
        fun create(): SharedPreferences = EncryptedSharedPreferences.create(
            appContext,
            "secure_token",
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        runCatching { return@lazy create() }
        runCatching {
            appContext.deleteSharedPreferences("secure_token")
            return@lazy create()
        }
        android.util.Log.e("QiekjTokenStore", "secure_token unrecoverable, fallback to plain")
        appContext.getSharedPreferences("secure_token.fallback", Context.MODE_PRIVATE)
    }

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
