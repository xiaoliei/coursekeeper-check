package com.coursekeeper.school

import com.coursekeeper.data.model.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * MYSTU（Moodle）作业页解析测试。
 * 样例 [mystu-dashboard.html] 按实测结构脱敏后编写：课程块 → assignment overview → name/info/details。
 */
class MyStuTaskParserTest {

    private val parser = MyStuTaskParser()

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("school/$name")!!.readBytes().decodeToString()

    @Test
    fun assignmentsMapTitleDueTypeAndStableId() {
        val result = parser.parseTasks(fixture("mystu-dashboard.html"))

        assertEquals(3, result.tasks.size)
        assertEquals(1, result.missingIdentity)

        val first = result.tasks.first { it.externalId == "307183" }
        // 标题去掉“-截止时间 …”后缀，截止时间以 div.info 为准。
        assertEquals("提交-实验-01", first.fields.title)
        assertEquals(TaskType.LAB_REPORT, first.fields.type)
        assertEquals(LocalDateTime.of(2026, 11, 15, 23, 59), first.fields.dueAt)
        assertNull("首版不做课程关联", first.courseExternalId)
    }

    @Test
    fun dueDateWithWeekdayAndTimeIsParsedNotDefaulted() {
        val result = parser.parseTasks(fixture("mystu-dashboard.html"))
        val pending = result.tasks.single { it.externalId == null }

        assertEquals("缺标识的作业", pending.fields.title)
        assertEquals(TaskType.HOMEWORK, pending.fields.type)
        // “2026年10月29日 星期四 12:30”必须解析出 12:30，而不是默认 23:59。
        assertEquals(LocalDateTime.of(2026, 10, 29, 12, 30), pending.fields.dueAt)
    }

    @Test
    fun pageWithoutAssignmentsReportsWarning() {
        val result = parser.parseTasks("<html><body>请登录</body></html>")

        assertTrue(result.tasks.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    // ---- CD-01 / CBE-01（2026-11-17）：透传学校提交状态与源课程标识，不猜测关联 ----

    @Test
    fun submissionStatusIsExposedWithoutDecidingLocallyCompleted() {
        val result = parser.parseTasks(fixture("mystu-dashboard.html"))

        val pending = result.tasks.first { it.externalId == "307183" }
        assertEquals("还未提交 (1 天之前) 应归一为 NOT_SUBMITTED", "NOT_SUBMITTED", pending.schoolSubmission)

        val missingId = result.tasks.single { it.externalId == null }
        assertEquals("已提交应归一为 SUBMITTED", "SUBMITTED", missingId.schoolSubmission)

        // C 只透传状态：既不设本地完成，也不把学校课程 ID 塞成已解析的课程关联。
        assertNull(missingId.fields.courseId)
        assertNull("课程关联仍需 B/E 约定映射", missingId.courseExternalId)
    }

    @Test
    fun sourceCourseIdentityIsCarriedForLaterMapping() {
        val result = parser.parseTasks(fixture("mystu-dashboard.html"))

        val task = result.tasks.first { it.externalId == "307184" }
        assertEquals("1001", task.sourceCourseId)
        assertTrue(task.sourceCourseName!!.contains("应用密码学"))
    }

    @Test
    fun draftsAndReopenedStatesAreDistinguished() {
        fun parse(details: String) = parser.parseTasks(
            """
            <div id="course-2001" class="box coursebox">
              <div class="assignment overview">
                <div class="name"><a href="mod/assignment/view.php?id=1">作业</a></div>
                <div class="info">截止时间: 2026年11月15日 星期日 23:59</div>
                <div class="details">$details</div>
              </div>
            </div>
            """.trimIndent(),
        ).tasks.single().schoolSubmission

        assertEquals("DRAFT", parse("草稿（未提交）"))
        assertEquals("REOPENED", parse("需要重新提交"))
        assertEquals("UNKNOWN", parse("待批阅"))
    }
}
