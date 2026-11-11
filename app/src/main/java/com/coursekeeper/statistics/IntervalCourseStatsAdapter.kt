package com.coursekeeper.statistics

import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.ScheduleRecord
import com.coursekeeper.data.model.SemesterRecord
import com.coursekeeper.data.model.WeekPattern
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 课程统计的**临时适配器**（待 B 的 CourseService 替换）。
 *
 * 仅用于让 F 的统计服务在当前区间模型（startWeek/endWeek/weekPattern）下可跑通、可测试。
 * B v1.0 生效（依赖 E v3 迁移，排课周次改位图 weeks + weeksText）后，由 B 的 CourseService
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
            if (week !in schedule.startWeek..schedule.endWeek) return false
            return when (schedule.weekPattern) {
                WeekPattern.ALL -> true
                WeekPattern.ODD -> week % 2 == 1
                WeekPattern.EVEN -> week % 2 == 0
            }
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
