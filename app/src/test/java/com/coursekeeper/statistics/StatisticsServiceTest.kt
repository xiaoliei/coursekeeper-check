package com.coursekeeper.statistics

import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.ScheduleRecord
import com.coursekeeper.data.model.SemesterRecord
import com.coursekeeper.data.model.TaskRecord
import com.coursekeeper.data.model.TaskType
import com.coursekeeper.data.model.WeekPattern
import com.coursekeeper.data.settings.AppSettings
import com.coursekeeper.fakedata.BoundaryFixtures
import com.coursekeeper.home.projectHome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * StatisticsService 冒烟测试（纯 JVM，无 Robolectric）。固定时钟沿用 BoundaryFixtures.NOW
 * = 2026-11-10 10:00（周二），本周 = [2026-11-09, 2026-11-16)。
 */
class StatisticsServiceTest {

    private val now = BoundaryFixtures.NOW

    private fun compute(snapshot: DataSnapshot, courseStats: CourseStats = CourseStats()) =
        StatisticsService.compute(snapshot, courseStats, now)

    private fun task(id: String, due: LocalDateTime? = null, completed: LocalDateTime? = null,
                     type: TaskType = TaskType.HOMEWORK) = TaskRecord(id, id, type, dueAt = due, completedAt = completed)

    @Test
    fun weekWindowAndCompletionMatchHomeProjection() {
        val snapshot = BoundaryFixtures.taskDateBoundary()
        val ui = compute(snapshot)
        val home = projectHome(snapshot, AppSettings(onboardingComplete = true), now)
        assertEquals(home.weekTaskCount, ui.weekTaskCount)
        assertEquals(home.weekCompletedCount, ui.weekCompletedCount)
        assertEquals(4, ui.weekTaskCount)          // 11-09 / 11-10 / 11-11 / 11-15
        assertEquals(1, ui.weekCompletedCount)
        assertEquals(0.25f, ui.completionRate!!, 0.0001f)
    }

    @Test
    fun undatedTasksAreExcludedFromWeeklyMetrics() {
        val ui = compute(DataSnapshot(tasks = listOf(task("undated"))))
        assertEquals(0, ui.weekTaskCount)
        assertNull(ui.completionRate)
        assertEquals(0, ui.dueDist.overdue)
    }

    @Test
    fun typeDistributionGroupsWeekTasksByType() {
        val tasks = listOf(
            task("a", LocalDateTime.of(2026, 11, 10, 12, 0), type = TaskType.HOMEWORK),
            task("b", LocalDateTime.of(2026, 11, 11, 10, 0), type = TaskType.LAB_REPORT),
            task("c", LocalDateTime.of(2026, 11, 11, 10, 0), type = TaskType.LAB_REPORT),
            task("d", LocalDateTime.of(2026, 11, 15, 9, 0), type = TaskType.CLUB),
        )
        val ui = compute(DataSnapshot(tasks = tasks))
        assertEquals(listOf(TaskType.HOMEWORK, TaskType.LAB_REPORT, TaskType.CLUB), ui.typeDistribution.map { it.type })
        assertEquals(listOf(1, 2, 1), ui.typeDistribution.map { it.count })
        assertEquals("课程作业", ui.typeDistribution.first().label)
    }

    @Test
    fun dueDistributionPerformsMutuallyExclusiveSplit() {
        val tasks = listOf(
            task("overdue", LocalDateTime.of(2026, 11, 9, 8, 0)),
            task("thisWeek-rem", now.plusHours(2)),
            task("next7", LocalDateTime.of(2026, 11, 18, 10, 0)),
            task("undated"),
        )
        val ui = compute(DataSnapshot(tasks = tasks))
        assertEquals(1, ui.dueDist.overdue)    // 2026-11-09 08:00 早于 now，计 overdue
        assertEquals(1, ui.dueDist.thisWeek)   // 2026-11-10 10:00+2h 仍在本周
        assertEquals(1, ui.dueDist.next7Days)  // 2026-11-18 在 [11-16, 11-23)
    }

    @Test
    fun emptySnapshotShowsNoData() {
        val ui = compute(DataSnapshot())
        assertNull(ui.completionRate)
        assertNull(ui.pressureScore)
        assertEquals("", ui.pressureLevel)
        assertEquals("暂无数据", ui.explanation)
        assertEquals(0, ui.dueDist.overdue)
        assertEquals(0, ui.dueDist.thisWeek)
        assertEquals(0, ui.dueDist.next7Days)
    }

    @Test
    fun pressureScoreIsDeterministicWithDominantFactors() {
        val ui = compute(BoundaryFixtures.taskDateBoundary())
        // 任务密度 4/6=0.667 ×40% + 临近截止 2/3=0.667 ×20% → 40
        assertEquals(40, ui.pressureScore)
        assertEquals("适中", ui.pressureLevel)
        assertEquals("本周任务较集中；有较早的截止任务", ui.explanation)
    }

    @Test
    fun courseLoadWeightUsesPeriodCountFromCourseStats() {
        val ui = compute(DataSnapshot(tasks = emptyList()), CourseStats(todayPeriodCount = 8))
        assertEquals(30, ui.pressureScore)     // 8/8=1.0 × 30% → 30（课程负荷满分）
        assertEquals("今日课程较多", ui.explanation)
    }

    @Test
    fun intervalAdapterCountsCrossPeriodAsOneEntryFourPeriods() {
        val adapter = IntervalCourseStatsAdapter()
        assertEquals(CourseStats(1, 4, 1, 1), adapter.summary(BoundaryFixtures.crossPeriodCourse(), now))
    }

    @Test
    fun intervalAdapterDeduplicatesCourseWhileCountingEntries() {
        val adapter = IntervalCourseStatsAdapter()
        assertEquals(CourseStats(1, 2, 1, 2), adapter.summary(BoundaryFixtures.sameCourseMultipleEntries(), now))
    }

    @Test
    fun intervalAdapterCountsSameTitleDistinctCodesAsTwoCourses() {
        val adapter = IntervalCourseStatsAdapter()
        assertEquals(CourseStats(1, 2, 2, 2), adapter.summary(BoundaryFixtures.sameTitleDistinctCodes(), now))
    }

    @Test
    fun intervalAdapterAppliesWeekParityToCurrentWeek() {
        val adapter = IntervalCourseStatsAdapter()
        // 教学周 10（偶数周）：ODD 不生效，EVEN 生效。
        assertEquals(CourseStats(0, 0, 0, 0), adapter.summary(BoundaryFixtures.alternatingWeeks(), now))
        val even = DataSnapshot(
            semesters = listOf(SemesterRecord("s1", "秋季", LocalDate.of(2026, 9, 7), 20)),
            schedules = listOf(ScheduleRecord("sch-even", "c1", dayOfWeek = 2, startPeriod = 1, endPeriod = 2,
                startWeek = 10, endWeek = 10, weekPattern = WeekPattern.EVEN)),
        )
        assertEquals(CourseStats(1, 2, 1, 1), adapter.summary(even, now))
    }
}
