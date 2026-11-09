package com.coursekeeper.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.coursekeeper.data.model.*
import kotlinx.coroutines.flow.Flow

/** Internal API; Repository owns version checks and transactions. */
@Dao
internal interface CourseKeeperDao {
    @Query("SELECT * FROM semesters ORDER BY id") suspend fun semesters(): List<SemesterRecord>
    @Query("SELECT * FROM periods ORDER BY semesterId, number") suspend fun periods(): List<PeriodRecord>
    @Query("SELECT * FROM courses ORDER BY id") suspend fun courses(): List<CourseRecord>
    @Query("SELECT * FROM schedules ORDER BY id") suspend fun schedules(): List<ScheduleRecord>
    @Query("SELECT * FROM tasks ORDER BY dueAt IS NOT NULL, dueAt, id") suspend fun tasks(): List<TaskRecord>
    @Query("SELECT * FROM sync_records ORDER BY module") suspend fun syncRecords(): List<SyncRecord>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun task(id: String): TaskRecord?
    @Query("SELECT * FROM courses WHERE id = :id") suspend fun course(id: String): CourseRecord?
    @Query("SELECT * FROM semesters WHERE id = :id") suspend fun semester(id: String): SemesterRecord?
    @Query("SELECT * FROM tasks WHERE source = :source AND externalId = :externalId")
    suspend fun importedTask(source: RecordSource, externalId: String): TaskRecord?
    @Query("SELECT * FROM courses WHERE source = :source AND externalId = :externalId")
    suspend fun importedCourse(source: RecordSource, externalId: String): CourseRecord?
    @Query("SELECT EXISTS(SELECT 1 FROM tombstones WHERE module = :module AND source = :source AND externalId = :externalId)")
    suspend fun deleted(module: SyncModule, source: RecordSource, externalId: String): Boolean
    @Query("SELECT * FROM write_requests WHERE requestId = :id") suspend fun request(id: String): WriteRequestRecord?
    @Insert suspend fun insertTask(record: TaskRecord)
    @Update suspend fun updateTask(record: TaskRecord)
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun deleteTask(id: String)
    @Insert suspend fun insertCourse(record: CourseRecord)
    @Update suspend fun updateCourse(record: CourseRecord)
    @Query("DELETE FROM courses WHERE id = :id") suspend fun deleteCourse(id: String)
    @Insert suspend fun insertSchedules(records: List<ScheduleRecord>)
    @Query("DELETE FROM schedules WHERE courseId = :id") suspend fun deleteSchedules(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSemester(record: SemesterRecord)
    @Insert suspend fun insertPeriods(records: List<PeriodRecord>)
    @Query("DELETE FROM periods WHERE semesterId = :id") suspend fun deletePeriods(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSyncRecord(record: SyncRecord)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addTombstone(record: TombstoneRecord)
    @Insert suspend fun addRequest(record: WriteRequestRecord)
    @Query("INSERT OR IGNORE INTO data_revision (id, revision) VALUES (1, 0)") suspend fun ensureRevision()
    @Query("UPDATE data_revision SET revision = revision + 1 WHERE id = 1") suspend fun bumpRevision()
    @Query("SELECT revision FROM data_revision WHERE id = 1") fun observeRevision(): Flow<Long?>
}
