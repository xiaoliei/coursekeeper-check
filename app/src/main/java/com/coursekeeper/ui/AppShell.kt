package com.coursekeeper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.coursekeeper.demo.DemoScenario
import com.coursekeeper.home.*
import com.coursekeeper.ui.components.*
import com.coursekeeper.ui.theme.*

enum class Destination(val label: String) { Home("首页"), Courses("课表"), Tasks("任务"), Statistics("数据") }

/** Navigation shell owned by A; replace demonstration destination content during team integration. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    state: HomeUiState,
    onTaskToggle: (TaskUi) -> Unit,
    onAddTask: (NewTask) -> Unit,
    onScenario: (DemoScenario) -> Unit,
    onSkipLogin: () -> Unit,
    onSimulateLogin: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    // D 的任务模块接入点：为空时使用 A 的导航占位页，便于 A 的演示工程独立运行。
    taskScreen: (@Composable (Modifier) -> Unit)? = null,
    coursesScreen: (@Composable (Modifier) -> Unit)? = null,
    // F 的看板接入点：为空时使用 A 的导航占位页（DemoStatistics）。
    statisticsScreen: (@Composable (Modifier) -> Unit)? = null,
    onTaskFilter: (TaskFilter) -> Unit = {},
    onCreateTask: (() -> Unit)? = null,
    onRetryHome: () -> Unit = {},
) {
    var selected by rememberSaveable { mutableStateOf(Destination.Home) }
    var filter by rememberSaveable { mutableStateOf(TaskFilter.Pending) }
    var menu by remember { mutableStateOf(false) }
    var add by rememberSaveable { mutableStateOf(false) }
    var login by rememberSaveable { mutableStateOf(false) }
    var taskDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    var courseDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled=selected != Destination.Home && !add && !login && taskDetailId==null && courseDetailId==null) { selected=Destination.Home }
    fun goTasks(value: TaskFilter) { filter=value;onTaskFilter(value);selected=Destination.Tasks }
    fun requestAddTask() {
        if (taskScreen == null) add=true
        else {
            // 已在任务页时不重置筛选：底栏入口与任务页原按钮等价，直接打开表单
            if (selected != Destination.Tasks) goTasks(TaskFilter.Pending)
            onCreateTask?.invoke()
        }
    }
    // 切页时收起上一条提示：带"撤销"的 Snackbar 在 Material3 里默认常驻（Indefinite），
    // 不主动关就会一直浮在底部挡住新页面，用户于是只能靠"点撤销"让它消失。
    LaunchedEffect(selected) { snackbarHostState.currentSnackbarData?.dismiss() }
    // 内容区与底栏的可见条件：底栏渲染与否、提示是否需要让位都取决于它，只定义一次避免漂移。
    val contentReady = !state.onboarding && !state.loading && state.readError == null

    Scaffold(
        containerColor=KeeperBackground,
        topBar={ TopAppBar(title={ Column {
            Text("课程任务管家",fontSize=18.sp,fontWeight=FontWeight.Bold)
            Text(if(state.isDemo) "交互演示 · 2026.11.09" else state.dateLabel,fontSize=11.sp,color=KeeperMuted)
        } }, actions={ if(state.isDemo) Box {
            TextButton(onClick={menu=true}) { Text("演示设置",fontSize=12.sp) }
            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                DropdownMenuItem(text={Text("切换场景会重置示例",fontSize=11.sp)},onClick={},enabled=false)
                DemoScenario.entries.forEach { scenario ->
                    DropdownMenuItem(text={Text(scenario.label)},onClick={
                        menu=false;selected=Destination.Home;filter=TaskFilter.Pending
                        add=false;login=false;taskDetailId=null;courseDetailId=null;onScenario(scenario)
                    })
                }
            }
        } }) },
        snackbarHost={ SnackbarHost(snackbarHostState) },
        bottomBar={ if(contentReady) Column {
            // 新建入口统一放底栏、所有页常驻（D 2026-11-14）：任务页不再自带按钮——
            // 此前"底栏按钮/内容层按钮"两层互换，切页时 Scaffold 子组合有一帧不同步，
            // 按钮会先高出约 60dp 再落位（真机逐帧实测）。按钮不动，跳动即不可能发生。
            // Snackbar 由 Scaffold 自动避让底栏，无需任何让位参数。
            Button(onClick=::requestAddTask,modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=8.dp).heightIn(min=48.dp)) {
                Text(if (taskScreen != null && selected == Destination.Tasks) "＋ 新建任务" else "＋ 手动添加任务")
            }
            NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
                Destination.entries.forEach { dest ->
                    val vector=remember(dest) { tabIcon(dest.label) }
                    // 点任意导航项都先收起提示（含点当前 tab 的情形：selected 不变也要能关掉）
                    NavigationBarItem(selected=selected==dest,onClick={
                        snackbarHostState.currentSnackbarData?.dismiss()
                        if(dest==Destination.Tasks && taskScreen!=null) goTasks(TaskFilter.Pending) else selected=dest
                    },
                        icon={Icon(vector,contentDescription=null)},label={Text(dest.label)},
                        colors=NavigationBarItemDefaults.colors(selectedIconColor=KeeperBlue,
                            selectedTextColor=KeeperBlue,indicatorColor=Color(0xFFEAF0FF)))
                }
            }
        } },
    ) { padding ->
        val area=Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        if(state.loading) Box(area,contentAlignment=Alignment.Center) { CircularProgressIndicator() }
        else if(state.readError != null) Box(area.padding(24.dp),contentAlignment=Alignment.Center) {
            EmptyState("暂时无法读取本地数据",state.readError,"重试",onRetryHome)
        }
        else if(state.onboarding) Onboarding(area,{login=true},onSkipLogin,state.isDemo)
        else when(selected) {
            Destination.Home -> HomeScreen(state,{selected=Destination.Courses},::goTasks,
                {selected=Destination.Statistics},onTaskToggle,{taskDetailId=it.id},{courseDetailId=it.id},{login=true},::requestAddTask,area)
            Destination.Courses -> if(coursesScreen != null) coursesScreen(area) else DemoCourses(state,area,{courseDetailId=it.id})
            Destination.Tasks -> if (taskScreen != null) taskScreen(area) else DemoTasks(state,filter,{filter=it},onTaskToggle,{taskDetailId=it.id},area)
            Destination.Statistics -> if(statisticsScreen != null) statisticsScreen(area) else DemoStatistics(state,area)
        }
    }
    if(add) AddTaskDialog(onDismiss={add=false},onSubmit={draft-> add=false;onAddTask(draft)})
    if(login) AlertDialog(onDismissRequest={login=false},title={Text("连接学校")},text={
        Text(if(!state.isDemo) "学校连接暂不可用，你可以先手动管理任务。已有本地数据不会清除。"
            else if(state.status==SyncStatus.Offline) "当前离线，联网后可以连接学校。已有数据仍可使用。"
            else "这是登录流程演示，不收集账号密码。正式版由 C 接入学校官方登录页。")
    },confirmButton={
        if(state.isDemo && state.status!=SyncStatus.Offline) TextButton(onClick={login=false;onSimulateLogin()}) { Text("模拟登录成功") }
        else TextButton(onClick={login=false}) { Text("知道了") }
    },dismissButton={TextButton(onClick={login=false;if(state.onboarding) onSkipLogin()}) { Text("以后再说") }})
    state.tasks.find { it.id==taskDetailId }?.let { task ->
        AlertDialog(onDismissRequest={taskDetailId=null},title={Text(task.title)},text={
            Text("${task.course.ifBlank { "未关联课程" }}\n${task.type}\n${if(task.dueLabel=="截止待定") task.dueLabel else "${task.dueLabel} 截止"}\n${if(task.completed) "已完成" else "待办"}")
        },confirmButton={TextButton(onClick={taskDetailId=null;onTaskToggle(task)}) { Text(if(task.completed) "恢复待办" else "标记完成") }},dismissButton={TextButton(onClick={taskDetailId=null}) {Text("关闭")}})
    }
    state.courses.find { it.id==courseDetailId }?.let { course ->
        AlertDialog(onDismissRequest={courseDetailId=null},title={Text(course.title)},text={Text("${course.time}\n${course.location}\n${course.periods}")},confirmButton={
            TextButton(onClick={courseDetailId=null;selected=Destination.Courses}) {Text("查看课表")}
        },dismissButton={TextButton(onClick={courseDetailId=null}) {Text("关闭")}})
    }
}

@Composable private fun Onboarding(modifier: Modifier,onLogin: () -> Unit,onSkip: () -> Unit,isDemo: Boolean) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(28.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Text("今天该做什么，\n打开就知道。",fontSize=28.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Text("查看今天的课程和近期截止任务。\n基础版支持离线查看和手动维护。",color=KeeperMuted)
        Spacer(Modifier.height(32.dp))
        Button(onClick=onLogin,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("连接学校，导入课表")}
        OutlinedButton(onClick=onSkip,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("先手动使用")}
        Text(if(isDemo) "本工程使用内存假数据，重启会还原。" else "手动任务保存在本机，可以离线使用。",fontSize=12.sp,color=KeeperMuted)
    }
}
@Composable private fun DemoCourses(state: HomeUiState,modifier: Modifier,onDetail: (CourseUi)->Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("我的课表",fontSize=25.sp,fontWeight=FontWeight.Bold)
        if(state.isDemo) Text("导航占位 · B 后续接入完整课表",fontSize=12.sp,color=KeeperMuted)
        Text("${state.weekLabel} · ${state.dateLabel}")
        if(state.courses.isEmpty()) KeeperCard {EmptyState(if(state.courseAvailability==CourseAvailability.NoSemester) "待设置学期" else "暂无课程信息","课程信息暂不可用，当前可先管理任务。")}
        state.courses.forEach { c -> KeeperCard {
            Text(c.time,color=KeeperBlue,fontSize=12.sp)
            Text(c.title,fontWeight=FontWeight.Bold)
            Text(c.location,color=KeeperMuted,fontSize=12.sp)
            TextButton(onClick={onDetail(c)}) {Text("课程详情")}
        } }
    }
}
@Composable private fun DemoTasks(state: HomeUiState,filter: TaskFilter,onFilter: (TaskFilter)->Unit,onToggle: (TaskUi)->Unit,onDetail: (TaskUi)->Unit,modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("任务清单",fontSize=25.sp,fontWeight=FontWeight.Bold)
        Text("导航占位 · D 后续接入完整任务模块",fontSize=12.sp,color=KeeperMuted)
        Text("当前筛选：${filter.label}",color=KeeperBlue,fontSize=13.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(TaskFilter.Pending,TaskFilter.Completed,TaskFilter.All).forEach { f ->
                FilterChip(selected=filter==f,onClick={onFilter(f)},label={Text(f.label)})
            }
        }
        KeeperCard {
            val tasks=state.tasks.filtered(filter)
            if(tasks.isEmpty()) EmptyState("这里暂时没有任务","可以切换筛选或添加任务。")
            tasks.forEachIndexed { i,t -> if(i>0) HorizontalDivider();TaskRow(t,onToggle,onDetail) }
        }
    }
}
@Composable private fun DemoStatistics(state: HomeUiState,modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Text("数据看板",fontSize=25.sp,fontWeight=FontWeight.Bold)
        if(state.isDemo) Text("导航占位 · F 后续接入统计与图表",fontSize=12.sp,color=KeeperMuted)
        KeeperCard {
            Text(if(state.weekTaskCount==0) "暂无本周任务" else "本周完成 ${state.weekCompletedCount} / ${state.weekTaskCount} 项",fontWeight=FontWeight.Bold)
            if(state.weekTaskCount>0) LinearProgressIndicator(progress={state.weekProgress},modifier=Modifier.fillMaxWidth())
            Text("按本周截止的任务统计",fontSize=12.sp,color=KeeperMuted)
        }
        KeeperCard {Text(if(state.courseAvailability==CourseAvailability.Ready) "本周课程 ${state.weekCourseCount} 门" else "本周课程 暂无数据");Text(state.pressureLabel,color=KeeperMuted)}
    }
}
@Composable private fun AddTaskDialog(onDismiss: ()->Unit,onSubmit: (NewTask)->Unit) {
    var title by rememberSaveable {mutableStateOf("")}
    var course by rememberSaveable {mutableStateOf("")}
    var deadline by rememberSaveable {mutableStateOf("2026-11-09 23:59")}
    var type by rememberSaveable {mutableStateOf("课程作业")}
    var error by rememberSaveable {mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("添加任务")},text={
        Column(Modifier.heightIn(max=430.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title,{title=it},label={Text("任务名称")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(course,{course=it},label={Text("关联课程（可留空）")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Text("任务类型",fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("课程作业","实验报告").forEach { label -> FilterChip(selected=type==label,onClick={type=label},label={Text(label,fontSize=12.sp)}) }
            }
            OutlinedTextField(deadline,{deadline=it},label={Text("截止时间")},supportingText={Text("格式：2026-11-09 23:59")},singleLine=true,modifier=Modifier.fillMaxWidth())
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
        }
    },confirmButton={TextButton(onClick={parseNewTask(title,course,type,deadline).fold(onSuccess=onSubmit,onFailure={error=it.message})}) {Text("保存任务")}},dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})
}
