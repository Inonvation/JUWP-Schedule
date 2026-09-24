package edu.jxslu.schedule.data.session

/** 一套凭证：学号 + 密码。CAS 与一卡通各一份，字段同名但**来源与用途完全不同**。 */
data class SessionCredentials(val username: String, val password: String)

/**
 * `CasSession` 对存储的全部要求（DESIGN §4.27）。
 *
 * 抽成接口只有一个目的：让会话策略（可信期 / 闸门 / 结果映射）能在 JVM 单测里跑完整
 * 分支。真正的实现 [CredentialVault] 依赖 Android Keystore，测不了。
 */
interface CredentialStore {

    fun readCas(): SessionCredentials?

    fun clearCas()

    fun readGate(target: LoginTarget): LoginGateState

    fun writeGate(target: LoginTarget, state: LoginGateState)
}
