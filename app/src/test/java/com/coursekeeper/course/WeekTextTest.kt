package com.coursekeeper.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周次文本解析的单测（成员 B）。
 *
 * 覆盖《课程与周次规则 v0.1》第三节要求：非连续、分段、单双周、越界丢弃、非法段容错，
 * 并保证“位图 ↔ 文本”始终同源（文本由 ScheduleWeeks 统一生成）。
 */
class WeekTextTest {

    @Test
    fun `非连续周 9,11-13 解析为位图 7424`() {
        val parsed = WeekText.parse("9,11-13")
        assertEquals(7424L, parsed.weeks)
        assertEquals("9,11-13", parsed.text)
        assertTrue(parsed.warnings.isEmpty())
        assertTrue(WeekText.contains(parsed.weeks, 9))
        assertFalse(WeekText.contains(parsed.weeks, 10))
        assertTrue(WeekText.contains(parsed.weeks, 13))
    }

    @Test
    fun `连续区间 1-16 解析为低 16 位全 1`() {
        val parsed = WeekText.parse("1-16")
        assertEquals(65535L, parsed.weeks)
        assertEquals("1-16", parsed.text)
    }

    @Test
    fun `相邻分段会合并成一段 4-7 加 8-16`() {
        val parsed = WeekText.parse("4-7,8-16")
        assertEquals("4-16", parsed.text)
    }

    @Test
    fun `单双周是位图子集`() {
        val odd = WeekText.parse("1-16(单)")
        assertEquals("1,3,5,7,9,11,13,15", odd.text)
        assertFalse(WeekText.contains(odd.weeks, 2))

        val even = WeekText.parse("1-16双周")
        assertEquals("2,4,6,8,10,12,14,16", even.text)
        assertFalse(WeekText.contains(even.weeks, 1))
    }

    @Test
    fun `兼容导出里的 周 后缀与全角符号`() {
        val parsed = WeekText.parse("9，11-13（周）")
        assertEquals(7424L, parsed.weeks)
        assertEquals("9,11-13", parsed.text)
    }

    @Test
    fun `超出本学期周数只丢弃该段并记警告`() {
        val parsed = WeekText.parse("1-16", weekCount = 10)
        assertEquals("1-10", parsed.text)
        assertTrue(parsed.warnings.any { it.contains("超出本学期") })
    }

    @Test
    fun `非法段不影响整条 其他段仍然生效`() {
        val parsed = WeekText.parse("abc,3-4")
        assertEquals("3-4", parsed.text)
        assertTrue(parsed.warnings.any { it.contains("无法识别") })
    }

    @Test
    fun `第 0 周被忽略并记警告`() {
        val parsed = WeekText.parse("0-2")
        assertEquals("1-2", parsed.text)
        assertTrue(parsed.warnings.any { it.contains("超出 1—64") })
    }

    @Test
    fun `全部非法时返回空位图而不是抛异常`() {
        val parsed = WeekText.parse("下周再说")
        assertTrue(parsed.isEmpty)
        assertEquals("", parsed.text)
    }

    @Test
    fun `位图与文本往返一致`() {
        listOf("1-16", "9,11-13", "2-4,6", "20").forEach { raw ->
            val parsed = WeekText.parse(raw)
            assertEquals(parsed.text, WeekText.format(parsed.weeks))
        }
    }
}
