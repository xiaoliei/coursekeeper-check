package com.coursekeeper.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coursekeeper.data.local.CourseKeeperDatabase
import com.coursekeeper.data.model.*
import com.coursekeeper.data.sync.*
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
abstract class DataTestBase {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected lateinit var database: CourseKeeperDatabase
    protected lateinit var repository: CourseKeeperRepository
    protected val clock = MutableClock()

    @Before fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, CourseKeeperDatabase::class.java).build()
        repository = CourseKeeperRepository(database, clock)
    }

    @After fun closeDatabase() { database.close() }

    protected fun draft(title: String = "课程作业") = TaskDraft(title, TaskType.HOMEWORK)
    protected fun coordinator(adapter: FakeAdapter) = SyncCoordinator(repository, adapter, clock, retryDelayMillis = 0)
}

class MutableClock(
    var current: Instant = Instant.parse("2026-11-09T01:50:00Z"),
    private val localZone: ZoneId = ZoneId.of("Asia/Shanghai"),
) : Clock() {
    override fun instant(): Instant = current
    override fun getZone(): ZoneId = localZone
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
}

class FakeAdapter : SchoolAdapter {
    override var schoolId = "test-school"
    var session = SessionState.VALID
    var checks = 0
    var courseCalls = 0
    var taskCalls = 0
    var courseResult: ModuleResult<ImportedCourse> = ModuleResult.Success(emptyList())
    var taskResult: ModuleResult<ImportedTask> = ModuleResult.Success(emptyList())
    var beforeCourses: suspend () -> Unit = {}
    var beforeTasks: suspend () -> Unit = {}
    override suspend fun checkSession(): SessionState { checks++; return session }
    override suspend fun fetchCourses(): ModuleResult<ImportedCourse> { courseCalls++; beforeCourses(); return courseResult }
    override suspend fun fetchTasks(): ModuleResult<ImportedTask> { taskCalls++; beforeTasks(); return taskResult }
}
