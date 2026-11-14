package com.coursekeeper.task

import com.coursekeeper.data.model.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 批量选择的纯函数口径（W5）：入口范围、全选开关、选择集随视图收缩。 */
class TaskBatchSelectionContractTest {

    private val now = LocalDateTime.of(2026, 11, 14, 10, 0)

    private fun view(id: String, completedAt: LocalDateTime? = null) =
        com.coursekeeper.data.model.TaskRecord(
            id = id, title = "任务-$id", type = TaskType.HOMEWORK,
            dueAt = null, completedAt = completedAt, version = 1,
        ).toView(emptyList(), now)

    @Test
    fun `batch select entry only in done and all views`() {
        assertTrue(TaskStatusFilter.DONE.supportsBatchSelect)
        assertTrue(TaskStatusFilter.ALL.supportsBatchSelect)
        assertFalse(TaskStatusFilter.OPEN.supportsBatchSelect)
    }

    @Test
    fun `toggled selection selects all visible and keeps unrelated ids`() {
        val selected = toggledSelection(
            listOf(view("a"), view("b")),
            setOf("z"),
        )
        assertEquals(setOf("z", "a", "b"), selected)
    }

    @Test
    fun `toggled selection clears visible ids when everything visible is selected`() {
        val selected = toggledSelection(
            listOf(view("a"), view("b")),
            setOf("z", "a", "b"),
        )
        assertEquals(setOf("z"), selected)
    }

    @Test
    fun `toggled selection on empty visible list does nothing`() {
        assertEquals(setOf("z"), toggledSelection(emptyList(), setOf("z")))
    }

    @Test
    fun `prune selection drops ids that left the visible list`() {
        val pruned = pruneSelection(
            setOf("a", "b", "z"),
            listOf(view("b"), view("c")),
        )
        assertEquals(setOf("b"), pruned)
    }
}
