package com.coursekeeper

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.coursekeeper.home.*
import com.coursekeeper.ui.HomeScreen
import com.coursekeeper.ui.theme.CourseKeeperTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Fixed local states only: no school adapter, network calls, or sync writes. */
class HomeSummaryUiTest {
    @get:Rule val compose=createComposeRule()

    private fun show(state: HomeUiState, onLogin: () -> Unit = {}, onAdd: () -> Unit = {}) {
        compose.setContent {
            CourseKeeperTheme {
                HomeScreen(state,{}, {}, {}, {}, {}, {},onLogin,onAdd)
            }
        }
    }

    @Test fun teachingWeekAndPressureSummaryIncludeExplanation() {
        show(HomeUiState(
            isDemo=false,weekLabel="第 10 教学周",pressureScore=30,pressureLevel="适中",
            pressureLabel="压力指数 30 · 适中",pressureExplanation="今日课程较多",
        ))
        compose.onNodeWithTag("home_week_label").assertTextEquals("第 10 教学周")
        compose.onNodeWithTag("home_pressure_summary").performScrollTo().assertTextEquals("压力指数 30 · 适中")
        compose.onNodeWithTag("home_pressure_explanation").performScrollTo().assertTextEquals("今日课程较多")
    }

    @Test fun noSemesterAndNoPressureDoNotShowExampleValues() {
        show(HomeUiState(isDemo=false,weekLabel="待设置学期",courseAvailability=CourseAvailability.NoSemester))
        compose.onNodeWithTag("home_week_label").assertTextEquals("待设置学期")
        compose.onNodeWithTag("home_pressure_summary").performScrollTo().assertTextEquals("暂无压力数据")
        compose.onNodeWithTag("home_pressure_explanation").assertDoesNotExist()
    }

    @Test fun partialFailureDetailsPreserveSuccessTimeAndManualTaskEntry() {
        var added=0
        show(HomeUiState(isDemo=false,status=SyncStatus.Partial,todayDueCount=2,syncModules=listOf(
            HomeSyncModuleUi("课表",SyncStatus.Ready,"已同步","最近成功：11-17 10:00"),
            HomeSyncModuleUi("作业",SyncStatus.Error,"同步失败","最近成功：11-16 10:00","学校页面解析失败，已有数据已保留"),
        )),onAdd={added++})
        compose.onNodeWithText("查看详情").performClick()
        compose.onNodeWithText("课表 · 已同步").assertIsDisplayed()
        compose.onNodeWithText("作业 · 同步失败").assertIsDisplayed()
        compose.onNodeWithText("最近成功：11-16 10:00").assertExists()
        compose.onNodeWithText("学校页面解析失败，已有数据已保留").assertExists()
        compose.onNodeWithText("重新登录").assertDoesNotExist()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithTag("home_due_count",useUnmergedTree=true).assertTextEquals("2")
        compose.onNodeWithText("手动添加").performClick()
        compose.runOnIdle { assertEquals(1,added) }
    }

    @Test fun partialExpiredModuleCanRequestLoginFromDetails() {
        var logins=0
        show(HomeUiState(isDemo=false,status=SyncStatus.Partial,syncModules=listOf(
            HomeSyncModuleUi("课表",SyncStatus.Ready,"已同步","最近成功：11-17 10:00"),
            HomeSyncModuleUi("作业",SyncStatus.Expired,"登录已过期","尚未成功同步","请重新登录学校账号"),
        )),onLogin={logins++})
        compose.onNodeWithText("查看详情").performClick()
        compose.onNodeWithText("作业 · 登录已过期").assertIsDisplayed()
        compose.onNodeWithText("重新登录").performClick()
        compose.onNodeWithText("同步详情").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1,logins) }
    }

    @Test fun offlineDetailsCanCloseWithoutChangingCachedCounts() {
        show(HomeUiState(isDemo=false,status=SyncStatus.Offline,todayCompletedCount=3,syncModules=listOf(
            HomeSyncModuleUi("课表",SyncStatus.Offline,"离线","最近成功：11-16 10:00","网络不可用，已有数据可继续使用"),
        )))
        compose.onNodeWithText("查看详情").performClick()
        compose.onNodeWithText("课表 · 离线").assertIsDisplayed()
        compose.onNodeWithText("网络不可用，已有数据可继续使用").assertExists()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithTag("home_completed_count",useUnmergedTree=true).assertTextEquals("3")
    }

    @Test fun syncingDetailsRetainPreviousSuccessAndDoNotOfferLogin() {
        show(HomeUiState(isDemo=false,status=SyncStatus.Syncing,syncModules=listOf(
            HomeSyncModuleUi("课表",SyncStatus.Syncing,"同步中","最近成功：11-16 10:00"),
        )))
        compose.onNodeWithText("查看详情").performClick()
        compose.onNodeWithText("课表 · 同步中").assertIsDisplayed()
        compose.onNodeWithText("最近成功：11-16 10:00").assertIsDisplayed()
        compose.onNodeWithText("重新登录").assertDoesNotExist()
    }
}
