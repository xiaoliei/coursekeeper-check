package com.coursekeeper.data.model

/** Storage encoding shared by E's migration and B/C's structured inputs. No date guessing/parsing. */
object ScheduleWeeks {
    const val MAX_WEEKS = 64

    fun contains(weeks: Long, week: Int): Boolean =
        week in 1..MAX_WEEKS && weeks and (1L shl (week - 1)) != 0L

    /** Includes the signed Long's highest bit: Long.MIN_VALUE means week 64, not invalid data. */
    fun format(weeks: Long): String {
        val ranges = mutableListOf<String>()
        var week = 1
        while (week <= MAX_WEEKS) {
            if (!contains(weeks, week)) { week++; continue }
            val start = week
            while (week < MAX_WEEKS && contains(weeks, week + 1)) week++
            ranges += if (start == week) "$start" else "$start-$week"
            week++
        }
        return ranges.joinToString(",")
    }

    fun fits(weeks: Long, weekCount: Int): Boolean =
        weekCount in 1..MAX_WEEKS && (weekCount == MAX_WEEKS || weeks ushr weekCount == 0L)

    /** Compatibility conversion only; v3 stores the resulting bitmap, never a parity enum. */
    fun fromRange(startWeek: Int, endWeek: Int, pattern: WeekPattern = WeekPattern.ALL): Long {
        require(startWeek in 1..MAX_WEEKS && endWeek in startWeek..MAX_WEEKS) {
            "排课周次须在 1—64 周内；旧数据保持原库，请核对后再升级"
        }
        var weeks = 0L
        for (week in startWeek..endWeek) {
            if (pattern == WeekPattern.ALL || pattern == WeekPattern.ODD && week % 2 == 1 ||
                pattern == WeekPattern.EVEN && week % 2 == 0) weeks = weeks or (1L shl (week - 1))
        }
        return weeks
    }
}
