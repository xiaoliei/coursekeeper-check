package com.coursekeeper.review

import com.coursekeeper.data.sync.CourseLinker
import com.coursekeeper.school.MyStuTaskParser
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Isolated review probes: synthetic boundaries, not real-school acceptance. */
class CFeedbackBoundaryProbeTest {
    private val courses = listOf(CourseLinker.Candidate("SE", "软件工程"))

    @Test
    fun trailingCourseQualifierMustNotBeAssumedToBeTeacher() {
        assertNull(
            "A course qualifier without a teacher must not be dropped",
            CourseLinker.resolve("[2026秋] 软件工程 下", courses),
        )
    }

    @Test
    fun leadingCourseQualifierMustNotBeAssumedToBeSemester() {
        assertNull(
            "A leading non-semester qualifier must not be dropped",
            CourseLinker.resolve("（双语）软件工程 张三", courses),
        )
    }

    @Test
    fun dueColumnMustNotUseAnEarlierOpeningDate() {
        val html = """
            <table><tr>
              <td><a href="mod/assign/view.php?id=123">课程作业</a></td>
              <td>开放时间: 2026年11月01日 00:00</td>
              <td>截止时间: 2026年11月30日 23:59</td>
              <td>还未提交</td>
            </tr></table>
        """.trimIndent()
        val task = MyStuTaskParser().parseTaskIndex(html, "1001", null).tasks.single()
        assertEquals(LocalDateTime.of(2026, 11, 30, 23, 59), task.fields.dueAt)
    }
}
