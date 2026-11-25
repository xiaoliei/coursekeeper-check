package com.coursekeeper.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * P2 历史作业补抓（成员 C，2026-11-25）：每课作业索引页解析。
 *
 * 样例 [mystu-assign-index.html] 按 Moodle 4.x `mod/assign/index.php` 的标准表格结构编写，
 * 老 `assignment` 模块混排。解析只看“行内 `view.php?id=` 链接”，因此类名/列顺序不是契约；
 * 真实页面结构不同则返回 0 条 + 警告，适配器跳过该课，不会误导入。
 */
class MyStuTaskIndexParserTest {

    private val parser = MyStuTaskParser()

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("school/$name")!!.readBytes().decodeToString()

    private fun parse(html: String) = parser.parseTaskIndex(html, courseId = "1001", courseName = "[2026秋] 应用密码学 方若宇")

    @Test
    fun allIndexRowsWithStableViewIdsAreParsedWithDueDateAndStatus() {
        val result = parse(fixture("mystu-assign-index.html"))

        assertEquals("两条新模块 + 一条老模块；index.php 行缺稳定 ID 不计入", 3, result.tasks.size)
        assertEquals(listOf("500001", "500002", "500003"), result.tasks.map { it.externalId })

        val submitted = result.tasks.first { it.externalId == "500001" }
        assertEquals("实验报告一", submitted.fields.title)
        assertEquals(LocalDateTime.of(2026, 10, 12, 23, 59), submitted.fields.dueAt)
        assertEquals("已提交应归一为 SUBMITTED，供 D v1.1 自动完成", "SUBMITTED", submitted.schoolSubmission)

        val pending = result.tasks.first { it.externalId == "500002" }
        assertEquals("实验报告二", pending.fields.title)
        // 索引页的 00:00 不能退化成默认 23:59。
        assertEquals(LocalDateTime.of(2026, 11, 2, 0, 0), pending.fields.dueAt)
        assertEquals("NOT_SUBMITTED", pending.schoolSubmission)

        val legacy = result.tasks.first { it.externalId == "500003" }
        assertEquals("老 assignment 模块同规则", "老模块作业：课程设计", legacy.fields.title)
        assertEquals("SUBMITTED", legacy.schoolSubmission)
    }

    @Test
    fun sourceCourseIdentityIsCarriedForLinkerAndDedup() {
        val result = parse(fixture("mystu-assign-index.html"))

        assertTrue("索引页结果带来源课程，合并层才能去重并交给 CBE-01 映射",
            result.tasks.all { it.sourceCourseId == "1001" && it.sourceCourseName!!.contains("应用密码学") })
        assertTrue("不猜跨系统课程关联，交给 E 的合并层按规则解析",
            result.tasks.all { it.courseExternalId == null })
        assertEquals(setOf("1001"), result.sourceCourseIds)
        assertEquals(0, result.missingIdentity)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun pageWithoutAssignmentRowsYieldsWarningInsteadOfGuessing() {
        val result = parse("<html><body><p>没有作业</p></body></html>")

        assertTrue(result.tasks.isEmpty())
        assertTrue(result.warnings.single().contains("未找到作业行"))
        assertEquals("无行时仍上报课程编号，不误报缺标识", 0, result.missingIdentity)
    }

    @Test
    fun loginPageIsNotMistakenForAnEmptyIndex() {
        val result = parse("<html><body><form><input type=\"password\"></form></body></html>")

        assertTrue(result.tasks.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }
}
