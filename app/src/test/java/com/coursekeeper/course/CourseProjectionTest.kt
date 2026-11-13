package com.coursekeeper.course

import com.coursekeeper.data.model.CourseRecord
import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.PeriodRecord
import com.coursekeeper.data.model.ScheduleRecord
import com.coursekeeper.data.model.SemesterRecord
import com.coursekeeper.data.model.RecordSource
import com.coursekeeper.fakedata.BoundaryFixtures
import com.coursekeeper.statistics.CourseStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 课程投影单测（成员 B）。
 *
 * 关键验收：四个课程数字由课程投影统一计算，统计层只消费投影结果。
 */
class CourseProjectionTest {

    @Test
    fun `四个课程数字统计契约符合固定期望`() {
        val fixtures = listOf(
            Fixture("跨节次", BoundaryFixtures.crossPeriodCourse(), CourseStats(1, 4, 1, 1)),
            Fixture("同课多条目", BoundaryFixtures.sameCourseMultipleEntries(), CourseStats(1, 2, 1, 2)),
            Fixture("同名不同编号", BoundaryFixtures.sameTitleDistinctCodes(), CourseStats(1, 2, 2, 2)),
            Fixture("单双周", BoundaryFixtures.alternatingWeeks(), CourseStats(0, 0, 0, 0)),
            Fixture("无学期", BoundaryFixtures.noSemester(), CourseStats(0, 0, 0, 0)),
            Fixture("有学期无排课", BoundaryFixtures.configuredSemesterNoSchedule(), CourseStats(0, 0, 0, 0)),
            Fixture("仅任务数据", BoundaryFixtures.taskDateBoundary(), CourseStats(0, 0, 0, 0)),
        )
        fixtures.forEach { fixture ->
            assertEquals(fixture.expected, CourseProjector.summary(fixture.snapshot, BoundaryFixtures.NOW))
        }
    }

    private data class Fixture(
        val name: String,
        val snapshot: DataSnapshot,
        val expected: CourseStats,
    )

    @Test
    fun `跨节次条目按条计 按节累加`() {
        val state = CourseProjector.project(BoundaryFixtures.crossPeriodCourse(), BoundaryFixtures.NOW)
        assertEquals(CoursePhase.READY, state.phase)
        assertEquals(1, state.todayLessonCount)
        assertEquals(4, state.todayPeriodCount)
        assertEquals(1, state.weekCourseCount)
        assertEquals(1, state.weekEntryCount)
    }

    @Test
    fun `同课程多条排课按条目计 按门去重`() {
        val state = CourseProjector.project(BoundaryFixtures.sameCourseMultipleEntries(), BoundaryFixtures.NOW)
        assertEquals(1, state.todayLessonCount)
        assertEquals(1, state.weekCourseCount)
        assertEquals(2, state.weekEntryCount)
    }

    @Test
    fun `同名不同编号算两门`() {
        val state = CourseProjector.project(BoundaryFixtures.sameTitleDistinctCodes(), BoundaryFixtures.NOW)
        assertEquals(1, state.todayLessonCount)
        assertEquals(2, state.weekCourseCount)
    }

    @Test
    fun `非连续周 第10周没有课 第11周有课`() {
        val weeks = (1L shl 8) or (1L shl 10) or (1L shl 11) or (1L shl 12) // 9,11-13
        val snapshot = DataSnapshot(
            semesters = listOf(SemesterRecord("s1", "秋季", LocalDate.of(2026, 9, 7), 20)),
            courses = listOf(CourseRecord("c1", "数据结构", semesterId = "s1")),
            schedules = listOf(
                ScheduleRecord("sch1", "c1", dayOfWeek = 2, startPeriod = 1, endPeriod = 2,
                    weeks = weeks, weeksText = "9,11-13", location = "A101"),
            ),
        )

        val week10 = CourseProjector.project(snapshot, LocalDateTime.of(2026, 11, 10, 10, 0))
        assertEquals(10, week10.weekNo)
        assertEquals(0, week10.todayLessonCount)
        assertEquals(0, week10.weekEntryCount)

        val week11 = CourseProjector.project(snapshot, LocalDateTime.of(2026, 11, 17, 10, 0))
        assertEquals(11, week11.weekNo)
        assertEquals(1, week11.todayLessonCount)
        assertEquals(2, week11.todayPeriodCount)
        assertEquals(1, week11.weekCourseCount)
    }

    @Test
    fun `未设学期是 NO_SEMESTER 不是 0`() {
        val state = CourseProjector.project(BoundaryFixtures.noSemester(), BoundaryFixtures.NOW)
        assertEquals(CoursePhase.NO_SEMESTER, state.phase)
        assertNull(state.semester)
        assertNull(state.weekNo)
    }

    @Test
    fun `已配学期但今天不在教学周内是 NOT_IN_TERM`() {
        val state = CourseProjector.project(
            BoundaryFixtures.configuredSemesterNoSchedule(),
            LocalDateTime.of(2027, 6, 1, 10, 0),
        )
        assertEquals(CoursePhase.NOT_IN_TERM, state.phase)
        assertEquals("秋季", state.semester?.name)
        assertNull(state.weekNo)
    }

    @Test
    fun `下一节课取当前时刻之后最近的一条`() {
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
        val state = CourseProjector.project(snapshot, LocalDateTime.of(2026, 11, 10, 10, 0))
        assertEquals(2, state.todayLessonCount)
        assertEquals("sch2", state.nextCourse?.scheduleId)
        assertEquals(4, state.todayPeriodCount)
    }

    @Test
    fun `课表按天分组 空周返回空表`() {
        val snapshot = BoundaryFixtures.sameCourseMultipleEntries()
        val timetable = CourseProjector.timetable(snapshot, 10, BoundaryFixtures.NOW.toLocalDate())
        assertEquals(1, timetable.days.getValue(2).size)
        assertEquals(1, timetable.days.getValue(4).size)
        assertTrue(CourseProjector.timetable(snapshot, 30, BoundaryFixtures.NOW.toLocalDate()).entryCount == 0)
    }

    @Test
    fun `冲突按周返回 同课程重复条目已去重`() {
        val snapshot = DataSnapshot(
            semesters = listOf(SemesterRecord("s1", "秋季", LocalDate.of(2026, 9, 7), 20)),
            courses = listOf(
                CourseRecord("c1", "数据结构", semesterId = "s1"),
                CourseRecord("c2", "操作系统", semesterId = "s1"),
            ),
            schedules = listOf(
                ScheduleRecord("sch1", "c1", dayOfWeek = 2, startPeriod = 1, endPeriod = 2,
                    weeks = WeekText.parse("1-16").weeks),
                ScheduleRecord("sch2", "c2", dayOfWeek = 2, startPeriod = 2, endPeriod = 3,
                    weeks = WeekText.parse("1-16").weeks),
            ),
        )
        val conflicts = CourseProjector.conflicts(snapshot, 10, BoundaryFixtures.NOW.toLocalDate())
        assertEquals(1, conflicts.size)
        assertEquals(ConflictKind.DIFFERENT_COURSE, conflicts[0].kind)
    }

    @Test
    fun `同步来源的课程与手动课程显示一致`() {
        fun snapshotOf(source: RecordSource) = DataSnapshot(
            semesters = listOf(SemesterRecord("s1", "秋季", LocalDate.of(2026, 9, 7), 20)),
            courses = listOf(
                CourseRecord(
                    id = "c1", title = "数据结构", semesterId = "s1", teacher = "教师甲",
                    source = source, externalId = if (source == RecordSource.SYNC) "CST4823A" else null,
                ),
            ),
            schedules = listOf(
                ScheduleRecord("sch1", "c1", dayOfWeek = 2, startPeriod = 1, endPeriod = 4,
                    weeks = WeekText.parse("9,11-13").weeks, weeksText = "9,11-13", location = "A101"),
            ),
        )

        val manual = CourseProjector.project(snapshotOf(RecordSource.MANUAL), BoundaryFixtures.NOW)
        val synced = CourseProjector.project(snapshotOf(RecordSource.SYNC), BoundaryFixtures.NOW)

        assertEquals(manual.todayLessonCount, synced.todayLessonCount)
        assertEquals(manual.todayPeriodCount, synced.todayPeriodCount)
        assertEquals(manual.weekCourseCount, synced.weekCourseCount)
        assertEquals(manual.weekEntryCount, synced.weekEntryCount)
        assertEquals(manual.todayEntries.map { it.title to it.teacher }, synced.todayEntries.map { it.title to it.teacher })

        val syncedTimetable = CourseProjector.timetable(snapshotOf(RecordSource.SYNC), 11, BoundaryFixtures.NOW.toLocalDate())
        assertEquals("数据结构", syncedTimetable.days.getValue(2).single().title)
    }
}
