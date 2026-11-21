package com.coursekeeper.statistics

import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.TaskRecord
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * 看板规则引擎（成员 F，T07，纯逻辑，无 Android 依赖）。
 *
 * 口径依据《统计口径与规则草案 v0.3》第三节：8 条规则全部离线、确定性计算，不调用模型。
 * - 时间类规则（1/2/3/5/8）不处理截止待定任务（dueAt == null，D v1.0 六-F③）；
 * - 优先级排序：5 逾期 > 1/2 截止类 > 6 滞后 > 4 负荷 > 3 集中 > 7 引导 > 8 低完成；
 * - 单条提示最多列 3 个任务名，超出合并为“等 N 项”；
 * - 看板最多同时展示 4 条不同规则的提示，超出按优先级取前 4；
 * - 学期未设置（[semesterConfigured] = false）时视为无课程但不触发规则 7。
 *
 * 同任务多条命中（例：任务既命中规则 1 又命中规则 2）合并为一条看板条目：
 * 以较高优先级（priority 最小）的文案为主体，其余条目的文案按“；”接在后面（草案 §3
 * “两条文案合并展示”），任务并入 relatedTaskIds。**任何一条命中都不能整条丢弃**，
 * 否则它独有的任务名与条数会从提醒区消失。
 */
object RuleEngine {

    /** 看板规则区最多同时展示的规则条数（草案 §3 执行细节）。 */
    private const val MAX_NOTICES = 4

    /** 单条提示最多列出的任务名个数，超出部分合并为“等 N 项”。 */
    private const val MAX_NAMES = 3

    fun evaluate(
        snapshot: DataSnapshot,
        courseStats: CourseStats,
        now: LocalDateTime,
        completionRate: Float?,
        semesterConfigured: Boolean,
    ): List<RuleNotice> {
        val tasks = snapshot.tasks
        val today = now.toLocalDate()
        // 日期归属：设备本地时区、00:00 日界（与 D v1.0 一致）。
        val dated = tasks.filter { it.dueAt != null }
        val dueToday = dated.filter { it.completedAt == null && it.dueAt?.toLocalDate() == today }
        val due24h = dated.filter { it.completedAt == null &&
            it.dueAt?.let { due -> !due.isBefore(now) && !due.isAfter(now.plusHours(24)) } == true }
        val due48h = dated.filter { it.completedAt == null &&
            it.dueAt?.let { due -> !due.isBefore(now) && !due.isAfter(now.plusHours(48)) } == true }
        val overdue = dated.filter { it.completedAt == null && it.dueAt?.isBefore(now) == true }

        val candidates = buildList {
            // 规则 5：逾期（最高优先级）
            if (overdue.isNotEmpty()) add(notice(5, "有 ${overdue.size} 项任务已逾期，可补录或调整",
                renderNames(overdue, overdue.size), overdue.map { it.id }))
            // 规则 1：今日截止
            if (dueToday.isNotEmpty()) add(notice(1, "今天有 ${dueToday.size} 项任务截止，记得完成",
                renderNames(dueToday, dueToday.size), dueToday.map { it.id }))
            // 规则 2：24 小时临期（文案按实际截止时刻渲染）
            if (due24h.isNotEmpty()) add(notice(2, renderDueSoon(due24h, now), "", due24h.map { it.id }))
            // 规则 6：进度滞后（本周过半且完成率 < 50%；完成率复用统计口径，分母不含截止待定）
            if (pastMidweek(today) && completionRate != null && completionRate < 0.5f) {
                val percent = (completionRate * 100).toInt()
                add(notice(6, "本周已过半，完成率 $percent%，有些紧张", "", emptyList()))
            }
            // 规则 4：课程负荷（今日节数，B 提供）
            if (courseStats.todayPeriodCount >= 6) add(notice(4,
                "今天有 ${courseStats.todayPeriodCount} 节课，注意安排休息", "", emptyList()))
            // 规则 3：任务集中（未来 48 小时 ≥ 3）
            if (due48h.size >= 3) add(notice(3, "未来两天有 ${due48h.size} 项任务，建议按截止时间排序",
                renderNames(due48h, due48h.size), due48h.map { it.id }))
            // 规则 7：空数据引导（学期未设置时不触发，避免误导用户去导入）。
            // “无课程”按课程表判定，不看排课条目：只建了课程、还没排课的用户不该被劝去重新导入。
            if (semesterConfigured && snapshot.courses.isEmpty() && tasks.isEmpty()) add(notice(7,
                "暂无课程和任务，可连接学校或手动添加", "", emptyList()))
            // 规则 8：连续低完成（含今天共 3 天每天完成数均为 0）。门槛是“存在未完成的截止任务”：
            // 全部截止任务都早已完成、当前没有待办的用户不该被催“调整安排”。
            if (tasks.any { it.dueAt != null && it.completedAt == null } && tasks.none { task ->
                task.completedAt?.toLocalDate()?.let { it in today.minusDays(2)..today } == true }) {
                add(notice(8, "连续几天没有完成记录，需要调整安排吗？", "", emptyList()))
            }
        }

        val merged = mergeByTask(candidates)
        return merged.sortedWith(compareBy({ it.priority }, { it.ruleId })).take(MAX_NOTICES)
    }

    /** 按规则号组装提示：names 非空时追加任务名（含“等 N 项”截断）。 */
    private fun notice(ruleId: Int, message: String, names: String, related: List<String>) =
        RuleNotice(ruleId, priorityOf(ruleId), if (names.isEmpty()) message else "$message：$names",
            related)

    private fun priorityOf(ruleId: Int): Int = when (ruleId) {
        5 -> 0
        1, 2 -> 1
        6 -> 2
        4 -> 3
        3 -> 4
        7 -> 5
        else -> 6 // 8
    }

    /**
     * 同任务多条命中合并：共享任务的条目合成一条看板条目，任务只出现一次。
     * 主体是优先级最高（priority 最小）的一条，其余条目的**文案一并拼接**（草案 §3
     * “两条文案合并展示”），任务并入 relatedTaskIds——被并入的条目若整条丢弃，
     * 它独有的任务名与条数就会从提醒区消失（例：规则 1 吞掉规则 3，用户看不到未来两天还有几项）。
     */
    private fun mergeByTask(candidates: List<RuleNotice>): List<RuleNotice> {
        val sorted = candidates.sortedWith(compareBy({ it.priority }, { it.ruleId }))
        val kept = mutableListOf<RuleNotice>()
        for (candidate in sorted) {
            val hostIndex = kept.indexOfFirst { existing ->
                existing.relatedTaskIds.any { it in candidate.relatedTaskIds } }
            if (hostIndex < 0) {
                kept.add(candidate)
            } else {
                // 与已保留条目共享任务：并入该条目，两条文案都保留，避免信息整条消失。
                val host = kept[hostIndex]
                kept[hostIndex] = host.copy(
                    message = if (host.message == candidate.message) host.message
                    else "${host.message}；${candidate.message}",
                    relatedTaskIds = (host.relatedTaskIds + candidate.relatedTaskIds).distinct(),
                )
            }
        }
        return kept
    }

    /** 单条提示最多 3 个任务名，按截止时间先后排列，超出合并为“等 N 项”（草案 §3 执行细节）。 */
    private fun renderNames(tasks: List<TaskRecord>, total: Int): String {
        val names = tasks.sortedWith(compareBy({ it.dueAt }, { it.id }))
            .take(MAX_NAMES).joinToString("、") { it.title }
        return if (total <= MAX_NAMES) names else "$names 等 $total 项"
    }

    /** 规则 2 文案：模板为“{任务名}将于今天 23:59 截止”；多条时列出任务名与各自时刻。 */
    private fun renderDueSoon(tasks: List<TaskRecord>, now: LocalDateTime): String {
        val listed = tasks.sortedWith(compareBy({ it.dueAt }, { it.id }))
            .take(MAX_NAMES)
            .mapNotNull { task -> task.dueAt?.let { due -> renderDueAt(task.title, due, now) } }
            .joinToString("；")
        return if (tasks.size > MAX_NAMES) "$listed 等 ${tasks.size} 项" else listed
    }

    private fun renderDueAt(title: String, due: LocalDateTime, now: LocalDateTime): String {
        // 固定用 Locale.ROOT：默认区域设置会把数字换成本地字形（如阿拉伯语的 ٢٣:٥٩）。
        val time = String.format(Locale.ROOT, "%02d:%02d", due.hour, due.minute)
        return "${title}将于${if (due.toLocalDate() == now.toLocalDate()) "今天" else "明天"} $time 截止"
    }

    /** 本周过半：周三及以后（含周三）视为过半。 */
    private fun pastMidweek(today: LocalDate): Boolean =
        !today.isBefore(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(2))
}
