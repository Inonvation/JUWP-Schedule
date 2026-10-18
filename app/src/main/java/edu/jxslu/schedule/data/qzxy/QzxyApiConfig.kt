package edu.jxslu.schedule.data.qzxy

/**
 * 趣智校园接口配置（DESIGN §4.30）。
 *
 * 域名、版本号与两个参考实现（linyu、quzhi-lite）以及公开协议资料一致：
 * `https://v3-api.china-qzxy.cn/`，`version=6.5.28`。服务端若收紧校验，先动这里。
 */
object QzxyApiConfig {
    const val BASE_URL = "https://v3-api.china-qzxy.cn/"
    const val VERSION = "6.5.28"
    const val PHONE_SYSTEM = "android"

    /** 蓝牙设备广播名前缀，扫描过滤用（官方水控设备形如 `KLCXKJ-Water,G,490067305242`）。 */
    const val BLE_NAME_PREFIX = "KLCXKJ"

    /**
     * Microchip/ISSC 透传服务的 UUID。
     *
     * 设备广播里不一定带名字（部分手机/系统会截断或隐藏 deviceName），
     * 但服务 UUID 通常还在广播包里；扫描时名字前缀与服务 UUID 二者命中其一即可。
     */
    const val BLE_SERVICE_UUID = "49535343-fe7d-4ae5-8fa9-9fafd205e455"

}
