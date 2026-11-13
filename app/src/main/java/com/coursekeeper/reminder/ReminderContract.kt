package com.coursekeeper.reminder

import com.coursekeeper.data.model.TaskRecord
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 提醒纯逻辑（无 Android 依赖）。口径依据《任务字段与通知规则 v1.0》§4：
 * 截止前 24 小时与 1 小时各提醒一次；时间一律代码计算，设备本地时区。
 */
private val LEAD_HOURS = listOf(24L, 1L)

/** 截止前 24h / 1h 两个提醒槽位；已过去的时刻不算（提醒不追发）。 */
fun reminderSlots(dueAt: LocalDateTime, now: LocalDateTime): List<LocalDateTime> =
    LEAD_HOURS.map { dueAt.minusHours(it) }.filter { it.isAfter(now) }

/** 自续链的下一槽位：最早的未来槽位；全部已过则 null（不再提醒）。 */
fun nextReminderSlot(dueAt: LocalDateTime, now: LocalDateTime): LocalDateTime? =
    reminderSlots(dueAt, now).minOrNull()

/** 该任务当前是否需要挂提醒：未完成、有截止时间、还有未来槽位。 */
fun TaskRecord.needsReminder(now: LocalDateTime): Boolean =
    completedAt == null && dueAt != null && nextReminderSlot(dueAt, now) != null

/**
 * 到点通知的放行条件：任务存在、未完成、截止时间未改。
 * 排上的闹钟可能在到点前因"删除/完成/改期"失效（例如进程被杀没来得及对账），这里兜底。
 */
fun shouldFireNotification(task: TaskRecord?, dueAt: LocalDateTime): Boolean =
    task != null && task.completedAt == null && task.dueAt == dueAt

private val DUE_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** 通知文案。槽位都在截止之前，到点时截止必然还在未来，"将在"恒成立。 */
fun reminderText(title: String, dueAt: LocalDateTime): String =
    "「$title」将在 ${dueAt.format(DUE_FORMAT)} 截止"

/**
 * 重启后是否该提示「允许自启动」。
 *
 * 背景：国产 ROM（EMUI/MagicOS 等）默认拦截 BOOT_COMPLETED，重启后提醒不会自动重建，
 * 直到用户下次打开 App。判定依据 BootReceiver 上次成功执行的墙钟标记：
 * 标记早于本次开机时刻（bootTime = nowWall − nowElapsed 估算）⇒ 本次开机接收器没跑过 ⇒ 提示。
 *
 * 刻意保守的两处：
 * - 标记缺失（从未执行过，典型是首次安装）不提示——无法区分"装完没重启过"与"重启被拦"，
 *   宁可漏提示也不误报；
 * - 本次开机内已展示过不再重复（由调用方持久化"展示时估算的开机时刻"并传入）。
 */
fun shouldShowBootHint(
    lastBootHandledWall: Long,
    bootTimeWall: Long,
    hasPendingReminder: Boolean,
    shownThisBoot: Boolean,
): Boolean = lastBootHandledWall > 0 &&
    bootTimeWall > lastBootHandledWall &&
    hasPendingReminder &&
    !shownThisBoot
