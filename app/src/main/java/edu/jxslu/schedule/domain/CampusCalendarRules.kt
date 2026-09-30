package edu.jxslu.schedule.domain

import edu.jxslu.schedule.data.session.AutoSyncRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 校历镜像的取数规则（DESIGN §4.34）。
 *
 * 校历在学校侧不是结构化数据，就是智慧校园平台里的一张图片，且「发现新图」的接口
 * 绑微信签发的一次性 token——App 端无法自主走完那条链路。所以图走**开发者仓库镜像**
 * （`docs-public/calendar/`，图 + index.json），App 只做「拉清单 → 比学年 → 下载图」。
 *
 * 本对象只放纯判定（解析、校验、拼 URL、闸门），网络与落盘在
 * `data/repo/CampusCalendarStore`。纯 Kotlin，JVM 可测。
 */
object CampusCalendarRules {

    /** 仓库镜像根（raw.githubusercontent.com，master 分支）。 */
    const val MIRROR_BASE: String =
        "https://raw.githubusercontent.com/Inonvation/JUWP-Schedule/master/docs-public/calendar"

    /** 自动刷新间隔（小时）。校历一学年一换，7 天绰绰有余。 */
    const val REFRESH_INTERVAL_HOURS: Int = 24 * 7

    /** 学年格式：`2026-2027`。 */
    private val YEAR_RE = Regex("""^(\d{4})-(\d{4})$""")

    /** 镜像文件名白名单：小写字母数字下划线连字符 + png/jpg 后缀。挡路径穿越与怪文件。 */
    private val FILE_RE = Regex("""^[a-z0-9_-]+\.(png|jpg)$""")

    /** index.json 的形状（解析宽容：未知字段忽略，缺字段由 [parseIndex] 兜）。 */
    @Serializable
    private data class IndexRow(val year: String = "", val file: String = "")

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析 index.json；不合法（坏 JSON / 学年或文件名不过白名单）返回 null。
     *
     * 学年做语义校验：后段 = 前段 + 1（`2026-2027` 对，`2026-2026` 错）。
     */
    fun parseIndex(text: String): CampusCalendarIndex? {
        if (text.length > MAX_INDEX_BYTES) return null
        val row = runCatching { json.decodeFromString<IndexRow>(text) }.getOrNull() ?: return null
        if (!YEAR_RE.matches(row.year)) return null
        if (!FILE_RE.matches(row.file)) return null
        val (start, end) = YEAR_RE.find(row.year)!!.destructured
        if (end.toInt() != start.toInt() + 1) return null
        return CampusCalendarIndex(year = row.year, file = row.file)
    }

    /** 学年清单的完整镜像地址。 */
    fun indexUrl(): String = "$MIRROR_BASE/index.json"

    /** 校历图完整镜像地址（[CampusCalendarIndex.file] 已过白名单，可放心拼）。 */
    fun imageUrl(index: CampusCalendarIndex): String = "$MIRROR_BASE/${index.file}"

    /** 拉下来的图该存成什么名字：带学年前缀，换学年时旧图天然不冲突。 */
    fun localFileName(index: CampusCalendarIndex): String =
        "calendar_${index.year}.${index.file.substringAfterLast('.')}"

    /** 上一次成功刷新是否已过期（委托 [AutoSyncRules.shouldAttemptAt] 的统一口径）。 */
    fun shouldRefresh(lastSuccessMillis: Long?, nowMillis: Long): Boolean =
        AutoSyncRules.shouldAttemptAt(lastSuccessMillis, REFRESH_INTERVAL_HOURS, nowMillis)

    private const val MAX_INDEX_BYTES = 4 * 1024
}

/**
 * index.json 解析结果（已过校验）：学年标签与镜像文件名。
 */
data class CampusCalendarIndex(
    /** 学年，`2026-2027` 形态。 */
    val year: String,
    /** 镜像文件名（白名单内），如 `calendar_2026-2027.png`。 */
    val file: String,
)
