package com.coursekeeper.home

import com.coursekeeper.data.CourseKeeperRepository
import com.coursekeeper.data.DataError
import com.coursekeeper.data.DataException
import com.coursekeeper.data.TaskDraft
import com.coursekeeper.data.WriteReceipt
import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.SyncStatus as DataSyncStatus
import com.coursekeeper.data.model.TaskType
import com.coursekeeper.data.settings.AppSettings
import com.coursekeeper.data.settings.SettingsStore
import com.coursekeeper.data.sync.SyncSummary
import com.coursekeeper.task.RepositoryTaskGateway
import com.coursekeeper.task.TaskGateway
import com.coursekeeper.task.label
import com.coursekeeper.task.sortedForDisplay
import com.coursekeeper.task.toView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** A's projection over E's consistent snapshot; task mutations reuse D's gateway. */
@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryHomeGateway(
    private val repository: CourseKeeperRepository,
    private val settings: SettingsStore,
    scope: CoroutineScope,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    ticks: Flow<Unit> = flow { while (true) { emit(Unit); delay(60_000) } },
    private val taskGateway: TaskGateway = RepositoryTaskGateway(repository),
) : HomeGateway {
    private val reload = MutableStateFlow(0)
    override val state = reload.flatMapLatest {
        combine(repository.snapshots, settings.settings, ticks) { snapshot, preferences, _ ->
            projectHome(snapshot, preferences, now())
        }.catch { emit(HomeUiState(isDemo = false, readError = "本地数据读取失败，请重试")) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), HomeUiState(isDemo = false, loading = true))

    override fun retry() { reload.value += 1 }
    override suspend fun completeOnboarding() { settings.completeOnboarding() }
    override suspend fun setTaskCompleted(id: String, completed: Boolean, expectedVersion: Long?): WriteReceipt {
        val version = expectedVersion ?: throw DataException(DataError.VERSION_CONFLICT)
        return taskGateway.setCompleted(id, completed, version)
    }
    override suspend fun addTask(task: NewTask): WriteReceipt {
        val type = TaskType.entries.firstOrNull { it.label == task.type || it.name == task.type }
            ?: throw DataException(DataError.INVALID_RECORD)
        // Display names are not stable course identifiers. Never guess a courseId from its title.
        if (task.course.isNotBlank() && task.courseId == null) throw DataException(DataError.COURSE_NOT_FOUND)
        return taskGateway.create(TaskDraft(task.title, type, task.deadline, task.courseId))
    }
}

internal fun projectHome(snapshot: DataSnapshot, settings: AppSettings, now: LocalDateTime): HomeUiState {
    val today = now.toLocalDate()
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val nextMonday = monday.plusWeeks(1)
    val weekTasks = snapshot.tasks.filter { task ->
        task.dueAt?.toLocalDate()?.let { !it.isBefore(monday) && it.isBefore(nextMonday) } == true
    }
    val completed = weekTasks.count { it.completedAt != null }
    val summary = SyncSummary.from(snapshot.syncRecords)
    val status = when (summary.status) {
        DataSyncStatus.IDLE -> SyncStatus.Ready
        DataSyncStatus.SYNCING -> SyncStatus.Syncing
        DataSyncStatus.OFFLINE -> SyncStatus.Offline
        DataSyncStatus.EXPIRED -> SyncStatus.Expired
        DataSyncStatus.PARTIAL -> SyncStatus.Partial
        DataSyncStatus.ERROR -> SyncStatus.Error
    }
    val syncLabel = if (summary.modules.none { it.lastSuccessAt != null }) "尚未成功同步" else
        summary.modules.joinToString(" · ") { module ->
            val name = if (module.module == com.coursekeeper.data.model.SyncModule.COURSES) "课表" else "作业"
            val time = module.lastSuccessAt?.atZone(ZoneId.systemDefault())
                ?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) ?: "尚未成功"
            "$name $time"
        }
    return HomeUiState(
        isDemo = false,
        dateLabel = "${today.monthValue} 月 ${today.dayOfMonth} 日 · 星期${"一二三四五六日"[today.dayOfWeek.value - 1]}",
        weekLabel = if (snapshot.semesters.none { it.startsOn != null }) "待设置学期" else "学期已设置",
        greeting = when (now.hour) { in 0..10 -> "上午好，同学"; in 11..13 -> "中午好，同学"; in 14..17 -> "下午好，同学"; else -> "晚上好，同学" },
        status = status,
        todayDueCount = snapshot.tasks.count { it.dueAt?.toLocalDate() == today },
        todayCompletedCount = snapshot.tasks.count { it.completedAt?.toLocalDate() == today },
        tasks = snapshot.tasks.sortedForDisplay().map { task ->
            val view = task.toView(snapshot.courses, now)
            TaskUi(task.id, task.title, view.courseLabel, task.type.label, view.dueLabel,
                task.completedAt != null, task.dueAt?.toLocalDate() == today,
                task.completedAt?.toLocalDate() == today, task.version)
        },
        weekTaskCount = weekTasks.size, weekCompletedCount = completed,
        weekProgress = if (weekTasks.isEmpty()) 0f else completed.toFloat() / weekTasks.size,
        // B's CourseService and F's pressure service are pending. Never substitute fixture values.
        courseAvailability = if (snapshot.semesters.none { it.startsOn != null }) CourseAvailability.NoSemester else CourseAvailability.Unavailable,
        pressureLabel = "暂无压力数据", lastSyncLabel = syncLabel,
        onboarding = !settings.onboardingComplete,
    )
}
