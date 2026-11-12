package com.coursekeeper.course

import com.coursekeeper.data.model.DataSnapshot
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime

/** 课程数据当前所处的状态（对应 A 首页 `CourseAvailability` 的课程侧表达）。 */
enum class CoursePhase {
    /** 学期已配置且今天在教学周内。 */
    READY,

    /** 从未设置学期起始日：显示“待设置学期”，**不能当作 0**。 */
    NO_SEMESTER,

    /** 已配置学期，但今天不在 1..weekCount 内（假期/学期之间）。 */
    NOT_IN_TERM,
}

/** 一条排课在界面上需要的全部信息；`teacher` 已按“条目覆盖课程”补齐。 */
data class CourseEntry(
    val scheduleId: String,
    val courseId: String,
    val title: String,
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val location: String?,
    val teacher: String?,
) {
    /** 该条目占的节数（跨节次只算一条，但节数要累加）。 */
    val periodCount: Int get() = endPeriod - startPeriod + 1
}

/** 一周课表：`days` 的键为 1..7（1=周一），同日按起始节排序。 */
data class WeekTimetable(
    val weekNo: Int,
    val days: Map<Int, List<CourseEntry>>,
) {
    val entryCount: Int get() = days.values.sumOf { it.size }
    val periodCount: Int get() = days.values.sumOf { day -> day.sumOf { it.periodCount } }
}

data class SemesterInfo(
    val id: String,
    val name: String,
    val startsOn: LocalDate,
    val weekCount: Int,
    val currentWeek: Int?,
)

/**
 * 首页与课表共用的课程快照。
 *
 * 四个数字的口径见《统计口径与规则草案 v0.2》第一节：
 * 今日课程数按**条目**、课程负荷按**节数**、本周课程数按**门**（不同课程去重）。
 */
data class CourseState(
    val phase: CoursePhase,
    val semester: SemesterInfo?,
    val weekNo: Int?,
    val todayEntries: List<CourseEntry>,
    val todayLessonCount: Int,
    val todayPeriodCount: Int,
    val weekCourseCount: Int,
    val weekEntryCount: Int,
    /** 今天当前时刻之后最近的一条；今天没有剩余课程时为 null。 */
    val nextCourse: CourseEntry?,
) {
    val hasCourses: Boolean get() = todayEntries.isNotEmpty() || weekEntryCount > 0
}

/**
 * 课程业务服务（成员 B）。
 *
 * 读侧以 E 的一致性快照 [DataSnapshot] 为输入，纯函数式投影，便于单测与多页面共用；
 * 写侧（新增/编辑/删除/学期配置）在下一步接入 `CourseKeeperRepository`。
 */
interface CourseService {

    /** 数据变化即重算的课程快照，供课表页订阅。 */
    val state: Flow<CourseState>

    /** 当前快照的一次性读取。 */
    suspend fun stateNow(): CourseState

    /** 纯投影：给定快照与时刻计算课程状态（A 首页、F 统计共用）。 */
    fun project(snapshot: DataSnapshot, now: LocalDateTime = LocalDateTime.now()): CourseState

    /** 指定教学周的课表；学期未配置时返回空表。 */
    fun timetable(snapshot: DataSnapshot, weekNo: Int): WeekTimetable

    /** 指定教学周的冲突；同课程重复条目已去重。 */
    fun conflicts(snapshot: DataSnapshot, weekNo: Int): List<CourseConflict>

    /** 日期落在第几教学周；不在学期内返回 null。 */
    fun weekOf(snapshot: DataSnapshot, date: LocalDate): Int?
}
