package com.coursekeeper.statistics

import com.coursekeeper.data.model.CourseRecord
import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.TaskRecord
import com.coursekeeper.data.model.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 规则引擎测试（纯 JVM，无 Robolectric）。固定时钟 2026-11-11 10:00（周三，本周已过半），
 * 本周窗口 [2026-11-09, 2026-11-16)，与《统计口径与规则草案 v0.3》§三的判定条件一一对应。
 */
class RuleEngineTest {

    private val now = LocalDateTime.of(2026, 11, 11, 10, 0) // 周三

    private fun task(
        id: String,
        due: LocalDateTime? = null,
        completed: LocalDateTime? = null,
        title: String = id,
    ) = TaskRecord(id, title, TaskType.HOMEWORK, dueAt = due, completedAt = completed)

    private fun evaluate(
        tasks: List<TaskRecord> = emptyList(),
        courses: List<CourseRecord> = emptyList(),
        courseStats: CourseStats = CourseStats(),
        completionRate: Float? = null,
        semesterConfigured: Boolean = true,
        now: LocalDateTime = this.now,
    ) = RuleEngine.evaluate(
        DataSnapshot(tasks = tasks, courses = courses), courseStats, now, completionRate, semesterConfigured)

    // —— 规则 5：逾期（最高优先级）——

    @Test
    fun rule5OverdueUncompletedTasks() {
        val notices = evaluate(tasks = listOf(
            task("a", due = now.minusHours(1)),
            task("done", due = now.minusHours(2), completed = now.minusHours(1)), // 已完成不算
        ))
        assertEquals(listOf(5), notices.map { it.ruleId })
        // a 今天 09:00 截止、now 为 10:00：同时命中规则 5 与规则 1，合并成一条，两条文案都保留。
        assertEquals("有 1 项任务已逾期，可补录或调整：a；今天有 1 项任务截止，记得完成：a",
            notices.single().message)
        assertEquals(listOf("a"), notices.single().relatedTaskIds)
    }

    // —— 规则 1：今日截止 ——

    @Test
    fun rule1DueTodayUncompleted() {
        val notices = evaluate(tasks = listOf(
            task("a", due = now.toLocalDate().atTime(23, 59)),
            task("done", due = now.toLocalDate().atTime(9, 0), completed = now), // 已完成不计
        ))
        assertEquals(1, notices.count { it.ruleId == 1 })
        // 任务 a 今天 23:59 截止，同时命中规则 2，合并后两条文案都保留（草案 §3“两条文案合并展示”）。
        assertEquals("今天有 1 项任务截止，记得完成：a；a将于今天 23:59 截止",
            notices.first { it.ruleId == 1 }.message)
    }

    // —— 规则 2：24 小时临期，文案按实际时刻渲染 ——

    @Test
    fun rule2DueWithin24hRendersActualTime() {
        val notices = evaluate(tasks = listOf(
            task("a", due = LocalDateTime.of(2026, 11, 12, 8, 30)), // 距 now(11-11 10:00) 22.5h
        ))
        val notice = notices.first { it.ruleId == 2 }
        assertEquals("a将于明天 08:30 截止", notice.message)
    }

    @Test
    fun rule2ExcludesBeyond24h() {
        val notices = evaluate(tasks = listOf(task("a", due = now.plusHours(25))))
        assertEquals(null, notices.firstOrNull { it.ruleId == 2 })
    }

    // —— 规则 3：48 小时内 ≥ 3 ——

    @Test
    fun rule3TriggersAtThreeTasks() {
        val tomorrow = now.toLocalDate().plusDays(1).atTime(20, 0)
        val tasks = listOf(
            task("a", due = now.plusDays(3)), // 规则外任务，凑规则 8 的“存在截止任务”
            task("b", due = tomorrow),
            task("c", due = tomorrow.plusHours(1)),
            task("d", due = tomorrow.plusHours(2)),
        )
        val notices = evaluate(tasks = tasks)
        val notice = notices.first { it.ruleId == 3 }
        assertEquals("未来两天有 3 项任务，建议按截止时间排序：b、c、d", notice.message)
    }

    @Test
    fun rule3SilentWithTwoTasks() {
        val notices = evaluate(tasks = listOf(
            task("a", due = now.plusHours(1)),
            task("b", due = now.plusHours(2)),
        ))
        assertEquals(null, notices.firstOrNull { it.ruleId == 3 })
    }

    // —— 规则 4：课程负荷（今日节数 ≥ 6，B 提供）——

    @Test
    fun rule4TriggersAtSixPeriods() {
        val notices = evaluate(
            tasks = listOf(task("a", due = now.plusDays(1))), // 有任务，排除规则 7 干扰
            courseStats = CourseStats(todayPeriodCount = 6),
        )
        assertEquals("今天有 6 节课，注意安排休息",
            notices.first { it.ruleId == 4 }.message)
    }

    @Test
    fun rule4SilentAtFivePeriods() {
        val notices = evaluate(
            tasks = listOf(task("a", due = now.plusDays(1))),
            courseStats = CourseStats(todayPeriodCount = 5),
        )
        assertEquals(null, notices.firstOrNull { it.ruleId == 4 })
    }

    // —— 规则 6：本周过半且完成率 < 50% ——

    @Test
    fun rule6TriggersAfterMidweekWithLowCompletion() {
        val notices = evaluate(
            tasks = listOf(task("a", due = now.plusDays(1))),
            completionRate = 0.25f,
        )
        assertEquals("本周已过半，完成率 25%，有些紧张",
            notices.first { it.ruleId == 6 }.message)
    }

    @Test
    fun rule6SilentAtExactlyFiftyPercent() {
        val notices = evaluate(tasks = listOf(task("a", due = now.plusDays(1))), completionRate = 0.5f)
        assertEquals(null, notices.firstOrNull { it.ruleId == 6 })
    }

    @Test
    fun rule6SilentBeforeMidweek() {
        val monday = LocalDateTime.of(2026, 11, 9, 10, 0)
        val notices = evaluate(tasks = listOf(task("a", due = monday.plusDays(1))), completionRate = 0f, now = monday)
        assertEquals(null, notices.firstOrNull { it.ruleId == 6 })
    }

    // —— 规则 7：空数据引导 ——

    @Test
    fun rule7TriggersOnEmptySemesterConfigured() {
        val notices = evaluate()
        assertEquals("暂无课程和任务，可连接学校或手动添加", notices.single().message)
    }

    @Test
    fun rule7SuppressedWhenSemesterNotConfigured() {
        val notices = evaluate(semesterConfigured = false)
        assertTrue(notices.isEmpty()) // 未设学期显示“暂无数据”态，不误导去导入
    }

    @Test
    fun rule7SilentWhenCoursesExistWithoutSchedules() {
        // 有课程、只是还没排课（或课表未同步）：不是“无课程”，不能弹“可连接学校或手动添加”。
        val notices = evaluate(courses = listOf(CourseRecord(id = "c1", title = "数学")))
        assertEquals(null, notices.firstOrNull { it.ruleId == 7 })
    }

    // —— 规则 8：连续 3 天（含今天为第 3 天）无完成记录且存在截止任务 ——

    @Test
    fun rule8TriggersWhenNoCompletionInThreeDays() {
        val notices = evaluate(tasks = listOf(
            task("a", due = now.plusDays(2)),
            // 最近一次完成在 11-08：窗口 [11-09, 11-11]（含今天共 3 天）外 → 命中规则 8。
            task("old", due = now.minusDays(3), completed = now.minusDays(3)),
        ))
        assertEquals(true, notices.any { it.ruleId == 8 })
    }

    @Test
    fun rule8SilentWhenCompletedYesterday() {
        val notices = evaluate(tasks = listOf(
            task("a", due = now.plusDays(2)),
            task("recent", due = now.minusDays(1), completed = now.minusDays(1)), // 昨天有完成
        ))
        assertEquals(null, notices.firstOrNull { it.ruleId == 8 })
    }

    @Test
    fun rule8SilentWhenNoUncompletedDatedTask() {
        // 所有带截止的任务都早已完成、当前没有待办：没有“没完成”可言，不该催用户调整安排。
        val notices = evaluate(tasks = listOf(
            task("old", due = now.minusDays(5), completed = now.minusDays(5)),
        ))
        assertEquals(null, notices.firstOrNull { it.ruleId == 8 })
    }

    // —— 截止待定不参与时间类规则 ——

    @Test
    fun undatedTasksExcludedFromTimeRules() {
        val notices = evaluate(tasks = listOf(task("pending", due = null)))
        // 只有规则 7（空数据里的任务存在性）可能触发；时间类规则不得因待定任务命中。
        assertEquals(null, notices.firstOrNull { it.ruleId == 1 })
        assertEquals(null, notices.firstOrNull { it.ruleId == 2 })
        assertEquals(null, notices.firstOrNull { it.ruleId == 5 })
    }

    // —— 优先级排序与 4 条上限 ——

    @Test
    fun noticesSortedByPriorityAndCappedAtFour() {
        val tomorrow = now.toLocalDate().plusDays(1).atTime(20, 0)
        val tasks = listOf(
            task("overdue", due = now.minusDays(1)),   // 5（昨天截止，不命中规则 1）
            task("t1", due = now.toLocalDate().atTime(23, 59)),  // 1（今天未过，亦在 24h 内）
            task("t2", due = tomorrow),                          // 2（明天，24h 内）
            task("t3", due = tomorrow.plusHours(1)),             // 3（明天，48h 内）
            task("t4", due = tomorrow.plusHours(2)),
        )
        val notices = evaluate(tasks = tasks, courseStats = CourseStats(todayPeriodCount = 8))
        // 命中 5 条规则：5、1、2、3、4 → 合并后只保留 4 条；t1 同时命中 1/2/3，
        // 规则 2、3 都并进含 t1 的条目（文案保留），只保留 4 条不同规则。
        assertEquals(listOf(5, 1, 4, 8), notices.map { it.ruleId })
        // 规则 3 被并入后，“未来两天有 4 项任务”这句话必须在，否则这 4 项任务在提醒区看不见。
        assertTrue(
            notices.first { it.ruleId == 1 }.message
                .contains("未来两天有 4 项任务，建议按截止时间排序"))
    }

    // —— 同任务多条命中合并 ——

    @Test
    fun sameTaskHitsMergedIntoOneEntry() {
        // 任务 a：今天 23:59 截止 → 命中规则 1（今日截止）和规则 2（24h 内），合并为一条。
        // 存在截止任务也会触发规则 8（无完成记录），属于不同任务的独立规则，照常出现。
        val notices = evaluate(tasks = listOf(
            task("a", due = now.toLocalDate().atTime(23, 59)),
        ))
        assertEquals(listOf(1, 8), notices.map { it.ruleId }) // 规则 2 合并进规则 1，只出一条
        // 两条文案都要保留（草案 §3“两条文案合并展示”），否则规则 2 的具体时刻永远看不到。
        assertEquals("今天有 1 项任务截止，记得完成：a；a将于今天 23:59 截止",
            notices.first { it.ruleId == 1 }.message)
        assertEquals(listOf("a"), notices.first { it.ruleId == 1 }.relatedTaskIds)
    }

    @Test
    fun mergedEntryKeepsTasksOnlyCoveredByOtherRules() {
        // X 昨天截止、Y 今天 09:00 截止（已过期）、Z 今天 23:59 截止。
        // 规则 5 命中 {X, Y}、规则 1 命中 {Y, Z}，共享 Y 会合并；Z 只出现在规则 1 里，
        // 若合并时丢弃被并入的文案，今晚截止的 Z 就会从提醒区彻底消失。
        val notices = evaluate(tasks = listOf(
            task("X", due = now.minusDays(1)),
            task("Y", due = now.toLocalDate().atTime(9, 0)),
            task("Z", due = now.toLocalDate().atTime(23, 59)),
        ))
        assertEquals(listOf(5, 8), notices.map { it.ruleId })
        val message = notices.first { it.ruleId == 5 }.message
        assertTrue("被并入的文案要保留：实际文案 $message", message.contains("Z将于今天 23:59 截止"))
        assertTrue("规则 1 的条数也要保留：实际文案 $message", message.contains("今天有 2 项任务截止"))
        assertEquals(listOf("X", "Y", "Z"), notices.first { it.ruleId == 5 }.relatedTaskIds)
    }

    @Test
    fun overdueTodayTaskMergesRule5AndRule1() {
        // 已逾期且今天截止的任务（今天 09:00 截止、now 为 10:00）同时命中规则 5 与规则 1，
        // 按“同任务合并”只出现一条，任务与两条文案都归入主体条目。
        val notices = evaluate(tasks = listOf(
            task("a", due = now.toLocalDate().atTime(9, 0)),
            task("b", due = now.plusDays(1)),
        ))
        assertEquals(5, notices.first().ruleId)
        assertEquals(listOf("a"), notices.first { it.ruleId == 5 }.relatedTaskIds)
        assertEquals(null, notices.firstOrNull { it.ruleId == 1 }) // 规则 1 被合并进规则 5
    }

    // —— 单条任务名上限：3 个任务名 + “等 N 项” ——

    @Test
    fun rule5NamesTruncatedWithRemainder() {
        val overdue = (1..7).map { task("t$it", due = now.minusDays(it.toLong())) }
        val notices = evaluate(tasks = overdue)
        // 任务名按截止时间先后排列：逾期最久的排最前。
        assertEquals("有 7 项任务已逾期，可补录或调整：t7、t6、t5 等 7 项",
            notices.first { it.ruleId == 5 }.message)
    }
}
