package edu.jxslu.schedule.data.repo

import android.content.Context
import edu.jxslu.schedule.domain.InternshipBase
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 实习基地快照（DESIGN §4.43）：上次成功抓取的清单落盘（`filesDir` 一份 JSON），
 * 让「短时间内几乎不变」的参考数据不必每次进页都打教务——12 小时新鲜期内直接用快照
 * （[edu.jxslu.schedule.domain.InternshipCacheRules]），顶栏手动刷新才强刷。
 *
 * 读写都必须在 IO 线程（调用方负责调度）；两端 runCatching——快照坏了等于没有快照，
 * 下次按首次抓取重建。**不进备份**（本机缓存，重取即有）。只存基地字段，不存任何凭证。
 */
class InternshipSnapshotStore(context: Context) {

    private val file = File(context.filesDir, SNAPSHOT_FILE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Snapshot(
        /** 抓取成功时刻（毫秒）；0 = 未记。 */
        val fetchedAt: Long = 0L,
        /** 教务声明的总数（-1 = 未声明）。 */
        val declaredCount: Int = -1,
        val bases: List<Row> = emptyList(),
    )

    /** [InternshipBase] 的可序列化影子（本体不是 @Serializable，字段一一对应）。 */
    @Serializable
    data class Row(
        val id: String = "",
        val no: String = "",
        val name: String = "",
        val category: String = "",
        val unit: String = "",
        val leader: String = "",
        val address: String = "",
        val phone: String = "",
        val builtDate: String = "",
        val listedYears: String = "",
        val capacity: String = "",
        val campusContact: String = "",
        val campusPhone: String = "",
        val college: String = "",
        val intro: String = "",
    )

    fun load(): Snapshot = runCatching {
        json.decodeFromString<Snapshot>(file.readText())
    }.getOrDefault(Snapshot())

    /**
     * 原样落盘（写失败吞错）。**空结果也写**：「count≤0 的空」是教务的权威状态，
     * 存下来页面才会如实显示（先例：SelectionRoundsStore 的「轮次快照允许存空」）；
     * 「宁旧勿空」的取舍在装载器（异常空不写、更新失败回退旧快照），不在这一层。
     */
    fun save(snapshot: Snapshot) {
        runCatching {
            file.writeText(json.encodeToString(snapshot))
        }
    }

    companion object {
        private const val SNAPSHOT_FILE = "internship_bases.json"

        fun rowOf(base: InternshipBase) = Row(
            id = base.id,
            no = base.no,
            name = base.name,
            category = base.category,
            unit = base.unit,
            leader = base.leader,
            address = base.address,
            phone = base.phone,
            builtDate = base.builtDate,
            listedYears = base.listedYears,
            capacity = base.capacity,
            campusContact = base.campusContact,
            campusPhone = base.campusPhone,
            college = base.college,
            intro = base.intro,
        )

        fun rowToBase(row: Row) = InternshipBase(
            id = row.id,
            no = row.no,
            name = row.name,
            category = row.category,
            unit = row.unit,
            leader = row.leader,
            address = row.address,
            phone = row.phone,
            builtDate = row.builtDate,
            listedYears = row.listedYears,
            capacity = row.capacity,
            campusContact = row.campusContact,
            campusPhone = row.campusPhone,
            college = row.college,
            intro = row.intro,
        )
    }
}
