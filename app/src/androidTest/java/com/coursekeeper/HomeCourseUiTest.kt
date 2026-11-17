package com.coursekeeper

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.coursekeeper.home.CourseAvailability
import com.coursekeeper.home.HomeUiState
import com.coursekeeper.ui.HomeScreen
import com.coursekeeper.ui.theme.CourseKeeperTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Course empty states must not turn a normal free day or finished lesson into a login error. */
class HomeCourseUiTest {
    @get:Rule val compose=createComposeRule()

    private fun show(today: Int, week: Int, hint: String = "", onCourses: () -> Unit = {}) {
        compose.setContent {
            CourseKeeperTheme {
                HomeScreen(
                    state=HomeUiState(
                        isDemo=false, courseAvailability=CourseAvailability.Ready,
                        todayCourseCount=today, weekCourseCount=week, nextCourseHint=hint,
                    ),
                    onCourses=onCourses, onTasks={}, onStatistics={}, onTaskToggle={},
                    onTaskDetail={}, onCourseDetail={}, onLogin={}, onAdd={},
                )
            }
        }
    }

    @Test fun finishedCoursesKeepTodayCountAndOfferTimetableInsteadOfLogin() {
        var visits=0
        show(today=1, week=2, hint="今日课程已结束", onCourses={visits++})
        compose.onNodeWithTag("home_course_count",useUnmergedTree=true).assertTextEquals("1")
        compose.onNodeWithText("今日课程已结束").assertIsDisplayed()
        compose.onNodeWithText("还没有课程安排").assertDoesNotExist()
        compose.onNodeWithText("连接学校").assertDoesNotExist()
        compose.onNodeWithText("查看课表").performClick()
        compose.runOnIdle { assertEquals(1,visits) }
    }

    @Test fun freeDayKeepsOtherDaysCoursesAndDoesNotAskForLogin() {
        show(today=0, week=2)
        compose.onNodeWithTag("home_course_count",useUnmergedTree=true).assertTextEquals("0")
        compose.onNodeWithText("今天没有课程").assertIsDisplayed()
        compose.onNodeWithText("今日课程已结束").assertDoesNotExist()
        compose.onNodeWithText("连接学校").assertDoesNotExist()
    }

    @Test fun emptyWeekOffersCourseMaintenance() {
        show(today=0, week=0)
        compose.onNodeWithText("本周暂无课程").assertIsDisplayed()
        compose.onNodeWithText("查看课表").assertIsDisplayed()
        compose.onNodeWithText("今日课程已结束").assertDoesNotExist()
    }
}
