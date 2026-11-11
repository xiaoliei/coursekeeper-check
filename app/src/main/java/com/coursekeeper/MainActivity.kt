package com.coursekeeper

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.app.Application
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursekeeper.data.CourseKeeperData
import com.coursekeeper.home.*
import com.coursekeeper.reminder.AlarmReminderScheduler
import com.coursekeeper.reminder.ReminderCoordinator
import com.coursekeeper.reminder.TaskNotifications
import com.coursekeeper.statistics.StatisticsViewModel
import com.coursekeeper.task.RepositoryTaskGateway
import com.coursekeeper.task.TaskBottomButtonHeight
import com.coursekeeper.task.TaskCompactHeightDp
import com.coursekeeper.task.TaskEvent
import com.coursekeeper.task.TaskScreen
import com.coursekeeper.task.TaskViewModel
import com.coursekeeper.task.TaskQuery
import com.coursekeeper.task.TaskStatusFilter
import com.coursekeeper.task.TaskDateFilter
import com.coursekeeper.ui.AppShell
import com.coursekeeper.ui.StatisticsScreen
import com.coursekeeper.ui.theme.CourseKeeperTheme
import kotlinx.coroutines.launch

/** One application data source for Home and D's task page. */
class HomeDataOwner(application: Application) : AndroidViewModel(application) {
    val data = CourseKeeperData.get(application)
    val tasks = RepositoryTaskGateway(data.repository)
    val gateway = RepositoryHomeGateway(data.repository, data.settings, viewModelScope, taskGateway=tasks)
    // F：看板数据入口，订阅 E 的一致性快照实时计算统计结果。
    val statistics = StatisticsViewModel(data.repository, scope = viewModelScope)
}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CourseKeeperTheme {
                val owner: HomeDataOwner = viewModel()
                val model: HomeViewModel = viewModel(factory=remember(owner) {
                    viewModelFactory { initializer { HomeViewModel(owner.gateway) } }
                })
                val state by model.state.collectAsStateWithLifecycle()
                val snackbar=remember {SnackbarHostState()}
                val statisticsState by owner.statistics.uiState.collectAsStateWithLifecycle()

                // D：任务页接 E 的真实数据层（Room + Repository），不再经过演示网关。
                val data = owner.data
                var createRequested by rememberSaveable { mutableStateOf(false) }
                val taskModel: TaskViewModel = viewModel(factory=remember(data) {
                    viewModelFactory { initializer { TaskViewModel(owner.tasks) } }
                })
                val taskState by taskModel.uiState.collectAsStateWithLifecycle()

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
                    // 任务页自带的"＋ 新建任务"会挡住提示，把提示抬高这个高度；
                    // 紧凑模式下按钮并入头部，不需要留位。
                    snackbarBottomInset=if (
                        LocalConfiguration.current.screenHeightDp >= TaskCompactHeightDp
                    ) TaskBottomButtonHeight else 0.dp,
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
                    ) },
                    statisticsScreen={ modifier -> StatisticsScreen(statisticsState, modifier) })
            }
        }
    }
}
