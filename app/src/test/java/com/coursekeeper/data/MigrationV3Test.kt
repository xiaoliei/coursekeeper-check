package com.coursekeeper.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.coursekeeper.data.local.CourseKeeperDatabase
import com.coursekeeper.data.model.*
import com.coursekeeper.data.sync.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

class MigrationV3Test : DataTestBase() {
    @Test fun v2UpgradePreservesBusinessDataParityHighestBitAndSqliteConstraints() = runTest {
        withLegacyDatabase { name ->
            val upgraded = CourseKeeperDatabase.open(context, name)
            try {
                val repo = CourseKeeperRepository(upgraded, clock)
                val state = repo.snapshot() // Opening performs migration and Room's complete schema validation.
                assertEquals(7L, state.courses.single().version)
                assertTrue(state.courses.single().manualDirty)
                assertEquals("默认教师", state.courses.single().teacher)
                assertEquals(4L, state.semesters.single().version)
                assertEquals(1, state.periods.size)
                assertEquals("course-old", state.tasks.single().courseId)
                assertEquals(9L, state.tasks.single().version)
                assertEquals(LocalDateTime.of(2026, 11, 11, 10, 0), state.tasks.single().completedAt)
                assertEquals(Instant.parse("2026-11-11T01:00:00Z"), state.syncRecords.single().lastSuccessAt)
                val rows = state.schedules.associateBy { it.id }
                assertEquals("1-16", rows.getValue("all").weeksText)
                assertEquals("1,3,5,7,9,11,13,15", rows.getValue("odd").weeksText)
                assertEquals("2,4,6,8,10,12,14,16", rows.getValue("even").weeksText)
                assertEquals(Long.MIN_VALUE, rows.getValue("week64").weeks)
                assertEquals("64", rows.getValue("week64").weeksText)
                assertTrue(rows.values.all { it.teacher == null && it.courseId == "course-old" })
                assertEquals("教学楼", rows.getValue("all").location)
                assertEquals(42L, upgraded.records().observeRevision().first())
                val sqlite = upgraded.openHelper.writableDatabase
                val columns = sqlite.query("PRAGMA table_info(schedules)").use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                }
                assertTrue(columns.containsAll(listOf("weeks", "weeksText", "teacher")))
                assertFalse(columns.any { it in listOf("startWeek", "endWeek", "weekPattern") })
                assertEquals(3, sqlite.version)
                sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                repo.deleteCourse("course-old", 7)
                assertTrue(repo.snapshot().schedules.isEmpty())
                assertEquals("course-old", repo.snapshot().tasks.single().courseId)
            } finally { upgraded.close() }
        }
    }

    @Test fun upgradedReceiptsAndTombstonesStillBlockDuplicateWritesAndResurrection() = runTest {
        withLegacyDatabase { name ->
            val upgraded = CourseKeeperDatabase.open(context, name)
            try {
                val repo = CourseKeeperRepository(upgraded, clock)
                val receipt = repo.createTask(draft("历史请求"), requestId = "old-request")
                assertTrue(receipt.replayed)
                assertEquals("task-old", receipt.id)
                val adapter = FakeAdapter().apply { taskResult = ModuleResult.Success(listOf(ImportedTask("deleted-task", draft()))) }
                SyncCoordinator(repo, adapter, clock).synchronize(true)
                assertEquals(listOf("task-old"), repo.snapshot().tasks.map { it.id })
                assertNotNull(repo.snapshot().tasks.single().completedAt)
            } finally { upgraded.close() }
        }
    }

    @Test fun firstAggregateEditAfterMigrationKeepsEveryUnchangedLegacyScheduleId() = runTest {
        withLegacyDatabase { name ->
            val upgraded = CourseKeeperDatabase.open(context, name)
            try {
                val repo = CourseKeeperRepository(upgraded, clock)
                val old = repo.snapshot()
                val newEntry = ScheduleDraft(3, 3, 4, weeks = 7424L, teacher = "条目教师")
                repo.updateCourse("course-old", 7, CourseDraft("改名后", "term-old", "默认教师",
                    old.schedules.map { it.asDraft() }.reversed() + newEntry))
                val now = repo.snapshot()
                assertEquals(8L, now.courses.single().version)
                assertTrue(now.schedules.map { it.id }.containsAll(old.schedules.map { it.id }))
                assertEquals(5, now.schedules.size)
                assertEquals("条目教师", now.schedules.single { it.weeks == 7424L }.teacher)
            } finally { upgraded.close() }
        }
    }

    @Test fun emptyV2DatabaseOpensAtV3WithoutInventingRecords() = runTest {
        withLegacyDatabase(seed = false) { name ->
            val upgraded = CourseKeeperDatabase.open(context, name)
            try {
                val repo = CourseKeeperRepository(upgraded, clock)
                assertEquals(DataSnapshot(), repo.snapshot())
                repo.createTask(draft())
                assertEquals(1, repo.snapshot().tasks.size)
                assertEquals(3, upgraded.openHelper.writableDatabase.version)
            } finally { upgraded.close() }
        }
    }

    @Test fun invalidUnrepresentableLegacyWeeksLeaveTheV2DatabaseIntact() = runTest {
        withLegacyDatabase(beforeUpgrade = { old ->
            old.execSQL("UPDATE schedules SET endWeek = 65 WHERE id = 'all'")
        }) { name ->
            val upgraded = CourseKeeperDatabase.open(context, name)
            try {
                val failure = runCatching { CourseKeeperRepository(upgraded, clock).snapshot() }.exceptionOrNull()
                assertNotNull(failure)
                assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("1—64") == true })
            } finally { upgraded.close() }
            context.openOrCreateDatabase(name, 0, null).use { old ->
                assertEquals(2, old.version)
                old.rawQuery("SELECT endWeek FROM schedules WHERE id = 'all'", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(65, it.getInt(0))
                }
                old.rawQuery("SELECT version, completedAt FROM tasks WHERE id = 'task-old'", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(9L, it.getLong(0)); assertNotNull(it.getString(1))
                }
            }
        }
    }

    @Test fun legacyParityWithNoTeachingWeekIsPreservedWithoutInventingOne() = runTest {
        withLegacyDatabase(beforeUpgrade = { old ->
            old.execSQL("UPDATE schedules SET startWeek = 2, endWeek = 2 WHERE id = 'odd'")
        }) { name ->
            val upgraded = CourseKeeperDatabase.open(context, name)
            try {
                val entry = CourseKeeperRepository(upgraded, clock).snapshot().schedules.single { it.id == "odd" }
                assertEquals(0L, entry.weeks)
                assertEquals("", entry.weeksText)
            } finally { upgraded.close() }
        }
    }

    private suspend fun withLegacyDatabase(
        seed: Boolean = true, beforeUpgrade: (SQLiteDatabase) -> Unit = {}, action: suspend (String) -> Unit,
    ) {
        val name = "v3-${UUID.randomUUID()}.db"
        try {
            val schema = javaClass.classLoader!!.getResourceAsStream("com.coursekeeper.data.local.CourseKeeperDatabase/2.json")!!
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
                if (seed) seedLegacy(old)
                beforeUpgrade(old)
                old.version = 2
            }
            action(name)
        } finally { context.deleteDatabase(name) }
    }

    private fun seedLegacy(old: SQLiteDatabase) {
        old.execSQL("INSERT INTO semesters VALUES ('term-old', '旧学期', '2026-09-07', 64, 4)")
        old.execSQL("INSERT INTO periods VALUES ('term-old', 1, '08:00', '08:45')")
        old.execSQL("INSERT INTO courses VALUES ('course-old', '旧课程', 'term-old', '默认教师', 'SYNC', 'course-code', 7, 1)")
        for ((id, pattern) in listOf("all" to "ALL", "odd" to "ODD", "even" to "EVEN", "week64" to "ALL")) {
            old.insertOrThrow("schedules", null, ContentValues().apply {
                put("id", id); put("courseId", "course-old"); put("dayOfWeek", 2); put("startPeriod", 1); put("endPeriod", 2)
                put("startWeek", if (id == "week64") 64 else 1); put("endWeek", if (id == "week64") 64 else 16)
                put("weekPattern", pattern); put("location", "教学楼")
            })
        }
        old.execSQL("INSERT INTO tasks VALUES ('task-old', '旧任务', 'LAB_REPORT', NULL, 'course-old', '2026-11-11T10:00', 'SYNC', 'old-task', 9, 1)")
        old.execSQL("INSERT INTO sync_records VALUES ('COURSES', 'test-school', 'IDLE', '2026-11-11T01:00:00Z', '2026-11-11T01:00:00Z', NULL, 1, 0, 0, 0)")
        old.execSQL("INSERT INTO tombstones VALUES ('TASKS', 'SYNC', 'deleted-task')")
        old.execSQL("INSERT INTO write_requests VALUES (?, ?, ?, ?, ?)", arrayOf<Any>("old-request",
            fingerprint("createTask", RecordSource.MANUAL, draft("历史请求")), "task-old", 1L, 1))
        old.execSQL("INSERT INTO data_revision VALUES (1, 42)")
    }
}
