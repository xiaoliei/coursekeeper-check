package com.coursekeeper.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.coursekeeper.data.model.*

@Database(entities = [SemesterRecord::class, PeriodRecord::class, CourseRecord::class,
    ScheduleRecord::class, TaskRecord::class, SyncRecord::class, TombstoneRecord::class,
    WriteRequestRecord::class, DataRevision::class], version = 3, exportSchema = true)
@TypeConverters(RecordConverters::class)
abstract class CourseKeeperDatabase : RoomDatabase() {
    internal abstract fun records(): CourseKeeperDao

    companion object {
        // E v1 is the base schema; v2 adds A00 receipts and deletion protection.
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `tombstones` (`module` TEXT NOT NULL, `source` TEXT NOT NULL, `externalId` TEXT NOT NULL, PRIMARY KEY(`module`, `source`, `externalId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `write_requests` (`requestId` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `recordId` TEXT NOT NULL, `version` INTEGER NOT NULL, `changed` INTEGER NOT NULL, PRIMARY KEY(`requestId`))")
            }
        }

        /** Preserve IDs/versions and expand the exact old week set (including odd/even weeks). */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE `_schedules_v3` (`id` TEXT NOT NULL, `courseId` TEXT NOT NULL, `dayOfWeek` INTEGER NOT NULL, `startPeriod` INTEGER NOT NULL, `endPeriod` INTEGER NOT NULL, `weeks` INTEGER NOT NULL, `weeksText` TEXT NOT NULL, `location` TEXT, `teacher` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.query("SELECT id, courseId, dayOfWeek, startPeriod, endPeriod, startWeek, endWeek, weekPattern, location FROM schedules").use { rows ->
                    while (rows.moveToNext()) {
                        val weeks = ScheduleWeeks.fromRange(rows.getInt(5), rows.getInt(6), WeekPattern.valueOf(rows.getString(7)))
                        // Null means "use the course's teacher"; never invent a per-entry override.
                        db.execSQL("INSERT INTO `_schedules_v3` (id, courseId, dayOfWeek, startPeriod, endPeriod, weeks, weeksText, location, teacher) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                            arrayOf<Any?>(rows.getString(0), rows.getString(1), rows.getInt(2), rows.getInt(3), rows.getInt(4),
                                weeks, ScheduleWeeks.format(weeks), if (rows.isNull(8)) null else rows.getString(8)))
                    }
                }
                db.execSQL("DROP TABLE schedules")
                db.execSQL("ALTER TABLE `_schedules_v3` RENAME TO schedules")
                db.execSQL("CREATE INDEX `index_schedules_courseId` ON `schedules` (`courseId`)")
            }
        }

        fun open(context: Context, name: String = "coursekeeper.db"): CourseKeeperDatabase =
            Room.databaseBuilder(context.applicationContext, CourseKeeperDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
