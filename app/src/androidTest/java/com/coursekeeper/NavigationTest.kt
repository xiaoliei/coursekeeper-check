package com.coursekeeper

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.coursekeeper.demo.*
import com.coursekeeper.ui.AppShell
import com.coursekeeper.ui.theme.CourseKeeperTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose=createComposeRule()
    private fun show(gateway: DemoHomeGateway=DemoHomeGateway(),customCourses:Boolean=false) {
        compose.setContent {
            val state by gateway.state.collectAsState()
            CourseKeeperTheme { AppShell(state,
                onTaskToggle={runBlocking {gateway.setTaskCompleted(it.id,!it.completed)}},
                onAddTask={runBlocking {gateway.addTask(it)}},onScenario=gateway::setScenario,
                onSkipLogin=gateway::skipLogin,onSimulateLogin=gateway::simulateLogin,
                coursesScreen=if(customCourses) { _ -> Text("课程插槽验证") } else null)
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
    @Test fun suppliedCourseSlotReplacesOnlyCoursesAndKeepsNavigation() {
        show(customCourses=true)
        compose.onNodeWithText("课表",useUnmergedTree=true).performClick()
        compose.onNodeWithText("课程插槽验证").assertIsDisplayed()
        compose.onNodeWithText("首页",useUnmergedTree=true).performClick()
        compose.onNodeWithText("上午好，同学").assertIsDisplayed()
        compose.onNodeWithText("＋ 手动添加任务").assertIsDisplayed()
    }

    /**
     * 回归：带"撤销"的 Snackbar 在 Material3 里默认常驻，曾出现"点任意导航都不消失、
     * 只有点撤销才消失"的缺陷。点当前 tab（选中态不变）也必须能收起。
     */
    @Test fun tappingNavigationItemDismissesUndoSnackbarEvenOnSameTab() {
        val gateway=DemoHomeGateway()
        val host=SnackbarHostState()
        compose.setContent {
            val state by gateway.state.collectAsState()
            CourseKeeperTheme { AppShell(state,
                onTaskToggle={runBlocking {gateway.setTaskCompleted(it.id,!it.completed)}},
                onAddTask={runBlocking {gateway.addTask(it)}},onScenario=gateway::setScenario,
                onSkipLogin=gateway::skipLogin,onSimulateLogin=gateway::simulateLogin,
                snackbarHostState=host)
            }
        }
        compose.onNodeWithText("任务",useUnmergedTree=true).performClick()
        val scope=CoroutineScope(Dispatchers.Default)
        try {
            compose.runOnIdle {
                scope.launch {
                    // 用 Indefinite 而非生产时长：让"自动超时"不可能成为本用例的通过原因，
                    // 从而真锁住"点当前 tab 也要收起"这条逻辑（与生产值改为 Short 也无关）。
                    host.showSnackbar("任务已完成",actionLabel="撤销",
                        withDismissAction=true,duration=SnackbarDuration.Indefinite)
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("撤销").fetchSemanticsNodes().isNotEmpty() }
            // 再点一次当前所在的"任务"tab：selected 不变，仍须收起提示
            compose.onNodeWithText("任务",useUnmergedTree=true).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("撤销").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("撤销").assertDoesNotExist()
        } finally {
            scope.cancel()
        }
    }
}
