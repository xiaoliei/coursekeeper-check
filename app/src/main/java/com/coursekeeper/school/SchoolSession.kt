package com.coursekeeper.school

import com.coursekeeper.data.sync.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 学校会话规则层（成员 C，计划书 T05/T06）。
 *
 * 只放纯函数，便于 JVM 单测。会话 Cookie 由 WebView 的 CookieManager 持有并仅用于
 * 本机同步（计划书 §五：会话信息与业务数据分开管理，仅为本机同步使用）；
 * 密码、验证码不进入本类，也不写入任何日志（E 交接 §表结构约定）。
 */
object SchoolRule {
    // E 的 SyncCoordinator 对每次检查/拉取套了 30 秒 withTimeout，而 HttpURLConnection 的阻塞读
    // 不响应协程取消。连接与读取之和必须落在 30 秒内，否则超时只能等读返回后才生效。
    const val CONNECT_TIMEOUT_MILLIS = 8_000
    const val READ_TIMEOUT_MILLIS = 15_000

    fun hostOf(url: String): String =
        runCatching { URL(url).host }.getOrDefault("")

    /** 教务过期的 JS 跳转壳页：`window.location.href='…/login…'`（无表单、主机名不变）。 */
    private val JS_LOGIN_REDIRECT = Regex("""window\.location(\.href)?\s*=\s*['"][^'"]*login[^'"]*['"]""")

    /**
     * WebView 登录成功判定：离开登录页且回到教务门户域名。
     *
     * 实测学校（汕头大学）统一身份认证的 `service` 参数是 `http://jw.stu.edu.cn/`，
     * 登录成功后 WebView 会被带回 **http** 的门户地址；因此对学校自己的域名允许明文回跳，
     * 其余仍只放行 HTTPS。[allowHttpHosts] 由 [SchoolInfo.allowHttpHosts] 提供。
     */
    fun isLoginComplete(
        loginUrl: String,
        portalUrl: String,
        currentUrl: String,
        allowHttpHosts: Set<String> = emptySet(),
    ): Boolean {
        val host = hostOf(currentUrl)
        val secure = currentUrl.startsWith("https://")
        val insecureButAllowed = currentUrl.startsWith("http://") && host in allowHttpHosts
        if (!secure && !insecureButAllowed) return false
        if (currentUrl.contains("login")) return false
        return host == hostOf(portalUrl)
    }

    /**
     * 作业源（MYSTU）登录完成判定：已回到作业页域名、且不在登录路径上。
     * 首次加载作业页通常先跳 SSO；只有落在 `my.stu.edu.cn` 的非登录页才算完成。
     */
    fun isTaskSourceReady(taskUrl: String, currentUrl: String): Boolean {
        if (!currentUrl.startsWith("https://")) return false
        if (hostOf(currentUrl) != hostOf(taskUrl)) return false
        return !currentUrl.lowercase().contains("login")
    }

    /**
     * 响应分类：门户页 2xx 且不像登录表单即会话有效；被重定向回登录页、401/403 视为过期；
     * 5xx 视为服务暂不可用（按离线呈现，不误报"登录过期"）。
     */
    fun classify(statusCode: Int, body: String): SessionState = when {
        statusCode == 401 || statusCode == 403 -> SessionState.EXPIRED
        statusCode in 200..299 -> if (looksLikeLoginPage(body)) SessionState.EXPIRED else SessionState.VALID
        else -> SessionState.OFFLINE
    }

    /**
     * 登录页特征：带 password 输入框或验证码提示的表单页；或教务过期后的
     * JS 跳转壳页（实测汕头大学：过期请求 302 回门户根，由一段
     * `window.location.href='https://sso.stu.edu.cn/login…'` 指回 SSO，全程无表单）。
     */
    fun looksLikeLoginPage(body: String): Boolean {
        val lowered = body.lowercase()
        if (JS_LOGIN_REDIRECT.containsMatchIn(lowered)) return true
        return "<form" in lowered && ("type=\"password\"" in lowered || "验证码" in body)
    }
}

/** [finalUrl] 是跟随重定向后的实际地址：被踢回 SSO 时主机名会变，用于判定会话过期。 */
data class HttpReply(val code: Int, val body: String, val finalUrl: String)

/** 轻量 GET 客户端：不引入第三方网络库；异常统一映射为 [IOException] 或原样抛出。 */
object SchoolHttp {
    /**
     * 手动跟随重定向：HttpURLConnection 的自动跟随**不跨协议**（实测学校把过期请求
     * 302 到明文 http 门户，自动跟随会停在中间一跳，导致"过期"被误判成"离线"）。
     * 会话 Cookie 只发往与首个请求同主机的跳（避免把 jw 的会话带去别的域名）。
     */
    private const val MAX_REDIRECTS = 6

    /**
     * 超时可按请求覆盖：作业索引页补抓（C 2026-11-25）是每课一次的附加请求，
     * 用更短的连接/读取超时把它限制在模块超时预算内；阻塞读不会响应协程取消，
     * 只能靠 socket 超时兜底。
     */
    suspend fun get(
        url: String,
        cookie: String?,
        connectTimeoutMillis: Int = SchoolRule.CONNECT_TIMEOUT_MILLIS,
        readTimeoutMillis: Int = SchoolRule.READ_TIMEOUT_MILLIS,
    ): HttpReply = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "学校数据地址必须为 HTTPS" }
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0) { "超时必须为正数" }
        val originHost = SchoolRule.hostOf(url)
        var current = url
        var reply: HttpReply? = null
        var hop = 0
        while (reply == null && hop <= MAX_REDIRECTS) {
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                if (hop == 0 || SchoolRule.hostOf(current) == originHost) {
                    cookie?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Cookie", it) }
                }
                connection.setRequestProperty("User-Agent", "CourseKeeper/0.2 (Android)")
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    current = URL(URL(current), location).toString()
                } else {
                    val body = connection.errorStream?.use { it.readBytes().decodeToString() }
                        ?: connection.inputStream.use { it.readBytes().decodeToString() }
                    reply = HttpReply(code, body, current)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: IOException) {
                throw error
            } catch (error: Exception) {
                throw IOException("学校数据请求失败", error)
            } finally {
                connection.disconnect()
            }
            hop++
        }
        // 重定向超出上限：按 508（循环检测）返回，上游按远端错误处理，不误判会话状态。
        reply ?: HttpReply(508, "", current)
    }
}

/**
 * 退出登录：只清会话 Cookie，不动本地业务数据（E 的课程/任务与手动修改必须保留）。
 *
 * `SettingsStore.selectSchool(null)` 由调用方执行。同步记录里的 schoolId 保留：
 * 再次登录同一学校可继续增量合并；换成别的学校会被 SyncCoordinator 的
 * `SchoolMismatch` 拦下，而不是静默混写。
 */
object SchoolLogout {
    fun clear() {
        val manager = android.webkit.CookieManager.getInstance()
        manager.removeAllCookies(null)
        manager.flush()
    }
}
