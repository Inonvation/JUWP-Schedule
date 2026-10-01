package edu.jxslu.schedule.domain

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 一条预选清单（DESIGN §3.20，2026-10-01）：用户提前录入的「想选的课」，
 * 选课开放后由抢课引擎按它匹配教务课程列表（DESIGN §4.36）。
 *
 * 匹配口径（[SelectionWishes.matches]）：
 * - [nameKeyword] 必填，课程名**包含**它（去首尾空白、忽略大小写）；
 * - [teacherKeyword] 可空，填了就要求教师串同样包含（如「曾」可匹配「曾刚,李四」）。
 *
 * [priority] 数值越大越先抢（高=2 / 中=1 / 低=0）；同档保持录入顺序（[sorted] 稳定排序）。
 * 只在本机保存，不上传——抢课指令只在用户显式开始后发出。
 */
@Serializable
data class SelectionWish(
    /** 本地 id；空 = 未保存的新条目（保存时由 [SelectionWishes.newId] 生成）。 */
    val id: String = "",
    val nameKeyword: String = "",
    val teacherKeyword: String = "",
    /** 优先级：高/中/低（见 [SelectionWishes.PRIORITY_HIGH] 等常量）。 */
    val priority: Int = SelectionWishes.PRIORITY_MEDIUM,
    /** 备注（如「周一上午没空」），仅展示。 */
    val note: String = "",
)

object SelectionWishes {

    const val PRIORITY_LOW = 0
    const val PRIORITY_MEDIUM = 1
    const val PRIORITY_HIGH = 2

    /** 优先级 → 展示名。未知档位回「中」（脏数据防线，不崩）。 */
    fun priorityLabel(priority: Int): String = when (priority) {
        PRIORITY_HIGH -> "高"
        PRIORITY_LOW -> "低"
        else -> "中"
    }

    /** 合法的优先级档位（对话框循环切换用）。 */
    val PRIORITIES: List<Int> = listOf(PRIORITY_LOW, PRIORITY_MEDIUM, PRIORITY_HIGH)

    fun newId(): String = UUID.randomUUID().toString()

    private val format = Json { ignoreUnknownKeys = true }

    fun decode(text: String): List<SelectionWish> = try {
        format.decodeFromString(ListSerializer(SelectionWish.serializer()), text)
    } catch (_: Exception) {
        emptyList()
    }

    fun encode(wishes: List<SelectionWish>): String =
        format.encodeToString(ListSerializer(SelectionWish.serializer()), wishes)

    /** 校验不通过返回用户可读文案；null = 可保存。 */
    fun validate(wish: SelectionWish): String? {
        if (wish.nameKeyword.isBlank()) return "请填写课程名关键词"
        return null
    }

    /** 优先级降序（高在前）；同档保持原有相对顺序（插入顺序 = 录入顺序）。 */
    fun sorted(wishes: List<SelectionWish>): List<SelectionWish> =
        wishes.sortedByDescending { it.priority }

    /**
     * 命中判定：课程名包含关键词，且（填了教师关键词时）教师串也包含它。
     * 大小写不敏感、两侧空白忽略；空白课程名一律不命中。
     */
    fun matches(wish: SelectionWish, courseName: String, teacher: String): Boolean {
        val name = courseName.trim()
        if (name.isEmpty()) return false
        val keyword = wish.nameKeyword.trim()
        if (keyword.isEmpty()) return false
        if (!name.contains(keyword, ignoreCase = true)) return false
        val teacherKeyword = wish.teacherKeyword.trim()
        if (teacherKeyword.isEmpty()) return true
        return teacher.trim().contains(teacherKeyword, ignoreCase = true)
    }
}
