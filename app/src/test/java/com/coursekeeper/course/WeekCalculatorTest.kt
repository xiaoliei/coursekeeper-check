package com.coursekeeper.course

import com.coursekeeper.data.model.SemesterRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * 教学周换算单测（成员 B）。
 *
 * 固定样例：2026-09-07（周一）为第 1 周，2026-11-09（周一）为第 10 周，
 * 与 F 的边界假数据（NOW = 2026-11-10）保持一致。
 */
class WeekCalculatorTest {

    private fun semester(startsOn: LocalDate? = LocalDate.of(2026, 9, 7), weekCount: Int = 20) =
        SemesterRecord("s1", "秋季学期", startsOn, weekCount)

    @Test
    fun `任意日期归一化到所在周的周一`() {
        assertEquals(LocalDate.of(2026, 9, 7), WeekCalculator.monday(LocalDate.of(2026, 9, 9)))
        assertEquals(LocalDate.of(2026, 9, 7), WeekCalculator.monday(LocalDate.of(2026, 9, 7)))
        assertEquals(LocalDate.of(2026, 9, 7), WeekCalculator.monday(LocalDate.of(2026, 9, 13)))
    }

    @Test
    fun `起始日不是周一时仍按该周周一起算`() {
        val oddStart = semester(startsOn = LocalDate.of(2026, 9, 9)) // 周三
        assertEquals(LocalDate.of(2026, 9, 7), WeekCalculator.startMonday(oddStart))
        assertEquals(10, WeekCalculator.currentWeek(oddStart, LocalDate.of(2026, 11, 10)))
    }

    @Test
    fun `第 10 周对应 11 月 9 日到 15 日`() {
        assertEquals(10, WeekCalculator.currentWeek(semester(), LocalDate.of(2026, 11, 10)))
        assertEquals(
            LocalDate.of(2026, 11, 9)..LocalDate.of(2026, 11, 15),
            WeekCalculator.weekRange(semester(), 10),
        )
    }

    @Test
    fun `学期开始前与结束后都返回 null`() {
        assertNull(WeekCalculator.currentWeek(semester(), LocalDate.of(2026, 9, 6)))
        assertNull(WeekCalculator.currentWeek(semester(), LocalDate.of(2027, 1, 25)))
        assertNull(WeekCalculator.weekRange(semester(), 0))
        assertNull(WeekCalculator.weekRange(semester(), 21))
    }

    @Test
    fun `起始日缺失时不做任何猜测`() {
        val missing = semester(startsOn = null)
        assertNull(WeekCalculator.startMonday(missing))
        assertNull(WeekCalculator.currentWeek(missing, LocalDate.of(2026, 11, 10)))
        assertNull(WeekCalculator.weekRange(missing, 1))
    }

    @Test
    fun `当前学期取包含今天的那个`() {
        val spring = SemesterRecord("s2", "春季学期", LocalDate.of(2027, 2, 22), 20)
        val autumn = semester()
        assertEquals(autumn, WeekCalculator.activeSemester(listOf(spring, autumn), LocalDate.of(2026, 11, 10)))
        assertEquals(spring, WeekCalculator.activeSemester(listOf(spring, autumn), LocalDate.of(2027, 3, 1)))
        assertNull(WeekCalculator.activeSemester(listOf(spring, autumn), LocalDate.of(2027, 1, 28)))
    }

    @Test
    fun `最近配置学期用于不在教学周内时仍能看课表`() {
        val spring = SemesterRecord("s2", "春季学期", LocalDate.of(2027, 2, 22), 20)
        val autumn = semester()
        assertEquals(spring, WeekCalculator.latestConfiguredSemester(listOf(autumn, spring)))
        assertEquals(autumn, WeekCalculator.latestConfiguredSemester(listOf(autumn)))
        assertNull(WeekCalculator.latestConfiguredSemester(listOf(semester(startsOn = null))))
    }
}
