package com.coursekeeper.agent

import com.coursekeeper.data.CourseKeeperRepository
import com.coursekeeper.data.TaskDraft
import com.coursekeeper.data.WriteReceipt

/**
 * [TaskWriteGateway] 的正式实现：转调 E 的 Repository，三个写操作都把 requestId 透传下去。
 *
 * 放在 agent 包但只依赖数据层；工具逻辑（[TaskWriteTools]）本身不引用 Repository，便于单测替换。
 */
class RepositoryTaskWriteGateway(private val repository: CourseKeeperRepository) : TaskWriteGateway {

    override suspend fun snapshot(): TaskWriteSnapshot {
        val snap = repository.snapshot()
        return TaskWriteSnapshot(tasks = snap.tasks, courses = snap.courses)
    }

    override suspend fun create(draft: TaskDraft, requestId: String): WriteReceipt =
        repository.createTask(draft, requestId = requestId)

    override suspend fun update(
        id: String,
        expectedVersion: Long,
        changes: TaskDraft,
        requestId: String,
    ): WriteReceipt = repository.updateTask(id, expectedVersion, changes, requestId = requestId)

    override suspend fun setCompleted(
        id: String,
        completed: Boolean,
        expectedVersion: Long,
        requestId: String,
    ): WriteReceipt = repository.setTaskCompleted(id, completed, expectedVersion, requestId = requestId)
}
