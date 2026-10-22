package edu.jxslu.schedule.data.repo

import org.junit.Assert.assertTrue
import org.junit.Test

class BackupValidationTest {

    private fun course(name: String = "高等数学") = CourseJson(
        name = name,
        day = 1,
        startSection = 1,
        endSection = 2,
        weeks = listOf(1, 2, 3),
    )

    private fun valid() = CourseExport(courses = listOf(course()))

    @Test
    fun validBackupPasses() {
        assertTrue(validateBackup(valid()) is BackupCheck.Ok)
    }

    @Test
    fun emptyCoursesRejected() {
        assertTrue(validateBackup(CourseExport()) is BackupCheck.Error)
    }

    @Test
    fun scoreMissingTermRejected() {
        val export = valid().copy(scores = listOf(ScoreBackupJson(name = "高等数学")))
        assertTrue(validateBackup(export) is BackupCheck.Error)
    }

    @Test
    fun textbookMissingTitleRejected() {
        val export = valid().copy(
            textbooks = listOf(TextbookBackupJson(term = "2026-2027-1", courseName = "高等数学")),
        )
        assertTrue(validateBackup(export) is BackupCheck.Error)
    }

    @Test
    fun invalidSemesterDateRejected() {
        val export = valid().copy(semester = SemesterBackupJson(startDate = "not-a-date"))
        assertTrue(validateBackup(export) is BackupCheck.Error)
    }

    @Test
    fun invalidSlotRejected() {
        val export = valid().copy(
            timeSlots = listOf(TimeSlotBackupJson(number = 1, startTime = "25:00", endTime = "26:00")),
        )
        assertTrue(validateBackup(export) is BackupCheck.Error)
    }
}
