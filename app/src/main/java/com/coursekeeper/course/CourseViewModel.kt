package com.coursekeeper.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursekeeper.data.CourseDraft
import com.coursekeeper.data.DataError
import com.coursekeeper.data.DataException
import com.coursekeeper.data.ScheduleDraft
import com.coursekeeper.data.model.DataSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/** 编辑课程时用的完整视图：课程字段 + 它的全部排课（不只当前周）。 */
data class CourseSummary(
    val id: String,
    val title: String,
    val teacher: String?,
    val version: Long,
    val schedules: List<ScheduleDraft>,
)

/**
 * 课表页状态（成员 B）。
 *
 * `selectedWeek` 始终是实际展示的周；`currentWeek` 是今天所在的教学周，
 * 两者不同即表示用户正在翻看其他周。
 */
data class CourseUiState(
    val loading: Boolean = true,
    val phase: CoursePhase = CoursePhase.NO_SEMESTER,
    val semesterId: String? = null,
    val semesterName: String = "",
    val semesterVersion: Long? = null,
    val weekCount: Int = 0,
    val currentWeek: Int? = null,
    val selectedWeek: Int = 1,
    val weekLabel: String = "",
    val days: Map<Int, List<CourseEntry>> = emptyMap(),
    val conflicts: List<CourseConflict> = emptyList(),
    val courses: List<CourseSummary> = emptyList(),
    val error: String? = null,
) {
    val entryCount: Int get() = days.values.sumOf { it.size }
    val isEmptyWeek: Boolean get() = entryCount == 0
    val isCurrentWeek: Boolean get() = currentWeek != null && selectedWeek == currentWeek
    val canGoPrevious: Boolean get() = weekCount > 0 && selectedWeek > 1
    val canGoNext: Boolean get() = weekCount > 0 && selectedWeek < weekCount
    val hasSemester: Boolean get() = semesterId != null
}

/**
 * 课表页 ViewModel（成员 B，第 3–4 步）。
 *
 * - 读：订阅 E 的一致性快照，经 [CourseService] 投影成周课表；
 * - 写：经 [CourseWriter] 委托 Repository，版本冲突按 E 的错误码转成人话；
 * - 不缓存业务数据，翻周只改一个周号，切回“本周”即跟随系统当前周。
 */
class CourseViewModel(
    private val service: CourseService,
    private val writer: CourseWriter,
    private val snapshots: Flow<DataSnapshot>,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) : ViewModel() {

    /** null = 跟随当前教学周；非空 = 用户正在看某一周。 */
    private val selectedWeek = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<CourseUiState> = combine(snapshots, selectedWeek) { snapshot, week ->
        build(snapshot, week, now())
    }
        .catch { emit(CourseUiState(loading = false, error = "课程数据读取失败，请重试")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CourseUiState())

    fun previousWeek() {
        val current = uiState.value
        if (current.canGoPrevious) selectedWeek.value = current.selectedWeek - 1
    }

    fun nextWeek() {
        val current = uiState.value
        if (current.canGoNext) selectedWeek.value = current.selectedWeek + 1
    }

    fun goCurrentWeek() {
        selectedWeek.value = uiState.value.currentWeek ?: 1
    }

    /** 学期起始日补录；成功后课表周次立即按新起始日重算。 */
    fun configureSemester(startsOn: LocalDate, weekCount: Int, onResult: (String?) -> Unit) =
        viewModelScope.launch {
            if (weekCount !in 1..64) {
                onResult(ERROR_WEEK_COUNT)
                return@launch
            }
            val current = uiState.value
            val id = current.semesterId ?: DEFAULT_SEMESTER_ID
            safely(onResult) {
                writer.configureSemester(
                    semesterId = id,
                    name = current.semesterName.ifBlank { "本学期" },
                    startsOn = startsOn,
                    weekCount = weekCount,
                    expectedVersion = current.semesterVersion,
                )
            }
        }

    /** [existing] 为空 = 新增课程；非空 = 按用户打开时看到的版本提交。 */
    fun saveCourse(existing: CourseSummary?, draft: CourseDraft, onResult: (String?) -> Unit) =
        viewModelScope.launch {
            val title = draft.title.trim()
            if (title.isEmpty()) {
                onResult("请填写课程名称")
                return@launch
            }
            if (title.length > 60) {
                onResult("课程名称过长（≤60 字）")
                return@launch
            }
            safely(onResult) {
                if (existing == null) {
                    writer.createCourse(draft.copy(title = title))
                } else {
                    writer.updateCourse(existing.id, existing.version, draft.copy(title = title))
                }
            }
        }

    fun deleteCourse(course: CourseSummary, onResult: (String?) -> Unit) = viewModelScope.launch {
        safely(onResult) { writer.deleteCourse(course.id, course.version) }
    }

    private fun build(snapshot: DataSnapshot, requestedWeek: Int?, at: LocalDateTime): CourseUiState {
        val state = service.project(snapshot, at)
        val semester = state.semester
        val weekCount = semester?.weekCount ?: 0
        val week = (requestedWeek ?: state.weekNo ?: 1).coerceIn(1, maxOf(1, weekCount))
        val timetable = if (semester == null) WeekTimetable(week, emptyMap()) else service.timetable(snapshot, week)
        val conflicts = if (semester == null) emptyList() else service.conflicts(snapshot, week)
        val courseSummaries = snapshot.courses
            .filter { semester != null && it.semesterId == semester.id }
            .map { course ->
                CourseSummary(
                    id = course.id,
                    title = course.title,
                    teacher = course.teacher,
                    version = course.version,
                    schedules = snapshot.schedules
                        .filter { it.courseId == course.id }
                        .map { schedule ->
                            ScheduleDraft(
                                dayOfWeek = schedule.dayOfWeek,
                                startPeriod = schedule.startPeriod,
                                endPeriod = schedule.endPeriod,
                                weeks = schedule.weeks,
                                location = schedule.location,
                                teacher = schedule.teacher,
                            )
                        },
                )
            }
        return CourseUiState(
            loading = false,
            phase = state.phase,
            semesterId = semester?.id,
            semesterName = semester?.name.orEmpty(),
            semesterVersion = snapshot.semesters.firstOrNull { it.id == semester?.id }?.version,
            weekCount = weekCount,
            currentWeek = state.weekNo,
            selectedWeek = week,
            weekLabel = weekLabel(snapshot, semester, week),
            days = timetable.days,
            conflicts = conflicts,
            courses = courseSummaries,
        )
    }

    private fun weekLabel(snapshot: DataSnapshot, semester: SemesterInfo?, week: Int): String {
        if (semester == null) return ""
        val record = snapshot.semesters.firstOrNull { it.id == semester.id } ?: return ""
        val range = WeekCalculator.weekRange(record, week) ?: return ""
        val start = range.start
        val end = range.endInclusive
        return "${start.monthValue} 月 ${start.dayOfMonth} 日 - ${end.monthValue} 月 ${end.dayOfMonth} 日"
    }

    private suspend fun safely(onResult: (String?) -> Unit, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: DataException) {
            onResult(mapError(error.code))
            return
        } catch (_: Exception) {
            onResult(WRITE_FAILED)
            return
        }
        onResult(null)
    }

    private fun mapError(code: DataError): String = when (code) {
        DataError.VERSION_CONFLICT -> "内容已被修改，请刷新后重试"
        DataError.COURSE_NOT_FOUND -> "课程不存在，请刷新后重试"
        DataError.INVALID_RECORD -> "数据格式不正确，请检查后重试"
        DataError.TASK_NOT_FOUND -> "任务不存在，请刷新后重试"
        DataError.EMPTY_TITLE -> "请填写课程名称"
        DataError.TITLE_TOO_LONG -> "课程名称过长（≤60 字）"
        DataError.REQUEST_ID_REUSED -> "请求已提交过，请勿重复操作"
        DataError.SYNC_IDENTITY_CONFLICT -> "同步数据冲突，请重新同步"
    }

    private companion object {
        const val DEFAULT_SEMESTER_ID = "semester-default"
        const val WRITE_FAILED = "保存失败，请重试"
        const val ERROR_WEEK_COUNT = "学期周数需在 1—64 之间"
    }
}
