package com.coursekeeper.reminder

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.coursekeeper.data.model.TaskRecord
import com.coursekeeper.data.model.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/** 调度对账：该排的排上、不该排的撤掉、同任务重建只留一条（Robolectric 假 AlarmManager）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReminderSchedulerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scheduler = AlarmReminderScheduler(context)
    private val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val now = LocalDateTime.of(2026, 11, 11, 12, 0)

    private fun record(
        id: String = "t1",
        dueAt: LocalDateTime? = null,
        completedAt: LocalDateTime? = null,
    ) = TaskRecord(
        id = id, title = "任务-$id", type = TaskType.HOMEWORK,
        dueAt = dueAt, completedAt = completedAt,
    )

    private fun scheduled() = shadowOf(alarm).scheduledAlarms

    private fun millis(time: LocalDateTime) =
        time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `schedule registers one exact alarm at the slot time`() {
        val slot = now.plusMinutes(30)
        scheduler.schedule("t1", "写报告", now.plusHours(1), slot)

        val alarms = scheduled()
        assertEquals(1, alarms.size)
        assertEquals(millis(slot), alarms.single().triggerAtTime)
        assertEquals(AlarmManager.RTC_WAKEUP, alarms.single().type)
    }

    @Test
    fun `rescheduling the same task replaces the alarm instead of adding`() {
        scheduler.schedule("t1", "写报告", now.plusHours(1), now.plusMinutes(30))
        scheduler.schedule("t1", "写报告", now.plusHours(1), now.plusMinutes(45))

        val alarms = scheduled()
        assertEquals(1, alarms.size)
        assertEquals(millis(now.plusMinutes(45)), alarms.single().triggerAtTime)
    }

    @Test
    fun `cancel removes the alarm`() {
        scheduler.schedule("t1", "写报告", now.plusHours(1), now.plusMinutes(30))
        scheduler.cancel("t1")
        assertEquals(0, scheduled().size)
    }

    @Test
    fun `cancel without prior schedule is a no-op`() {
        scheduler.cancel("never-scheduled")
        assertEquals(0, scheduled().size)
    }

    @Test
    fun `reconcile keeps reminders only for open tasks with a future slot`() {
        val tasks = listOf(
            record("open", dueAt = now.plusDays(2)),
            record("done", dueAt = now.plusDays(2), completedAt = now),
            record("no-due", dueAt = null),
            record("overdue", dueAt = now.minusDays(1)),
            record("soon", dueAt = now.plusMinutes(90)),
        )
        scheduler.reconcile(tasks, now)

        val alarms = scheduled()
        assertEquals(2, alarms.size)
        assertEquals(
            setOf("open", "soon"),
            alarms.map { shadowOf(it.operation).savedIntent.getStringExtra(ReminderReceiver.EXTRA_TASK_ID) }
                .toSet(),
        )
    }

    @Test
    fun `reconcile cancels alarms of tasks that no longer need them`() {
        val open = record("open", dueAt = now.plusDays(2))
        scheduler.reconcile(listOf(open), now)
        assertEquals(1, scheduled().size)

        // 任务被完成 → 对账后撤掉；再改期 → 重排到新槽位
        scheduler.reconcile(listOf(open.copy(completedAt = now)), now)
        assertEquals(0, scheduled().size)

        scheduler.reconcile(listOf(open.copy(dueAt = now.plusDays(3))), now)
        assertEquals(1, scheduled().size)
    }

    @Test
    fun `reconcile is idempotent`() {
        val tasks = listOf(record("open", dueAt = now.plusDays(2)))
        scheduler.reconcile(tasks, now)
        scheduler.reconcile(tasks, now)
        assertEquals(1, scheduled().size)
    }

    @Test
    fun `reconcile cancels alarms of deleted tasks that vanished from the snapshot`() {
        val open = record("open", dueAt = now.plusDays(2))
        scheduler.reconcile(listOf(open), now)
        assertEquals(1, scheduled().size)

        // 任务被删除后不再出现在快照里——登记表据此补撤，不残留旧闹钟（v1.0 §四.3）
        scheduler.reconcile(listOf(record("other", dueAt = now.plusDays(3))), now)
        assertEquals(1, scheduled().size)
        assertEquals(
            "other",
            shadowOf(scheduled().single().operation).savedIntent.getStringExtra(ReminderReceiver.EXTRA_TASK_ID),
        )
    }

    @Test
    fun `exact alarm usage follows sdk and system switch`() {
        assertTrue(useExactAlarm(28, false))   // API 31 前无需权限
        assertTrue(useExactAlarm(31, true))
        assertFalse(useExactAlarm(31, false))  // Android 12+ 未授权 → 降级窗口提醒
    }

    @Test
    fun `scheduled intent carries task identity for the receiver`() {
        scheduler.schedule("t1", "写报告", now.plusHours(1), now.plusMinutes(30))
        val intent = shadowOf(scheduled().single().operation).savedIntent
        assertEquals("t1", intent.getStringExtra(ReminderReceiver.EXTRA_TASK_ID))
        assertEquals("写报告", intent.getStringExtra(ReminderReceiver.EXTRA_TITLE))
        assertEquals(millis(now.plusHours(1)), intent.getLongExtra(ReminderReceiver.EXTRA_DUE_AT, 0L))
        assertEquals(ReminderReceiver.ACTION_FIRE, intent.action)
    }
}
