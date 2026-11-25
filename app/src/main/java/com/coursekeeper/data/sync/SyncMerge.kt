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
    schoolId: String? = null, sourceCourseIds: Set<String> = emptySet(),
): MergeCounts = database.withTransaction {
    val distinct = imports.distinct()
    val identified = distinct.filter { !it.externalId.isNullOrBlank() }
    checkData(identified.map { it.externalId }.distinct().size == identified.size, DataError.SYNC_IDENTITY_CONFLICT)
    // CBE-01：本地教务课程的映射候选（只含同步来源、有稳定编号的课程；手动课程没有外部编号）。
    val linkableCourses = dao.courses()
        .filter { it.source == RecordSource.SYNC && it.externalId != null }
        .map { CourseLinker.Candidate(it.externalId!!, it.title) }
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
        // CBE-01（2026-11-25）：显式编号优先；没有显式编号时按 C 的课程名规则尝试唯一映射。
        // 映射落在本层（E 的合并）而不是适配器：适配器不读库，且合并层已有的
        // manualDirty / completedAt 保护天然覆盖"用户手动改关联"。
        val explicitExternalId = item.courseExternalId
        val linkedExternalId = explicitExternalId
            ?: CourseLinker.resolve(item.sourceCourseName, linkableCourses)
        val courseId = when {
            explicitExternalId != null -> dao.importedCourse(RecordSource.SYNC, explicitExternalId)?.id
                ?: old?.courseId?.takeIf { dao.deleted(SyncModule.COURSES, RecordSource.SYNC, explicitExternalId) }
            // 自动映射命中：课程已在候选内，正常都能找到；找不到时保留旧关联而不是清空。
            linkedExternalId != null -> dao.importedCourse(RecordSource.SYNC, linkedExternalId)?.id ?: old?.courseId
            // 未命中或歧义：保留旧关联（手动关联另有 manualDirty 保护），不猜。
            else -> old?.courseId
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
    // Persist only after the entire task batch validates, within the same transaction.
    // Union preserves temporarily absent courses; protected/deleted tasks do not erase discovery history.
    val discovered = sourceCourseIds + imports.mapNotNull { it.sourceCourseId }
    checkData(discovered.all { it.isNotBlank() }, DataError.INVALID_RECORD)
    if (schoolId != null) {
        checkData(schoolId.isNotBlank(), DataError.INVALID_RECORD)
        dao.rememberSourceCourses(discovered.map { SchoolCourseCacheRecord(schoolId, it) })
    } else checkData(sourceCourseIds.isEmpty(), DataError.INVALID_RECORD)
    finishModule(SyncModule.TASKS, counts, completedAt)
    counts
}

private suspend fun CourseKeeperRepository.finishModule(module: SyncModule, counts: MergeCounts, at: Instant) {
    val previous = dao.syncRecords().singleOrNull { it.module == module } ?: SyncRecord(module)
    dao.saveSyncRecord(previous.copy(status = SyncStatus.IDLE, lastSuccessAt = at, errorCode = null,
        inserted = counts.inserted, updated = counts.updated, protectedCount = counts.protectedCount, missingIdentity = counts.missingIdentity))
    changed()
}
