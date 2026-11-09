package com.coursekeeper.data

import androidx.room.withTransaction
import com.coursekeeper.data.local.CourseKeeperDatabase
import com.coursekeeper.data.model.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

/** All public mutations are atomic. Business services B/D own calendar rules and reminders. */
class CourseKeeperRepository(
    internal val database: CourseKeeperDatabase,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    internal val dao = database.records()

    // One transaction per emission prevents pages observing a half-written course/schedule aggregate.
    val snapshots = dao.observeRevision().map { snapshot() }.distinctUntilChanged()

    suspend fun snapshot(): DataSnapshot = database.withTransaction {
        DataSnapshot(dao.semesters(), dao.periods(), dao.courses(), dao.schedules(), dao.tasks(), dao.syncRecords())
    }

    suspend fun getTask(id: String): TaskRecord? = dao.task(id)
    suspend fun getCourse(id: String): CourseRecord? = dao.course(id)

    suspend fun createTask(draft: TaskDraft, source: RecordSource = RecordSource.MANUAL, requestId: String? = null): WriteReceipt =
        write(requestId, fingerprint("createTask", source, draft)) {
            checkData(source != RecordSource.SYNC, DataError.INVALID_RECORD)
            val clean = validateTask(draft)
            val record = TaskRecord(UUID.randomUUID().toString(), clean.title, clean.type, clean.dueAt, clean.courseId, source = source)
            dao.insertTask(record)
            WriteReceipt(record.id, record.version, true)
        }

    suspend fun updateTask(id: String, expectedVersion: Long, changes: TaskDraft, requestId: String? = null): WriteReceipt =
        write(requestId, fingerprint("updateTask", id, expectedVersion, changes)) {
            val old = taskAtVersion(id, expectedVersion)
            // Retain a dangling association after course deletion; new associations must exist.
            val clean = validateTask(changes, old.courseId)
            val updated = old.copy(title = clean.title, type = clean.type, dueAt = clean.dueAt,
                courseId = clean.courseId, version = nextVersion(old.version), manualDirty = true)
            dao.updateTask(updated)
            WriteReceipt(id, updated.version, true)
        }

    suspend fun setTaskCompleted(id: String, completed: Boolean, expectedVersion: Long, requestId: String? = null): WriteReceipt =
        write(requestId, fingerprint("completeTask", id, completed, expectedVersion)) {
            val old = taskAtVersion(id, expectedVersion)
            if ((old.completedAt != null) == completed) WriteReceipt(id, old.version, false)
            else {
                val updated = old.copy(completedAt = if (completed) LocalDateTime.now(clock) else null,
                    version = nextVersion(old.version), manualDirty = true)
                dao.updateTask(updated)
                WriteReceipt(id, updated.version, true)
            }
        }

    suspend fun deleteTask(id: String, expectedVersion: Long, requestId: String? = null): WriteReceipt =
        write(requestId, fingerprint("deleteTask", id, expectedVersion)) {
            val old = taskAtVersion(id, expectedVersion)
            old.externalId?.let { dao.addTombstone(TombstoneRecord(SyncModule.TASKS, old.source, it)) }
            dao.deleteTask(id)
            WriteReceipt(id, nextVersion(old.version), true)
        }

    suspend fun createCourse(draft: CourseDraft, requestId: String? = null): WriteReceipt =
        write(requestId, fingerprint("createCourse", draft)) {
            validateCourse(draft)
            val record = CourseRecord(UUID.randomUUID().toString(), draft.title.trim(), draft.semesterId, draft.teacher)
            dao.insertCourse(record)
            replaceSchedules(record.id, draft.schedules)
            WriteReceipt(record.id, 1, true)
        }

    suspend fun updateCourse(id: String, expectedVersion: Long, changes: CourseDraft, requestId: String? = null): WriteReceipt =
        write(requestId, fingerprint("updateCourse", id, expectedVersion, changes)) {
            val old = courseAtVersion(id, expectedVersion)
            validateCourse(changes)
            val updated = old.copy(title = changes.title.trim(), semesterId = changes.semesterId,
                teacher = changes.teacher, version = nextVersion(old.version), manualDirty = true)
            dao.updateCourse(updated)
            replaceSchedules(id, changes.schedules)
            WriteReceipt(id, updated.version, true)
        }

    suspend fun deleteCourse(id: String, expectedVersion: Long, requestId: String? = null): WriteReceipt =
        write(requestId, fingerprint("deleteCourse", id, expectedVersion)) {
            val old = courseAtVersion(id, expectedVersion)
            old.externalId?.let { dao.addTombstone(TombstoneRecord(SyncModule.COURSES, old.source, it)) }
            dao.deleteCourse(id) // Schedules cascade; tasks intentionally survive.
            WriteReceipt(id, nextVersion(old.version), true)
        }

    /** Semester and period configuration is one versioned aggregate; no guessed start date. */
    suspend fun saveSemester(record: SemesterRecord, periods: List<PeriodRecord>, expectedVersion: Long?): WriteReceipt =
        write(null, "") {
            val old = dao.semester(record.id)
            checkData(old?.version == expectedVersion, DataError.VERSION_CONFLICT)
            checkData(record.id.isNotBlank() && record.name.isNotBlank() && record.weekCount > 0, DataError.INVALID_RECORD)
            checkData(periods.map { it.number }.distinct().size == periods.size &&
                periods.all { it.semesterId == record.id && it.number > 0 && it.startsAt < it.endsAt }, DataError.INVALID_RECORD)
            val version = old?.let { nextVersion(it.version) } ?: 1L
            dao.saveSemester(record.copy(version = version))
            dao.deletePeriods(record.id)
            dao.insertPeriods(periods)
            WriteReceipt(record.id, version, true)
        }

    internal suspend fun taskAtVersion(id: String, version: Long): TaskRecord {
        val old = dao.task(id) ?: throw DataException(DataError.TASK_NOT_FOUND)
        checkData(old.version == version, DataError.VERSION_CONFLICT)
        return old
    }

    private suspend fun courseAtVersion(id: String, version: Long): CourseRecord {
        val old = dao.course(id) ?: throw DataException(DataError.COURSE_NOT_FOUND)
        checkData(old.version == version, DataError.VERSION_CONFLICT)
        return old
    }

    internal suspend fun validateTask(draft: TaskDraft, retainedCourse: String? = null): TaskDraft {
        val title = draft.title.trim()
        checkData(title.isNotEmpty(), DataError.EMPTY_TITLE)
        checkData(title.length <= 60, DataError.TITLE_TOO_LONG)
        checkData(draft.courseId == null || draft.courseId == retainedCourse || dao.course(draft.courseId) != null,
            DataError.COURSE_NOT_FOUND)
        return draft.copy(title = title)
    }

    internal suspend fun validateCourse(draft: CourseDraft) {
        checkData(draft.title.isNotBlank(), DataError.INVALID_RECORD)
        checkData(draft.semesterId == null || dao.semester(draft.semesterId) != null, DataError.INVALID_RECORD)
        checkData(draft.schedules.distinct().size == draft.schedules.size && draft.schedules.all {
            it.dayOfWeek in 1..7 && it.startPeriod > 0 && it.endPeriod >= it.startPeriod &&
                it.startWeek > 0 && it.endWeek >= it.startWeek
        }, DataError.INVALID_RECORD)
    }

    internal suspend fun replaceSchedules(courseId: String, schedules: List<ScheduleDraft>) {
        dao.deleteSchedules(courseId)
        dao.insertSchedules(schedules.map { draft ->
            // Stable schedule IDs, independent of response order; aggregate version is on the course.
            val id = UUID.nameUUIDFromBytes("$courseId:${fingerprint(draft)}".toByteArray(Charsets.UTF_8)).toString()
            ScheduleRecord(id, courseId, draft.dayOfWeek, draft.startPeriod, draft.endPeriod,
                draft.startWeek, draft.endWeek, draft.weekPattern, draft.location)
        })
    }

    internal suspend fun changed() { dao.ensureRevision(); dao.bumpRevision() }

    internal suspend fun saveSync(record: SyncRecord) = database.withTransaction {
        dao.saveSyncRecord(record)
        changed()
    }

    private suspend fun write(requestId: String?, signature: String, action: suspend () -> WriteReceipt): WriteReceipt =
        database.withTransaction {
            checkData(requestId == null || requestId.isNotBlank(), DataError.INVALID_RECORD)
            requestId?.let { dao.request(it) }?.let { prior ->
                checkData(prior.fingerprint == signature, DataError.REQUEST_ID_REUSED)
                return@withTransaction WriteReceipt(prior.recordId, prior.version, prior.changed, replayed = true)
            }
            val receipt = action()
            requestId?.let { dao.addRequest(WriteRequestRecord(it, signature, receipt.id, receipt.version, receipt.changed)) }
            if (receipt.changed) changed()
            receipt
        }
}

internal fun nextVersion(version: Long): Long {
    checkData(version in 1 until Long.MAX_VALUE, DataError.INVALID_RECORD)
    return version + 1
}

internal fun fingerprint(vararg parts: Any?): String {
    fun encode(part: Any?): String = when (part) {
        null -> "N"
        is TaskDraft -> "T" + listOf(part.title, part.type, part.dueAt, part.courseId).joinToString("") { encode(it) }
        is CourseDraft -> "C" + listOf(part.title, part.semesterId, part.teacher, part.schedules).joinToString("") { encode(it) }
        is ScheduleDraft -> "S" + listOf(part.dayOfWeek, part.startPeriod, part.endPeriod, part.startWeek,
            part.endWeek, part.weekPattern, part.location).joinToString("") { encode(it) }
        is List<*> -> "L${part.size}:" + part.joinToString("") { encode(it) }
        else -> part.toString().let { "V${it.length}:$it" }
    }
    val encoded = parts.joinToString("") { encode(it) }
    return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
