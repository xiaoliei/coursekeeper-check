package com.coursekeeper

import androidx.compose.material3.Text
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.coursekeeper.home.HomeSyncModuleUi
import com.coursekeeper.home.HomeUiState
import com.coursekeeper.home.SyncStatus
import com.coursekeeper.home.TaskUi
import com.coursekeeper.ui.AppShell
import com.coursekeeper.ui.theme.CourseKeeperTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Home refresh, retry, and login routing with cached UI data and a fake sync callback. */
class HomeSyncUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offlineRetryKeepsTaskVisibleWhenSyncStarts() {
        val state = mutableStateOf(cachedHome(SyncStatus.Offline))
        var refreshes = 0
        show(state) {
            refreshes++
            state.value = state.value.copy(status = SyncStatus.Syncing)
        }

        compose.onNodeWithText("重试同步").performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
        compose.onNodeWithTag("home_refresh_button").assertIsNotEnabled()
        assertCachedTaskVisible()
    }

    @Test fun partialSyncCanRetryFromDetailsWithoutClearingCachedTasks() {
        val state = mutableStateOf(cachedHome(SyncStatus.Partial).copy(syncModules = listOf(
            HomeSyncModuleUi("课表", SyncStatus.Ready, "已同步", "最近成功：11-24 10:00"),
            HomeSyncModuleUi("作业", SyncStatus.Error, "同步失败", "最近成功：11-23 10:00"),
        )))
        var refreshes = 0
        show(state) { refreshes++ }

        compose.onNodeWithText("手动添加").assertIsDisplayed()
        compose.onNodeWithText("查看详情").performClick()
        compose.onNodeWithText("作业 · 同步失败").assertIsDisplayed()
        compose.onNode(hasText("重试同步") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("同步详情").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, refreshes) }
        assertCachedTaskVisible()
    }

    @Test fun refreshButtonSuppressesAnotherTapUntilRefreshEnds() {
        val refreshing = mutableStateOf(false)
        var refreshes = 0
        show(mutableStateOf(cachedHome()), refreshing = refreshing) {
            refreshes++
            refreshing.value = true
        }

        val refreshButton = compose.onNodeWithTag("home_refresh_button")
        refreshButton.assertIsEnabled().performClick()
        refreshButton.assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, refreshes)
            refreshing.value = false
        }
        refreshButton.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, refreshes) }
    }

    @Test fun swipeDownAtTopInvokesRefresh() {
        var refreshes = 0
        show(mutableStateOf(cachedHome())) { refreshes++ }

        compose.onNodeWithTag("home_list").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun expiredModuleOpensSchoolLoginWithoutRefreshing() {
        val state = mutableStateOf(cachedHome(SyncStatus.Partial).copy(syncModules = listOf(
            HomeSyncModuleUi("课表", SyncStatus.Ready, "已同步", "最近成功：11-24 10:00"),
            HomeSyncModuleUi("作业", SyncStatus.Expired, "登录已过期", "尚未成功同步"),
        )))
        var refreshes = 0
        show(state) { refreshes++ }

        compose.onNodeWithText("重试同步").assertDoesNotExist()
        compose.onNodeWithText("重新登录").performClick()
        compose.onNodeWithText(SchoolLoginTitle).assertExists()
        compose.runOnIdle { assertEquals(0, refreshes) }
    }

    @Test fun refreshWithoutSchoolConnectionOpensLoginWithoutRefreshing() {
        var refreshes = 0
        show(mutableStateOf(cachedHome()), schoolConnected = false) { refreshes++ }

        compose.onNodeWithTag("home_refresh_button").performClick()
        compose.onNodeWithText(SchoolLoginTitle).assertExists()
        compose.runOnIdle { assertEquals(0, refreshes) }
    }

    @Test fun schoolButtonCannotReopenLoginWhileLogoutRuns() {
        val actionsEnabled = mutableStateOf(false)
        show(mutableStateOf(cachedHome()), schoolActionsEnabled = actionsEnabled) {}

        val schoolButton = compose.onNodeWithText("学校")
        schoolButton.assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText(SchoolLoginTitle).assertDoesNotExist()

        compose.runOnIdle { actionsEnabled.value = true }
        schoolButton.assertIsEnabled().performClick()
        compose.onNodeWithText(SchoolLoginTitle).assertExists()
    }

    private fun show(
        state: State<HomeUiState>,
        schoolConnected: Boolean = true,
        refreshing: State<Boolean> = mutableStateOf(false),
        schoolActionsEnabled: State<Boolean> = mutableStateOf(true),
        onRefresh: () -> Unit,
    ) {
        compose.setContent {
            CourseKeeperTheme {
                AppShell(
                    state = state.value,
                    onTaskToggle = {}, onAddTask = {}, onScenario = {},
                    onSkipLogin = {}, onSimulateLogin = {},
                    loginSheet = { _ -> Text(SchoolLoginTitle) },
                    onRefreshHome = onRefresh,
                    refreshingHome = refreshing.value,
                    schoolConnected = schoolConnected,
                    schoolActionsEnabled = schoolActionsEnabled.value,
                )
            }
        }
    }

    private fun assertCachedTaskVisible() {
        val completeTask = hasContentDescription("完成：$CachedTaskTitle")
        compose.onNodeWithTag("home_list").performScrollToNode(completeTask)
        compose.onNode(completeTask).assertIsDisplayed()
    }

    private fun cachedHome(status: SyncStatus = SyncStatus.Ready) = HomeUiState(
        isDemo = false,
        status = status,
        tasks = listOf(TaskUi(
            id = "cached-task", title = CachedTaskTitle, course = "数据库",
            type = "课程作业", dueLabel = "截止待定", completed = false,
            dueToday = false, completedToday = false,
        )),
    )

    private companion object {
        const val CachedTaskTitle = "已经保存在本机的任务"
        const val SchoolLoginTitle = "学校登录测试入口"
    }
}
