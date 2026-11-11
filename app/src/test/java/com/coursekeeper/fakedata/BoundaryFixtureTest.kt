package com.coursekeeper.fakedata

import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.settings.AppSettings
import com.coursekeeper.home.CourseAvailability
import com.coursekeeper.home.projectHome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.coursekeeper.data.model.ScheduleWeeks
import org.junit.Test

/**
 * 边界假数据冒烟测试：锁定冻结素材的关键数字，并确认素材能被现有首页投影消费。
 * 不引入新的统计公式；W2 的 StatisticsService / 看板测试从此目录统一取素材。
 */
class BoundaryFixtureTest {

    private fun project(snapshot: DataSnapshot) = projectHome(snapshot, AppSettings(), BoundaryFixtures.NOW)

    @Test
    fun taskDateBoundaryLocksWeekWindowAsHalfOpenInterval() {
        val home = project(BoundaryFixtures.taskDateBoundary())
        assertEquals(4, home.weekTaskCount)     // 11-09 / 11-10 / 11-11 / 11-15；不含待定、11-08、11-16
        assertEquals(1, home.weekCompletedCount)
        assertEquals(1f / 4f, home.weekProgress, 0.0001f)
        assertEquals(1, home.todayDueCount)
        assertEquals(1, home.todayCompletedCount)
        assertEquals("undated", home.tasks.first().id)      // 截止待定排最前
        assertEquals("截止待定", home.tasks.first().dueLabel)
        assertFalse(home.tasks.first { it.id == "nextMondayMidnight" }.dueToday) // 下周一 00:00 非今日截止
    }

    @Test
    fun semesterBoundariesDistinguishNoSemesterFromConfigured() {
        assertEquals(CourseAvailability.NoSemester, project(BoundaryFixtures.noSemester()).courseAvailability)
        assertEquals("待设置学期", project(BoundaryFixtures.noSemester()).weekLabel)
        assertEquals(CourseAvailability.Unavailable, project(BoundaryFixtures.configuredSemesterNoSchedule()).courseAvailability)
        assertEquals("学期已设置", project(BoundaryFixtures.configuredSemesterNoSchedule()).weekLabel)
    }

    @Test
    fun crossPeriodCourseIsSingleEntrySpanningFourPeriods() {
        val snap = BoundaryFixtures.crossPeriodCourse()
        assertEquals(1, snap.schedules.size)
        val schedule = snap.schedules.single()
        assertEquals(1, schedule.startPeriod)
        assertEquals(4, schedule.endPeriod)
        assertEquals(4, schedule.endPeriod - schedule.startPeriod + 1) // 锚定“跨节次=4 节”
    }

    @Test
    fun sameCourseMultipleEntriesKeepOneDistinctCourse() {
        val snap = BoundaryFixtures.sameCourseMultipleEntries()
        assertEquals(2, snap.schedules.size)
        assertEquals(1, snap.courses.size)
        assertEquals(setOf("c1"), snap.schedules.map { it.courseId }.toSet()) // 多条条目仍是一门
    }

    @Test
    fun sameTitleDistinctCodesAreTwoCourses() {
        val snap = BoundaryFixtures.sameTitleDistinctCodes()
        assertEquals(2, snap.courses.distinctBy { it.id }.size)
        assertEquals(2, snap.courses.map { it.externalId }.distinct().size) // 同名不同编号 = 两门
        assertEquals(1, snap.courses.map { it.title }.distinct().size)
    }

    @Test
    fun alternatingWeeksStayExpressibleInCurrentModel() {
        val snap = BoundaryFixtures.alternatingWeeks()
        assertTrue(ScheduleWeeks.contains(snap.schedules.single().weeks, 9))
        assertFalse(ScheduleWeeks.contains(snap.schedules.single().weeks, 10))
        assertTrue(ScheduleWeeks.contains(snap.schedules.single().weeks, 11))
    }

    @Test
    fun nonConsecutiveWeeksAreRepresentedWithoutFillingTheGap() {
        val schedule = BoundaryFixtures.nonConsecutiveWeeks().schedules.single()
        assertEquals(7424L, schedule.weeks)
        assertEquals("9,11-13", schedule.weeksText)
        assertFalse(ScheduleWeeks.contains(schedule.weeks, 10))
        assertEquals(listOf(9, 11, 12, 13), (1..64).filter { ScheduleWeeks.contains(schedule.weeks, it) })
    }
}
