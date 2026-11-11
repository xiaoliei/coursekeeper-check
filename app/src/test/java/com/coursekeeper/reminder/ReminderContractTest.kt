package com.coursekeeper.reminder

import com.coursekeeper.data.model.TaskRecord
import com.coursekeeper.data.model.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 提醒纯逻辑：槽位计算、自续链、通知放行（v1.0 §4：截止前 24h 与 1h 各一次，不追发）。 */
class ReminderContractTest {

    private val now = LocalDateTime.of(2026, 11, 11, 12, 0)

    private fun record(
        dueAt: LocalDateTime? = null,
        completedAt: LocalDateTime? = null,
    ) = TaskRecord(
        id = "t1", title = "写报告", type = TaskType.HOMEWORK,
        dueAt = dueAt, completedAt = completedAt,
    )

    @Test
    fun `slots are 24h and 1h before due`() {
        val dueAt = LocalDateTime.of(2026, 11, 13, 12, 0)
        assertEquals(
            listOf(LocalDateTime.of(2026, 11, 12, 12, 0), LocalDateTime.of(2026, 11, 13, 11, 0)),
            reminderSlots(dueAt, now),
        )
    }

    @Test
    fun `past slots are dropped`() {
        // 截止在 90 分钟后：24h 槽位已过，只剩 1h 槽位
        val dueAt = now.plusMinutes(90)
        assertEquals(listOf(now.plusMinutes(30)), reminderSlots(dueAt, now))
    }

    @Test
    fun `no slots remain when both are past`() {
        // 截止在 30 分钟后：两个槽位都已过 → 不再提醒
        val dueAt = now.plusMinutes(30)
        assertTrue(reminderSlots(dueAt, now).isEmpty())
        assertNull(nextReminderSlot(dueAt, now))
        assertFalse(record(dueAt = dueAt).needsReminder(now))
    }

    @Test
    fun `overdue task has no slots`() {
        val dueAt = now.minusDays(1)
        assertTrue(reminderSlots(dueAt, now).isEmpty())
        assertFalse(record(dueAt = dueAt).needsReminder(now))
    }

    @Test
    fun `next slot is the earliest future one`() {
        val dueAt = LocalDateTime.of(2026, 11, 13, 12, 0)
        assertEquals(LocalDateTime.of(2026, 11, 12, 12, 0), nextReminderSlot(dueAt, now))
    }

    @Test
    fun `completed task and task without due need no reminder`() {
        assertFalse(record(dueAt = now.plusDays(2), completedAt = now.minusHours(1)).needsReminder(now))
        assertFalse(record(dueAt = null).needsReminder(now))
    }

    @Test
    fun `notification fires only for existing open task with unchanged due`() {
        val dueAt = LocalDateTime.of(2026, 11, 13, 12, 0)
        assertTrue(shouldFireNotification(record(dueAt = dueAt), dueAt))
        assertFalse(shouldFireNotification(null, dueAt))
        assertFalse(shouldFireNotification(record(dueAt = dueAt, completedAt = now), dueAt))
        assertFalse(shouldFireNotification(record(dueAt = dueAt.plusMinutes(5)), dueAt))
    }

    @Test
    fun `reminder text contains title and formatted due time`() {
        assertEquals(
            "「写报告」将在 11-13 23:59 截止",
            reminderText("写报告", LocalDateTime.of(2026, 11, 13, 23, 59)),
        )
    }
}
