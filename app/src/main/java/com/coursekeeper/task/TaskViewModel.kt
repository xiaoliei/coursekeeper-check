package com.coursekeeper.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursekeeper.data.CourseKeeperRepository
import com.coursekeeper.data.DataException
import com.coursekeeper.data.TaskDraft
import com.coursekeeper.data.WriteReceipt
import com.coursekeeper.data.model.CourseRecord
import com.coursekeeper.data.model.TaskRecord
import com.coursekeeper.data.model.TaskType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * 任务侧 UI 契约（成员 D）。业务规则权威校验在 Repository（E 的数据层），
 * 这里只做界面状态与错误文案。v1.0 §3：界面不重复实现业务规则。
 */
interface TaskGateway {
    val data: Flow<TaskData>

    suspend fun create(draft: TaskDraft, requestId: String? = null): WriteReceipt
    suspend fun update(id: String, expectedVersion: Long, changes: TaskDraft): WriteReceipt
    suspend fun setCompleted(id: String, completed: Boolean, expectedVersion: Long): WriteReceipt
    suspend fun delete(id: String, expectedVersion: Long): WriteReceipt
}

data class TaskData(
    val tasks: List<TaskRecord> = emptyList(),
    val courses: List<CourseRecord> = emptyList(),
)

/** 把 E 的 Repository 适配成任务页需要的窄接口。 */
class RepositoryTaskGateway(private val repository: CourseKeeperRepository) : TaskGateway {
    override val data: Flow<TaskData> = repository.snapshots.map { TaskData(it.tasks, it.courses) }

    override suspend fun create(draft: TaskDraft, requestId: String?) =
        repository.createTask(draft, requestId = requestId)

    override suspend fun update(id: String, expectedVersion: Long, changes: TaskDraft) =
        repository.updateTask(id, expectedVersion, changes)

    override suspend fun setCompleted(id: String, completed: Boolean, expectedVersion: Long) =
        repository.setTaskCompleted(id, completed, expectedVersion)

    override suspend fun delete(id: String, expectedVersion: Long) =
        repository.deleteTask(id, expectedVersion)
}

data class TaskUiState(
    val tasks: List<TaskView> = emptyList(),
    val courses: List<CourseRecord> = emptyList(),
    val statusFilter: TaskStatusFilter = TaskStatusFilter.OPEN,
    val typeFilter: TaskType? = null,
    val openCount: Int = 0,
    val doneCount: Int = 0,
    val dateFilter: TaskDateFilter = TaskDateFilter.ALL,
    /** 批量选择（W5）：当前视图内被勾选的任务 id。非空即处于选择模式。 */
    val selectedIds: Set<String> = emptySet(),
) {
    val totalCount: Int get() = openCount + doneCount

    /** 「全选/取消全选」按钮的标签依据：当前可见任务已全部选中。 */
    val allVisibleSelected: Boolean get() = tasks.isNotEmpty() && tasks.all { it.record.id in selectedIds }
}

sealed interface TaskEvent {
    /** undoVersion 是完成动作回执里的新版本号；撤销必须用它提交（E：提交用户实际看见的版本）。 */
    data class Message(
        val text: String,
        val undoTaskId: String? = null,
        val undoCompleted: Boolean = false,
        val undoVersion: Long? = null,
    ) : TaskEvent

    /** 回执供第 3 周提醒接线：只在 changed && !replayed 时驱动。 */
    data class Saved(val receipt: WriteReceipt) : TaskEvent
    data class Deleted(val receipt: WriteReceipt) : TaskEvent
}

class TaskViewModel(
    private val gateway: TaskGateway,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) : ViewModel() {

    private val query = MutableStateFlow(TaskQuery())
    /** 批量选择（W5）：存任务 id。版本号不在此冻结，删除时按提交瞬间的快照版本走，
     *  与单条删除同一口径：界面看见什么版本就提交什么版本。 */
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val eventChannel = Channel<TaskEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private val data: StateFlow<TaskData> =
        gateway.data.stateIn(viewModelScope, SharingStarted.Eagerly, TaskData())

    /** 每分钟重发一次，驱动"已逾期 / 今天 / 明天"文案随时间刷新（跨零点、跨截止时刻）。 */
    private val minuteTick = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    val uiState: StateFlow<TaskUiState> =
        combine(data, query, minuteTick, selection) { snapshot, selected, _, selectedIds ->
            val stamp = now()
            val views = snapshot.tasks.sortedForDisplay().map { it.toView(snapshot.courses, stamp) }
            val visible = views.visible(selected.status, selected.type, selected.date, stamp)
            TaskUiState(
                tasks = visible,
                courses = snapshot.courses,
                statusFilter = selected.status,
                typeFilter = selected.type,
                dateFilter = selected.date,
                openCount = views.count { it.status != TaskStatus.COMPLETED },
                doneCount = views.count { it.status == TaskStatus.COMPLETED },
                // 后台快照变化（同步删除、状态变化移出视图）时，选中集随当前视图自动收缩
                selectedIds = pruneSelection(selectedIds, visible),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskUiState())

    fun setStatusFilter(value: TaskStatusFilter) { exitSelection(); query.update { it.copy(status = value) } }

    fun setTypeFilter(value: TaskType?) { exitSelection(); query.update { it.copy(type = value) } }
    /** One atomic query change prevents stale type/status chips narrowing a homepage count. */
    fun showQuery(value: TaskQuery) { exitSelection(); query.value = value }
    fun clearDateFilter() { exitSelection(); query.update { it.copy(date = TaskDateFilter.ALL) } }

    // ---- 批量选择与批量删除（W5，「已完成/全部」视图） ----

    /** 勾选/取消一行。 */
    fun toggleSelect(id: String) = selection.update { old ->
        val kept = pruneSelection(old, uiState.value.tasks)
        if (id in kept) kept - id else kept + id
    }

    /** 「全选」开关：可见任务已全部选中 → 取消这批；否则全部选中。 */
    fun toggleSelectAll() = selection.update { old ->
        toggledSelection(uiState.value.tasks, pruneSelection(old, uiState.value.tasks))
    }

    /** 退出选择模式。 */
    fun exitSelection() { selection.value = emptySet() }

    /**
     * 批量删除：逐条按当前快照版本提交，单项失败（版本冲突/已删）不影响其余；
     * 结束后清空选择并发一条汇总提示（无逐条 Deleted 事件）。
     */
    fun deleteSelected() = viewModelScope.launch {
        val targets = uiState.value.tasks.filter { it.record.id in selection.value }
        var deleted = 0
        var failed = 0
        for (view in targets) {
            try {
                gateway.delete(view.record.id, view.record.version)
                deleted++
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: DataException) {
                failed++
            } catch (_: Exception) {
                failed++
            }
        }
        selection.value = emptySet()
        eventChannel.send(TaskEvent.Message(text = when {
            targets.isEmpty() -> "没有可删除的任务"
            failed == 0 -> "已删除 $deleted 项任务"
            else -> "已删除 $deleted 项，$failed 项失败（可能已被其他操作改动）"
        }))
    }

    /** onResult 在协程内回调：null = 成功；非 null = 用户文案（表单保持打开、草稿不丢）。
     *  不传回调时失败会回落到事件通道提示，不会被静默吞掉。 */
    fun create(draft: TaskDraft, requestId: String? = null, onResult: ((String?) -> Unit)? = null) =
        viewModelScope.launch {
            safely(onResult) { gateway.create(draft, requestId) }?.let {
                eventChannel.send(TaskEvent.Saved(it))
            }
        }

    fun update(view: TaskView, draft: TaskDraft, onResult: ((String?) -> Unit)? = null) =
        viewModelScope.launch {
            safely(onResult) { gateway.update(view.record.id, view.record.version, draft) }?.let {
                eventChannel.send(TaskEvent.Saved(it))
            }
        }

    /** 完成 / 恢复待办；仅在真正变更时提示，重放请求不重复提示（E 的 changed 语义）。 */
    fun toggle(view: TaskView) = viewModelScope.launch {
        val completed = view.status != TaskStatus.COMPLETED
        val receipt = safely {
            gateway.setCompleted(view.record.id, completed, view.record.version)
        } ?: return@launch
        if (receipt.changed) {
            eventChannel.send(
                TaskEvent.Message(
                    text = if (completed) "任务已完成" else "已恢复为待办",
                    undoTaskId = view.record.id,
                    undoCompleted = !completed,
                    undoVersion = receipt.version,
                )
            )
        }
    }

    /** 撤销使用完成动作回执里的版本号提交，不读 data.value（避免静默覆盖第三方改动）。 */
    fun undo(taskId: String, completed: Boolean, expectedVersion: Long) = viewModelScope.launch {
        safely { gateway.setCompleted(taskId, completed, expectedVersion) }
    }

    fun delete(view: TaskView) = viewModelScope.launch {
        safely { gateway.delete(view.record.id, view.record.version) }?.let {
            eventChannel.send(TaskEvent.Deleted(it))
        }
    }

    private suspend fun <T> safely(onResult: ((String?) -> Unit)? = null, block: suspend () -> T): T? {
        val value = try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: DataException) {
            report(error.code.userMessage, onResult)
            return null
        } catch (error: Exception) {
            report(WRITE_FAILED, onResult)
            return null
        }
        // 成功回调放在 try 之外并隔离异常：回调自身抛错不得被当成写失败，
        // 否则会把一次成功的写库报成"操作失败"并丢掉 Saved 事件。
        runCatching { onResult?.invoke(null) }
        return value
    }

    private suspend fun report(text: String, onResult: ((String?) -> Unit)?) {
        if (onResult != null) onResult(text) else eventChannel.send(TaskEvent.Message(text))
    }

    private companion object {
        val WRITE_FAILED = "操作失败，请重试"
    }
}
