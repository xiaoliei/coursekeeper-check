package com.coursekeeper.statistics

import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.ScheduleRecord
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
        val currentWeek = snapshot.semesters.firstOrNull { it.startsOn != null }?.let { semester ->
            val start = semester.startsOn!!.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            if (!today.isBefore(start) && today.isBefore(start.plusWeeks(semester.weekCount.toLong()))) {
                ChronoUnit.WEEKS.between(start, monday).toInt() + 1
            } else null
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

        val todayEntries = snapshot.schedules.filter { active(it) && it.dayOfWeek == today.dayOfWeek.value }
        val weekEntries = snapshot.schedules.filter(::active)
        return CourseStats(
            todayLessonCount = todayEntries.size,
            todayPeriodCount = todayEntries.sumOf { it.endPeriod - it.startPeriod + 1 },
            weekCourseCount = weekEntries.map { it.courseId }.distinct().size,
            weekEntryCount = weekEntries.size,
        )
    }
}
