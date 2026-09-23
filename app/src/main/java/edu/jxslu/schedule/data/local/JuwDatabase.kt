package edu.jxslu.schedule.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import edu.jxslu.schedule.domain.TimetablePrefs

@Database(
    entities = [
        TimetableEntity::class,
        CourseEntity::class,
        TimeSlotEntity::class,
        SemesterConfigEntity::class,
        ScoreEntity::class,
        DetectBaselineEntity::class,
        DetectReportEntity::class,
        YktTurnoverEntity::class,
        NoteEntity::class,
        HomeworkEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class JuwDatabase : RoomDatabase() {
    abstract fun timetableDao(): TimetableDao
    abstract fun courseDao(): CourseDao
    abstract fun timeSlotDao(): TimeSlotDao
    abstract fun semesterConfigDao(): SemesterConfigDao
    abstract fun scoreDao(): ScoreDao
    abstract fun detectBaselineDao(): DetectBaselineDao
    abstract fun detectReportDao(): DetectReportDao
    abstract fun yktTurnoverDao(): YktTurnoverDao
    abstract fun noteDao(): NoteDao
    abstract fun homeworkDao(): HomeworkDao

    companion object {

        /**
         * v1 → v2：courses 表加 `kind` 列（区分理论课 / 实验课）。
         *
         * 用 ALTER TABLE 而不是重建表：老用户库里已经有课表，
         * 走 destructive migration 会直接清空——这是不可接受的数据损失。
         * DEFAULT 'theory' 让历史数据自动落成理论课，语义正确且无需回填。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN kind TEXT NOT NULL DEFAULT 'theory'")
            }
        }

        /**
         * v2 → v3：多课表（DESIGN §4.9）。
         *
         * - 新表 `timetables`：课表身份 + 课表级设置（显示偏好 JSON + 作息自定义标记）。
         *   迁移插入 id=1「我的课表」承接全部旧数据；显示偏好的实际值搬迁是异步的
         *   （Room migration 是同步的读不了 DataStore），放在 ScheduleRepository.ensureDefaults
         *   里按 `timetable_prefs_migrated` 标记一次性完成。
         * - `courses` 加 timetableId（DEFAULT 1 = 全部旧课程归入默认课表）+ 索引。
         * - `time_slots` / `semester_config` 主键要从「全局单份」变成「每课表一份」，
         *   SQLite 不能改主键，只能建新表 → 搬数据 → 改名。**禁用 destructive**：
         *   用户设备上有真实课表数据，这一步搬错就是数据丢失。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS timetables (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "sortOrder INTEGER NOT NULL, " +
                        "slotsCustomized INTEGER NOT NULL, " +
                        "prefsJson TEXT NOT NULL)",
                )
                // 旧数据的显示偏好先落内置默认；ensureDefaults 的一次性迁移会用
                // DataStore 里的旧全局值覆盖这一行（幂等标记防重放）
                db.execSQL(
                    "INSERT INTO timetables (id, name, createdAt, sortOrder, slotsCustomized, prefsJson) " +
                        "VALUES (1, '我的课表', ?, 0, 0, ?)",
                    arrayOf(System.currentTimeMillis(), TimetablePrefs().encode()),
                )

                db.execSQL(
                    "ALTER TABLE courses ADD COLUMN timetableId INTEGER NOT NULL DEFAULT 1",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_courses_timetableId ON courses (timetableId)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS time_slots_new (" +
                        "timetableId INTEGER NOT NULL, " +
                        "number INTEGER NOT NULL, " +
                        "startTime TEXT NOT NULL, " +
                        "endTime TEXT NOT NULL, " +
                        "PRIMARY KEY(timetableId, number))",
                )
                db.execSQL(
                    "INSERT INTO time_slots_new (timetableId, number, startTime, endTime) " +
                        "SELECT 1, number, startTime, endTime FROM time_slots",
                )
                db.execSQL("DROP TABLE time_slots")
                db.execSQL("ALTER TABLE time_slots_new RENAME TO time_slots")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS semester_config_new (" +
                        "timetableId INTEGER NOT NULL, " +
                        "startDate TEXT NOT NULL, " +
                        "totalWeeks INTEGER NOT NULL, " +
                        "firstDayOfWeek INTEGER NOT NULL, " +
                        "PRIMARY KEY(timetableId))",
                )
                db.execSQL(
                    "INSERT INTO semester_config_new (timetableId, startDate, totalWeeks, firstDayOfWeek) " +
                        "SELECT 1, startDate, totalWeeks, firstDayOfWeek FROM semester_config",
                )
                db.execSQL("DROP TABLE semester_config")
                db.execSQL("ALTER TABLE semester_config_new RENAME TO semester_config")
            }
        }

        /**
         * v3 → v4：成绩按学期存储（DESIGN §4.15）。
         * 新表 `scores`，全局归属学生（不挂 timetableId）；CREATE TABLE 非 destructive。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS scores (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "term TEXT NOT NULL, " +
                        "courseNo TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "unit TEXT NOT NULL, " +
                        "credit REAL NOT NULL, " +
                        "hours REAL NOT NULL, " +
                        "examForm TEXT NOT NULL, " +
                        "courseAttr TEXT NOT NULL, " +
                        "category TEXT NOT NULL, " +
                        "score REAL, " +
                        "scoreStr TEXT NOT NULL, " +
                        "gradePoint REAL, " +
                        "status TEXT NOT NULL, " +
                        "pendingReview INTEGER NOT NULL, " +
                        "importedAt INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scores_term ON scores(term)")
            }
        }

        /**
         * v4 → v5：调课自动检测（DESIGN §4.17）。
         * 新表 `detect_baselines`（教务基线快照）与 `detect_reports`（最新差异报告），
         * 都按 timetableId 主键、每课表一份；CREATE TABLE 非 destructive。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS detect_baselines (" +
                        "timetableId INTEGER NOT NULL, " +
                        "term TEXT NOT NULL, " +
                        "payload TEXT NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(timetableId))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS detect_reports (" +
                        "timetableId INTEGER NOT NULL, " +
                        "payload TEXT NOT NULL, " +
                        "unread INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(timetableId))",
                )
            }
        }

        /**
         * v5 → v6：校园卡消费流水本地副本（DESIGN §4.19 L1–L5）。
         * 新表 `ykt_turnovers`，orderId 主键（服务端订单号，同步去重键）；
         * CREATE TABLE 非 destructive。个人消费记录非凭证，随云备份。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ykt_turnovers (" +
                        "orderId TEXT NOT NULL PRIMARY KEY, " +
                        "jndatetime INTEGER NOT NULL, " +
                        "jndatetimeStr TEXT NOT NULL, " +
                        "tranamtFen INTEGER NOT NULL, " +
                        "income INTEGER NOT NULL, " +
                        "turnoverType TEXT NOT NULL, " +
                        "remark TEXT, " +
                        "resume TEXT, " +
                        "balanceAfterFen INTEGER, " +
                        "locationName TEXT, " +
                        "syncedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ykt_turnovers_jndatetime ON ykt_turnovers(jndatetime)",
                )
            }
        }

        /**
         * v6 → v7：课程笔记·课件与作业（DESIGN §4.20）。
         * 新表 `notes` / `homework`，都**按课程名归属、不带 timetableId**（理由见
         * NoteEntity 的 KDoc 与 DESIGN §4.20「归属」）；CREATE TABLE / CREATE INDEX 非 destructive。
         * 索引名必须与实体 @Index 生成的（`index_<表>_<列>`）逐字对齐，否则迁移校验崩溃。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS notes (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "courseName TEXT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "body TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_courseName ON notes(courseName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_updatedAt ON notes(updatedAt)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS homework (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "courseName TEXT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "detail TEXT NOT NULL, " +
                        "dueDate TEXT, " +
                        "done INTEGER NOT NULL, " +
                        "doneAt INTEGER, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_homework_courseName ON homework(courseName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_homework_done ON homework(done)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_homework_dueDate ON homework(dueDate)")
            }
        }

        /**
         * v7 → v8：课程备注（DESIGN §4.3，2026-09-21）。
         * `courses` 加 `remark` 列——DEFAULT '' 让历史课程自动落成空备注，语义正确且无需回填；
         * 走 ALTER TABLE 而不是重建表（表里有用户的课表数据）。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN remark TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v8 → v9：作业去标题（2026-09-23）。
         *
         * `homework` 表删 `title` 列——SQLite 不能 DROP COLUMN，走「建新表 → 搬数据 → 删旧表 →
         * 改名 → 重建索引」的既有重建纪律（同 v2→v3 的 time_slots）。
         * 旧 title 丢弃：列表行与提醒文案改用正文第一行摘要（`homeworkDisplayTitle`）。
         * 索引名与 `HomeworkEntity` 的 `@Index` 声明逐字对齐（漏声明 = 迁移校验崩溃）。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS homework_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "courseName TEXT NOT NULL, " +
                        "detail TEXT NOT NULL, " +
                        "dueDate TEXT, " +
                        "done INTEGER NOT NULL, " +
                        "doneAt INTEGER, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO homework_new (id, courseName, detail, dueDate, done, doneAt, createdAt, updatedAt) " +
                        "SELECT id, courseName, detail, dueDate, done, doneAt, createdAt, updatedAt FROM homework",
                )
                db.execSQL("DROP TABLE homework")
                db.execSQL("ALTER TABLE homework_new RENAME TO homework")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_homework_courseName ON homework(courseName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_homework_done ON homework(done)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_homework_dueDate ON homework(dueDate)")
            }
        }

        @Volatile
        private var instance: JuwDatabase? = null

        fun get(context: Context): JuwDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    JuwDatabase::class.java,
                    "juw_schedule.db",
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
