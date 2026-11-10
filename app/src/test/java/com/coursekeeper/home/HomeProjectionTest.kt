package com.coursekeeper.home

import com.coursekeeper.data.model.*
import com.coursekeeper.data.settings.AppSettings
import com.coursekeeper.task.TaskDateFilter
import com.coursekeeper.task.TaskStatusFilter
import com.coursekeeper.task.toView
import com.coursekeeper.task.visible
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class HomeProjectionTest {
    private val now = LocalDateTime.of(2026, 11, 10, 10, 0)
    private fun task(id: String, due: LocalDateTime? = null, completed: LocalDateTime? = null) =
        TaskRecord(id, id, TaskType.HOMEWORK, dueAt = due, completedAt = completed, version = 7)
    private fun fixtures() = listOf(
        task("待定"),
        task("今日待办", now.toLocalDate().atTime(23, 59)),
        task("今日已完成", now.toLocalDate().atTime(20, 0), now.minusHours(1)),
        task("明日截止今日完成", now.plusDays(1), now.minusHours(2)),
        task("上周", LocalDateTime.of(2026,11,8,20,0), LocalDateTime.of(2026,11,8,19,0)),
        task("下周", LocalDateTime.of(2026,11,16,0,0)),
    )
    @Test fun dailyCountsMatchDateFiltersIncludingCompletedAndUndatedBoundaries() {
        val records = fixtures()
        val home = projectHome(DataSnapshot(tasks = records), AppSettings(onboardingComplete = true), now)
        val rows = records.map { it.toView(emptyList(), now) }
        assertEquals(2, home.todayDueCount)
        assertEquals(2, home.todayCompletedCount)
        assertEquals(home.todayDueCount, rows.visible(TaskStatusFilter.ALL, null, TaskDateFilter.DUE_TODAY, now).size)
        assertEquals(home.todayCompletedCount, rows.visible(TaskStatusFilter.DONE, null, TaskDateFilter.COMPLETED_TODAY, now).size)
        assertEquals("待定", home.tasks.first().id)
        assertEquals("截止待定", home.tasks.first().dueLabel)
        assertTrue(home.tasks.all { it.version == 7L })
    }
    @Test fun weekStartsOnMondayAndExcludesUndatedPreviousAndNextWeek() {
        val home = projectHome(DataSnapshot(tasks = fixtures()), AppSettings(), now)
        assertEquals(3, home.weekTaskCount)
        assertEquals(2, home.weekCompletedCount)
        assertEquals(2f/3f, home.weekProgress, 0.0001f)
    }
    @Test fun dayRolloverChangesCountersAndLabelsWithoutChangingRecords() {
        val records = listOf(task("明天", now.plusDays(1)))
        assertEquals(0, projectHome(DataSnapshot(tasks=records),AppSettings(),now).todayDueCount)
        val next = projectHome(DataSnapshot(tasks=records),AppSettings(),now.plusDays(1))
        assertEquals(1,next.todayDueCount)
        assertTrue(next.tasks.single().dueLabel.startsWith("今天"))
    }
    @Test fun unavailableCoursesAndPressureNeverBorrowFixtureValues() {
        val empty = projectHome(DataSnapshot(),AppSettings(),now)
        assertFalse(empty.isDemo)
        assertEquals(CourseAvailability.NoSemester,empty.courseAvailability)
        assertEquals("待设置学期",empty.weekLabel)
        assertNull(empty.nextCourse)
        assertTrue(empty.tasks.isEmpty())
        assertEquals("暂无压力数据",empty.pressureLabel)
        val configured = projectHome(DataSnapshot(semesters=listOf(SemesterRecord("s","秋季",LocalDate.of(2026,9,7),20))),AppSettings(),now)
        assertEquals(CourseAvailability.Unavailable,configured.courseAvailability)
        assertNull(configured.nextCourse)
    }
    @Test fun syncUsesModuleSummaryAndDoesNotClaimSuccessWithoutTimestamp() {
        val empty = projectHome(DataSnapshot(),AppSettings(),now)
        assertEquals("尚未成功同步",empty.lastSyncLabel)
        val records = listOf(
            SyncRecord(SyncModule.COURSES,status=com.coursekeeper.data.model.SyncStatus.IDLE,lastSuccessAt=Instant.parse("2026-11-10T01:00:00Z")),
            SyncRecord(SyncModule.TASKS,status=com.coursekeeper.data.model.SyncStatus.ERROR),
        )
        val partial = projectHome(DataSnapshot(syncRecords=records),AppSettings(),now)
        assertEquals(com.coursekeeper.home.SyncStatus.Partial,partial.status)
        assertTrue(partial.lastSyncLabel.contains("作业 尚未成功"))
    }
    @Test fun deletedCourseAssociationRemainsVisibleAsDeleted() {
        val row = task("关联").copy(courseId="deleted")
        val home = projectHome(DataSnapshot(tasks=listOf(row)),AppSettings(),now)
        assertEquals("课程已删除",home.tasks.single().course)
    }
}
