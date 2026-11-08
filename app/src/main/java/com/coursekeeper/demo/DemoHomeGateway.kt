package com.coursekeeper.demo

import com.coursekeeper.home.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class DemoScenario(val label: String) { Normal("正常使用"), Offline("离线"), Expired("登录过期"), Partial("部分成功"), Empty("空数据"), FirstLaunch("首次启动") }

/** In-memory fixtures only. Never connect this demonstration implementation to production data. */
class DemoHomeGateway : HomeGateway {
    private data class Task(val id: String, val title: String, val course: String, val type: String,
        val deadline: LocalDateTime, val completedAt: LocalDateTime? = null)
    private val now = LocalDateTime.of(2026, 11, 9, 9, 50)
    private var scenario = DemoScenario.Normal
    private var tasks = initialTasks()
    private val mutableState = MutableStateFlow(project())
    override val state = mutableState.asStateFlow()

    fun setScenario(value: DemoScenario) {
        scenario = value
        tasks = if (value in listOf(DemoScenario.Empty, DemoScenario.FirstLaunch)) emptyList() else initialTasks()
        publish()
    }
    fun skipLogin() { scenario = DemoScenario.Empty; publish() }
    fun simulateLogin() {
        // Connecting the school must retain tasks created in manual mode.
        if (scenario in listOf(DemoScenario.Empty, DemoScenario.FirstLaunch)) {
            val existingIds = tasks.map { it.id }.toSet()
            tasks = initialTasks().filterNot { it.id in existingIds } + tasks
        }
        scenario = DemoScenario.Normal
        publish()
    }
    override suspend fun setTaskCompleted(id: String, completed: Boolean) {
        require(tasks.any { it.id == id }) { "任务不存在，请刷新后重试" }
        tasks = tasks.map { task ->
            if (task.id != id) task else task.copy(completedAt = if (completed) task.completedAt ?: now else null)
        }
        publish()
    }
    override suspend fun addTask(task: NewTask) {
        require(task.title.isNotBlank()) { "请填写任务名称" }
        require(task.title.length <= 60) { "任务名称过长" }
        tasks = tasks + Task(UUID.randomUUID().toString(), task.title, task.course, task.type, task.deadline)
        publish()
    }
    private fun publish() { mutableState.value = project() }
    private fun initialTasks() = listOf(
        Task("java", "Java 实验报告", "面向对象程序设计", "实验报告", now.toLocalDate().atTime(23, 59)),
        Task("database", "数据库设计作业", "数据库原理", "课程作业", now.plusDays(1).toLocalDate().atTime(18, 0)),
        Task("math", "高等数学习题", "高等数学", "课程作业", now.plusDays(2).toLocalDate().atTime(23, 59)),
        Task("english", "英语听力练习", "大学英语", "课程作业", now.toLocalDate().atTime(21, 0), now.minusHours(1)),
    )
    private fun project(): HomeUiState {
        val hasCourses = scenario !in listOf(DemoScenario.Empty, DemoScenario.FirstLaunch)
        val courses = if (hasCourses) listOf(
            CourseUi("math", "高等数学", "08:00 — 09:35", "理科楼 A205", "第 1–2 节"),
            CourseUi("java", "面向对象程序设计", "10:10 — 11:45", "教学楼 302", "第 3–4 节"),
            CourseUi("db", "数据库原理", "14:00 — 15:35", "实验楼 B206", "第 5–6 节"),
        ) else emptyList()
        val week = tasks.filter { !it.deadline.toLocalDate().isBefore(now.toLocalDate()) && it.deadline.toLocalDate().isBefore(now.toLocalDate().plusDays(7)) }
        val complete = week.count { it.completedAt != null }
        return HomeUiState(
            dateLabel = "11 月 9 日 · 星期一", weekLabel = "第 10 教学周",
            status = when (scenario) { DemoScenario.Offline -> SyncStatus.Offline; DemoScenario.Expired -> SyncStatus.Expired; DemoScenario.Partial -> SyncStatus.Partial; else -> SyncStatus.Ready },
            todayCourseCount = courses.size,
            todayDueCount = tasks.count { it.deadline.toLocalDate() == now.toLocalDate() },
            todayCompletedCount = tasks.count { it.completedAt?.toLocalDate() == now.toLocalDate() },
            courses = courses, nextCourse = courses.getOrNull(1), nextCourseHint = "20 分钟后",
            tasks = tasks.sortedBy { it.deadline }.map { t ->
                val date = when (t.deadline.toLocalDate()) {
                    now.toLocalDate() -> "今天"
                    now.plusDays(1).toLocalDate() -> "明天"
                    else -> t.deadline.format(DateTimeFormatter.ofPattern("MM/dd"))
                }
                TaskUi(t.id, t.title, t.course, t.type, (if(t.deadline.isBefore(now) && t.completedAt == null) "已逾期 · " else "") + date + " " + t.deadline.format(DateTimeFormatter.ofPattern("HH:mm")), t.completedAt != null,
                    t.deadline.toLocalDate() == now.toLocalDate(), t.completedAt?.toLocalDate() == now.toLocalDate())
            },
            weekTaskCount = week.size, weekCompletedCount = complete,
            weekProgress = if (week.isEmpty()) 0f else complete.toFloat() / week.size,
            weekCourseCount = if (hasCourses) 15 else 0,
            pressureLabel = if (hasCourses) "压力指数 40 · 适中（示例）" else "暂无压力数据",
            lastSyncLabel = if (hasCourses) "最近同步 09:45" else "手动模式 · 尚未同步",
            onboarding = scenario == DemoScenario.FirstLaunch,
        )
    }
}
