package com.coursekeeper.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import com.coursekeeper.data.model.TaskRecord
import java.time.LocalDateTime
import java.time.ZoneId

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
 * 登记表的增量合并：只移除本轮撤掉的、只加入本轮排上的。
 *
 * 不能拿"本轮开始时读到的集合 + 本轮结果"整表覆盖——另一个实例（到点 Receiver 续排、
 * 开机重建）可能在本轮读完之后刚登记了新 taskId，整表覆盖会把它静默抹掉；等进程重启后
 * 删除该任务，就留下撤不掉的孤儿闹钟，正是登记表要防的场景。
 */
internal fun registeredAfter(
    current: Set<String>,
    cancelled: Set<String>,
    scheduled: Set<String>,
): Set<String> = (current - cancelled) + scheduled

/**
 * 本轮真正生效的撤销集合：**在本轮开始读取之后才出现**的登记不撤。
 *
 * reconcile 的 cancelled 基于本轮开始时读到的登记表；如果另一个实例（到点 Receiver 续排）
 * 在这之后才登记了某 taskId，说明它刚给该任务挂上新闹钟。此时按过期判断去撤，会留下
 * "有闹钟、无登记"的孤儿。歧义时偏向保留登记，失败方向变成"有登记、无闹钟"，
 * 由下一次对账空转清掉。等价的化简：cancelled - (current - initial)。
 */
internal fun cancellationsToApply(
    cancelled: Set<String>,
    initial: Set<String>,
    current: Set<String>,
): Set<String> = cancelled - (current - initial)

/**
 * AlarmManager 实现（技术决定：不用 WorkManager——任务少、时间点明确，
 * WM 在 Doze 下会推迟到维护窗口，DDL 提醒不可接受）。
 * 每个任务一条自续链：只挂最早的未来槽位，到点后 Receiver 再排下一槽位；
 * 重建 = 同 taskId 覆盖（Intent data 参与 PendingIntent 匹配，任务之间互不串扰）。
 */
class AlarmReminderScheduler(private val context: Context) : ReminderScheduler {

    /**
     * 已登记闹钟的 taskId，**持久化**保存（v1.0 §四.3）。
     *
     * 只放进程内存时登记表会随实例消失：开机重建（BootReceiver）或进程被回收后重新打开
     * App 都是新实例，登记表为空。此时删除任务，taskId 已从快照消失，reconcile 无从得知
     * "它排过闹钟"，AlarmManager 里那条就成了孤儿——到点会被 Receiver 的 shouldFireNotification
     * 守卫拦下（不弹通知），但在此之前一直占着系统闹钟队列。
     *
     * 前提（Agent 阶段新增写入路径时必须保持）：
     * 1. 所有组件在同一进程内（Manifest 不声明 `android:process`）——SharedPreferences 不跨进程同步；
     * 2. `reconcile` 必须传**完整**任务快照——登记表是全局的，传过滤后的子集会撤掉其它任务的闹钟。
     */
    private val store: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun storedIds(): Set<String> =
        store.getStringSet(KEY_REGISTERED, null)?.toSet() ?: emptySet()

    private fun tracked(): Set<String> = synchronized(TRACKED_LOCK) { storedIds() }

    private fun updateTracked(block: (MutableSet<String>) -> Unit) = synchronized(TRACKED_LOCK) {
        val current = storedIds()
        val ids = current.toMutableSet()
        block(ids)
        if (ids != current) store.edit().putStringSet(KEY_REGISTERED, ids.toSet()).apply()
    }

    override fun reconcile(tasks: List<TaskRecord>, now: LocalDateTime) {
        val present = tasks.mapTo(HashSet()) { it.id }
        val initial = tracked()
        // 跨实例补撤：登记过、但已从快照消失的 taskId（本实例没见过它，靠持久化登记表兜住）
        val cancelled = initial.filterNotTo(mutableSetOf<String>()) { it in present }
        cancelled.forEach { cancelAlarm(it) }
        val scheduled = mutableSetOf<String>()
        for (task in tasks) {
            if (task.needsReminder(now)) {
                scheduleAlarm(task.id, task.title, task.dueAt!!, nextReminderSlot(task.dueAt, now)!!)
                scheduled += task.id
            } else {
                cancelAlarm(task.id)
                cancelled += task.id
            }
        }
        // 增量合并而不是整表覆盖：两次读取之间可能有别的实例刚登记了新 id（见 registeredAfter）
        synchronized(TRACKED_LOCK) {
            val current = storedIds()
            // 另一实例在本轮读完之后才登记的 id，不能被本轮的（可能基于过期快照的）cancelled 压掉。
            // 歧义时偏向"保持登记"：失败方向从"有闹钟无登记"（孤儿，占系统队列）
            // 变成"有登记无闹钟"（幽灵，下次对账空转清掉）。
            val merged = registeredAfter(current, cancellationsToApply(cancelled, initial, current), scheduled)
            if (merged != current) store.edit().putStringSet(KEY_REGISTERED, merged).apply()
        }
    }

    /** 挂/重建一条提醒链：从给定的未来槽位开始（由 nextReminderSlot 计算的调用方保证在未来）。 */
    fun schedule(taskId: String, title: String, dueAt: LocalDateTime, fireAt: LocalDateTime) {
        scheduleAlarm(taskId, title, dueAt, fireAt)
        updateTracked { it += taskId }
    }

    private fun scheduleAlarm(taskId: String, title: String, dueAt: LocalDateTime, fireAt: LocalDateTime) {
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
    }

    override fun cancel(taskId: String) {
        cancelAlarm(taskId)
        updateTracked { it -= taskId }
    }

    private fun cancelAlarm(taskId: String) {
        // 取消闹钟必须走 AlarmManager.cancel（按 PendingIntent 匹配移除）；
        // PendingIntent.cancel() 只是停用 PI 本身，不能可靠移除系统里已注册的闹钟
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

    companion object {
        private const val WINDOW_MILLIS = 10L * 60 * 1000

        /** 开机自启引导（BootStateStore）复用同一文件：登记表状态与开机标记一起读，少开一个 prefs。 */
        internal const val PREFS_NAME = "reminder_scheduler"
        internal const val KEY_REGISTERED = "registered_task_ids"

        /**
         * 进程级锁：UI（MainActivity）、到点 Receiver、BootReceiver 各自 new 一个实例，
         * 但共享同一份 SharedPreferences。锁若放在实例上，跨实例的读-改-写完全没有互斥。
         */
        val TRACKED_LOCK = Any()
    }
}
