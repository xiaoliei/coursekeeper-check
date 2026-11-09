package com.coursekeeper.task

import com.coursekeeper.data.model.CourseRecord
import com.coursekeeper.data.model.TaskRecord
import com.coursekeeper.data.model.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 纯逻辑：状态派生、排序、筛选、文案。全部可人工核对，不需要 Android 环境。 */
class TaskContractTest {

    private val now = LocalDateTime.of(2026, 11, 9, 10, 0)

    private fun task(
        id: String,
        title: String = id,
        type: TaskType = TaskType.HOMEWORK,
        dueAt: LocalDateTime? = null,
        completedAt: LocalDateTime? = null,
        courseId: String? = null,
    ) = TaskRecord(id = id, title = title, type = type, dueAt = dueAt, completedAt = completedAt, courseId = courseId)

    @Test
    fun `status derives from completedAt then dueAt`() {
        assertEquals(TaskStatus.PENDING, task("a").statusAt(now))
        assertEquals(TaskStatus.PENDING, task("a", dueAt = now.plusHours(1)).statusAt(now))
        assertEquals(TaskStatus.OVERDUE, task("a", dueAt = now.minusMinutes(1)).statusAt(now))
        // 已完成优先于逾期：逾期不应盖住完成状态
        assertEquals(TaskStatus.COMPLETED, task("a", dueAt = now.minusDays(3), completedAt = now.minusDays(1)).statusAt(now))
    }

    @Test
    fun `due label covers undated today tomorrow overdue and other dates`() {
        assertEquals("截止待定", task("a").dueLabel(now))
        assertEquals("今天 23:59", task("a", dueAt = LocalDateTime.of(2026, 11, 9, 23, 59)).dueLabel(now))
        assertEquals("明天 18:00", task("a", dueAt = LocalDateTime.of(2026, 11, 10, 18, 0)).dueLabel(now))
        assertEquals("11/12 08:00", task("a", dueAt = LocalDateTime.of(2026, 11, 12, 8, 0)).dueLabel(now))
        assertEquals("已逾期 · 昨天 21:00", task("a", dueAt = LocalDateTime.of(2026, 11, 8, 21, 0)).dueLabel(now))
        // 已完成的任务即使过期也不再显示"已逾期"
        assertEquals("昨天 21:00", task("a", dueAt = LocalDateTime.of(2026, 11, 8, 21, 0), completedAt = now).dueLabel(now))
    }

    @Test
    fun `sort puts undated first then due time then completed last`() {
        val undated = task("undated")
        val later = task("later", dueAt = now.plusDays(2))
        val overdue = task("overdue", dueAt = now.minusDays(1))
        val tomorrow = task("tomorrow", dueAt = now.plusDays(1))
        val doneEarly = task("doneEarly", completedAt = now.minusHours(5))
        val doneLate = task("doneLate", completedAt = now.minusHours(1))

        val sorted = listOf(later, doneEarly, tomorrow, undated, doneLate, overdue).sortedForDisplay()

        assertEquals(listOf("undated", "overdue", "tomorrow", "later", "doneLate", "doneEarly"), sorted.map { it.id })
    }

    @Test
    fun `filters combine status and type`() {
        val views = listOf(
            task("p1", type = TaskType.HOMEWORK, dueAt = now.plusDays(1)),
            task("p2", type = TaskType.LAB_REPORT),
            task("d1", type = TaskType.HOMEWORK, completedAt = now),
        ).map { it.toView(emptyList(), now) }

        assertEquals(listOf("p1", "p2"), views.visible(TaskStatusFilter.OPEN, null).map { it.record.id })
        assertEquals(listOf("d1"), views.visible(TaskStatusFilter.DONE, null).map { it.record.id })
        assertEquals(listOf("p1", "d1"), views.visible(TaskStatusFilter.ALL, TaskType.HOMEWORK).map { it.record.id })
        assertEquals(listOf("p2"), views.visible(TaskStatusFilter.OPEN, TaskType.LAB_REPORT).map { it.record.id })
        assertTrue(views.visible(TaskStatusFilter.ALL, TaskType.CLUB).isEmpty())
    }

    @Test
    fun `course label distinguishes missing course from deleted course`() {
        val courses = listOf(CourseRecord(id = "c1", title = "数据库原理"))
        assertEquals("未关联课程", task("a").courseLabel(courses))
        assertEquals("数据库原理", task("a", courseId = "c1").courseLabel(courses))
        // 课程删除后任务保留 courseId，界面标注"课程已删除"（v1.0 六-B）
        assertEquals("课程已删除", task("a", courseId = "gone").courseLabel(courses))
    }

    @Test
    fun `input validation matches repository error messages`() {
        val emptyTitle = buildTaskDraft("   ", TaskType.HOMEWORK, null, true, "").exceptionOrNull()
        assertEquals("请填写任务名称", emptyTitle?.message)
        val tooLong = buildTaskDraft("a".repeat(61), TaskType.HOMEWORK, null, true, "").exceptionOrNull()
        assertEquals("任务名称过长（≤60 字）", tooLong?.message)
        val badDate = buildTaskDraft("写作业", TaskType.HOMEWORK, null, false, "2026-11-31 10:00").exceptionOrNull()
        assertEquals("请填写有效的截止时间，例如 2026-11-09 23:59", badDate?.message)
        val draft = buildTaskDraft(" 写作业 ", TaskType.HOMEWORK, null, true, "忽略").getOrThrow()
        assertEquals("写作业", draft.title)
        assertEquals(null, draft.dueAt)
    }
}
