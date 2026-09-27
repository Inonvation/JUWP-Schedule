package edu.jxslu.schedule.data.qzxy

/**
 * 趣智校园接口配置（DESIGN §4.30）。
 *
 * 域名、版本号与两个参考实现（linyu、quzhi-lite）以及看雪 2025-11-29 的分析一致：
 * `https://v3-api.china-qzxy.cn/`，`version=6.5.28`。服务端若收紧校验，先动这里。
 */
object QzxyApiConfig {
    const val BASE_URL = "https://v3-api.china-qzxy.cn/"
    const val VERSION = "6.5.28"
    const val PHONE_SYSTEM = "android"

    /** 蓝牙设备广播名前缀，扫描过滤用（官方水控设备形如 `KLCXKJ-Water,G,490067305242`）。 */
    const val BLE_NAME_PREFIX = "KLCXKJ"

}
