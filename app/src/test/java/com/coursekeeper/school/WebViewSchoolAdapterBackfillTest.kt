package com.coursekeeper.school

import com.coursekeeper.data.sync.ImportedTask
import com.coursekeeper.data.sync.ModuleResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * P2 历史作业补抓（成员 C，2026-11-25）：适配器编排。
 *
 * 覆盖：首页 + 缓存课程编号 → 每课索引页补抓、按作业 ID 去重、缓存里“首页已移出”的课程
 * 也能补回；单课失败与结构不符时降级为首页结果；请求数受预算上限约束。
 * 通过注入 [WebViewSchoolAdapter] 的 httpGet 使用固定页面，不依赖真机与网络。
 */
class WebViewSchoolAdapterBackfillTest {

    private val homepage: String = fixture("mystu-dashboard.html")

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("school/$name")!!.readBytes().decodeToString()

    private fun indexPage(
        taskId: String,
        title: String,
        due: String = "2026年11月15日 星期日 23:59",
        status: String = "还未提交",
    ): String = """
        <html><body><table class="generaltable"><tbody>
          <tr class="assign">
            <td class="cell c0"><a href="https://my.stu.edu.cn/courses/campus/mod/assign/view.php?id=$taskId">$title</a></td>
            <td class="cell c1">$due</td>
            <td class="cell c2">$status</td>
          </tr>
        </tbody></table></body></html>
    """.trimIndent()

    private fun adapter(
        known: Set<String> = emptySet(),
        handler: (String) -> HttpReply,
    ): WebViewSchoolAdapter = WebViewSchoolAdapter(
        schoolProvider = { SchoolCatalog.default },
        courseParser = SchoolCatalog.courseParser,
        taskParser = SchoolCatalog.taskParser,
        cookieProvider = { "session=1" },
        knownSourceCourses = { known },
        httpGet = { url, _, _, _ -> handler(url) },
    )

    @Test
    fun indexBackfillAddsAssignmentsThatHomepageNoLongerShows() = runTest {
        val adapter = adapterFor { url ->
            when {
                url == SchoolCatalog.default.taskListUrl -> HttpReply(200, homepage, url)
                // 首页只保留首页快照里的作业；索引页还列着一条已移出首页的历史作业。
                "id=1001" in url -> HttpReply(200, indexPage("500101", "历史作业：实验报告一"), url)
                else -> HttpReply(200, "<html><body>无作业</body></html>", url)
            }
        }

        val result = adapter.fetchTasks() as ModuleResult.Success<ImportedTask>

        val backfilled = result.records.single { it.externalId == "500101" }
        assertEquals("历史作业：实验报告一", backfilled.fields.title)
        assertEquals("1001", backfilled.sourceCourseId)
        assertTrue("首页作业必须保留", result.records.any { it.externalId == "307183" })
        assertEquals("首页课程编号集合不被索引页改写", setOf("1001", "1002", "3001"), result.sourceCourseIds)
    }

    @Test
    fun cachedCourseMissingFromHomepageIsStillBackfilled() = runTest {
        val sparseHomepage = """
            <div id="course-1001" class="box coursebox">
              <h2><a href="course/view.php?id=1001">[2026秋] 应用密码学 方若宇</a></h2>
            </div>
        """.trimIndent()
        val adapter = adapterFor(known = setOf("4001")) { url ->
            when {
                url == SchoolCatalog.default.taskListUrl -> HttpReply(200, sparseHomepage, url)
                "id=4001" in url -> HttpReply(200, indexPage("600001", "整课移出首页的作业"), url)
                else -> HttpReply(200, "<html><body>无作业</body></html>", url)
            }
        }

        val result = adapter.fetchTasks() as ModuleResult.Success<ImportedTask>

        val recovered = result.records.single { it.externalId == "600001" }
        assertEquals("整课移出首页的作业", recovered.fields.title)
        assertEquals("缓存编号作为来源课程透传，合并层照样记账", "4001", recovered.sourceCourseId)
    }

    @Test
    fun failingIndexRequestDegradesToHomepageResult() = runTest {
        val adapter = adapterFor { url ->
            when {
                url == SchoolCatalog.default.taskListUrl -> HttpReply(200, homepage, url)
                else -> throw IOException("索引页暂时不可用")
            }
        }

        val result = adapter.fetchTasks() as ModuleResult.Success<ImportedTask>

        assertEquals("单课失败只跳过该课，不影响首页作业", 7, result.records.size)
        assertTrue(result.records.any { it.externalId == "410001" })
    }

    @Test
    fun unrecognizedIndexStructureStopsAfterTwoMissesInsteadOfWastingRequests() = runTest {
        val requestedIndex = mutableListOf<String>()
        val adapter = adapterFor { url ->
            when {
                url == SchoolCatalog.default.taskListUrl -> HttpReply(200, homepage, url)
                else -> {
                    requestedIndex += url
                    HttpReply(200, "<html><body>页面结构变化</body></html>", url)
                }
            }
        }

        val result = adapter.fetchTasks() as ModuleResult.Success<ImportedTask>

        assertEquals("首页结果原样返回", 7, result.records.size)
        assertEquals("连续两门课都解析不到作业行后停止，避免白耗请求", 2, requestedIndex.size)
    }

    @Test
    fun indexRequestsAreCappedByCourseBudget() = runTest {
        val requestedIndex = mutableListOf<String>()
        val known = (1..30).map { "7$it" }.toSet()
        val adapter = adapterFor(known = known) { url ->
            when {
                url == SchoolCatalog.default.taskListUrl -> HttpReply(200, homepage, url)
                else -> {
                    requestedIndex += url
                    val courseId = url.substringAfter("id=")
                    HttpReply(200, indexPage("9$courseId", "补抓-$courseId"), url)
                }
            }
        }

        val result = adapter.fetchTasks() as ModuleResult.Success<ImportedTask>

        assertEquals("每课 1 请求、最多 12 门课", 12, requestedIndex.size)
        assertEquals("补抓结果已进入作业列表", 12, result.records.count { it.externalId?.startsWith("9") == true })
    }

    @Test
    fun homepageWinsWhenIndexRepeatsTheSameAssignment() = runTest {
        val adapter = adapterFor { url ->
            when {
                url == SchoolCatalog.default.taskListUrl -> HttpReply(200, homepage, url)
                // 索引页把首页已有的 307183 再列一次，且标题/状态不同。
                "id=1001" in url -> HttpReply(200, indexPage("307183", "索引页标题", status = "已提交"), url)
                else -> HttpReply(200, "<html><body>无作业</body></html>", url)
            }
        }

        val result = adapter.fetchTasks() as ModuleResult.Success<ImportedTask>

        val task = result.records.single { it.externalId == "307183" }
        assertEquals("同 ID 保留首页内容，不给 E 上交自相矛盾的数据", "提交-实验-01", task.fields.title)
    }

    /** 与 [adapter] 同构但不带默认参数名的别名，避免测试正文里重复写空 map。 */
    private fun adapterFor(known: Set<String> = emptySet(), handler: (String) -> HttpReply) =
        adapter(known, handler)
}
