package edu.jxslu.schedule.data

import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.domain.TimeSlot

object DefaultData {

    /**
     * 作息表：每小节 40 分钟，大节内两小节之间休息 5 分钟，大节之间 20 分钟换教室。
     *
     * 根因：旧版本这里只放了 5 条「大节」（08:00/10:00/14:00/16:00/19:00），
     * 而 `Course.startSection/endSection` 存的是教务返回的**小节号** 1–11。
     * 两者语义不一致，`WeekPage` 又直接把小节号当行号用，导致 7-8 节的课被画到第 7 行、9-10 节画到第 9 行，
     * 也就是课块溢出网格。现在作息细化到小节，行号 = 小节号，不再需要折算。
     *
     * 校验依据：用「40 分钟 + 5 分钟」逐节递推，5 个大节的结束时间
     * （09:55 / 11:40 / 15:25 / 17:10 / 21:10）与教务页行标签给的时间逐一吻合（见 DESIGN 3.5）。
     */
    val defaultTimeSlots: List<TimeSlot> = listOf(
        TimeSlot(1, "08:30", "09:10"),
        TimeSlot(2, "09:15", "09:55"),
        TimeSlot(3, "10:15", "10:55"),
        TimeSlot(4, "11:00", "11:40"),
        TimeSlot(5, "14:00", "14:40"),
        TimeSlot(6, "14:45", "15:25"),
        TimeSlot(7, "15:45", "16:25"),
        TimeSlot(8, "16:30", "17:10"),
        TimeSlot(9, "19:00", "19:40"),
        TimeSlot(10, "19:45", "20:25"),
        TimeSlot(11, "20:30", "21:10"),
    )

    /**
     * 作息表结构版本。
     * 0 = 旧的 5 条大节；1 = 11 条小节。
     * 升级时一次性覆盖节次表，避免老安装继续用错作息（v1 之后不再自动覆盖用户自定义作息）。
     */
    const val SLOT_SCHEMA_VERSION = 1

    /**
     * 2026-2027-1 默认开学日：2026-09-07（周一）。
     * 可在设置中修改。
     */
    val defaultSemester: SemesterConfig = SemesterConfig(
        startDate = "2026-09-07",
        totalWeeks = 20,
        firstDayOfWeek = 1,
    )
}
