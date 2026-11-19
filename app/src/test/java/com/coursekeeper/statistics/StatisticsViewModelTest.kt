package com.coursekeeper.statistics

import com.coursekeeper.data.DataTestBase
import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.TaskType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** 看板数据入口：订阅快照实时计算，读失败保留上一次成功值。 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest : DataTestBase() {

    // 固定“现在”：2026-11-10 10:00（周二），本周 = [2026-11-09, 2026-11-16)，与 BoundaryFixtures 一致。
    private val now = LocalDateTime.of(2026, 11, 10, 10, 0)
    private fun dueTask(id: String, due: LocalDateTime?) = draft(id).copy(dueAt = due)

    // 本周内有截止的 4 项（其一已完成）+ 截止待定 + 下周一 00:00。
    private suspend fun seedBoundaryWeek() {
        val a = repository.createTask(dueTask("a", LocalDateTime.of(2026, 11, 9, 20, 0)))
        repository.setTaskCompleted(a.id, true, a.version)
        repository.createTask(dueTask("b", LocalDateTime.of(2026, 11, 10, 23, 59)))
        repository.createTask(dueTask("c", LocalDateTime.of(2026, 11, 11, 20, 0)))
        repository.createTask(dueTask("d", LocalDateTime.of(2026, 11, 15, 23, 59)))
        repository.createTask(dueTask("undated", null))
        repository.createTask(dueTask("next-midnight", LocalDateTime.of(2026, 11, 16, 0, 0)))
    }

    @Test
    fun vmMapsSnapshotToStatisticsUiWithFixtureClock() = runTest {
        val vm = StatisticsViewModel(repository, courseStatsSource = object : CourseStatsSource {
            override fun summary(snapshot: DataSnapshot, now: LocalDateTime) = CourseStats()
        }, scope = backgroundScope, now = { now })
        seedBoundaryWeek()
        // 等全部边界数据入快照（含下周一 00:00 的 next7Days 项）。
        val ui = vm.uiState.first { it.weekTaskCount == 4 && it.dueDist.next7Days == 1 }
        assertEquals(1, ui.weekCompletedCount)
        assertEquals(0.25f, ui.completionRate!!, 0.0001f)
        assertEquals(4, ui.typeDistribution.sumOf { it.count })
        assertEquals(TaskType.HOMEWORK, ui.typeDistribution.single().type)
        assertEquals(1, ui.dueDist.overdue)    // 任务 a 截止 11-09 已过
        assertEquals(3, ui.dueDist.thisWeek)   // b / c / d
        assertEquals(1, ui.dueDist.next7Days)  // 11-16 00:00 在 [11-16, 11-23)
        assertEquals(40, ui.pressureScore)
        assertEquals("适中", ui.pressureLevel)
    }

    @Test
    fun vmKeepsLastSuccessWhenCourseSourceFails() = runTest {
        val source = object : CourseStatsSource {
            var failing = false
            override fun summary(snapshot: DataSnapshot, now: LocalDateTime): CourseStats {
                if (failing) throw IllegalStateException("course source down")
                return CourseStats()
            }
        }
        val vm = StatisticsViewModel(repository, courseStatsSource = source, scope = backgroundScope, now = { now })
        seedBoundaryWeek()
        val before = vm.uiState.first { it.weekTaskCount == 4 }
        source.failing = true
        vm.retry()
        // 计算失败：保留上一次成功值（仍为 4），不以空结果覆盖。
        assertEquals(before.copy(error = "看板加载失败，请重试"), vm.uiState.value)
    }

    @Test
    fun vmShowsErrorAndRetryRecoversAfterInitialSourceFailure() = runTest {
        val source = object : CourseStatsSource {
            var failing = true
            override fun summary(snapshot: DataSnapshot, now: LocalDateTime): CourseStats {
                check(!failing) { "course source down" }
                return CourseStats()
            }
        }
        // 先灌数据、后建 VM：Room 的 Flow 在真实后台线程发射，advanceUntilIdle() 只推进
        // 虚拟时间、等不到它。若先建 VM，首个快照可能是空库快照，错误态一出现
        // first{} 就返回，retry() 会拿着空快照算出 weekTaskCount=0（CI 慢机器上必现）。
        seedBoundaryWeek()
        val vm = StatisticsViewModel(repository, courseStatsSource = source, scope = backgroundScope, now = { now })
        advanceUntilIdle()
        val failed = vm.uiState.first { it.error != null }
        assertEquals(0, failed.weekTaskCount)
        assertEquals("看板加载失败，请重试", failed.error)

        source.failing = false
        vm.retry()
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.error)
        assertEquals(4, vm.uiState.value.weekTaskCount)
    }

    // F-01：无数据库写入时，仅靠时间推进（分钟 tick）看板也要重算。
    // 与 A 的 RepositoryHomeGatewayTest 同款：注入 MutableSharedFlow 手动 emit 驱动。

    @Test
    fun vmRecalculatesOverdueWhenDeadlinePassesWithoutWrites() = runTest {
        // 任务 b 截止 11-10 23:59；从 23:00 起，tick 后时钟推 60 分钟跨过截止。
        var stamp = LocalDateTime.of(2026, 11, 10, 23, 0)
        val ticks = MutableSharedFlow<Unit>(replay = 1)
        ticks.emit(Unit)
        val vm = StatisticsViewModel(repository, courseStatsSource = object : CourseStatsSource {
            override fun summary(snapshot: DataSnapshot, now: LocalDateTime) = CourseStats()
        }, scope = backgroundScope, now = { stamp }, ticks = ticks)
        seedBoundaryWeek()
        // 任务 a 截止 11-09 已过：初始 overdue=1，b 尚未过期。
        val before = vm.uiState.first { it.weekTaskCount == 4 }
        assertEquals(1, before.dueDist.overdue)

        // “无数据库写入”由 tick 仅触发 compute、不改 Room 保证；直接断言数字随时间变化。
        stamp = stamp.plusMinutes(60)                   // 11-11 00:00，跨过 23:59
        ticks.emit(Unit)
        val after = vm.uiState.first { it.dueDist.overdue == 2 }   // a、b 均过期
        assertEquals(4, after.weekTaskCount)                        // 11-11 仍在同一周窗口内
    }

    @Test
    fun vmRollsWeekWindowAcrossWeekBoundaryWithoutWrites() = runTest {
        var stamp = LocalDateTime.of(2026, 11, 15, 23, 59)
        val ticks = MutableSharedFlow<Unit>(replay = 1)
        ticks.emit(Unit)
        val vm = StatisticsViewModel(repository, courseStatsSource = object : CourseStatsSource {
            override fun summary(snapshot: DataSnapshot, now: LocalDateTime) = CourseStats()
        }, scope = backgroundScope, now = { stamp }, ticks = ticks)
        seedBoundaryWeek()
        // weekTaskCount==4 只要求 a/b/c/d 到齐；next7Days==1 才保证最后一个任务
        // next-midnight(11-16 00:00) 也进了快照。Room 快照是异步发射，不等到它就在
        // 下方 tick 重算，会拿着缺 next-midnight 的旧快照算出错误数字（见 review）。
        vm.uiState.first { it.weekTaskCount == 4 && it.dueDist.next7Days == 1 }

        // 跨到下周一 11-16 00:01：本周窗口滚动为 [11-16, 11-23)。
        stamp = stamp.plusMinutes(2)
        ticks.emit(Unit)
        // next-midnight(11-16 00:00) 落在新周窗口内、且此刻已过期：
        // 完整数据下 rolled 为 thisWeek=0、overdue=5、next7=0、weekTaskCount=1。
        val rolled = vm.uiState.first { it.weekTaskCount == 1 }
        assertEquals(0, rolled.dueDist.thisWeek)   // 新周内无未过期任务
        // a/b/c/d + next-midnight 截止均 < 11-16 00:01，共 5 件全部逾期。
        assertEquals(5, rolled.dueDist.overdue)
        assertEquals(0, rolled.dueDist.next7Days)   // [11-23, 11-30) 内无任务
    }

    @Test
    fun vmKeepsLastSuccessAndErrorWhenTickHitsSourceFailure() = runTest {
        val source = object : CourseStatsSource {
            var failing = false
            override fun summary(snapshot: DataSnapshot, now: LocalDateTime): CourseStats {
                if (failing) throw IllegalStateException("course source down")
                return CourseStats()
            }
        }
        var stamp = LocalDateTime.of(2026, 11, 10, 10, 0)
        val ticks = MutableSharedFlow<Unit>(replay = 1)
        ticks.emit(Unit)
        val vm = StatisticsViewModel(repository, courseStatsSource = source, scope = backgroundScope, now = { stamp }, ticks = ticks)
        seedBoundaryWeek()
        val before = vm.uiState.first { it.weekTaskCount == 4 }

        source.failing = true
        stamp = stamp.plusMinutes(1)
        ticks.emit(Unit)                             // tick 触发重算，来源失败
        // 与 retry() 失败一致：保留上一次成功值 + 错误文案，不以空结果覆盖。
        assertEquals(before.copy(error = "看板加载失败，请重试"), vm.uiState.first { it.error != null })
    }
}
