package com.coursekeeper.data

import android.app.AlarmManager
import android.content.Context
import com.coursekeeper.data.local.CourseKeeperDatabase
import com.coursekeeper.data.model.*
import com.coursekeeper.data.sync.*
import com.coursekeeper.reminder.AlarmReminderScheduler
import com.coursekeeper.reminder.needsReminder
import com.coursekeeper.reminder.shouldFireNotification
import com.coursekeeper.school.MyStuTaskParser
import com.coursekeeper.statistics.CourseStats
import com.coursekeeper.statistics.StatisticsService
import com.coursekeeper.task.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

class TaskSubmissionSyncTest : DataTestBase() {
    private fun imported(status: String? = "NOT_SUBMITTED", title: String = "课程作业") =
        ImportedTask("assignment", draft(title), sourceCourseId = "course-1001",
            sourceCourseName = "[2026秋] 数据库 王老师", schoolSubmission = status)

    @Test fun firstSubmittedImportIsCompletedAtLocalSyncTimeWithoutManualDirty() = runTest {
        clock.current = Instant.parse("2026-11-09T16:30:00Z")
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(imported("SUBMITTED"))) }
        // Production coordinator uses UTC; persisted completion must use the repository's local zone.
        SyncCoordinator(repository, adapter, clock.withZone(java.time.ZoneOffset.UTC)).synchronize()
        val task = repository.snapshot().tasks.single()
        assertEquals(LocalDateTime.of(2026, 11, 10, 0, 30), task.completedAt)
        assertEquals("SUBMITTED", task.schoolSubmission)
        assertEquals("course-1001", task.sourceCourseId)
        assertEquals("[2026秋] 数据库 王老师", task.sourceCourseName)
        assertEquals(1L, task.version)
        assertFalse(task.manualDirty)
        assertEquals(RecordSource.SYNC, task.source)
    }

    @Test fun existingTaskCompletesAndUpdatesFieldsInOneVersionThenRepeatedSyncIsStable() = runTest {
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(imported())) }
        val sync = coordinator(adapter)
        sync.synchronize()
        val old = repository.snapshot().tasks.single()
        clock.current += Duration.ofHours(1)
        adapter.taskResult = ModuleResult.Success(listOf(imported("SUBMITTED", "学校更新")))
        sync.synchronize(true)
        val done = repository.getTask(old.id)!!
        assertEquals(2L, done.version)
        assertEquals("学校更新", done.title)
        assertEquals(LocalDateTime.now(clock), done.completedAt)
        assertFalse(done.manualDirty)
        assertEquals(1, repository.snapshot().syncRecords.single { it.module == SyncModule.TASKS }.updated)
        clock.current += Duration.ofHours(1)
        sync.synchronize(true)
        assertEquals(done, repository.getTask(old.id))
    }

    @Test fun onlyExactSubmittedStatusCompletesAndOtherStatusesArePersistedIdempotently() = runTest {
        val statuses = listOf(null, "NOT_SUBMITTED", "DRAFT", "REOPENED", "UNKNOWN", "OTHER", "submitted", " SUBMITTED ")
        val adapter = FakeAdapter().apply {
            taskResult = ModuleResult.Success(statuses.mapIndexed { i, status -> imported(status).copy(externalId = "$i") })
        }
        val sync = coordinator(adapter)
        sync.synchronize()
        val before = repository.snapshot().tasks
        assertEquals(statuses.toSet(), before.map { it.schoolSubmission }.toSet())
        assertTrue(before.all { it.completedAt == null && !it.manualDirty && it.version == 1L })
        sync.synchronize(true)
        assertEquals(before, repository.snapshot().tasks)
    }

    @Test fun exactCourseNameLinksSyncedCourseAndUnmatchedNameKeepsExistingLink() = runTest {
        val adapter = FakeAdapter().apply {
            courseResult = ModuleResult.Success(listOf(ImportedCourse("course-1001", CourseDraft("数据库"))))
            taskResult = ModuleResult.Success(listOf(imported()))
        }
        val sync = coordinator(adapter)
        sync.synchronize()
        val linked = repository.snapshot().tasks.single()
        val courseId = repository.snapshot().courses.single().id
        assertEquals("CBE-01：学校课程名（去学期标签与教师名）唯一命中本地同步课程", courseId, linked.courseId)
        assertEquals("来源课程编号只作来源信息，不冒充教务编号", "course-1001", linked.sourceCourseId)
        adapter.taskResult = ModuleResult.Success(listOf(imported("DRAFT").copy(sourceCourseName = "学校改名")))
        sync.synchronize(true)
        val updated = repository.getTask(linked.id)!!
        assertEquals(2L, updated.version)
        assertEquals("DRAFT", updated.schoolSubmission)
        assertEquals("改名后不再唯一命中：保留已建立的关联，不抖动也不猜测", courseId, updated.courseId)
        sync.synchronize(true)
        assertEquals(updated, repository.getTask(linked.id))
        adapter.taskResult = ModuleResult.Success(listOf(imported("DRAFT").copy(courseExternalId = "course-1001")))
        sync.synchronize(true)
        assertEquals(repository.snapshot().courses.single().id, repository.getTask(linked.id)!!.courseId)
    }

    @Test fun sameCourseNameInTwoSyncedCoursesIsAmbiguousAndStaysUnlinked() = runTest {
        val adapter = FakeAdapter().apply {
            courseResult = ModuleResult.Success(listOf(
                ImportedCourse("course-1001", CourseDraft("数据库")),
                ImportedCourse("course-2001", CourseDraft("数据库")),
            ))
            taskResult = ModuleResult.Success(listOf(imported()))
        }
        coordinator(adapter).synchronize()
        assertNull("同名不同课不猜，留待用户手动关联", repository.snapshot().tasks.single().courseId)
    }

    @Test fun numericSourceCourseIdIsNeverTreatedAsTeachingSystemCourseNumber() = runTest {
        val adapter = FakeAdapter().apply {
            // 本地课程编号恰好与 MYSTU 课程 ID 相同，也不能据此关联。
            courseResult = ModuleResult.Success(listOf(ImportedCourse("course-1001", CourseDraft("别的课"))))
            taskResult = ModuleResult.Success(listOf(imported().copy(sourceCourseName = null)))
        }
        coordinator(adapter).synchronize()
        assertNull(repository.snapshot().tasks.single().courseId)
    }

    @Test fun autoLinkedCourseIsKeptWhenLocalCourseIsMissingFromThisSnapshot() = runTest {
        val adapter = FakeAdapter().apply {
            courseResult = ModuleResult.Success(listOf(ImportedCourse("course-1001", CourseDraft("数据库"))))
            taskResult = ModuleResult.Success(listOf(imported()))
        }
        val sync = coordinator(adapter)
        sync.synchronize()
        val linked = repository.snapshot().tasks.single()
        val courseId = requireNotNull(linked.courseId)
        // 后续作业同步不重复导入课程：本地课程仍在，关联按名称重新解析后保持不变。
        adapter.courseResult = ModuleResult.Success(emptyList())
        adapter.taskResult = ModuleResult.Success(listOf(imported("NOT_SUBMITTED")))
        sync.synchronize(true)
        assertEquals(courseId, repository.getTask(linked.id)!!.courseId)
    }

    @Test fun manualEditDuringFetchProtectsWholeTaskIncludingItsAssociationAndProvenance() = runTest {
        val local = repository.createCourse(CourseDraft("用户关联课程"))
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(imported())) }
        val sync = coordinator(adapter)
        sync.synchronize()
        val old = repository.snapshot().tasks.single()
        adapter.beforeTasks = { repository.updateTask(old.id, old.version, draft("用户改名").copy(courseId = local.id)) }
        adapter.taskResult = ModuleResult.Success(listOf(imported("SUBMITTED").copy(sourceCourseName = "远端改名")))
        withContext(Dispatchers.Default) { sync.synchronize(true) }
        val task = repository.getTask(old.id)!!
        assertEquals("用户改名", task.title)
        assertEquals(local.id, task.courseId)
        assertEquals(old.schoolSubmission, task.schoolSubmission)
        assertEquals(old.sourceCourseName, task.sourceCourseName)
        assertNull(task.completedAt)
        assertTrue(task.manualDirty)
        assertEquals(2L, task.version)
    }

    @Test fun manualCompletionAndSchoolCompletionNeverRevertWhenSchoolReopens() = runTest {
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(imported())) }
        val sync = coordinator(adapter)
        sync.synchronize()
        val id = repository.snapshot().tasks.single().id
        repository.setTaskCompleted(id, true, 1)
        val manual = repository.getTask(id)!!
        adapter.taskResult = ModuleResult.Success(listOf(imported("SUBMITTED")))
        sync.synchronize(true)
        assertEquals(manual, repository.getTask(id))

        adapter.taskResult = ModuleResult.Success(listOf(imported("SUBMITTED").copy(externalId = "auto")))
        sync.synchronize(true)
        val automatic = repository.snapshot().tasks.single { it.externalId == "auto" }
        adapter.taskResult = ModuleResult.Success(listOf(imported("REOPENED").copy(externalId = "auto")))
        sync.synchronize(true)
        assertEquals(automatic, repository.getTask(automatic.id))
    }

    @Test fun userRestoreToPendingPreventsAnyLaterSchoolAutoCompletion() = runTest {
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(imported("SUBMITTED"))) }
        val sync = coordinator(adapter)
        sync.synchronize()
        val old = repository.snapshot().tasks.single()
        repository.setTaskCompleted(old.id, false, old.version)
        val restored = repository.getTask(old.id)!!
        assertTrue(restored.manualDirty)
        assertNull(restored.completedAt)
        sync.synchronize(true)
        assertEquals(restored, repository.getTask(old.id))
    }

    @Test fun submittedTaskDoesNotResurrectAfterUserDeletionOrTouchManualOrAgentTasks() = runTest {
        val manual = repository.createTask(draft("手动任务"))
        val agent = repository.createTask(draft("Agent 任务"), RecordSource.AGENT)
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(imported("SUBMITTED"))) }
        val sync = coordinator(adapter)
        sync.synchronize()
        val remote = repository.snapshot().tasks.single { it.source == RecordSource.SYNC }
        repository.deleteTask(remote.id, remote.version)
        sync.synchronize(true)
        assertEquals(setOf(manual.id, agent.id), repository.snapshot().tasks.map { it.id }.toSet())
        assertTrue(repository.snapshot().tasks.all { it.completedAt == null && it.schoolSubmission == null })
    }

    @Test fun malformedBatchRollsBackAutoCompletionAndPreservesLastSuccess() = runTest {
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(imported())) }
        val sync = coordinator(adapter)
        sync.synchronize()
        val old = repository.snapshot()
        clock.current += Duration.ofHours(1)
        adapter.taskResult = ModuleResult.Success(listOf(imported("SUBMITTED"), imported().copy(externalId = "bad", fields = draft(" "))))
        sync.synchronize(true)
        val after = repository.snapshot()
        assertEquals(old.tasks, after.tasks)
        val status = after.syncRecords.single { it.module == SyncModule.TASKS }
        assertEquals(SyncStatus.ERROR, status.status)
        assertEquals(old.syncRecords.single { it.module == SyncModule.TASKS }.lastSuccessAt, status.lastSuccessAt)
    }

    @Test fun conflictingSubmissionStatusesRejectWholeModule() = runTest {
        val adapter = FakeAdapter().apply {
            taskResult = ModuleResult.Success(listOf(imported("SUBMITTED"), imported("NOT_SUBMITTED")))
        }
        coordinator(adapter).synchronize()
        assertTrue(repository.snapshot().tasks.isEmpty())
        assertEquals(SyncFailure.INVALID_DATA.name,
            repository.snapshot().syncRecords.single { it.module == SyncModule.TASKS }.errorCode)
    }

    @Test fun staleBaselineCannotAutoCompleteNewerUnprotectedRecord() = runTest {
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(imported())) }
        coordinator(adapter).synchronize()
        val baseline = repository.snapshot()
        repository.mergeTasks(listOf(imported("DRAFT")), baseline, clock.instant())
        val newer = repository.snapshot().tasks
        val failure = runCatching { repository.mergeTasks(listOf(imported("SUBMITTED")), baseline, clock.instant()) }.exceptionOrNull()
        assertEquals(DataError.VERSION_CONFLICT, (failure as DataException).code)
        assertEquals(newer, repository.snapshot().tasks)
    }

    @Test fun schoolFieldsAndCompletionSurviveDatabaseReopen() = runTest {
        val name = "submission-${UUID.randomUUID()}.db"
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(imported("SUBMITTED"))) }
        try {
            val expected = CourseKeeperDatabase.open(context, name).let { disk ->
                try {
                    val repo = CourseKeeperRepository(disk, clock)
                    SyncCoordinator(repo, adapter, clock).synchronize()
                    repo.snapshot().tasks.single()
                } finally { disk.close() }
            }
            val reopened = CourseKeeperDatabase.open(context, name)
            try { assertEquals(expected, CourseKeeperRepository(reopened, clock).getTask(expected.id)) }
            finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun parserSubmissionReachesTaskFiltersAndCompletionStatistics() = runTest {
        val html = javaClass.classLoader!!.getResourceAsStream("school/mystu-dashboard.html")!!.use { it.readBytes().decodeToString() }
        // The original submitted fixture lacks a stable assignment ID and must remain deferred.
        // Simulate the school's identified assignment changing from pending to submitted.
        val imports = MyStuTaskParser().parseTasks(html.replace(
            "<div class=\"details\">还未提交</div>", "<div class=\"details\">已提交</div>"
        )).tasks
        val submitted = imports.first { it.schoolSubmission == "SUBMITTED" && it.externalId != null }
        val due = requireNotNull(submitted.fields.dueAt)
        clock.current = due.plusSeconds(30).atZone(clock.zone).toInstant()
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(submitted)) }
        coordinator(adapter).synchronize()
        val state = repository.snapshot()
        val task = state.tasks.single()
        val now = LocalDateTime.now(clock)
        assertEquals(TaskStatus.COMPLETED, task.statusAt(now))
        assertTrue(listOf(task.toView(emptyList(), now)).visible(TaskStatusFilter.OPEN, null).isEmpty())
        assertEquals(1, listOf(task.toView(emptyList(), now)).visible(TaskStatusFilter.DONE, null).size)
        assertFalse(task.needsReminder(now))
        assertFalse(shouldFireNotification(task, due))
        val stats = StatisticsService.compute(state, CourseStats(), now)
        assertEquals(1, stats.weekCompletedCount)
        assertEquals(1f, stats.completionRate)
    }

    @Test fun autoCompletionCancelsPreviouslyScheduledReminderOnSnapshotReconcile() = runTest {
        val due = LocalDateTime.now(clock).plusDays(2)
        val adapter = FakeAdapter().apply {
            taskResult = ModuleResult.Success(listOf(imported().copy(fields = draft().copy(dueAt = due))))
        }
        val sync = coordinator(adapter)
        sync.synchronize()
        val scheduler = AlarmReminderScheduler(context)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        scheduler.reconcile(repository.snapshot().tasks, LocalDateTime.now(clock))
        assertEquals(1, shadowOf(alarm).scheduledAlarms.size)
        adapter.taskResult = ModuleResult.Success(listOf(imported("SUBMITTED").copy(fields = draft().copy(dueAt = due))))
        sync.synchronize(true)
        scheduler.reconcile(repository.snapshot().tasks, LocalDateTime.now(clock))
        assertTrue(shadowOf(alarm).scheduledAlarms.isEmpty())
    }
}
