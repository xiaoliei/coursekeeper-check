package com.coursekeeper.school

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 登录流程状态机（成员 C，计划书 §四"登录成功"体验）。
 *
 * 选校 → WebView 官方登录 → 登录成功反馈（约 1.5 秒）→ 读取课表与作业 → 完成。
 * 后台未完成时不提前显示成功；失败给出原因并可重试，已有数据不受影响。
 * UI 只驱动状态转移，本类不发起网络请求（拉取在 afterLogin 里走 SyncCoordinator）。
 */
sealed interface LoginStep {
    data object SelectSchool : LoginStep
    data class WebView(val school: SchoolInfo) : LoginStep
    /** 登录成功后的 1.5 秒反馈过渡，不让页面骤然消失导致用户重复登录。 */
    data class Feedback(val school: SchoolInfo) : LoginStep
    data class Importing(val school: SchoolInfo) : LoginStep
    data class Done(val school: SchoolInfo) : LoginStep
    data class Failure(val school: SchoolInfo?, val message: String) : LoginStep
}

class SchoolLoginController(
    private val feedbackMillis: Long = 1_500,
    private val afterLogin: suspend () -> Unit,
) {
    private val _step = MutableStateFlow<LoginStep>(LoginStep.SelectSchool)
    val step: StateFlow<LoginStep> = _step.asStateFlow()

    /** 进入 WebView 登录步骤；已记住学校时由 UI 直接调用，跳过选校。 */
    fun open(school: SchoolInfo) { _step.value = LoginStep.WebView(school) }

    fun backToSelect() { _step.value = LoginStep.SelectSchool }

    fun reset() { _step.value = LoginStep.SelectSchool }

    /** WebView 检测到登录成功后调用：反馈过渡结束后拉取数据并写入本地。 */
    suspend fun onLoginSuccess(school: SchoolInfo) {
        _step.value = LoginStep.Feedback(school)
        try {
            delay(feedbackMillis)
            _step.value = LoginStep.Importing(school)
            afterLogin()
            _step.value = LoginStep.Done(school)
        } catch (cancel: CancellationException) {
            // 导入由弹层的界面作用域启动：旋转、退出页面都可能取消协程（C-01 2026-11-17）。
            // 只重新抛出会让状态永远停在“正在读取学校数据”，下次打开仍显示已停止的任务。
            // 复位到选校页可重新登录；若用户已主动 backToSelect（关闭按钮），保持其选择不动。
            if (_step.value is LoginStep.Feedback || _step.value is LoginStep.Importing) {
                _step.value = LoginStep.SelectSchool
            }
            throw cancel
        } catch (error: Exception) {
            _step.value = LoginStep.Failure(school, error.message ?: "同步失败，可稍后在首页重试")
        }
    }

    /** WebView 加载失败等本地错误：给出原因，不触发数据同步。 */
    fun fail(school: SchoolInfo?, message: String) {
        _step.value = LoginStep.Failure(school, message)
    }
}
