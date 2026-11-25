package com.coursekeeper.home

import com.coursekeeper.data.DataTestBase
import com.coursekeeper.data.FakeAdapter
import com.coursekeeper.data.model.SyncStatus as StoredSyncStatus
import com.coursekeeper.data.sync.ImportedTask
import com.coursekeeper.data.sync.ModuleResult
import com.coursekeeper.data.sync.SessionState
import com.coursekeeper.data.sync.SyncFailure
import com.coursekeeper.data.sync.SyncSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** A requests pass through the real coordinator and Room repository with a local school adapter. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSyncIntegrationTest : DataTestBase() {
    @Test fun offlineRefreshPreservesCachedTaskAndRecoveryBypassesRecentSuccessThrottle() = runTest {
        val cached = repository.createTask(draft("本机手动任务"))
        val adapter = FakeAdapter()
        val sync = coordinator(adapter)
        sync.synchronize(true)
        val controller = HomeSyncController(backgroundScope, sync::onForeground) { sync.synchronize(true) }

        adapter.session = SessionState.OFFLINE
        controller.refresh()
        runCurrent()
        controller.refreshing.first { !it }
        val offline = repository.snapshot()
        assertEquals(StoredSyncStatus.OFFLINE, SyncSummary.from(offline.syncRecords).status)
        assertEquals(cached.id, offline.tasks.single().id)
        assertEquals(cached.version, offline.tasks.single().version)

        adapter.session = SessionState.VALID
        adapter.taskResult = ModuleResult.Success(listOf(ImportedTask("new-task", draft("学校新作业"))))
        controller.refresh()
        runCurrent()
        controller.refreshing.first { !it }
        val recovered = repository.snapshot()
        assertEquals(StoredSyncStatus.IDLE, SyncSummary.from(recovered.syncRecords).status)
        assertEquals(setOf("本机手动任务", "学校新作业"), recovered.tasks.map { it.title }.toSet())
        assertEquals(2, adapter.taskCalls)
    }

    @Test fun partialRefreshRetainsCachedTasksAndImportsSchoolCompletion() = runTest {
        repository.createTask(draft("离线仍可使用的任务"))
        val adapter = FakeAdapter().apply {
            courseResult = ModuleResult.Failure(SyncFailure.PARSE_ERROR)
            taskResult = ModuleResult.Success(listOf(ImportedTask("submitted", draft("已提交作业"),
                schoolSubmission = "SUBMITTED")))
        }
        val sync = coordinator(adapter)
        val controller = HomeSyncController(backgroundScope, sync::onForeground) { sync.synchronize(true) }
        controller.refresh()
        runCurrent()
        controller.refreshing.first { !it }
        val snapshot = repository.snapshot()
        assertEquals(StoredSyncStatus.PARTIAL, SyncSummary.from(snapshot.syncRecords).status)
        assertEquals(2, snapshot.tasks.size)
        assertNotNull(snapshot.tasks.single { it.externalId == "submitted" }.completedAt)
        assertNull(snapshot.tasks.single { it.title == "离线仍可使用的任务" }.completedAt)
    }

    @Test fun logoutStopWaitsForCancellationAndPreventsLateTaskImport() = runTest {
        val cached = repository.createTask(draft("已有任务"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapter = FakeAdapter().apply {
            beforeTasks = { started.complete(Unit); release.await() }
            taskResult = ModuleResult.Success(listOf(ImportedTask("late", draft("退出后不能写入"))))
        }
        val sync = coordinator(adapter)
        val controller = HomeSyncController(backgroundScope, sync::onForeground) { sync.synchronize(true) }
        controller.refresh()
        started.await()
        controller.stop()
        release.complete(Unit)
        runCurrent()
        assertFalse(controller.refreshing.value)
        val snapshot = repository.snapshot()
        assertEquals(listOf(cached.id), snapshot.tasks.map { it.id })
        assertTrue(snapshot.syncRecords.none { it.status == StoredSyncStatus.SYNCING })
    }

    @Test fun expiredSessionPreservesCachedTasksDuringManualRefresh() = runTest {
        val cached = repository.createTask(draft("过期后保留"))
        val adapter = FakeAdapter().apply { session = SessionState.EXPIRED }
        val sync = coordinator(adapter)
        val controller = HomeSyncController(backgroundScope, sync::onForeground) { sync.synchronize(true) }
        controller.refresh()
        runCurrent()
        controller.refreshing.first { !it }
        val snapshot = repository.snapshot()
        assertEquals(StoredSyncStatus.EXPIRED, SyncSummary.from(snapshot.syncRecords).status)
        assertEquals(cached.id, snapshot.tasks.single().id)
        assertEquals(0, adapter.taskCalls)
    }
}
