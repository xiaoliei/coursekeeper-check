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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * C 的学校适配器（计划书 T05）：实现 E 的 [SchoolAdapter] 契约。
 *
 * - Cookie 来自 WebView 的 CookieManager（登录弹层写入），不落业务表、不进日志；
 * - 会话检测只访问门户页，不拉业务数据（E：节流窗口内也检测过期）；
 * - 课表与作业分别拉取、分别解析，模块级失败互不影响（计划书 T06 部分成功）；
 * - 解析失败映射 PARSE_ERROR、网络失败映射 OFFLINE，重试策略由 SyncCoordinator 统一控制。
 *
 * 2026-11-25（C）新增两项 P2/CBE-01 能力：
 * - [backfillTaskIndex]：用 E 的跨同步课程编号补抓每课作业索引页，覆盖首页已移出的历史作业；
 * - [httpGet] 可注入，使补抓流程能在 JVM 单测里用固定页面验证，不必依赖真机。
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
    /**
     * E 的跨同步课程编号读取（`repository.knownSourceCourseIds`）。补抓历史作业时与本次首页
     * 课程取并集；默认空集合表示不补抓，行为与旧版一致。
     */
    private val knownSourceCourses: suspend (String) -> Set<String> = { emptySet() },
    /** 可注入的 GET（生产为 [SchoolHttp.get]）：便于 JVM 单测用固定页面验证，不改变请求策略。 */
    private val httpGet: suspend (url: String, cookie: String?, connectTimeout: Int, readTimeout: Int) -> HttpReply =
        { url, cookie, connectTimeout, readTimeout ->
            SchoolHttp.get(url, cookie, connectTimeout, readTimeout)
        },
) : SchoolAdapter {

    override val schoolId: String get() = schoolProvider().id

    override suspend fun checkSession(): SessionState = withContext(Dispatchers.IO) {
        val school = schoolProvider()
        try {
            val reply = httpGet(school.portalUrl, cookieProvider(school.portalUrl),
                SchoolRule.CONNECT_TIMEOUT_MILLIS, SchoolRule.READ_TIMEOUT_MILLIS)
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
            val homepage = convergeTaskPages(url, taskParser.parseTasks(body))
            val parsed = backfillTaskIndex(school, homepage)
            // 未登录时 MYSTU 会返回登录页或空壳：同样是“0 条 + 警告”，必须报失败，
            // 否则会显示“作业同步成功、0 条作业”。
            if (parsed.tasks.isEmpty() && parsed.warnings.isNotEmpty()) {
                error(parsed.warnings.joinToString("；"))
            }
            ModuleResult.Success(parsed.tasks, sourceCourseIds = parsed.sourceCourseIds)
        }
    }

    /**
     * D 2026-11-21 兜底：MYSTU 首页快照随平台缓存波动，可能整门课消失/重现。
     * 同一次同步内再抓一页与已有课程块 ID 集合取并集，仍有新增才继续（≤[TASK_SNAPSHOT_MAX_PAGES]
     * 次封顶），集合不再新增即停。附加抓取失败/超时降级为已有结果，不影响主流程；
     * 打开时全量拉取的口径不变，不引入轮询。
     */
    private suspend fun convergeTaskPages(url: String, first: TaskParse): TaskParse {
        var pages = listOf(first)
        var ids = first.sourceCourseIds
        for (attempt in 2..TASK_SNAPSHOT_MAX_PAGES) {
            val next = withTimeoutOrNull(TASK_SNAPSHOT_TIMEOUT_MS) { refetchPage(url) } ?: break
            pages += next
            val union = ids + next.sourceCourseIds
            if (union.size == ids.size) break // 课程集合不再新增：页面稳定
            ids = union
        }
        return mergeTaskPages(pages)
    }

    private suspend fun refetchPage(url: String): TaskParse? = try {
        val reply = httpGet(url, cookieProvider(url),
            SchoolRule.CONNECT_TIMEOUT_MILLIS, SchoolRule.READ_TIMEOUT_MILLIS)
        dumpForDebug(url, reply.body)
        if (reply.code in 200..299 && !SchoolRule.looksLikeLoginPage(reply.body)) {
            taskParser.parseTasks(reply.body)
        } else null
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: IOException) {
        null
    }

    /**
     * P2 历史作业补抓（C 2026-11-25）。
     *
     * 首页只展示“需要留意”的作业：已提交或截止较早的作业会整条移出首页，首次导入前
     * 也未缓存过，于是永远补不回来。E 的 `school_course_cache` 跨同步保存了见过的课程块编号，
     * 这里对「本次首页课程 ∪ 历史课程」逐课抓一次索引页（`mod/assign/index.php?id=`），
     * 按作业 ID 去重后与首页结果合并。
     *
     * 边界：
     * - 预算 [TASK_INDEX_MAX_COURSES] 门课 / [TASK_INDEX_BUDGET_MS] 总时长，单课超时
     *   [TASK_INDEX_REQUEST_TIMEOUT_MS]；仍属“打开时全量拉取”，不引入后台轮询；
     * - 单课失败只跳过该课；连续 [TASK_INDEX_MISS_TOLERANCE] 门课都解析不到作业行，
     *   视为索引页结构与预期不同，本轮停止，避免白耗请求；
     * - 合并顺序首页优先，同一作业 ID 不会被索引页覆盖，重复同步结果稳定。
     */
    private suspend fun backfillTaskIndex(school: SchoolInfo, homepage: TaskParse): TaskParse {
        val template = school.taskIndexUrlTemplate ?: return homepage
        val known = runCatching { knownSourceCourses(school.id) }.getOrDefault(emptySet())
        val namesByCourse = homepage.tasks.mapNotNull { task ->
            val id = task.sourceCourseId
            val name = task.sourceCourseName
            if (id != null && name != null) id to name else null
        }.toMap()
        val courseIds = (homepage.sourceCourseIds + known)
            .filter(::isCourseId)
            .distinct()
            // 本次首页没出现的课程优先：历史作业最可能藏在“整课被首页移出”的情况里。
            .sortedBy { if (it in homepage.sourceCourseIds) 1 else 0 }
            .take(TASK_INDEX_MAX_COURSES)
        if (courseIds.isEmpty()) return homepage

        val deadline = System.nanoTime() + TASK_INDEX_BUDGET_MS * 1_000_000
        var missStreak = 0
        val extra = mutableListOf<ImportedTask>()
        for (courseId in courseIds) {
            if (System.nanoTime() > deadline) break
            val page = fetchTaskIndex(template, courseId, namesByCourse[courseId]) ?: continue
            if (page.tasks.isEmpty()) {
                if (++missStreak >= TASK_INDEX_MISS_TOLERANCE) break
                continue
            }
            missStreak = 0
            extra += page.tasks
        }
        if (extra.isEmpty()) return homepage
        return mergeTaskPages(
            listOf(homepage, TaskParse(extra, missingIdentity = 0, warnings = emptyList(),
                sourceCourseIds = homepage.sourceCourseIds)),
        )
    }

    private suspend fun fetchTaskIndex(template: String, courseId: String, courseName: String?): TaskParse? {
        val url = template.replace(COURSE_ID_PLACEHOLDER, courseId)
        return try {
            val reply = withTimeoutOrNull(TASK_INDEX_REQUEST_TIMEOUT_MS) {
                httpGet(url, cookieProvider(url), TASK_INDEX_CONNECT_TIMEOUT_MS, TASK_INDEX_READ_TIMEOUT_MS)
            } ?: return null
            dumpForDebug(url, reply.body)
            if (reply.code !in 200..299 || SchoolRule.looksLikeLoginPage(reply.body)) return null
            taskParser.parseTaskIndex(reply.body, courseId, courseName)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    /** 课程块编号只作为索引页 URL 参数使用，必须是纯数字，避免拼接任意地址。 */
    private fun isCourseId(value: String): Boolean =
        value.isNotBlank() && value.all { it in '0'..'9' }

    /** 多页快照合并：按 externalId 去重（同 ID 保留首页内容），缺 ID 条目按标题+截止+课程折叠。 */
    internal fun mergeTaskPages(pages: List<TaskParse>): TaskParse {
        if (pages.size == 1) return pages.single()
        val byId = LinkedHashMap<String, ImportedTask>()
        val unidentified = LinkedHashMap<String, ImportedTask>()
        val warnings = LinkedHashSet<String>()
        val ids = LinkedHashSet<String>()
        for (page in pages) {
            warnings += page.warnings
            ids += page.sourceCourseIds
            for (task in page.tasks) {
                val id = task.externalId
                if (id != null) byId.putIfAbsent(id, task)
                else {
                    val key = "${task.fields.title}|${task.fields.dueAt}|${task.sourceCourseId}"
                    unidentified.putIfAbsent(key, task)
                }
            }
        }
        return TaskParse(
            tasks = byId.values + unidentified.values,
            missingIdentity = unidentified.size,
            warnings = warnings.toList(),
            sourceCourseIds = ids,
        )
    }

    private suspend fun <T> fetch(url: String, parse: suspend (String) -> ModuleResult<T>): ModuleResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val reply = httpGet(url, cookieProvider(url),
                    SchoolRule.CONNECT_TIMEOUT_MILLIS, SchoolRule.READ_TIMEOUT_MILLIS)
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

private const val TASK_SNAPSHOT_MAX_PAGES = 3
private const val TASK_SNAPSHOT_TIMEOUT_MS = 10_000L

// P2 历史作业补抓的请求预算（C 2026-11-25）：每课 1 请求，总时长留在 E 的 30s 模块超时内。
private const val TASK_INDEX_MAX_COURSES = 12
private const val TASK_INDEX_MISS_TOLERANCE = 2
private const val TASK_INDEX_BUDGET_MS = 6_000L
private const val TASK_INDEX_REQUEST_TIMEOUT_MS = 4_000L
private const val TASK_INDEX_CONNECT_TIMEOUT_MS = 3_000
private const val TASK_INDEX_READ_TIMEOUT_MS = 4_000
private const val COURSE_ID_PLACEHOLDER = "{id}"
