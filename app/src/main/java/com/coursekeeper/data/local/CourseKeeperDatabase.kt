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
    WriteRequestRecord::class, DataRevision::class], version = 2, exportSchema = true)
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

        fun open(context: Context, name: String = "coursekeeper.db"): CourseKeeperDatabase =
            Room.databaseBuilder(context.applicationContext, CourseKeeperDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2).build()
    }
}
