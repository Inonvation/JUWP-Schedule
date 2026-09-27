package edu.jxslu.schedule.data.power

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 上次成功拉到的电费流水落盘（`filesDir` 下一份 JSON）。
 *
 * 平台流水在 [PowerRepository] 里只有 120 秒内存缓存，冷启动进程里什么都没有：生活页进页
 * 要串行跑 登录 → 项目详情 → 读表 → 流水 四条请求，手机网络上要好几秒，这段时间「最近
 * 流水」的电费段只能挂骨架（2026-09-26 用户报「一打开一直是骨架屏，手动一刷新反而秒出」
 * ——刷新快是因为那时 token 已经热了）。把上次成功结果落一份，进页先用它把电费段顶起来，
 * 刷新到货后原地替换；一卡通段本来就来自 Room，同口径。
 *
 * 读写都必须在 IO 线程（调用方负责调度）；两端都 runCatching——缓存坏了等于没有缓存，
 * 不影响主链路。**只存流水，不存 token**（token 落盘红线不变）。
 */
class PowerHistoryCache(context: Context) {

    private val file = File(context.filesDir, CACHE_FILE)
    private val json = Json { ignoreUnknownKeys = true }

    /** [PowerTurnover] 的可序列化影子（本体不是 @Serializable，字段一一对应）。 */
    @Serializable
    private data class Row(
        val turnoverId: Long?,
        val dateText: String,
        val epochMs: Long,
        val month: String?,
        val amountFen: Long,
        val room: String?,
        val refund: Boolean,
    )

    fun load(): List<PowerTurnover> = runCatching {
        json.decodeFromString<List<Row>>(file.readText()).map { row ->
            PowerTurnover(
                turnoverId = row.turnoverId,
                dateText = row.dateText,
                epochMs = row.epochMs,
                month = row.month,
                amountFen = row.amountFen,
                room = row.room,
                refund = row.refund,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 空结果不写：一次「取到但为空」（或半路失败落到这的空表）不该把上次有数的那份种子
     * 洗掉，种子的使命是「冷启动别挂骨架」，宁旧勿空。
     */
    fun save(turnovers: List<PowerTurnover>) {
        if (turnovers.isEmpty()) return
        runCatching {
            file.writeText(json.encodeToString(turnovers.map(::rowOf)))
        }
    }

    private fun rowOf(it: PowerTurnover) = Row(
        turnoverId = it.turnoverId,
        dateText = it.dateText,
        epochMs = it.epochMs,
        month = it.month,
        amountFen = it.amountFen,
        room = it.room,
        refund = it.refund,
    )

    private companion object {
        const val CACHE_FILE = "power_history_cache.json"
    }
}
