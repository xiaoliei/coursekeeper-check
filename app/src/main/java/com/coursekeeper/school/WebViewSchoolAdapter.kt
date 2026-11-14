package com.coursekeeper.school

import com.coursekeeper.data.sync.ImportedCourse
import com.coursekeeper.data.sync.ImportedTask
import com.coursekeeper.data.sync.ModuleResult
import com.coursekeeper.data.sync.SchoolAdapter
import com.coursekeeper.data.sync.SessionState
import com.coursekeeper.data.sync.SyncFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * C 的学校适配器（计划书 T05）：实现 E 的 [SchoolAdapter] 契约。
 *
 * - Cookie 来自 WebView 的 CookieManager（登录弹层写入），不落业务表、不进日志；
 * - 会话检测只访问门户页，不拉业务数据（E：节流窗口内也检测过期）；
 * - 课表与作业分别拉取、分别解析，模块级失败互不影响（计划书 T06 部分成功）；
 * - 解析失败映射 PARSE_ERROR、网络失败映射 OFFLINE，重试策略由 SyncCoordinator 统一控制。
 */
class WebViewSchoolAdapter(
    private val schoolProvider: () -> SchoolInfo,
    private val parser: SchoolPageParser = TableSchoolPageParser(),
    private val cookieProvider: (String) -> String? = { url ->
        android.webkit.CookieManager.getInstance().getCookie(url)
    },
) : SchoolAdapter {

    override val schoolId: String get() = schoolProvider().id

    override suspend fun checkSession(): SessionState = withContext(Dispatchers.IO) {
        val school = schoolProvider()
        try {
            val reply = SchoolHttp.get(school.portalUrl, cookieProvider(school.portalUrl))
            SchoolRule.classify(reply.code, reply.body)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: IOException) {
            // 网络层失败按离线上报；其他异常向上传播，由协调器按远端错误记录。
            SessionState.OFFLINE
        }
    }

    override suspend fun fetchCourses(): ModuleResult<ImportedCourse> =
        fetch(schoolProvider().scheduleUrl) { parser.parseSchedule(it).courses }

    override suspend fun fetchTasks(): ModuleResult<ImportedTask> =
        fetch(schoolProvider().taskListUrl) { parser.parseTasks(it).tasks }

    private suspend fun <T> fetch(url: String, parse: (String) -> List<T>): ModuleResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val reply = SchoolHttp.get(url, cookieProvider(url))
                when {
                    reply.code == 401 || reply.code == 403 ||
                        SchoolRule.looksLikeLoginPage(reply.body) -> ModuleResult.Failure(SyncFailure.EXPIRED)
                    reply.code !in 200..299 -> ModuleResult.Failure(SyncFailure.REMOTE_ERROR)
                    else -> try {
                        ModuleResult.Success(parse(reply.body))
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        ModuleResult.Failure(SyncFailure.PARSE_ERROR)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: IOException) {
                ModuleResult.Failure(SyncFailure.OFFLINE, retryable = true)
            }
        }
}
