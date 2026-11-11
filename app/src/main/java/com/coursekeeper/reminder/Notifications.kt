package com.coursekeeper.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.coursekeeper.MainActivity
import com.coursekeeper.R
import java.time.LocalDateTime

/** 通知渠道与到点通知的构建（第 3 周：提醒与通知）。 */
object TaskNotifications {
    const val CHANNEL_ID = "task_reminders"

    /** 幂等；App 启动与每次到点都调一次（minSdk 26，无需版本分支）。 */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "任务截止提醒", NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = "任务截止前 24 小时与 1 小时各提醒一次"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * 发到点通知。通知权限被拒（API 33+）时静默放弃——提醒照常排（v1.0 §4"拒绝权限仍可用"），
     * 只是发不出。minSdk 26 上该权限不存在，恒为已授权。
     */
    fun notifyDue(context: Context, taskId: String, title: String, dueAt: LocalDateTime) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val text = reminderText(title, dueAt)
        val open = PendingIntent.getActivity(
            context, taskId.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("任务截止提醒")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(taskId.hashCode(), notification)
    }
}
