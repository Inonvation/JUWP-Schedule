package edu.jxslu.schedule.data.ykt

import edu.jxslu.schedule.data.session.CredentialVault
import edu.jxslu.schedule.data.session.LoginGateState
import edu.jxslu.schedule.data.session.LoginTarget
import edu.jxslu.schedule.data.session.SessionStatus

/**
 * 校园卡登录凭证存储（DESIGN §4.19）。
 *
 * **公开签名一个都没动**（8 个调用点：生活页 / 账单页 / 付款码 / 设置页 / 余额提醒…），
 * 内部改为委托 [CredentialVault]——加密存储的唯一读写口（DESIGN §4.27「改动面」）。
 * 文件仍是 `ykt_credentials.xml`、键名仍是 `username` / `password`，升级用户的凭证原样可读；
 * `backup_rules` / `data_extraction_rules` 照旧排除该文件，凭证不随云备份与设备迁移走。
 *
 * 构造点**只有 `Graph` 一处**——`CredentialVault` 必须是单例，否则两份
 * EncryptedSharedPreferences 实例各持一份内存缓存，一边写入另一边看不见。
 */
class YktCredentialStore(private val vault: CredentialVault) {

    data class Credentials(val username: String, val password: String)

    fun read(): Credentials? = vault.readYkt()?.let { Credentials(it.username, it.password) }

    fun save(username: String, password: String) = vault.saveYkt(username, password)

    /**
     * 清除凭证（关闭开关时调用）。
     *
     * 同时清掉一卡通的登录闸门与停用标记：凭证都没了，「已失效」也没有意义。
     * **不动教务那套**——两份凭证互不牵连（DESIGN §4.27）。
     */
    fun clear() {
        vault.clearYkt()
        vault.writeGate(LoginTarget.Ykt, LoginGateState())
        SessionStatus.clear(LoginTarget.Ykt)
    }
}
