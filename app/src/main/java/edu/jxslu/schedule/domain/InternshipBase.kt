package edu.jxslu.schedule.domain

/**
 * 实习基地（DESIGN §4.43）：教务「实习基地查看」的一条记录。
 *
 * 字段口径 = 教务 layui 表格的列（`/jsxsd/sxsxgl/sxjd.do` 的 JSON 键），全部按展示用
 * 字符串保留——挂牌时限这类字段教务给的就是 `5` 式原文，不换算、不猜测单位。
 * 除 [name] 外教务都可能给空，界面按空值跳过。
 */
data class InternshipBase(
    /** sj0604id：教务内部 id（32 位十六进制），仅作列表身份。 */
    val id: String,
    /** sxjdbh：基地编号。 */
    val no: String,
    /** sxjdmc：基地名称（唯一必有字段，缺失的行在解析层就丢掉）。 */
    val name: String,
    /** dmmc：基地类别（校内基地 / 校外基地 / 其他）。 */
    val category: String,
    /** sxdwmc：所属单位。 */
    val unit: String,
    /** sxjdfzr：基地负责人。 */
    val leader: String,
    /** sxjddz：基地地址。 */
    val address: String,
    /** sxjdlxdh：基地电话。 */
    val phone: String,
    /** jcsj：建成时间。 */
    val builtDate: String,
    /** gpsx：挂牌时限。 */
    val listedYears: String,
    /** rnrs：容纳人数。 */
    val capacity: String,
    /** xnlxr：校内联系人。 */
    val campusContact: String,
    /** xnlxrdh：校内电话。 */
    val campusPhone: String,
    /** dwmc：所属院系。 */
    val college: String,
    /** sxjdjj：基地简介。 */
    val intro: String,
)

/** 实习基地的纯规则（JVM 可测）。 */
object InternshipBaseRules {

    /** 分组顺序（与教务筛选器的类别口径一致）；未列出的类别按出现顺序跟尾。 */
    val CATEGORY_ORDER = listOf("校内基地", "校外基地", "其他")

    /**
     * 按类别分组：已知类别保 [CATEGORY_ORDER] 顺序、其余跟尾；
     * 组内按编号升序，编号缺失的排最后。
     */
    fun byCategory(list: List<InternshipBase>): List<Pair<String, List<InternshipBase>>> {
        val grouped = list.groupBy { it.category.ifBlank { "未标注" } }
        val ordered = CATEGORY_ORDER.filter { it in grouped } +
            grouped.keys.filter { it !in CATEGORY_ORDER }
        return ordered.map { key ->
            key to grouped.getValue(key).sortedWith(compareBy({ it.no.isBlank() }, { it.no }))
        }
    }
}

/** 实习基地快照的缓存规则（DESIGN §4.43）：数据是学年尺度的清单，低频抓取即可。 */
object InternshipCacheRules {

    /** 快照新鲜期：12 小时（与考试/选课的壳页学期缓存同档）——一天最多打一次教务。 */
    const val TTL_MS = 12 * 60 * 60 * 1000L

    /** [fetchedAt] 时刻的快照在 [now] 时是否还新鲜（未来时间戳视为不新鲜，按需重抓）。 */
    fun isFresh(fetchedAt: Long, now: Long): Boolean = now - fetchedAt in 0..TTL_MS

    /**
     * 本轮是否直接用快照：非强刷 + 抓过（[fetchedAt] > 0，**空快照也算「抓过」**，
     * 权威空要如实展示）+ 还在新鲜期内。抽成纯函数是为了把缓存判定矩阵钉进测试。
     */
    fun shouldUseCache(force: Boolean, fetchedAt: Long, now: Long): Boolean =
        !force && fetchedAt > 0 && isFresh(fetchedAt, now)
}
