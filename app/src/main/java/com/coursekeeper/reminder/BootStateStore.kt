package com.coursekeeper.reminder

import android.content.Context
import android.os.SystemClock

/**
 * 开机自启引导的持久化状态，与提醒登记表同文件（[AlarmReminderScheduler.PREFS_NAME]）、同进程。
 *
 * 背景见 [shouldShowBootHint]：国产 ROM 默认拦截 BOOT_COMPLETED，重启后提醒不自动重建。
 * 判定链路：BootReceiver 成功执行时写"处理过"标记 → 用户打开 App 时用纯函数
 * [shouldShowBootHint] 比较标记与本次开机时刻 → 任务页出提示卡。
 *
 * 标记原则上由 BootReceiver 写：App 打开本身就会重建闹钟（attach 对账），
 * 它分不清"本次开机接收器跑过没有"；只有接收器自己知道。唯一的例外是 App 首次打开时
 * 若没有任何标记（干净安装），补一条基线（[markBaseline]）——否则自启动从一开始就被拦的
 * 用户永远没有参照，提示不会出现。
 * 失败方向设计：标记过期（早于本次开机）→ 提示（宁可多提醒一次也不让用户蒙在鼓里），
 * 但标记缺失时只补基线、不提示——无法区分"装完没重启过"与"重启被拦"，避免误报。
 */
object BootStateStore {

    /** BootReceiver 成功重建后调用：记录本次开机的墙钟与开机时长，供下次开机对比。 */
    fun markBootHandled(context: Context, nowWall: Long, nowElapsed: Long) {
        context.getSharedPreferences(AlarmReminderScheduler.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_BOOT_HANDLED_WALL, nowWall)
            .putLong(KEY_BOOT_HANDLED_ELAPSED, nowElapsed)
            .apply()
    }

    /**
     * App 首次打开且从未有任何标记时补基线：仅用于下一次开机对比，不代表本次开机接收器跑过。
     * 干净安装后接收器可能一直没机会执行（自启动被拦、又没经历应用更新），
     * 没有基线就永远判不出"被拦"，提示也就永远不会出现。
     */
    fun markBaseline(context: Context) {
        markBootHandled(context, System.currentTimeMillis(), SystemClock.elapsedRealtime())
    }

    /** 提示卡展示时调用：记录展示时估算的开机时刻，同一次开机内不再重复展示。 */
    fun markBootHintShown(context: Context, bootTimeWall: Long) {
        context.getSharedPreferences(AlarmReminderScheduler.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_BOOT_HINT_SHOWN_BOOT, bootTimeWall)
            .apply()
    }

    /** BootReceiver 上次成功执行的墙钟；0 = 从未执行过（首次安装或从未成功）。 */
    fun lastBootHandledWall(context: Context): Long = prefs(context).getLong(KEY_BOOT_HANDLED_WALL, 0L)

    /** 上次展示提示卡时的开机时刻估算；0 = 从未展示过。 */
    fun bootHintShownWall(context: Context): Long = prefs(context).getLong(KEY_BOOT_HINT_SHOWN_BOOT, 0L)

    /** 登记表非空 = 还有在册的提醒任务（重启被拦时闹钟已丢但登记还在，正是该提示的情形）。 */
    fun hasPendingReminder(context: Context): Boolean =
        prefs(context).getStringSet(AlarmReminderScheduler.KEY_REGISTERED, null)?.isNotEmpty() == true

    /** 本次开机时刻估算 = 当前墙钟 − 开机以来时长。墙钟被手动调整会漂移，判定容忍此误差。 */
    fun currentBootTimeWall(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun prefs(context: Context) =
        context.getSharedPreferences(AlarmReminderScheduler.PREFS_NAME, Context.MODE_PRIVATE)

    private const val KEY_BOOT_HANDLED_WALL = "last_boot_handled_wall"
    private const val KEY_BOOT_HANDLED_ELAPSED = "last_boot_handled_elapsed"
    private const val KEY_BOOT_HINT_SHOWN_BOOT = "boot_hint_shown_boot_wall"
}
