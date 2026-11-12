package com.coursekeeper.course

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coursekeeper.data.CourseDraft
import com.coursekeeper.data.ScheduleDraft
import com.coursekeeper.data.model.ScheduleWeeks
import com.coursekeeper.ui.components.EmptyState
import com.coursekeeper.ui.components.KeeperCard
import com.coursekeeper.ui.theme.KeeperBlue
import com.coursekeeper.ui.theme.KeeperMuted
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val DayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val ConflictColor = Color(0xFFB3261E)
private val DatePattern = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

/**
 * 课表页（成员 B，第 3–4 步）。
 *
 * 五种状态：正常周视图 / 未设学期 / 不在教学周 / 空课表 / 读取失败。
 * 只负责展示与表单交互，校验与版本冲突由 ViewModel 和 E 的 Repository 负责。
 */
@Composable
fun CourseScreen(
    state: CourseUiState,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCurrentWeek: () -> Unit,
    onSaveSemester: (LocalDate, Int, (String?) -> Unit) -> Unit,
    onSaveCourse: (CourseSummary?, CourseDraft, (String?) -> Unit) -> Unit,
    onDeleteCourse: (CourseSummary, (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var semesterDialog by rememberSaveable { mutableStateOf(false) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorCourseId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailCourseId by rememberSaveable { mutableStateOf<String?>(null) }
    var banner by rememberSaveable { mutableStateOf<String?>(null) }

    val detail = state.courses.firstOrNull { it.id == detailCourseId }
    val editing = state.courses.firstOrNull { it.id == editorCourseId }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        WeekHeader(
            state = state,
            onPreviousWeek = onPreviousWeek,
            onNextWeek = onNextWeek,
            onCurrentWeek = onCurrentWeek,
        )
        banner?.let {
            Text(it, color = ConflictColor, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        }

        when {
            state.loading -> LoadingBody()
            state.error != null -> ErrorBody(state.error)
            state.phase == CoursePhase.NO_SEMESTER -> SemesterMissingBody { semesterDialog = true }
            else -> {
                if (state.phase == CoursePhase.NOT_IN_TERM) {
                    AuthBanner("当前不在教学周内，可翻周查看课表。")
                }
                if (state.conflicts.isNotEmpty()) {
                    ConflictBanner(state.conflicts)
                }
                if (state.isEmptyWeek) {
                    EmptyState(
                        title = "本周暂无课程",
                        detail = "可以手动添加课程和排课；学期起始日设置后按教学周显示。",
                        action = "＋ 添加课程",
                        onAction = { editorCourseId = null; editorOpen = true },
                    )
                } else {
                    TimetableBody(state, Modifier.weight(1f)) { entry ->
                        detailCourseId = entry.courseId
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { semesterDialog = true }, modifier = Modifier.weight(1f)) {
                        Text("学期设置")
                    }
                    Button(onClick = { editorCourseId = null; editorOpen = true }, modifier = Modifier.weight(1f)) {
                        Text("＋ 添加课程")
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (semesterDialog) {
        SemesterDialog(
            initialStartsOn = LocalDate.now(),
            initialWeekCount = state.weekCount.takeIf { it > 0 } ?: 20,
            onDismiss = { semesterDialog = false },
            onConfirm = { date, weeks, onResult ->
                onSaveSemester(date, weeks) { error ->
                    onResult(error)
                    if (error == null) semesterDialog = false
                }
            },
        )
    }

    if (editorOpen) {
        CourseEditorDialog(
            course = editing,
            semesterId = state.semesterId,
            onDismiss = { editorOpen = false },
            onSave = { draft, onResult ->
                onSaveCourse(editing, draft) { error ->
                    onResult(error)
                    if (error == null) editorOpen = false
                }
            },
        )
    }

    detail?.let { course ->
        CourseDetailDialog(
            course = course,
            onDismiss = { detailCourseId = null },
            onEdit = { detailCourseId = null; editorCourseId = course.id; editorOpen = true },
            onDelete = {
                onDeleteCourse(course) { error ->
                    if (error != null) banner = error else detailCourseId = null
                }
            },
        )
    }
}

@Composable
private fun WeekHeader(
    state: CourseUiState,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCurrentWeek: () -> Unit,
) {
    Column(Modifier.padding(top = 16.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("我的课表", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (state.weekCount == 0) "尚未设置学期" else "第 ${state.selectedWeek} 周 · ${state.weekLabel}",
                    fontSize = 13.sp,
                    color = KeeperMuted,
                )
            }
            Text(
                "${state.entryCount} 条排课",
                fontSize = 12.sp,
                color = KeeperMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPreviousWeek, enabled = state.canGoPrevious) { Text("上一周") }
            OutlinedButton(onClick = onCurrentWeek, enabled = state.currentWeek != null && !state.isCurrentWeek) {
                Text("本周")
            }
            OutlinedButton(onClick = onNextWeek, enabled = state.canGoNext) { Text("下一周") }
        }
    }
}

@Composable
private fun TimetableBody(
    state: CourseUiState,
    modifier: Modifier = Modifier,
    onEntryClick: (CourseEntry) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        (1..7).forEach { day ->
            val entries = state.days[day].orEmpty()
            if (entries.isNotEmpty()) {
                item(key = "day-$day") {
                    Text(
                        "${DayNames[day - 1]} · ${entries.size} 节",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = KeeperMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(entries, key = { it.scheduleId }) { entry ->
                    CourseEntryCard(entry, state.conflicts.any { it.first.scheduleId == entry.scheduleId || it.second.scheduleId == entry.scheduleId }) {
                        onEntryClick(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseEntryCard(entry: CourseEntry, conflicted: Boolean, onClick: () -> Unit) {
    KeeperCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = if (conflicted) ConflictColor.copy(alpha = 0.12f) else KeeperBlue.copy(alpha = 0.10f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    if (entry.startPeriod == entry.endPeriod) "${entry.startPeriod} 节"
                    else "${entry.startPeriod}-${entry.endPeriod} 节",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                    color = if (conflicted) ConflictColor else KeeperBlue,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title.ifBlank { "未命名课程" }, fontWeight = FontWeight.Bold)
                val meta = listOfNotNull(entry.location?.takeIf { it.isNotBlank() }, entry.teacher?.takeIf { it.isNotBlank() })
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), fontSize = 12.sp, color = KeeperMuted)
            }
            if (conflicted) Text("冲突", fontSize = 12.sp, color = ConflictColor)
            TextButton(onClick = onClick) { Text("详情") }
        }
    }
}

@Composable
private fun ConflictBanner(conflicts: List<CourseConflict>) {
    val first = conflicts.first()
    val text = if (conflicts.size == 1) {
        "与「${first.second.courseTitle.ifBlank { "其他课程" }}」时间冲突：${first.weeksText} 第 ${first.periods.first}-${first.periods.last} 节"
    } else {
        "本周有 ${conflicts.size} 处时间冲突，点课程详情复核"
    }
    Surface(
        color = ConflictColor.copy(alpha = 0.08f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("⚠ $text", color = ConflictColor, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun AuthBanner(text: String) {
    Surface(
        color = KeeperMuted.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, fontSize = 12.sp, color = KeeperMuted, modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun LoadingBody() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorBody(message: String) {
    KeeperCard {
        Text("加载失败", fontWeight = FontWeight.Bold)
        Text(message, fontSize = 12.sp, color = KeeperMuted)
    }
}

@Composable
private fun SemesterMissingBody(onSetup: () -> Unit) {
    EmptyState(
        title = "待设置学期",
        detail = "设置本学期第 1 周的周一和周数后，才能真正算出今天上什么课。",
        action = "设置学期起始日",
        onAction = onSetup,
    )
}

@Composable
private fun CourseDetailDialog(
    course: CourseSummary,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(course.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("教师：${course.teacher?.takeIf { it.isNotBlank() } ?: "未填写"}", fontSize = 13.sp)
                course.schedules.forEach { schedule ->
                    Text(
                        "${DayNames[schedule.dayOfWeek - 1]} 第 ${schedule.startPeriod}-${schedule.endPeriod} 节 · " +
                            "${ScheduleWeeks.format(schedule.weeks)} 周 · ${schedule.location?.takeIf { it.isNotBlank() } ?: "地点未定"}",
                        fontSize = 12.sp,
                        color = KeeperMuted,
                    )
                }
                if (confirmDelete) {
                    Text("删除后该课程的所有排课一并移除，关联任务保留。", color = ConflictColor, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            if (confirmDelete) {
                TextButton(onClick = onDelete) { Text("确认删除", color = ConflictColor) }
            } else {
                TextButton(onClick = onEdit) { Text("编辑") }
            }
        },
        dismissButton = {
            if (confirmDelete) {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            } else {
                TextButton(onClick = { confirmDelete = true }) { Text("删除", color = ConflictColor) }
            }
        },
    )
}

@Composable
private fun SemesterDialog(
    initialStartsOn: LocalDate,
    initialWeekCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, Int, (String?) -> Unit) -> Unit,
) {
    var dateText by rememberSaveable { mutableStateOf(initialStartsOn.toString()) }
    var weekText by rememberSaveable { mutableStateOf(initialWeekCount.toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("学期设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("第 1 周从哪天开始（会自动对齐到该周周一）。", fontSize = 12.sp, color = KeeperMuted)
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("起始日 yyyy-MM-dd") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = weekText,
                    onValueChange = { weekText = it },
                    label = { Text("学期周数") },
                    singleLine = true,
                )
                error?.let { Text(it, color = ConflictColor, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val date = runCatching { LocalDate.parse(dateText.trim(), DatePattern) }.getOrNull()
                val weeks = weekText.trim().toIntOrNull()
                when {
                    date == null -> error = "请填写有效日期，例如 2026-09-07"
                    weeks == null || weeks !in 1..64 -> error = "学期周数需在 1—64 之间"
                    else -> onConfirm(date, weeks) { message ->
                        error = message
                        if (message == null) onDismiss()
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 编辑对话框中的一条排课草稿。 */
private data class ScheduleRow(
    val dayOfWeek: Int = 1,
    val startPeriod: String = "1",
    val endPeriod: String = "2",
    val weeksText: String = "1-16",
    val location: String = "",
)

@Composable
private fun CourseEditorDialog(
    course: CourseSummary?,
    semesterId: String?,
    onDismiss: () -> Unit,
    onSave: (CourseDraft, (String?) -> Unit) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(course?.title.orEmpty()) }
    var teacher by rememberSaveable { mutableStateOf(course?.teacher.orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = remember(course) {
        mutableStateListOf<ScheduleRow>().apply {
            if (course == null) {
                add(ScheduleRow())
            } else {
                course.schedules.forEach { schedule ->
                    add(
                        ScheduleRow(
                            dayOfWeek = schedule.dayOfWeek,
                            startPeriod = schedule.startPeriod.toString(),
                            endPeriod = schedule.endPeriod.toString(),
                            weeksText = ScheduleWeeks.format(schedule.weeks),
                            location = schedule.location.orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (course == null) "添加课程" else "编辑课程") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("课程名称（必填）") }, singleLine = true,
                )
                OutlinedTextField(
                    value = teacher, onValueChange = { teacher = it },
                    label = { Text("教师（可留空）") }, singleLine = true,
                )
                Text("排课", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                rows.forEachIndexed { index, row ->
                    ScheduleRowEditor(
                        row = row,
                        onChange = { rows[index] = it },
                        onRemove = { if (rows.size > 1) rows.removeAt(index) },
                    )
                }
                TextButton(onClick = { rows.add(ScheduleRow()) }) { Text("＋ 再加一条排课") }
                error?.let { Text(it, color = ConflictColor, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = mutableListOf<ScheduleDraft>()
                var problem: String? = null
                rows.forEachIndexed { index, row ->
                    val start = row.startPeriod.trim().toIntOrNull()
                    val end = row.endPeriod.trim().toIntOrNull()
                    val weeks = WeekText.parse(row.weeksText)
                    when {
                        start == null || end == null || start < 1 || end < start ->
                            problem = "第 ${index + 1} 条排课的节次不正确"
                        weeks.isEmpty ->
                            problem = "第 ${index + 1} 条排课的周次无法识别"
                        else -> parsed += ScheduleDraft(
                            dayOfWeek = row.dayOfWeek,
                            startPeriod = start,
                            endPeriod = end,
                            weeks = weeks.weeks,
                            location = row.location.trim().ifBlank { null },
                            teacher = null,
                        )
                    }
                }
                val message = problem
                if (message != null) {
                    error = message
                } else {
                    val draft = CourseDraft(
                        title = title.trim(),
                        semesterId = semesterId,
                        teacher = teacher.trim().ifBlank { null },
                        schedules = parsed,
                    )
                    onSave(draft) { result ->
                        error = result
                        if (result == null) onDismiss()
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ScheduleRowEditor(
    row: ScheduleRow,
    onChange: (ScheduleRow) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0x0F000000), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            DayNames.forEachIndexed { index, label ->
                FilterChip(
                    selected = row.dayOfWeek == index + 1,
                    onClick = { onChange(row.copy(dayOfWeek = index + 1)) },
                    label = { Text(label, fontSize = 11.sp) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = row.startPeriod,
                onValueChange = { onChange(row.copy(startPeriod = it)) },
                label = { Text("起始节") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = row.endPeriod,
                onValueChange = { onChange(row.copy(endPeriod = it)) },
                label = { Text("结束节") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = row.weeksText,
            onValueChange = { onChange(row.copy(weeksText = it)) },
            label = { Text("周次") },
            supportingText = { Text("例：1-16、9,11-13、1-16(单)", fontSize = 11.sp) },
            singleLine = true,
        )
        OutlinedTextField(
            value = row.location,
            onValueChange = { onChange(row.copy(location = it)) },
            label = { Text("地点（可留空）") },
            singleLine = true,
        )
        TextButton(onClick = onRemove) { Text("删除这条排课", fontSize = 12.sp) }
    }
}
