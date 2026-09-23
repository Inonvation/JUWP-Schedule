package edu.jxslu.schedule.domain

/**
 * WGS84 → GCJ-02 坐标转换（DESIGN §3.9 / §4.23）。
 *
 * 为什么需要它：手机定位给的是 WGS84，而地图瓦片（高德栅格）与运营方的车辆坐标都是 GCJ-02。
 * 少转这一步，定位点在图上会偏出约 500 米——看起来"定位不准"，其实是两套坐标系被当成了一套。
 *
 * 只做单向（WGS84 → GCJ-02）。反向没有用途：车辆坐标本来就是 GCJ-02，
 * 画到 GCJ-02 的瓦片上不需要任何处理，**再转一次就是双重偏移**。
 *
 * 算法是国内通用的公开偏移公式（椭球参数取 Krasovsky 1940），与高德/腾讯所用的一致，
 * 与官方实现的差距在米级，用来把地图中心落到用户所在的那一片足够。
 */

/** 一个 GCJ-02 坐标点。 */
data class GcjPoint(val lat: Double, val lng: Double)

object Gcj02 {

    /** 长半轴（Krasovsky 1940 椭球），单位米。 */
    private const val SEMI_MAJOR_AXIS = 6378245.0

    /** 第一偏心率平方。 */
    private const val ECCENTRICITY_SQUARED = 0.00669342162296594323

    /**
     * WGS84 → GCJ-02。
     *
     * 中国大陆以外原样返回：偏移算法只在境内有意义，境外汇入会被算出几十公里的假位移。
     * 非法坐标（NaN / 无穷）同样原样返回，交给调用方自己判断要不要用。
     */
    fun toGcj02(lat: Double, lng: Double): GcjPoint {
        if (!lat.isFinite() || !lng.isFinite()) return GcjPoint(lat, lng)
        if (isOutsideChina(lat, lng)) return GcjPoint(lat, lng)

        var latOffset = transformLat(lng - 105.0, lat - 35.0)
        var lngOffset = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = Math.sin(radLat)
        magic = 1 - ECCENTRICITY_SQUARED * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        latOffset = (latOffset * 180.0) /
            ((SEMI_MAJOR_AXIS * (1 - ECCENTRICITY_SQUARED)) / (magic * sqrtMagic) * Math.PI)
        lngOffset = (lngOffset * 180.0) /
            (SEMI_MAJOR_AXIS / sqrtMagic * Math.cos(radLat) * Math.PI)
        return GcjPoint(lat + latOffset, lng + lngOffset)
    }

    /**
     * 是否在中国大陆范围之外。这个矩形比国界粗，只用来决定要不要做偏移——
     * 边境线上会有一两公里的判定误差，但那里的用户本来就少见。
     */
    fun isOutsideChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * Math.sqrt(Math.abs(x))
        result += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        result += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        result += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return result
    }

    private fun transformLng(x: Double, y: Double): Double {
        var result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * Math.sqrt(Math.abs(x))
        result += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        result += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        result += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return result
    }
}