package com.coursekeeper.home

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.coursekeeper.data.*
import com.coursekeeper.data.model.TaskType
import com.coursekeeper.data.settings.SettingsStore
import com.coursekeeper.task.RepositoryTaskGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

class RepositoryHomeGatewayTest : DataTestBase() {
    @Test fun homeObservesTasksCreatedByDAndCompletionChangesFromBothDirections() = runTest {
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope=backgroundScope,
            produceFile={File(context.cacheDir,"home-${UUID.randomUUID()}.preferences_pb")}))
        val home = RepositoryHomeGateway(repository,settings,backgroundScope,{LocalDateTime.now(clock)},MutableStateFlow(Unit))
        settings.completeOnboarding()
        val d = RepositoryTaskGateway(repository)
        val created = d.create(TaskDraft("共同任务",TaskType.HOMEWORK,LocalDateTime.now(clock).toLocalDate().atTime(23,59)))
        val first = home.state.first { !it.loading && it.tasks.size==1 }
        assertFalse(first.onboarding)
        assertEquals(created.id,first.tasks.single().id)
        assertEquals(1,first.todayDueCount)
        val receipt = home.setTaskCompleted(created.id,true,first.tasks.single().version)
        assertEquals(2L,receipt.version)
        assertTrue(d.data.first().tasks.single().completedAt!=null)
        assertEquals(1,home.state.first { it.todayCompletedCount==1 }.todayCompletedCount)
        d.setCompleted(created.id,false,receipt.version)
        assertEquals(0,home.state.first { it.tasks.singleOrNull()?.version==3L }.todayCompletedCount)
    }
    @Test fun unknownDeadlineAndFiveTypeLabelsArePersistedWithoutFixtureData() = runTest {
        val settings=SettingsStore(PreferenceDataStoreFactory.create(scope=backgroundScope,
            produceFile={File(context.cacheDir,"home-${UUID.randomUUID()}.preferences_pb")}))
        val home=RepositoryHomeGateway(repository,settings,backgroundScope,{LocalDateTime.now(clock)},MutableStateFlow(Unit))
        listOf("课程作业","实验报告","社团活动","部门工作","其他").forEach {
            home.addTask(NewTask(it,"",it,null))
        }
        val state=home.state.first { it.tasks.size==5 }
        assertEquals(0,state.todayDueCount)
        assertEquals(0,state.weekTaskCount)
        assertEquals(5,repository.snapshot().tasks.map{it.type}.distinct().size)
        assertTrue(repository.snapshot().tasks.all{it.dueAt==null})
    }
    @Test fun staleAndMissingVersionsCannotOverwriteAConcurrentEdit() = runTest {
        val settings=SettingsStore(PreferenceDataStoreFactory.create(scope=backgroundScope,
            produceFile={File(context.cacheDir,"home-${UUID.randomUUID()}.preferences_pb")}))
        val home=RepositoryHomeGateway(repository,settings,backgroundScope,{LocalDateTime.now(clock)},MutableStateFlow(Unit))
        val task=repository.createTask(draft("原任务"))
        repository.updateTask(task.id,task.version,draft("第三方编辑"))
        for(version in listOf(1L,null)) {
            val error=runCatching{home.setTaskCompleted(task.id,true,version)}.exceptionOrNull()
            assertEquals(DataError.VERSION_CONFLICT,(error as DataException).code)
        }
        assertEquals("第三方编辑",repository.getTask(task.id)!!.title)
        assertNull(repository.getTask(task.id)!!.completedAt)
    }
    @Test fun skippingOnboardingUsesPersistentSettingsAndDoesNotSeedTasks() = runTest {
        val settings=SettingsStore(PreferenceDataStoreFactory.create(scope=backgroundScope,
            produceFile={File(context.cacheDir,"home-${UUID.randomUUID()}.preferences_pb")}))
        val home=RepositoryHomeGateway(repository,settings,backgroundScope,{LocalDateTime.now(clock)},MutableStateFlow(Unit))
        assertTrue(home.state.first{!it.loading}.onboarding)
        home.completeOnboarding()
        assertFalse(home.state.first{!it.loading && !it.onboarding}.onboarding)
        assertTrue(settings.settings.first().onboardingComplete)
        assertTrue(repository.snapshot().tasks.isEmpty())
    }
}
