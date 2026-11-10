package com.coursekeeper

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.coursekeeper.data.CourseKeeperData
import com.coursekeeper.data.TaskDraft
import com.coursekeeper.data.model.TaskType
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime

/** Actual Activity + D editor + E's file database; no fixture HomeGateway. */
class HomeIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val data get()=CourseKeeperData.get(compose.activity.applicationContext)
    @Before fun prepare() {
        runBlocking {
            data.repository.snapshot().tasks.forEach { data.repository.deleteTask(it.id,it.version) }
            data.settings.completeOnboarding()
        }
        awaitCounter("home_due_count","0")
    }
    private fun awaitCounter(tag:String,value:String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes().singleOrNull()
                ?.config?.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text==value
        }
    }
    @Test fun homeCreateCompletionUndoAndActivityRecreationUseTheSameDatabase() {
        val title="首页真实任务验证"
        compose.onNodeWithText("Java 实验报告").assertDoesNotExist()
        compose.onNodeWithText("＋ 手动添加任务").performClick()
        compose.onNodeWithText("新建任务").assertIsDisplayed()
        compose.onNodeWithText("任务名称").performTextInput(title)
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { runBlocking {data.repository.snapshot().tasks.any{it.title==title}} }
        compose.onNodeWithText("首页",useUnmergedTree=true).performClick()
        awaitCounter("home_completed_count","0")
        // The save Snackbar overlays the first task row on short screens; wait before a touch click.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("任务已保存").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("完成：$title").performScrollTo().performClick()
        compose.onNodeWithTag("home_list").performScrollToIndex(0)
        awaitCounter("home_completed_count","1")
        compose.onNodeWithText("撤销").performClick()
        awaitCounter("home_completed_count","0")
        compose.onNodeWithText("任务",useUnmergedTree=true).performClick()
        compose.onNodeWithText("新建任务").assertDoesNotExist() // consumed request must not reopen the editor
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText("首页",useUnmergedTree=true).performClick()
        compose.activityRule.scenario.recreate()
        awaitCounter("home_completed_count","0")
        compose.onNodeWithContentDescription("完成：$title").assertExists()
        compose.onNodeWithText("先手动使用").assertDoesNotExist()
    }
    @Test fun homeCountersOpenExactDateFiltersInsteadOfOnlyOpenOrDone() {
        val now=LocalDateTime.now()
        runBlocking {
            data.repository.createTask(TaskDraft("今日未完成",TaskType.HOMEWORK,now.toLocalDate().atTime(23,59)))
            val dueToday=data.repository.createTask(TaskDraft("今日已完成",TaskType.LAB_REPORT,now.toLocalDate().atTime(23,59)))
            data.repository.setTaskCompleted(dueToday.id,true,dueToday.version)
            val dueTomorrow=data.repository.createTask(TaskDraft("明日截止但今日完成",TaskType.OTHER,now.plusDays(1)))
            data.repository.setTaskCompleted(dueTomorrow.id,true,dueTomorrow.version)
            data.repository.createTask(TaskDraft("截止待定不计今日",TaskType.CLUB))
        }
        awaitCounter("home_due_count","2")
        awaitCounter("home_completed_count","2")
        compose.onNodeWithText("今日截止").performClick()
        compose.onNodeWithText("今日截止 · 2 项").assertExists()
        compose.onNodeWithText("今日未完成").assertExists()
        compose.onNodeWithText("今日已完成").assertExists()
        compose.onNodeWithText("明日截止但今日完成").assertDoesNotExist()
        compose.onNodeWithText("截止待定不计今日").assertDoesNotExist()
        compose.onNodeWithText("首页",useUnmergedTree=true).performClick()
        compose.onNodeWithText("今日已完成").performClick()
        compose.onNodeWithText("今日完成 · 2 项").assertExists()
        compose.onNodeWithText("明日截止但今日完成").assertExists()
        compose.onNodeWithText("今日未完成").assertDoesNotExist()
    }
}
