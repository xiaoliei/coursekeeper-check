package com.coursekeeper.data

import com.coursekeeper.data.model.ScheduleWeeks
import com.coursekeeper.data.model.WeekPattern
import org.junit.Assert.*
import org.junit.Test

class ScheduleWeeksTest {
    @Test fun nonConsecutiveWeeksKeepTheirGapAndCanonicalText() {
        val weeks = 7424L
        assertEquals(listOf(9, 11, 12, 13), (1..64).filter { ScheduleWeeks.contains(weeks, it) })
        assertEquals("9,11-13", ScheduleWeeks.format(weeks))
        assertFalse(ScheduleWeeks.contains(weeks, 10))
    }

    @Test fun highestSignedBitAndAll64WeeksRemainValid() {
        assertEquals(Long.MIN_VALUE, ScheduleWeeks.fromRange(64, 64))
        assertEquals("64", ScheduleWeeks.format(Long.MIN_VALUE))
        assertEquals(-1L, ScheduleWeeks.fromRange(1, 64))
        assertEquals("1-64", ScheduleWeeks.format(-1L))
        assertTrue(ScheduleWeeks.fits(Long.MIN_VALUE, 64))
        assertFalse(ScheduleWeeks.fits(Long.MIN_VALUE, 63))
        assertFalse(ScheduleWeeks.contains(-1L, 0))
        assertFalse(ScheduleWeeks.contains(-1L, 65))
    }

    @Test fun oldParityPreservesTheActualTeachingWeeks() {
        assertEquals("1,3,5,7,9,11,13,15", ScheduleWeeks.format(ScheduleWeeks.fromRange(1, 16, WeekPattern.ODD)))
        assertEquals("2,4,6,8,10,12,14,16", ScheduleWeeks.format(ScheduleWeeks.fromRange(1, 16, WeekPattern.EVEN)))
        // An old empty odd/even intersection must not invent a teaching week during migration.
        assertEquals(0L, ScheduleWeeks.fromRange(2, 2, WeekPattern.ODD))
        assertEquals("", ScheduleWeeks.format(0L))
    }

    @Test fun invalidLegacyRangesAreRejectedInsteadOfTruncated() {
        for ((start, end) in listOf(0 to 16, 1 to 65, 9 to 8)) {
            try { ScheduleWeeks.fromRange(start, end); fail("Expected invalid range $start-$end") }
            catch (_: IllegalArgumentException) { }
        }
        assertFalse(ScheduleWeeks.fits(1L, 0))
        assertFalse(ScheduleWeeks.fits(1L, 65))
    }
}
