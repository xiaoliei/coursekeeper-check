package com.coursekeeper.course

import com.coursekeeper.data.model.SemesterRecord
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 教学周换算（成员 B）。
 *
 * 《课程与周次规则 v0.1》第三节：
 * - `startsOn` 归一化为周一，第 1 周从该周开始；
 * - 第 n 周 = `startsOn + 7×(n-1)` 起的周一至周日；
 * - 日期归属按设备本地时区、00:00 为日界（与 D v1.0 一致）。
 *
 * 这里只做日期计算，不读数据库，便于单测。
 */
object WeekCalculator {

    /** 该日期所在周的周一。 */
    fun monday(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** 学期第 1 周的周一；未设置 `startsOn` 时为空。 */
    fun startMonday(semester: SemesterRecord): LocalDate? =
        semester.startsOn?.let(::monday)

    /**
     * [date] 落在该学期的第几周；不在 1..weekCount 内返回 null。
     * 学期起始日缺失或周数非法也返回 null，绝不猜日期。
     */
    fun currentWeek(semester: SemesterRecord, date: LocalDate): Int? {
        val start = startMonday(semester) ?: return null
        if (semester.weekCount <= 0) return null
        val week = ChronoUnit.WEEKS.between(start, monday(date)).toInt() + 1
        return week.takeIf { it in 1..semester.weekCount }
    }

    /** 第 [weekNo] 周的日期区间（周一至周日）；越界返回 null。 */
    fun weekRange(semester: SemesterRecord, weekNo: Int): ClosedRange<LocalDate>? {
        val start = startMonday(semester) ?: return null
        if (weekNo !in 1..semester.weekCount) return null
        val first = start.plusWeeks((weekNo - 1).toLong())
        return first..first.plusDays(6)
    }

    /** 包含 [date] 的当前学期；多个重叠时取起始日最晚的一个。 */
    fun activeSemester(semesters: List<SemesterRecord>, date: LocalDate): SemesterRecord? =
        semesters
            .filter { currentWeek(it, date) != null }
            .maxWithOrNull(compareBy({ it.startsOn }, { it.id }))

    /** 最近一个已配置起始日的学期（用于“不在教学周内”时仍能看课表）。 */
    fun latestConfiguredSemester(semesters: List<SemesterRecord>): SemesterRecord? =
        semesters
            .filter { startMonday(it) != null }
            .maxWithOrNull(compareBy({ it.startsOn }, { it.id }))
}
