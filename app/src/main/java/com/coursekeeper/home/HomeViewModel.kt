package com.coursekeeper.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface HomeEvent {
    data class Message(val text: String, val undoTaskId: String? = null, val undoCompleted: Boolean = false) : HomeEvent
    data object TaskAdded : HomeEvent
}
class HomeViewModel(private val gateway: HomeGateway) : ViewModel() {
    val state = gateway.state
    private val eventChannel = Channel<HomeEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    fun setCompleted(task: TaskUi) = viewModelScope.launch {
        safely {
            gateway.setTaskCompleted(task.id, !task.completed)
            eventChannel.send(HomeEvent.Message(if(task.completed) "已恢复为待办" else "任务已完成", task.id, task.completed))
        }
    }
    fun undo(id: String, completed: Boolean) = viewModelScope.launch { safely { gateway.setTaskCompleted(id, completed) } }
    fun addTask(task: NewTask) = viewModelScope.launch {
        safely { gateway.addTask(task); eventChannel.send(HomeEvent.TaskAdded) }
    }
    private suspend fun safely(block: suspend () -> Unit) {
        try { block() } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { eventChannel.send(HomeEvent.Message(error.message ?: "操作失败，请重试")) }
    }
}
