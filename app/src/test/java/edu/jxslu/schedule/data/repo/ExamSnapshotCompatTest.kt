package edu.jxslu.schedule.data.repo

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ExamSnapshotCompatTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 旧快照里的 Row 与 ExamEntry 字段同名同型，直接解码不能丢字段。 */
    @Test
    fun oldRowJsonDecodesIntoExamEntry() {
        val old = """
            {"term":"2026-2027-1","exams":[
              {"courseNo":"A","name":"高等数学","teacher":"张三","room":"南C303",
               "campus":"瑶湖校区","date":"2026-05-18","startTime":"08:30",
               "endTime":"09:55","seatNo":"1","sessionNo":"S"}
            ]}
        """.trimIndent()
        val snapshot = json.decodeFromString<ExamSnapshotStore.Snapshot>(old)
        val entry = snapshot.exams.single()
        assertEquals("高等数学", entry.name)
        assertEquals("2026-05-18", entry.date)
        assertEquals("08:30", entry.startTime)
    }

    @Test
    fun snapshotRoundTrips() {
        val snapshot = ExamSnapshotStore.Snapshot(
            term = "2026-2027-1",
            exams = listOf(
                edu.jxslu.schedule.domain.ExamMapper.ExamEntry(
                    name = "高等数学",
                    date = "2026-05-18",
                    startTime = "08:30",
                    endTime = "09:55",
                ),
            ),
        )
        val text = json.encodeToString(snapshot)
        assertEquals(snapshot, json.decodeFromString<ExamSnapshotStore.Snapshot>(text))
    }
}
