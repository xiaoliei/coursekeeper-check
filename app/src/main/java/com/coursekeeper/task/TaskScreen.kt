package com.coursekeeper.task

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coursekeeper.data.DataError
import com.coursekeeper.data.TaskDraft
import com.coursekeeper.data.model.CourseRecord
import com.coursekeeper.data.model.TaskType
import com.coursekeeper.ui.components.EmptyState
import com.coursekeeper.ui.theme.KeeperBlue
import com.coursekeeper.ui.theme.KeeperInk
import com.coursekeeper.ui.theme.KeeperMuted
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val DueColor = Color(0xFF97591D)
private val OverdueColor = Color(0xFFB3261E)
private val DoneColor = Color(0xFF21856C)
private val DuePattern = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)

/**
 * 任务页（成员 D）。只负责展示与交互，业务校验由 Repository 权威执行。
 * create / update 通过 onResult 回传结果：null = 成功（关闭表单），非 null = 用户文案（表单保持打开、草稿不丢）。
 */
@Composable
fun TaskScreen(
    state: TaskUiState,
    onStatusFilter: (TaskStatusFilter) -> Unit,
    onTypeFilter: (TaskType?) -> Unit,
    onCreate: (TaskDraft, onResult: (String?) -> Unit) -> Unit,
    onUpdate: (TaskView, TaskDraft, onResult: (String?) -> Unit) -> Unit,
    onToggle: (TaskView) -> Unit,
    onDelete: (TaskView) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editorTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var editorNew by rememberSaveable { mutableStateOf(false) }
    // 打开表单时冻结版本号：提交按用户打开时看到的版本，第三方改动会得到 VERSION_CONFLICT 而不是静默覆盖
    var editorVersion by rememberSaveable { mutableStateOf(0L) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteVersion by rememberSaveable { mutableStateOf(0L) }

    // 任务从列表消失（同步删除、筛选变化）时清掉残留的弹层状态，防止"复活"
    LaunchedEffect(editorTarget, pendingDeleteId, state.tasks) {
        if (editorTarget != null && state.tasks.none { it.record.id == editorTarget }) editorTarget = null
        if (pendingDeleteId != null && state.tasks.none { it.record.id == pendingDeleteId }) pendingDeleteId = null
    }

    // 横屏/短屏只 ~230dp 可用高度，大标题与固定按钮会把列表压到看不见；此时改用紧凑头部 + 行内新建入口。
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480

    Column(modifier.fillMaxSize()) {
        // 页头作为列表首项一起滚动：横屏内容区仅约 200dp，固定页头会把列表挤到不可见。
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Column(Modifier.padding(top = 6.dp)) {
                    if (!compact) {
                        Text("任务清单", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (state.totalCount == 0) "还没有任务" else "待办 ${state.openCount} 项 · 已完成 ${state.doneCount} 项",
                            fontSize = 12.sp, color = KeeperMuted,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TaskStatusFilter.entries.forEach { filter ->
                                FilterChip(
                                    selected = state.statusFilter == filter,
                                    onClick = { onStatusFilter(filter) },
                                    label = { Text(filter.label, fontSize = 12.sp) },
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TypeFilterMenu(state.typeFilter, onTypeFilter)
                        if (compact) {
                            TextButton(onClick = { editorNew = true; editorTarget = null }) { Text("＋ 新建", fontSize = 13.sp) }
                        }
                    }
                }
            }

            if (state.tasks.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFFEBEFF6), RoundedCornerShape(16.dp)).padding(16.dp)) {
                        if (state.totalCount == 0) {
                            EmptyState("还没有任务", "点击下方按钮新建；截止待定的任务会排在最前面。")
                        } else {
                            EmptyState("当前筛选下没有任务", "可以切换状态或类型筛选。")
                        }
                    }
                }
            } else {
                items(state.tasks, key = { it.record.id }) { view ->
                    TaskCard(
                        view = view,
                        onToggle = { onToggle(view) },
                        onEdit = {
                            editorTarget = view.record.id
                            editorVersion = view.record.version
                            editorNew = false
                        },
                        onDelete = {
                            pendingDeleteId = view.record.id
                            pendingDeleteVersion = view.record.version
                        },
                    )
                }
            }
        }

        if (!compact) {
            Button(
                onClick = { editorNew = true; editorTarget = null },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp).heightIn(min = 48.dp),
            ) { Text("＋ 新建任务") }
        }
    }

    val editing = state.tasks.firstOrNull { it.record.id == editorTarget }?.let { view ->
        view.copy(record = view.record.copy(version = editorVersion))
    }
    if (editorNew || editing != null) {
        TaskEditorDialog(
            initial = if (editorNew) null else editing,
            courses = state.courses,
            onDismiss = { editorNew = false; editorTarget = null },
            onSubmit = { draft, onResult ->
                if (editorNew) {
                    onCreate(draft, onResult)
                } else {
                    editing?.let { onUpdate(it, draft, onResult) } ?: onResult(DataError.TASK_NOT_FOUND.userMessage)
                }
            },
        )
    }

    val deleting = state.tasks.firstOrNull { it.record.id == pendingDeleteId }?.let { view ->
        view.copy(record = view.record.copy(version = pendingDeleteVersion))
    }
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("删除任务") },
            text = { Text("确定删除「${deleting.record.title}」？删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = { onDelete(deleting); pendingDeleteId = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun TypeFilterMenu(selected: TaskType?, onSelect: (TaskType?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("类型：${selected?.label ?: "全部"}", fontSize = 12.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("全部类型", fontSize = 13.sp) }, onClick = { expanded = false; onSelect(null) })
            TaskType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.label, fontSize = 13.sp) },
                    onClick = { expanded = false; onSelect(type) },
                )
            }
        }
    }
}

@Composable
private fun TaskCard(view: TaskView, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val done = view.status == TaskStatus.COMPLETED
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFEBEFF6), RoundedCornerShape(14.dp)).padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = done,
            onCheckedChange = { onToggle() },
            modifier = Modifier.semantics {
                contentDescription = (if (done) "恢复待办：" else "完成：") + view.record.title
            },
        )
        Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                view.record.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = if (done) KeeperMuted else KeeperInk,
            )
            Text("${view.record.type.label} · ${view.courseLabel}", fontSize = 11.sp, color = KeeperMuted)
            Text(
                if (done) "已完成" else view.dueLabel,
                fontSize = 11.sp,
                color = when {
                    done -> DoneColor
                    view.status == TaskStatus.OVERDUE -> OverdueColor
                    view.record.dueAt == null -> KeeperBlue
                    else -> DueColor
                },
            )
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋯", fontSize = 18.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("编辑", fontSize = 13.sp) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("删除", fontSize = 13.sp) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun TaskEditorDialog(
    initial: TaskView?,
    courses: List<CourseRecord>,
    onDismiss: () -> Unit,
    onSubmit: (TaskDraft, onResult: (String?) -> Unit) -> Unit,
) {
    val record = initial?.record
    var title by rememberSaveable { mutableStateOf(record?.title ?: "") }
    var type by rememberSaveable { mutableStateOf(record?.type ?: TaskType.HOMEWORK) }
    var courseId by rememberSaveable { mutableStateOf(record?.courseId ?: "") }
    var undated by rememberSaveable { mutableStateOf(record?.dueAt == null) }
    var dueText by rememberSaveable {
        mutableStateOf(
            record?.dueAt?.format(DuePattern)
                ?: LocalDateTime.now().toLocalDate().atTime(23, 59).format(DuePattern)
        )
    }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建任务" else "编辑任务") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("任务名称") }, singleLine = true,
                    supportingText = { Text("${title.trim().length}/60", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("任务类型", fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TaskType.entries.forEach { candidate ->
                        FilterChip(
                            selected = type == candidate,
                            onClick = { type = candidate },
                            label = { Text(candidate.label, fontSize = 12.sp) },
                        )
                    }
                }
                Text("关联课程", fontSize = 12.sp)
                if (courses.isEmpty()) {
                    Text("暂无课程可关联", fontSize = 11.sp, color = KeeperMuted)
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = courseId.isEmpty(), onClick = { courseId = "" }, label = { Text("不关联", fontSize = 12.sp) })
                        courses.forEach { course ->
                            FilterChip(
                                selected = courseId == course.id,
                                onClick = { courseId = course.id },
                                label = { Text(course.title, fontSize = 12.sp) },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("截止时间待定", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(checked = undated, onCheckedChange = { undated = it })
                }
                if (!undated) {
                    OutlinedTextField(
                        value = dueText, onValueChange = { dueText = it },
                        label = { Text("截止时间") }, singleLine = true,
                        supportingText = { Text("格式：2026-11-09 23:59", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                buildTaskDraft(title, type, courseId.ifBlank { null }, undated, dueText).fold(
                    onSuccess = { draft ->
                        error = null
                        onSubmit(draft) { result ->
                            if (result == null) onDismiss() else error = result
                        }
                    },
                    onFailure = { error = it.message },
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 界面侧预校验；文案引用 DataError 常量，与权威校验同源。日期解析错误无对应错误码，用同一句示例文案。 */
internal fun buildTaskDraft(
    title: String,
    type: TaskType,
    courseId: String?,
    undated: Boolean,
    dueText: String,
): Result<TaskDraft> = runCatching {
    val trimmed = title.trim()
    require(trimmed.isNotEmpty()) { DataError.EMPTY_TITLE.userMessage }
    require(trimmed.length <= 60) { DataError.TITLE_TOO_LONG.userMessage }
    val due = if (undated) null else parseDue(dueText)
    TaskDraft(title = trimmed, type = type, dueAt = due, courseId = courseId)
}

internal fun parseDue(text: String): LocalDateTime = try {
    LocalDateTime.parse(text.trim(), DuePattern)
} catch (_: Exception) {
    throw IllegalArgumentException("请填写有效的截止时间，例如 2026-11-09 23:59")
}
