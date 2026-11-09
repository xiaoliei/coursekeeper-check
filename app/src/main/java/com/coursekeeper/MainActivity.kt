package com.coursekeeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursekeeper.data.CourseKeeperData
import com.coursekeeper.demo.DemoHomeGateway
import com.coursekeeper.home.*
import com.coursekeeper.task.RepositoryTaskGateway
import com.coursekeeper.task.TaskEvent
import com.coursekeeper.task.TaskScreen
import com.coursekeeper.task.TaskViewModel
import com.coursekeeper.ui.AppShell
import com.coursekeeper.ui.theme.CourseKeeperTheme
import kotlinx.coroutines.launch

// Demo owner survives rotation. Replacing this composition root is the team integration point.
class DemoOwner : ViewModel() { val gateway = DemoHomeGateway() }
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CourseKeeperTheme {
                val owner: DemoOwner = viewModel()
                val model: HomeViewModel = viewModel(factory=remember(owner) {
                    viewModelFactory { initializer { HomeViewModel(owner.gateway) } }
                })
                val state by model.state.collectAsStateWithLifecycle()
                val snackbar=remember {SnackbarHostState()}

                // D：任务页接 E 的真实数据层（Room + Repository），不再经过演示网关。
                val data = remember { CourseKeeperData.get(applicationContext) }
                val taskModel: TaskViewModel = viewModel(factory=remember(data) {
                    viewModelFactory { initializer { TaskViewModel(RepositoryTaskGateway(data.repository)) } }
                })
                val taskState by taskModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(taskModel) {
                    taskModel.events.collect { event -> launch {
                        snackbar.currentSnackbarData?.dismiss()
                        when(event) {
                            is TaskEvent.Saved -> snackbar.showSnackbar("任务已保存")
                            is TaskEvent.Deleted -> snackbar.showSnackbar("任务已删除")
                            is TaskEvent.Message -> {
                                val result=snackbar.showSnackbar(event.text,actionLabel=event.undoTaskId?.let { "撤销" })
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
                                val result=snackbar.showSnackbar(event.text,actionLabel=if(event.undoTaskId!=null) "撤销" else null)
                                if(result==SnackbarResult.ActionPerformed && event.undoTaskId!=null) model.undo(event.undoTaskId,event.undoCompleted)
                            }
                        }
                    } }
                }
                AppShell(state, {model.setCompleted(it)}, {model.addTask(it)},
                    onScenario={snackbar.currentSnackbarData?.dismiss();owner.gateway.setScenario(it)},
                    onSkipLogin=owner.gateway::skipLogin,onSimulateLogin=owner.gateway::simulateLogin,
                    snackbarHostState=snackbar,
                    taskScreen={ modifier -> TaskScreen(
                        state=taskState,
                        onStatusFilter=taskModel::setStatusFilter,
                        onTypeFilter=taskModel::setTypeFilter,
                        onCreate={ draft,onResult -> taskModel.create(draft,onResult=onResult) },
                        onUpdate={ view,draft,onResult -> taskModel.update(view,draft,onResult=onResult) },
                        onToggle=taskModel::toggle,
                        onDelete=taskModel::delete,
                        modifier=modifier,
                    ) })
            }
        }
    }
}
