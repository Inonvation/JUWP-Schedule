package edu.jxslu.schedule.domain

/**
 * 空调红外的协议口径（DESIGN §4.48）。
 *
 * - [R05D]：国内美的挂机最常见的一套（遥控器 R05D/BG 等），6 字节 `B2 4D ...`，
 *   开关 / 模式 / 温度 / 风速齐全，没有睡眠 / 定时 / 摆风等公开编码。
 * - [A1]：IRremoteESP8266 `ir_Midea` 的 48 位口径（Pioneer / Comfee / Danby 等
 *   OEM 与部分美的机型），带睡眠 / 定时 / 切换帧。
 *
 * 默认走 [R05D]：寝室里的国产美的按这套发才动。切换只改本机口径，不发红外。
 */
enum class AcProtocol { R05D, A1 }
