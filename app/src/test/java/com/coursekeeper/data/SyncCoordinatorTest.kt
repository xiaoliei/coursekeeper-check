package com.coursekeeper.data

import com.coursekeeper.data.model.*
import com.coursekeeper.data.sync.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration

class SyncCoordinatorTest : DataTestBase() {
    @Test fun duplicateImportsAndUnchangedRefreshKeepIdsVersionsAndSchedulesStable() = runTest {
        val adapter = FakeAdapter().apply {
            courseResult = ModuleResult.Success(listOf(ImportedCourse("course-1", CourseDraft("数据库", schedules = listOf(ScheduleDraft(1, 1, 2, 1, 16))))))
            taskResult = ModuleResult.Success(listOf(ImportedTask("task-1", draft(), "course-1"), ImportedTask("task-1", draft(), "course-1")))
        }
        val sync = coordinator(adapter)
        sync.synchronize()
        val before = repository.snapshot()
        sync.synchronize(force = true)
        val after = repository.snapshot()
        assertEquals(before.courses, after.courses)
        assertEquals(before.schedules, after.schedules)
        assertEquals(before.tasks, after.tasks)
        assertEquals(after.courses.single().id, after.tasks.single().courseId)
    }

    @Test fun changedRemoteFieldsIncrementVersionsAndEmptySuccessNeverClearsCache() = runTest {
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(ImportedTask("task", draft()))) }
        val sync = coordinator(adapter)
        sync.synchronize()
        val id = repository.snapshot().tasks.single().id
        adapter.taskResult = ModuleResult.Success(listOf(ImportedTask("task", draft("学校更新"))))
        sync.synchronize(true)
        assertEquals(2L, repository.getTask(id)!!.version)
        adapter.taskResult = ModuleResult.Success(emptyList())
        sync.synchronize(true)
        assertEquals("学校更新", repository.getTask(id)!!.title)
    }

    @Test fun userEditsCompletionsAndDeletedImportedTasksAreProtected() = runTest {
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success((1..3).map { ImportedTask("task-$it", draft("任务 $it")) }) }
        val sync = coordinator(adapter)
        sync.synchronize()
        val tasks = repository.snapshot().tasks.associateBy { it.externalId }
        repository.updateTask(tasks.getValue("task-1").id, 1, draft("用户修改"))
        repository.setTaskCompleted(tasks.getValue("task-2").id, true, 1)
        repository.deleteTask(tasks.getValue("task-3").id, 1)
        adapter.taskResult = ModuleResult.Success((1..3).map { ImportedTask("task-$it", draft("远程覆盖")) })
        sync.synchronize(true)
        val state = repository.snapshot()
        assertEquals(2, state.tasks.size)
        assertEquals("用户修改", state.tasks.single { it.externalId == "task-1" }.title)
        assertNotNull(state.tasks.single { it.externalId == "task-2" }.completedAt)
        assertEquals(3, state.syncRecords.single { it.module == SyncModule.TASKS }.protectedCount)
    }

    @Test fun malformedModuleRollsBackAllRowsWhileOtherModuleCommits() = runTest {
        val adapter = FakeAdapter().apply {
            courseResult = ModuleResult.Success(listOf(ImportedCourse("c", CourseDraft("成功课表"))))
            taskResult = ModuleResult.Success(listOf(ImportedTask("valid", draft()), ImportedTask("invalid", draft(" "))))
        }
        val result = coordinator(adapter).synchronize() as SyncRunResult.Finished
        assertEquals(SyncStatus.PARTIAL, result.summary.status)
        assertEquals(1, repository.snapshot().courses.size)
        assertTrue(repository.snapshot().tasks.isEmpty())
        assertNull(result.summary.modules.single { it.module == SyncModule.TASKS }.lastSuccessAt)
    }

    @Test fun conflictingRemoteIdentitiesRejectWholeModuleAndMissingIdentityIsDeferred() = runTest {
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(ImportedTask(null, draft()))) }
        val sync = coordinator(adapter)
        sync.synchronize()
        assertEquals(1, repository.snapshot().syncRecords.single { it.module == SyncModule.TASKS }.missingIdentity)
        adapter.taskResult = ModuleResult.Success(listOf(ImportedTask("same", draft("a")), ImportedTask("same", draft("b"))))
        sync.synchronize(true)
        assertTrue(repository.snapshot().tasks.isEmpty())
        assertEquals(SyncStatus.ERROR, repository.snapshot().syncRecords.single { it.module == SyncModule.TASKS }.status)
    }

    @Test fun throttleBoundaryForcedRefreshAndSessionChecksAreIndependent() = runTest {
        val adapter = FakeAdapter()
        val sync = coordinator(adapter)
        sync.synchronize()
        clock.current += Duration.ofMinutes(29)
        val skipped = sync.synchronize() as SyncRunResult.Finished
        assertEquals(SyncModule.entries.toSet(), skipped.throttled)
        assertEquals(1, adapter.taskCalls)
        assertEquals(2, adapter.checks)
        sync.synchronize(true)
        assertEquals(2, adapter.taskCalls)
        clock.current += Duration.ofMinutes(30)
        sync.synchronize()
        assertEquals(3, adapter.taskCalls)
    }

    @Test fun retriesStopAfterTwoAndOnlyFailedModuleIsFetchedNextTime() = runTest {
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Failure(SyncFailure.TIMEOUT, retryable = true) }
        val sync = coordinator(adapter)
        val partial = sync.synchronize() as SyncRunResult.Finished
        assertEquals(SyncStatus.PARTIAL, partial.summary.status)
        assertEquals(1, adapter.courseCalls)
        assertEquals(3, adapter.taskCalls)
        adapter.taskResult = ModuleResult.Success(emptyList())
        sync.synchronize()
        assertEquals(1, adapter.courseCalls)
        assertEquals(4, adapter.taskCalls)
    }

    @Test fun expiryWithinThrottleShowsBannerAndManualWritesStillWork() = runTest {
        val adapter = FakeAdapter()
        val sync = coordinator(adapter)
        sync.synchronize()
        adapter.session = SessionState.EXPIRED
        assertEquals(SyncStatus.EXPIRED, (sync.synchronize() as SyncRunResult.Finished).summary.status)
        repository.createTask(draft())
        assertEquals(1, repository.snapshot().tasks.size)
        assertEquals(1, adapter.taskCalls)
        adapter.session = SessionState.VALID
        assertEquals(SyncStatus.IDLE, (sync.synchronize() as SyncRunResult.Finished).summary.status)
    }

    @Test fun networkFailurePreservesDataAndLastSuccessfulTime() = runTest {
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(ImportedTask("t", draft()))) }
        val sync = coordinator(adapter)
        sync.synchronize()
        val before = repository.snapshot()
        clock.current += Duration.ofHours(1)
        adapter.session = SessionState.OFFLINE
        assertEquals(SyncStatus.OFFLINE, (sync.synchronize() as SyncRunResult.Finished).summary.status)
        assertEquals(before.tasks, repository.snapshot().tasks)
        assertEquals(before.syncRecords.map { it.lastSuccessAt }, repository.snapshot().syncRecords.map { it.lastSuccessAt })
    }

    @Test fun manualWriteDuringNetworkRequestIsNeverOverwritten() = runTest {
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(ImportedTask("t", draft()))) }
        val sync = coordinator(adapter)
        sync.synchronize()
        val task = repository.snapshot().tasks.single()
        adapter.beforeTasks = { repository.updateTask(task.id, 1, draft("并发手动修改")) }
        adapter.taskResult = ModuleResult.Success(listOf(ImportedTask("t", draft("迟到的远程数据"))))
        // Use real time while the injected second writer runs on Room's executor.
        withContext(Dispatchers.Default) { sync.synchronize(true) }
        assertEquals("并发手动修改", repository.getTask(task.id)!!.title)
    }

    @Test fun overlappingRefreshIsCoalescedAndCancellationLeavesNoSyncingState() = runTest {
        val entered = CompletableDeferred<Unit>()
        val adapter = FakeAdapter().apply { beforeCourses = { entered.complete(Unit); awaitCancellation() } }
        val sync = coordinator(adapter)
        val running = launch { sync.synchronize(true) }
        entered.await()
        assertEquals(SyncRunResult.InProgress, sync.synchronize(true))
        running.cancelAndJoin()
        assertFalse(repository.snapshot().syncRecords.any { it.status == SyncStatus.SYNCING })
        adapter.beforeCourses = {}
        assertTrue(sync.synchronize(true) is SyncRunResult.Finished)
    }

    @Test fun switchingSchoolCannotMergeIntoExistingSchoolCache() = runTest {
        val adapter = FakeAdapter()
        coordinator(adapter).synchronize()
        adapter.schoolId = "another-school"
        assertEquals(SyncRunResult.SchoolMismatch, coordinator(adapter).synchronize(true))
        assertEquals(1, adapter.courseCalls)
    }

    @Test fun resyncAfterDeletingImportedCourseRetainsItsTaskAssociation() = runTest {
        val adapter = FakeAdapter().apply {
            courseResult = ModuleResult.Success(listOf(ImportedCourse("c", CourseDraft("课程"))))
            taskResult = ModuleResult.Success(listOf(ImportedTask("t", draft(), "c")))
        }
        val sync = coordinator(adapter)
        sync.synchronize()
        val course = repository.snapshot().courses.single()
        repository.deleteCourse(course.id, course.version)
        sync.synchronize(true)
        assertTrue(repository.snapshot().courses.isEmpty())
        assertEquals(course.id, repository.snapshot().tasks.single().courseId)
    }

    @Test fun staleSyncBaselineRejectsALateResponseAfterAnotherSyncCommitted() = runTest {
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(ImportedTask("t", draft()))) }
        val sync = coordinator(adapter)
        sync.synchronize()
        val newerAdapter = FakeAdapter().apply {
            taskResult = ModuleResult.Success(listOf(ImportedTask("t", draft("已提交的新版本"))))
        }
        adapter.beforeTasks = { coordinator(newerAdapter).synchronize(true) }
        adapter.taskResult = ModuleResult.Success(listOf(ImportedTask("t", draft("迟到的旧版本"))))
        withContext(Dispatchers.Default) { sync.synchronize(true) }
        val snapshot = repository.snapshot()
        assertEquals("已提交的新版本", snapshot.tasks.single().title)
        assertEquals(2L, snapshot.tasks.single().version)
        assertEquals(SyncFailure.VERSION_CONFLICT.name, snapshot.syncRecords.single { it.module == SyncModule.TASKS }.errorCode)
    }

    @Test fun eachFetchTimeoutIsBoundedAndParseFailuresNeverRetry() = runTest {
        val adapter = FakeAdapter().apply { beforeTasks = { delay(1_000) } }
        val sync = SyncCoordinator(repository, adapter, clock, requestTimeoutMillis = 50, retryDelayMillis = 0)
        sync.synchronize()
        assertEquals(3, adapter.taskCalls)
        assertEquals(SyncFailure.TIMEOUT.name, repository.snapshot().syncRecords.single { it.module == SyncModule.TASKS }.errorCode)
        adapter.beforeTasks = {}
        adapter.taskResult = ModuleResult.Failure(SyncFailure.PARSE_ERROR, retryable = true)
        sync.synchronize(true)
        assertEquals(4, adapter.taskCalls)
        repository.createTask(draft("网络故障仍能保存"))
        assertEquals(1, repository.snapshot().tasks.size)
    }
}
