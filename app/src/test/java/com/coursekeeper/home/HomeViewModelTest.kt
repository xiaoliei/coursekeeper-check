package com.coursekeeper.home

import com.coursekeeper.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher=UnconfinedTestDispatcher()
    @Before fun setup(){Dispatchers.setMain(dispatcher)}
    @After fun teardown(){Dispatchers.resetMain()}
    private class Gateway:HomeGateway {
        override val state=MutableStateFlow(HomeUiState())
        val calls=mutableListOf<Triple<String,Boolean,Long?>>()
        var failure:Exception?=null
        override suspend fun setTaskCompleted(id:String,completed:Boolean,expectedVersion:Long?):WriteReceipt {
            failure?.let{throw it};calls+=Triple(id,completed,expectedVersion)
            return WriteReceipt(id,9,true)
        }
        override suspend fun addTask(task:NewTask)=WriteReceipt("new",1,true)
    }
    @Test fun completionUsesShownVersionAndUndoUsesReceiptVersion()=runTest(dispatcher) {
        val gateway=Gateway();val model=HomeViewModel(gateway);val events=mutableListOf<HomeEvent>()
        backgroundScope.launch{model.events.collect{events+=it}}
        model.setCompleted(TaskUi("t","任务","","课程作业","截止待定",false,false,false,version=4))
        val event=events.single() as HomeEvent.Message
        assertEquals(4L,gateway.calls.single().third)
        assertEquals(9L,event.undoVersion)
        model.undo(event.undoTaskId!!,event.undoCompleted,event.undoVersion!!)
        assertEquals(Triple("t",false,9L),gateway.calls.last())
    }
    @Test fun versionConflictHasNoUndoAndGenericErrorsDoNotLeakInternals()=runTest(dispatcher) {
        val gateway=Gateway();val model=HomeViewModel(gateway);val events=mutableListOf<HomeEvent>()
        backgroundScope.launch{model.events.collect{events+=it}}
        val task=TaskUi("t","任务","","课程作业","截止待定",false,false,false)
        gateway.failure=DataException(DataError.VERSION_CONFLICT);model.setCompleted(task)
        val conflict=events.last() as HomeEvent.Message
        assertEquals(DataError.VERSION_CONFLICT.userMessage,conflict.text)
        assertNull(conflict.undoTaskId)
        gateway.failure=IllegalStateException("SQL internal details");model.setCompleted(task)
        assertEquals("操作失败，请重试",(events.last() as HomeEvent.Message).text)
    }
}
