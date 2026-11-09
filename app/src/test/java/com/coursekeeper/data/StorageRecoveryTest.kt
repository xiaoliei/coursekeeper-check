package com.coursekeeper.data

import android.content.ContentValues
import com.coursekeeper.data.local.CourseKeeperDatabase
import com.coursekeeper.data.model.*
import com.coursekeeper.data.sync.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class StorageRecoveryTest : DataTestBase() {
    @Test fun persistedWritesReceiptsAndTombstonesSurviveReopenAndBusinessBackupRestore() = runTest {
        val name = "persist-${UUID.randomUUID()}.db"
        val restoreName = "restore-${UUID.randomUUID()}.db"
        var disk = CourseKeeperDatabase.open(context, name)
        try {
            var repo = CourseKeeperRepository(disk, clock)
            val adapter = FakeAdapter().apply {
                courseResult = ModuleResult.Success(listOf(ImportedCourse("course", CourseDraft("持久化课程",
                    schedules = listOf(ScheduleDraft(1, 1, 2, 1, 16))))))
                taskResult = ModuleResult.Success(listOf(ImportedTask("remote", draft(), "course")))
            }
            SyncCoordinator(repo, adapter, clock).synchronize()
            val remote = repo.snapshot().tasks.single()
            repo.deleteTask(remote.id, remote.version)
            val manualDraft = draft("本地留存").copy(courseId = repo.snapshot().courses.single().id)
            val manual = repo.createTask(manualDraft, requestId = "persist-request")
            repo.setTaskCompleted(manual.id, true, 1)
            val expected = repo.snapshot()
            disk.close()
            // Business DB contains no credentials; SQLite is closed before copying its consistent file.
            context.getDatabasePath(name).copyTo(context.getDatabasePath(restoreName), overwrite = true)
            disk = CourseKeeperDatabase.open(context, restoreName)
            repo = CourseKeeperRepository(disk, clock)
            assertEquals(expected, repo.snapshot())
            assertTrue(repo.createTask(manualDraft, requestId = "persist-request").replayed)
            SyncCoordinator(repo, adapter, clock).synchronize(true)
            assertEquals(listOf(manual.id), repo.snapshot().tasks.map { it.id })
            assertNotNull(repo.getTask(manual.id)!!.completedAt)
        } finally {
            disk.close()
            context.deleteDatabase(name)
            context.deleteDatabase(restoreName)
        }
    }

    @Test fun baselineV1MigrationPreservesIdsVersionsCompletionAndDeletedCourseAssociation() = runTest {
        migrateBaseline(withRecords = true)
    }

    @Test fun emptyBaselineV1MigratesWithoutInventingRecords() = runTest {
        migrateBaseline(withRecords = false)
    }

    private suspend fun migrateBaseline(withRecords: Boolean) {
        val name = "migration-${UUID.randomUUID()}.db"
        val schema = javaClass.classLoader!!.getResourceAsStream("com.coursekeeper.data.local.CourseKeeperDatabase/1.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql")
                    .replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            if (withRecords) {
                old.insertOrThrow("semesters", null, ContentValues().apply {
                    put("id", "retained-term"); put("name", "秋季"); put("startsOn", "2026-09-07")
                    put("weekCount", 18); put("version", 3L)
                })
                old.insertOrThrow("courses", null, ContentValues().apply {
                    put("id", "retained-course"); put("title", "旧课程"); put("semesterId", "retained-term")
                    putNull("teacher"); put("source", "SYNC"); put("externalId", "old-course")
                    put("version", 4L); put("manualDirty", 0)
                })
                old.insertOrThrow("schedules", null, ContentValues().apply {
                    put("id", "retained-schedule"); put("courseId", "retained-course"); put("dayOfWeek", 1)
                    put("startPeriod", 1); put("endPeriod", 2); put("startWeek", 1); put("endWeek", 18)
                    put("weekPattern", "ALL"); put("location", "教学楼")
                })
                old.insertOrThrow("tasks", null, ContentValues().apply {
                    put("id", "retained-id"); put("title", "旧任务"); put("type", "LAB_REPORT")
                    putNull("dueAt"); put("courseId", "deleted-course"); put("completedAt", "2026-11-09T00:00")
                    put("source", "SYNC"); put("externalId", "stable-external-id"); put("version", 7L); put("manualDirty", 1)
                })
            }
            old.version = 1
        }
        val upgraded = CourseKeeperDatabase.open(context, name)
        try {
            val repo = CourseKeeperRepository(upgraded, clock)
            val state = repo.snapshot()
            if (withRecords) {
                assertEquals("retained-term", state.semesters.single().id)
                assertEquals(3L, state.semesters.single().version)
                assertEquals("retained-course", state.courses.single().id)
                assertEquals(4L, state.courses.single().version)
                assertEquals("retained-schedule", state.schedules.single().id)
                assertEquals("retained-course", state.schedules.single().courseId)
                val retained = state.tasks.single()
                assertEquals("retained-id", retained.id)
                assertEquals(7L, retained.version)
                assertEquals("deleted-course", retained.courseId)
                assertNotNull(retained.completedAt)
                assertNull(retained.dueAt)
                assertTrue(retained.manualDirty)
                repo.deleteTask(retained.id, 7, "after-migration")
                assertTrue(repo.deleteTask(retained.id, 7, "after-migration").replayed)
            } else assertTrue(state.tasks.isEmpty())
            repo.createTask(draft())
            assertEquals(1, repo.snapshot().tasks.size)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
