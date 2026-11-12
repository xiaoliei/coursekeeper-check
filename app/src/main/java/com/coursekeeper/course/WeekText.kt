package com.coursekeeper.course

import com.coursekeeper.data.model.ScheduleWeeks

/**
 * 周次文本 ↔ 位图的解析层（成员 B）。
 *
 * 《课程与周次规则 v0.1》第三节：周次用集合表示，存储为位图、展示为规范化文本。
 * 位图的格式化与包含判断由 E 的 [ScheduleWeeks] 负责（v3 已交付），这里**只补文本解析**，
 * 不复制格式逻辑，避免两套口径。
 */
object WeekText {

    /** 解析结果：位图 + 规范化文本 + 字段级警告（警告不影响整条排课是否有效）。 */
    data class Parsed(
        val weeks: Long,
        val text: String,
        val warnings: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = weeks == 0L
    }

    fun format(weeks: Long): String = ScheduleWeeks.format(weeks)

    fun contains(weeks: Long, week: Int): Boolean = ScheduleWeeks.contains(weeks, week)

    /**
     * 解析实测导出里的各种写法：`1-16`、`9,11-13`、`4-7,8-16`、`1-16(周)`、`1-16(单)`、`1~16`。
     *
     * 规则（v0.1 第三节）：
     * - 单个分段非法只丢弃该段并记警告，**整条不算失败**；
     * - 超出 1—64 或超出 [weekCount] 的周次丢弃；
     * - 单双周是位图子集，解析成对应集合，不保留枚举。
     */
    fun parse(raw: String?, weekCount: Int = ScheduleWeeks.MAX_WEEKS): Parsed {
        val source = raw.orEmpty()
        if (source.isBlank()) return Parsed(0L, "", listOf("周次为空"))

        val warnings = mutableListOf<String>()
        var normalized = source
            .replace('（', '(').replace('）', ')')
            .replace('，', ',').replace('、', ',').replace('；', ',').replace(';', ',')
            .replace("—", "-").replace("–", "-").replace("~", "-")
            .replace(" ", "").replace("\t", "")
            .replace("第", "")
            .lowercase()

        val parity = when {
            normalized.contains("单") -> 1
            normalized.contains("双") -> 0
            else -> -1
        }
        normalized = normalized
            .replace("单周", "").replace("双周", "")
            .replace("单", "").replace("双", "")
            .replace("周", "")
            .replace("(", "").replace(")", "")

        val limit = weekCount.coerceIn(1, ScheduleWeeks.MAX_WEEKS)
        var weeks = 0L
        for (token in normalized.split(',')) {
            if (token.isBlank()) continue
            val bounds = token.split('-')
            if (bounds.size > 2) {
                warnings += "无法识别的周次段：$token"
                continue
            }
            val from = bounds[0].toIntOrNull()
            val to = if (bounds.size == 2) bounds[1].toIntOrNull() else from
            if (from == null || to == null) {
                warnings += "无法识别的周次段：$token"
                continue
            }
            val lo = minOf(from, to)
            val hi = maxOf(from, to)
            for (week in lo..hi) {
                if (week < 1 || week > ScheduleWeeks.MAX_WEEKS) {
                    warnings += "第 $week 周超出 1—64 范围，已忽略"
                    continue
                }
                if (week > limit) {
                    warnings += "第 $week 周超出本学期 $limit 周，已忽略"
                    continue
                }
                if (parity == 1 && week % 2 == 0) continue
                if (parity == 0 && week % 2 == 1) continue
                weeks = weeks or (1L shl (week - 1))
            }
        }
        return Parsed(weeks, ScheduleWeeks.format(weeks), warnings)
    }

    /** 供测试与界面展示：确保位图与文本始终同源。 */
    fun normalize(weeks: Long): String = ScheduleWeeks.format(weeks)
}
