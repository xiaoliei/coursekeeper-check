package com.coursekeeper.course

import com.coursekeeper.data.model.CourseRecord
import com.coursekeeper.data.model.ScheduleRecord
import com.coursekeeper.data.model.ScheduleWeeks

/** 冲突种类：不同课程抢同一时段；或同一门课同时段但地点/教师不一致（数据异常）。 */
enum class ConflictKind { DIFFERENT_COURSE, SAME_COURSE_DIFFERENT_DETAILS }

/** 参与冲突判定的一条排课（已补齐课程标题与有效教师）。 */
data class ConflictSlot(
    val scheduleId: String,
    val courseId: String,
    val courseTitle: String,
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeks: Long,
    val location: String?,
    val teacher: String?,
)

data class CourseConflict(
    val first: ConflictSlot,
    val second: ConflictSlot,
    val kind: ConflictKind,
    /** 两条排课重叠的周次（交集位图）。 */
    val weeks: Long,
    /** 重叠的节次区间。 */
    val periods: IntRange,
) {
    val dayOfWeek: Int get() = first.dayOfWeek
    val weeksText: String get() = ScheduleWeeks.format(weeks)
}

/**
 * 冲突检测（成员 B）。
 *
 * 《课程与周次规则 v0.1》第八节：同一星期 + 节次区间相交 + 周次位图相交；
 * 不同课程算冲突，同一课程同时段但地点/教师不同按数据异常提示，
 * 完全相同的重复条目视为导出重复，直接去重、不报冲突。
 *
 * 只用星期、节次号、周次判定，**不用具体时刻**（第四节）。
 */
object ConflictDetector {

    fun detect(slots: List<ConflictSlot>): List<CourseConflict> {
        val result = mutableListOf<CourseConflict>()
        for (i in slots.indices) {
            for (j in i + 1 until slots.size) {
                val first = slots[i]
                val second = slots[j]
                if (first.dayOfWeek != second.dayOfWeek) continue
                val overlapWeeks = first.weeks and second.weeks
                if (overlapWeeks == 0L) continue
                val start = maxOf(first.startPeriod, second.startPeriod)
                val end = minOf(first.endPeriod, second.endPeriod)
                if (start > end) continue
                when {
                    first.courseId != second.courseId ->
                        result += CourseConflict(first, second, ConflictKind.DIFFERENT_COURSE, overlapWeeks, start..end)
                    first.location != second.location || first.teacher != second.teacher ->
                        result += CourseConflict(
                            first, second, ConflictKind.SAME_COURSE_DIFFERENT_DETAILS, overlapWeeks, start..end,
                        )
                    else -> Unit // 同课程同地点同教师的重复条目：去重，不算冲突
                }
            }
        }
        return result
    }

    /** 把持久化的排课记录转成判定输入；课程已删除的排课直接跳过。 */
    fun slots(schedules: List<ScheduleRecord>, courses: List<CourseRecord>): List<ConflictSlot> =
        schedules.mapNotNull { schedule ->
            val course = courses.firstOrNull { it.id == schedule.courseId } ?: return@mapNotNull null
            ConflictSlot(
                scheduleId = schedule.id,
                courseId = course.id,
                courseTitle = course.title,
                dayOfWeek = schedule.dayOfWeek,
                startPeriod = schedule.startPeriod,
                endPeriod = schedule.endPeriod,
                weeks = schedule.weeks,
                location = schedule.location,
                teacher = schedule.teacher ?: course.teacher,
            )
        }
}
