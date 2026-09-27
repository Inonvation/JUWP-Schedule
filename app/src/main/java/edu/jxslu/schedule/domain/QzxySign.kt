package edu.jxslu.schedule.domain

/**
 * 趣智校园蓝牙设备接口的 `signature` 参数（DESIGN §4.30）。
 *
 * 算法由官方 `libklcxkjencry.so` 反汇编得到（2026-09-27），不再是推测：
 *
 * ```
 * inner     = md5(param1 + "&key=" + param2)
 * signature = md5(inner + "&key=sign-kailu-855c74a88b8b494187c99b08b8c9a744")
 * ```
 *
 * - `param1`：参与签名的字段按键名字典序拼成 `键名 + 值`。下单接口只放
 *   `telephone`（或 `telPhone`）、`deviceId`、`xfModel`、`randomNumber` 四项，
 *   不是请求里的全部参数。
 * - `param2`：第一次 `&key=` 后挂的值，官方放的是 loginCode。
 * - 第二次追加的是**另一个**常量，自带 `&key=` 前缀，见 [SIGN_SUFFIX]。
 *
 * 反汇编依据：
 *
 * - `_GLOBAL__sub_I_klcxkjencry.cpp` 用 `.rodata` 0xa1fd0 处的字符串构造全局
 *   `std::string`，内容是 `&key=sign-kailu-…`；
 * - `signParams` 里第一次追加的是 `.rodata` 0xa20eb 处**独立的 5 字节 `&key=`**，
 *   之后接第二个 Java 字符串，做一次 MD5；再把上面那个全局串拼上去做第二次。
 *
 * 先前把两处 `&key=` 当成同一个盐，是连续两轮试错全部落空的根因。
 */
object QzxySign {

    /**
     * 第二次哈希前固定追加的后缀。自带 `&key=` 前缀，别再加一个。
     *
     * 同一段 so 里还有 `sign-kailu=`、`1234567876543210`、`0123456789abcdef`、
     * `20181201klcx@001` 等常量，分别是 `getSk` 之类的 AES 密钥入口与旧协议用的，
     * 与签名无关，不要混进来。
     */
    const val SIGN_SUFFIX = "&key=sign-kailu-855c74a88b8b494187c99b08b8c9a744"

    /**
     * 生成 signature。
     *
     * @param fields 参与签名的键值对，**只放调用方确认要签的字段**
     * @param key 第一次 `&key=` 后挂的值，官方放的是 loginCode
     */
    fun sign(fields: Map<String, String>, key: String): String {
        val plain = fields.toSortedMap().entries.joinToString("") { it.key + it.value }
        return md5(md5(plain + "&key=" + key) + SIGN_SUFFIX)
    }

    /** 单层 MD5，小写 32 位。 */
    fun md5(input: String): String = QzxyCredential.md5Hex(input)

    /** 两次 MD5，小写 32 位。 */
    fun doubleMd5(input: String): String = md5(md5(input))

    /** 一个候选算法：标签给人看，签名给服务端。 */
    data class Candidate(val label: String, val signature: String)

    /**
     * 试错用的候选列表，**主算法排第一**。
     *
     * 反汇编已经给出答案，保留这组候选只为防备两种意外：服务端按客户端版本走两套
     * 算法；或者第一次 `&key=` 后挂的不是登录码。签名不对时服务端直接拒绝、
     * 不创建订单，所以试是安全的。
     */
    fun candidates(fields: Map<String, String>, key: String): List<Candidate> {
        val sorted = fields.toSortedMap().entries.toList()
        val plain = sorted.joinToString("") { it.key + it.value }
        val paired = sorted.joinToString("&") { "${it.key}=${it.value}" }
        val salt = SIGN_SUFFIX.removePrefix("&key=")

        return listOf(
            // 反汇编确认的算法
            Candidate("md5(md5(键+值 + &key=登录码) + 固定后缀)", sign(fields, key)),
            Candidate("md5(md5(键=值&… + &key=登录码) + 固定后缀)", md5(md5("$paired&key=$key") + SIGN_SUFFIX)),
            // 后缀去掉 &key= 前缀的版本（万一服务端只认裸盐）
            Candidate("md5(md5(键+值 + &key=登录码) + 裸盐)", md5(md5("$plain&key=$key") + salt)),
            // 第一次也用固定后缀的版本
            Candidate("md5(md5(键+值 + &key=固定后缀) + 固定后缀)", md5(md5("$plain$SIGN_SUFFIX") + SIGN_SUFFIX)),
            // 历史变体，留作对照
            Candidate("md5(md5(键+值 + &key=登录码))", doubleMd5("$plain&key=$key")),
            Candidate("md5(md5(键+值) + 固定后缀)", md5(md5(plain) + SIGN_SUFFIX)),
            Candidate("md5(md5(键+值 + &key=固定后缀))", doubleMd5("$plain$SIGN_SUFFIX")),
        ).distinctBy { it.signature }
    }
}
