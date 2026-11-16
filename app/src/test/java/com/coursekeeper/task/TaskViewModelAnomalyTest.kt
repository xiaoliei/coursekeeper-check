package com.coursekeeper.task

import com.coursekeeper.data.DataError
import com.coursekeeper.data.DataException
import com.coursekeeper.data.TaskDraft
import com.coursekeeper.data.WriteReceipt
import com.coursekeeper.data.model.TaskRecord
import com.coursekeeper.data.model.TaskType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/**
 * W6 固化样例：任务页状态机在异常输入下的行为。
 *
 * 与 [TaskViewModelTest] 的分工：那边验证正常路径与版本号传递，这里只放"真机上会遇到的异常"——
 * 重放请求不该弹提示、撤销目标已被删除、跨零点文案与日期筛选要自己刷新、选择集里混入幽灵 id。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModelAnomalyTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun record(
        id: String,
        dueAt: LocalDateTime? = null,
        completedAt: LocalDateTime? = null,
        version: Long = 1,
    ) = TaskRecord(id = id, title = "任务-$id", type = TaskType.HOMEWORK,
        dueAt = dueAt, completedAt = completedAt, version = version)

    /** 可指定"是否真的变更"（changed 标志）与按方法注入失败的假网关。 */
    private class AnomalyGateway : TaskGateway {
        val state = MutableStateFlow(TaskData())
        override val data: Flow<TaskData> = state
        val calls = mutableListOf<String>()
        var changedFlag = true
        var completedFailure: DataError? = null
        var deleteFailure: DataError? = null

        fun emit(vararg records: TaskRecord) {
            state.value = state.value.copy(tasks = records.toList())
        }

        fun record(id: String): TaskRecord = state.value.tasks.first { it.id == id }

        override suspend fun create(draft: TaskDraft, requestId: String?) =
            WriteReceipt("created", 1, changed = true)

        override suspend fun update(id: String, expectedVersion: Long, changes: TaskDraft) =
            WriteReceipt(id, expectedVersion + 1, changed = true)

        override suspend fun setCompleted(id: String, completed: Boolean, expectedVersion: Long): WriteReceipt {
            calls += "setCompleted:$id:$completed:$expectedVersion"
            completedFailure?.let { throw DataException(it) }
            state.value = state.value.copy(tasks = state.value.tasks.map {
                if (it.id == id) it.copy(completedAt = if (completed) it.completedAt ?: LocalDateTime.of(2026, 11, 9, 10, 0) else null) else it
            })
            return WriteReceipt(id, expectedVersion + 1, changed = changedFlag)
        }

        override suspend fun delete(id: String, expectedVersion: Long): WriteReceipt {
            calls += "delete:$id:$expectedVersion"
            deleteFailure?.let { throw DataException(it) }
            state.value = state.value.copy(tasks = state.value.tasks.filterNot { it.id == id })
            return WriteReceipt(id, expectedVersion + 1, changed = true)
        }
    }

    @Test
    fun `replayed completion posts no message and offers no undo`() = runTest(dispatcher) {
        // E 的写回执：changed=false 表示这次请求没有真正改变数据
        //（客户端重试、同秒双击）。此时静默即可，不能弹"任务已完成"再给一个撤销按钮。
        // 注：本用例走的是"幂等完成"（changed=false）；`replayed=true` 需要 requestId，
        // TaskGateway.setCompleted 目前不传，A05 若加 requestId 需同步复查这里。
        val gateway = AnomalyGateway()
        gateway.emit(record("t1"))
        gateway.changedFlag = false
        val vm = TaskViewModel(gateway) { LocalDateTime.of(2026, 11, 9, 10, 0) }
        val events = mutableListOf<TaskEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        backgroundScope.launch { vm.uiState.collect { } }

        vm.toggle(gateway.record("t1").toView(emptyList(), LocalDateTime.of(2026, 11, 9, 10, 0)))

        assertEquals(listOf("setCompleted:t1:true:1"), gateway.calls)
        assertTrue("重放不应产生任何界面提示", events.isEmpty())
    }

    @Test
    fun `undo on a task deleted meanwhile reports not found and offers no undo`() = runTest(dispatcher) {
        // 用户点完成 → 提示条还开着 → 另一端（同步/别的页面）把任务删了 → 用户点"撤销"
        val gateway = AnomalyGateway()
        gateway.completedFailure = DataError.TASK_NOT_FOUND
        val vm = TaskViewModel(gateway) { LocalDateTime.of(2026, 11, 9, 10, 0) }
        val events = mutableListOf<TaskEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }

        vm.undo("gone", completed = false, expectedVersion = 3)

        val message = events.filterIsInstance<TaskEvent.Message>().single()
        assertEquals("任务不存在，请刷新后重试", message.text)
        assertNull("失败不能再给撤销按钮，否则会二次失败", message.undoTaskId)
    }

    @Test
    fun `deleting an already deleted task reports not found`() = runTest(dispatcher) {
        val gateway = AnomalyGateway()
        gateway.emit(record("t1"))
        gateway.deleteFailure = DataError.TASK_NOT_FOUND
        val vm = TaskViewModel(gateway) { LocalDateTime.of(2026, 11, 9, 10, 0) }
        val events = mutableListOf<TaskEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        backgroundScope.launch { vm.uiState.collect { } }

        vm.delete(gateway.record("t1").toView(emptyList(), LocalDateTime.of(2026, 11, 9, 10, 0)))

        assertEquals("任务不存在，请刷新后重试", events.filterIsInstance<TaskEvent.Message>().single().text)
        assertTrue(events.none { it is TaskEvent.Deleted })
    }

    @Test
    fun `selecting an id outside the current view never shows selection mode`() = runTest(dispatcher) {
        // 防御：从旧界面/无障碍树传来的、已经不在当前可见集里的 id 不得让选择模式凭空出现。
        // 口径是"用户可见状态"：私有 selection 可能瞬时收下该 id，但 uiState 会立即按可见集收缩。
        val gateway = AnomalyGateway()
        gateway.emit(record("t1"))
        val vm = TaskViewModel(gateway) { LocalDateTime.of(2026, 11, 9, 10, 0) }
        backgroundScope.launch { vm.uiState.collect { } }
        vm.setStatusFilter(TaskStatusFilter.ALL)

        vm.toggleSelect("ghost")

        assertEquals(emptySet<String>(), vm.uiState.value.selectedIds)
    }

    @Test
    fun `minute tick refreshes the due label across midnight`() = runTest(dispatcher) {
        var clock = LocalDateTime.of(2026, 11, 9, 23, 59)
        val gateway = AnomalyGateway()
        gateway.emit(record("t1", dueAt = LocalDateTime.of(2026, 11, 9, 23, 59)))
        val vm = TaskViewModel(gateway) { clock }
        backgroundScope.launch { vm.uiState.collect { } }

        assertEquals("今天 23:59", vm.uiState.value.tasks.single().dueLabel)

        // 跨过零点：任务自己没有变化，是"现在"变了——文案必须自己刷新，不能等下一条数据
        clock = LocalDateTime.of(2026, 11, 10, 0, 1)
        advanceTimeBy(61_000)
        runCurrent()

        assertEquals("已逾期 · 昨天 23:59", vm.uiState.value.tasks.single().dueLabel)
        assertEquals(TaskStatus.OVERDUE, vm.uiState.value.tasks.single().status)
    }

    @Test
    fun `date filter re-evaluates itself after midnight`() = runTest(dispatcher) {
        var clock = LocalDateTime.of(2026, 11, 9, 23, 0)
        val gateway = AnomalyGateway()
        gateway.emit(record("today", dueAt = LocalDateTime.of(2026, 11, 9, 23, 59)))
        val vm = TaskViewModel(gateway) { clock }
        backgroundScope.launch { vm.uiState.collect { } }
        vm.showQuery(TaskQuery(status = TaskStatusFilter.ALL, date = TaskDateFilter.DUE_TODAY))
        assertEquals(listOf("today"), vm.uiState.value.tasks.map { it.record.id })

        clock = LocalDateTime.of(2026, 11, 10, 0, 1)
        advanceTimeBy(61_000)
        runCurrent()

        // 过了零点，"今日截止"里不再有它；页面不需要用户重新点筛选
        assertTrue(vm.uiState.value.tasks.isEmpty())
    }
}
