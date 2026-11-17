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
        onDismissRequest = {
            // 完成页/失败页从任何途径关闭都复位状态机：完成页避免下次打开仍停在“导入完成”，
            // 失败页避免下次打开先看到上一次的旧错误（D 复核②）。
            if (resetsOnDismiss(step)) controller.reset()
            onDismiss()
        },
        title = { Text(if (step is LoginStep.SelectSchool && savedSchool != null) "学校连接" else stepTitle(step)) },
        text = { StepContent(step, controller, scope, savedSchool) { onLogout(); onDismiss() } },
        confirmButton = { StepConfirm(step, controller, scope, onDismiss) },
        dismissButton = {
            // 导入完成页的“关闭”与“完成”同义，只保留一个按钮；其余步骤保留关闭入口。
            if (step !is LoginStep.Done) {
                TextButton(onClick = {
                    if (currentSchool != null) controller.backToSelect()
                    onDismiss()
                }) { Text("关闭") }
            }
        },
    )
}

/**
 * 关闭弹层时是否复位状态机。
 *
 * 完成页与失败页都是“终态”：不复位的话，下次打开会直接落在这两页上
 * （完成页看起来像刚导完、失败页显示上一次的旧错误）。中间步骤保留状态，
 * 让用户关掉后再打开还能继续。抽成纯函数以便单测覆盖每个分支（D 复核②）。
 */
internal fun resetsOnDismiss(step: LoginStep): Boolean =
    step is LoginStep.Done || step is LoginStep.Failure

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
        Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
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
        is LoginStep.Done -> TextButton(onClick = {
            controller.reset() // 与其他关闭途径一致：离开完成页即复位，下次打开回到连接状态
            onDismiss()
        }) { Text("完成") }
        is LoginStep.WebView -> TextButton(onClick = onDismiss) { Text("以后再说") }
        else -> {}
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginWebView(school: SchoolInfo, controller: SchoolLoginController, scope: kotlinx.coroutines.CoroutineScope) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 计划书 §十一：请求前展示目标域名；登录页与数据页分属两个域名时都要说清楚。
        Text(
            "登录页 ${school.loginHost} · 数据接收域名 ${school.host}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val taskUrl = school.taskListUrl
        var detected by rememberSaveable(school.id) { mutableStateOf(false) }
        var awaitingTaskSource by rememberSaveable(school.id) { mutableStateOf(false) }
        // WebView 高度跟随页面内容自适应：固定高度时网页自身可以滚动，WebView 会吞掉
        // 竖向手势，外层弹层永远滑不到底，登录按钮被挡住。内容全展开后页面无可滚区域，
        // 登录按钮（在网页内）直接完整可见。
        var pageHeightDp by rememberSaveable(school.id) { mutableStateOf(WEBVIEW_MIN_HEIGHT_DP) }
        if (awaitingTaskSource) {
            Text(
                "教务登录成功，正在连接学习平台（MYSTU）读取作业…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(pageHeightDp.dp),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    isVerticalScrollBarEnabled = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    // 读取网页内容高度并回填 Compose 高度；加载中/加载完各测一次，
                    // 覆盖脚本延迟渲染的页面。scrollHeight 是 CSS 像素，与 dp 同标度，
                    // 直接取整即可（乘以屏幕密度反而会被钳到下限）。超出上限时保留网页内部滚动兜底。
                    fun measureContentHeight() {
                        evaluateJavascript(
                            "Math.max(document.body?document.body.scrollHeight:0," +
                                "document.documentElement?document.documentElement.scrollHeight:0)",
                        ) { raw ->
                            val cssHeight = raw?.trim('"', ' ')?.toFloatOrNull() ?: return@evaluateJavascript
                            if (cssHeight <= 0f) return@evaluateJavascript
                            val height = cssHeight.toInt().coerceIn(WEBVIEW_MIN_HEIGHT_DP, WEBVIEW_MAX_HEIGHT_DP)
                            if (height != pageHeightDp) pageHeightDp = height
                        }
                    }
                    val complete = {
                        if (!detected) {
                            detected = true
                            CookieManager.getInstance().flush()
                            scope.launch { controller.onLoginSuccess(school) }
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url
                            if (url.scheme == "https") return false
                            // 学校 SSO 的 service 参数可能是明文（汕头大学为 http://jw.stu.edu.cn/），
                            // 只对 SchoolInfo.allowHttpHosts 里的学校域名放行；其余明文与自定义 scheme 一律阻断。
                            return !(url.scheme == "http" && url.host in school.allowHttpHosts)
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            measureContentHeight()
                            // 表单等延迟渲染的内容会在完成后再撑高一次，稍后复测。
                            view.postDelayed({ measureContentHeight() }, 400)
                            val current = url ?: return
                            // 第二处登录（MYSTU）完成后才继续导入；中途停在登录页不算完成。
                            if (awaitingTaskSource && taskUrl != null && SchoolRule.isTaskSourceReady(taskUrl, current)) {
                                complete()
                            }
                        }
                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            val current = url ?: return
                            if (detected || awaitingTaskSource) return
                            if (!SchoolRule.isLoginComplete(
                                    school.loginUrl, school.portalUrl, current, school.allowHttpHosts,
                                )
                            ) return
                            // 教务登录成功。作业源在另一个域名（MYSTU）时，继续在同一 WebView 里完成第二处登录。
                            if (taskUrl != null && SchoolRule.hostOf(taskUrl) != SchoolRule.hostOf(school.portalUrl)) {
                                awaitingTaskSource = true
                                view.loadUrl(taskUrl)
                            } else {
                                complete()
                            }
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            if (newProgress >= 80) measureContentHeight()
                        }
                    }
                    loadUrl(school.loginUrl)
                }
            },
        )
        Text(
            "登录完成后将自动返回。若长时间未跳转，请先在页面内完成验证码；作业平台可能需要再登录一次。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** WebView 自适应高度的上下限（dp）：下限保证首次渲染就有可点的登录区域；上限按主流手机整页可容纳。 */
private const val WEBVIEW_MIN_HEIGHT_DP = 380
private const val WEBVIEW_MAX_HEIGHT_DP = 600
