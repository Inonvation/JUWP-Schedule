package edu.jxslu.schedule.domain

/**
 * 「我的」页账号条的学号遮罩（DESIGN §3.3，2026-09-23）。
 *
 * 规则：保留前 2 位 + `****` + 后 2 位；短于 6 位（或空白）返回 null，
 * 调用方拿到 null 就不显示账号条——遮不住的短串宁可整条隐藏，也不给出半个明文。
 */
object AccountMask {

    /** 遮罩学号。null = 输入不足以安全遮罩（空白 / 长度 < 6），账号条整体隐藏。 */
    fun maskStudentId(raw: String?): String? {
        val id = raw?.trim().orEmpty()
        if (id.length < MIN_MASKED_LENGTH) return null
        return id.take(2) + MASK + id.takeLast(2)
    }

    const val MASK = "****"

    /** 前 2 + 遮罩 + 后 2 = 6 位起才有遮罩意义。 */
    const val MIN_MASKED_LENGTH = 6
}
