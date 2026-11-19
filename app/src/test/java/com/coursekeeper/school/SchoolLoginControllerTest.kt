package com.coursekeeper.school

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 登录流程状态机测试：1.5 秒反馈过渡（计划书 §四"登录成功"）与失败路径。 */
class SchoolLoginControllerTest {

    private val school = SchoolCatalog.default

    @Test
    fun successFlowImportsThenFinishes() = runTest {
        var afterLoginCalls = 0
        val controller = SchoolLoginController(afterLogin = { afterLoginCalls++ })

        val job = launch { controller.onLoginSuccess(school) }
        advanceUntilIdle()

        assertEquals(LoginStep.Done(school), controller.step.value)
        assertEquals(1, afterLoginCalls)
        job.join()
    }

    @Test
    fun feedbackTransitionWaitsFullDurationBeforeImporting() = runTest {
        // afterLogin 挂起片刻，便于观察 Importing 中间态；完成后进入 Done。
        val controller = SchoolLoginController(feedbackMillis = 1_500, afterLogin = { delay(100) })
        val job = launch { controller.onLoginSuccess(school) }

        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(controller.step.value is LoginStep.Feedback) // 后台未完成不提前显示成功

        advanceTimeBy(500)
        runCurrent()
        assertEquals(LoginStep.Importing(school), controller.step.value)
        advanceUntilIdle()
        job.join()
        assertEquals(LoginStep.Done(school), controller.step.value)
    }

    @Test
    fun syncFailureAfterLoginShowsFailureAndKeepsSchoolForRetry() = runTest {
        val controller = SchoolLoginController(afterLogin = { error("同步失败") })
        val job = launch { controller.onLoginSuccess(school) }
        advanceUntilIdle()

        val failure = controller.step.value
        assertTrue(failure is LoginStep.Failure)
        assertEquals(school, (failure as LoginStep.Failure).school)
        job.join()

        controller.open(school)
        assertEquals(LoginStep.WebView(school), controller.step.value)
    }

    @Test
    fun openAndResetMoveBetweenWebViewAndSelectSchoolSteps() {
        val controller = SchoolLoginController(afterLogin = {})
        assertEquals(LoginStep.SelectSchool, controller.step.value)
        controller.open(school)
        assertEquals(LoginStep.WebView(school), controller.step.value)
        controller.backToSelect()
        assertEquals(LoginStep.SelectSchool, controller.step.value)
    }

    // ---- C-01（2026-11-17）：导入协程被取消后不能永远停在“正在读取” ----

    @Test
    fun cancellationDuringFeedbackResetsToRecoverableStep() = runTest {
        val controller = SchoolLoginController(feedbackMillis = 10_000, afterLogin = {})
        val job = launch { controller.onLoginSuccess(school) }
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(controller.step.value is LoginStep.Feedback)

        job.cancel()
        runCurrent()

        assertEquals("旋转/退出取消后应回到可重试状态", LoginStep.SelectSchool, controller.step.value)
    }

    @Test
    fun cancellationDuringImportResetsToRecoverableStep() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val controller = SchoolLoginController(feedbackMillis = 0, afterLogin = { gate.await() })
        val job = launch { controller.onLoginSuccess(school) }
        runCurrent()
        assertEquals(LoginStep.Importing(school), controller.step.value)

        job.cancel()
        runCurrent()

        assertEquals(LoginStep.SelectSchool, controller.step.value)
        assertTrue("不得残留“正在读取”", controller.step.value !is LoginStep.Importing)
    }

    @Test
    fun userClosingDuringImportIsNotOverwrittenByCancellation() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val controller = SchoolLoginController(feedbackMillis = 0, afterLogin = { gate.await() })
        val job = launch { controller.onLoginSuccess(school) }
        runCurrent()

        controller.backToSelect() // 用户点“关闭”
        job.cancel()
        runCurrent()

        assertEquals(LoginStep.SelectSchool, controller.step.value)
    }
}
