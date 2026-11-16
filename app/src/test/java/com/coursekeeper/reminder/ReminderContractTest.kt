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
    fun `slot exactly at now is treated as past`() {
        // 等号边界（W6 固化）：截止在 1 小时后 → 1h 槽位恰好等于 now，按"已过"丢弃（isAfter 严格）。
        // 与任务侧"dueAt 恰等于 now 不算逾期"（isBefore 严格）方向相反，但两者语义一致：
        // 这一刻都不产生新动作——不追发提醒、也不把任务判成逾期。
        // 生产时钟带纳秒，等号在实践中不可达；若日后改成 !isBefore，这条用例会红，需同步改口径说明。
        val dueAt = now.plusHours(1)
        assertTrue(reminderSlots(dueAt, now).isEmpty())
        assertNull(nextReminderSlot(dueAt, now))
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
