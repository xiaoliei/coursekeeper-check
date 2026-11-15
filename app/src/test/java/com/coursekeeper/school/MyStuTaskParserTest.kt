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
}
