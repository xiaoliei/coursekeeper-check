package com.coursekeeper.school

import com.coursekeeper.data.TaskDraft
import com.coursekeeper.data.model.TaskType
import com.coursekeeper.data.sync.ImportedTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDateTime

/**
 * D 2026-11-21 兜底：MYSTU 首页快照随平台缓存波动，可能整门课消失/重现。
 * 同一次同步内多抓一页做课程集合并集收敛（≤3 次封顶）；本文件锁定多页合并的纯函数部分——
 * 按作业 ID 去重、缺 ID 条目按内容折叠、missingIdentity 与警告按合并后结果计。
 */
class MyStuPageMergeTest {

    private val adapter = WebViewSchoolAdapter(schoolProvider = { SchoolCatalog.default })

    private fun task(id: String?, title: String, due: LocalDateTime, course: String? = "1001") =
        ImportedTask(
            externalId = id,
            fields = TaskDraft(title = title, type = TaskType.HOMEWORK, dueAt = due),
            sourceCourseId = course,
        )

    private fun page(tasks: List<ImportedTask>, ids: Set<String>, warnings: List<String> = emptyList()) =
        TaskParse(tasks = tasks, missingIdentity = tasks.count { it.externalId == null },
            warnings = warnings, sourceCourseIds = ids)

    private val due = LocalDateTime.of(2026, 9, 26, 0, 0)

    @Test
    fun singlePageIsReturnedAsIsWithoutRecounting() {
        val page = page(listOf(task("a1", "作业", due)), ids = setOf("1001"), warnings = listOf("w"))

        assertSame(page, adapter.mergeTaskPages(listOf(page)))
    }

    @Test
    fun unionOfTwoSnapshotsKeepsFirstSeenContentAndAddsMissingCourses() {
        val page1 = page(listOf(task("a1", "作业 A", due)), ids = setOf("1001"))
        val page2 = page(
            listOf(
                task("a1", "作业 A", due), // 平台波动后同一条作业原样重现
                task("b1", "中期检查", due, course = "3001"), // 整课消失又重现的作业
            ),
            ids = setOf("1001", "3001"),
        )

        val merged = adapter.mergeTaskPages(listOf(page1, page2))

        assertEquals(listOf("a1", "b1"), merged.tasks.map { it.externalId })
        assertEquals("作业 A", merged.tasks.first { it.externalId == "a1" }.fields.title)
        assertEquals(setOf("1001", "3001"), merged.sourceCourseIds)
        assertEquals(0, merged.missingIdentity)
    }

    @Test
    fun unidentifiedEntriesAreFoldedByContentAndCountedOnce() {
        val page1 = page(listOf(task(null, "缺标识的作业", due)), ids = setOf("1001"))
        val page2 = page(
            listOf(
                task(null, "缺标识的作业", due), // 同一条缺 ID 作业在两次快照里重复出现
                task(null, "另一条缺标识", due),
            ),
            ids = setOf("1001"),
        )

        val merged = adapter.mergeTaskPages(listOf(page1, page2))

        assertEquals(2, merged.tasks.size)
        assertEquals("去重后按剩余条目计数，不重复累计", 2, merged.missingIdentity)
    }

    @Test
    fun sameIdWithDifferentContentKeepsFirstSnapshot() {
        val page1 = page(listOf(task("a1", "第一次标题", due)), ids = setOf("1001"))
        val page2 = page(listOf(task("a1", "第二次标题", due)), ids = setOf("1001"))

        val merged = adapter.mergeTaskPages(listOf(page1, page2))

        assertEquals(1, merged.tasks.size)
        assertEquals("同 ID 不同内容保留首页快照，不给 E 上交自相矛盾的数据", "第一次标题",
            merged.tasks.single().fields.title)
    }

    @Test
    fun warningsAndCourseIdsAreUnionedAcrossPages() {
        val page1 = page(emptyList(), ids = setOf("1001"), warnings = listOf("页面未找到作业列表"))
        val page2 = page(emptyList(), ids = setOf("1001", "3001"), warnings = listOf("页面未找到作业列表", "另一条"))

        val merged = adapter.mergeTaskPages(listOf(page1, page2))

        assertEquals(listOf("页面未找到作业列表", "另一条"), merged.warnings)
        assertEquals(setOf("1001", "3001"), merged.sourceCourseIds)
    }
}
