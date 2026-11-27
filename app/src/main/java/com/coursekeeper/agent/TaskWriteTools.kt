package com.coursekeeper.agent

import com.coursekeeper.data.DataError
import com.coursekeeper.data.DataException
import com.coursekeeper.data.TaskDraft
import com.coursekeeper.data.model.CourseRecord
import com.coursekeeper.data.model.TaskRecord
import com.coursekeeper.reminder.reminderSlots
import com.coursekeeper.task.label
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 任务写工具集（A03）：预览 → 用户确认 → 本地校验凭证 → 写库。
 *
 * 分工：
 * - 预览只读快照，校验字段合法性，输出前后值、风险提示与一次性凭证；
 * - 凭证只存内存（[pending]），绑定工具/任务/预期版本/变更指纹，5 分钟过期、用一次即失效；
 * - 确认先原子取出凭证再调 [TaskWriteGateway]，requestId 由 token 派生，重复请求不会二次生效。
 *
 * 单实例约束（Q-3）：凭证只存本实例内存，**必须应用级单例持有**（如 CourseKeeperData）；
 * 旋转/每页新建实例会使屏上预览的凭证全部失效（confirm 恒报 TOKEN_MISMATCH）。
 * 关闭 AI / 切换会话时调 [revokeAll] 作废全部在途预览（R08/R11）。
 *
 * 接线红线（Q-1）：模型工具只注册 `preview*`；[confirm] 仅由确认 UI 回调调用；
 * 给模型只传 [TaskWriteModelView]（[TaskWritePreview.toModelView] 裁剪），不得透出 token/binding。
 * wire 级参数/错误码规格见 [TaskWriteWire]（Q-2）。
 *
 * 时间口径：预览与过期判断共用 [now]（设备本地时区）；completedAt 由 Repository 用其 clock 写入，
 * 本类不自行拼时间。
 */
class TaskWriteTools(
    private val gateway: TaskWriteGateway,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) {

    /** token -> 待确认操作；LinkedHashMap 保持插入顺序，便于按最旧淘汰。 */
    private val pending = LinkedHashMap<String, Pending>()

    // ---------------------------------------------------------------- 预览

    suspend fun previewCreate(draft: TaskDraft): TaskWritePreviewResult {
        val snapshot = gateway.snapshot()
        val clean = normalize(draft)
        draftFailure(clean, snapshot.courses, retainedCourseId = null)?.let { return rejected(it) }
        return issue(
            tool = TaskWriteTool.CREATE,
            target = null,
            draft = clean,
            completed = null,
            expectedVersion = null,
            changes = listOf(
                TaskWriteChange(FIELD_TITLE, null, clean.title),
                TaskWriteChange(FIELD_TYPE, null, clean.type.label),
                TaskWriteChange(FIELD_DUE, null, dueLabel(clean.dueAt)),
                TaskWriteChange(FIELD_COURSE, null, courseLabel(clean.courseId, snapshot.courses)),
            ),
            risks = buildList {
                if (clean.dueAt != null) addAll(dueRisks(clean.dueAt, now()))
            },
        )
    }

    suspend fun previewUpdate(
        id: String,
        expectedVersion: Long,
        changes: TaskDraft,
    ): TaskWritePreviewResult {
        val snapshot = gateway.snapshot()
        val old = snapshot.tasks.firstOrNull { it.id == id }
            ?: return rejected(TaskWriteFailure(TaskWriteError.NOT_FOUND, "任务不存在，可能已被删除"))
        // 调用方拿着过期版本就不必再预览：当场拒绝比"确认时才冲突"更早止损。
        if (expectedVersion != old.version) return rejected(
            TaskWriteFailure(TaskWriteError.VERSION_CONFLICT, "任务已被修改（当前版本 ${old.version}），请刷新后重新预览"),
        )
        val clean = normalize(changes)
        // 与 Repository 同口径：课程被删后沿用旧关联允许保留，新关联必须存在。
        draftFailure(clean, snapshot.courses, retainedCourseId = old.courseId)?.let { return rejected(it) }
        val fieldChanges = buildList {
            if (clean.title != old.title) add(TaskWriteChange(FIELD_TITLE, old.title, clean.title))
            if (clean.type != old.type) add(TaskWriteChange(FIELD_TYPE, old.type.label, clean.type.label))
            // after=null 表示"将被清空"（Q-4）：确认 UI 据此醒目提示，不依赖风险文案。
            if (clean.dueAt != old.dueAt) {
                add(TaskWriteChange(FIELD_DUE, dueLabel(old.dueAt), clean.dueAt?.let(::dueLabel)))
            }
            if (clean.courseId != old.courseId) {
                add(
                    TaskWriteChange(
                        FIELD_COURSE,
                        courseLabel(old.courseId, snapshot.courses),
                        clean.courseId?.let { courseLabel(it, snapshot.courses) },
                    ),
                )
            }
        }
        // 全量替换语义（保持不变）：漏传字段 = 清空该字段，因此逐项给出后果明确的文案。
        val risks = buildList {
            when {
                old.dueAt != null && clean.dueAt == null ->
                    add("将清空截止时间：该任务不再产生到点提醒，已登记的提醒将取消")
                old.dueAt == null && clean.dueAt != null -> add("将新增截止时间")
                clean.dueAt != old.dueAt -> add("截止时间变更后，提醒将按新时间重排")
            }
            if (clean.dueAt != null && clean.dueAt != old.dueAt) addAll(dueRisks(clean.dueAt, now()))
            when {
                old.courseId != null && clean.courseId == null ->
                    add("将解除课程关联（任务本身保留）")
                old.courseId == null && clean.courseId != null ->
                    add("将关联课程「${courseLabel(clean.courseId, snapshot.courses)}」")
                clean.courseId != old.courseId && clean.courseId != null ->
                    add("课程关联将更换为「${courseLabel(clean.courseId, snapshot.courses)}」")
            }
            if (fieldChanges.isEmpty()) add("内容与当前一致；确认仍会生成新版本（version+1）")
        }
        return issue(
            tool = TaskWriteTool.UPDATE,
            target = TaskWriteTarget(old.id, old.title, old.version),
            draft = clean,
            completed = null,
            expectedVersion = expectedVersion,
            changes = fieldChanges,
            risks = risks,
        )
    }

    /** [completed] = true 标记完成、false 恢复待办（v1.1：completedAt 由 Repository 写/清）。 */
    suspend fun previewComplete(
        id: String,
        completed: Boolean,
        expectedVersion: Long,
    ): TaskWritePreviewResult {
        val snapshot = gateway.snapshot()
        val task = snapshot.tasks.firstOrNull { it.id == id }
            ?: return rejected(TaskWriteFailure(TaskWriteError.NOT_FOUND, "任务不存在，可能已被删除"))
        if (expectedVersion != task.version) return rejected(
            TaskWriteFailure(TaskWriteError.VERSION_CONFLICT, "任务已被修改（当前版本 ${task.version}），请刷新后重新预览"),
        )
        val before = statusLabel(task)
        val after = if (completed) STATUS_DONE else STATUS_PENDING
        val risks = when {
            completed && task.completedAt != null -> listOf("任务已是完成状态，确认不会产生变更")
            !completed && task.completedAt == null -> listOf("任务已是待办状态，确认不会产生变更")
            !completed -> listOf("该任务已完成，确认后将恢复为待办（completedAt 清空）")
            else -> emptyList()
        }
        return issue(
            tool = TaskWriteTool.COMPLETE,
            target = TaskWriteTarget(task.id, task.title, task.version),
            draft = null,
            completed = completed,
            expectedVersion = expectedVersion,
            changes = listOf(TaskWriteChange(FIELD_STATUS, before, after)),
            risks = risks,
        )
    }

    // ---------------------------------------------------------------- 确认

    /**
     * 校验并执行一次预览。[binding] 可选：调用方回传预览里的绑定做二次校验，
     * 不符（换任务、改指纹）直接 [TaskWriteError.TOKEN_MISMATCH]，且不消耗凭证。
     */
    suspend fun confirm(token: String, binding: TaskWriteBinding? = null): TaskWriteResult {
        val failure = consume(token, binding) { op -> execute(op, token) }
        return failure
    }

    /** 失败快路径：先给出结构化错误；成功则返回 Success(receipt)。 */
    private suspend fun consume(
        token: String,
        binding: TaskWriteBinding?,
        run: suspend (Pending) -> TaskWriteResult,
    ): TaskWriteResult {
        var problem: TaskWriteFailure? = null
        var operation: Pending? = null
        val nowMillis = now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        synchronized(pending) {
            val entry = pending[token]
            when {
                entry == null -> problem = TaskWriteFailure(TaskWriteError.TOKEN_MISMATCH, "确认凭证无效或已使用，请重新预览")
                nowMillis >= entry.expiresAtMillis -> {
                    pending.remove(token)
                    problem = TaskWriteFailure(TaskWriteError.EXPIRED_TOKEN, "确认凭证已过期（5 分钟），请重新预览")
                }
                binding != null && !binding.matches(entry) ->
                    problem = TaskWriteFailure(TaskWriteError.TOKEN_MISMATCH, "确认凭证与本次操作不匹配，请重新预览")
                else -> {
                    // 原子取出：并发/重复调用只有一个能拿到 entry。
                    pending.remove(token)
                    operation = entry
                }
            }
        }
        problem?.let { return TaskWriteResult.Failure(it) }
        val op = operation ?: return TaskWriteResult.Failure(
            TaskWriteFailure(TaskWriteError.TOKEN_MISMATCH, "确认凭证无效或已使用，请重新预览"),
        )
        return run(op)
    }

    private suspend fun execute(op: Pending, token: String): TaskWriteResult = try {
        val requestId = REQUEST_PREFIX + token
        val receipt = when (op.tool) {
            TaskWriteTool.CREATE -> gateway.create(op.draft!!, requestId)
            TaskWriteTool.UPDATE -> gateway.update(op.targetId!!, op.expectedVersion!!, op.draft!!, requestId)
            TaskWriteTool.COMPLETE -> gateway.setCompleted(op.targetId!!, op.completed!!, op.expectedVersion!!, requestId)
        }
        TaskWriteResult.Success(receipt)
    } catch (error: DataException) {
        TaskWriteResult.Failure(error.toFailure())
    }

    // ---------------------------------------------------------------- 撤销

    /**
     * 撤销一张在途预览的凭证（Q-5）。返回是否撤到；false = 已使用/已过期/本来不存在——
     * 撤没撤到，后续 confirm 都得 TOKEN_MISMATCH，调用方无需区分。
     */
    fun revoke(token: String): Boolean = synchronized(pending) { pending.remove(token) != null }

    /** 作废全部在途预览凭证（R08/R11：关闭 AI、切换会话时调用）。进程死亡等价于 revokeAll——凭证不落盘。 */
    fun revokeAll() {
        synchronized(pending) { pending.clear() }
    }

    // ---------------------------------------------------------------- 内部

    private fun issue(
        tool: TaskWriteTool,
        target: TaskWriteTarget?,
        draft: TaskDraft?,
        completed: Boolean?,
        expectedVersion: Long?,
        changes: List<TaskWriteChange>,
        risks: List<String>,
    ): TaskWritePreviewResult {
        val token = newToken()
        val nowMillis = now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val expiresAt = nowMillis + TOKEN_TTL_MILLIS
        val fingerprint = fingerprint(tool, target?.id, draft, completed)
        val binding = TaskWriteBinding(tool, target?.id, expectedVersion, fingerprint)
        synchronized(pending) {
            pruneLocked(nowMillis)
            while (pending.size >= MAX_PENDING) {
                val oldest = pending.keys.firstOrNull() ?: break
                pending.remove(oldest)
            }
            pending[token] = Pending(tool, target?.id, expectedVersion, draft, completed, fingerprint, expiresAt)
        }
        return TaskWritePreviewResult.Ready(
            TaskWritePreview(tool, target, changes, risks, token, expiresAt, binding),
        )
    }

    private fun pruneLocked(nowMillis: Long) {
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            if (nowMillis >= iterator.next().value.expiresAtMillis) iterator.remove()
        }
    }

    private fun normalize(draft: TaskDraft) = draft.copy(
        title = draft.title.trim(),
        courseId = draft.courseId?.takeIf { it.isNotBlank() },
    )

    private fun draftFailure(
        draft: TaskDraft,
        courses: List<CourseRecord>,
        retainedCourseId: String?,
    ): TaskWriteFailure? = when {
        draft.title.isBlank() -> TaskWriteFailure(TaskWriteError.INVALID_FIELD, "任务名称不能为空")
        draft.title.length > TaskWriteWire.TITLE_MAX_LENGTH ->
            TaskWriteFailure(TaskWriteError.INVALID_FIELD, "任务名称过长（≤${TaskWriteWire.TITLE_MAX_LENGTH} 字）")
        draft.courseId != null && draft.courseId != retainedCourseId && courses.none { it.id == draft.courseId } ->
            TaskWriteFailure(TaskWriteError.INVALID_FIELD, "关联的课程不存在")
        else -> null
    }

    /** 风险提示按提醒口径（截止−24h / −1h，已过槽位不追发）代码计算，不解析自然语言。 */
    private fun dueRisks(dueAt: LocalDateTime, now: LocalDateTime): List<String> {
        if (!dueAt.isAfter(now)) return listOf("截止时间已过，不会产生到点提醒")
        return when (reminderSlots(dueAt, now).size) {
            0 -> listOf("截止不足 1 小时：两个提醒槽位均已过去，不会到点提醒")
            1 -> listOf("截止不足 24 小时：只有 1 小时槽位会提醒")
            else -> emptyList()
        }
    }

    private fun fingerprint(
        tool: TaskWriteTool,
        targetId: String?,
        draft: TaskDraft?,
        completed: Boolean?,
    ): String = sha256(
        listOf(
            tool.wireName,
            targetId ?: "-",
            draft?.title ?: "-",
            draft?.type?.name ?: "-",
            draft?.dueAt?.toString() ?: "-",
            draft?.courseId ?: "-",
            completed?.toString() ?: "-",
        ).joinToString("|"),
    )

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun dueLabel(dueAt: LocalDateTime?): String = dueAt?.format(DUE_FORMAT) ?: DUE_PENDING

    private fun courseLabel(courseId: String?, courses: List<CourseRecord>): String =
        courseId?.let { id -> courses.firstOrNull { it.id == id }?.title ?: id } ?: COURSE_NONE

    private fun statusLabel(task: TaskRecord): String =
        if (task.completedAt != null) STATUS_DONE else STATUS_PENDING

    private fun TaskWriteBinding.matches(op: Pending): Boolean =
        tool == op.tool &&
            targetId == op.targetId &&
            expectedVersion == op.expectedVersion &&
            changeFingerprint == op.fingerprint

    private fun rejected(failure: TaskWriteFailure) = TaskWritePreviewResult.Rejected(failure)

    private fun DataException.toFailure(): TaskWriteFailure = when (code) {
        DataError.TASK_NOT_FOUND -> TaskWriteFailure(TaskWriteError.NOT_FOUND, "任务不存在，可能已被删除")
        DataError.VERSION_CONFLICT -> TaskWriteFailure(TaskWriteError.VERSION_CONFLICT, "任务已被修改，请重新预览后再确认")
        DataError.REQUEST_ID_REUSED -> TaskWriteFailure(TaskWriteError.TOKEN_MISMATCH, "确认凭证已用于其他操作")
        DataError.EMPTY_TITLE, DataError.TITLE_TOO_LONG, DataError.COURSE_NOT_FOUND,
        DataError.INVALID_RECORD, DataError.SYNC_IDENTITY_CONFLICT ->
            TaskWriteFailure(TaskWriteError.INVALID_FIELD, code.userMessage)
    }

    private class Pending(
        val tool: TaskWriteTool,
        val targetId: String?,
        val expectedVersion: Long?,
        val draft: TaskDraft?,
        val completed: Boolean?,
        val fingerprint: String,
        val expiresAtMillis: Long,
    )

    private companion object {
        const val TOKEN_TTL_MILLIS = 5L * 60 * 1000
        const val MAX_PENDING = 64
        const val REQUEST_PREFIX = "agent-write:"

        const val FIELD_TITLE = "title"
        const val FIELD_TYPE = "type"
        const val FIELD_DUE = "dueAt"
        const val FIELD_COURSE = "courseId"
        const val FIELD_STATUS = "status"

        const val STATUS_PENDING = "待办"
        const val STATUS_DONE = "已完成"
        const val DUE_PENDING = "截止待定"
        const val COURSE_NONE = "未关联"

        val DUE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
