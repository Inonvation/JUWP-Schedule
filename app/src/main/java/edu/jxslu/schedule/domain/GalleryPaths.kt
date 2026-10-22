package edu.jxslu.schedule.domain

/**
 * 相册目录口径（2026-10-23 改名 JUWPHub）。
 *
 * 旧版本把图存到 `Pictures/水贝贝`；新版存 `Pictures/JUWPHub`。
 * 首次冷启动由 `data/media/GalleryMigration` 搬旧图；这里只放纯路径规则，JVM 可测。
 */
object GalleryPaths {
    const val LEGACY_SUBDIR: String = "水贝贝"
    const val CURRENT_SUBDIR: String = "JUWPHub"

    /**
     * 把旧相册绝对路径改写成新目录；不是旧目录路径返回 null。
     * 只改路径字符串，不碰文件系统。
     */
    fun rewriteLegacyFilePath(path: String): String? {
        val marker = "/Pictures/${LEGACY_SUBDIR}/"
        val index = path.indexOf(marker)
        if (index < 0) return null
        return path.substring(0, index) +
            "/Pictures/${CURRENT_SUBDIR}/" +
            path.substring(index + marker.length)
    }
}
