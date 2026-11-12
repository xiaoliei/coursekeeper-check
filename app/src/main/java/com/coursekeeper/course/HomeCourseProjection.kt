package com.coursekeeper.course

import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.PeriodRecord
import com.coursekeeper.home.CourseAvailability
import com.coursekeeper.home.CourseUi
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 首页课程投影（成员 B，第 5 步）。
 *
 * A 的 `projectHome()` 目前把 `courseAvailability` 写死为 `Unavailable`（等 B 的占位）。
 * 接上本函数后，首页的六个课程字段一次算出，A 只需把结果赋给 `HomeUiState`：
 *
 * ```
 * val course = HomeCourseProjection.project(snapshot, now)
 * courseAvailability = course.availability,
 * todayCourseCount = course.todayCourseCount,
 * weekCourseCount = course.weekCourseCount,
 * courses = course.courses,
 * nextCourse = course.nextCourse,
 * nextCourseHint = course.nextCourseHint,
 * ```
 *
 * 状态语义（替换旧的 `Unavailable` 占位）：
 * - 未设学期 → `NoSemester`（显示“待设置学期”，不是 0）
 * - 已设学期但今天不在 1..weekCount → `NotInTerm`
 * - 在教学周 → `Ready`，没课就是 0
 *
 * `Unavailable` 只保留给“读取失败”，不再表示“课程服务未接入”。
 */
object HomeCourseProjection {

    data class HomeCourseData(
        val availability: CourseAvailability,
        val todayCourseCount: Int,
        val weekCourseCount: Int,
        val courses: List<CourseUi>,
        val nextCourse: CourseUi?,
        val nextCourseHint: String,
    )

    private val Clock = DateTimeFormatter.ofPattern("HH:mm")

    fun project(snapshot: DataSnapshot, now: LocalDateTime): HomeCourseData {
        val state = CourseProjector.project(snapshot, now)
        val availability = when (state.phase) {
            CoursePhase.READY -> CourseAvailability.Ready
            CoursePhase.NO_SEMESTER -> CourseAvailability.NoSemester
            CoursePhase.NOT_IN_TERM -> CourseAvailability.NotInTerm
        }
        val semesterId = state.semester?.id
        val periods = snapshot.periods
            .filter { it.semesterId == semesterId }
            .associateBy { it.number }

        val courses = state.todayEntries.map { it.toCourseUi(periods) }
        val next = nextEntry(state.todayEntries, periods, now)
        val hint = when {
            next == null && courses.isEmpty() -> ""
            next == null -> "今日课程已结束"
            else -> hint(next, periods, now)
        }
        return HomeCourseData(
            availability = availability,
            todayCourseCount = state.todayLessonCount,
            weekCourseCount = state.weekCourseCount,
            courses = courses,
            nextCourse = next?.toCourseUi(periods),
            nextCourseHint = hint,
        )
    }

    private fun nextEntry(
        entries: List<CourseEntry>,
        periods: Map<Int, PeriodRecord>,
        now: LocalDateTime,
    ): CourseEntry? {
        if (entries.isEmpty()) return null
        if (periods.isEmpty()) {
            // 没有官方节次时刻表时不猜时间：取当天最早的一条作为“下一节”。
            return entries.first()
        }
        val today = now.toLocalDate()
        return entries.firstOrNull { entry ->
            val end = periods[entry.endPeriod]?.endsAt ?: return@firstOrNull false
            today.atTime(end).isAfter(now)
        }
    }

    private fun hint(entry: CourseEntry, periods: Map<Int, PeriodRecord>, now: LocalDateTime): String {
        val start = periods[entry.startPeriod]?.startsAt ?: return "今天"
        val startAt = now.toLocalDate().atTime(start)
        val minutes = Duration.between(now, startAt).toMinutes()
        return when {
            minutes in 1..59 -> "$minutes 分钟后"
            minutes > 59 -> "今天 ${start.format(Clock)}"
            else -> "进行中"
        }
    }

    private fun CourseEntry.toCourseUi(periods: Map<Int, PeriodRecord>): CourseUi {
        val start = periods[startPeriod]?.startsAt
        val end = periods[endPeriod]?.endsAt
        val time = if (start != null && end != null) "${start.format(Clock)} — ${end.format(Clock)}" else ""
        val periodText = if (startPeriod == endPeriod) "第 $startPeriod 节" else "第 $startPeriod-$endPeriod 节"
        return CourseUi(
            id = scheduleId,
            title = title.ifBlank { "未命名课程" },
            time = time,
            location = location.orEmpty(),
            periods = periodText,
        )
    }
}
