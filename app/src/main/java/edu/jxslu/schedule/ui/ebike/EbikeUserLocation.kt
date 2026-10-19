package edu.jxslu.schedule.ui.ebike

/**
 * 骑行页最近一次用户定位的**进程内缓存**（2026-10-19）。
 *
 * 只服务自动还车的坐标选择：手动还车会现取一次定位；自动还车在后台跑，不能弹权限、
 * 也不该主动拉起定位，所以复用页面连续定位留下的最后读数。缓存不落盘、不带账号信息，
 * 超过 [MAX_AGE_MS] 就当作没有。
 */
internal object EbikeUserLocation {

    /** 缓存有效期：与 `BikeLocator` 的「五分钟缓存定位」同口径。 */
    private const val MAX_AGE_MS = 5 * 60_000L

    data class Fix(val lat: Double, val lng: Double, val atMillis: Long)

    @Volatile
    private var fix: Fix? = null

    fun update(lat: Double, lng: Double, nowMillis: Long = System.currentTimeMillis()) {
        if (!lat.isFinite() || !lng.isFinite()) return
        fix = Fix(lat, lng, nowMillis)
    }

    fun fresh(nowMillis: Long = System.currentTimeMillis()): Fix? {
        val current = fix ?: return null
        val age = nowMillis - current.atMillis
        return current.takeIf { age in 0..MAX_AGE_MS }
    }
}
