package com.coursekeeper.data

import com.coursekeeper.data.model.*
import java.time.LocalDateTime

enum class DataError(val userMessage: String) {
    TASK_NOT_FOUND("任务不存在，请刷新后重试"),
    EMPTY_TITLE("请填写任务名称"),
    TITLE_TOO_LONG("任务名称过长（≤60 字）"),
    VERSION_CONFLICT("内容已被修改，请刷新后重试"),
    COURSE_NOT_FOUND("关联的课程不存在"),
    INVALID_RECORD("数据格式不正确，请检查后重试"),
    REQUEST_ID_REUSED("请求编号已用于其他操作，请重新提交"),
    SYNC_IDENTITY_CONFLICT("同步结果包含冲突的标识，请重新同步"),
}

class DataException(val code: DataError) : IllegalArgumentException(code.userMessage)
internal fun checkData(valid: Boolean, code: DataError) { if (!valid) throw DataException(code) }

data class TaskDraft(
    val title: String,
    val type: TaskType,
    val dueAt: LocalDateTime? = null,
    val courseId: String? = null,
)

data class WriteReceipt(val id: String, val version: Long, val changed: Boolean, val replayed: Boolean = false)

data class CourseDraft(
    val title: String,
    val semesterId: String? = null,
    val teacher: String? = null,
    val schedules: List<ScheduleDraft> = emptyList(),
)

data class ScheduleDraft(
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val startWeek: Int,
    val endWeek: Int,
    val weekPattern: WeekPattern = WeekPattern.ALL,
    val location: String? = null,
)
