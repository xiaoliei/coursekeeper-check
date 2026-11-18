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
    private val courseParser: SchoolPageParser = TableSchoolPageParser(),
    private val taskParser: SchoolPageParser = courseParser,
    private val cookieProvider: (String) -> String? = { url ->
        android.webkit.CookieManager.getInstance().getCookie(url)
    },
    /** 调试诊断用：非空时把每次拉到的原始页面存到该目录（仅本机留存，不外发）。 */
    private val debugDumpDir: java.io.File? = null,
) : SchoolAdapter {

    override val schoolId: String get() = schoolProvider().id

    override suspend fun checkSession(): SessionState = withContext(Dispatchers.IO) {
        val school = schoolProvider()
        try {
            val reply = SchoolHttp.get(school.portalUrl, cookieProvider(school.portalUrl))
            // 会话失效时教务会把请求重定向到 SSO 登录页；HttpURLConnection 已跟随跳转，
            // 最终主机不再是门户，直接判过期（比只看响应体更可靠）。
            if (SchoolRule.hostOf(reply.finalUrl) != SchoolRule.hostOf(school.portalUrl)) {
                SessionState.EXPIRED
            } else {
                SchoolRule.classify(reply.code, reply.body)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: IOException) {
            // 网络层失败按离线上报；其他异常向上传播，由协调器按远端错误记录。
            SessionState.OFFLINE
        }
    }

    override suspend fun fetchCourses(): ModuleResult<ImportedCourse> =
        fetch(schoolProvider().scheduleUrl) { body -> courseParser.parseSchedule(body).toCourseSyncResult() }

    override suspend fun fetchTasks(): ModuleResult<ImportedTask> {
        val school = schoolProvider()
        val url = school.taskListUrl
            ?: return ModuleResult.Failure(SyncFailure.PARSE_ERROR)
        return fetch(url) { body ->
            val parsed = taskParser.parseTasks(body)
            // 未登录时 MYSTU 会返回登录页或空壳：同样是“0 条 + 警告”，必须报失败，
            // 否则会显示“作业同步成功、0 条作业”。
            if (parsed.tasks.isEmpty() && parsed.warnings.isNotEmpty()) {
                error(parsed.warnings.joinToString("；"))
            }
            ModuleResult.Success(parsed.tasks)
        }
    }

    private suspend fun <T> fetch(url: String, parse: (String) -> ModuleResult<T>): ModuleResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val reply = SchoolHttp.get(url, cookieProvider(url))
                dumpForDebug(url, reply.body)
                when {
                    reply.code == 401 || reply.code == 403 ||
                        SchoolRule.looksLikeLoginPage(reply.body) -> ModuleResult.Failure(SyncFailure.EXPIRED)
                    reply.code !in 200..299 -> ModuleResult.Failure(SyncFailure.REMOTE_ERROR)
                    else -> try {
                        parse(reply.body)
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

    /** 仅当传入 debugDumpDir 时生效：保存原始响应，便于离线复现解析问题。 */
    private fun dumpForDebug(url: String, body: String) {
        val dir = debugDumpDir ?: return
        runCatching {
            dir.mkdirs()
            val stamp = java.time.format.DateTimeFormatter.ofPattern("HHmmss-SSS")
                .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.now())
            val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "page" }
            java.io.File(dir, "$name-$stamp.html").writeText(body)
        }
    }
}

internal fun ScheduleParse.toCourseSyncResult(): ModuleResult<ImportedCourse> =
    if (courses.isEmpty() || skippedCourses != 0 || warnings.isNotEmpty()) {
        ModuleResult.Failure(SyncFailure.PARSE_ERROR)
    } else {
        ModuleResult.Success(courses, completeSnapshot = true)
    }
