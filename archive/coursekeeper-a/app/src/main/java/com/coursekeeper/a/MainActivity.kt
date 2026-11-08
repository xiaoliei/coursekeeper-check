package com.coursekeeper.a

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
import com.coursekeeper.a.demo.DemoHomeGateway
import com.coursekeeper.a.home.*
import com.coursekeeper.a.ui.AppShell
import com.coursekeeper.a.ui.theme.CourseKeeperTheme
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
                    snackbarHostState=snackbar)
            }
        }
    }
}
