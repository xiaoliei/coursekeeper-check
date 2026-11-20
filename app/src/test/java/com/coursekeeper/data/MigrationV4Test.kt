package com.coursekeeper.data

import android.database.Cursor
import com.coursekeeper.data.local.CourseKeeperDatabase
import com.coursekeeper.data.model.RecordSource
import com.coursekeeper.data.sync.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.util.UUID

class MigrationV4Test : DataTestBase() {
    @Test fun v3UpgradePreservesAllTablesAndAddsNullableSchoolFieldsWithoutGuessingCompletion() = runTest {
        withV3Database(seed = true) { name, before ->
            val upgraded = CourseKeeperDatabase.open(context, name)
            try {
                val repo = CourseKeeperRepository(upgraded, clock)
                val state = repo.snapshot() // Room validates the migrated schema, indices and constraints.
                val sqlite = upgraded.openHelper.writableDatabase
                assertEquals(4, sqlite.version)
                for ((table, contents) in before) {
                    val (columns, rows) = contents
                    sqlite.query("SELECT ${columns.joinToString { "`$it`" }} FROM `$table` ORDER BY rowid").use {
                        assertEquals("Preserve $table", rows, it.rows())
                    }
                }
                assertTrue(state.tasks.all { it.schoolSubmission == null && it.sourceCourseId == null && it.sourceCourseName == null })
                assertNull(state.tasks.single { it.id == "pending" }.completedAt)
                val done = state.tasks.single { it.id == "done" }
                assertEquals(LocalDateTime.of(2026, 11, 9, 8, 0), done.completedAt)
                assertTrue(done.manualDirty)
                assertEquals("deleted-course", done.courseId)
                sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }

                // Upgraded receipts still replay, and the tombstone still prevents submitted-task resurrection.
                assertTrue(repo.createTask(draft("历史请求"), requestId = "old-request").replayed)
                val adapter = FakeAdapter().apply {
                    taskResult = ModuleResult.Success(listOf(ImportedTask("deleted-task", draft(), schoolSubmission = "SUBMITTED")))
                }
                SyncCoordinator(repo, adapter, clock).synchronize(true)
                assertEquals(setOf("pending", "done"), repo.snapshot().tasks.map { it.id }.toSet())
            } finally { upgraded.close() }
        }
    }

    @Test fun emptyV3UpgradeCanPersistNewSchoolFields() = runTest {
        withV3Database(seed = false) { name, _ ->
            val upgraded = CourseKeeperDatabase.open(context, name)
            try {
                val repo = CourseKeeperRepository(upgraded, clock)
                assertTrue(repo.snapshot().tasks.isEmpty())
                val adapter = FakeAdapter().apply {
                    taskResult = ModuleResult.Success(listOf(ImportedTask("new", draft(), schoolSubmission = "SUBMITTED",
                        sourceCourseId = "course-1001", sourceCourseName = "学校课程")))
                }
                SyncCoordinator(repo, adapter, clock).synchronize()
                val task = repo.snapshot().tasks.single()
                assertEquals(RecordSource.SYNC, task.source)
                assertEquals("SUBMITTED", task.schoolSubmission)
                assertEquals("course-1001", task.sourceCourseId)
                assertEquals("学校课程", task.sourceCourseName)
                assertNotNull(task.completedAt)
                assertFalse(task.manualDirty)
            } finally { upgraded.close() }
        }
    }

    private suspend fun withV3Database(
        seed: Boolean,
        action: suspend (String, Map<String, Pair<List<String>, List<List<String?>>>>) -> Unit,
    ) {
        val name = "v4-${UUID.randomUUID()}.db"
        try {
            val schema = javaClass.classLoader!!.getResourceAsStream("com.coursekeeper.data.local.CourseKeeperDatabase/3.json")!!
                .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            val before = linkedMapOf<String, Pair<List<String>, List<List<String?>>>>()
            context.openOrCreateDatabase(name, 0, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: JSONArray()
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                if (seed) {
                    old.execSQL("INSERT INTO semesters VALUES ('term', '秋季', '2026-09-07', 20, 4)")
                    old.execSQL("INSERT INTO periods VALUES ('term', 1, '08:00', '08:45')")
                    old.execSQL("INSERT INTO courses VALUES ('course', '数据库', 'term', '教师', 'SYNC', 'code', 7, 0)")
                    old.execSQL("INSERT INTO schedules VALUES ('schedule', 'course', 1, 1, 2, 21, '1,3,5', '教室', '条目教师')")
                    old.execSQL("INSERT INTO tasks VALUES ('pending', '待办', 'HOMEWORK', '2026-11-10T12:00', 'course', NULL, 'SYNC', 'pending-id', 8, 0)")
                    old.execSQL("INSERT INTO tasks VALUES ('done', '完成', 'LAB_REPORT', NULL, 'deleted-course', '2026-11-09T08:00', 'SYNC', 'done-id', 9, 1)")
                    old.execSQL("INSERT INTO sync_records VALUES ('TASKS', 'test-school', 'IDLE', '2026-11-09T01:00:00Z', '2026-11-09T01:00:00Z', NULL, 2, 0, 0, 0)")
                    old.execSQL("INSERT INTO tombstones VALUES ('TASKS', 'SYNC', 'deleted-task')")
                    old.execSQL("INSERT INTO write_requests VALUES (?, ?, ?, ?, ?)", arrayOf<Any>("old-request",
                        fingerprint("createTask", RecordSource.MANUAL, draft("历史请求")), "pending", 1L, 1))
                    old.execSQL("INSERT INTO data_revision VALUES (1, 42)")
                }
                for (i in 0 until entities.length()) {
                    val table = entities.getJSONObject(i).getString("tableName")
                    old.rawQuery("SELECT * FROM `$table` ORDER BY rowid", null).use {
                        before[table] = it.columnNames.toList() to it.rows()
                    }
                }
                old.version = 3
            }
            action(name, before)
        } finally { context.deleteDatabase(name) }
    }

    private fun Cursor.rows(): List<List<String?>> = buildList {
        while (moveToNext()) add((0 until columnCount).map { if (isNull(it)) null else getString(it) })
    }
}
