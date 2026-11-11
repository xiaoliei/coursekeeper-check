package com.coursekeeper.statistics

import com.coursekeeper.data.DataTestBase
import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.TaskType
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        val vm = StatisticsViewModel(repository, scope = backgroundScope, now = { now })
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
        val vm = StatisticsViewModel(repository, courseStatsSource = source, scope = backgroundScope, now = { now })
        seedBoundaryWeek()
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
}
