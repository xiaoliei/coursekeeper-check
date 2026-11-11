package com.coursekeeper.statistics

import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.TaskRecord
import com.coursekeeper.data.model.TaskType
import com.coursekeeper.task.label
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/**
 * 统计服务（成员 F，纯逻辑，无 Android 依赖）。
 *
 * 口径依据《统计口径与规则草案 v0.2》：
 * - 周窗口统一为 [本周一 00:00, 下周一 00:00)，按本地日期比较，与 A 首页投影完全一致；
 * - 任务侧来自 E 的一致性快照；课程侧四个数字由调用方传入（B 的 CourseService 提供），F 不重算；
 * - 压力指数权重 任务密度40% + 课程负荷30% + 临近截止20% + 逾期10%，等级 0-29/30-59/60-79/80-100。
 * 重复作业留到 W3：8 条规则不在此实现。
 */
object StatisticsService {

    fun compute(snapshot: DataSnapshot, courseStats: CourseStats, now: LocalDateTime): StatisticsUi {
        val today = now.toLocalDate()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val nextMonday = monday.plusWeeks(1)
        val inWeek: (TaskRecord) -> Boolean = { task ->
            task.dueAt?.toLocalDate()?.let { !it.isBefore(monday) && it.isBefore(nextMonday) } == true
        }

        val weekTasks = snapshot.tasks.filter(inWeek)
        val weekCompleted = weekTasks.count { it.completedAt != null }

        val typeDistribution = TaskType.entries.mapNotNull { type ->
            val count = weekTasks.count { it.type == type }
            if (count > 0) TypeSlice(type, type.label, count) else null
        }

        val dueDist = dueDistribution(snapshot.tasks, now, monday, nextMonday)
        val pressure = pressure(snapshot.tasks, weekTasks, courseStats, now, today, monday, nextMonday)

        return StatisticsUi(
            weekTaskCount = weekTasks.size,
            weekCompletedCount = weekCompleted,
            completionRate = if (weekTasks.isEmpty()) null else weekCompleted.toFloat() / weekTasks.size,
            typeDistribution = typeDistribution,
            dueDist = dueDist,
            pressureScore = pressure?.first,
            pressureLevel = if (pressure != null) level(pressure.first) else "",
            explanation = pressure?.second ?: "暂无数据",
        )
    }

    /** 截止分布：互斥分区，均含已完成、均不含截止待定（草案 §1）。 */
    private fun dueDistribution(
        tasks: List<TaskRecord>,
        now: LocalDateTime,
        monday: java.time.LocalDate,
        nextMonday: java.time.LocalDate,
    ): DueDistribution {
        var overdue = 0
        var thisWeek = 0
        var next7Days = 0
        tasks.forEach { task ->
            val due = task.dueAt ?: return@forEach
            when {
                due.isBefore(now) -> overdue++
                due.toLocalDate().let { !it.isBefore(monday) && it.isBefore(nextMonday) } -> thisWeek++
                due.toLocalDate().let { !it.isBefore(nextMonday) && it.isBefore(nextMonday.plusWeeks(1)) } -> next7Days++
            }
        }
        return DueDistribution(overdue, thisWeek, next7Days)
    }

    /**
     * 压力指数。返回 (score, explanation)；四项基准计数全为 0 时返回 null（显示“暂无数据”）。
     * explanation 由最高贡献因子生成固定短句，多个最高因子用“；”拼接。
     */
    private fun pressure(
        tasks: List<TaskRecord>,
        weekTasks: List<TaskRecord>,
        courseStats: CourseStats,
        now: LocalDateTime,
        today: java.time.LocalDate,
        monday: java.time.LocalDate,
        nextMonday: java.time.LocalDate,
    ): Pair<Int, String>? {
        // 剩余可用天数：从今天到下周一一共还有几天（按计划用 today，而非整周宽度）。
        val remainingDays = ChronoUnit.DAYS.between(today, nextMonday).toDouble()
        val density = if (weekTasks.isEmpty()) 0.0 else (weekTasks.size / remainingDays).coerceAtMost(1.0)
        val load = (courseStats.todayPeriodCount / 8.0).coerceAtMost(1.0)
        val nearDueCount = tasks.count { it.completedAt == null && it.dueAt != null &&
            !it.dueAt.isBefore(now) && !it.dueAt.isAfter(now.plusHours(48)) }
        val overdueCount = tasks.count { it.dueAt != null && it.dueAt.isBefore(now) && it.completedAt == null }
        val near = (nearDueCount / 3.0).coerceAtMost(1.0)
        val overdue = (overdueCount / 2.0).coerceAtMost(1.0)

        if (weekTasks.isEmpty() && courseStats.todayPeriodCount == 0 && nearDueCount == 0 && overdueCount == 0) {
            return null
        }

        val score = (density * 40 + load * 30 + near * 20 + overdue * 10).roundToInt()
        val max = maxOf(density, load, near, overdue)
        val phrases = buildList {
            if (density == max) add("本周任务较集中")
            if (load == max) add("今日课程较多")
            if (near == max) add("有较早的截止任务")
            if (overdue == max) add("存在逾期任务")
        }
        return score to phrases.joinToString("；")
    }

    private fun level(score: Int): String = when {
        score in 0..29 -> "轻松"
        score in 30..59 -> "适中"
        score in 60..79 -> "偏高"
        else -> "很高"
    }
}
