package com.coursekeeper.a

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.coursekeeper.a.demo.*
import com.coursekeeper.a.ui.AppShell
import com.coursekeeper.a.ui.theme.CourseKeeperTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose=createComposeRule()
    private fun show(gateway: DemoHomeGateway=DemoHomeGateway()) {
        compose.setContent {
            val state by gateway.state.collectAsState()
            CourseKeeperTheme { AppShell(state,
                onTaskToggle={runBlocking {gateway.setTaskCompleted(it.id,!it.completed)}},
                onAddTask={runBlocking {gateway.addTask(it)}},onScenario=gateway::setScenario,
                onSkipLogin=gateway::skipLogin,onSimulateLogin=gateway::simulateLogin)
            }
        }
    }
    @Test fun fourDestinationsAreReachable() {
        show()
        compose.onNodeWithText("课表",useUnmergedTree=true).performClick()
        compose.onNodeWithText("我的课表").assertIsDisplayed()
        compose.onNodeWithText("任务",useUnmergedTree=true).performClick()
        compose.onNodeWithText("任务清单").assertIsDisplayed()
        compose.onNodeWithText("数据",useUnmergedTree=true).performClick()
        compose.onNodeWithText("数据看板").assertIsDisplayed()
        compose.onNodeWithText("首页",useUnmergedTree=true).performClick()
        compose.onNodeWithText("上午好，同学").assertIsDisplayed()
    }
    @Test fun firstLaunchCanEnterManualMode() {
        val gateway=DemoHomeGateway().apply{setScenario(DemoScenario.FirstLaunch)}
        show(gateway)
        compose.onNodeWithText("先手动使用").performClick()
        compose.onNodeWithText("上午好，同学").assertIsDisplayed()
        compose.onNodeWithText("＋ 手动添加任务").assertIsDisplayed()
    }
    @Test fun offlineStillAllowsTaskCreation() {
        val gateway=DemoHomeGateway().apply{setScenario(DemoScenario.Offline)}
        show(gateway)
        compose.onNodeWithText("＋ 手动添加任务").performClick()
        compose.onNodeWithText("任务名称").performTextInput("离线新增测试")
        compose.onNodeWithText("保存任务").performClick()
        compose.onNodeWithText("任务",useUnmergedTree=true).performClick()
        compose.onNodeWithText("离线新增测试").assertExists()
    }
}
