package edu.jxslu.schedule.data.qzxy

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 趣智校园登录凭证加密存储（DESIGN §4.30）。
 *
 * 与胖乖 [edu.jxslu.schedule.data.qiekj.QiekjTokenStore] 同方案、**不同文件**
 * （`secure_qzxy`）：两个第三方的会话互不相干，混在一个文件里退登录会互相牵连。
 * 新文件同样要在 `backup_rules.xml` / `data_extraction_rules.xml` 里排除，
 * 免得跟着云备份或设备迁移跑到别的机子上。
 */
class QzxySessionStore(context: Context) {
    private val appContext = context.applicationContext

    /**
     * 带自愈的加密 prefs 创建（2026-09-28 审查补，口径同 QiekjTokenStore）：
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
        android.util.Log.e("QzxySessionStore", "$FILE_NAME unrecoverable, fallback to plain")
        appContext.getSharedPreferences("$FILE_NAME.fallback", Context.MODE_PRIVATE)
    }

    fun read(): QzxySession? {
        val loginCode = prefs.getString(KEY_LOGIN_CODE, null)?.takeIf { it.isNotBlank() } ?: return null
        return QzxySession(
            loginCode = loginCode,
            userId = prefs.getString(KEY_USER_ID, "").orEmpty(),
            accountId = prefs.getString(KEY_ACCOUNT_ID, "").orEmpty(),
            projectId = prefs.getString(KEY_PROJECT_ID, "").orEmpty(),
            telephone = prefs.getString(KEY_TELEPHONE, "").orEmpty(),
            name = prefs.getString(KEY_NAME, "").orEmpty(),
        )
    }

    fun save(session: QzxySession) {
        prefs.edit()
            .putString(KEY_LOGIN_CODE, session.loginCode)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_ACCOUNT_ID, session.accountId)
            .putString(KEY_PROJECT_ID, session.projectId)
            .putString(KEY_TELEPHONE, session.telephone)
            .putString(KEY_NAME, session.name)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "secure_qzxy"
        const val KEY_LOGIN_CODE = "login_code"
        const val KEY_USER_ID = "user_id"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_TELEPHONE = "telephone"
        const val KEY_NAME = "name"
    }
}
