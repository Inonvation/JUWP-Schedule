package edu.jxslu.schedule.domain

/** 一条开源组件记录：[name] 展示名，[license] 许可名，[url] 官方仓库或主页。 */
data class LicenseEntry(
    val name: String,
    val license: String,
    val url: String,
)

/**
 * 「关于 → 开源许可」卡片的数据（DESIGN §3.3）。
 *
 * 只列随 APK 分发的主要组件，测试专用的 JUnit 不上屏。许可名逐个核过官方仓库的
 * LICENSE 文件或构件 POM（2026-09-27），**改依赖时要一并核**，不要按印象填。
 *
 * HugeIcons 单独说明：图标集本身（hugeicons/hugeicons）是 MIT，但本项目实际引用的
 * Compose 封装 `com.github.rikkahub:hugeicons-compose` 仓库里没有 LICENSE 文件，
 * GitHub 也识别不出许可。这里按上游图标集口径标注，并在弹窗底部写明。
 */
object OpenSourceLicenses {

    /** 本应用自己的许可。 */
    const val SELF_NAME: String = "水贝贝（本应用）"
    const val SELF_LICENSE: String = "MIT"

    /** HugeIcons 的口径说明，逐字显示在弹窗底部。 */
    const val HUGEICONS_NOTE: String =
        "HugeIcons 图标集为 MIT；本项目引用的 Compose 封装仓库未声明许可，此处按上游图标集口径标注。"

    /** 弹窗底部的一句总说明。 */
    const val FOOTER: String =
        "以上组件的完整许可文本见各自官方仓库；本应用的许可全文见仓库根目录 LICENSE。"

    val ENTRIES: List<LicenseEntry> = listOf(
        LicenseEntry(
            name = "AndroidX：Compose · Material3 · Room · Glance · Navigation · DataStore · Security · WorkManager",
            license = "Apache-2.0",
            url = "https://github.com/androidx/androidx",
        ),
        LicenseEntry(
            name = "Kotlin 标准库 · kotlinx 协程与序列化",
            license = "Apache-2.0",
            url = "https://github.com/JetBrains/kotlin",
        ),
        LicenseEntry(
            name = "OkHttp · Retrofit（含 kotlinx-serialization 转换器）",
            license = "Apache-2.0",
            url = "https://github.com/square/okhttp",
        ),
        LicenseEntry(
            name = "Jsoup",
            license = "MIT",
            url = "https://jsoup.org/license",
        ),
        LicenseEntry(
            name = "ZXing core（二维码生成）",
            license = "Apache-2.0",
            url = "https://github.com/zxing/zxing",
        ),
        LicenseEntry(
            name = "osmdroid（地图）",
            license = "Apache-2.0",
            url = "https://github.com/osmdroid/osmdroid",
        ),
        LicenseEntry(
            name = "HugeIcons（图标）",
            license = "MIT",
            url = "https://github.com/hugeicons/hugeicons",
        ),
    )
}
