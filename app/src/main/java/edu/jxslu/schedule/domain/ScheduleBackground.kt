package edu.jxslu.schedule.domain

import kotlin.math.roundToInt

/**
 * 课表页背景图的纯逻辑（DESIGN §4.21）：参数夹取、模糊档位换算、文件名合法性。
 *
 * 抽成 object 是为了可 JVM 测：渲染层与文件层都只调用这里，不各自写一份口径。
 */
object ScheduleBackground {

    /** 落盘压缩的长边上限（px）。与笔记附件同档，铺满 1080p 屏足够。 */
    const val MAX_SIDE = 1920

    /** 落盘压缩的原始字节上限；超过就重编码。 */
    const val MAX_BYTES = 1_500_000L

    /** 落盘压缩的 JPEG 质量。 */
    const val JPEG_QUALITY = 88

    /**
     * 模糊档位对应的解码长边（px），索引 0 最清晰。
     *
     * 观感靠「解码得小、绘制时双线性放大」得到：纹理越粗，放大后越糊。
     * 不用 `Modifier.blur`——它的 RenderEffect 路径要 API 31，minSdk 26 下会分叉成两种观感。
     */
    val BLUR_LONG_SIDES = listOf(1920, 1280, 1024, 640, 320)

    /** 模糊强度（0–1）→ 解码长边。越界值夹取，不抛异常。 */
    fun decodeLongSide(blur: Float): Int {
        val index = (blur.coerceIn(0f, 1f) * BLUR_LONG_SIDES.lastIndex)
            .roundToInt()
            .coerceIn(0, BLUR_LONG_SIDES.lastIndex)
        return BLUR_LONG_SIDES[index]
    }

    /**
     * 实际解码目标长边：模糊档位与屏幕尺寸取小。
     * 背景不需要比屏幕更清晰，多余的分辨率只占内存。
     */
    fun targetLongSide(screenLongSidePx: Int, blur: Float): Int =
        minOf(decodeLongSide(blur), screenLongSidePx.coerceAtLeast(64))

    /**
     * 文件名合法性。只放行字符白名单，挡掉 `../` 这类越出目录的取值。
     *
     * 名字来自偏好 JSON（自己写的数据），正常不会坏；坏掉时读路径必须能安全降级，
     * 而不是拼出一个 `filesDir` 之外的路径去读。
     */
    fun isValidFileName(name: String): Boolean {
        if (name.isBlank() || name.length > 64) return false
        if (name == "." || name == "..") return false
        return name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
    }
}
