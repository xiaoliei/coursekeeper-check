package com.coursekeeper.data.sync

import androidx.room.withTransaction
import com.coursekeeper.data.*
import com.coursekeeper.data.model.*
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/** Baseline is read BEFORE network I/O; a later user edit must cause a conflict or be protected. */
internal suspend fun CourseKeeperRepository.mergeCourses(
    imports: List<ImportedCourse>, baseline: DataSnapshot, completedAt: Instant,
    completeSnapshot: Boolean = false,
): MergeCounts = database.withTransaction {
    val distinct = imports.distinct()
    checkData(distinct.all { it.externalId.isNotBlank() } &&
        distinct.map { it.externalId }.distinct().size == distinct.size, DataError.SYNC_IDENTITY_CONFLICT)
    val currentCourses = dao.courses().filter { it.source == RecordSource.SYNC }.associateBy { it.externalId }
    val baselineCourses = baseline.courses.filter { it.source == RecordSource.SYNC }.associateBy { it.externalId }
    val schedulesByCourse = dao.schedules().groupBy { it.courseId }
    var counts = MergeCounts()
    for (item in distinct) {
        if (dao.deleted(SyncModule.COURSES, RecordSource.SYNC, item.externalId)) {
            counts = counts.copy(protectedCount = counts.protectedCount + 1)
            continue
        }
        val old = currentCourses[item.externalId]
        val before = baselineCourses[item.externalId]
        if (old?.manualDirty == true) {
            counts = counts.copy(protectedCount = counts.protectedCount + 1)
            continue
        }
        checkData(old?.version == before?.version, DataError.VERSION_CONFLICT)
        validateCourse(item.fields)
        val fields = item.fields
        val currentSchedules = schedulesByCourse[old?.id].orEmpty().map { it.asDraft() }.toSet()
        if (old == null) {
            val record = CourseRecord(UUID.randomUUID().toString(), fields.title.trim(), fields.semesterId,
                fields.teacher, RecordSource.SYNC, item.externalId)
            dao.insertCourse(record)
            replaceSchedules(record.id, fields.schedules)
            counts = counts.copy(inserted = counts.inserted + 1)
        } else if (old.title != fields.title.trim() || old.semesterId != fields.semesterId ||
            old.teacher != fields.teacher || currentSchedules != fields.schedules.toSet()) {
            dao.updateCourse(old.copy(title = fields.title.trim(), semesterId = fields.semesterId,
                teacher = fields.teacher, version = nextVersion(old.version)))
            replaceSchedules(old.id, fields.schedules)
            counts = counts.copy(updated = counts.updated + 1)
        }
    }
    val scopes = distinct.map { it.fields.semesterId }.distinct()
    if (completeSnapshot && scopes.size == 1) {
        val semesterId = scopes.single()
        val importedIds = distinct.mapTo(HashSet()) { it.externalId }
        for (stale in currentCourses.values) {
            if (stale.externalId == null || stale.externalId in importedIds || stale.manualDirty ||
                stale.semesterId != semesterId) continue
            checkData(baselineCourses[stale.externalId] == stale, DataError.VERSION_CONFLICT)
            // 学校撤课只清理镜像。保留任务关联，也允许学校重新排课时再导入。
            dao.deleteCourse(stale.id)
            counts = counts.copy(deleted = counts.deleted + 1)
        }
    }
    finishModule(SyncModule.COURSES, counts, completedAt)
    counts
}

internal suspend fun CourseKeeperRepository.mergeTasks(
    imports: List<ImportedTask>, baseline: DataSnapshot, completedAt: Instant,
): MergeCounts = database.withTransaction {
    val distinct = imports.distinct()
    val identified = distinct.filter { !it.externalId.isNullOrBlank() }
    checkData(identified.map { it.externalId }.distinct().size == identified.size, DataError.SYNC_IDENTITY_CONFLICT)
    var counts = MergeCounts(missingIdentity = distinct.size - identified.size)
    for (item in identified) {
        val externalId = requireNotNull(item.externalId)
        if (dao.deleted(SyncModule.TASKS, RecordSource.SYNC, externalId)) {
            counts = counts.copy(protectedCount = counts.protectedCount + 1)
            continue
        }
        val old = dao.importedTask(RecordSource.SYNC, externalId)
        val before = baseline.tasks.singleOrNull { it.source == RecordSource.SYNC && it.externalId == externalId }
        if (old?.manualDirty == true || old?.completedAt != null) {
            counts = counts.copy(protectedCount = counts.protectedCount + 1)
            continue
        }
        checkData(old?.version == before?.version, DataError.VERSION_CONFLICT)
        // C supplies external associations, never a guessed local courseId.
        val courseId = item.courseExternalId?.let { externalCourse ->
            dao.importedCourse(RecordSource.SYNC, externalCourse)?.id
                ?: old?.courseId?.takeIf { dao.deleted(SyncModule.COURSES, RecordSource.SYNC, externalCourse) }
        }
        val fields = validateTask(item.fields.copy(courseId = courseId))
        // D v1.1: only an explicit SUBMITTED fills an unprotected SYNC task's completion.
        // Convert the sync instant using the repository's local zone, as manual completion does.
        val schoolCompletedAt = if (item.schoolSubmission == "SUBMITTED")
            LocalDateTime.ofInstant(completedAt, clock.zone) else null
        if (old == null) {
            dao.insertTask(TaskRecord(UUID.randomUUID().toString(), fields.title, fields.type, fields.dueAt,
                courseId, completedAt = schoolCompletedAt, source = RecordSource.SYNC, externalId = externalId,
                schoolSubmission = item.schoolSubmission, sourceCourseId = item.sourceCourseId,
                sourceCourseName = item.sourceCourseName))
            counts = counts.copy(inserted = counts.inserted + 1)
        } else {
            val merged = old.copy(title = fields.title, type = fields.type, dueAt = fields.dueAt,
                courseId = courseId, completedAt = schoolCompletedAt, schoolSubmission = item.schoolSubmission,
                sourceCourseId = item.sourceCourseId, sourceCourseName = item.sourceCourseName)
            if (merged != old) {
                dao.updateTask(merged.copy(version = nextVersion(old.version)))
                counts = counts.copy(updated = counts.updated + 1)
            }
        }
    }
    finishModule(SyncModule.TASKS, counts, completedAt)
    counts
}

private suspend fun CourseKeeperRepository.finishModule(module: SyncModule, counts: MergeCounts, at: Instant) {
    val previous = dao.syncRecords().singleOrNull { it.module == module } ?: SyncRecord(module)
    dao.saveSyncRecord(previous.copy(status = SyncStatus.IDLE, lastSuccessAt = at, errorCode = null,
        inserted = counts.inserted, updated = counts.updated, protectedCount = counts.protectedCount, missingIdentity = counts.missingIdentity))
    changed()
}
