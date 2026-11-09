package com.coursekeeper.data

import com.coursekeeper.data.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RepositoryTest : DataTestBase() {
    @Test fun manualTasksUseUuidNullableDatesAndAllFiveTypes() = runTest {
        TaskType.entries.forEach { repository.createTask(draft("  ${it.name}  ").copy(type = it)) }
        val tasks = repository.snapshot().tasks
        assertEquals(5, tasks.size)
        assertEquals(5, tasks.map { java.util.UUID.fromString(it.id) }.distinct().size)
        assertTrue(tasks.all { it.dueAt == null && it.version == 1L && it.source == RecordSource.MANUAL })
        assertTrue(tasks.all { it.title == it.title.trim() })
    }

    @Test fun completionUsesLocalInstantAndUndoIncrementsVersion() = runTest {
        val task = repository.createTask(draft())
        val receipt = repository.setTaskCompleted(task.id, true, 1)
        assertEquals(2L, receipt.version)
        assertEquals(LocalDateTime.of(2026, 11, 9, 9, 50), repository.getTask(task.id)!!.completedAt)
        val noChange = repository.setTaskCompleted(task.id, true, 2)
        assertFalse(noChange.changed)
        assertEquals(2L, noChange.version)
        repository.setTaskCompleted(task.id, false, 2)
        assertNull(repository.getTask(task.id)!!.completedAt)
        assertEquals(3L, repository.getTask(task.id)!!.version)
    }

    @Test fun staleVersionFailsWithoutPartialWrite() = runTest {
        val task = repository.createTask(draft())
        repository.updateTask(task.id, 1, draft("新内容"))
        expect(DataError.VERSION_CONFLICT) { repository.setTaskCompleted(task.id, true, 1) }
        assertEquals("新内容", repository.getTask(task.id)!!.title)
        assertNull(repository.getTask(task.id)!!.completedAt)
    }

    @Test fun concurrentWritersOnlyOneCanUseTheSameVersion() = runTest {
        val task = repository.createTask(draft())
        val writes = (1..2).map { index -> async {
            runCatching { repository.updateTask(task.id, 1, draft("编辑 $index")) }
        } }.map { it.await() }
        assertEquals(1, writes.count { it.isSuccess })
        assertEquals(DataError.VERSION_CONFLICT, (writes.single { it.isFailure }.exceptionOrNull() as DataException).code)
        assertEquals(2L, repository.getTask(task.id)!!.version)
    }

    @Test fun requestReceiptsReplayCreateUpdateAndDeleteWithoutAnotherWrite() = runTest {
        val created = repository.createTask(draft(), requestId = "create-1")
        val replay = repository.createTask(draft(), requestId = "create-1")
        assertEquals(created.id, replay.id)
        assertTrue(replay.replayed)
        repository.updateTask(created.id, 1, draft("修改"), "edit-1")
        assertTrue(repository.updateTask(created.id, 1, draft("修改"), "edit-1").replayed)
        repository.deleteTask(created.id, 2, "delete-1")
        assertTrue(repository.deleteTask(created.id, 2, "delete-1").replayed)
        assertTrue(repository.snapshot().tasks.isEmpty())
        expect(DataError.REQUEST_ID_REUSED) { repository.createTask(draft("其他"), requestId = "create-1") }
    }

    @Test fun invalidWritesDoNotConsumeRequestIdAndReturnAgreedErrors() = runTest {
        expect(DataError.EMPTY_TITLE) { repository.createTask(draft("   "), requestId = "retry") }
        expect(DataError.TITLE_TOO_LONG) { repository.createTask(draft("字".repeat(61))) }
        expect(DataError.COURSE_NOT_FOUND) { repository.createTask(draft().copy(courseId = "missing")) }
        expect(DataError.TASK_NOT_FOUND) { repository.deleteTask("missing", 1) }
        repository.createTask(draft(), requestId = "retry")
        assertEquals(1, repository.snapshot().tasks.size)
    }

    @Test fun deletingCourseRemovesSchedulesButRetainsTaskAssociation() = runTest {
        val course = repository.createCourse(CourseDraft("软件工程", schedules = listOf(ScheduleDraft(1, 1, 2, 1, 16))))
        val task = repository.createTask(draft().copy(courseId = course.id))
        repository.deleteCourse(course.id, 1)
        val snapshot = repository.snapshot()
        assertTrue(snapshot.courses.isEmpty())
        assertTrue(snapshot.schedules.isEmpty())
        assertEquals(course.id, snapshot.tasks.single().courseId)
        repository.updateTask(task.id, 1, draft("保留原关联").copy(courseId = course.id))
        expect(DataError.COURSE_NOT_FOUND) { repository.createTask(draft().copy(courseId = course.id)) }
    }

    @Test fun semesterAndPeriodsUpdateAsOneVersionedAggregate() = runTest {
        val semester = SemesterRecord("term", "秋季", null, 18)
        val period = PeriodRecord("term", 1, LocalTime.of(8, 0), LocalTime.of(8, 45))
        repository.saveSemester(semester, listOf(period), null)
        repository.saveSemester(semester.copy(startsOn = LocalDate.of(2026, 9, 7)), listOf(period.copy(endsAt = LocalTime.of(8, 50))), 1)
        expect(DataError.VERSION_CONFLICT) { repository.saveSemester(semester, emptyList(), 1) }
        assertEquals(2L, repository.snapshot().semesters.single().version)
        assertEquals(LocalTime.of(8, 50), repository.snapshot().periods.single().endsAt)
    }

    @Test fun snapshotFlowPublishesCommittedData() = runTest {
        assertTrue(repository.snapshots.first().tasks.isEmpty())
        val course = repository.createCourse(CourseDraft("数据库", schedules = listOf(ScheduleDraft(2, 3, 4, 1, 16))))
        val state = repository.snapshots.first { it.courses.isNotEmpty() }
        assertEquals(course.id, state.schedules.single().courseId)
    }

    private suspend fun expect(code: DataError, block: suspend () -> Unit) {
        try { block(); fail("Expected $code") }
        catch (error: DataException) { assertEquals(code, error.code) }
    }
}
