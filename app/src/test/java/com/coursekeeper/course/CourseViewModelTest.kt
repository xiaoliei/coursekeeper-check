package com.coursekeeper.course

import com.coursekeeper.data.CourseDraft
import com.coursekeeper.data.DataError
import com.coursekeeper.data.DataException
import com.coursekeeper.data.ScheduleDraft
import com.coursekeeper.data.WriteReceipt
import com.coursekeeper.data.model.CourseRecord
import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.ScheduleRecord
import com.coursekeeper.data.model.SemesterRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 课表页 ViewModel 单测（成员 B）。
 *
 * 覆盖：初始周、翻周、未设学期、学期设置、保存/删除与版本冲突文案。
 * 用假实现替换 Repository，避免依赖 Room/Robolectric。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CourseViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val now = LocalDateTime.of(2026, 11, 10, 10, 0)

    private val semester = SemesterRecord("s1", "秋季", LocalDate.of(2026, 9, 7), 20)
    private val course = CourseRecord("c1", "数据结构", semesterId = "s1")
    private val schedule = ScheduleRecord(
        id = "sch1", courseId = "c1", dayOfWeek = 2, startPeriod = 1, endPeriod = 2,
        weeks = WeekText.parse("9-10").weeks, location = "A101",
    )

    private fun snapshot(
        semesters: List<SemesterRecord> = listOf(semester),
        scheduled: Boolean = true,
    ) = DataSnapshot(
        semesters = semesters,
        courses = if (scheduled) listOf(course) else emptyList(),
        schedules = if (scheduled) listOf(schedule) else emptyList(),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        snapshots: MutableStateFlow<DataSnapshot>,
        writer: CourseWriter = FakeWriter(snapshots),
    ) = CourseViewModel(FakeService(now), writer, snapshots) { now }

    @Test
    fun `初始显示当前教学周与本周课程`() = runTest {
        val snapshots = MutableStateFlow(snapshot())
        val model = viewModel(snapshots)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect { } }
        advanceUntilIdle()

        val state = model.uiState.value
        assertEquals(CoursePhase.READY, state.phase)
        assertEquals(10, state.currentWeek)
        assertEquals(10, state.selectedWeek)
        assertEquals(1, state.entryCount)
        assertTrue(state.isCurrentWeek)
        assertEquals(1, state.courses.size)
    }

    @Test
    fun `翻到下一周再回到本周`() = runTest {
        val snapshots = MutableStateFlow(snapshot())
        val model = viewModel(snapshots)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect { } }
        advanceUntilIdle()

        model.nextWeek()
        advanceUntilIdle()
        assertEquals(11, model.uiState.value.selectedWeek)
        assertEquals(0, model.uiState.value.entryCount)

        model.goCurrentWeek()
        advanceUntilIdle()
        assertEquals(10, model.uiState.value.selectedWeek)
        assertEquals(1, model.uiState.value.entryCount)
    }

    @Test
    fun `边界周不能继续翻`() = runTest {
        val snapshots = MutableStateFlow(snapshot())
        val model = viewModel(snapshots)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect { } }
        advanceUntilIdle()

        repeat(30) {
            model.nextWeek()
            advanceUntilIdle()
        }
        assertEquals(20, model.uiState.value.selectedWeek)
        assertTrue(!model.uiState.value.canGoNext)

        repeat(30) {
            model.previousWeek()
            advanceUntilIdle()
        }
        assertEquals(1, model.uiState.value.selectedWeek)
        assertTrue(!model.uiState.value.canGoPrevious)
    }

    @Test
    fun `未设学期时是 NO_SEMESTER`() = runTest {
        val snapshots = MutableStateFlow(snapshot(semesters = listOf(SemesterRecord("s1", "秋季", null, 20))))
        val model = viewModel(snapshots)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect { } }
        advanceUntilIdle()

        val state = model.uiState.value
        assertEquals(CoursePhase.NO_SEMESTER, state.phase)
        assertNull(state.semesterId)
        assertEquals(0, state.weekCount)
        assertEquals(0, state.entryCount)
    }

    @Test
    fun `设置学期成功后进入 READY`() = runTest {
        val snapshots = MutableStateFlow(snapshot(semesters = listOf(SemesterRecord("s1", "秋季", null, 20))))
        val model = viewModel(snapshots)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect { } }
        advanceUntilIdle()
        assertEquals(CoursePhase.NO_SEMESTER, model.uiState.value.phase)

        var result: String? = "未回调"
        model.configureSemester(LocalDate.of(2026, 9, 7), 20) { result = it }
        advanceUntilIdle()

        assertNull(result)
        assertEquals(CoursePhase.READY, model.uiState.value.phase)
        assertEquals(10, model.uiState.value.currentWeek)
    }

    @Test
    fun `保存课程把草稿交给写侧`() = runTest {
        val snapshots = MutableStateFlow(snapshot())
        val writer = FakeWriter(snapshots)
        val model = viewModel(snapshots, writer)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect { } }
        advanceUntilIdle()

        var result: String? = "未回调"
        model.saveCourse(
            existing = null,
            draft = CourseDraft(
                title = " 操作系统 ",
                semesterId = "s1",
                schedules = listOf(ScheduleDraft(3, 3, 4, WeekText.parse("1-16").weeks, location = "B202")),
            ),
        ) { result = it }
        advanceUntilIdle()

        assertNull(result)
        assertEquals("操作系统", writer.lastDraft?.title)
        assertEquals(1, writer.created)
    }

    @Test
    fun `空标题被拦下 不调用写侧`() = runTest {
        val snapshots = MutableStateFlow(snapshot())
        val writer = FakeWriter(snapshots)
        val model = viewModel(snapshots, writer)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect { } }
        advanceUntilIdle()

        var result: String? = null
        model.saveCourse(null, CourseDraft("   ", semesterId = "s1")) { result = it }
        advanceUntilIdle()

        assertEquals("请填写课程名称", result)
        assertEquals(0, writer.created)
    }

    @Test
    fun `版本冲突转成人话`() = runTest {
        val snapshots = MutableStateFlow(snapshot())
        val writer = FakeWriter(snapshots).apply { failWith = DataError.VERSION_CONFLICT }
        val model = viewModel(snapshots, writer)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect { } }
        advanceUntilIdle()

        var result: String? = null
        model.deleteCourse(model.uiState.value.courses.first()) { result = it }
        advanceUntilIdle()

        assertEquals("内容已被修改，请刷新后重试", result)
    }

    @Test
    fun `删除课程调用写侧并移除课程`() = runTest {
        val snapshots = MutableStateFlow(snapshot())
        val writer = FakeWriter(snapshots)
        val model = viewModel(snapshots, writer)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect { } }
        advanceUntilIdle()

        var result: String? = "未回调"
        model.deleteCourse(model.uiState.value.courses.first()) { result = it }
        advanceUntilIdle()

        assertNull(result)
        assertEquals(1, writer.deleted)
        assertEquals(0, model.uiState.value.courses.size)
    }

    private class FakeService(val now: LocalDateTime) : CourseService {
        override val state: Flow<CourseState> = emptyFlow()
        override fun project(snapshot: DataSnapshot, now: LocalDateTime) = CourseProjector.project(snapshot, now)
        override fun timetable(snapshot: DataSnapshot, weekNo: Int) =
            CourseProjector.timetable(snapshot, weekNo, now.toLocalDate())
        override fun conflicts(snapshot: DataSnapshot, weekNo: Int) =
            CourseProjector.conflicts(snapshot, weekNo, now.toLocalDate())
        override fun weekOf(snapshot: DataSnapshot, date: LocalDate) = CourseProjector.weekOf(snapshot, date)
        override suspend fun stateNow() = CourseProjector.project(DataSnapshot(), now)
    }

    private class FakeWriter(
        private val snapshots: MutableStateFlow<DataSnapshot>,
        var failWith: DataError? = null,
    ) : CourseWriter {
        var created = 0
        var deleted = 0
        var lastDraft: CourseDraft? = null

        override suspend fun createCourse(draft: CourseDraft, requestId: String?): WriteReceipt {
            failWith?.let { throw DataException(it) }
            created++
            lastDraft = draft
            snapshots.value = snapshots.value.copy(
                courses = snapshots.value.courses + CourseRecord("new", draft.title, semesterId = draft.semesterId),
            )
            return WriteReceipt("new", 1, true)
        }

        override suspend fun updateCourse(
            id: String,
            expectedVersion: Long,
            draft: CourseDraft,
            requestId: String?,
        ): WriteReceipt {
            failWith?.let { throw DataException(it) }
            lastDraft = draft
            return WriteReceipt(id, expectedVersion + 1, true)
        }

        override suspend fun deleteCourse(id: String, expectedVersion: Long, requestId: String?): WriteReceipt {
            failWith?.let { throw DataException(it) }
            deleted++
            snapshots.value = snapshots.value.copy(
                courses = snapshots.value.courses.filterNot { it.id == id },
                schedules = snapshots.value.schedules.filterNot { it.courseId == id },
            )
            return WriteReceipt(id, expectedVersion + 1, true)
        }

        override suspend fun configureSemester(
            semesterId: String,
            name: String,
            startsOn: LocalDate,
            weekCount: Int,
            expectedVersion: Long?,
        ): WriteReceipt {
            failWith?.let { throw DataException(it) }
            snapshots.value = snapshots.value.copy(
                semesters = listOf(SemesterRecord(semesterId, name, startsOn, weekCount)),
            )
            return WriteReceipt(semesterId, 1, true)
        }
    }
}
