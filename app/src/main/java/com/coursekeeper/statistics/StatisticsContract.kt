package com.coursekeeper.statistics

import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.TaskType
import java.time.LocalDateTime

/**
 * 统计服务契约（成员 F）。
 *
 * 口径依据《统计口径与规则草案 v0.2》：首页与看板共用同一套统计，F 定“算什么”，
 * 任务数据来自 E（DataSnapshot/Repository），课程侧四个数字由 B 的 CourseService 提供、
 * F 只消费不重算。下列类型均为草案 §1/§4 的落地表达，随 v0.2 生效。
 */
data class CourseStats(
    val todayLessonCount: Int = 0,
    val todayPeriodCount: Int = 0,
    val weekCourseCount: Int = 0,
    val weekEntryCount: Int = 0,
)

/** 课程统计来源。由 B 的 CourseService 实现；当前用临时适配器填充（见 IntervalCourseStatsAdapter）。 */
interface CourseStatsSource {
    fun summary(snapshot: DataSnapshot, now: LocalDateTime): CourseStats
}

/** 本周任务按类型分布的一项。label 与 D 的类型文案一致（TaskType.label）。 */
data class TypeSlice(
    val type: TaskType,
    val label: String,
    val count: Int,
)

/** 截止分布（互斥分区，均含已完成、均不含截止待定）。 */
data class DueDistribution(
    val overdue: Int = 0,
    val thisWeek: Int = 0,
    val next7Days: Int = 0,
)

/** 首页与看板共用统计结果（草案 §4）。 */
data class StatisticsUi(
    val weekTaskCount: Int = 0,
    val weekCompletedCount: Int = 0,
    val completionRate: Float? = null,
    val typeDistribution: List<TypeSlice> = emptyList(),
    val dueDist: DueDistribution = DueDistribution(),
    val pressureScore: Int? = null,
    val pressureLevel: String = "",
    val explanation: String = "",
    val error: String? = null,
)
