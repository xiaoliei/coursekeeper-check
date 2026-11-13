package com.coursekeeper.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.coursekeeper.data.CourseKeeperData
import com.coursekeeper.data.model.DataSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private const val TAG = "Reminder"

/** 到点广播：核对库内状态 → 发通知 → 自续下一槽位。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val dueAtMillis = intent.getLongExtra(EXTRA_DUE_AT, 0L)
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderCoordinator.onAlarmFired(context, taskId, title, dueAtMillis)
            } catch (error: Exception) {
                // 库读取失败不得让进程在响铃时刻崩溃；放弃本次通知即可，日志留排查痕迹
                Log.w(TAG, "reminder fire failed for $taskId", error)
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.coursekeeper.action.FIRE_TASK_REMINDER"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DUE_AT = "dueAtMillis"
    }
}

/**
 * 系统事件重建（最易漏的一环）：闹钟不跨重启持久，且应用更新会清空全部闹钟。
 * 开机/更新后按库内任务状态整体重建；期间已过去的槽位不补发（只挂仍未来的）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderCoordinator.rebuildAll(context)
                // 成功才写"本次开机已处理"标记：这是自启引导判定"本次开机接收器跑过没有"的唯一依据。
                // 失败（库不可用）不写——下次打开 App 仍会提示，用户知情。ROM 拦截时本方法根本不会执行，
                // 标记保持上一次开机的旧值，同样触发提示。
                BootStateStore.markBootHandled(
                    context, System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                )
            } catch (error: Exception) {
                // 开机/更新时库不可用不得让进程崩溃，下次打开 App 时 attach 会对账补上
                Log.w(TAG, "reminder rebuild failed", error)
            } finally {
                result.finish()
            }
        }
    }
}

/** 提醒与数据层、界面之间的接驳点：App 内对账、到点处理、开机重建共用一套逻辑。 */
object ReminderCoordinator {

    /**
     * 界面存活期间随数据快照对账。任何来源的写入（手动/同步/Agent/首页联动）
     * 都会刷新快照并在此重算提醒——不需要各处逐个挂钩。
     * 数据层首次出现带截止时间的任务时回调 onFirstDatedTask（入口无关，含首页快速添加，
     * v1.0 §四"保存成功后申请通知权限"）；仅触发一次。
     */
    fun attach(
        snapshots: Flow<DataSnapshot>,
        scheduler: ReminderScheduler,
        scope: CoroutineScope,
        onFirstDatedTask: (() -> Unit)? = null,
    ): Job = scope.launch {
        var firstDatedSignaled = false
        snapshots.collect { snapshot ->
            scheduler.reconcile(snapshot.tasks, LocalDateTime.now())
            if (onFirstDatedTask != null && !firstDatedSignaled &&
                snapshot.tasks.any { it.dueAt != null }
            ) {
                firstDatedSignaled = true
                onFirstDatedTask()
            }
        }
    }

    /** 闹钟到点：过期（已删/已完成/截止已改）则放弃；有效则发通知并续排下一槽位。 */
    suspend fun onAlarmFired(context: Context, taskId: String, title: String, dueAtMillis: Long) {
        val dueAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(dueAtMillis), ZoneId.systemDefault())
        val snapshot = CourseKeeperData.get(context).repository.snapshots.first()
        val task = snapshot.tasks.firstOrNull { it.id == taskId }
        if (!shouldFireNotification(task, dueAt)) return
        val record = task!!
        TaskNotifications.notifyDue(context, taskId, record.title, record.dueAt!!)
        // 自续：排下一个槽位；1h 提醒到点后 nextReminderSlot 为 null，链条自然结束
        val next = nextReminderSlot(record.dueAt, LocalDateTime.now()) ?: return
        AlarmReminderScheduler(context).schedule(taskId, record.title, record.dueAt, next)
    }

    /** 开机（或应用更新后）整体重建。 */
    suspend fun rebuildAll(context: Context) {
        val snapshot = CourseKeeperData.get(context).repository.snapshots.first()
        AlarmReminderScheduler(context).reconcile(snapshot.tasks, LocalDateTime.now())
    }
}
