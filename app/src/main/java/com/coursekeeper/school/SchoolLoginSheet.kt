package com.coursekeeper.school

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * C 的登录弹层（计划书 T05 / §四 验收）：接 AppShell 的"连接学校"入口。
 *
 * - 选校：首批仅一所学校；已记住学校时直接进入登录页，不重走引导；
 * - WebView：加载学校官方登录页，账号密码与验证码在官方页面输入，本应用不经手、不保存；
 * - 成功过渡：登录成功约 1.5 秒反馈后拉取数据，后台未完成不提前显示成功；
 * - 退出入口：登录页保留取消入口（onDismiss），已有本地数据不受影响。
 */
@Composable
fun SchoolLoginSheet(
    controller: SchoolLoginController,
    savedSchool: SchoolInfo?,
    onDismiss: () -> Unit,
    /**
     * 只有"登录过期"入口才直接打开已记住学校的登录页（计划书 §四：点击直接打开已记住学校的登录页）。
     * 用户主动点顶栏"学校"进来时先看到连接状态与退出入口，不会被直接塞回登录页。
     */
    autoLogin: Boolean = false,
    /** 退出登录：清会话 Cookie + 取消记住的学校；本地课程任务保留。 */
    onLogout: () -> Unit = {},
) {
    val step by controller.step.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(savedSchool, autoLogin) {
        if (autoLogin && step is LoginStep.SelectSchool && savedSchool != null) controller.open(savedSchool)
    }
    val currentSchool = when (val value = step) {
        is LoginStep.WebView -> value.school
        is LoginStep.Feedback -> value.school
        is LoginStep.Importing -> value.school
        is LoginStep.Done -> value.school
        is LoginStep.Failure -> value.school
        LoginStep.SelectSchool -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (step is LoginStep.SelectSchool && savedSchool != null) "学校连接" else stepTitle(step)) },
        text = { StepContent(step, controller, scope, savedSchool) { onLogout(); onDismiss() } },
        confirmButton = { StepConfirm(step, controller, scope, onDismiss) },
        dismissButton = {
            TextButton(onClick = {
                if (step is LoginStep.Done) controller.reset() else if (currentSchool != null) controller.backToSelect()
                onDismiss()
            }) { Text("关闭") }
        },
    )
}

private fun stepTitle(step: LoginStep): String = when (step) {
    LoginStep.SelectSchool -> "选择学校"
    is LoginStep.WebView -> "登录学校账号"
    is LoginStep.Feedback -> "登录成功"
    is LoginStep.Importing -> "正在读取学校数据"
    is LoginStep.Done -> "导入完成"
    is LoginStep.Failure -> "连接失败"
}

@Composable
private fun StepContent(
    step: LoginStep,
    controller: SchoolLoginController,
    scope: kotlinx.coroutines.CoroutineScope,
    savedSchool: SchoolInfo?,
    onLogout: () -> Unit,
) {
    Column(
        Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (step) {
            LoginStep.SelectSchool -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("首次连接将打开学校官方登录页。账号密码只在学校官方页面输入，本应用不保存密码。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (savedSchool != null) {
                    Text("已连接：${savedSchool.name}", fontSize = 13.sp)
                    Text("退出登录只清除会话，已导入的课程与任务仍保留在本机。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { controller.open(savedSchool) }) { Text("重新登录") }
                        TextButton(onClick = onLogout) { Text("退出登录") }
                    }
                } else {
                    SchoolCatalog.default.let { school ->
                        TextButton(onClick = { controller.open(school) }) { Text(school.name) }
                    }
                }
            }
            is LoginStep.WebView -> {
                LoginWebView(step.school, controller, scope)
                // 过期场景会直达登录页，这里也留一个退出入口，避免用户被"锁"在登录页里。
                if (savedSchool != null) TextButton(onClick = onLogout) { Text("退出登录", fontSize = 12.sp) }
            }
            is LoginStep.Feedback -> Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text("登录成功，正在进入教务系统…", fontSize = 13.sp)
            }
            is LoginStep.Importing -> Text(
                "读取课表与作业 → 计算周次 → 完成\n\n数据将保存到本机，已有记录不会清除。",
                fontSize = 13.sp,
            )
            is LoginStep.Done -> Text(
                "学校数据已导入本机。\n\n若学期起始日未能自动识别，请到「课表」页完善学期设置；作业缺少稳定标识的条目不会入库，会记入同步记录的缺失计数，补齐标识后重新同步即可导入。",
                fontSize = 13.sp,
            )
            is LoginStep.Failure -> Text(step.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StepConfirm(
    step: LoginStep,
    controller: SchoolLoginController,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
) {
    when (step) {
        is LoginStep.Failure -> TextButton(onClick = {
            step.school?.let { controller.open(it) }
        }) { Text("重试") }
        is LoginStep.Done -> TextButton(onClick = onDismiss) { Text("完成") }
        is LoginStep.WebView -> TextButton(onClick = onDismiss) { Text("以后再说") }
        else -> {}
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginWebView(school: SchoolInfo, controller: SchoolLoginController, scope: kotlinx.coroutines.CoroutineScope) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 计划书 §十一：请求前展示目标域名；仅接受 HTTPS。
        Text("数据接收域名：${school.host}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var detected by rememberSaveable(school.id) { mutableStateOf(false) }
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(380.dp),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            // 计划书 §十一：仅接受 HTTPS；阻断明文与自定义 scheme 跳转，校内 SSO 子页面不受影响。
                            return !request.url.toString().startsWith("https://")
                        }
                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            val current = url ?: return
                            if (!detected && SchoolRule.isLoginComplete(school.loginUrl, school.portalUrl, current)) {
                                detected = true
                                CookieManager.getInstance().flush()
                                scope.launch { controller.onLoginSuccess(school) }
                            }
                        }
                    }
                    loadUrl(school.loginUrl)
                }
            },
        )
        Text("登录完成后将自动返回。若长时间未跳转，请先在页面内完成验证码。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
