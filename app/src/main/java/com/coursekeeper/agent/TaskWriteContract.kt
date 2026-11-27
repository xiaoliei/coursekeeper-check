package com.coursekeeper.agent

import com.coursekeeper.data.TaskDraft
import com.coursekeeper.data.WriteReceipt
import com.coursekeeper.data.model.CourseRecord
import com.coursekeeper.data.model.TaskRecord

/**
 * Agent 任务写入（A03）：模型/调度层只能拿到**预览**，写库由本地 [TaskWriteTools.confirm] 校验凭证后执行。
 *
 * 安全边界（定版 AI 边界）：
 * - 模型不接触 SQL/Repository，只能调用工具；
 * - 确认凭证本地随机生成、单次使用、5 分钟过期、只存内存（不落盘不入库）；
 * - 确认时任务版本与预览不一致即拒绝，不静默覆盖。
 */

/** 可写工具（本轮不含 delete：与墓碑语义的交互另议）。 */
enum class TaskWriteTool(val wireName: String) {
    CREATE("create_task"),
    UPDATE("update_task"),
    COMPLETE("complete_task"),
}

/** 结构化错误码；预览阶段复用 [NOT_FOUND] 与 [INVALID_FIELD]。 */
enum class TaskWriteError {
    NOT_FOUND,
    VERSION_CONFLICT,
    EXPIRED_TOKEN,
    TOKEN_MISMATCH,
    INVALID_FIELD,
}

/** 错误 + 给模型/用户看的中文原因。 */
data class TaskWriteFailure(val error: TaskWriteError, val detail: String)

/**
 * 单个字段的前后值，显示值一律是代码计算的文本：
 * - `before == null`：字段为新增（create，或原无值改为有值）；
 * - `after == null`：**字段将被清空**（update 清掉已有值时才出现）——确认 UI 据此醒目提示（Q-4）。
 */
data class TaskWriteChange(val field: String, val before: String?, val after: String?)

/** 操作目标：更新/完成才有；create 为 null。 */
data class TaskWriteTarget(val id: String, val title: String, val version: Long)

/**
 * 凭证绑定：工具 + 任务 + 预期版本 + 变更指纹。
 *
 * confirm 可回传它做二次校验——拿 A 任务的凭证去写 B、或换了拟变更内容，
 * 都会因绑定不符得到 [TaskWriteError.TOKEN_MISMATCH]。
 */
data class TaskWriteBinding(
    val tool: TaskWriteTool,
    val targetId: String?,
    val expectedVersion: Long?,
    val changeFingerprint: String,
)

/**
 * 预览：对象＋拟变更前后值＋风险提示＋单次确认凭证。
 *
 * **只在本进程内流转**（确认 UI ↔ 工具）：含 token/binding，不得序列化给模型、不得写日志——
 * data class 默认 toString() 会带出 token。给模型只传 [TaskWriteModelView]。
 */
data class TaskWritePreview(
    val tool: TaskWriteTool,
    val target: TaskWriteTarget?,
    val changes: List<TaskWriteChange>,
    val risks: List<String>,
    val token: String,
    val expiresAtMillis: Long,
    val binding: TaskWriteBinding,
)

/** 预览结果：Ready 才会带 token；Rejected 说明该操作在预览阶段就该被拦下。 */
sealed interface TaskWritePreviewResult {
    data class Ready(val preview: TaskWritePreview) : TaskWritePreviewResult
    data class Rejected(val failure: TaskWriteFailure) : TaskWritePreviewResult
}

/**
 * 给模型看的预览视图（Q-1）：[TaskWritePreview] 的安全子集，**不含 token/expiresAt/binding**。
 *
 * 接线红线（违反即升级 P0，见质检 Q-1）：
 * - 模型工具只注册 `preview*`；[TaskWriteTools.confirm] 只能由确认 UI 的点击回调调用，
 *   **不得注册为模型工具**；
 * - 传给模型的只能是本视图（或按 [TaskWriteWire] 规格编码的结果），不得把 [TaskWritePreview]
 *   原样序列化或写日志给模型；
 * - 凭证有效期不进视图：过期由 confirm 兜底拒绝（EXPIRED_TOKEN），UI 从完整预览自取。
 */
data class TaskWriteModelView(
    val tool: TaskWriteTool,
    val target: TaskWriteTarget?,
    val changes: List<TaskWriteChange>,
    val risks: List<String>,
)

/** 从完整预览裁剪出模型视图：只保留无凭证字段，其余（token/有效期/绑定）一律不外传。 */
fun TaskWritePreview.toModelView(): TaskWriteModelView = TaskWriteModelView(
    tool = tool,
    target = target,
    changes = changes,
    risks = risks,
)

/** 确认结果：成功回执或结构化错误。 */
sealed interface TaskWriteResult {
    data class Success(val receipt: WriteReceipt) : TaskWriteResult
    data class Failure(val failure: TaskWriteFailure) : TaskWriteResult
}

/** 工具读取的窄快照：只依赖数据模型，不依赖 UI 状态类型。 */
data class TaskWriteSnapshot(
    val tasks: List<TaskRecord> = emptyList(),
    val courses: List<CourseRecord> = emptyList(),
)

/**
 * 写入侧窄接口：三个写操作**都带 requestId**（由 token 派生），
 * 即使凭证层出现意外，Repository 的写请求去重也能保证同一请求只生效一次。
 *
 * 不复用 `task.TaskGateway`：它只在 create 上暴露 requestId，update/setCompleted 没有，
 * 无法满足本轮"幂等"要求；也不改 D 的既有契约。
 */
interface TaskWriteGateway {
    suspend fun snapshot(): TaskWriteSnapshot
    suspend fun create(draft: TaskDraft, requestId: String): WriteReceipt
    suspend fun update(id: String, expectedVersion: Long, changes: TaskDraft, requestId: String): WriteReceipt
    suspend fun setCompleted(id: String, completed: Boolean, expectedVersion: Long, requestId: String): WriteReceipt
}
