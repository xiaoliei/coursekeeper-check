package com.coursekeeper.data.sync

import com.coursekeeper.data.CourseKeeperRepository
import com.coursekeeper.data.DataError
import com.coursekeeper.data.DataException
import com.coursekeeper.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.time.Clock
import java.time.Duration

data class SyncSummary(val status: SyncStatus, val modules: List<SyncRecord>) {
    companion object {
        fun from(records: List<SyncRecord>): SyncSummary {
            val modules = SyncModule.entries.map { module -> records.singleOrNull { it.module == module } ?: SyncRecord(module) }
            val statuses = modules.map { it.status }
            val failed = statuses.any { it in setOf(SyncStatus.OFFLINE, SyncStatus.EXPIRED, SyncStatus.ERROR) }
            val status = when {
                SyncStatus.SYNCING in statuses -> SyncStatus.SYNCING
                failed && modules.any { it.status == SyncStatus.IDLE && it.lastSuccessAt != null } -> SyncStatus.PARTIAL
                SyncStatus.EXPIRED in statuses -> SyncStatus.EXPIRED
                statuses.all { it == SyncStatus.OFFLINE } -> SyncStatus.OFFLINE
                failed -> SyncStatus.ERROR
                else -> SyncStatus.IDLE
            }
            return SyncSummary(status, modules)
        }
    }
}

sealed interface SyncRunResult {
    data class Finished(val summary: SyncSummary, val throttled: Set<SyncModule> = emptySet()) : SyncRunResult
    data object InProgress : SyncRunResult
    data object SchoolMismatch : SyncRunResult
}

/** One application-scoped coordinator. No HTTP or school-specific parsing belongs here. */
class SyncCoordinator(
    private val repository: CourseKeeperRepository,
    private val adapter: SchoolAdapter,
    private val clock: Clock = Clock.systemUTC(),
    private val requestTimeoutMillis: Long = 30_000,
    private val retryDelayMillis: Long = 500,
) {
    private val mutex = Mutex()
    val state = repository.snapshots.map { SyncSummary.from(it.syncRecords) }.distinctUntilChanged()

    init {
        require(adapter.schoolId.isNotBlank())
        require(requestTimeoutMillis > 0 && retryDelayMillis >= 0)
    }

    /** A calls after cached content is available; cancellation follows the caller's lifecycle. */
    suspend fun onForeground(): SyncRunResult {
        delay(400)
        return synchronize()
    }

    /** Pull-to-refresh uses force=true. A concurrent call never starts a second network run. */
    suspend fun synchronize(force: Boolean = false): SyncRunResult {
        if (!mutex.tryLock()) return SyncRunResult.InProgress
        try {
            val baseline = repository.snapshot()
            if (baseline.syncRecords.any { it.schoolId != null && it.schoolId != adapter.schoolId }) {
                return SyncRunResult.SchoolMismatch
            }
            val previous = SyncModule.entries.associateWith { module ->
                baseline.syncRecords.singleOrNull { it.module == module } ?: SyncRecord(module, schoolId = adapter.schoolId)
            }
            val now = clock.instant()
            val throttled = if (force) emptySet() else previous.filterValues { record ->
                record.lastSuccessAt?.let { at ->
                    val age = Duration.between(at, now)
                    !age.isNegative && age < Duration.ofMinutes(30)
                } == true
            }.keys

            // Detect expiry even within the throttle window, without re-fetching business data.
            val sessionFailure = checkSession()
            if (sessionFailure != null) {
                for (module in SyncModule.entries) fail(previous.getValue(module), sessionFailure, now)
                return finished(throttled)
            }
            for (module in SyncModule.entries.filterNot { it in throttled }) {
                val old = previous.getValue(module)
                repository.saveSync(old.copy(schoolId = adapter.schoolId, status = SyncStatus.SYNCING,
                    lastAttemptAt = now, errorCode = null))
                when (module) {
                    SyncModule.COURSES -> handle(old, now, { adapter.fetchCourses() }) {
                        repository.mergeCourses(it, baseline, clock.instant())
                    }
                    SyncModule.TASKS -> handle(old, now, { adapter.fetchTasks() }) {
                        repository.mergeTasks(it, baseline, clock.instant())
                    }
                }
            }
            // A successful lightweight check clears a previous network/expiry banner for throttled data.
            for (module in throttled) {
                val old = previous.getValue(module)
                repository.saveSync(old.copy(schoolId = adapter.schoolId, status = SyncStatus.IDLE, errorCode = null))
            }
            return finished(throttled)
        } catch (cancel: CancellationException) {
            // Interrupted work must not leave a permanent "syncing" banner.
            withContext(NonCancellable) {
                repository.snapshot().syncRecords.filter { it.status == SyncStatus.SYNCING }.forEach {
                    repository.saveSync(it.copy(status = SyncStatus.ERROR, errorCode = SyncFailure.INTERRUPTED.name))
                }
            }
            throw cancel
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun checkSession(): SyncFailure? = try {
        when (withTimeout(requestTimeoutMillis) { adapter.checkSession() }) {
            SessionState.VALID -> null
            SessionState.EXPIRED -> SyncFailure.EXPIRED
            SessionState.OFFLINE -> SyncFailure.OFFLINE
        }
    } catch (_: TimeoutCancellationException) { SyncFailure.TIMEOUT }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: IOException) { SyncFailure.OFFLINE }
    catch (_: Exception) { SyncFailure.REMOTE_ERROR }

    private suspend fun <T> handle(
        old: SyncRecord, at: java.time.Instant,
        fetch: suspend () -> ModuleResult<T>, merge: suspend (List<T>) -> MergeCounts,
    ) {
        when (val result = fetchWithRetry(fetch)) {
            is ModuleResult.Failure -> fail(old, result.reason, at)
            is ModuleResult.Success -> try { merge(result.records) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: DataException) {
                fail(old, if (error.code == DataError.VERSION_CONFLICT) SyncFailure.VERSION_CONFLICT else SyncFailure.INVALID_DATA, at)
            }
            catch (_: Exception) { fail(old, SyncFailure.INVALID_DATA, at) }
        }
    }

    private suspend fun <T> fetchWithRetry(fetch: suspend () -> ModuleResult<T>): ModuleResult<T> {
        repeat(3) { attempt ->
            val result = try { withTimeout(requestTimeoutMillis) { fetch() } }
            catch (_: TimeoutCancellationException) { ModuleResult.Failure(SyncFailure.TIMEOUT, retryable = true) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: IOException) { ModuleResult.Failure(SyncFailure.OFFLINE, retryable = true) }
            catch (_: Exception) { ModuleResult.Failure(SyncFailure.REMOTE_ERROR) }
            val retry = result is ModuleResult.Failure && result.retryable &&
                result.reason in setOf(SyncFailure.OFFLINE, SyncFailure.TIMEOUT, SyncFailure.REMOTE_ERROR)
            if (!retry || attempt == 2) return result
            delay(retryDelayMillis * (attempt + 1))
        }
        error("Unreachable")
    }

    private suspend fun fail(old: SyncRecord, reason: SyncFailure, at: java.time.Instant) {
        val status = when (reason) {
            SyncFailure.OFFLINE -> SyncStatus.OFFLINE
            SyncFailure.EXPIRED -> SyncStatus.EXPIRED
            else -> SyncStatus.ERROR
        }
        repository.saveSync(old.copy(schoolId = adapter.schoolId, status = status, lastAttemptAt = at, errorCode = reason.name))
    }

    private suspend fun finished(throttled: Set<SyncModule>) =
        SyncRunResult.Finished(SyncSummary.from(repository.snapshot().syncRecords), throttled)
}
