package com.coursekeeper.statistics

import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.ScheduleRecord
import com.coursekeeper.data.model.SemesterRecord
import com.coursekeeper.data.model.ScheduleWeeks
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 课程统计的**临时适配器**（待 B 的 CourseService 替换）。
 *
 * 兼容原入口名称，周次读取已随 E v3 对齐 weeks 位图，支持非连续周次。
 * B v1.0 生效并交付正式服务后，由 B 的 CourseService
 * 实现 [CourseStatsSource]，本适配器删除。此组件只读 semesters/courses/schedules，不触碰任务与压力逻辑。
 */
class IntervalCourseStatsAdapter : CourseStatsSource {
    override fun summary(snapshot: DataSnapshot, now: LocalDateTime): CourseStats {
        val today = now.toLocalDate()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val currentSemester = snapshot.semesters
            .filter { it.startsOn != null }
            .filter { semester -> semester.contains(today) }
            .maxByOrNull { it.startsOn!! }
        val currentWeek = currentSemester?.let { semester ->
            val start = semester.startsOn!!.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            ChronoUnit.WEEKS.between(start, monday).toInt() + 1
        }

        fun active(schedule: ScheduleRecord): Boolean {
            val week = currentWeek ?: return false
            return ScheduleWeeks.contains(schedule.weeks, week)
        }

        val activeSchedules = snapshot.schedules.filter { schedule ->
            snapshot.courses.firstOrNull { it.id == schedule.courseId }?.semesterId == currentSemester?.id
        }
        val todayEntries = activeSchedules.filter { active(it) && it.dayOfWeek == today.dayOfWeek.value }
        val weekEntries = activeSchedules.filter(::active)
        return CourseStats(
            todayLessonCount = todayEntries.size,
            todayPeriodCount = todayEntries.sumOf { it.endPeriod - it.startPeriod + 1 },
            weekCourseCount = weekEntries.map { it.courseId }.distinct().size,
            weekEntryCount = weekEntries.size,
        )
    }

    private fun SemesterRecord.contains(date: LocalDate): Boolean {
        val start = startsOn!!.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return !date.isBefore(start) && date.isBefore(start.plusWeeks(weekCount.toLong()))
    }
}
