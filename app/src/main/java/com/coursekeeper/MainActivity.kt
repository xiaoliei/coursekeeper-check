package com.coursekeeper

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursekeeper.course.CourseScreen
import com.coursekeeper.course.CourseViewModel
import com.coursekeeper.course.RepositoryCourseService
import com.coursekeeper.data.CourseKeeperData
import com.coursekeeper.data.model.SyncModule
import com.coursekeeper.data.model.SyncRecord
import com.coursekeeper.data.model.SyncStatus
import com.coursekeeper.data.settings.AppSettings
import com.coursekeeper.data.sync.SyncFailure
import com.coursekeeper.data.sync.SyncRunResult
import com.coursekeeper.home.*
import com.coursekeeper.reminder.AlarmReminderScheduler
import com.coursekeeper.reminder.BootStateStore
import com.coursekeeper.reminder.ReminderCoordinator
import com.coursekeeper.reminder.TaskNotifications
import com.coursekeeper.reminder.shouldShowBootHint
import com.coursekeeper.statistics.StatisticsViewModel
import com.coursekeeper.task.RepositoryTaskGateway
import com.coursekeeper.task.TaskEvent
import com.coursekeeper.task.TaskScreen
import com.coursekeeper.task.TaskViewModel
import com.coursekeeper.task.TaskQuery
import com.coursekeeper.task.TaskStatusFilter
import com.coursekeeper.task.TaskDateFilter
import com.coursekeeper.school.SchoolAdapterHolder
import com.coursekeeper.school.SchoolCatalog
import com.coursekeeper.school.SchoolLoginController
import com.coursekeeper.school.SchoolLoginSheet
import com.coursekeeper.school.SchoolLogout
import com.coursekeeper.ui.AppShell
import com.coursekeeper.ui.StatisticsScreen
import com.coursekeeper.ui.theme.CourseKeeperTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One application data source for Home and D's task page. */
class HomeDataOwner(application: Application) : AndroidViewModel(application) {
    val data = CourseKeeperData.get(application)
    val tasks = RepositoryTaskGateway(data.repository)
    val gateway = RepositoryHomeGateway(data.repository, data.settings, viewModelScope, taskGateway=tasks)
    // F：看板数据入口，订阅 E 的一致性快照实时计算统计结果。
    // B：课程业务，读侧投影 + 写侧委托 Repository。
    val courseService = RepositoryCourseService(data.repository)
    val statistics = StatisticsViewModel(data.repository, courseStatsSource = courseService, scope = viewModelScope)
    // C：学校适配器（E 约定应用级同一实例交给协调器）与登录流程控制器。
    // 适配器经 SchoolAdapterHolder 按进程持有单例：ViewModel 会随返回键退出销毁，
    // 每次新建实例会撞上数据层的 require（D 复核：启动崩溃）。
    val schoolAdapter = SchoolAdapterHolder.get(application)
    val sync = data.syncCoordinator(schoolAdapter)
    val loginController = SchoolLoginController(afterLogin = {
        data.settings.selectSchool(SchoolCatalog.default.id)
        data.settings.completeOnboarding()
        val result = sync.synchronize(force = true)
        // 后台同步失败不能谎报“导入完成”（与首页的过期/错误横幅矛盾，D 复核①）：
        // 有模块失败时向上抛，让登录状态机进入 Failure 并保留重试入口。
        // 判定逻辑抽成纯函数，便于覆盖 InProgress / SchoolMismatch 等分支（D 复核②）。
        afterLoginFailureMessage(result)?.let { error(it) }
    })
}

/**
 * 登录后强制同步的结果判定：返回要展示的失败文案，成功返回 null。
 *
 * 子代理复核补充：InProgress / SchoolMismatch 也不能静默当成功——前者是
 * force 同步根本没跑（撞上另一次持锁），后者意味着数据源与缓存不一致。
 */
internal fun afterLoginFailureMessage(result: SyncRunResult): String? = when (result) {
    is SyncRunResult.Finished -> {
        val failed = result.summary.modules.filter {
            it.status == SyncStatus.OFFLINE ||
                it.status == SyncStatus.EXPIRED ||
                it.status == SyncStatus.ERROR
        }
        if (failed.isEmpty()) null else syncFailureText(failed)
    }
    SyncRunResult.InProgress -> "上一次同步还在进行中，本次结果未确认。稍后可在首页查看或重试。"
    // 退出登录只清 Cookie，不清 sync_records.schoolId（E 的数据层口径），
    // 所以「退出后重连」并不能解除不一致；提示改为可执行的清除数据（D 复核③）。
    SyncRunResult.SchoolMismatch ->
        "本机同步记录属于其他学校。当前版本仅支持汕头大学，请清除本机应用数据后重新连接。"
}

/** 同步模块失败时的人话描述：登录完成页据此提示真实结果，而不是谎报“导入完成”。 */
internal fun syncFailureText(failed: List<SyncRecord>): String =
    "同步未完成：" + failed.joinToString("；") { record ->
        val module = when (record.module) {
            SyncModule.COURSES -> "课表"
            SyncModule.TASKS -> "作业"
        }
        val reason = when (record.errorCode) {
            SyncFailure.OFFLINE.name -> "网络不可用"
            SyncFailure.EXPIRED.name -> "登录已过期"
            SyncFailure.TIMEOUT.name -> "连接超时"
            SyncFailure.PARSE_ERROR.name -> "页面解析失败"
            SyncFailure.REMOTE_ERROR.name -> "学校服务暂不可用"
            SyncFailure.VERSION_CONFLICT.name -> "本地数据冲突"
            SyncFailure.INVALID_DATA.name -> "数据校验未通过"
            SyncFailure.INTERRUPTED.name -> "同步被中断"
            else -> "未知原因"
        }
        "$module：$reason"
    } + "。稍后可在首页重试。"
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CourseKeeperTheme {
                val owner: HomeDataOwner = viewModel()
                // 退出登录要写 DataStore，用一个随界面销毁的协程作用域。
                val scope = rememberCoroutineScope()
                val model: HomeViewModel = viewModel(factory=remember(owner) {
                    viewModelFactory { initializer { HomeViewModel(owner.gateway) } }
                })
                val state by model.state.collectAsStateWithLifecycle()
                val snackbar=remember {SnackbarHostState()}
                val showReservedEntry: (String) -> Unit = { label ->
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(
                            "$label 入口已预留，待导入格式确认后接入",
                            duration=SnackbarDuration.Short,
                        )
                    }
                }
                val statisticsState by owner.statistics.uiState.collectAsStateWithLifecycle()

                // D：任务页接 E 的真实数据层（Room + Repository），不再经过演示网关。
                val data = owner.data
                var createRequested by rememberSaveable { mutableStateOf(false) }
                val taskModel: TaskViewModel = viewModel(factory=remember(data) {
                    viewModelFactory { initializer { TaskViewModel(owner.tasks) } }
                })
                val taskState by taskModel.uiState.collectAsStateWithLifecycle()

                // B：课表页，读 E 的快照、写走 Repository。
                val courseModel: CourseViewModel = viewModel(factory=remember(data) {
                    viewModelFactory { initializer {
                        CourseViewModel(owner.courseService, owner.courseService, data.repository.snapshots)
                    } }
                })
                val courseState by courseModel.uiState.collectAsStateWithLifecycle()

                // C：缓存展示后启动后台会话检测与同步（E：onForeground 延迟 400ms 检测会话）。
                // 已完成引导并选择学校后才执行，首次启动不发网络请求；挂起等待首次登录完成。
                LaunchedEffect(data) {
                    data.settings.settings.first { it.onboardingComplete && it.schoolId != null }
                    owner.sync.onForeground()
                }
                val appSettings by data.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

                // D 第 3 周：提醒对账随数据快照刷新；数据层首次出现带截止时间的任务（任何入口，
                // 含首页快速添加）后申请一次通知权限（v1.0 §四）。
                val reminderScheduler = remember { AlarmReminderScheduler(applicationContext) }
                var notifPermissionAsked by rememberSaveable { mutableStateOf(false) }
                val notifPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }
                LaunchedEffect(data, reminderScheduler) {
                    TaskNotifications.ensureChannel(applicationContext)
                    ReminderCoordinator.attach(data.repository.snapshots, reminderScheduler, this) {
                        if (!notifPermissionAsked) {
                            notifPermissionAsked = true
                            if (Build.VERSION.SDK_INT >= 33) {
                                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    }
                }

                // W5 开机自启引导：重启被 ROM 拦截（BootReceiver 没跑）时，任务页提示一次。
                // 展示即持久化"本次开机已提示"，同一次开机内重进不重复；
                // 允许自启动后重启，接收器跑过会刷新标记，不再出现。
                var showBootHint by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    val ctx = applicationContext
                    val bootTime = BootStateStore.currentBootTimeWall()
                    val lastHandled = BootStateStore.lastBootHandledWall(ctx)
                    if (lastHandled == 0L) {
                        // 干净安装、接收器从没跑过：先补基线，下一次被拦的重启才有参照。
                        BootStateStore.markBaseline(ctx)
                    } else if (shouldShowBootHint(
                            lastBootHandledWall = lastHandled,
                            bootTimeWall = bootTime,
                            hasPendingReminder = BootStateStore.hasPendingReminder(ctx),
                            shownThisBoot = BootStateStore.bootHintShownWall(ctx) >= bootTime,
                        )
                    ) {
                        BootStateStore.markBootHintShown(ctx, bootTime)
                        showBootHint = true
                    }
                }
                // W5 二轮：进入批量选择模式时收起当前提示——带"撤销"的 Snackbar 会浮在
                // 底部操作条（删除所选）上方盖住它；选择模式一旦开始，提示即为过时信息。
                LaunchedEffect(taskState.selectedIds) {
                    if (taskState.selectedIds.isNotEmpty()) snackbar.currentSnackbarData?.dismiss()
                }
                LaunchedEffect(taskModel) {
                    taskModel.events.collect { event -> launch {
                        snackbar.currentSnackbarData?.dismiss()
                        when(event) {
                            is TaskEvent.Saved -> snackbar.showSnackbar("任务已保存")
                            is TaskEvent.Deleted -> snackbar.showSnackbar("任务已删除")
                            is TaskEvent.Message -> {
                                // 带操作按钮时 Material3 默认 Indefinite（永不自动消失），必须显式给时长；
                                // 同时开 withDismissAction，让用户能关掉而不是被迫点"撤销"。
                                val result=snackbar.showSnackbar(
                                    event.text,
                                    actionLabel=event.undoTaskId?.let { "撤销" },
                                    withDismissAction=event.undoTaskId!=null,
                                    duration=if(event.undoTaskId!=null) SnackbarDuration.Long else SnackbarDuration.Short,
                                )
                                if(result==SnackbarResult.ActionPerformed && event.undoTaskId!=null && event.undoVersion!=null) {
                                    taskModel.undo(event.undoTaskId,event.undoCompleted,event.undoVersion)
                                }
                            }
                        }
                    } }
                }
                LaunchedEffect(model) {
                    model.events.collect { event -> launch {
                        snackbar.currentSnackbarData?.dismiss()
                        when(event) {
                            HomeEvent.TaskAdded -> snackbar.showSnackbar("任务已加入清单")
                            is HomeEvent.Message -> {
                                val result=snackbar.showSnackbar(
                                    event.text,
                                    actionLabel=if(event.undoTaskId!=null) "撤销" else null,
                                    withDismissAction=event.undoTaskId!=null,
                                    duration=if(event.undoTaskId!=null) SnackbarDuration.Long else SnackbarDuration.Short,
                                )
                                if(result==SnackbarResult.ActionPerformed && event.undoTaskId!=null && event.undoVersion!=null) model.undo(event.undoTaskId,event.undoCompleted,event.undoVersion)
                            }
                        }
                    } }
                }
                AppShell(state, {model.setCompleted(it)}, {model.addTask(it)},
                    onScenario={},
                    onSkipLogin={model.skipLogin()},onSimulateLogin={},
                    snackbarHostState=snackbar,
                    onTaskFilter={ filter -> taskModel.showQuery(when(filter) {
                        TaskFilter.Today -> TaskQuery(status=TaskStatusFilter.ALL,date=TaskDateFilter.DUE_TODAY)
                        TaskFilter.CompletedToday -> TaskQuery(status=TaskStatusFilter.DONE,date=TaskDateFilter.COMPLETED_TODAY)
                        TaskFilter.Completed -> TaskQuery(status=TaskStatusFilter.DONE)
                        TaskFilter.All -> TaskQuery(status=TaskStatusFilter.ALL)
                        TaskFilter.Pending -> TaskQuery()
                    }) },
                    onCreateTask={createRequested=true},
                    onRetryHome=model::retry,
                    onImportExcel={showReservedEntry("手动导入 Excel")},
                    onPasteCourseNotice={showReservedEntry("粘贴课程通知")},
                    taskScreen={ modifier -> TaskScreen(
                        state=taskState,
                        onStatusFilter=taskModel::setStatusFilter,
                        onTypeFilter=taskModel::setTypeFilter,
                        onCreate={ draft,onResult -> taskModel.create(draft,onResult=onResult) },
                        onUpdate={ view,draft,onResult -> taskModel.update(view,draft,onResult=onResult) },
                        onToggle=taskModel::toggle,
                        onDelete=taskModel::delete,
                        modifier=modifier,
                        createRequested=createRequested,
                        onCreateRequestConsumed={createRequested=false},
                        onClearDateFilter=taskModel::clearDateFilter,
                        bootReminderHint=showBootHint,
                        onDismissBootReminderHint={ showBootHint=false },
                        onSelectAll=taskModel::toggleSelectAll,
                        onToggleSelect=taskModel::toggleSelect,
                        onExitSelection=taskModel::exitSelection,
                        onDeleteSelected=taskModel::deleteSelected,
                        onOpenAutoStartSettings={
                            // 系统应用详情页：所有 ROM 都有"允许自启动"类开关的入口（荣耀在
                            // 「应用启动管理」），不猜 OEM 专属 Activity，避免深链失效。
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", packageName, null))
                            )
                        },
                    ) },
                    coursesScreen={ modifier -> CourseScreen(
                        state=courseState,
                        onSelectTab=courseModel::selectTab,
                        onPreviousWeek=courseModel::previousWeek,
                        onNextWeek=courseModel::nextWeek,
                        onSaveSemester=courseModel::configureSemester,
                        onSaveCourse=courseModel::saveCourse,
                        onDeleteCourse=courseModel::deleteCourse,
                        modifier=modifier,
                    ) },
                    statisticsScreen={ modifier -> StatisticsScreen(statisticsState, modifier) { owner.statistics.retry() } },
                    // C：登录弹层交给 C 的实现；弹层关闭即回到原页面，已有数据不受影响。
                    loginSheet={ onDismiss -> SchoolLoginSheet(
                        controller=owner.loginController,
                        savedSchool=if (appSettings.schoolId != null) SchoolCatalog.default else null,
                        // 整体部分成功但某模块已过期时，同样直达已记住学校的登录页。
                        autoLogin = state.hasExpiredSession,
                        onLogout = {
                            // 清会话 Cookie + 取消记住的学校；本地课程任务由 E 保留，不删库。
                            SchoolLogout.clear()
                            owner.loginController.reset()
                            scope.launch { data.settings.selectSchool(null) }
                        },
                        onDismiss=onDismiss,
                    ) })
            }
        }
    }
}
