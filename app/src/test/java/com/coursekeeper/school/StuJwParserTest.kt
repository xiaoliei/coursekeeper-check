package com.coursekeeper.school

import com.coursekeeper.course.WeekText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 汕头大学正方教务课表解析测试。
 * 样例 [stu-timetable.html] 按 2026-11 实测页面结构脱敏后编写：课程编号来自“有课表课程”表，
 * 网格条目为 `div.kbcontent`，字段按 `<br>` 分行。
 */
class StuJwParserTest {

    private val parser = StuJwPageParser()

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("school/$name")!!.readBytes().decodeToString()

    @Test
    fun coursesUseCodeFromListTableAndKeepTitle() {
        val result = parser.parseSchedule(fixture("stu-timetable.html"))

        assertEquals(3, result.courses.size)
        assertNull("实测页面没有学期起始日，不得猜测", result.semesterStart)
        val byCode = result.courses.associateBy { it.externalId }
        assertEquals("应用密码学", byCode.getValue("CST3254A").fields.title)
        assertEquals("编译原理", byCode.getValue("CST3504A").fields.title)
        assertEquals("羽毛球（2）", byCode.getValue("PED1042A").fields.title)
    }

    @Test
    fun entryFieldsMapDayPeriodsWeeksTeacherAndRoom() {
        val result = parser.parseSchedule(fixture("stu-timetable.html"))
        val crypto = result.courses.single { it.externalId == "CST3254A" }.fields.schedules.single()

        assertEquals(1, crypto.dayOfWeek)
        assertEquals(1, crypto.startPeriod)
        assertEquals(2, crypto.endPeriod)
        assertEquals(WeekText.parse("3-18").weeks, crypto.weeks)
        assertEquals("3-18", crypto.weeksText)
        assertEquals("方若宇", crypto.teacher)
        // 地点在页面里分两行（【校区】+ 教室），只取教室。
        assertEquals("东海岸-E教133-阶梯", crypto.location)
    }

    @Test
    fun periodsComeFromEntryBracketNotRowLabel() {
        val result = parser.parseSchedule(fixture("stu-timetable.html"))
        val compiler = result.courses.single { it.externalId == "CST3504A" }.fields.schedules

        assertEquals(2, compiler.size)
        assertTrue("第二大节标签是 03-05，但条目写明 03-04，应以条目为准", compiler.all { it.startPeriod == 3 && it.endPeriod == 4 })
        assertEquals(
            setOf(WeekText.parse("3-18").weeks, WeekText.parse("4,6,8,10,12,14,16,18").weeks),
            compiler.map { it.weeks }.toSet(),
        )
    }

    @Test
    fun repeatedBlockAcrossRowsIsDeduplicated() {
        // 跨大节的课在多行里重复出现同一条：同一天/节次/周次/地点只保留一条。
        val result = parser.parseSchedule(fixture("stu-timetable.html"))
        val pe = result.courses.single { it.externalId == "PED1042A" }.fields.schedules

        assertEquals(1, pe.size)
        assertEquals(8 to 9, pe.single().startPeriod to pe.single().endPeriod)
        assertEquals("东海岸-体育馆羽毛球场", pe.single().location)
    }

    @Test
    fun termTagPrefixesCourseIds() {
        val tagged = StuJwPageParser(termTag = "2026AUT").parseSchedule(fixture("stu-timetable.html"))
        assertTrue(tagged.courses.all { it.externalId.startsWith("2026AUT-") })
        assertNotNull(tagged.courses.firstOrNull { it.externalId == "2026AUT-CST3254A" })
    }

    @Test
    fun pageWithoutTimetableReportsWarningInsteadOfThrowing() {
        val result = parser.parseSchedule("<html><body>登录已失效</body></html>")

        assertTrue(result.courses.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun coTaughtEntriesInSameSlotKeepBothTeacherNames() {
        // B v0.3 §8：解析层去重键不含教师，合讲（同格同周次、两位教师）应合并成一条，
        // 教师合并为“张三/李四”；只留第一条会丢掉第二位教师。
        val html = """
            <table id="dataTables2" border="1">
              <tr><th>序号</th><th>上课班级</th><th>课程编号</th><th>课程名称</th><th>授课教师</th></tr>
              <tr><td>1</td><td>202611700</td><td>XX2001A</td><td>合讲示例</td><td>张三</td></tr>
            </table>
            <table id="timetable" border="1">
              <tr><th>&nbsp;</th><th>星期一</th></tr>
              <tr><th>第一大节<br>(01,02小节)</th>
                <td>
                  <div class="kbcontent">
                    <font>合讲示例</font><br><font>张三</font><br>
                    <font>3-18(周)[01-02节]</font><br><font>东海岸-E教101</font><br>
                    <font>班级：202611700</font><br>
                  </div>
                  <div class="kbcontent">
                    <font>合讲示例</font><br><font>李四</font><br>
                    <font>3-18(周)[01-02节]</font><br><font>东海岸-E教101</font><br>
                    <font>班级：202611700</font><br>
                  </div>
                </td>
              </tr>
            </table>
        """.trimIndent()

        val result = parser.parseSchedule(html)

        assertEquals(emptyList<String>(), result.warnings)
        val schedule = result.courses.single().fields.schedules.single()
        assertEquals("张三/李四", schedule.teacher)
    }

    @Test
    fun nestedBracketWeekFormatIsParsed() {
        // 实测导出（xls/部分页面）会出现 “9,11-13([周])[06-07节]” 的双层括号写法。
        val html = """
            <table id="dataTables2" border="1">
              <tr><th>序号</th><th>上课班级</th><th>课程编号</th><th>课程名称</th><th>授课教师</th></tr>
              <tr><td>1</td><td>202611620</td><td>XX1234A</td><td>潮汕非遗数字化传承服务实践</td><td>梁丽娟</td></tr>
            </table>
            <table id="timetable" border="1">
              <tr><th>&nbsp;</th><th>星期三</th></tr>
              <tr><th>第三大节<br>(06,07小节)</th>
                <td><div class="kbcontent">
                  <font>潮汕非遗数字化传承服务实践</font><br>
                  <font>梁丽娟,朱小雪</font><br>
                  <font>9,11-13([周])[06-07-08-09-10节]</font><br>
                  <font>外出实践（校外）</font><br>
                  <font>班级：202611620</font><br>
                </div></td>
              </tr>
            </table>
        """.trimIndent()

        val result = parser.parseSchedule(html)

        assertEquals("不应再因双层括号周次被整条跳过", emptyList<String>(), result.warnings)
        val course = result.courses.single { it.externalId == "XX1234A" }
        val schedule = course.fields.schedules.single()
        assertEquals(WeekText.parse("9,11-13").weeks, schedule.weeks)
        assertEquals(6, schedule.startPeriod)
        assertEquals(10, schedule.endPeriod)
        assertEquals("外出实践（校外）", schedule.location)
    }

    @Test
    fun multipleEntriesInOneCellAreAllParsed() {
        // 实测（2026-11）：同一 div.kbcontent 里用一整行短横线分隔多条排课
        // （如大学物理实验 4-7/8-16/17-18 周三段），不拆开就只解析得到第一条。
        val html = """
            <table id="dataTables2" border="1">
              <tr><th>序号</th><th>上课班级</th><th>课程编号</th><th>课程名称</th><th>授课教师</th></tr>
              <tr><td>1</td><td>202610280</td><td>PHY1105A</td><td>大学物理实验（1）</td><td>傅勇</td></tr>
            </table>
            <table id="timetable" border="1">
              <tr><th>&nbsp;</th><th>星期一</th></tr>
              <tr><th>第三大节<br>(06,07小节)</th>
                <td><div class="kbcontent">
                  <font>大学物理实验（1）</font><br>
                  <font>傅勇,李典</font><br>
                  <font>4-7(周)[06-07-08节]</font><br>
                  <font>【东海岸校区-D栋实验楼】</font><br>
                  <font>东海岸-D栋实验楼5楼-普通物理实验室</font><br>
                  <font>班级：202610280</font><br>
                  <font>备注：</font><br>
                  ----------------------<br>
                  <font>大学物理实验（1）</font><br>
                  <font>傅勇,吕秀品</font><br>
                  <font>8-16(周)[06-07-08节]</font><br>
                  <font>【东海岸校区-D栋实验楼】</font><br>
                  <font>东海岸-D栋实验楼5楼-普通物理实验室</font><br>
                  <font>班级：202610280</font><br>
                  ----------------------<br>
                  <font>大学物理实验（1）</font><br>
                  <font>傅勇,唐凌志</font><br>
                  <font>17-18(周)[06-07-08节]</font><br>
                  <font>东海岸-D栋实验楼5楼-普通物理实验室</font><br>
                  <font>班级：202610280</font><br>
                </div></td>
              </tr>
            </table>
        """.trimIndent()

        val result = parser.parseSchedule(html)

        assertEquals(emptyList<String>(), result.warnings)
        val schedules = result.courses.single { it.externalId == "PHY1105A" }.fields.schedules
        assertEquals("同一格子的三段周次都要导入", 3, schedules.size)
        assertEquals(
            setOf(
                WeekText.parse("4-7").weeks,
                WeekText.parse("8-16").weeks,
                WeekText.parse("17-18").weeks,
            ),
            schedules.map { it.weeks }.toSet(),
        )
        assertTrue(schedules.all { it.startPeriod == 6 && it.endPeriod == 8 && it.dayOfWeek == 1 })
    }
}
