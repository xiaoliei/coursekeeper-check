package com.coursekeeper.course

import com.coursekeeper.data.model.CourseRecord
import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.PeriodRecord
import com.coursekeeper.data.model.ScheduleRecord
import com.coursekeeper.data.model.SemesterRecord
import com.coursekeeper.fakedata.BoundaryFixtures
import com.coursekeeper.home.CourseAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 首页课程投影单测（成员 B，第 5 步）。
 *
 * 验收重点是状态语义：未设学期 / 非教学周 / 教学周无课，三者不能都显示“暂无数据”。
 */
class HomeCourseProjectionTest {

    private val now = BoundaryFixtures.NOW // 2026-11-10 周二 10:00

    @Test
    fun `未设学期映射为 NoSemester`() {
        val data = HomeCourseProjection.project(BoundaryFixtures.noSemester(), now)
        assertEquals(CourseAvailability.NoSemester, data.availability)
        assertEquals(0, data.todayCourseCount)
        assertEquals(0, data.weekCourseCount)
        assertTrue(data.courses.isEmpty())
        assertNull(data.nextCourse)
    }

    @Test
    fun `已配学期但不在教学周映射为 NotInTerm`() {
        val data = HomeCourseProjection.project(
            BoundaryFixtures.configuredSemesterNoSchedule(),
            LocalDateTime.of(2027, 6, 1, 10, 0),
        )
        assertEquals(CourseAvailability.NotInTerm, data.availability)
    }

    @Test
    fun `教学周内即使没课也是 Ready 且计数为 0`() {
        val data = HomeCourseProjection.project(BoundaryFixtures.configuredSemesterNoSchedule(), now)
        assertEquals(CourseAvailability.Ready, data.availability)
        assertEquals(0, data.todayCourseCount)
        assertEquals(0, data.weekCourseCount)
        assertEquals("", data.nextCourseHint)
    }

    @Test
    fun `跨节次课程按条目计数 并给出节次文案`() {
        val data = HomeCourseProjection.project(BoundaryFixtures.crossPeriodCourse(), now)
        assertEquals(CourseAvailability.Ready, data.availability)
        assertEquals(1, data.todayCourseCount)
        assertEquals(1, data.weekCourseCount)
        assertEquals(1, data.courses.size)
        assertEquals("第 1-4 节", data.courses.first().periods)
        assertEquals("", data.courses.first().time) // 没有官方节次时刻表时不编造钟点
        assertEquals("今天", data.nextCourseHint)
    }

    @Test
    fun `有节次时刻表时算下一节课与分钟提示`() {
        val snapshot = DataSnapshot(
            semesters = listOf(SemesterRecord("s1", "秋季", LocalDate.of(2026, 9, 7), 20)),
            periods = listOf(
                PeriodRecord("s1", 1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
                PeriodRecord("s1", 2, LocalTime.of(8, 55), LocalTime.of(9, 40)),
                PeriodRecord("s1", 3, LocalTime.of(10, 0), LocalTime.of(10, 45)),
                PeriodRecord("s1", 4, LocalTime.of(10, 55), LocalTime.of(11, 40)),
            ),
            courses = listOf(CourseRecord("c1", "数据结构", semesterId = "s1")),
            schedules = listOf(
                ScheduleRecord("sch1", "c1", dayOfWeek = 2, startPeriod = 1, endPeriod = 2,
                    weeks = WeekText.parse("1-16").weeks, location = "A101"),
                ScheduleRecord("sch2", "c1", dayOfWeek = 2, startPeriod = 3, endPeriod = 4,
                    weeks = WeekText.parse("1-16").weeks, location = "B202"),
            ),
        )
        val data = HomeCourseProjection.project(snapshot, LocalDateTime.of(2026, 11, 10, 9, 50))

        assertEquals(2, data.todayCourseCount)
        assertEquals("10 分钟后", data.nextCourseHint)
        assertEquals("sch2", data.nextCourse?.id)
        assertEquals("10:00 — 11:40", data.nextCourse?.time)
        assertEquals("第 3-4 节", data.nextCourse?.periods)
        assertEquals("B202", data.nextCourse?.location)
    }

    @Test
    fun `当天课程全部结束后给出结束提示`() {
        val snapshot = DataSnapshot(
            semesters = listOf(SemesterRecord("s1", "秋季", LocalDate.of(2026, 9, 7), 20)),
            periods = listOf(
                PeriodRecord("s1", 1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
                PeriodRecord("s1", 2, LocalTime.of(8, 55), LocalTime.of(9, 40)),
            ),
            courses = listOf(CourseRecord("c1", "数据结构", semesterId = "s1")),
            schedules = listOf(
                ScheduleRecord("sch1", "c1", dayOfWeek = 2, startPeriod = 1, endPeriod = 2,
                    weeks = WeekText.parse("1-16").weeks),
            ),
        )
        val data = HomeCourseProjection.project(snapshot, LocalDateTime.of(2026, 11, 10, 12, 0))
        assertNull(data.nextCourse)
        assertEquals("今日课程已结束", data.nextCourseHint)
    }
}
