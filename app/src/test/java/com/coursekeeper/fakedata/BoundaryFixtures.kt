package com.coursekeeper.fakedata

import com.coursekeeper.data.model.CourseRecord
import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.RecordSource
import com.coursekeeper.data.model.ScheduleRecord
import com.coursekeeper.data.model.SemesterRecord
import com.coursekeeper.data.model.TaskRecord
import com.coursekeeper.data.model.TaskType
import com.coursekeeper.data.model.WeekPattern
import com.coursekeeper.data.model.ScheduleWeeks
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * F 的“边界假数据”统一素材（JVM 单测专用，不进生产代码）。
 *
 * 固定演示时钟 now = 2026-11-10 10:00（周二）；教学周为 2026-11-09 周一 ~ 2026-11-15 周日，
 * 下周一 = 2026-11-16（开区间右界）。与 HomeProjectionTest / DataTestBase 使用的日期一致。
 *
 * 用途：供 F 的 W2 StatisticsService 与看板测试、以及现有首页投影回归测试统一取用。
 * 排课使用 E v3 的 weeks 位图；非连续周样例已可供读写和统计适配器回归使用。
 */
object BoundaryFixtures {
    /** 固定“现在”：2026-11-10 10:00（周二）。 */
    val NOW: LocalDateTime = LocalDateTime.of(2026, 11, 10, 10, 0)

    private fun semester(startsOn: LocalDate?) = SemesterRecord("s1", "秋季", startsOn, weekCount = 20, version = 1)
    private fun course(id: String, title: String, externalId: String? = null) =
        CourseRecord(id, title, semesterId = "s1", source = RecordSource.MANUAL, externalId = externalId, version = 1)
    private fun schedule(courseId: String, day: Int, start: Int, end: Int, pattern: WeekPattern = WeekPattern.ALL) =
        ScheduleRecord("sch-$courseId-$day-$start", courseId, day, start, end, startWeek = 10, endWeek = 10, weekPattern = pattern)
    private fun task(id: String, due: LocalDateTime? = null, completed: LocalDateTime? = null, type: TaskType = TaskType.HOMEWORK) =
        TaskRecord(id, id, type, dueAt = due, completedAt = completed, source = RecordSource.MANUAL, version = 1)

    // ---- 课程/排课边界（当前模型可表达；供 B CourseService / F 统计消费） ----

    /** 跨节次课：一条排课条目占第 1-4 节（endPeriod - startPeriod + 1 = 4），不拆行。 */
    fun crossPeriodCourse(): DataSnapshot = DataSnapshot(
        semesters = listOf(semester(LocalDate.of(2026, 9, 7))),
        courses = listOf(course("c1", "数据结构")),
        schedules = listOf(schedule("c1", day = 2, start = 1, end = 4)),
    )

    /** 同课程多条排课条目：今日课程按条目数计，本周课程按课程去重 = 1 门。 */
    fun sameCourseMultipleEntries(): DataSnapshot = DataSnapshot(
        semesters = listOf(semester(LocalDate.of(2026, 9, 7))),
        courses = listOf(course("c1", "数据结构")),
        schedules = listOf(schedule("c1", day = 2, start = 1, end = 2), schedule("c1", day = 4, start = 3, end = 3)),
    )

    /** 同名不同编号：两个不同 courseId/externalId，按 B v0.1 定义算两门。 */
    fun sameTitleDistinctCodes(): DataSnapshot = DataSnapshot(
        semesters = listOf(semester(LocalDate.of(2026, 9, 7))),
        courses = listOf(course("c1", "高等数学", externalId = "MATH101"), course("c2", "高等数学", externalId = "MATH102")),
        schedules = listOf(schedule("c1", day = 2, start = 1, end = 2), schedule("c2", day = 3, start = 1, end = 2)),
    )

    /** 单周位图：第 10 周不生效，第 9/11 周生效。 */
    fun alternatingWeeks(): DataSnapshot = DataSnapshot(
        semesters = listOf(semester(LocalDate.of(2026, 9, 7))),
        courses = listOf(course("c1", "数据结构")),
        schedules = listOf(ScheduleRecord("sch-odd", "c1", 2, 1, 2,
            weeks = ScheduleWeeks.fromRange(1, 20, WeekPattern.ODD))),
    )

    // ---- 学期边界 ----

    /** 无学期（未设置起始日）：首页显示 NoSemester/“待设置学期”，不算 0。 */
    fun noSemester(): DataSnapshot = DataSnapshot(semesters = listOf(semester(null)))

    /** 已配学期但无排课：显示 Unavailable/“学期已设置”，不算 0。 */
    fun configuredSemesterNoSchedule(): DataSnapshot =
        DataSnapshot(semesters = listOf(semester(LocalDate.of(2026, 9, 7))))

    // ---- 任务日期边界（当前首页投影可直接消费） ----

    /**
     * 任务日期边界：截止待定、今日截止含已完成、本周区间两侧、取消完成（=completedAt 清空）、跨周。
     *
     * 预期（now=2026-11-10 周二，本周 [2026-11-09, 2026-11-16)）：
     *  - weekTaskCount = 4（11-09 / 11-10 / 11-11 / 11-15）；不含截止待定、不含 11-08、不含 11-16 00:00；
     *  - weekCompletedCount = 1（仅“本周一截止、今天完成”那条）；weekProgress = 1/4；
     *  - todayDueCount = 1（11-10 那条）；todayCompletedCount = 1（completedAt 在今天）；
     *  - 列表首位是截止待定（无 dueAt 排最前）。
     */
    fun taskDateBoundary(): DataSnapshot {
        val now = NOW
        return DataSnapshot(tasks = listOf(
            task("undated"),                                                                                  // 截止待定：不入本周分母、列表最前
            task("todayDue", due = LocalDateTime.of(2026, 11, 10, 23, 59)),                                    // 今日截止
            task("dueMonCompletedToday", due = LocalDateTime.of(2026, 11, 9, 20, 0), completed = now.minusHours(2)), // 本周一截止、今天完成
            task("sundayDeadline", due = LocalDateTime.of(2026, 11, 15, 23, 59)),                              // 周日 23:59（含）仍在本周
            task("nextMondayMidnight", due = LocalDateTime.of(2026, 11, 16, 0, 0)),                            // 下周一 00:00 不含本周
            task("lastWeekDone", due = LocalDateTime.of(2026, 11, 8, 20, 0), completed = LocalDateTime.of(2026, 11, 8, 19, 0)), // 上周完成，不在本周
            task("undoneThisWeek", due = LocalDateTime.of(2026, 11, 11, 20, 0)),                               // 本周待办（未完成）
        ))
    }

    /** B 实测的非连续周次：9、11—13 周；数据表示已落地，不代表 B 的服务已交付。 */
    fun nonConsecutiveWeeks(): DataSnapshot = DataSnapshot(
        semesters = listOf(semester(LocalDate.of(2026, 9, 7))),
        courses = listOf(course("c1", "非连续周课程")),
        schedules = listOf(ScheduleRecord("sch-non-consecutive", "c1", 2, 1, 2,
            weeks = (1L shl 8) or (1L shl 10) or (1L shl 11) or (1L shl 12))),
    )
}
