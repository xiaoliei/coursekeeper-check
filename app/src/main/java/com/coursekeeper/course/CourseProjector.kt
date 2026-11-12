package com.coursekeeper.course

import com.coursekeeper.data.model.CourseRecord
import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.PeriodRecord
import com.coursekeeper.data.model.ScheduleRecord
import com.coursekeeper.data.model.ScheduleWeeks
import com.coursekeeper.data.model.SemesterRecord
import com.coursekeeper.statistics.CourseStats
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 课程投影的纯计算部分（成员 B）。
 *
 * 输入 E 的一致性快照，输出课程状态；不读数据库、不取系统时间，所有时刻由调用方传入，
 * 因此可以在 JVM 单测里用固定时钟直接验证口径。
 */
object CourseProjector {

    fun project(snapshot: DataSnapshot, now: LocalDateTime): CourseState {
        val today = now.toLocalDate()
        val active = WeekCalculator.activeSemester(snapshot.semesters, today)
        if (active == null) {
            val configured = WeekCalculator.latestConfiguredSemester(snapshot.semesters)
            val phase = if (configured == null) CoursePhase.NO_SEMESTER else CoursePhase.NOT_IN_TERM
            return CourseState(
                phase = phase,
                semester = configured?.let { semesterInfo(it, null) },
                weekNo = null,
                todayEntries = emptyList(),
                todayLessonCount = 0,
                todayPeriodCount = 0,
                weekCourseCount = 0,
                weekEntryCount = 0,
                nextCourse = null,
            )
        }

        val week = WeekCalculator.currentWeek(active, today) ?: 1
        val courses = snapshot.courses.filter { it.semesterId == active.id }
        val courseIds = courses.mapTo(HashSet()) { it.id }
        val weekEntries = snapshot.schedules
            .filter { it.courseId in courseIds && ScheduleWeeks.contains(it.weeks, week) }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }, { it.endPeriod }))
        val todayEntries = weekEntries
            .filter { it.dayOfWeek == today.dayOfWeek.value }
            .map { it.toEntry(courses) }
        val periods = snapshot.periods.filter { it.semesterId == active.id }

        return CourseState(
            phase = CoursePhase.READY,
            semester = semesterInfo(active, week),
            weekNo = week,
            todayEntries = todayEntries,
            todayLessonCount = todayEntries.size,
            todayPeriodCount = todayEntries.sumOf { it.periodCount },
            weekCourseCount = weekEntries.map { it.courseId }.distinct().size,
            weekEntryCount = weekEntries.size,
            nextCourse = nextCourse(todayEntries, periods, now),
        )
    }

    /** 指定教学周的课表；学期未配置时返回空表。 */
    fun timetable(snapshot: DataSnapshot, weekNo: Int, today: LocalDate): WeekTimetable {
        val semester = activeOrLatest(snapshot, today) ?: return WeekTimetable(weekNo, emptyMap())
        val courses = snapshot.courses.filter { it.semesterId == semester.id }
        val courseIds = courses.mapTo(HashSet()) { it.id }
        val entries = snapshot.schedules
            .filter { it.courseId in courseIds && ScheduleWeeks.contains(it.weeks, weekNo) }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }, { it.endPeriod }))
        val days = (1..7).associateWith { day ->
            entries.filter { it.dayOfWeek == day }.map { it.toEntry(courses) }
        }
        return WeekTimetable(weekNo, days)
    }

    /** 指定教学周的冲突；同课程完全重复的条目已去重。 */
    fun conflicts(snapshot: DataSnapshot, weekNo: Int, today: LocalDate): List<CourseConflict> {
        val semester = activeOrLatest(snapshot, today) ?: return emptyList()
        val courses = snapshot.courses.filter { it.semesterId == semester.id }
        val courseIds = courses.mapTo(HashSet()) { it.id }
        val entries = snapshot.schedules
            .filter { it.courseId in courseIds && ScheduleWeeks.contains(it.weeks, weekNo) }
        return ConflictDetector.detect(ConflictDetector.slots(entries, courses))
    }

    fun weekOf(snapshot: DataSnapshot, date: LocalDate): Int? =
        WeekCalculator.activeSemester(snapshot.semesters, date)
            ?.let { WeekCalculator.currentWeek(it, date) }

    /** F 的 [com.coursekeeper.statistics.CourseStatsSource] 对接点。 */
    fun summary(snapshot: DataSnapshot, now: LocalDateTime): CourseStats {
        val state = project(snapshot, now)
        return CourseStats(
            todayLessonCount = state.todayLessonCount,
            todayPeriodCount = state.todayPeriodCount,
            weekCourseCount = state.weekCourseCount,
            weekEntryCount = state.weekEntryCount,
        )
    }

    private fun activeOrLatest(snapshot: DataSnapshot, today: LocalDate): SemesterRecord? =
        WeekCalculator.activeSemester(snapshot.semesters, today)
            ?: WeekCalculator.latestConfiguredSemester(snapshot.semesters)

    private fun semesterInfo(semester: SemesterRecord, week: Int?): SemesterInfo =
        SemesterInfo(
            id = semester.id,
            name = semester.name,
            startsOn = WeekCalculator.startMonday(semester) ?: semester.startsOn!!,
            weekCount = semester.weekCount,
            currentWeek = week,
        )

    private fun nextCourse(
        entries: List<CourseEntry>,
        periods: List<PeriodRecord>,
        now: LocalDateTime,
    ): CourseEntry? {
        if (entries.isEmpty() || periods.isEmpty()) return null
        val endsAt = periods.associate { it.number to it.endsAt }
        val currentTime = now.toLocalTime()
        return entries.firstOrNull { entry -> endsAt[entry.endPeriod]?.isAfter(currentTime) == true }
    }

    private fun ScheduleRecord.toEntry(courses: List<CourseRecord>): CourseEntry {
        val course = courses.firstOrNull { it.id == courseId }
        return CourseEntry(
            scheduleId = id,
            courseId = courseId,
            title = course?.title.orEmpty(),
            dayOfWeek = dayOfWeek,
            startPeriod = startPeriod,
            endPeriod = endPeriod,
            location = location,
            teacher = teacher ?: course?.teacher,
        )
    }
}
