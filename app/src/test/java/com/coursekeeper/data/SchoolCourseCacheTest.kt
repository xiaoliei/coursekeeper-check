package com.coursekeeper.data

import com.coursekeeper.data.local.CourseKeeperDatabase
import com.coursekeeper.data.model.*
import com.coursekeeper.data.sync.*
import com.coursekeeper.school.MyStuTaskParser
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SchoolCourseCacheTest : DataTestBase() {
    @Test fun unionRetainsAbsentCoursesIncludingBlocksWithoutAssignments() = runTest {
        val html = javaClass.classLoader!!.getResourceAsStream("school/mystu-dashboard.html")!!
            .use { it.readBytes().decodeToString() }
        val parsed = MyStuTaskParser().parseTasks(html)
        assertTrue(parsed.sourceCourseIds.any { id -> parsed.tasks.none { it.sourceCourseId == id } })
        val adapter = FakeAdapter().apply {
            taskResult = ModuleResult.Success(parsed.tasks, sourceCourseIds = parsed.sourceCourseIds)
        }
        val sync = coordinator(adapter)
        sync.synchronize()
        assertEquals(parsed.sourceCourseIds, repository.knownSourceCourseIds(adapter.schoolId))
        adapter.taskResult = ModuleResult.Success(emptyList(), sourceCourseIds = setOf("new-course"))
        sync.synchronize(true)
        val expected = parsed.sourceCourseIds + "new-course"
        assertEquals(expected, repository.knownSourceCourseIds(adapter.schoolId))
        adapter.taskResult = ModuleResult.Success(emptyList())
        sync.synchronize(true)
        sync.synchronize(true)
        assertEquals(expected, repository.knownSourceCourseIds(adapter.schoolId))
    }

    @Test fun failedBatchAndNetworkFailureDoNotPolluteCacheOrAdvanceSuccess() = runTest {
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(emptyList(), sourceCourseIds = setOf("old")) }
        val sync = coordinator(adapter)
        sync.synchronize()
        val success = repository.snapshot().syncRecords.single { it.module == SyncModule.TASKS }.lastSuccessAt
        for (result in listOf(
            ModuleResult.Success(listOf(ImportedTask("bad", draft(" "))), sourceCourseIds = setOf("new")),
            ModuleResult.Success(listOf(ImportedTask("valid", draft())), sourceCourseIds = setOf(" ")),
            ModuleResult.Failure(SyncFailure.OFFLINE),
        )) {
            adapter.taskResult = result
            sync.synchronize(true)
            assertEquals(setOf("old"), repository.knownSourceCourseIds(adapter.schoolId))
            assertTrue(repository.snapshot().tasks.isEmpty())
            assertEquals(success, repository.snapshot().syncRecords.single { it.module == SyncModule.TASKS }.lastSuccessAt)
        }
    }

    @Test fun discoveryDoesNotOverwriteProtectedTasksOrResurrectDeletedOnes() = runTest {
        val task = ImportedTask("t", draft(), sourceCourseId = "first")
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(task)) }
        val sync = coordinator(adapter)
        sync.synchronize()
        val old = repository.snapshot().tasks.single()
        repository.updateTask(old.id, old.version, draft("我的修改"))
        adapter.taskResult = ModuleResult.Success(listOf(task.copy(sourceCourseId = "second", schoolSubmission = "SUBMITTED")))
        sync.synchronize(true)
        val protected = repository.getTask(old.id)!!
        assertEquals("我的修改", protected.title)
        assertEquals("first", protected.sourceCourseId)
        assertNull(protected.completedAt)
        repository.deleteTask(old.id, protected.version)
        adapter.taskResult = ModuleResult.Success(listOf(task.copy(sourceCourseId = "third")))
        sync.synchronize(true)
        assertTrue(repository.snapshot().tasks.isEmpty())
        assertEquals(setOf("first", "second", "third"), repository.knownSourceCourseIds(adapter.schoolId))
    }

    @Test fun schoolMismatchCannotWriteAndQueriesAreIsolated() = runTest {
        val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(emptyList(), sourceCourseIds = setOf("first")) }
        val sync = coordinator(adapter)
        sync.synchronize()
        adapter.schoolId = "other-school"
        adapter.taskResult = ModuleResult.Success(emptyList(), sourceCourseIds = setOf("second"))
        assertEquals(SyncRunResult.SchoolMismatch, sync.synchronize(true))
        assertEquals(setOf("first"), repository.knownSourceCourseIds("test-school"))
        assertTrue(repository.knownSourceCourseIds("other-school").isEmpty())
    }

    @Test fun cacheSurvivesCloseAndReopen() = runTest {
        val name = "cache-${UUID.randomUUID()}.db"
        try {
            val disk = CourseKeeperDatabase.open(context, name)
            try {
                val repo = CourseKeeperRepository(disk, clock)
                val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(emptyList(), sourceCourseIds = setOf("1", "2")) }
                SyncCoordinator(repo, adapter, clock).synchronize()
            } finally { disk.close() }
            val reopened = CourseKeeperDatabase.open(context, name)
            try { assertEquals(setOf("1", "2"), CourseKeeperRepository(reopened, clock).knownSourceCourseIds("test-school")) }
            finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun v4MigrationPreservesCompletedTaskAndStartsWithEmptyDiscoveryHistory() = runTest {
        val name = "v5-${UUID.randomUUID()}.db"
        try {
            val schema = javaClass.classLoader!!.getResourceAsStream("com.coursekeeper.data.local.CourseKeeperDatabase/4.json")!!
                .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            context.openOrCreateDatabase(name, 0, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: JSONArray()
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                old.execSQL("INSERT INTO tasks VALUES ('t', '保留任务', 'HOMEWORK', NULL, 'deleted-course', '2026-11-20T08:00', 'SYNC', 'remote', 9, 1, 'SUBMITTED', '1001', '来源课程')")
                old.version = 4
            }
            val upgraded = CourseKeeperDatabase.open(context, name)
            try {
                val repo = CourseKeeperRepository(upgraded, clock)
                val task = repo.snapshot().tasks.single()
                assertEquals(5, upgraded.openHelper.writableDatabase.version)
                assertEquals(9L, task.version)
                assertTrue(task.manualDirty)
                assertEquals("2026-11-20T08:00", task.completedAt.toString())
                assertEquals("deleted-course", task.courseId)
                assertEquals("SUBMITTED", task.schoolSubmission)
                assertEquals("1001", task.sourceCourseId)
                assertEquals("来源课程", task.sourceCourseName)
                assertTrue(repo.knownSourceCourseIds("test-school").isEmpty())
                // Old task provenance alone cannot establish school ownership; only a new successful fetch can.
                val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(emptyList(), sourceCourseIds = setOf("1001")) }
                SyncCoordinator(repo, adapter, clock).synchronize()
                assertEquals(setOf("1001"), repo.knownSourceCourseIds("test-school"))
                assertEquals(task, repo.getTask("t"))
            } finally { upgraded.close() }
        } finally { context.deleteDatabase(name) }
    }
}
