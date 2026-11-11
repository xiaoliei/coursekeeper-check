package com.coursekeeper.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.coursekeeper.data.model.TaskRecord
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** 提醒调度抽象，便于单测替换。 */
interface ReminderScheduler {
    /**
     * 状态对账：按当前任务状态整体重算"谁该有提醒"。需要的排上，不需要的撤掉。
     * 幂等——重复执行无副作用，重放/重复快照不会重复建提醒（E 的 changed && !replayed 语义由此保证）。
     */
    fun reconcile(tasks: List<TaskRecord>, now: LocalDateTime)

    fun cancel(taskId: String)
}

/** 精确闹钟可用性：API 31 前无需权限；31 起看系统开关（不可用则降级窗口提醒）。 */
internal fun useExactAlarm(sdkInt: Int, canScheduleExactAlarms: Boolean): Boolean =
    sdkInt < 31 || canScheduleExactAlarms

/**
 * AlarmManager 实现（技术决定：不用 WorkManager——任务少、时间点明确，
 * WM 在 Doze 下会推迟到维护窗口，DDL 提醒不可接受）。
 * 每个任务一条自续链：只挂最早的未来槽位，到点后 Receiver 再排下一槽位；
 * 重建 = 同 taskId 覆盖（Intent data 参与 PendingIntent 匹配，任务之间互不串扰）。
 */
class AlarmReminderScheduler(private val context: Context) : ReminderScheduler {

    /** 本实例登记过闹钟的 taskId：任务被删除后从快照消失，reconcile 据此补撤残留闹钟（v1.0 §四.3）。 */
    private val registered =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    override fun reconcile(tasks: List<TaskRecord>, now: LocalDateTime) {
        val present = tasks.mapTo(HashSet()) { it.id }
        registered.filterNot { it in present }.forEach { cancel(it) }
        for (task in tasks) {
            if (task.needsReminder(now)) {
                schedule(task.id, task.title, task.dueAt!!, nextReminderSlot(task.dueAt, now)!!)
            } else {
                cancel(task.id)
            }
        }
    }

    /** 挂/重建一条提醒链：从给定的未来槽位开始（由 nextReminderSlot 计算的调用方保证在未来）。 */
    fun schedule(taskId: String, title: String, dueAt: LocalDateTime, fireAt: LocalDateTime) {
        val triggerAt = fireAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val dueAtMillis = dueAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending = PendingIntent.getBroadcast(
            context, taskId.hashCode(), fireIntent(taskId, title, dueAtMillis),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (useExactAlarm(Build.VERSION.SDK_INT, exactAllowed(alarm))) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            // 窗口提醒：系统在 [triggerAt, triggerAt+WINDOW] 内择机发出，误差最多几分钟
            alarm.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, WINDOW_MILLIS, pending)
        }
        registered += taskId
    }

    override fun cancel(taskId: String) {
        // 取消闹钟必须走 AlarmManager.cancel（按 PendingIntent 匹配移除）；
        // PendingIntent.cancel() 只是停用 PI 本身，不能可靠移除系统里已注册的闹钟
        registered -= taskId
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(
            context, taskId.hashCode(), fireIntent(taskId, "", 0L),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarm.cancel(pending)
    }

    private fun exactAllowed(alarm: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()

    private fun fireIntent(taskId: String, title: String, dueAtMillis: Long): Intent =
        Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            // data 参与 PendingIntent 匹配：不同任务即使 hashCode 相撞也不会互相覆盖
            data = Uri.parse("coursekeeper://reminder/$taskId")
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
            putExtra(ReminderReceiver.EXTRA_TITLE, title)
            putExtra(ReminderReceiver.EXTRA_DUE_AT, dueAtMillis)
        }

    private companion object {
        const val WINDOW_MILLIS = 10L * 60 * 1000
    }
}
