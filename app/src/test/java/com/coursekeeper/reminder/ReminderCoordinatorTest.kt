package com.coursekeeper.reminder

import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.TaskRecord
import com.coursekeeper.data.model.TaskType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/** 对账接驳：快照逐条 reconcile；数据层首次出现带截止时间的任务时回调一次（入口无关）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderCoordinatorTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val now = LocalDateTime.of(2026, 11, 11, 12, 0)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun record(id: String, dueAt: LocalDateTime?) = TaskRecord(
        id = id, title = "任务-$id", type = TaskType.HOMEWORK, dueAt = dueAt,
    )

    private class RecordingScheduler : ReminderScheduler {
        val reconciled = mutableListOf<List<TaskRecord>>()
        val cancelled = mutableListOf<String>()
        override fun reconcile(tasks: List<TaskRecord>, now: LocalDateTime) {
            reconciled += tasks
        }
        override fun cancel(taskId: String) {
            cancelled += taskId
        }
    }

    @Test
    fun `every snapshot is reconciled`() = runTest(dispatcher) {
        val snapshots = MutableStateFlow(DataSnapshot())
        val scheduler = RecordingScheduler()
        val job = ReminderCoordinator.attach(snapshots, scheduler, backgroundScope)

        snapshots.value = DataSnapshot(tasks = listOf(record("a", null)))
        snapshots.value = DataSnapshot(tasks = listOf(record("a", null), record("b", now.plusDays(1))))
        advanceUntilIdle()
        job.cancel()

        assertEquals(3, scheduler.reconciled.size) // 初始空快照 + 两次更新
        assertEquals(listOf("a", "b"), scheduler.reconciled.last().map { it.id })
    }

    @Test
    fun `first dated task triggers callback exactly once regardless of entry`() = runTest(dispatcher) {
        val snapshots = MutableStateFlow(DataSnapshot())
        val scheduler = RecordingScheduler()
        val callbacks = mutableListOf<Int>()
        val job = ReminderCoordinator.attach(snapshots, scheduler, backgroundScope) {
            callbacks += 1
        }

        // 只有不带截止时间的任务（含首页快速添加的待定任务）→ 不触发
        snapshots.value = DataSnapshot(tasks = listOf(record("undated", null)))
        advanceUntilIdle()
        assertEquals(0, callbacks.size)

        // 首次出现带截止时间的任务 → 触发一次
        snapshots.value = DataSnapshot(tasks = listOf(record("undated", null), record("dated", now.plusDays(1))))
        advanceUntilIdle()
        assertEquals(1, callbacks.size)

        // 之后更多带截止时间的任务 → 不重复触发
        snapshots.value = DataSnapshot(
            tasks = listOf(record("dated", now.plusDays(1)), record("another", now.plusDays(2))),
        )
        advanceUntilIdle()
        assertEquals(1, callbacks.size)
        job.cancel()
    }

    @Test
    fun `callback fires immediately when dated tasks already exist at attach`() = runTest(dispatcher) {
        // 升级场景：库中已有带截止时间的任务，打开 App 即申请权限
        val snapshots = MutableStateFlow(DataSnapshot(tasks = listOf(record("dated", now.plusDays(1)))))
        val callbacks = mutableListOf<Int>()
        val job = ReminderCoordinator.attach(
            snapshots, RecordingScheduler(), backgroundScope,
        ) { callbacks += 1 }
        advanceUntilIdle()
        assertEquals(1, callbacks.size)
        job.cancel()
    }
}
