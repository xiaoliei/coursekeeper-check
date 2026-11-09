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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/** 任务页状态机：版本号传递、错误映射、撤销使用回执版本。用假网关跑在 JVM 上。 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val now = LocalDateTime.of(2026, 11, 9, 10, 0)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun record(
        id: String,
        version: Long = 1,
        dueAt: LocalDateTime? = null,
        completedAt: LocalDateTime? = null,
    ) = TaskRecord(
        id = id, title = "任务-$id", type = TaskType.HOMEWORK,
        dueAt = dueAt, completedAt = completedAt, version = version,
    )

    private fun view(record: TaskRecord): TaskView = record.toView(emptyList(), now)

    @Test
    fun `toggle submits current version and offers undo with receipt version`() = runTest(dispatcher) {
        val gateway = FakeTaskGateway()
        gateway.emit(record("t1", version = 3))
        val vm = TaskViewModel(gateway) { now }
        val events = mutableListOf<TaskEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        backgroundScope.launch { vm.uiState.collect { } }

        vm.toggle(view(gateway.record("t1")))

        assertEquals(listOf("setCompleted:t1:true:3"), gateway.calls)
        val message = events.filterIsInstance<TaskEvent.Message>().single()
        assertEquals("任务已完成", message.text)
        assertEquals("t1", message.undoTaskId)
        assertEquals(false, message.undoCompleted)
        // 撤销必须携带完成动作回执里的新版本号
        assertEquals(4L, message.undoVersion)
    }

    @Test
    fun `toggle on completed task reopens it`() = runTest(dispatcher) {
        val gateway = FakeTaskGateway()
        gateway.emit(record("t1", version = 2, completedAt = now.minusHours(1)))
        val vm = TaskViewModel(gateway) { now }
        backgroundScope.launch { vm.uiState.collect { } }

        vm.toggle(view(gateway.record("t1")))

        assertEquals(listOf("setCompleted:t1:false:2"), gateway.calls)
    }

    @Test
    fun `version conflict surfaces repository message and creates no undo`() = runTest(dispatcher) {
        val gateway = FakeTaskGateway()
        gateway.emit(record("t1", version = 1))
        gateway.failWith = DataError.VERSION_CONFLICT
        val vm = TaskViewModel(gateway) { now }
        val events = mutableListOf<TaskEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }

        vm.toggle(view(gateway.record("t1")))

        assertEquals("网关应被调用一次", 1, gateway.calls.size)
        val message = events.filterIsInstance<TaskEvent.Message>().single()
        assertEquals("内容已被修改，请刷新后重试", message.text)
        assertEquals(null, message.undoTaskId)
    }

    @Test
    fun `generic failure surfaces generic text not internal message`() = runTest(dispatcher) {
        val gateway = FakeTaskGateway()
        gateway.emit(record("t1", version = 1))
        gateway.failGeneric = "boom"
        val vm = TaskViewModel(gateway) { now }
        val events = mutableListOf<TaskEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }

        vm.toggle(view(gateway.record("t1")))

        assertEquals("操作失败，请重试", events.filterIsInstance<TaskEvent.Message>().single().text)
    }

    @Test
    fun `undo submits the receipt version not the latest version in data`() = runTest(dispatcher) {
        val gateway = FakeTaskGateway()
        gateway.emit(record("t1", version = 3))
        val vm = TaskViewModel(gateway) { now }
        val events = mutableListOf<TaskEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        backgroundScope.launch { vm.uiState.collect { } }

        vm.toggle(view(gateway.record("t1")))
        val message = events.filterIsInstance<TaskEvent.Message>().single()
        // 模拟第三方改动使界面数据被刷新到 v5：撤销仍必须用回执里的 v4 提交，
        // 让 Repository 判定冲突，而不是静默覆盖第三方改动（E 的交接约定）
        gateway.emit(record("t1", version = 5))
        vm.undo("t1", false, message.undoVersion!!)

        assertEquals(listOf("setCompleted:t1:true:3", "setCompleted:t1:false:4"), gateway.calls)
    }

    @Test
    fun `create and delete report saved and deleted events with receipts`() = runTest(dispatcher) {
        val gateway = FakeTaskGateway()
        val vm = TaskViewModel(gateway) { now }
        val events = mutableListOf<TaskEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        backgroundScope.launch { vm.uiState.collect { } }
        val results = mutableListOf<String?>()

        vm.create(TaskDraft(title = "写报告", type = TaskType.LAB_REPORT)) { results += it }
        // 成功路径必须回调 onResult(null)，界面据此关闭表单（真机曾因此卡住对话框）
        assertEquals(listOf<String?>(null), results)
        assertTrue(events.filterIsInstance<TaskEvent.Saved>().single().receipt.changed)
        assertEquals("create:写报告", gateway.calls.first())

        vm.delete(view(gateway.record("new-0")))
        assertTrue(events.filterIsInstance<TaskEvent.Deleted>().single().receipt.changed)
        assertEquals("delete:new-0:1", gateway.calls.last())
    }

    @Test
    fun `create failure reports through onResult without saved event`() = runTest(dispatcher) {
        val gateway = FakeTaskGateway()
        gateway.failWith = DataError.TITLE_TOO_LONG
        val vm = TaskViewModel(gateway) { now }
        val events = mutableListOf<TaskEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        val results = mutableListOf<String?>()

        vm.create(TaskDraft(title = "写报告", type = TaskType.LAB_REPORT)) { results += it }

        assertEquals(listOf<String?>("任务名称过长（≤60 字）"), results)
        assertFalse(events.any { it is TaskEvent.Saved })
    }

    @Test
    fun `filters drive the visible list`() = runTest(dispatcher) {
        val gateway = FakeTaskGateway()
        gateway.emit(
            record("open1", dueAt = now.plusDays(1)),
            record("open2"),
            record("done1", completedAt = now.minusHours(2)),
        )
        val vm = TaskViewModel(gateway) { now }
        backgroundScope.launch { vm.uiState.collect { } }

        // 默认"待办"：截止待定排最前
        assertEquals(listOf("open2", "open1"), vm.uiState.value.tasks.map { it.record.id })
        assertEquals(2, vm.uiState.value.openCount)
        assertEquals(1, vm.uiState.value.doneCount)

        vm.setStatusFilter(TaskStatusFilter.ALL)
        assertEquals(3, vm.uiState.value.tasks.size)

        vm.setStatusFilter(TaskStatusFilter.DONE)
        assertEquals(listOf("done1"), vm.uiState.value.tasks.map { it.record.id })
    }

    private class FakeTaskGateway : TaskGateway {
        private val state = MutableStateFlow(TaskData())
        override val data: Flow<TaskData> = state
        val calls = mutableListOf<String>()
        var failWith: DataError? = null
        var failGeneric: String? = null
        private val clock = LocalDateTime.of(2026, 11, 9, 10, 0)

        fun emit(vararg records: TaskRecord) {
            state.value = state.value.copy(tasks = records.toList())
        }

        fun record(id: String): TaskRecord = state.value.tasks.first { it.id == id }

        override suspend fun create(draft: TaskDraft, requestId: String?): WriteReceipt {
            calls += "create:${draft.title}"
            failGeneric?.let { throw IllegalStateException(it) }
            failWith?.let { throw DataException(it) }
            val created = TaskRecord(
                id = "new-${state.value.tasks.size}", title = draft.title,
                type = draft.type, dueAt = draft.dueAt, courseId = draft.courseId,
            )
            state.value = state.value.copy(tasks = state.value.tasks + created)
            return WriteReceipt(created.id, created.version, changed = true)
        }

        override suspend fun update(id: String, expectedVersion: Long, changes: TaskDraft): WriteReceipt {
            calls += "update:$id:$expectedVersion"
            failGeneric?.let { throw IllegalStateException(it) }
            failWith?.let { throw DataException(it) }
            replace(id) {
                it.copy(
                    title = changes.title, type = changes.type, dueAt = changes.dueAt,
                    courseId = changes.courseId, version = it.version + 1,
                )
            }
            return WriteReceipt(id, record(id).version, changed = true)
        }

        override suspend fun setCompleted(id: String, completed: Boolean, expectedVersion: Long): WriteReceipt {
            calls += "setCompleted:$id:$completed:$expectedVersion"
            failGeneric?.let { throw IllegalStateException(it) }
            failWith?.let { throw DataException(it) }
            replace(id) {
                it.copy(
                    completedAt = if (completed) it.completedAt ?: clock else null,
                    version = it.version + 1,
                )
            }
            return WriteReceipt(id, record(id).version, changed = true)
        }

        override suspend fun delete(id: String, expectedVersion: Long): WriteReceipt {
            calls += "delete:$id:$expectedVersion"
            failGeneric?.let { throw IllegalStateException(it) }
            failWith?.let { throw DataException(it) }
            state.value = state.value.copy(tasks = state.value.tasks.filterNot { it.id == id })
            return WriteReceipt(id, expectedVersion + 1, changed = true)
        }

        private fun replace(id: String, block: (TaskRecord) -> TaskRecord) {
            state.value = state.value.copy(tasks = state.value.tasks.map { if (it.id == id) block(it) else it })
        }
    }
}
