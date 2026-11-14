package com.coursekeeper.task

import com.coursekeeper.data.DataError
import com.coursekeeper.data.DataException
import com.coursekeeper.data.WriteReceipt
import com.coursekeeper.data.model.TaskRecord
import com.coursekeeper.data.model.TaskType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/** 批量删除（W5）：逐条按快照版本提交、单项失败不拖累其余、筛选切换清空选择。 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskBatchDeleteTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val now = LocalDateTime.of(2026, 11, 14, 10, 0)

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun record(
        id: String,
        version: Long = 1,
        completedAt: LocalDateTime? = null,
    ) = TaskRecord(
        id = id, title = "任务-$id", type = TaskType.HOMEWORK,
        dueAt = null, completedAt = completedAt, version = version,
    )

    /** 只为批量删除造的窄假网关：delete 走真实状态变更，可指定单个 id 失败。 */
    private class BatchFakeGateway : TaskGateway {
        val state = MutableStateFlow(TaskData())
        override val data: Flow<TaskData> = state
        val calls = mutableListOf<String>()
        val failIds = mutableSetOf<String>()

        fun emit(vararg records: TaskRecord) {
            state.value = state.value.copy(tasks = records.toList())
        }

        fun record(id: String): TaskRecord = state.value.tasks.first { it.id == id }

        override suspend fun create(draft: com.coursekeeper.data.TaskDraft, requestId: String?) =
            throw UnsupportedOperationException()

        override suspend fun update(id: String, expectedVersion: Long, changes: com.coursekeeper.data.TaskDraft) =
            throw UnsupportedOperationException()

        override suspend fun setCompleted(id: String, completed: Boolean, expectedVersion: Long) =
            throw UnsupportedOperationException()

        override suspend fun delete(id: String, expectedVersion: Long): WriteReceipt {
            calls += "delete:$id:$expectedVersion"
            if (id in failIds) throw DataException(DataError.VERSION_CONFLICT)
            state.value = state.value.copy(tasks = state.value.tasks.filterNot { it.id == id })
            return WriteReceipt(id, expectedVersion, changed = true)
        }
    }

    private fun vmWith(gateway: BatchFakeGateway) = TaskViewModel(gateway) { now }

    @Test
    fun `toggle select all selects visible tasks and toggles back`() = runTest(dispatcher) {
        val gateway = BatchFakeGateway()
        gateway.emit(record("open1"), record("open2"), record("done1", completedAt = now.minusHours(1)))
        val vm = vmWith(gateway)
        backgroundScope.launch { vm.uiState.collect { } }

        vm.setStatusFilter(TaskStatusFilter.ALL)
        vm.toggleSelectAll()
        assertEquals(setOf("open1", "open2", "done1"), vm.uiState.value.selectedIds)
        assertTrue(vm.uiState.value.allVisibleSelected)

        vm.toggleSelectAll()
        assertEquals(emptySet<String>(), vm.uiState.value.selectedIds)
    }

    @Test
    fun `toggling one row updates selection`() = runTest(dispatcher) {
        val gateway = BatchFakeGateway()
        gateway.emit(record("t1"), record("t2"))
        val vm = vmWith(gateway)
        backgroundScope.launch { vm.uiState.collect { } }

        vm.setStatusFilter(TaskStatusFilter.ALL)
        vm.toggleSelect("t1")
        assertEquals(setOf("t1"), vm.uiState.value.selectedIds)
        vm.toggleSelect("t1")
        assertEquals(emptySet<String>(), vm.uiState.value.selectedIds)
    }

    @Test
    fun `filter change clears selection`() = runTest(dispatcher) {
        val gateway = BatchFakeGateway()
        gateway.emit(record("t1"), record("t2"))
        val vm = vmWith(gateway)
        backgroundScope.launch { vm.uiState.collect { } }

        vm.setStatusFilter(TaskStatusFilter.ALL)
        vm.toggleSelectAll()
        vm.setStatusFilter(TaskStatusFilter.DONE)
        assertEquals(emptySet<String>(), vm.uiState.value.selectedIds)
    }

    @Test
    fun `selection prunes when a task leaves the visible list`() = runTest(dispatcher) {
        val gateway = BatchFakeGateway()
        gateway.emit(record("t1"), record("done1", completedAt = now.minusHours(1)))
        val vm = vmWith(gateway)
        backgroundScope.launch { vm.uiState.collect { } }

        vm.setStatusFilter(TaskStatusFilter.DONE)
        vm.toggleSelectAll()
        assertEquals(setOf("done1"), vm.uiState.value.selectedIds)

        // done1 被第三方恢复为待办（版本 +1）：不再出现在「已完成」视图 → 自动退出选中集
        gateway.emit(record("t1"), record("done1", version = 2))
        assertEquals(emptySet<String>(), vm.uiState.value.selectedIds)
    }

    @Test
    fun `delete selected submits snapshot versions and posts summary`() = runTest(dispatcher) {
        val gateway = BatchFakeGateway()
        gateway.emit(record("t1", version = 1), record("t2", version = 4), record("t3", version = 2))
        val vm = vmWith(gateway)
        val events = mutableListOf<TaskEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        backgroundScope.launch { vm.uiState.collect { } }

        vm.setStatusFilter(TaskStatusFilter.ALL)
        vm.toggleSelectAll()
        vm.deleteSelected()

        assertEquals(listOf("delete:t1:1", "delete:t2:4", "delete:t3:2"), gateway.calls)
        assertEquals("已删除 3 项任务", events.filterIsInstance<TaskEvent.Message>().single().text)
        assertEquals(emptySet<String>(), vm.uiState.value.selectedIds)
        assertTrue(gateway.state.value.tasks.isEmpty())
    }

    @Test
    fun `delete selected continues past a failing item and reports partial`() = runTest(dispatcher) {
        val gateway = BatchFakeGateway()
        gateway.emit(record("t1"), record("t2"), record("t3"))
        gateway.failIds += "t2"
        val vm = vmWith(gateway)
        val events = mutableListOf<TaskEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        backgroundScope.launch { vm.uiState.collect { } }

        vm.setStatusFilter(TaskStatusFilter.ALL)
        vm.toggleSelectAll()
        vm.deleteSelected()

        assertEquals(listOf("delete:t1:1", "delete:t2:1", "delete:t3:1"), gateway.calls)
        assertEquals(
            "已删除 2 项，1 项失败（可能已被其他操作改动）",
            events.filterIsInstance<TaskEvent.Message>().single().text,
        )
        assertEquals(emptySet<String>(), vm.uiState.value.selectedIds)
    }

    @Test
    fun `delete selected with nothing deletable posts a hint`() = runTest(dispatcher) {
        val gateway = BatchFakeGateway()
        gateway.emit(record("t1"))
        val vm = vmWith(gateway)
        val events = mutableListOf<TaskEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        backgroundScope.launch { vm.uiState.collect { } }

        // 选择集里有已不在视图中的 id：批量删除按可见快照为准
        vm.setStatusFilter(TaskStatusFilter.ALL)
        vm.toggleSelect("t1")
        gateway.emit(record("t1"))
        // t1 仍在视图 → 先模拟消失：改成只有 t2
        gateway.emit(record("t2"))

        vm.deleteSelected()
        assertEquals("没有可删除的任务", events.filterIsInstance<TaskEvent.Message>().single().text)
        assertEquals(emptySet<String>(), vm.uiState.value.selectedIds)
        assertTrue(gateway.calls.isEmpty())
    }
}
