package com.coursekeeper.reminder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 开机自启引导：纯判定 + 存储层。
 * 判定规则见 [shouldShowBootHint]（Marker 语义见 [BootStateStore]）：
 * 接收器本次开机没跑过 + 登记表有待提醒任务 + 本次开机没提示过 → 提示。
 */
class BootHintDecisionTest {

    // 场景基准：上次开机 10:00 处理过（墙钟 1794_0000_0000 假设值），
    // 本次开机 12:00 启动（bootTime 更大）→ 接收器没跑过 → 该提示
    private val handled = 1_794_000_000_000L
    private val bootTime = 1_794_007_200_000L

    @Test
    fun `hints when handled before this boot and reminders pending`() {
        assertTrue(shouldShowBootHint(handled, bootTime, hasPendingReminder = true, shownThisBoot = false))
    }

    @Test
    fun `no hint when receiver never ran (fresh install)`() {
        // 首次安装没有标记：无法区分"装完没重启过"与"重启被拦"，宁可漏提示不误报
        assertFalse(shouldShowBootHint(0L, bootTime, hasPendingReminder = true, shownThisBoot = false))
        assertFalse(shouldShowBootHint(-1L, bootTime, hasPendingReminder = true, shownThisBoot = false))
    }

    @Test
    fun `no hint when receiver ran during this boot`() {
        // 开机后接收器成功执行（墙钟标记 >= 开机时刻）：比如 MY_PACKAGE_REPLACED 或自启动放行
        assertFalse(shouldShowBootHint(bootTime, bootTime, hasPendingReminder = true, shownThisBoot = false))
        assertFalse(
            shouldShowBootHint(bootTime + 60_000, bootTime, hasPendingReminder = true, shownThisBoot = false),
        )
    }

    @Test
    fun `no hint without pending reminder tasks`() {
        // 登记表空（无带截止任务/全部完成删除）：提示无意义
        assertFalse(shouldShowBootHint(handled, bootTime, hasPendingReminder = false, shownThisBoot = false))
    }

    @Test
    fun `no hint when already shown this boot`() {
        // 展示即持久化：同一开机内重进 App 不再出现
        assertFalse(shouldShowBootHint(handled, bootTime, hasPendingReminder = true, shownThisBoot = true))
    }

    @Test
    fun `hint again after another reboot even if shown last boot`() {
        // 上次开机的展示记录（墙钟 < 本次开机时刻）不算"本次已展示"
        val shownLastBoot = handled // 展示发生在上次开机内，其估算开机时刻 <= handled
        assertFalse(shownLastBoot >= bootTime)
        assertTrue(shouldShowBootHint(handled, bootTime, hasPendingReminder = true, shownThisBoot = shownLastBoot >= bootTime))
    }
}

/** 存储层（Robolectric）：标记读写、登记表在册判断。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BootStateStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun raw() = context.getSharedPreferences("reminder_scheduler", Context.MODE_PRIVATE)

    @Test
    fun `markBootHandled writes wall and elapsed markers`() {
        BootStateStore.markBootHandled(context, nowWall = 1_794_000_000_000L, nowElapsed = 7_200_000L)

        assertEquals(1_794_000_000_000L, raw().getLong("last_boot_handled_wall", 0L))
        assertEquals(7_200_000L, raw().getLong("last_boot_handled_elapsed", 0L))
        assertEquals(1_794_000_000_000L, BootStateStore.lastBootHandledWall(context))
    }

    @Test
    fun `markers default to zero when receiver never ran`() {
        assertEquals(0L, BootStateStore.lastBootHandledWall(context))
        assertEquals(0L, BootStateStore.bootHintShownWall(context))
        assertFalse(BootStateStore.hasPendingReminder(context))
    }

    @Test
    fun `markBootHintShown persists the boot time for same-boot dedup`() {
        val bootTime = 1_794_007_200_000L
        BootStateStore.markBootHintShown(context, bootTime)

        assertEquals(bootTime, BootStateStore.bootHintShownWall(context))
        // 同一次开机：shownWall >= bootTime → 纯函数判"已展示"
        assertTrue(BootStateStore.bootHintShownWall(context) >= bootTime)
    }

    @Test
    fun `hasPendingReminder follows the registry of scheduled tasks`() {
        // 写一条登记（借真实调度器，保证键名与生产一致）
        val scheduler = AlarmReminderScheduler(context)
        val due = java.time.LocalDateTime.of(2026, 11, 20, 12, 0)
        scheduler.schedule("t-boot", "任务", due, due.minusHours(1))
        assertTrue(BootStateStore.hasPendingReminder(context))

        scheduler.cancel("t-boot")
        assertFalse(BootStateStore.hasPendingReminder(context))
    }
}
