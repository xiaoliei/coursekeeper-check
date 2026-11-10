package com.coursekeeper.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursekeeper.data.DataException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface HomeEvent {
    data class Message(val text: String, val undoTaskId: String? = null, val undoCompleted: Boolean = false, val undoVersion: Long? = null) : HomeEvent
    data object TaskAdded : HomeEvent
}
class HomeViewModel(private val gateway: HomeGateway) : ViewModel() {
    val state = gateway.state
    private val eventChannel = Channel<HomeEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    fun setCompleted(task: TaskUi) = viewModelScope.launch {
        safely {
            val receipt = gateway.setTaskCompleted(task.id, !task.completed, task.version)
            if (receipt.changed) eventChannel.send(HomeEvent.Message(if(task.completed) "已恢复为待办" else "任务已完成", task.id, task.completed, receipt.version))
        }
    }
    fun undo(id: String, completed: Boolean, expectedVersion: Long) = viewModelScope.launch { safely { gateway.setTaskCompleted(id, completed, expectedVersion) } }
    fun skipLogin() = viewModelScope.launch { safely { gateway.completeOnboarding() } }
    fun retry() = gateway.retry()
    fun addTask(task: NewTask) = viewModelScope.launch {
        safely { gateway.addTask(task); eventChannel.send(HomeEvent.TaskAdded) }
    }
    private suspend fun safely(block: suspend () -> Unit) {
        try { block() } catch (cancel: CancellationException) { throw cancel }
        catch (error: DataException) { eventChannel.send(HomeEvent.Message(error.code.userMessage)) }
        catch (_: Exception) { eventChannel.send(HomeEvent.Message("操作失败，请重试")) }
    }
}
