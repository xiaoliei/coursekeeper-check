package com.coursekeeper.home

import com.coursekeeper.data.model.SyncModule
import com.coursekeeper.data.model.SyncRecord
import com.coursekeeper.data.model.SyncStatus
import com.coursekeeper.data.sync.SyncFailure
import com.coursekeeper.data.sync.SyncRunResult
import com.coursekeeper.data.sync.SyncSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSyncControllerTest {
    private val success = SyncRunResult.Finished(SyncSummary.from(SyncModule.entries.map {
        SyncRecord(it, status = SyncStatus.IDLE, lastSuccessAt = Instant.parse("2026-11-25T01:00:00Z"))
    }))

    @Test fun manualDuringForegroundQueuesOneForcePassAndRepeatedClicksDoNotDuplicateIt() = runTest {
        val foregroundGate = CompletableDeferred<SyncRunResult>()
        val manualGate = CompletableDeferred<SyncRunResult>()
        var foregroundCalls = 0
        var forceCalls = 0
        val controller = HomeSyncController(backgroundScope,
            { foregroundCalls++; foregroundGate.await() },
            { forceCalls++; manualGate.await() })

        controller.onForeground()
        repeat(3) { controller.refresh(); controller.onForeground() }
        runCurrent()
        assertTrue(controller.refreshing.value)
        assertEquals(1, foregroundCalls)
        assertEquals(0, forceCalls)

        foregroundGate.complete(success)
        runCurrent()
        assertEquals(1, forceCalls)
        assertTrue(controller.refreshing.value)
        repeat(3) { controller.refresh() }
        manualGate.complete(success)
        runCurrent()
        assertEquals(1, foregroundCalls)
        assertEquals(1, forceCalls)
        assertFalse(controller.refreshing.value)
    }

    @Test fun repeatedManualRequestsShareOneActiveRequest() = runTest {
        val gate = CompletableDeferred<SyncRunResult>()
        var calls = 0
        val controller = HomeSyncController(backgroundScope, { error("前台请求不应运行") }, { calls++; gate.await() })
        repeat(4) { controller.refresh() }
        controller.onForeground()
        runCurrent()
        assertEquals(1, calls)
        gate.complete(success)
        runCurrent()
        assertFalse(controller.refreshing.value)
    }

    @Test fun loginCancelsForegroundAndItsQueuedManualPassBeforeForcingNewSession() = runTest {
        val foregroundGate = CompletableDeferred<SyncRunResult>()
        var foregroundCancelled = false
        var forceCalls = 0
        val controller = HomeSyncController(backgroundScope,
            { try { foregroundGate.await() } finally { foregroundCancelled = true } },
            { assertTrue(foregroundCancelled); forceCalls++; success })
        controller.onForeground()
        controller.refresh()

        val login = async { controller.afterLogin() }
        runCurrent()
        assertEquals(success, login.await())
        assertTrue(foregroundCancelled)
        assertEquals(1, forceCalls)
        assertFalse(controller.refreshing.value)
    }

    @Test fun loginReplacesAnActiveManualRequestWithItsOwnForceRequest() = runTest {
        val firstGate = CompletableDeferred<SyncRunResult>()
        var firstCancelled = false
        var calls = 0
        val controller = HomeSyncController(backgroundScope, { success }, {
            calls++
            if (calls == 1) try { firstGate.await() } finally { firstCancelled = true }
            else { assertTrue(firstCancelled); success }
        })
        controller.refresh()
        val login = async { controller.afterLogin() }
        runCurrent()
        assertEquals(success, login.await())
        assertEquals(2, calls)
        assertFalse(controller.refreshing.value)
    }

    @Test fun cancellingLoginAlsoCancelsOwnerScopeRequestAndClearsSpinner() = runTest {
        val gate = CompletableDeferred<SyncRunResult>()
        var cancelled = false
        var calls = 0
        val controller = HomeSyncController(backgroundScope, { success }, {
            calls++
            if (calls == 1) try { gate.await() } finally { cancelled = true } else success
        })
        val login = async { controller.afterLogin() }
        runCurrent()
        login.cancelAndJoin()
        assertTrue(cancelled)
        assertFalse(controller.refreshing.value)
        controller.refresh()
        runCurrent()
        assertEquals(2, calls)
        assertFalse(controller.refreshing.value)
    }

    @Test fun stopDropsPendingForcePassAndWaitsForActiveCancellation() = runTest {
        val gate = CompletableDeferred<SyncRunResult>()
        var cancelled = false
        var forceCalls = 0
        val controller = HomeSyncController(backgroundScope,
            { try { gate.await() } finally { cancelled = true } },
            { forceCalls++; success })
        controller.onForeground()
        controller.refresh()
        controller.stop()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(0, forceCalls)
        assertFalse(controller.refreshing.value)
        controller.stop()
    }

    @Test fun stopDuringLoginReplacementWaitsForPreviousRequestCleanup() = runTest {
        val gate = CompletableDeferred<SyncRunResult>()
        val cleanupGate = CompletableDeferred<Unit>()
        var cleanupStarted = false
        var cleanupFinished = false
        var forceCalls = 0
        val controller = HomeSyncController(backgroundScope, {
            try { gate.await() } finally {
                withContext(NonCancellable) {
                    cleanupStarted = true
                    cleanupGate.await()
                    cleanupFinished = true
                }
            }
        }, { forceCalls++; success })
        controller.onForeground()
        val login = async { controller.afterLogin() }
        runCurrent()
        assertTrue(cleanupStarted)
        val stopping = async { controller.stop() }
        runCurrent()
        assertFalse(stopping.isCompleted)
        cleanupGate.complete(Unit)
        stopping.await()
        runCurrent()
        assertTrue(cleanupFinished)
        assertTrue(login.isCancelled)
        assertEquals(0, forceCalls)
        assertFalse(controller.refreshing.value)
    }

    @Test fun manualMessagesDescribeSuccessPartialFailureAndCoordinatorRejections() = runTest {
        val partial = SyncRunResult.Finished(SyncSummary.from(listOf(
            SyncRecord(SyncModule.COURSES, status = SyncStatus.IDLE, lastSuccessAt = Instant.EPOCH),
            SyncRecord(SyncModule.TASKS, status = SyncStatus.OFFLINE, errorCode = SyncFailure.OFFLINE.name),
        )))
        val results = ArrayDeque(listOf(success, partial, SyncRunResult.InProgress, SyncRunResult.SchoolMismatch))
        val messages = mutableListOf<String>()
        val controller = HomeSyncController(backgroundScope, { success }, { results.removeFirst() })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.messages.collect { messages += it } }
        repeat(4) { controller.refresh(); runCurrent() }
        assertEquals(4, messages.size)
        assertEquals("同步完成", messages[0])
        assertTrue(messages[1].contains("作业：网络不可用"))
        assertTrue(messages[2].contains("上一次同步还在进行中"))
        assertTrue(messages[3].contains("其他学校"))
        assertFalse(controller.refreshing.value)
    }

    @Test fun unexpectedFailureHasSafeMessageAndDoesNotCancelOwnerOrFutureRefresh() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            var calls = 0
            val messages = mutableListOf<String>()
            val controller = HomeSyncController(owner, { success }, {
                calls++
                if (calls == 1) error("private-server-token") else success
            })
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.messages.collect { messages += it } }
            controller.refresh()
            runCurrent()
            assertEquals(listOf("同步失败，请稍后重试"), messages)
            assertTrue(owner.isActive)
            assertFalse(controller.refreshing.value)
            controller.refresh()
            runCurrent()
            assertEquals(2, calls)
            assertEquals("同步完成", messages.last())
        } finally { owner.cancel() }
    }

    @Test fun ownerCancellationClearsBusyStateAndDoesNotRunQueuedForceRequest() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<SyncRunResult>()
        var forceCalls = 0
        val controller = HomeSyncController(owner, { gate.await() }, { forceCalls++; success })
        controller.onForeground()
        controller.refresh()
        owner.cancel()
        runCurrent()
        assertEquals(0, forceCalls)
        assertFalse(controller.refreshing.value)
    }

    @Test fun automaticModuleFailuresStayInModuleStateWithoutManualSuccessMessage() = runTest {
        val failure = SyncRunResult.Finished(SyncSummary.from(SyncModule.entries.map {
            SyncRecord(it, status = SyncStatus.OFFLINE, errorCode = SyncFailure.OFFLINE.name)
        }))
        val messages = mutableListOf<String>()
        val controller = HomeSyncController(backgroundScope, { failure }, { failure })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.messages.collect { messages += it } }
        controller.onForeground()
        runCurrent()
        assertTrue(messages.isEmpty())
        assertEquals(failure, controller.afterLogin())
        assertTrue(messages.isEmpty())
        assertFalse(controller.refreshing.value)
    }
}
