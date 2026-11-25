package com.coursekeeper.home

import com.coursekeeper.afterLoginFailureMessage
import com.coursekeeper.data.sync.SyncRunResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Coordinates A's entry points while E owns synchronization, retries and persistence. */
class HomeSyncController(
    private val scope: CoroutineScope,
    private val foregroundRequest: suspend () -> SyncRunResult,
    private val forceRequest: suspend () -> SyncRunResult,
) {
    private enum class Trigger { FOREGROUND, MANUAL, LOGIN }

    private class Request(val trigger: Trigger) {
        val result = CompletableDeferred<SyncRunResult>()
        var job: Job? = null
    }

    private val stateMutex = Mutex()
    private var active: Request? = null
    private var manualPending = false
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    fun onForeground() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            stateMutex.withLock {
                if (active == null && scope.isActive) start(Request(Trigger.FOREGROUND))
            }
        }
    }

    fun refresh() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            stateMutex.withLock {
                when (active?.trigger) {
                    null -> if (scope.isActive) start(Request(Trigger.MANUAL))
                    Trigger.FOREGROUND -> manualPending = true
                    Trigger.MANUAL, Trigger.LOGIN -> Unit
                }
            }
        }
    }

    /** Login needs its own force request using the newly accepted school session. */
    suspend fun afterLogin(): SyncRunResult {
        val request = Request(Trigger.LOGIN)
        stateMutex.withLock {
            if (!scope.isActive) throw CancellationException("同步作用域已关闭")
            val previous = active
            manualPending = false
            start(request, previous)
        }
        try {
            val result = request.result.await()
            request.job?.join()
            return result
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { removeAndCancel(request) }
            throw cancel
        }
    }

    /** Called before logout clears school cookies and settings. */
    suspend fun stop() {
        val previous = stateMutex.withLock {
            val old = active
            active = null
            manualPending = false
            _refreshing.value = false
            old
        }
        cancel(previous)
    }

    /** The state mutex is held while a request is installed, never while awaiting its job. */
    private fun start(request: Request, previous: Request? = null) {
        if (!scope.isActive) {
            request.result.cancel()
            if (active === request) active = null
            _refreshing.value = false
            return
        }
        active = request
        _refreshing.value = true
        request.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                cancel(previous)
                currentCoroutineContext().ensureActive()
                val result = if (request.trigger == Trigger.FOREGROUND) foregroundRequest() else forceRequest()
                currentCoroutineContext().ensureActive()
                stateMutex.withLock {
                    if (active === request) publishResult(request.trigger, result)
                }
                request.result.complete(result)
            } catch (cancel: CancellationException) {
                request.result.cancel(cancel)
                throw cancel
            } catch (_: Exception) {
                val message = "同步失败，请稍后重试"
                if (currentCoroutineContext().isActive) {
                    stateMutex.withLock {
                        if (active === request && request.trigger != Trigger.LOGIN) messageChannel.trySend(message)
                    }
                    request.result.completeExceptionally(IllegalStateException(message))
                } else {
                    request.result.cancel()
                }
            } finally {
                withContext(NonCancellable) {
                    // Logout must also wait for a request being replaced by login to finish cancelling.
                    cancel(previous)
                    finish(request)
                }
            }
        }
    }

    private fun publishResult(trigger: Trigger, result: SyncRunResult) {
        when {
            trigger == Trigger.MANUAL ->
                messageChannel.trySend(afterLoginFailureMessage(result) ?: "同步完成")
            trigger == Trigger.FOREGROUND && result == SyncRunResult.SchoolMismatch ->
                afterLoginFailureMessage(result)?.let { messageChannel.trySend(it) }
        }
    }

    private suspend fun finish(request: Request) {
        stateMutex.withLock {
            if (active !== request) return
            active = null
            if (manualPending && scope.isActive) {
                manualPending = false
                start(Request(Trigger.MANUAL))
            } else {
                manualPending = false
                _refreshing.value = false
            }
        }
    }

    private suspend fun removeAndCancel(request: Request) {
        stateMutex.withLock {
            if (active === request) {
                active = null
                manualPending = false
                _refreshing.value = false
            }
        }
        cancel(request)
    }

    private suspend fun cancel(request: Request?) {
        request?.result?.cancel()
        request?.job?.cancelAndJoin()
    }
}
