package com.coursekeeper.course

import com.coursekeeper.data.model.CourseRecord
import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.ScheduleRecord
import com.coursekeeper.data.model.ScheduleWeeks
import com.coursekeeper.data.model.SemesterRecord
import com.coursekeeper.fakedata.BoundaryFixtures
import com.coursekeeper.statistics.CourseStats
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 手动自查报告（成员 B，临时工具，可随时删除）。
 *
 * 运行后在 Run 控制台打印一张中文对照表：周次解析、教学周换算、冲突判定，
 * 以及课程四个数字投影结果是否一致。
 * 任何一项显示“FAIL”都会让本测试变红。
 */
class CourseSelfCheckTest {

    private val line = "─".repeat(72)

    @Test
    fun `打印自查报告并校验`() {
        val report = StringBuilder()
        report.appendLine(line)
        report.appendLine("B 课程服务自查（第 0—2 步）")
        report.appendLine(line)

        var failed = false

        report.appendLine()
        report.appendLine("① 周次文本解析")
        val weekCases = listOf(
            "9,11-13" to "9,11-13",
            "9,11-13(周)" to "9,11-13",
            "4-7,8-16" to "4-16",
            "1-16(单)" to "1,3,5,7,9,11,13,15",
            "1-16（双）" to "2,4,6,8,10,12,14,16",
            "abc,3-4" to "3-4",
        )
        weekCases.forEach { (raw, expected) ->
            val parsed = WeekText.parse(raw)
            val ok = parsed.text == expected
            if (!ok) failed = true
            report.appendLine(
                "  %-14s → %-26s 位图=%-6d %s%s".format(
                    raw, parsed.text, parsed.weeks, if (ok) "OK" else "FAIL 期望=$expected",
                    if (parsed.warnings.isEmpty()) "" else "  ⚠ ${parsed.warnings.first()}",
                ),
            )
        }
        report.appendLine("  7424 的含义：${ScheduleWeeks.format(7424L)}（第 9、11、12、13 周）")

        report.appendLine()
        report.appendLine("② 教学周换算（第 1 周 = 2026-09-07 周一）")
        val semester = SemesterRecord("s1", "秋季", LocalDate.of(2026, 9, 7), 20)
        listOf(
            LocalDate.of(2026, 11, 10) to 10,
            LocalDate.of(2026, 11, 16) to 11,
            LocalDate.of(2026, 9, 6) to null,
            LocalDate.of(2027, 1, 28) to null,
        ).forEach { (date, expected) ->
            val actual = WeekCalculator.currentWeek(semester, date)
            val ok = actual == expected
            if (!ok) failed = true
            report.appendLine("  %s → 第 %s 周  %s".format(date, actual ?: "—", if (ok) "OK" else "FAIL 期望=$expected"))
        }

        report.appendLine()
        report.appendLine("③ 冲突判定")
        val conflicts = ConflictDetector.detect(listOf(
            slot("s1", "c1", weeks = "1-16", location = "A101"),
            slot("s2", "c2", weeks = "1-16", location = "B202"),
            slot("s3", "c3", weeks = "1-16", start = 3, end = 4, location = "C303"),
        ))
        report.appendLine("  3 条排课检出 ${conflicts.size} 条冲突（期望 1 条：c1 与 c2 抢第 1-2 节）")
        conflicts.forEach {
            report.appendLine("  · ${it.first.courseId} × ${it.second.courseId} 周 ${it.weeksText} 第 ${it.periods.first}-${it.periods.last} 节 (${it.kind})")
        }
        if (conflicts.size != 1) failed = true

        report.appendLine()
        report.appendLine("④ 四个课程数字投影（now = ${BoundaryFixtures.NOW}）")
        report.appendLine("  %-32s %-23s %-23s %s".format("夹具", "实际", "期望", "结果"))
        val fixtures = listOf(
            Fixture("跨节次", BoundaryFixtures.crossPeriodCourse(), CourseStats(1, 4, 1, 1)),
            Fixture("同课多条目", BoundaryFixtures.sameCourseMultipleEntries(), CourseStats(1, 2, 1, 2)),
            Fixture("同名不同编号", BoundaryFixtures.sameTitleDistinctCodes(), CourseStats(1, 2, 2, 2)),
            Fixture("单双周", BoundaryFixtures.alternatingWeeks(), CourseStats(0, 0, 0, 0)),
            Fixture("无学期", BoundaryFixtures.noSemester(), CourseStats(0, 0, 0, 0)),
            Fixture("有学期无排课", BoundaryFixtures.configuredSemesterNoSchedule(), CourseStats(0, 0, 0, 0)),
        )
        fixtures.forEach { fixture ->
            val actual = CourseProjector.summary(fixture.snapshot, BoundaryFixtures.NOW)
            val ok = actual == fixture.expected
            if (!ok) failed = true
            report.appendLine(
                "  %-32s %-23s %-23s %s".format(
                    fixture.name,
                    actual.short(),
                    fixture.expected.short(),
                    if (ok) "OK" else "FAIL",
                ),
            )
        }

        report.appendLine()
        report.appendLine("⑤ 非连续周（9,11-13）")
        val weeks = (1L shl 8) or (1L shl 10) or (1L shl 11) or (1L shl 12)
        val snapshot = DataSnapshot(
            semesters = listOf(semester),
            courses = listOf(CourseRecord("c1", "数据结构", semesterId = "s1")),
            schedules = listOf(
                ScheduleRecord("sch1", "c1", dayOfWeek = 2, startPeriod = 1, endPeriod = 2,
                    weeks = weeks, weeksText = "9,11-13", location = "A101"),
            ),
        )
        listOf(
            LocalDateTime.of(2026, 11, 3, 10, 0) to 9,   // 第 9 周 周二
            LocalDateTime.of(2026, 11, 10, 10, 0) to 10, // 第 10 周 周二（不在集合里）
            LocalDateTime.of(2026, 11, 17, 10, 0) to 11, // 第 11 周 周二
        ).forEach { (now, week) ->
            val state = CourseProjector.project(snapshot, now)
            val expected = if (week == 10) 0 else 1
            val ok = state.todayLessonCount == expected
            if (!ok) failed = true
            report.appendLine(
                "  第 %2d 周 %s → 今日课程 %d 条、%d 节  %s".format(
                    week, now.toLocalDate(), state.todayLessonCount, state.todayPeriodCount,
                    if (ok) "OK" else "FAIL 期望 $expected 条",
                ),
            )
        }

        report.appendLine()
        report.appendLine(line)
        report.appendLine(if (failed) "结论：有 FAIL，请把上面红色项发我" else "结论：全部通过 ✅")
        report.appendLine(line)
        println(report)

        assertTrue("自查报告存在 FAIL，见控制台输出", !failed)
    }

    private fun CourseStats.short(): String =
        "条$todayLessonCount 节$todayPeriodCount 门$weekCourseCount 周$weekEntryCount"

    private data class Fixture(
        val name: String,
        val snapshot: DataSnapshot,
        val expected: CourseStats,
    )

    private fun slot(
        id: String,
        courseId: String,
        weeks: String,
        start: Int = 1,
        end: Int = 2,
        location: String? = null,
    ) = ConflictSlot(
        scheduleId = id,
        courseId = courseId,
        courseTitle = courseId,
        dayOfWeek = 2,
        startPeriod = start,
        endPeriod = end,
        weeks = WeekText.parse(weeks).weeks,
        location = location,
        teacher = "教师甲",
    )
}
