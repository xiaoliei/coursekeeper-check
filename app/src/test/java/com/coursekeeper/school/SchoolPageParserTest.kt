package com.coursekeeper.school

import com.coursekeeper.course.WeekText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** 解析层测试：样例为 T05 脱敏 HTML（app/src/test/resources/school/）。 */
class SchoolPageParserTest {

    private val parser = TableSchoolPageParser()

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("school/$name")!!.readBytes().decodeToString()

    @Test
    fun scheduleFixtureParsesCoursesWeeksLocationsAndSemesterStart() {
        val result = parser.parseSchedule(fixture("schedule-sample.html"))

        assertEquals(4, result.courses.size)
        assertEquals(LocalDate.of(2026, 9, 7), result.semesterStart)
        val byCode = result.courses.associateBy { it.externalId }
        assertTrue(byCode.containsKey("A02ENG"))

        val math = byCode["A01MATH"]!!
        assertEquals("高等数学", math.fields.title)
        assertEquals(2, math.fields.schedules.size)
        val monday = math.fields.schedules.first { it.dayOfWeek == 1 }
        assertEquals(1, monday.startPeriod)
        assertEquals(2, monday.endPeriod)
        assertEquals("教一201", monday.location)
        assertEquals("王老师", monday.teacher)
        assertEquals(WeekText.parse("1-16周(单)").weeks, monday.weeks)
        val tuesday = math.fields.schedules.first { it.dayOfWeek == 2 }
        assertEquals(WeekText.parse("1-16周(双)").weeks, tuesday.weeks)

        val ds = byCode["A03DS"]!!
        assertEquals(2, ds.fields.schedules.size)
        // 9,11-13 周与非连续周次样例（E v3 位图约定）。
        assertEquals(WeekText.parse("9,11-13周").weeks, ds.fields.schedules.first().weeks)
        assertNull(ds.fields.schedules.first().teacher) // 条目无教师，读侧沿用课程默认

        val pe = byCode["A04PE"]!!
        assertEquals("田径场", pe.fields.schedules.single().location)
    }

    @Test
    fun courseWithoutStableCodeIsSkippedAndCounted() {
        val result = parser.parseSchedule(fixture("schedule-sample.html"))
        // 数据库系统条目缺课程代码：不导入、不猜去重键（E 交接约定）。
        assertEquals(1, result.skippedCourses)
        assertTrue(result.warnings.any { it.contains("数据库系统") })
        assertTrue(result.courses.none { it.fields.title == "数据库系统" })
    }

    @Test
    fun htmlWithoutScheduleTableReportsWarningInsteadOfThrowing() {
        val result = parser.parseSchedule("<html><body>登录已失效</body></html>")
        assertTrue(result.courses.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun homeworkFixtureParsesTasksStableIdsDueDatesAndMissingIdentity() {
        val result = parser.parseTasks(fixture("homework-sample.html"))

        // 2 条有稳定 id + 1 条缺 id：缺 id 的仍上交（externalId = null），由 E 计数并暂缓入库。
        assertEquals(3, result.tasks.size)
        assertEquals(1, result.missingIdentity)
        val pending = result.tasks.single { it.externalId == null }
        assertTrue(pending.fields.title.contains("讲座通知"))
        val lab = result.tasks.first { it.externalId == "101" }
        assertEquals("实验一：开发环境配置", lab.fields.title)
        assertEquals(com.coursekeeper.data.model.TaskType.LAB_REPORT, lab.fields.type)
        assertEquals(LocalDateTime.of(2026, 11, 20, 23, 59), lab.fields.dueAt)
        assertEquals("CS101", lab.courseExternalId)

        val homework = result.tasks.first { it.externalId == "102" }
        assertEquals("第三章课后习题", homework.fields.title)
        // 只有日期没有时刻：默认 23:59 截止，不做其他猜测。
        assertEquals(LocalDateTime.of(2026, 11, 18, 23, 59), homework.fields.dueAt)
        assertEquals(com.coursekeeper.data.model.TaskType.HOMEWORK, homework.fields.type)
    }

    @Test
    fun dueDateParsingCoversCommonSchoolFormats() {
        assertEquals(LocalDateTime.of(2026, 11, 20, 14, 30), DueDates.parse("截止 2026年11月20日 14:30 提交"))
        assertEquals(LocalDateTime.of(2026, 11, 20, 23, 59), DueDates.parse("2026/11/20"))
        assertNull(DueDates.parse("本周五提交"))
        assertNull(DueDates.parse("2026-13-40 25:00"))
    }

    @Test
    fun entryWithoutPeriodInformationIsSkippedInsteadOfDefaultingToOne() {
        // 行首没有节次标签、条目内也没有内联节次：跳过并计数，不把课排到第 1 节。
        val html = """
            <table>
            <tr><th>节次</th><th>星期一</th></tr>
            <tr><td></td><td>编译原理(A05COMP)<br/>1-16周<br/>教一101</td></tr>
            </table>
        """.trimIndent()

        val result = parser.parseSchedule(html)

        assertTrue(result.courses.isEmpty())
        assertEquals(1, result.skippedCourses)
    }

    @Test
    fun termTagPrefixesCourseIdsAndTaskCourseLinks() {
        // 跨学期重修会撞 (source, externalId) 全局唯一键：带学期标签时两侧必须同时加前缀。
        val tagged = TableSchoolPageParser(termTag = "2026AUT")

        val schedule = tagged.parseSchedule(fixture("schedule-sample.html"))
        assertTrue(schedule.courses.all { it.externalId.startsWith("2026AUT-") })
        assertTrue(schedule.courses.any { it.externalId == "2026AUT-A01MATH" })

        val tasks = tagged.parseTasks(fixture("homework-sample.html"))
        assertEquals("2026AUT-CS101", tasks.tasks.first { it.externalId == "101" }.courseExternalId)
        assertEquals("2026AUT-A01MATH", tasks.tasks.first { it.externalId == "102" }.courseExternalId)
    }
}
