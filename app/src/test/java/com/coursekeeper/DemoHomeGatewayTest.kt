package com.coursekeeper

import com.coursekeeper.demo.*
import com.coursekeeper.home.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class DemoHomeGatewayTest {
    @Test fun initialCountsAndDailyFiltersAgree() {
        val state=DemoHomeGateway().state.value
        assertEquals(3,state.todayCourseCount)
        assertEquals(2,state.todayDueCount)
        assertEquals(1,state.todayCompletedCount)
        assertEquals(state.todayDueCount,state.tasks.filtered(TaskFilter.Today).size)
        assertEquals(state.todayCompletedCount,state.tasks.filtered(TaskFilter.CompletedToday).size)
        assertEquals(0.25f,state.weekProgress,0.001f)
    }
    @Test fun completionIsIdempotentAndCanBeUndone()=runTest {
        val gateway=DemoHomeGateway()
        gateway.setTaskCompleted("java",true)
        gateway.setTaskCompleted("java",true)
        assertEquals(2,gateway.state.value.todayCompletedCount)
        assertEquals(0.5f,gateway.state.value.weekProgress,0.001f)
        gateway.setTaskCompleted("java",false)
        assertEquals(1,gateway.state.value.todayCompletedCount)
        assertEquals(0.25f,gateway.state.value.weekProgress,0.001f)
    }
    @Test fun emptyStateAcceptsTasksWithDistinctIds()=runTest {
        val gateway=DemoHomeGateway()
        gateway.setScenario(DemoScenario.Empty)
        val draft=NewTask("第一条任务","","课程作业",LocalDateTime.of(2026,11,9,23,59))
        gateway.addTask(draft);gateway.addTask(draft)
        assertEquals(2,gateway.state.value.tasks.size)
        assertEquals(2,gateway.state.value.tasks.map{it.id}.distinct().size)
        assertEquals(2,gateway.state.value.todayDueCount)
    }
    @Test fun nextWeekTaskDoesNotChangeThisWeeksDenominator()=runTest {
        val gateway=DemoHomeGateway()
        gateway.addTask(NewTask("下周任务","","课程作业",LocalDateTime.of(2026,11,16,0,0)))
        assertEquals(5,gateway.state.value.tasks.size)
        assertEquals(4,gateway.state.value.weekTaskCount)
        assertEquals(0.25f,gateway.state.value.weekProgress,0.001f)
    }
    @Test fun localActionsWorkInAllFailureStates()=runTest {
        listOf(DemoScenario.Offline,DemoScenario.Expired,DemoScenario.Partial).forEach { scenario ->
            val gateway=DemoHomeGateway();gateway.setScenario(scenario)
            gateway.setTaskCompleted("java",true)
            gateway.addTask(NewTask("手动补录","","实验报告",LocalDateTime.of(2026,11,9,17,0)))
            assertEquals(5,gateway.state.value.tasks.size)
            assertTrue(gateway.state.value.tasks.first{it.id=="java"}.completed)
        }
    }
    @Test fun reloginPreservesExistingUserChanges()=runTest {
        val gateway=DemoHomeGateway();gateway.setScenario(DemoScenario.Expired)
        gateway.setTaskCompleted("java",true);gateway.simulateLogin()
        assertEquals(SyncStatus.Ready,gateway.state.value.status)
        assertTrue(gateway.state.value.tasks.first{it.id=="java"}.completed)
    }
    @Test fun connectingSchoolRetainsManualTasks()=runTest {
        val gateway=DemoHomeGateway()
        gateway.setScenario(DemoScenario.FirstLaunch)
        gateway.skipLogin()
        gateway.addTask(NewTask("手动任务","","课程作业",LocalDateTime.of(2026,11,9,17,0)))
        val manualId=gateway.state.value.tasks.single().id
        gateway.setTaskCompleted(manualId,true)
        gateway.simulateLogin()
        gateway.simulateLogin()
        assertEquals(5,gateway.state.value.tasks.size)
        assertTrue(gateway.state.value.tasks.single{it.id==manualId}.completed)
        assertEquals(3,gateway.state.value.todayCourseCount)
    }
    @Test fun invalidTitleAndImpossibleDateAreRejected() {
        assertTrue(parseNewTask("   ","","课程作业","2026-11-09 23:59").isFailure)
        assertTrue(parseNewTask("实验","","课程作业","2026-02-30 12:00").isFailure)
        assertTrue(parseNewTask("实验","","课程作业","2026-11-09 25:00").isFailure)
        val valid=parseNewTask("  实验  ","  课程  ","实验报告","2026-11-09 23:59").getOrThrow()
        assertEquals("实验",valid.title);assertEquals("课程",valid.course)
    }
    @Test fun unknownTaskDoesNotMutateState()=runTest {
        val gateway=DemoHomeGateway();val before=gateway.state.value
        try { gateway.setTaskCompleted("missing",true);fail("Should reject an unknown ID") }
        catch (_: IllegalArgumentException) { assertEquals(before,gateway.state.value) }
    }
}
