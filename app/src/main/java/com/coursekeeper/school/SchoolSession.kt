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

    /** WebView 登录成功判定：离开登录页且回到教务门户域名，全程 HTTPS。 */
    fun isLoginComplete(loginUrl: String, portalUrl: String, currentUrl: String): Boolean {
        if (!currentUrl.startsWith("https://")) return false
        if (currentUrl.contains("login")) return false
        return hostOf(currentUrl) == hostOf(portalUrl)
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

    /** 登录表单特征：带 password 输入框或验证码提示的表单页。 */
    fun looksLikeLoginPage(body: String): Boolean {
        val lowered = body.lowercase()
        return "<form" in lowered && ("type=\"password\"" in lowered || "验证码" in body)
    }
}

data class HttpReply(val code: Int, val body: String)

/** 轻量 GET 客户端：不引入第三方网络库；异常统一映射为 [IOException] 或原样抛出。 */
object SchoolHttp {
    suspend fun get(url: String, cookie: String?): HttpReply = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "学校数据地址必须为 HTTPS" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = SchoolRule.CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = SchoolRule.READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = true
            cookie?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Cookie", it) }
            connection.setRequestProperty("User-Agent", "CourseKeeper/0.2 (Android)")
            val body = connection.errorStream?.use { it.readBytes().decodeToString() }
                ?: connection.inputStream.use { it.readBytes().decodeToString() }
            HttpReply(connection.responseCode, body)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("学校数据请求失败", error)
        } finally {
            connection.disconnect()
        }
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
