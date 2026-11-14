package com.coursekeeper.school

import com.coursekeeper.course.WeekText
import com.coursekeeper.data.CourseDraft
import com.coursekeeper.data.ScheduleDraft
import com.coursekeeper.data.TaskDraft
import com.coursekeeper.data.model.TaskType
import com.coursekeeper.data.sync.ImportedCourse
import com.coursekeeper.data.sync.ImportedTask
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 学校页面解析层（成员 C，计划书 T05）。
 *
 * T05 实测前先按常见教务系统的表格课表 + 列表作业页实现参考解析；脱敏样例见
 * `app/src/test/resources/school/`。实测后只调整 [TableSchoolPageParser] 的字段
 * 提取规则或替换实现，`SchoolAdapter` 契约与 E 的合并规则不动。
 *
 * 稳定标识约定（E 交接 §C 的同步输入）：
 * - 课程 externalId 取页面上的课程代码（如 `高等数学(A01MATH)` 中的 `A01MATH`）。
 *   `courses` 的 `(source, externalId)` 是全局唯一键，跨学期重修会撞（B 的开放问题 6），
 *   因此构造时可传 `termTag`（如 `2026AUT`）做前缀；未传则保持课程代码原样。
 *   页面未提供课程代码的条目不导入，计入 skippedCourses，不猜测去重键。
 * - 任务 externalId 取列表项链接里的 `id=` 参数或 `data-id` 属性；缺失时**仍以
 *   `externalId = null` 上交**，由 E 计入 missingIdentity 并暂缓入库。适配器不静默丢弃，
 *   否则同步记录里的缺失计数永远是 0，登录页也无法如实告诉用户有多少条没导进来。
 * - 学期起始日解析不出返回 null，由用户在课表页补充输入，不猜日期（计划书 §四）。
 * - 节次无法从行首标签或条目内联文本确定时整条跳过，不默认到第 1 节（不猜）。
 */
interface SchoolPageParser {
    fun parseSchedule(html: String): ScheduleParse
    fun parseTasks(html: String): TaskParse
}

data class ScheduleParse(
    val courses: List<ImportedCourse>,
    /** 有内容但缺课程代码、周次无法解析或节次无法确定而被跳过的条目数。 */
    val skippedCourses: Int,
    val semesterStart: LocalDate?,
    val warnings: List<String>,
)

data class TaskParse(
    val tasks: List<ImportedTask>,
    /** 缺稳定 ID 的任务数；这些任务仍随 [tasks] 以 `externalId = null` 上交，由 E 计数并暂缓入库。 */
    val missingIdentity: Int,
    val warnings: List<String>,
)

/**
 * 宽表课表 + 列表作业的参考实现：手写容错解析，不引入第三方 HTML 库。
 *
 * [termTag] 是学期标签（如 `2026AUT`），用于给课程稳定标识加学期前缀；为 null 时行为不变。
 * T05 实测确定学校后由 `SchoolInfo.termTag` 注入，避免跨学期重修撞 `(source, externalId)`。
 */
class TableSchoolPageParser(private val termTag: String? = null) : SchoolPageParser {

    override fun parseSchedule(html: String): ScheduleParse {
        val warnings = mutableListOf<String>()
        val table = MiniHtml.firstTableContaining(html, "星期") ?: run {
            warnings += "页面未找到课表表格"
            return ScheduleParse(emptyList(), 0, semesterStartOf(html), warnings)
        }
        val rows = MiniHtml.rows(table).map { MiniHtml.cells(it) }
        val headerIndex = rows.indexOfFirst { row -> row.any { dayOfWeekOf(it) != null } }
        if (headerIndex < 0) {
            warnings += "课表表头未识别到星期列"
            return ScheduleParse(emptyList(), 0, semesterStartOf(html), warnings)
        }
        val dayColumns = rows[headerIndex]
            .mapIndexedNotNull { index, cell -> dayOfWeekOf(cell)?.let { index to it } }
        if (dayColumns.isEmpty()) {
            warnings += "课表表头未识别到星期列"
            return ScheduleParse(emptyList(), 0, semesterStartOf(html), warnings)
        }

        // 课程代码 -> (标题, 排课集合)；同一门课占多个单元格时合并为一条记录。
        val grouped = LinkedHashMap<String, Pair<String, MutableList<ScheduleDraft>>>()
        var skipped = 0
        for (row in rows.drop(headerIndex + 1)) {
            val periods = row.firstOrNull()?.let { periodRangeOf(it) }
            for ((column, day) in dayColumns) {
                val cell = row.getOrNull(column) ?: continue
                // 一个单元格可含多条课程条目：以"课程名(代码)"行为界分组，其余行为元数据。
                for (group in MiniHtml.entryGroups(cell)) {
                    val entry = entryOf(group, periods) ?: continue
                    if (entry.code.isBlank() || WeekText.parse(entry.weeksText).isEmpty) {
                        warnings += "「${entry.title}」缺少课程代码或周次无法解析，条目已跳过"
                        skipped++
                        continue
                    }
                    val startPeriod = entry.startPeriod
                    val endPeriod = entry.endPeriod
                    if (startPeriod == null || endPeriod == null) {
                        // 行首标签与条目内联文本都没有节次：跳过而不是默认第 1 节。
                        warnings += "「${entry.title}」无法确定节次，条目已跳过"
                        skipped++
                        continue
                    }
                    val weeks = WeekText.parse(entry.weeksText)
                    val draft = ScheduleDraft(
                        dayOfWeek = day,
                        startPeriod = startPeriod,
                        endPeriod = endPeriod,
                        weeks = weeks.weeks,
                        location = entry.location,
                        teacher = entry.teacher,
                    )
                    val key = courseKey(entry.code)
                    val existing = grouped[key]
                    if (existing == null) grouped[key] = entry.title to mutableListOf(draft)
                    else existing.second += draft
                }
            }
        }
        val courses = grouped.map { (code, value) -> ImportedCourse(code, CourseDraft(value.first, schedules = value.second)) }
        return ScheduleParse(courses, skipped, semesterStartOf(html), warnings)
    }

    override fun parseTasks(html: String): TaskParse {
        val warnings = mutableListOf<String>()
        val tasks = mutableListOf<ImportedTask>()
        var missingIdentity = 0
        for (item in MiniHtml.listItems(html)) {
            val text = MiniHtml.textOf(item.html)
            if (text.isBlank()) continue
            val title = titleOf(item) ?: continue
            val externalId = (item.linkId ?: item.dataId)?.takeIf { it.isNotBlank() }
            if (externalId == null) {
                // 缺稳定标识仍上交（externalId = null）：由 E 计入 missingIdentity 并暂缓入库，
                // 适配器不静默丢弃，否则同步记录里的缺失计数永远是 0。
                missingIdentity++
                warnings += "「$title」缺少稳定标识，已暂缓导入"
            }
            val task = TaskDraft(
                title = title.take(MAX_TITLE_LENGTH),
                type = taskTypeOf(text),
                dueAt = DueDates.parse(text),
                courseId = null,
            )
            tasks += ImportedTask(externalId, task, courseExternalIdOf(text)?.let(::courseKey))
        }
        return TaskParse(tasks, missingIdentity, warnings)
    }

    private data class CellEntry(
        val code: String, val title: String, val weeksText: String,
        /** 无法确定时为 null：调用方跳过该条目，不默认到第 1 节。 */
        val startPeriod: Int?, val endPeriod: Int?,
        val location: String?, val teacher: String?,
    )

    /** 单元格条目格式：`课程名(课程代码)` + 周次行 + 地点行 + 教师行（顺序容错）。 */
    private fun entryOf(lines: List<String>, periods: Pair<Int, Int>?): CellEntry? {
        if (lines.isEmpty()) return null
        val nameLine = lines.first()
        if (nameLine.isBlank()) return null
        val match = NAME_WITH_PARENS.matchEntire(nameLine)
        var title = nameLine
        var code = ""
        if (match != null) {
            title = match.groupValues[1].trim()
            if (title.isBlank()) return null
            val paren = match.groupValues[2].trim()
            if (CODE_ONLY.matches(paren)) code = paren
        }
        // 周次行：名称行之外第一处含"周"且带周次写法的行；其余行按"地点、教师"取值。
        val weeksText = lines.drop(1).firstOrNull { it.contains("周") && WEEKS_HINT.containsMatchIn(it) } ?: ""
        val remainder = lines.drop(1).filter { it != weeksText && it.isNotBlank() }
        val location = remainder.firstOrNull()?.takeIf { it != weeksText }
        val inlinePeriods = remainder.lastOrNull()?.let { periodRangeOf(it) } ?: periods
        // 教师来源：名称行括号（如 `高等数学(王老师)`），或第二条元数据行（`周次/地点/教师` 四行格式）。
        val parenTeacher = match?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() && !CODE_ONLY.matches(it) }
        return CellEntry(
            code = code,
            title = title,
            weeksText = weeksText,
            startPeriod = inlinePeriods?.first,
            endPeriod = inlinePeriods?.second ?: inlinePeriods?.first,
            location = location,
            teacher = parenTeacher ?: remainder.getOrNull(1)?.takeIf { it != location },
        )
    }

    /** 标题优先取链接文本（作业列表的惯例），缺链接时退回整行文本并去掉尾部日期。 */
    private fun titleOf(item: MiniHtml.ListItemRef): String? {
        val linkText = MiniHtml.linkTextOf(item.html)
        val raw = (linkText?.takeIf { it.isNotBlank() } ?: MiniHtml.textOf(item.html))
            .replace(WHEN_SUFFIX, "").trim()
        val title = raw.trim().take(MAX_TITLE_LENGTH)
        return title.takeIf { it.isNotBlank() }
    }

    private fun courseExternalIdOf(text: String): String? =
        CODE_WITH_PARENS.findAll(text).firstOrNull()?.groupValues?.last()?.takeIf { CODE_ONLY.matches(it) }

    /** 课程稳定标识：带学期前缀时避免跨学期重修撞 `(source, externalId)` 全局唯一键（B 的开放问题 6）。 */
    private fun courseKey(code: String): String = termTag?.let { "$it-$code" } ?: code

    private fun taskTypeOf(text: String): TaskType = when {
        text.contains("实验") -> TaskType.LAB_REPORT
        text.contains("社团") || text.contains("俱乐部") -> TaskType.CLUB
        text.contains("兼职") || text.contains("实习") -> TaskType.WORK
        else -> TaskType.HOMEWORK
    }

    private fun dayOfWeekOf(cell: String): Int? = when {
        cell.contains("星期一") || cell.contains("周一") -> 1
        cell.contains("星期二") || cell.contains("周二") -> 2
        cell.contains("星期三") || cell.contains("周三") -> 3
        cell.contains("星期四") || cell.contains("周四") -> 4
        cell.contains("星期五") || cell.contains("周五") -> 5
        cell.contains("星期六") || cell.contains("周六") -> 6
        cell.contains("星期日") || cell.contains("周日") || cell.contains("星期天") -> 7
        else -> null
    }

    /** 行首节次标签：`第1-2节`、`1-2`、`第3节`；解析不出返回 null（交由条目内联节次或默认第 1 节）。 */
    private fun periodRangeOf(label: String): Pair<Int, Int>? {
        val match = PERIOD_RANGE.find(label.replace("第", "").replace("节", "")) ?: return null
        val start = match.groupValues[1].toIntOrNull() ?: return null
        val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: start
        if (start < 1 || end < start || end > MAX_PERIOD) return null
        return start to end
    }

    /** 学期起始日：仅识别页面明示的"起始/开始"日期，解析不出返回 null，不猜日期。 */
    private fun semesterStartOf(html: String): LocalDate? {
        val match = SEMESTER_START.find(html) ?: return null
        return runCatching {
            LocalDate.parse(
                "%04d-%02d-%02d".format(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt(),
                ),
            )
        }.getOrNull()
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 60

        /**
         * 节次的常识上限，只用于挡明显脏数据；一节排课是否超出本学期节次数，
         * 由 B 的学期配置（默认 13 节）在写侧校验，解析层不重复定义业务口径。
         */
        const val MAX_PERIOD = 16
        val NAME_WITH_PARENS = Regex("^(.+?)[（(]([^）)]*)[）)].*$", RegexOption.DOT_MATCHES_ALL)
        val CODE_ONLY = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        val CODE_WITH_PARENS = Regex("[（(]([A-Za-z0-9][A-Za-z0-9._-]*)[）)]")
        val WEEKS_HINT = Regex("""\d+\s*[-~－—]\s*\d+|单周|双周|\d+周""")
        val PERIOD_RANGE = Regex("""^(\d+)(?:\s*[-~]\s*(\d+))?$""")
        val SEMESTER_START =
            Regex("""(?:学期开始|起始日|开始日期|第一周)[^\d]{0,12}(\d{4})[年/\-.](\d{1,2})[月/\-.](\d{1,2})""")
        val WHEN_SUFFIX = Regex("""\d{4}[年/\-.]\d{1,2}[月/\-.]\d{1,2}日?\s*\d{0,2}:?\d{0,2}$""")
    }
}

/** 截止时间解析：`2026-11-20 23:59`、`2026年11月20日`、`2026/11/20`；缺时刻默认 23:59。 */
object DueDates {
    private val pattern =
        Regex("""(\d{4})[年/\-.](\d{1,2})[月/\-.](\d{1,2})日?(?:\s+(\d{1,2}):(\d{2}))?""")
    private val fallbackTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun parse(text: String): LocalDateTime? {
        val match = pattern.find(text) ?: return null
        val date = runCatching {
            LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
        }.getOrNull() ?: return null
        val hour = match.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 23
        val minute = match.groupValues[5].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 59
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
    }

    fun format(value: LocalDateTime): String = value.format(fallbackTime)
}

/** 手写容错 HTML 片段提取：只覆盖课表/作业页用到的结构，解析失败按空处理并记警告。 */
internal object MiniHtml {

    fun unescape(text: String): String = text
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }

    fun textOf(fragment: String): String = unescape(fragment.replace(TAG, ""))

    /** 按 `<br>`/块级标签拆分单元格内容为条目行（换行即条目分隔）。 */
    fun blocks(cellHtml: String): List<String> =
        cellHtml.replace(BLOCK_SPLIT, "\n")
            .split("\n")
            .map { textOf(it).trim() }
            .filter { it.isNotEmpty() }

    fun rows(tableHtml: String): List<String> =
        tableHtml.split("<tbody>", "</tbody>").joinToString("").split(ROW_START).drop(1)
            .map { it.substringBefore("</tr>") }

    fun cells(rowHtml: String): List<String> =
        rowHtml.split(CELL_START).drop(1).map { it.substringBefore("</td>").substringBefore("</th>") }

    fun firstTableContaining(html: String, keyword: String): String? =
        TABLE.findAll(html).map { it.value }.firstOrNull { textOf(it).contains(keyword) }

    /** 列表项：`<li>`（含链接），用于作业公告页解析。 */
    fun listItems(html: String): List<ListItemRef> =
        LI.findAll(html).map { match ->
            ListItemRef(match.value, linkIdOf(match.value), dataIdOf(match.value))
        }.toList()

    /**
     * 将单元格内容按"课程名(代码)"行分组为条目：其余行（周次、地点、教师）归入当前条目。
     * 教务宽表的一格里通常一门课一至多个时段；同格多门课时按带代码的名称行分界。
     */
    fun entryGroups(cellHtml: String): List<List<String>> {
        val lines = blocks(cellHtml)
        if (lines.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<String>>(mutableListOf(lines.first()))
        for (line in lines.drop(1)) {
            // 带课程代码的行是新条目的分界；其余行（周次/地点/教师）归入当前条目。
            if (CODE_IN_PARENS.containsMatchIn(line)) groups += mutableListOf(line) else groups.last() += line
        }
        return groups
    }

    data class ListItemRef(val html: String, val linkId: String?, val dataId: String?)

    fun linkIdOf(fragment: String): String? =
        HREF_ID.find(fragment)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    fun dataIdOf(fragment: String): String? =
        DATA_ID.find(fragment)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    fun linkTextOf(fragment: String): String? =
        ANCHOR.find(fragment)?.groupValues?.get(1)?.let { textOf(it).trim() }

    private val TAG = Regex("<[^>]*>")
    private val ANCHOR = Regex("""<a[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BLOCK_SPLIT = Regex("<br\\s*/?>|</p>|<p[^>]*>|</div>|<div[^>]*>", RegexOption.IGNORE_CASE)
    private val ROW_START = Regex("<tr[^>]*>", RegexOption.IGNORE_CASE)
    private val CELL_START = Regex("<t[dh][^>]*>", RegexOption.IGNORE_CASE)
    private val TABLE = Regex("<table[^>]*>.*?</table>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val LI = Regex("<li[^>]*>.*?</li>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HREF_ID = Regex("""href="[^"]*[?&]id=([^"&]+)""", RegexOption.IGNORE_CASE)
    private val DATA_ID = Regex("""data-id="([^"]+)""", RegexOption.IGNORE_CASE)
    private val CODE_IN_PARENS = Regex("[（(]([A-Za-z0-9][A-Za-z0-9._-]*)[）)]")
}
