package com.coursekeeper.task

import com.coursekeeper.data.model.CourseRecord
import com.coursekeeper.data.model.TaskRecord
import com.coursekeeper.data.model.TaskType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 任务侧 UI 契约（成员 D）。
 *
 * 口径来自《任务字段与通知规则 v1.0》：
 * - 状态不存储，一律由代码派生（第一节）
 * - 排序：待办按 dueAt 升序、dueAt 为空排最前；已完成排最后（第三节）
 * - 日期归属按设备本地时区、00:00 为日界（第一节补记）
 */

enum class TaskStatus { PENDING, COMPLETED, OVERDUE }

enum class TaskStatusFilter(val label: String) { OPEN("待办"), DONE("已完成"), ALL("全部") }
enum class TaskDateFilter(val label: String) { ALL("全部日期"), DUE_TODAY("今日截止"), COMPLETED_TODAY("今日完成") }
data class TaskQuery(val status: TaskStatusFilter = TaskStatusFilter.OPEN, val type: TaskType? = null, val date: TaskDateFilter = TaskDateFilter.ALL)

/** 一行任务在界面上的全部信息；record 保留原始版本号供写入校验。 */
data class TaskView(
    val record: TaskRecord,
    val status: TaskStatus,
    val dueLabel: String,
    val courseLabel: String,
)

val TaskType.label: String
    get() = when (this) {
        TaskType.HOMEWORK -> "课程作业"
        TaskType.LAB_REPORT -> "实验报告"
        TaskType.CLUB -> "社团活动"
        TaskType.WORK -> "部门工作"
        TaskType.OTHER -> "其他"
    }

/** v1.0 §1：completedAt 非空即已完成；否则 dueAt 已过即逾期；其余待办（含无截止时间）。 */
fun TaskRecord.statusAt(now: LocalDateTime): TaskStatus = when {
    completedAt != null -> TaskStatus.COMPLETED
    dueAt != null && dueAt.isBefore(now) -> TaskStatus.OVERDUE
    else -> TaskStatus.PENDING
}

/**
 * 展示用时间短语；逾期时前缀"已逾期"。无截止时间返回"截止待定"。
 * 注意：该函数面向**未完成**任务的行内展示；已完成任务由界面固定显示"已完成"，
 * 不应把本函数的返回值直接用在已完成行上（否则已完成且无截止的任务会显示"截止待定"）。
 */
fun TaskRecord.dueLabel(now: LocalDateTime): String {
    val due = dueAt ?: return "截止待定"
    val dueDate = due.toLocalDate()
    val today = now.toLocalDate()
    val clock = due.format(DateTimeFormatter.ofPattern("HH:mm"))
    val text = when (dueDate) {
        today -> "今天 $clock"
        today.plusDays(1) -> "明天 $clock"
        today.minusDays(1) -> "昨天 $clock"
        else -> "${dueDate.monthValue}/${dueDate.dayOfMonth} $clock"
    }
    return if (completedAt == null && due.isBefore(now)) "已逾期 · $text" else text
}

/** 课程已删时保留关联并标注，不级联删除任务（v1.0 六-B）。 */
fun TaskRecord.courseLabel(courses: List<CourseRecord>): String {
    val id = courseId ?: return "未关联课程"
    return courses.firstOrNull { it.id == id }?.title ?: "课程已删除"
}

fun TaskRecord.toView(courses: List<CourseRecord>, now: LocalDateTime): TaskView =
    TaskView(record = this, status = statusAt(now), dueLabel = dueLabel(now), courseLabel = courseLabel(courses))

/**
 * v1.0 §3 排序：待办在前、已完成在后。
 * 待办内部：截止待定最前 → 其余按 dueAt 升序（逾期自然浮到前面）。
 * 已完成内部：按完成时间倒序。
 */
fun List<TaskRecord>.sortedForDisplay(): List<TaskRecord> {
    val open = filter { it.completedAt == null }.sortedWith(compareBy(nullsFirst()) { it.dueAt })
    val done = filter { it.completedAt != null }.sortedByDescending { it.completedAt }
    return open + done
}

/** 状态与类型是"与"关系；类型为 null 表示不限。 */
fun List<TaskView>.visible(status: TaskStatusFilter, type: TaskType?, date: TaskDateFilter = TaskDateFilter.ALL, now: LocalDateTime = LocalDateTime.now()): List<TaskView> = filter { view ->
    val statusMatched = when (status) {
        TaskStatusFilter.OPEN -> view.status != TaskStatus.COMPLETED
        TaskStatusFilter.DONE -> view.status == TaskStatus.COMPLETED
        TaskStatusFilter.ALL -> true
    }
    val dateMatched = when(date) {
        TaskDateFilter.ALL -> true
        TaskDateFilter.DUE_TODAY -> view.record.dueAt?.toLocalDate() == now.toLocalDate()
        TaskDateFilter.COMPLETED_TODAY -> view.record.completedAt?.toLocalDate() == now.toLocalDate()
    }
    statusMatched && dateMatched && (type == null || view.record.type == type)
}

/** 批量选择入口只在「已完成」「全部」两个视图提供（待办视图不做批量删除）。 */
val TaskStatusFilter.supportsBatchSelect: Boolean
    get() = this == TaskStatusFilter.DONE || this == TaskStatusFilter.ALL

/**
 * 「全选」的开关语义：当前可见任务已全部在选中集 → 取消这批选择；
 * 否则 → 把全部可见项加入选中集。可见集即当前筛选结果；切换筛选时整体清空（ViewModel 负责）。
 */
fun toggledSelection(visible: List<TaskView>, selected: Set<String>): Set<String> {
    val visibleIds = visible.mapTo(HashSet()) { it.record.id }
    return if (visibleIds.isNotEmpty() && visibleIds.all { it in selected }) {
        selected - visibleIds
    } else {
        selected + visibleIds
    }
}

/** 列表里已消失的任务（被同步删除、或状态变化移出当前视图）自动退出选中集。 */
fun pruneSelection(selected: Set<String>, visible: List<TaskView>): Set<String> {
    val visibleIds = visible.mapTo(HashSet()) { it.record.id }
    return selected.filterTo(HashSet()) { it in visibleIds }
}
