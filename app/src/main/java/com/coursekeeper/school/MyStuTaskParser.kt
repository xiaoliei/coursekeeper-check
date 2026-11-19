package com.coursekeeper.school

import com.coursekeeper.data.TaskDraft
import com.coursekeeper.data.model.TaskType
import com.coursekeeper.data.sync.ImportedTask

/**
 * MYSTU 学习平台（Moodle）作业解析（成员 C，计划书 T05）。
 *
 * 实测页面（`my.stu.edu.cn/courses/campus/my/`，脱敏样例见
 * `app/src/test/resources/school/mystu-dashboard.html`）是 Moodle 的“课程概览”：
 * - 每门课一个 `div.coursebox#course-<id>`，课程标题形如 `[2026秋] 应用密码学 方若宇`；
 * - 作业块 `div.assignment.overview` 内：`div.name > a`（标题，链接含 `mod/assignment/view.php?id=<作业ID>`）、
 *   `div.info`（`截止时间: 2026年11月15日 星期日 23:59`）、`div.details`（提交状态）。
 *
 * 稳定标识取作业 ID（`view.php?id=`），跨同步稳定；页面上每条作业都有链接，缺 ID 的按
 * `externalId = null` 上交由 E 计数。
 *
 * 首版**不做课程关联**：MYSTU 的课程是另一套 ID 体系（`course/view.php?id=`），与教务课程编号
 * 不能直接对上；按课程名模糊匹配会引入错误关联。但会把源课程标识/名称与提交状态原样透传
 * （CBE-01 / CD-01），由 B/E/D 约定映射与展示口径后再落库，C 侧不猜测关联。
 */
class MyStuTaskParser : SchoolPageParser {

    override fun parseSchedule(html: String): ScheduleParse =
        ScheduleParse(emptyList(), 0, null, listOf("MYSTU 作业页不含课表"))

    override fun parseTasks(html: String): TaskParse {
        val warnings = mutableListOf<String>()
        val tasks = mutableListOf<ImportedTask>()
        var missingIdentity = 0

        for (chunk in courseChunks(html)) {
            for (match in ASSIGNMENT.findAll(chunk.html)) {
                val body = match.groupValues[1]
                val details = match.groupValues[2]
                val rawTitle = NAME_ANCHOR.find(body)?.groupValues?.get(1) ?: continue
                val title = cleanTitle(rawTitle).take(MAX_TITLE_LENGTH)
                if (title.isBlank()) continue

                val externalId = ASSIGNMENT_ID.find(body)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                if (externalId == null) {
                    missingIdentity++
                    warnings += "「$title」缺少作业 ID，已暂缓导入"
                }
                val info = INFO.find(body)?.groupValues?.get(1).orEmpty()
                tasks += ImportedTask(
                    externalId = externalId,
                    fields = TaskDraft(
                        title = title,
                        type = typeOf(title),
                        dueAt = DueDates.parse(info),
                    ),
                    // 教务课程编号与 MYSTU 课程 ID 不同源，不能直接当成已解析的关联。
                    courseExternalId = null,
                    sourceCourseId = chunk.id,
                    sourceCourseName = chunk.name,
                    schoolSubmission = submissionOf(details),
                )
            }
        }

        if (tasks.isEmpty() && warnings.isEmpty()) {
            warnings += "页面未找到作业列表（可能未登录或页面结构变化）"
        }
        return TaskParse(tasks, missingIdentity, warnings)
    }

    private data class CourseChunk(val id: String?, val name: String?, val html: String)

    /** 按课程块切分，避免作业 ID 跨课程串台；同时带上课程块的源标识与名称。 */
    private fun courseChunks(html: String): List<CourseChunk> {
        val matches = COURSE_BOX.findAll(html).toList()
        if (matches.isEmpty()) return listOf(CourseChunk(null, null, html))
        return matches.mapIndexed { index, match ->
            val start = match.range.first
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else html.length
            val chunk = html.substring(start, end)
            CourseChunk(
                id = match.groupValues[1].ifBlank { null },
                name = COURSE_NAME.find(chunk)?.groupValues?.get(1)?.let(::plainText)?.takeIf { it.isNotBlank() },
                html = chunk,
            )
        }
    }

    /**
     * `div.details` 的提交状态原文 → 归一化值（CD-01）。只做文本映射，不推断平台规则：
     * 判断“是否算完成、要不要从待办移出”仍由 D/E 按约定决定。
     */
    private fun submissionOf(detailsHtml: String): String? {
        val text = plainText(detailsHtml)
        return when {
            text.isBlank() -> null
            "草稿" in text -> "DRAFT"
            "重新提交" in text -> "REOPENED"
            "还未提交" in text || "未提交" in text -> "NOT_SUBMITTED"
            "已提交" in text -> "SUBMITTED"
            else -> "UNKNOWN"
        }
    }

    private fun plainText(fragment: String): String =
        MiniHtml.unescape(fragment.replace(TAG, " ")).replace('\u00A0', ' ').trim()

    /** 标题里常带“-截止时间 2026.11.15 23：59”，去掉，截止时间以 div.info 为准。 */
    private fun cleanTitle(raw: String): String =
        raw.replace(TITLE_SUFFIX, "").trim()

    private fun typeOf(title: String): TaskType = when {
        "实验" in title || "报告" in title -> TaskType.LAB_REPORT
        "社团" in title || "俱乐部" in title -> TaskType.CLUB
        "实习" in title || "兼职" in title -> TaskType.WORK
        else -> TaskType.HOMEWORK
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 60

        /** 课程块，捕获 MYSTU 课程 ID（`course/view.php?id=` 同源）。 */
        val COURSE_BOX = Regex("""<div id="course-(\d+)"[^>]*>""")
        /** 课程标题锚文本，形如 `[2026秋] 应用密码学 方若宇`，作为源课程名称透传。 */
        val COURSE_NAME = Regex("""<h2[^>]*>.*?<a[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val ASSIGNMENT = Regex(
            """<div class="assignment overview">(.*?)<div class="details">(.*?)</div>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val ASSIGNMENT_ID = Regex("""mod/assignment/view\.php\?id=(\d+)""")
        val NAME_ANCHOR = Regex("""<div class="name">.*?<a[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val INFO = Regex("""<div class="info">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        val TITLE_SUFFIX = Regex("""\s*[-－—]?\s*截止时间.*$""")
        val TAG = Regex("<[^>]*>")
    }
}
