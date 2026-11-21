package com.coursekeeper.school

import com.coursekeeper.data.model.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * MYSTU（Moodle）作业页解析测试。
 * 样例 [mystu-dashboard.html] 按实测结构脱敏后编写：课程块 → 作业块（老 assignment / 新 assign）→
 * name / info / details。D 2026-11-21：新 `assign` 模块 4 条此前全部漏抓（P1），本文件锁定双模块解析。
 */
class MyStuTaskParserTest {

    private val parser = MyStuTaskParser()

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("school/$name")!!.readBytes().decodeToString()

    @Test
    fun assignmentsMapTitleDueTypeAndStableId() {
        val result = parser.parseTasks(fixture("mystu-dashboard.html"))

        assertEquals("老 assignment 3 条 + 新 assign 4 条", 7, result.tasks.size)
        assertEquals("仅 course-1001 的 index.php 链接缺作业 ID", 1, result.missingIdentity)

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

    // ---- D 2026-11-21 P1：新 assign 模块此前 4 条全部漏抓，双模块解析 ----

    @Test
    fun newAssignModuleEntriesAreParsedWithIdDueAndSubmission() {
        val result = parser.parseTasks(fixture("mystu-dashboard.html"))

        val assignTasks = result.tasks.filter { it.sourceCourseId == "3001" }
        assertEquals("高级算法的 4 条 assign 作业必须全部解析", 4, assignTasks.size)
        assertEquals(
            listOf("410001", "410002", "410003", "410004"),
            assignTasks.map { it.externalId },
        )

        val first = assignTasks.first()
        assertEquals("中期检查-第1组", first.fields.title)
        assertEquals(TaskType.HOMEWORK, first.fields.type)
        // 新模块标签是「到期日期:」，时刻 00:00 不能退化成默认 23:59。
        assertEquals(LocalDateTime.of(2026, 9, 26, 0, 0), first.fields.dueAt)
        assertEquals("「我的作业：还未提交, 未评分」清洗后归一", "NOT_SUBMITTED", first.schoolSubmission)
        assertTrue(first.sourceCourseName!!.contains("高级算法"))
    }

    @Test
    fun parserExposesCourseBoxIdsForSnapshotConvergence() {
        val result = parser.parseTasks(fixture("mystu-dashboard.html"))

        assertEquals(setOf("1001", "1002", "3001"), result.sourceCourseIds)
    }

    @Test
    fun myAssignmentPrefixAndGradeSuffixAreStrippedBeforeNormalization() {
        fun parse(details: String) = parser.parseTasks(
            """
            <div id="course-3002" class="box coursebox">
              <div class="assign overview">
                <div class="name"><a href="mod/assign/view.php?id=99">作业</a></div>
                <div class="info">到期日期: 2026年09月26日 星期六 00:00</div>
                <div class="details">$details</div>
              </div>
            </div>
            """.trimIndent(),
        ).tasks.single().schoolSubmission

        assertEquals("SUBMITTED", parse("我的作业：已提交, 未评分"))
        assertEquals("NOT_SUBMITTED", parse("我的作业：还未提交"))
        assertEquals("REOPENED", parse("我的作业：需要重新提交, 未评分"))
        assertEquals("未评分只描述评分状态，不能吞掉整条状态", "UNKNOWN", parse("我的作业：待批阅"))
    }

    @Test
    fun assignModuleWithoutViewIdIsDeferredLikeAssignmentModule() {
        val result = parser.parseTasks(
            """
            <div id="course-3003" class="box coursebox">
              <div class="assign overview">
                <div class="name"><a href="mod/assign/index.php?id=3003">无 view 链接的新模块作业</a></div>
                <div class="info">到期日期: 2026年09月26日 星期六 00:00</div>
                <div class="details">我的作业：还未提交, 未评分</div>
              </div>
            </div>
            """.trimIndent(),
        )

        val task = result.tasks.single()
        assertNull("index.php 链接没有作业 ID，与老模块同规则暂缓", task.externalId)
        assertEquals(1, result.missingIdentity)
        assertEquals("NOT_SUBMITTED", task.schoolSubmission)
        assertTrue(result.warnings.single().contains("无 view 链接的新模块作业"))
    }
}
