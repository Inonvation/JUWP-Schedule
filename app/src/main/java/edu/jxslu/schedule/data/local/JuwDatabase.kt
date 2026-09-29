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
        YktTurnoverEntity::class,
        NoteEntity::class,
        HomeworkEntity::class,
        PowerReadingEntity::class,
        ScholarGroupEntity::class,
        ScholarCourseEntity::class,
        TextbookEntity::class,
        RideRecordEntity::class,
    ],
    version = 16,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class JuwDatabase : RoomDatabase() {
    abstract fun timetableDao(): TimetableDao
    abstract fun courseDao(): CourseDao
    abstract fun timeSlotDao(): TimeSlotDao
    abstract fun semesterConfigDao(): SemesterConfigDao
    abstract fun scoreDao(): ScoreDao
    abstract fun yktTurnoverDao(): YktTurnoverDao
    abstract fun noteDao(): NoteDao
    abstract fun homeworkDao(): HomeworkDao
    abstract fun powerReadingDao(): PowerReadingDao
    abstract fun scholarProgressDao(): ScholarProgressDao
    abstract fun textbookDao(): TextbookDao
    abstract fun rideRecordDao(): RideRecordDao

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

        /**
         * v9 → v10：调课自动检测功能移除（2026-09-24）。
         * DROP 两张专属表 `detect_baselines`/`detect_reports`（其余数据零触碰，非 destructive）；
         * `MIGRATION_4_5` 的建表语句保留——迁移链不可断，v4 用户先建表、到这里再删。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS detect_baselines")
                db.execSQL("DROP TABLE IF EXISTS detect_reports")
            }
        }

        /**
         * v10 → v11：电表读数本机记录（DESIGN §3.13「用电统计」，2026-09-24）。
         * 新表 `power_readings`，CREATE TABLE / CREATE INDEX 非 destructive；
         * 索引名与 [PowerReadingEntity] 的 `@Index` 声明逐字对齐
         * （`index_power_readings_epochMs_roomId` 是唯一索引，漏写 unique 会被迁移校验判不一致）。
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS power_readings (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "epochMs INTEGER NOT NULL, " +
                        "remainKwh REAL NOT NULL, " +
                        "priceYuan REAL NOT NULL, " +
                        "roomId TEXT NOT NULL, " +
                        "source TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_power_readings_epochMs_roomId " +
                        "ON power_readings(epochMs, roomId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_power_readings_roomId " +
                        "ON power_readings(roomId)",
                )
            }
        }

        /**
         * v11 → v12：读数补记房间显示名（2026-09-27）。
         *
         * 生活页冷启动要拿最新读数当首屏种子（电费卡不再从「读取中…」起步），
         * 而房号以前只能从 `roomId` 拿——那是平台的数字内部 id，显示出来是一串数字。
         * 走 ALTER TABLE 加空串默认值，历史读数照常参与统计（`roomName` 不参与分组）。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE power_readings ADD COLUMN roomName TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v12 → v13：学业完成情况（DESIGN §4.29）。
         *
         * 两张新表，CREATE TABLE / CREATE INDEX 非 destructive。全局归属学生、不挂
         * timetableId。可空列（学分、结论、是否学位课）在 SQLite 侧**不带 NOT NULL**，
         * 与实体的 `Double?` / `Boolean?` 一一对应——多写一个 NOT NULL 会被迁移校验判不一致。
         * 索引名必须与实体 `@Index("dimension")` 生成的 `index_<表>_<列>` 逐字对齐。
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS scholar_groups (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "dimension TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "sortOrder INTEGER NOT NULL, " +
                        "requiredCredit REAL, " +
                        "earnedCredit REAL, " +
                        "ongoingCredit REAL, " +
                        "remainingCredit REAL, " +
                        "passed INTEGER, " +
                        "percent TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_scholar_groups_dimension " +
                        "ON scholar_groups(dimension)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS scholar_courses (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "dimension TEXT NOT NULL, " +
                        "groupName TEXT NOT NULL, " +
                        "sortOrder INTEGER NOT NULL, " +
                        "term TEXT NOT NULL, " +
                        "courseNo TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "credit REAL NOT NULL, " +
                        "planned INTEGER, " +
                        "category TEXT NOT NULL, " +
                        "attribute TEXT NOT NULL, " +
                        "nature TEXT NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "scoreText TEXT NOT NULL, " +
                        "remark TEXT NOT NULL, " +
                        "degreeCourse INTEGER)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_scholar_courses_dimension " +
                        "ON scholar_courses(dimension)",
                )
            }
        }

        /**
         * v13 → v14：教材（DESIGN §4.31）+ 课表学期号。
         *
         * `timetables.term` 记这张课表的数据学期（教务导入时写入），是课程详情查教材的
         * 钥匙；ALTER TABLE 加可空列，历史行落 null（不显示教材，导入后自动补）。
         * `textbooks` 全局挂 courseName（同 notes/homework，不挂 timetableId），
         * 另按 term 过滤。索引名必须与实体 `@Index(value=["term","courseName"])`
         * 生成的 `index_textbooks_term_courseName` 逐字对齐。
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timetables ADD COLUMN term TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS textbooks (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "term TEXT NOT NULL, " +
                        "courseName TEXT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        // author/press/edition/isbn/price 在实体里带 Kotlin 默认值 ""——
                        // Room 会把构造默认值写进预期 schema 的 DEFAULT，这里必须逐字带
                        // DEFAULT ''，否则迁移校验崩溃（v7→v8 remark、v11→v12 roomName 同坑）
                        "author TEXT NOT NULL DEFAULT '', " +
                        "press TEXT NOT NULL DEFAULT '', " +
                        "edition TEXT NOT NULL DEFAULT '', " +
                        "isbn TEXT NOT NULL DEFAULT '', " +
                        "price TEXT NOT NULL DEFAULT '')",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_textbooks_term_courseName " +
                        "ON textbooks(term, courseName)",
                )
            }
        }

        /**
         * v14 → v15（2026-09-28）：消费流水补两列交易账户（卡号 + 账户类型），
         * 让「充值充到哪个账户」能显示在账单里。
         *
         * 两列都可空、实体里没有 Kotlin 默认值 → 预期 schema 也没有 DEFAULT，
         * `ADD COLUMN x TEXT` 即可（**别顺手写 NOT NULL/DEFAULT**：多写一个字就是
         * 「迁移建的表结构与实体期望不一致 → 校验崩溃」，v7→v8 / v11→v12 的老坑）。
         * 历史行留 NULL：展示侧「认不出就不显示」，不编一个账户出来。
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ykt_turnovers ADD COLUMN fromAccount TEXT")
                db.execSQL("ALTER TABLE ykt_turnovers ADD COLUMN accType TEXT")
            }
        }

        /**
         * v15 → v16（2026-09-28）：新表 `ride_records`（本机骑行记录，DESIGN §3.9）。
         *
         * CREATE TABLE / CREATE INDEX 非 destructive；实体里没有 Kotlin 默认值的列
         * （`feeCents` 与 `settled` 都是**构造参数**，实体没写 `= null` / `= false`），
         * 所以建表语句里**不要**自作主张加 NOT NULL/DEFAULT——
         * 多写一个字就是「迁移建的表结构与实体期望不一致 → 校验崩溃」（v7→v8 的老坑）。
         * 索引与实体的 `@Index(value = ["endAt"])` 一一对应，漏一条同样校验崩溃。
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ride_records (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "carNum TEXT NOT NULL, " +
                        "startAt INTEGER NOT NULL, " +
                        "endAt INTEGER NOT NULL, " +
                        "durationSeconds INTEGER NOT NULL, " +
                        "feeCents INTEGER, " +
                        "settled INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ride_records_endAt ON ride_records (endAt)",
                )
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
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
