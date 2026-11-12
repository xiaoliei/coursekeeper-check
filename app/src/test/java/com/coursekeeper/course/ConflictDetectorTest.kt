package com.coursekeeper.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 冲突判定单测（成员 B），对应规则 v0.1 第八节。 */
class ConflictDetectorTest {

    private fun slot(
        id: String,
        courseId: String = "c1",
        day: Int = 2,
        start: Int = 1,
        end: Int = 2,
        weeks: String = "1-16",
        location: String? = "A101",
        teacher: String? = "教师甲",
    ) = ConflictSlot(
        scheduleId = id,
        courseId = courseId,
        courseTitle = courseId,
        dayOfWeek = day,
        startPeriod = start,
        endPeriod = end,
        weeks = WeekText.parse(weeks).weeks,
        location = location,
        teacher = teacher,
    )

    @Test
    fun `不同课程同一天节次相交即为冲突`() {
        val conflicts = ConflictDetector.detect(listOf(
            slot("s1", courseId = "c1", start = 1, end = 2),
            slot("s2", courseId = "c2", start = 2, end = 3),
        ))
        assertEquals(1, conflicts.size)
        assertEquals(ConflictKind.DIFFERENT_COURSE, conflicts[0].kind)
        assertEquals(2, conflicts[0].periods.first)
        assertEquals(2, conflicts[0].periods.last)
        assertEquals("1-16", conflicts[0].weeksText)
    }

    @Test
    fun `周次不相交不算冲突 4-7 与 8-16 分段`() {
        val conflicts = ConflictDetector.detect(listOf(
            slot("s1", courseId = "c1", weeks = "4-7"),
            slot("s2", courseId = "c2", weeks = "8-16"),
        ))
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `部分周重叠时只报重叠周次`() {
        val conflicts = ConflictDetector.detect(listOf(
            slot("s1", courseId = "c1", weeks = "1-8"),
            slot("s2", courseId = "c2", weeks = "6-12"),
        ))
        assertEquals(1, conflicts.size)
        assertEquals("6-8", conflicts[0].weeksText)
    }

    @Test
    fun `同一课程同时段但地点不同按数据异常提示`() {
        val conflicts = ConflictDetector.detect(listOf(
            slot("s1", courseId = "c1", location = "A101"),
            slot("s2", courseId = "c1", location = "B202"),
        ))
        assertEquals(1, conflicts.size)
        assertEquals(ConflictKind.SAME_COURSE_DIFFERENT_DETAILS, conflicts[0].kind)
    }

    @Test
    fun `同课程完全重复的条目去重 不报冲突`() {
        val conflicts = ConflictDetector.detect(listOf(
            slot("s1", courseId = "c1"),
            slot("s2", courseId = "c1"),
        ))
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `不同星期或节次不相交都不算冲突`() {
        val conflicts = ConflictDetector.detect(listOf(
            slot("s1", courseId = "c1", day = 2, start = 1, end = 2),
            slot("s2", courseId = "c2", day = 3, start = 1, end = 2),
            slot("s3", courseId = "c3", day = 2, start = 3, end = 4),
        ))
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `课程已删除的排课不进入冲突判定`() {
        val snapshotCourses = emptyList<com.coursekeeper.data.model.CourseRecord>()
        val schedule = com.coursekeeper.data.model.ScheduleRecord(
            id = "s1", courseId = "gone", dayOfWeek = 2, startPeriod = 1, endPeriod = 2,
            weeks = WeekText.parse("1-16").weeks,
        )
        assertTrue(ConflictDetector.slots(listOf(schedule), snapshotCourses).isEmpty())
    }
}
