package edu.jxslu.schedule.domain

/**
 * 正文里的图片引用 `![](img:文件名)`——收集 / 构造 / 移除的**唯一口径**（DESIGN §4.20）。
 *
 * 图片本体是应用私有目录里的一份文件（`data/repo/AttachmentStore`），正文只存引用：
 * 不建附件表，文件与正文一起生灭；「哪些文件还有人引用」由这里说了算
 * （编辑器删除附件、保存后清孤儿、冷启动 sweep 都走它，不许各写一遍正则）。
 */

/** 匹配 `![任意 alt](img:文件名)`，允许括号内含空白。 */
private val IMAGE_REF = Regex("""!\[[^\]]*]\(\s*img:([^)\s]+)\s*\)""")

/** 正文引用的全部附件文件名（去重）。 */
fun imageRefs(text: String): Set<String> =
    IMAGE_REF.findAll(text).map { it.groupValues[1] }.toSet()

/** 正文里是否含图片引用（列表项的图片角标）。 */
fun hasImageRef(text: String): Boolean = IMAGE_REF.containsMatchIn(text)

/** 构造一条图片引用（编辑器在光标处插入用）。 */
fun imageRefToken(fileName: String): String = "![](img:$fileName)"

/**
 * 移除某个附件在正文里的全部引用（删除附件时配套调用）。
 * 顺手把多余空行收拢（图片通常独占一行，删完会留一片空行），不碰行尾空格——
 * Markdown 的两个行尾空格是硬换行，不能清。
 */
fun removeImageRef(text: String, fileName: String): String {
    val target = Regex("""!\[[^\]]*]\(\s*img:${Regex.escape(fileName)}\s*\)""")
    return text.replace(target, "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim('\n')
}
