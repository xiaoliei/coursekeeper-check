package com.coursekeeper.agent

import com.coursekeeper.data.model.TaskType

/**
 * A03 三个写工具的 wire 级契约（Q-2）：调度层注册模型工具、编码参数、映射错误码时以此为准。
 *
 * 纯 Kotlin 常量，不引 JSON 库；需要 JSON schema 时由接线层按本规格自行拼装。
 * 修改 [TaskWriteTools] 的预览签名或 [TaskWriteError] 时必须同步这里——
 * TaskWriteToolsTest 有防漂移断言（参数名对齐 TaskDraft/预览函数、错误码全枚举覆盖）。
 *
 * 依赖约定（Q-6，跨成员）：`expectedVersion` 必须来自 B 的只读查询工具（任务须带 id+version），
 * 本包不提供只读查询。
 */
object TaskWriteWire {

    /** 三个工具全部需要用户在确认 UI 上点确认后才落库（confirm 不得注册为模型工具）。 */
    const val REQUIRES_CONFIRMATION = true

    /** dueAt 的 wire 编码格式（ISO 本地时间，无秒无时区）。 */
    const val DUE_AT_FORMAT = "yyyy-MM-dd'T'HH:mm"

    /** title 长度口径：与 Repository / [TaskWriteTools] 一致（单一出处，工具侧引用本常量）。 */
    const val TITLE_MAX_LENGTH = 60

    /** type 参数允许值：直接取自 [TaskType]，枚举改名/加值自动跟随。 */
    val TASK_TYPE_VALUES: List<String> = TaskType.entries.map { it.name }

    /**
     * 单个参数的 wire 规格。
     *
     * `required` 与 `nullable` 是两个独立维度：required=true + nullable=true 表示
     * **必须显式传参但可传 null**（如 update 的 dueAt：传 null 即清空截止）。
     */
    data class ParamSpec(
        val name: String,
        val type: String,
        val required: Boolean,
        val description: String,
        val nullable: Boolean = false,
        val enumValues: List<String>? = null,
    )

    /** 单个工具的 wire 规格；name 即 [TaskWriteTool.wireName]。 */
    data class ToolSpec(
        val tool: TaskWriteTool,
        val parameters: List<ParamSpec>,
        val requiresConfirmation: Boolean = REQUIRES_CONFIRMATION,
    ) {
        val name: String get() = tool.wireName
    }

    /** create_task：draft 字段平铺；dueAt/courseId 缺省 = 暂无截止 / 不关联。 */
    val CREATE_TASK = ToolSpec(
        tool = TaskWriteTool.CREATE,
        parameters = listOf(
            ParamSpec("title", "string", required = true, description = "任务名称，去首尾空白后 1~$TITLE_MAX_LENGTH 字"),
            ParamSpec("type", "string", required = true, enumValues = TASK_TYPE_VALUES, description = "任务类型"),
            ParamSpec("dueAt", "string", required = false, nullable = true, description = "截止时间，$DUE_AT_FORMAT；缺省表示暂无截止"),
            ParamSpec("courseId", "string", required = false, nullable = true, description = "关联课程 id；缺省表示不关联"),
        ),
    )

    /**
     * update_task：**全量替换语义**（保持 A03 语义不变，patch 语义留 A04 再议）——
     * 四个 draft 字段全部必填，模型漏传等于"清空该字段"；dueAt/courseId 传 null 显式清空，
     * 预览会给出对应风险提示。
     */
    val UPDATE_TASK = ToolSpec(
        tool = TaskWriteTool.UPDATE,
        parameters = listOf(
            ParamSpec("id", "string", required = true, description = "目标任务 id（须来自只读查询工具，随任务返回 version）"),
            ParamSpec("expectedVersion", "integer", required = true, description = "调用方手里的任务版本；不符将被拒绝（VERSION_CONFLICT）"),
            ParamSpec("title", "string", required = true, description = "新任务名称（全量替换）"),
            ParamSpec("type", "string", required = true, enumValues = TASK_TYPE_VALUES, description = "新任务类型（全量替换）"),
            ParamSpec("dueAt", "string", required = true, nullable = true, description = "新截止时间，$DUE_AT_FORMAT；null = 清空截止（提醒将取消）"),
            ParamSpec("courseId", "string", required = true, nullable = true, description = "新关联课程 id；null = 解除课程关联"),
        ),
    )

    /** complete_task：completed=true 标记完成，false 恢复待办（completedAt 由 Repository 写/清）。 */
    val COMPLETE_TASK = ToolSpec(
        tool = TaskWriteTool.COMPLETE,
        parameters = listOf(
            ParamSpec("id", "string", required = true, description = "目标任务 id（须来自只读查询工具）"),
            ParamSpec("completed", "boolean", required = true, description = "true 标记完成；false 恢复待办"),
            ParamSpec("expectedVersion", "integer", required = true, description = "调用方手里的任务版本；不符将被拒绝（VERSION_CONFLICT）"),
        ),
    )

    /** 全部工具规格，顺序即建议注册顺序。 */
    val TOOLS: List<ToolSpec> = listOf(CREATE_TASK, UPDATE_TASK, COMPLETE_TASK)

    /** 按工具取规格。 */
    fun spec(tool: TaskWriteTool): ToolSpec = TOOLS.first { it.tool == tool }

    /** 结构化错误码 → wire 错误码（稳定字符串，供调度层/前端映射文案）。 */
    fun wireError(error: TaskWriteError): String = when (error) {
        TaskWriteError.NOT_FOUND -> "task_not_found"
        TaskWriteError.VERSION_CONFLICT -> "version_conflict"
        TaskWriteError.EXPIRED_TOKEN -> "token_expired"
        TaskWriteError.TOKEN_MISMATCH -> "token_invalid"
        TaskWriteError.INVALID_FIELD -> "invalid_field"
    }
}
