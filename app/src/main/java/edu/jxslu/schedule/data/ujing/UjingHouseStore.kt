package edu.jxslu.schedule.data.ujing

import android.content.Context

/** 收藏的洗衣房（U净 门店）。 */
data class UjingHouse(val storeId: String, val name: String)

/**
 * 收藏的洗衣房（DESIGN §4.37）。普通 `SharedPreferences`、`storeId|名称` 的 stringSet
 * ——同 QzxyDeviceStore 的存法：门店这类本地偏好不上加密，加密只对凭证。
 *
 * 同一门店按 storeId 去重：重复收藏是更新名称，不产生第二条。
 */
class UjingHouseStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun list(): List<UjingHouse> = prefs.getStringSet(KEY_HOUSES, emptySet()).orEmpty()
        .mapNotNull { raw ->
            val separator = raw.indexOf(SEPARATOR)
            if (separator <= 0) {
                null
            } else {
                UjingHouse(raw.substring(0, separator), raw.substring(separator + 1))
            }
        }
        .sortedBy { it.name }

    fun add(house: UjingHouse) {
        val current = prefs.getStringSet(KEY_HOUSES, emptySet()).orEmpty()
            .filterNot { it.substringBefore(SEPARATOR) == house.storeId }
            .toMutableSet()
        current += house.storeId + SEPARATOR + house.name
        prefs.edit().putStringSet(KEY_HOUSES, current).apply()
    }

    fun remove(storeId: String) {
        val current = prefs.getStringSet(KEY_HOUSES, emptySet()).orEmpty()
            .filterNot { it.substringBefore(SEPARATOR) == storeId }
            .toSet()
        prefs.edit().putStringSet(KEY_HOUSES, current).apply()
    }

    private companion object {
        const val FILE_NAME = "ujing_houses"
        const val KEY_HOUSES = "houses"
        const val SEPARATOR = "|"
    }
}
