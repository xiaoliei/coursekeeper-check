package com.coursekeeper.data.sync

import com.coursekeeper.data.CourseDraft
import com.coursekeeper.data.TaskDraft

/** C implements official login/session/parsing; this contract contains no password or cookie. */
interface SchoolAdapter {
    val schoolId: String
    suspend fun checkSession(): SessionState
    suspend fun fetchCourses(): ModuleResult<ImportedCourse>
    suspend fun fetchTasks(): ModuleResult<ImportedTask>
}

enum class SessionState { VALID, EXPIRED, OFFLINE }
enum class SyncFailure { OFFLINE, EXPIRED, TIMEOUT, PARSE_ERROR, REMOTE_ERROR, VERSION_CONFLICT, INVALID_DATA, INTERRUPTED }

sealed interface ModuleResult<out T> {
    data class Success<T>(val records: List<T>) : ModuleResult<T>
    data class Failure(val reason: SyncFailure, val retryable: Boolean = false) : ModuleResult<Nothing>
}

data class ImportedCourse(val externalId: String, val fields: CourseDraft)

/** externalId must be explicitly supplied by C from a verified stable school identifier.
 * Missing IDs are counted and deferred; E does not invent title/time hashes or enable unsafe dedup.
 * courseExternalId is resolved against locally stored SYNC courses; unresolved links remain null.
 */
data class ImportedTask(val externalId: String?, val fields: TaskDraft, val courseExternalId: String? = null)

data class MergeCounts(val inserted: Int = 0, val updated: Int = 0, val protectedCount: Int = 0, val missingIdentity: Int = 0)
