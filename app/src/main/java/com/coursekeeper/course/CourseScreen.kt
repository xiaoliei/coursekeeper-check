package com.coursekeeper.course

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coursekeeper.data.CourseDraft
import com.coursekeeper.data.ScheduleDraft
import com.coursekeeper.data.model.PeriodRecord
import com.coursekeeper.data.model.ScheduleWeeks
import com.coursekeeper.ui.components.EmptyState
import com.coursekeeper.ui.components.KeeperCard
import com.coursekeeper.ui.theme.KeeperBlue
import com.coursekeeper.ui.theme.KeeperInk
import com.coursekeeper.ui.theme.KeeperMuted
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlin.math.abs

private val DayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val ConflictColor = Color(0xFFB3261E)
private val GridLineColor = Color(0xFFE5EBF5)
private val DatePattern = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
private val TimePattern = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

/** 整周网格的课程卡配色（图 1 样式：按课程稳定着色、白字）。 */
private val GridPalette = listOf(
    Color(0xFF4C9AFF), // 蓝
    Color(0xFF43A786), // 绿
    Color(0xFFE6A23C), // 橙
    Color(0xFF9B7BE5), // 紫
    Color(0xFFE57373), // 红
    Color(0xFF56B4D3), // 青
)

private fun gridColor(courseId: String): Color = GridPalette[abs(courseId.hashCode()) % GridPalette.size]

/** 整周网格：每节一行的高度；卡片按起始节绝对定位、跨节撑高。 */
private val GridRowHeight = 52.dp
private val GridAxisWidth = 44.dp

/**
 * 课表页（成员 B，第 3–4 步）。
 *
 * 顶部“今日 / 本周 / 上周 / 下周”页签切换：
 * - 今日：保留逐条列表样式，可滑动；
 * - 本周 / 上周 / 下周：整周一屏网格（节次时间轴 + 七天列 + 课程卡），周网格头部带 ‹ › 可继续翻周。
 *
 * 五种状态：正常视图 / 未设学期 / 不在教学周 / 空课表 / 读取失败。
 * 只负责展示与表单交互，校验与版本冲突由 ViewModel 和 E 的 Repository 负责。
 */
@Composable
fun CourseScreen(
    state: CourseUiState,
    onSelectTab: (CourseTab) -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onSaveSemester: (LocalDate, Int, List<PeriodRecord>, (String?) -> Unit) -> Unit,
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
        WeekHeader(state, onSelectTab)
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
                when (state.viewMode) {
                    CourseViewMode.TODAY -> TodayBody(state, Modifier.weight(1f)) { entry ->
                        detailCourseId = entry.courseId
                    }
                    CourseViewMode.WEEK -> WeekGridBody(
                        state = state,
                        modifier = Modifier.weight(1f),
                        onPreviousWeek = onPreviousWeek,
                        onNextWeek = onNextWeek,
                    ) { entry ->
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
            initialPeriods = state.periods,
            onDismiss = { semesterDialog = false },
            onConfirm = { date, weeks, periods, onResult ->
                onSaveSemester(date, weeks, periods) { error ->
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
            periodLimit = state.periodCount,
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
private fun WeekHeader(state: CourseUiState, onSelectTab: (CourseTab) -> Unit) {
    Column(Modifier.padding(top = 16.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("我的课表", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(headerSubtitle(state), fontSize = 13.sp, color = KeeperMuted)
            }
            Text(
                if (state.viewMode == CourseViewMode.TODAY) "今日 ${state.todayEntries.size} 条"
                else "${state.entryCount} 条排课",
                fontSize = 12.sp,
                color = KeeperMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
        CourseTabs(state, onSelectTab)
    }
}

private fun headerSubtitle(state: CourseUiState): String = when {
    state.weekCount == 0 -> "尚未设置学期"
    state.viewMode == CourseViewMode.TODAY && state.weekDates.size == 7 -> {
        val date = state.weekDates[state.todayDay - 1]
        "今天 ${date.monthValue} 月 ${date.dayOfMonth} 日 · 第 ${state.selectedWeek} 周"
    }
    else -> "第 ${state.selectedWeek} 周 · ${state.weekLabel}"
}

@Composable
private fun CourseTabs(state: CourseUiState, onSelectTab: (CourseTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFEAEFF8), RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        CourseTab.entries.forEach { tab ->
            val selected = tabSelected(tab, state)
            val enabled = tabEnabled(tab, state)
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable(enabled = enabled) { onSelectTab(tab) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        !enabled -> KeeperMuted.copy(alpha = 0.45f)
                        selected -> KeeperBlue
                        else -> KeeperMuted
                    },
                )
            }
        }
    }
}

/** 页签高亮：周次一律相对“今天所在的教学周”（不在教学周内时退回当前展示周）。 */
private fun tabSelected(tab: CourseTab, state: CourseUiState): Boolean {
    if (tab == CourseTab.TODAY) return state.viewMode == CourseViewMode.TODAY
    if (state.viewMode != CourseViewMode.WEEK) return false
    val base = state.currentWeek ?: state.selectedWeek
    return when (tab) {
        CourseTab.PREV_WEEK -> state.selectedWeek == base - 1
        CourseTab.NEXT_WEEK -> state.selectedWeek == base + 1
        else -> state.selectedWeek == base || (state.selectedWeek != base - 1 && state.selectedWeek != base + 1)
    }
}

private fun tabEnabled(tab: CourseTab, state: CourseUiState): Boolean = when (tab) {
    CourseTab.TODAY, CourseTab.THIS_WEEK -> true
    CourseTab.PREV_WEEK -> (state.currentWeek ?: state.selectedWeek) > 1
    CourseTab.NEXT_WEEK -> (state.currentWeek ?: state.selectedWeek) < state.weekCount
}

/** 今日视图：保留逐条卡片列表（原样式），上下滑动观看。 */
@Composable
private fun TodayBody(
    state: CourseUiState,
    modifier: Modifier = Modifier,
    onEntryClick: (CourseEntry) -> Unit,
) {
    if (state.currentWeek == null) {
        Box(modifier.fillMaxWidth()) {
            EmptyState(
                title = "今天不在教学周内",
                detail = "今天没有排课可看。可切换到周视图翻看课表，或手动添加课程。",
            )
        }
        return
    }
    val entries = state.todayEntries
    if (entries.isEmpty()) {
        Box(modifier.fillMaxWidth()) {
            EmptyState(
                title = "今天没有课程",
                detail = "可以切换到“本周”查看整周安排；周次不同的课程会在对应周自动显示。",
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(entries, key = { it.scheduleId }) { entry ->
            CourseEntryCard(
                entry = entry,
                timeText = timeText(entry, state.periods),
                conflicted = state.conflicts.any {
                    it.first.scheduleId == entry.scheduleId || it.second.scheduleId == entry.scheduleId
                },
            ) {
                onEntryClick(entry)
            }
        }
    }
}

/**
 * 周视图：整周一屏网格（图 1 样式）。左侧节次/时间轴，七列课程卡；
 * 纵向可滑（13 节超屏），头部 ‹ › 可继续翻周。
 */
@Composable
private fun WeekGridBody(
    state: CourseUiState,
    modifier: Modifier = Modifier,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onEntryClick: (CourseEntry) -> Unit,
) {
    val gridPeriods = maxOf(
        state.periodCount,
        state.days.values.maxOfOrNull { day -> day.maxOfOrNull { it.endPeriod } ?: 0 } ?: 0,
    ).coerceAtLeast(1)
    val totalHeight = GridRowHeight * gridPeriods
    val conflictIds = state.conflicts.flatMap {
        listOf(it.first.scheduleId, it.second.scheduleId)
    }.toSet()

    Column(modifier.fillMaxWidth()) {
        // 周次导航：‹ 第 N 周 · 日期范围 ›
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPreviousWeek, enabled = state.canGoPrevious) {
                Text("‹", fontSize = 22.sp, color = if (state.canGoPrevious) KeeperInk else KeeperMuted)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("第 ${state.selectedWeek} 周", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (state.weekLabel.isNotBlank()) {
                    Text(state.weekLabel, fontSize = 11.sp, color = KeeperMuted)
                }
            }
            IconButton(onClick = onNextWeek, enabled = state.canGoNext) {
                Text("›", fontSize = 22.sp, color = if (state.canGoNext) KeeperInk else KeeperMuted)
            }
        }
        Spacer(Modifier.height(4.dp))

        // 表头：周一 ~ 周日 + 具体日期，今天的列高亮
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(GridAxisWidth))
            (0..6).forEach { index ->
                val day = index + 1
                val date = state.weekDates.getOrNull(index)
                val isToday = state.isCurrentWeek && day == state.todayDay
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 1.dp)
                        .background(
                            if (isToday) KeeperBlue.copy(alpha = 0.12f) else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        DayNames[index].removePrefix("周"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isToday) KeeperBlue else KeeperInk,
                    )
                    if (date != null) {
                        Text(
                            "${date.monthValue}/${date.dayOfMonth}",
                            fontSize = 9.sp,
                            color = if (isToday) KeeperBlue else KeeperMuted,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(
                    Modifier
                        .height(totalHeight)
                        .fillMaxWidth()
                        .drawBehind {
                            // 横向节次分隔线 + 纵向日期分隔线，构成图 1 的网格底纹
                            val rowPx = GridRowHeight.toPx()
                            val axisPx = GridAxisWidth.toPx()
                            val columnPx = (size.width - axisPx) / 7f
                            val stroke = 1.dp.toPx()
                            for (row in 1 until gridPeriods) {
                                val y = row * rowPx
                                drawLine(GridLineColor, Offset(0f, y), Offset(size.width, y), stroke)
                            }
                            for (column in 1..6) {
                                val x = axisPx + column * columnPx
                                drawLine(GridLineColor, Offset(x, 0f), Offset(x, size.height), stroke)
                            }
                        },
                ) {
                    TimeAxis(
                        periods = state.periods,
                        count = gridPeriods,
                        modifier = Modifier.width(GridAxisWidth),
                    )
                    (1..7).forEach { day ->
                        DayColumn(
                            entries = state.days[day].orEmpty(),
                            conflictIds = conflictIds,
                            rowHeight = GridRowHeight,
                            modifier = Modifier.weight(1f),
                        ) { entry ->
                            onEntryClick(entry)
                        }
                    }
                }
            }
            if (state.isEmptyWeek) {
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = "本周暂无课程",
                        detail = "周次不同的课程会在对应周自动显示；也可以手动添加课程。",
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeAxis(periods: List<PeriodRecord>, count: Int, modifier: Modifier = Modifier) {
    val byNumber = periods.associateBy { it.number }
    Column(modifier) {
        repeat(count) { index ->
            Column(
                Modifier.height(GridRowHeight).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("${index + 1}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = KeeperMuted)
                byNumber[index + 1]?.let { period ->
                    Text(period.startsAt.format(TimePattern), fontSize = 8.sp, color = KeeperMuted)
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    entries: List<CourseEntry>,
    conflictIds: Set<String>,
    rowHeight: Dp,
    modifier: Modifier = Modifier,
    onEntryClick: (CourseEntry) -> Unit,
) {
    BoxWithConstraints(modifier) {
        placeEntries(entries).forEach { placed ->
            val entry = placed.entry
            val cardWidth = maxWidth / placed.columns
            Box(
                Modifier
                    .offset(x = cardWidth * placed.column, y = rowHeight * (entry.startPeriod - 1) + 2.dp)
                    .width(cardWidth - 3.dp)
                    .height(rowHeight * entry.periodCount - 5.dp)
                    .background(gridColor(entry.courseId), RoundedCornerShape(7.dp))
                    .clickable { onEntryClick(entry) }
                    .padding(horizontal = 4.dp, vertical = 3.dp),
            ) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        entry.title.ifBlank { "未命名课程" },
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(
                        entry.location?.takeIf { it.isNotBlank() },
                        entry.teacher?.takeIf { it.isNotBlank() },
                    ).joinToString(" ")
                    if (meta.isNotBlank()) {
                        Text(
                            meta,
                            fontSize = 8.sp,
                            lineHeight = 10.sp,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (entry.scheduleId in conflictIds) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(8.dp)
                            .background(ConflictColor, RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
    }
}

private data class PlacedEntry(val entry: CourseEntry, val column: Int, val columns: Int)

/**
 * 同一时段重叠的排课并排显示（图 1 的“非本周”与冲突条目都这样处理）：
 * 按起始节排序，重叠组内的第几条就放在第几列，组内共几条就均分列宽。
 */
private fun placeEntries(entries: List<CourseEntry>): List<PlacedEntry> {
    if (entries.isEmpty()) return emptyList()
    val sorted = entries.sortedWith(compareBy({ it.startPeriod }, { it.endPeriod }, { it.scheduleId }))
    return sorted.map { entry ->
        val group = sorted.filter {
            it.startPeriod <= entry.endPeriod && entry.startPeriod <= it.endPeriod
        }
        val column = group.indexOfFirst { it.scheduleId == entry.scheduleId }.coerceAtLeast(0)
        PlacedEntry(entry, column, group.size)
    }
}

/** 已配置节次时刻时，卡片上显示具体时间；否则只显示节次号。 */
private fun timeText(entry: CourseEntry, periods: List<PeriodRecord>): String? {
    if (periods.isEmpty()) return null
    val byNumber = periods.associateBy { it.number }
    val start = byNumber[entry.startPeriod]?.startsAt ?: return null
    val end = byNumber[entry.endPeriod]?.endsAt ?: return null
    return "${start.format(TimePattern)} — ${end.format(TimePattern)}"
}

@Composable
private fun CourseEntryCard(entry: CourseEntry, timeText: String?, conflicted: Boolean, onClick: () -> Unit) {
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
                val meta = listOfNotNull(
                    timeText,
                    entry.location?.takeIf { it.isNotBlank() },
                    entry.teacher?.takeIf { it.isNotBlank() },
                )
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
    initialPeriods: List<PeriodRecord>,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, Int, List<PeriodRecord>, (String?) -> Unit) -> Unit,
) {
    var dateText by rememberSaveable { mutableStateOf(initialStartsOn.toString()) }
    var weekText by rememberSaveable { mutableStateOf(initialWeekCount.toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = remember(initialPeriods) {
        mutableStateListOf<PeriodRow>().apply {
            initialPeriods.sortedBy { it.number }.forEach {
                add(PeriodRow(it.startsAt.format(TimePattern), it.endsAt.format(TimePattern)))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("学期设置") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
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
                HorizontalDivider()
                Text("节次时间（可选）", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    "留空则只显示“第 N 节”；填写后课表和“下一节课”会显示具体时刻。",
                    fontSize = 11.sp,
                    color = KeeperMuted,
                )
                rows.forEachIndexed { index, row ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("第 ${index + 1} 节", fontSize = 12.sp, modifier = Modifier.width(56.dp))
                        OutlinedTextField(
                            value = row.start,
                            onValueChange = { rows[index] = row.copy(start = it) },
                            label = { Text("开始", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = row.end,
                            onValueChange = { rows[index] = row.copy(end = it) },
                            label = { Text("结束", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { rows.removeAt(index) }) { Text("删", fontSize = 11.sp) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { rows.add(PeriodRow("", "")) }) { Text("＋ 添加一节", fontSize = 12.sp) }
                    TextButton(onClick = { while (rows.size < DEFAULT_PERIOD_COUNT) rows.add(PeriodRow("", "")) }) {
                        Text("补齐到 $DEFAULT_PERIOD_COUNT 节", fontSize = 12.sp)
                    }
                }
                error?.let { Text(it, color = ConflictColor, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val date = runCatching { LocalDate.parse(dateText.trim(), DatePattern) }.getOrNull()
                val weeks = weekText.trim().toIntOrNull()
                val periods = mutableListOf<PeriodRecord>()
                var problem: String? = null
                rows.forEachIndexed { index, row ->
                    if (problem != null) return@forEachIndexed
                    val startText = row.start.trim()
                    val endText = row.end.trim()
                    if (startText.isEmpty() && endText.isEmpty()) return@forEachIndexed
                    val start = runCatching { LocalTime.parse(startText, TimePattern) }.getOrNull()
                    val end = runCatching { LocalTime.parse(endText, TimePattern) }.getOrNull()
                    when {
                        start == null || end == null -> problem = "第 ${index + 1} 节的时间格式应为 HH:mm"
                        !start.isBefore(end) -> problem = "第 ${index + 1} 节的结束时间必须晚于开始时间"
                        else -> periods += PeriodRecord(
                            semesterId = "",
                            number = index + 1,
                            startsAt = start,
                            endsAt = end,
                        )
                    }
                }
                when {
                    date == null -> error = "请填写有效日期，例如 2026-09-07"
                    weeks == null || weeks !in 1..64 -> error = "学期周数需在 1—64 之间"
                    problem != null -> error = problem
                    else -> onConfirm(date, weeks, periods) { message ->
                        error = message
                        if (message == null) onDismiss()
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private data class PeriodRow(val start: String, val end: String)

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
    periodLimit: Int,
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
                        end > periodLimit ->
                            problem = "第 ${index + 1} 条排课超出本学期节次数（共 $periodLimit 节）"
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
