package com.coursekeeper.data

import com.coursekeeper.data.model.*
import com.coursekeeper.data.sync.*
import com.coursekeeper.fakedata.BoundaryFixtures
import com.coursekeeper.statistics.CourseStats
import com.coursekeeper.statistics.IntervalCourseStatsAdapter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CourseScheduleV3Test : DataTestBase() {
    private fun schedule(weeks: Long = 7424L, teacher: String? = null) =
        ScheduleDraft(2, 1, 2, weeks = weeks, teacher = teacher)

    @Test fun nonConsecutiveWeeksAndTeacherOverridesRoundTripWithoutIdCollisions() = runTest {
        repository.createCourse(CourseDraft("实测课程", teacher = "默认教师", schedules = listOf(
            schedule(teacher = "教师甲"), schedule(teacher = "教师乙"), schedule(weeks = 1L shl 9),
        )))
        val records = repository.snapshot().schedules
        assertEquals(3, records.map { it.id }.distinct().size)
        assertEquals(setOf("教师甲", "教师乙", null), records.map { it.teacher }.toSet())
        assertEquals(2, records.count { it.weeksText == "9,11-13" })
        assertEquals(1, records.count { it.weeksText == "10" })
        assertEquals("默认教师", repository.snapshot().courses.single().teacher)
    }

    @Test fun aggregateEditKeepsUnchangedEntryIdsAndIncrementsCourseVersionOnce() = runTest {
        val first = schedule(teacher = "教师甲")
        val second = schedule(teacher = "教师乙")
        val receipt = repository.createCourse(CourseDraft("课程", schedules = listOf(first, second)))
        val before = repository.snapshot().schedules.associateBy { it.teacher }
        repository.updateCourse(receipt.id, 1, CourseDraft("课程", schedules = listOf(second, first.copy(weeks = 1L, weeksText = "1"))))
        val after = repository.snapshot()
        assertEquals(2L, after.courses.single().version)
        assertEquals(before.getValue("教师乙").id, after.schedules.single { it.teacher == "教师乙" }.id)
        assertNotEquals(before.getValue("教师甲").id, after.schedules.single { it.teacher == "教师甲" }.id)
        assertTrue(after.courses.single().manualDirty)
    }

    @Test fun inconsistentWeekTextEmptyWeeksAndSemesterOverflowFailAtomically() = runTest {
        repository.saveSemester(SemesterRecord("term", "学期", null, 20), emptyList(), null)
        for (entry in listOf(schedule().copy(weeksText = "9-13"), schedule(weeks = 0), schedule(weeks = Long.MIN_VALUE))) {
            try {
                repository.createCourse(CourseDraft("非法排课", semesterId = "term", schedules = listOf(entry)))
                fail("Expected rejected schedule")
            } catch (error: DataException) { assertEquals(DataError.INVALID_RECORD, error.code) }
        }
        assertTrue(repository.snapshot().courses.isEmpty())
        assertTrue(repository.snapshot().schedules.isEmpty())
        // Week 64 is valid when no narrower semester has been selected.
        repository.createCourse(CourseDraft("第64周", schedules = listOf(schedule(weeks = Long.MIN_VALUE))))
        assertEquals("64", repository.snapshot().schedules.single().weeksText)
    }

    @Test fun syncDetectsTeacherAndWeekChangesButIgnoresResponseOrder() = runTest {
        val first = schedule(teacher = "教师甲")
        val second = schedule(weeks = 1L shl 9)
        val adapter = FakeAdapter().apply {
            courseResult = ModuleResult.Success(listOf(ImportedCourse("CST4823A", CourseDraft("课程", schedules = listOf(first, second)))))
        }
        val sync = coordinator(adapter)
        sync.synchronize()
        val before = repository.snapshot()
        adapter.courseResult = ModuleResult.Success(listOf(ImportedCourse("CST4823A", CourseDraft("课程", schedules = listOf(second, first)))))
        sync.synchronize(true)
        assertEquals(before.courses, repository.snapshot().courses)
        assertEquals(before.schedules, repository.snapshot().schedules)
        val changed = first.copy(teacher = "新教师")
        adapter.courseResult = ModuleResult.Success(listOf(ImportedCourse("CST4823A", CourseDraft("课程", schedules = listOf(second, changed)))))
        sync.synchronize(true)
        assertEquals(2L, repository.snapshot().courses.single().version)
        assertEquals(before.schedules.single { it.weeksText == "10" }.id,
            repository.snapshot().schedules.single { it.weeksText == "10" }.id)
        assertEquals("新教师", repository.snapshot().schedules.single { it.weeks == 7424L }.teacher)
        val rescheduled = CourseDraft("课程", schedules = listOf(second, changed.copy(weeks = 1L, weeksText = "1")))
        adapter.courseResult = ModuleResult.Success(listOf(ImportedCourse("CST4823A", rescheduled)))
        sync.synchronize(true)
        assertEquals(3L, repository.snapshot().courses.single().version)
    }

    @Test fun invalidSecondCourseRollsBackTheEntireImportedModule() = runTest {
        val adapter = FakeAdapter().apply {
            courseResult = ModuleResult.Success(listOf(
                ImportedCourse("ok", CourseDraft("合法课程", schedules = listOf(schedule()))),
                ImportedCourse("bad", CourseDraft("非法课程", schedules = listOf(schedule().copy(weeksText = "错误周次")))),
            ))
        }
        val result = coordinator(adapter).synchronize() as SyncRunResult.Finished
        assertEquals(SyncStatus.PARTIAL, result.summary.status)
        assertTrue(repository.snapshot().courses.isEmpty())
        assertTrue(repository.snapshot().schedules.isEmpty())
    }

    @Test fun statisticsAdapterDoesNotFillNonConsecutiveWeekGaps() {
        val source = IntervalCourseStatsAdapter()
        val fixture = BoundaryFixtures.nonConsecutiveWeeks()
        assertEquals(CourseStats(), source.summary(fixture, BoundaryFixtures.NOW)) // week 10: absent
        assertEquals(CourseStats(1, 2, 1, 1), source.summary(fixture, BoundaryFixtures.NOW.plusWeeks(1))) // week 11
        assertEquals(CourseStats(), source.summary(fixture, BoundaryFixtures.NOW.plusWeeks(4))) // week 14
    }
}
