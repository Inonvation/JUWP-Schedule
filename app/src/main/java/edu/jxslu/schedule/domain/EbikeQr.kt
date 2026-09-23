package edu.jxslu.schedule.domain

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 共享单车骑行二维码（DESIGN §3.9 / §4.18）。
 *
 * 车身码 = 普通链接二维码 `https://www.kvcoogo.com/ebike?id=<完整车号>`；微信扫码后
 * 命中运营方「扫普通链接二维码打开小程序」规则，**完整 URL 经 `q` 参数透传给小程序**，
 * 小程序自行解析 id 加载车辆。因此只要前缀不变、改 id 依然命中——App 内输入尾部
 * 车号拼 URL 出码即可。本文件只做纯 JVM 的拼装与矩阵生成，不含任何 UI 与网络。
 */
object EbikeQr {

    /** 链接模板：`100000` 为车队编号段（实测样例 `100000669`），尾部 3 位才是车身号。 */
    const val TEMPLATE = "100000"

    private const val BASE_URL = "https://www.kvcoogo.com/ebike"

    /** 尾部车号位数（用户口径：改后面三位即可）。 */
    const val TAIL_LENGTH = 3

    /**
     * 完整车号的位数范围（2026-09-23 起输入框也接受完整车号）。
     *
     * 实测样例都是 9 位（`100000652` / `300000604` / `300000080`），留出上下浮动余量，
     * 但不放宽到「任意长度」——宁可不出一张扫不开的码。
     */
    const val CAR_NUM_MIN_LENGTH = 6
    const val CAR_NUM_MAX_LENGTH = 12

    /** 输入框允许的最大位数（完整车号上限）。 */
    const val INPUT_MAX_LENGTH = CAR_NUM_MAX_LENGTH

    /** QR 输出像素边长（存相册用大图，扫码距离近，大了不亏）。 */
    const val QR_SIZE_PX = 720

    /** 最近车号历史上限（DESIGN §3.9：8 个，倒序去重）。 */
    const val RECENT_LIMIT = 8

    /** 待焚毁二维码记录上限（扫完即焚；正常使用到不了，只防异常膨胀）。 */
    const val PENDING_DELETE_LIMIT = 32

    /** 待焚毁记录前缀：MediaStore uri（API 29+）。 */
    const val PENDING_MEDIA_PREFIX = "m:"

    /** 待焚毁记录前缀：公共目录文件绝对路径（API 26–28）。 */
    const val PENDING_FILE_PREFIX = "f:"

    /**
     * 车号输入区的说明文案（UI 唯一出处）。
     *
     * 放这里是因为它由 [TAIL_LENGTH] / [CAR_NUM_MIN_LENGTH] / [CAR_NUM_MAX_LENGTH]
     * 算出来，改位数只改一处；输入框的 `supportingText` 与校验失败的行内提示共用它。
     */
    val INPUT_HINT: String =
        "填 $TAIL_LENGTH 位尾部车号（车身二维码后三位），或粘贴 $CAR_NUM_MIN_LENGTH~" +
            "$CAR_NUM_MAX_LENGTH 位完整车号"

    /** 待焚毁记录的删除通道：决定走 MediaStore 还是 File。 */
    enum class PendingKind { MediaStore, FilePath }

    /** MediaStore 保存条目 → 待焚毁 key。 */
    fun pendingMediaKey(uri: String): String = PENDING_MEDIA_PREFIX + uri

    /** 公共目录保存文件 → 待焚毁 key。 */
    fun pendingFileKey(path: String): String = PENDING_FILE_PREFIX + path

    /**
     * 待焚毁 key → 删除通道与目标。未知前缀（脏数据/旧版本）返回 null，
     * 调用方丢弃即可——删错文件比漏删一张码严重得多。
     */
    fun parsePendingKey(key: String): Pair<PendingKind, String>? = when {
        key.startsWith(PENDING_MEDIA_PREFIX) ->
            key.removePrefix(PENDING_MEDIA_PREFIX).takeIf { it.isNotBlank() }
                ?.let { PendingKind.MediaStore to it }
        key.startsWith(PENDING_FILE_PREFIX) ->
            key.removePrefix(PENDING_FILE_PREFIX).takeIf { it.isNotBlank() }
                ?.let { PendingKind.FilePath to it }
        else -> null
    }

    /**
     * 把刚保存的待焚毁 key 并入记录集：去重、防膨胀（超 [PENDING_DELETE_LIMIT]
     * 丢最旧的——Set 无序，这里只是兜底防膨胀，不承诺淘汰顺序）。
     */
    fun mergePendingDelete(current: Set<String>, key: String): Set<String> =
        (current + key).let { merged ->
            if (merged.size > PENDING_DELETE_LIMIT) {
                merged.toList().takeLast(PENDING_DELETE_LIMIT).toSet()
            } else merged
        }

    /**
     * 拼完整骑行链接。车号必须是 [CAR_NUM_MIN_LENGTH]~[CAR_NUM_MAX_LENGTH] 位数字，
     * 否则返回 null——非法输入宁可拒掉也不出一张扫不开的码。
     */
    fun bikeUrl(carNum: String): String? {
        if (carNum.length < CAR_NUM_MIN_LENGTH || carNum.length > CAR_NUM_MAX_LENGTH) return null
        if (carNum.any { it !in '0'..'9' }) return null
        return "$BASE_URL?id=$carNum"
    }

    /**
     * 输入框原始文本 → 规整输入（只留数字，上限 [INPUT_MAX_LENGTH] 位）。
     * UI 层的唯一入口口径（DESIGN §3.9）。
     *
     * **不再剥模板前缀**（2026-09-23 改）：整条校园车号（`100000669`）原样留着，
     * 因为别的校区车号前缀不是 `100000`，剥掉就凑不回去了。旧版"剥前缀再截三位"
     * 对 `100000669` 得到的仍是同一个出行链接，行为上等价。
     */
    fun normalizeCarInput(raw: String): String =
        raw.filter { it in '0'..'9' }.take(INPUT_MAX_LENGTH)

    /**
     * 规整输入 → 完整车号；还构不成合法车号时返回 null。
     *
     * - 1~3 位：校园车队尾部，补 [TEMPLATE] 前缀（`669` → `100000669`）；
     * - [CAR_NUM_MIN_LENGTH]~[CAR_NUM_MAX_LENGTH] 位：按完整车号原样使用
     *   （地图选中的车就是这个形态，别的校区前缀是 `300000…`，靠尾部三位拼不出来）；
     * - 其余（空串、4~5 位）：null。
     *
     * 判定纯看长度，不看前缀：`100000669` 是完整车号，`669` 是尾部，
     * 不会出现"把完整车号当成尾部再拼一次前缀"的重叠。
     */
    fun resolveCarNum(input: String): String? = when {
        input.isEmpty() -> null
        input.any { it !in '0'..'9' } -> null
        input.length <= TAIL_LENGTH -> TEMPLATE + input
        input.length in CAR_NUM_MIN_LENGTH..CAR_NUM_MAX_LENGTH -> input
        else -> null
    }

    /**
     * 输入框前缀提示（`OutlinedTextField.prefix`）：输入还停在尾部（≤3 位）时显示
     * [TEMPLATE]，已经输/粘了完整车号时返回空串。
     *
     * 前缀是纯展示，但展示错了等于骗人：选中别的校区的车（`300000604`）却顶着
     * `100000` 的前缀，用户会以为出的是校园车。
     */
    fun inputPrefix(input: String): String =
        if (input.isNotEmpty() && input.length <= TAIL_LENGTH) TEMPLATE else ""

    /** 完整车号 → 展示用尾部三位（`100000669` → `669`）；不足三位原样返回。 */
    fun tailOf(carNum: String): String =
        if (carNum.length <= TAIL_LENGTH) carNum else carNum.takeLast(TAIL_LENGTH)

    /**
     * 最近车号 chip 的文案。校园车号只展示尾部三位（`…669`），别的车队给全串——
     * 两批车号的尾部会撞（`100000669` 与 `300000669`），撞了就必须区分得开。
     */
    fun chipLabel(carNum: String): String =
        if (carNum.startsWith(TEMPLATE)) "…" + tailOf(carNum) else carNum

    /**
     * 生成 QR 位阵。容错取 M（15%，打印/屏幕亮度损失下仍有余量）；
     * 白边 1 模块（zxing 约定：margin 是模块数不是像素，1 已满足扫码器的静区要求，
     * UI 展示时再由外层容器给视觉留白）。
     */
    fun qrMatrix(url: String): com.google.zxing.common.BitMatrix =
        QRCodeWriter().encode(
            url,
            BarcodeFormat.QR_CODE,
            QR_SIZE_PX,
            QR_SIZE_PX,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            ),
        )

    /**
     * 把刚生成的车号并入最近历史（存**完整车号**）：倒序去重、上限 [RECENT_LIMIT]。
     * 纯函数，输入输出都是不可变列表，测试与调用方共用同一口径。
     */
    fun mergeRecent(recent: List<String>, carNum: String): List<String> =
        (listOf(carNum) + recent.filter { it != carNum }).take(RECENT_LIMIT)

    /** 最近车号列表 → JSON（DataStore 存储）。 */
    fun encodeRecent(recent: List<String>): String = Json.encodeToString(recent.toList())

    /**
     * JSON → 最近车号列表。脏 JSON / 结构不符一律回空列表：
     * 历史是锦上添花的回填入口，坏了不该让页面读不到偏好。
     *
     * 纯 3 位条目是 2026-09-23 之前的存储格式（只存尾部），补上 [TEMPLATE] 前缀读出来，
     * 老用户的历史不会因为改格式而清空。
     */
    fun decodeRecent(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val list = Json.decodeFromString<List<String>>(json)
            // 存储层防线：越界/脏条目就地清理，不指望写入方永远规矩
            list.map(::upgradeLegacyRecent)
                .filter { bikeUrl(it) != null }
                .distinct()
                .take(RECENT_LIMIT)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 旧格式（**恰好** [TAIL_LENGTH] 位数字的尾部）→ 完整车号；其余原样返回交给校验过滤。
     *
     * 卡在"恰好三位"是准的：旧版生成入口只接受 3 位尾部，所以历史里出现 1~2 位数字
     * 只可能是偏好损坏，补上前缀反而会造出一个像模像样的假车号。
     */
    private fun upgradeLegacyRecent(raw: String): String =
        if (raw.length == TAIL_LENGTH && raw.all { it in '0'..'9' }) {
            TEMPLATE + raw
        } else {
            raw
        }
}
