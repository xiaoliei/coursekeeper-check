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
    /** Only a verified, complete course response may authorize removal of absent courses. */
    data class Success<T>(val records: List<T>, val completeSnapshot: Boolean = false) : ModuleResult<T>
    data class Failure(val reason: SyncFailure, val retryable: Boolean = false) : ModuleResult<Nothing>
}

data class ImportedCourse(val externalId: String, val fields: CourseDraft)

/** externalId must be explicitly supplied by C from a verified stable school identifier.
 * Missing IDs are counted and deferred; E does not invent title/time hashes or enable unsafe dedup.
 * courseExternalId is resolved against locally stored SYNC courses; unresolved links remain null.
 *
 * 以下三个字段是 C 从学校页面原样提取的**来源信息**，E 目前不落库（CD-01 / CBE-01 交接用）：
 * - [sourceCourseId] / [sourceCourseName]：学校侧课程的稳定标识与名称。MYSTU 的课程 ID 与教务
 *   课程编号是两套体系，不能塞进 [courseExternalId] 冒充已解析的关联；映射由 B/E 约定后再消费。
 * - [schoolSubmission]：学校侧提交状态（`SUBMITTED` / `NOT_SUBMITTED` / `DRAFT` /
 *   `REOPENED` / `UNKNOWN`），供 D/E 确定“学校已提交”与本地完成、逾期的处理口径。
 */
data class ImportedTask(
    val externalId: String?,
    val fields: TaskDraft,
    val courseExternalId: String? = null,
    val sourceCourseId: String? = null,
    val sourceCourseName: String? = null,
    val schoolSubmission: String? = null,
)

data class MergeCounts(
    val inserted: Int = 0,
    val updated: Int = 0,
    /** 同步时清理掉的“远端已不存在”的残留课程数（镜像口径，见 mergeCourses）。 */
    val deleted: Int = 0,
    val protectedCount: Int = 0,
    val missingIdentity: Int = 0,
)
