package com.coursekeeper.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Ignore
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class RecordSource { MANUAL, SYNC, AGENT }
enum class TaskType { HOMEWORK, LAB_REPORT, CLUB, WORK, OTHER }
enum class WeekPattern { ALL, ODD, EVEN }
enum class SyncModule { COURSES, TASKS }
enum class SyncStatus { IDLE, SYNCING, OFFLINE, EXPIRED, PARTIAL, ERROR }

@Entity(tableName = "semesters")
data class SemesterRecord(
    @PrimaryKey val id: String,
    val name: String,
    val startsOn: LocalDate?,
    val weekCount: Int,
    val version: Long = 1,
)

@Entity(tableName = "periods", primaryKeys = ["semesterId", "number"], foreignKeys = [
    ForeignKey(entity = SemesterRecord::class, parentColumns = ["id"],
        childColumns = ["semesterId"], onDelete = ForeignKey.CASCADE),
])
data class PeriodRecord(val semesterId: String, val number: Int, val startsAt: LocalTime, val endsAt: LocalTime)

@Entity(tableName = "courses", indices = [Index(value = ["source", "externalId"], unique = true)])
data class CourseRecord(
    @PrimaryKey val id: String,
    val title: String,
    val semesterId: String? = null,
    val teacher: String? = null,
    val source: RecordSource = RecordSource.MANUAL,
    val externalId: String? = null,
    val version: Long = 1,
    val manualDirty: Boolean = false,
)

@Entity(tableName = "schedules", indices = [Index("courseId")], foreignKeys = [
    ForeignKey(entity = CourseRecord::class, parentColumns = ["id"],
        childColumns = ["courseId"], onDelete = ForeignKey.CASCADE),
])
data class ScheduleRecord(
    @PrimaryKey val id: String,
    val courseId: String,
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeks: Long,
    val weeksText: String = ScheduleWeeks.format(weeks),
    val location: String? = null,
    val teacher: String? = null,
) {
    /** Source compatibility for existing A/F fixtures; all persisted fields use v3. */
    @Ignore
    constructor(id: String, courseId: String, dayOfWeek: Int, startPeriod: Int, endPeriod: Int,
        startWeek: Int, endWeek: Int, weekPattern: WeekPattern = WeekPattern.ALL, location: String? = null,
    ) : this(id, courseId, dayOfWeek, startPeriod, endPeriod,
        weeks = ScheduleWeeks.fromRange(startWeek, endWeek, weekPattern), location = location)
}

// No course FK: deleting a course must retain its tasks and courseId (D v1.0).
@Entity(tableName = "tasks", indices = [
    Index(value = ["source", "externalId"], unique = true), Index("courseId"), Index("dueAt"),
])
data class TaskRecord(
    @PrimaryKey val id: String,
    val title: String,
    val type: TaskType,
    val dueAt: LocalDateTime? = null,
    val courseId: String? = null,
    val completedAt: LocalDateTime? = null,
    val source: RecordSource = RecordSource.MANUAL,
    val externalId: String? = null,
    val version: Long = 1,
    val manualDirty: Boolean = false,
)

@Entity(tableName = "sync_records")
data class SyncRecord(
    @PrimaryKey val module: SyncModule,
    val schoolId: String? = null,
    val status: SyncStatus = SyncStatus.IDLE,
    val lastAttemptAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val errorCode: String? = null,
    val inserted: Int = 0,
    val updated: Int = 0,
    val protectedCount: Int = 0,
    val missingIdentity: Int = 0,
)

@Entity(tableName = "tombstones", primaryKeys = ["module", "source", "externalId"])
data class TombstoneRecord(val module: SyncModule, val source: RecordSource, val externalId: String)

@Entity(tableName = "write_requests")
data class WriteRequestRecord(
    @PrimaryKey val requestId: String,
    val fingerprint: String,
    val recordId: String,
    val version: Long,
    val changed: Boolean,
)

@Entity(tableName = "data_revision")
data class DataRevision(@PrimaryKey val id: Int = 1, val revision: Long = 0)

data class DataSnapshot(
    val semesters: List<SemesterRecord> = emptyList(),
    val periods: List<PeriodRecord> = emptyList(),
    val courses: List<CourseRecord> = emptyList(),
    val schedules: List<ScheduleRecord> = emptyList(),
    val tasks: List<TaskRecord> = emptyList(),
    val syncRecords: List<SyncRecord> = emptyList(),
)
