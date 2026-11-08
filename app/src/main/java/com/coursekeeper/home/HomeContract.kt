package com.coursekeeper.home

import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** A's UI contract. Production adapters aggregate B/D/E/F services behind this interface. */
interface HomeGateway {
    val state: StateFlow<HomeUiState>
    suspend fun setTaskCompleted(id: String, completed: Boolean)
    suspend fun addTask(task: NewTask)
}

enum class SyncStatus { Ready, Offline, Expired, Partial }
data class CourseUi(val id: String, val title: String, val time: String, val location: String, val periods: String)
data class TaskUi(
    val id: String, val title: String, val course: String, val type: String,
    val dueLabel: String, val completed: Boolean, val dueToday: Boolean, val completedToday: Boolean,
)
data class HomeUiState(
    val dateLabel: String = "", val weekLabel: String = "", val status: SyncStatus = SyncStatus.Ready,
    val todayCourseCount: Int = 0, val todayDueCount: Int = 0, val todayCompletedCount: Int = 0,
    val courses: List<CourseUi> = emptyList(), val nextCourse: CourseUi? = null,
    val nextCourseHint: String = "", val tasks: List<TaskUi> = emptyList(),
    val weekTaskCount: Int = 0, val weekCompletedCount: Int = 0, val weekProgress: Float = 0f,
    val weekCourseCount: Int = 0, val pressureLabel: String = "暂无压力数据",
    val lastSyncLabel: String = "尚未同步", val onboarding: Boolean = false,
)
data class NewTask(val title: String, val course: String, val type: String, val deadline: LocalDateTime)
enum class TaskFilter(val label: String) { Pending("待办"), Completed("已完成"), All("全部"), Today("今日截止"), CompletedToday("今日完成") }
fun List<TaskUi>.filtered(filter: TaskFilter) = filter {
    when (filter) {
        TaskFilter.Pending -> !it.completed
        TaskFilter.Completed -> it.completed
        TaskFilter.All -> true
        TaskFilter.Today -> it.dueToday
        TaskFilter.CompletedToday -> it.completedToday
    }
}

/** Input feedback only; production D service must repeat authoritative business validation. */
fun parseNewTask(title: String, course: String, type: String, deadline: String): Result<NewTask> = runCatching {
    require(title.trim().isNotEmpty()) { "请填写任务名称" }
    require(title.trim().length <= 60) { "任务名称不能超过 60 个字" }
    val time = try {
        LocalDateTime.parse(deadline.trim(), DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT))
    } catch (_: Exception) { throw IllegalArgumentException("请填写有效的截止时间，例如 2026-11-09 23:59") }
    NewTask(title.trim(), course.trim(), type, time)
}
