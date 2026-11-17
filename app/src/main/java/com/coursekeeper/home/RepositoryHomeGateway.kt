package com.coursekeeper.home

import com.coursekeeper.course.HomeCourseProjection
import com.coursekeeper.course.CourseProjector
import com.coursekeeper.data.CourseKeeperRepository
import com.coursekeeper.data.DataError
import com.coursekeeper.data.DataException
import com.coursekeeper.data.TaskDraft
import com.coursekeeper.data.WriteReceipt
import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.SyncModule
import com.coursekeeper.data.model.SyncRecord
import com.coursekeeper.data.model.SyncStatus as DataSyncStatus
import com.coursekeeper.data.model.TaskType
import com.coursekeeper.data.settings.AppSettings
import com.coursekeeper.data.settings.SettingsStore
import com.coursekeeper.data.sync.SyncSummary
import com.coursekeeper.data.sync.SyncFailure
import com.coursekeeper.statistics.StatisticsService
import com.coursekeeper.task.RepositoryTaskGateway
import com.coursekeeper.task.TaskGateway
import com.coursekeeper.task.label
import com.coursekeeper.task.sortedForDisplay
import com.coursekeeper.task.toView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    val course = HomeCourseProjection.project(snapshot, now)
    val week = CourseProjector.weekOf(snapshot, today)
    // F 的纯计算使用本次相同快照和时刻；已有分钟 tick 也会驱动压力重算。
    val statistics = StatisticsService.compute(snapshot, CourseProjector.summary(snapshot, now), now)
    val summary = SyncSummary.from(snapshot.syncRecords)
    val status = summary.status.homeStatus()
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
        weekLabel = when (course.availability) {
            CourseAvailability.NoSemester -> "待设置学期"
            CourseAvailability.NotInTerm -> "非教学周"
            else -> week?.let { "第 $it 教学周" } ?: "教学周待确认"
        },
        greeting = when (now.hour) { in 0..10 -> "上午好，同学"; in 11..13 -> "中午好，同学"; in 14..17 -> "下午好，同学"; else -> "晚上好，同学" },
        todayCourseCount = course.todayCourseCount,
        weekCourseCount = course.weekCourseCount,
        courses = course.courses,
        nextCourse = course.nextCourse,
        nextCourseHint = course.nextCourseHint,
        status = status,
        todayDueCount = snapshot.tasks.count { it.dueAt?.toLocalDate() == today },
        todayCompletedCount = snapshot.tasks.count { it.completedAt?.toLocalDate() == today },
        tasks = snapshot.tasks.sortedForDisplay().map { task ->
            val view = task.toView(snapshot.courses, now)
            TaskUi(task.id, task.title, view.courseLabel, task.type.label, view.dueLabel,
                task.completedAt != null, task.dueAt?.toLocalDate() == today,
                task.completedAt?.toLocalDate() == today, task.version)
        },
        weekTaskCount = statistics.weekTaskCount, weekCompletedCount = statistics.weekCompletedCount,
        weekProgress = statistics.completionRate ?: 0f,
        // B's course projection distinguishes no semester, outside term, and a real zero-course week.
        courseAvailability = course.availability,
        pressureLabel = statistics.pressureScore?.let { "压力指数 $it · ${statistics.pressureLevel}" }
            ?: "暂无压力数据",
        pressureScore = statistics.pressureScore,
        pressureLevel = statistics.pressureLevel,
        pressureExplanation = if (statistics.pressureScore == null) "" else buildString {
            append(statistics.explanation)
            if (course.availability == CourseAvailability.NoSemester) append("；课程负荷待设置学期")
        },
        syncModules = summary.modules.map { it.toHomeModule() },
        lastSyncLabel = syncLabel,
        onboarding = !settings.onboardingComplete,
    )
}

private fun DataSyncStatus.homeStatus(): SyncStatus = when (this) {
    DataSyncStatus.IDLE -> SyncStatus.Ready
    DataSyncStatus.SYNCING -> SyncStatus.Syncing
    DataSyncStatus.OFFLINE -> SyncStatus.Offline
    DataSyncStatus.EXPIRED -> SyncStatus.Expired
    DataSyncStatus.PARTIAL -> SyncStatus.Partial
    DataSyncStatus.ERROR -> SyncStatus.Error
}

private fun SyncRecord.toHomeModule(): HomeSyncModuleUi {
    val failure = status in setOf(DataSyncStatus.OFFLINE, DataSyncStatus.EXPIRED, DataSyncStatus.ERROR)
    return HomeSyncModuleUi(
        name = if (module == SyncModule.COURSES) "课表" else "作业",
        status = status.homeStatus(),
        statusLabel = when (status) {
            DataSyncStatus.IDLE -> if (lastSuccessAt == null) "尚未成功同步" else "已同步"
            DataSyncStatus.SYNCING -> "同步中"
            DataSyncStatus.OFFLINE -> "离线"
            DataSyncStatus.EXPIRED -> "登录已过期"
            DataSyncStatus.PARTIAL -> "部分成功"
            DataSyncStatus.ERROR -> "同步失败"
        },
        lastSuccessLabel = lastSuccessAt?.atZone(ZoneId.systemDefault())
            ?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))?.let { "最近成功：$it" }
            ?: "尚未成功同步",
        errorMessage = if (!failure) null else when (errorCode) {
            SyncFailure.OFFLINE.name -> "网络不可用，已有数据可继续使用"
            SyncFailure.EXPIRED.name -> "请重新登录学校账号"
            SyncFailure.TIMEOUT.name -> "连接超时，已有数据已保留"
            SyncFailure.PARSE_ERROR.name -> "学校页面解析失败，已有数据已保留"
            SyncFailure.REMOTE_ERROR.name -> "学校服务暂不可用"
            SyncFailure.VERSION_CONFLICT.name -> "本地数据已被修改，本次同步未完成"
            SyncFailure.INVALID_DATA.name -> "同步数据未通过校验"
            SyncFailure.INTERRUPTED.name -> "上次同步已中断"
            else -> when (status) {
                DataSyncStatus.OFFLINE -> "网络不可用，已有数据可继续使用"
                DataSyncStatus.EXPIRED -> "请重新登录学校账号"
                else -> "同步暂未完成，已有数据可继续使用"
            }
        },
    )
}
