package com.coursekeeper.school

import com.coursekeeper.course.WeekText
import com.coursekeeper.data.CourseDraft
import com.coursekeeper.data.ScheduleDraft
import com.coursekeeper.data.sync.ImportedCourse

/**
 * 汕头大学正方教务（`jw.stu.edu.cn/jsxsd`）学期理论课表解析（成员 C，计划书 T05）。
 *
 * 实测页面（2026-11，脱敏样例见 `app/src/test/resources/school/stu-timetable.html`）结构：
 * - `table#dataTables2`「有课表课程」：`上课班级 | 课程编号 | 课程名称 | 授课教师`，网格里只有班级号，
 *   课程稳定标识必须到这里映射；同一班级可能多行（合讲教师），按班级取首行。
 * - `table#timetable`：表头 `星期一…星期日`；行首为「第一大节 (01,02小节)」这类标签，
 *   单元格内每个条目一个 `div.kbcontent`，`<br>` 分行：
 *   `课程名 / 教师 / 周次(周)[节次] / 【校区】 / 教室 / 通知单编号 / 班级：…`。
 * - 周次写法：`3-18(周)`、`4,6,8,10,12,14,16,18(周)`、`15(周)`，直接交给 B 的 [WeekText.parse]。
 * - 节次以条目内 `[03-04节]` 为准（同一门课可跨多个大节），缺失时退回行首标签。
 * - 页面不含学期起始日，返回 null，由用户在课表页补录（不猜日期）。
 *
 * 作业来自 MYSTU 学习平台（`my.stu.edu.cn`），与教务系统不同源、不同会话，首版未接入；
 * 由 [SchoolInfo.taskListUrl] 为 null 让适配器按模块失败上报，而不是把空结果当成功。
 */
class StuJwPageParser(private val termTag: String? = null) : SchoolPageParser {

    override fun parseSchedule(html: String): ScheduleParse {
        val warnings = mutableListOf<String>()
        val table = tableById(html, "timetable")
            ?: return ScheduleParse(emptyList(), 0, null, listOf("页面未找到学期理论课表"))

        val rows = rowHtmls(table)
        if (rows.isEmpty()) {
            return ScheduleParse(emptyList(), 0, null, listOf("课表表格为空"))
        }
        val header = cellHtmls(rows.first()).map(::plainOf)
        val dayColumns = header.mapIndexedNotNull { index, text -> dayOfWeek(text)?.let { index to it } }
        if (dayColumns.isEmpty()) {
            return ScheduleParse(emptyList(), 0, null, listOf("课表表头未识别到星期列"))
        }

        val codes = courseCodes(html)
        val titles = HashMap<String, String>()
        val grouped = LinkedHashMap<String, MutableList<ScheduleDraft>>()
        var skipped = 0

        for (row in rows.drop(1)) {
            val cells = cellHtmls(row)
            val rowPeriods = cells.firstOrNull()?.let { periodsOf(plainOf(it)) }
            for ((index, day) in dayColumns) {
                val cell = cells.getOrNull(index) ?: continue
                for (block in contentBlocks(cell)) {
                    for (lines in entryGroups(linesOf(block))) {
                        if (lines.isEmpty()) continue

                        // 教师名里也可能出现“周”字（如“周艳红”），不能用 contains("周") 找周次行。
                        val weeksIndex = lines.indexOfFirst { WEEKS_LINE.containsMatchIn(it) }
                        if (weeksIndex < 0) {
                            skipped++
                            warnings += "「${lines.first()}」缺少周次，条目已跳过"
                            continue
                        }
                        val weeks = WeekText.parse(lines[weeksIndex].substringBefore("("))
                        if (weeks.isEmpty) {
                            skipped++
                            warnings += "「${lines.first()}」周次无法解析（${lines[weeksIndex]}），条目已跳过"
                            continue
                        }
                        // 即使某些周次能解析，也要让同步层知道本次课表可能缺周。
                        if (weeks.warnings.isNotEmpty()) warnings += "「${lines.first()}」周次部分无法解析"
                        val periods = PERIODS_IN_BRACKET.find(lines[weeksIndex])?.groupValues?.get(1)
                            ?.let(::periodsOf)
                            ?: rowPeriods
                        if (periods == null) {
                            skipped++
                            warnings += "「${lines.first()}」无法确定节次，条目已跳过"
                            continue
                        }

                        val classNo = lines.firstOrNull { it.startsWith("班级") }
                            ?.substringAfter('：', "")
                            ?.trim()
                            .orEmpty()
                        val mapped = codes[classNo]
                        if (mapped == null) {
                            // 没有课程编号就没有稳定标识：不导入、不猜去重键。
                            skipped++
                            warnings += "「${lines.first()}」未在“有课表课程”里找到课程编号（班级 ${classNo.ifBlank { "未知" }}），条目已跳过"
                            continue
                        }
                        val code = mapped.first
                        titles[code] = mapped.second.ifBlank { lines.first() }

                        val teacher = lines.getOrNull(weeksIndex - 1)
                            ?.takeIf { it.isNotBlank() && it != lines.first() }
                        val draft = ScheduleDraft(
                            dayOfWeek = day,
                            startPeriod = periods.first,
                            endPeriod = periods.second,
                            weeks = weeks.weeks,
                            location = locationOf(lines, weeksIndex),
                            teacher = teacher,
                        )
                        val schedules = grouped.getOrPut(code) { mutableListOf() }
                        // 跨大节的课程会在多个行里重复出现同一条；同一天/节次/周次/地点只保留一次。
                        if (schedules.none { it.sameSlotAs(draft) }) schedules += draft
                    }
                }
            }
        }

        val courses = grouped.map { (code, schedules) ->
            ImportedCourse(courseKey(code), CourseDraft(title = titles[code] ?: code, schedules = schedules))
        }
        return ScheduleParse(courses, skipped, null, warnings)
    }

    override fun parseTasks(html: String): TaskParse = TaskParse(
        tasks = emptyList(),
        missingIdentity = 0,
        warnings = listOf("作业来自 MYSTU 学习平台，首版未接入解析"),
    )

    /** 课程稳定标识带学期前缀，避免跨学期重修撞 `(source, externalId)`（B 的开放问题 6）。 */
    private fun courseKey(code: String): String = termTag?.let { "$it-$code" } ?: code

    private fun courseCodes(html: String): Map<String, Pair<String, String>> {
        val table = tableById(html, "dataTables2") ?: return emptyMap()
        val result = LinkedHashMap<String, Pair<String, String>>()
        for (row in rowHtmls(table)) {
            val cells = cellHtmls(row).map(::plainOf)
            // 列序在不同学期可能多一列“序号”，所以按“第一个含字母的课程编号”定位：
            // 它的前一列是上课班级，后一列是课程名称。纯数字的班级号不会被误认成课程编号。
            val codeIndex = cells.indexOfFirst { COURSE_CODE.matches(it) }
            if (codeIndex <= 0) continue
            val classNo = cells[codeIndex - 1].trim()
            val code = cells[codeIndex].trim()
            val title = cells.getOrNull(codeIndex + 1).orEmpty().trim()
            if (classNo.isEmpty()) continue
            result.putIfAbsent(classNo, code to title)
        }
        return result
    }

    /**
     * 地点：周次行之后先是「【校区】」，再是教室；没有校区行时直接取教室。
     * 返回教室文本（校区只影响展示，不进唯一键）。
     */
    private fun locationOf(lines: List<String>, weeksIndex: Int): String? {
        val first = lines.getOrNull(weeksIndex + 1)?.takeIf { it.isNotBlank() } ?: return null
        return if (first.startsWith("【")) {
            lines.getOrNull(weeksIndex + 2)?.takeIf { it.isNotBlank() } ?: first.trim('【', '】')
        } else {
            first
        }
    }

    /**
     * 正方页面把同一单元格的多条排课塞进同一个 `div.kbcontent`，用一整行短横线分隔
     * （实测 2026-11：大学物理实验一个格子里 4-7/8-16/17-18 周三段、潮汕非遗八段）。
     * 不拆开就只会解析每格第一条，后面的周次全部丢失。
     */
    private fun entryGroups(lines: List<String>): List<List<String>> {
        if (lines.isEmpty()) return emptyList()
        val groups = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (line in lines) {
            if (ENTRY_SEPARATOR.matches(line)) {
                if (current.isNotEmpty()) groups += current
                current = mutableListOf()
            } else {
                current += line
            }
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    private fun ScheduleDraft.sameSlotAs(other: ScheduleDraft): Boolean =
        dayOfWeek == other.dayOfWeek && startPeriod == other.startPeriod &&
            endPeriod == other.endPeriod && weeks == other.weeks && location == other.location

    private fun periodsOf(text: String): Pair<Int, Int>? {
        val numbers = DIGITS.findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
        if (numbers.isEmpty()) return null
        val start = numbers.min()
        val end = numbers.max()
        if (start < 1 || end > MAX_PERIOD) return null
        return start to end
    }

    private fun dayOfWeek(text: String): Int? = when {
        text.contains("星期一") || text.contains("周一") -> 1
        text.contains("星期二") || text.contains("周二") -> 2
        text.contains("星期三") || text.contains("周三") -> 3
        text.contains("星期四") || text.contains("周四") -> 4
        text.contains("星期五") || text.contains("周五") -> 5
        text.contains("星期六") || text.contains("周六") -> 6
        text.contains("星期日") || text.contains("周日") || text.contains("星期天") -> 7
        else -> null
    }

    private fun tableById(html: String, id: String): String? =
        Regex("""<table[^>]*id="$id"[^>]*>.*?</table>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.value

    private fun rowHtmls(table: String): List<String> =
        Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(table).map { it.groupValues[1] }.toList()

    private fun cellHtmls(row: String): List<String> =
        Regex("<t[dh][^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(row).map { it.groupValues[1] }.toList()

    /** 每个条目一个 `div.kbcontent`；`kbcontent1` 是旧版展示块，不参与解析。 */
    private fun contentBlocks(cellHtml: String): List<String> =
        Regex(
            """<div[^>]*class="kbcontent"[^>]*>(.*?)(?=<div[^>]*class="kbcontent"|</td>|$)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(cellHtml).map { it.groupValues[1] }.toList()

    private fun linesOf(fragment: String): List<String> =
        fragment
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?(div|p|li|tr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(TAG, "")
            .let { MiniHtml.unescape(it) }
            .replace('\u00A0', ' ')
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun plainOf(fragment: String): String =
        MiniHtml.unescape(fragment.replace(TAG, " ")).replace('\u00A0', ' ').trim()

    private companion object {
        /** 节次常识上限，最终以 B 的学期节次配置为准。 */
        const val MAX_PERIOD = 16

        val TAG = Regex("<[^>]*>")
        val DIGITS = Regex("""\d{1,2}""")

        /** 课程编号必须含字母（如 CST3254A），否则会把纯数字的上课班级号误判成编号。 */
        val COURSE_CODE = Regex("[A-Za-z0-9._-]*[A-Za-z][A-Za-z0-9._-]*")
        val PERIODS_IN_BRACKET = Regex("""\[([\d\-]+)节]""")

        /** 周次行：数字开头、可带逗号/连字符/中文逗号，后接可选的“(周)”，兼容导出页的“([周])”双层括号。 */
        val WEEKS_LINE = Regex("""\d[\d,\-，、]*\s*[\(（]?\[?\s*周""")

        /** 同一 div 内多条排课的分隔行：一整行短横线（≥5 个）。 */
        val ENTRY_SEPARATOR = Regex("""-{5,}""")
    }
}
