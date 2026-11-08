package com.coursekeeper.a.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coursekeeper.a.demo.DemoHomeGateway
import com.coursekeeper.a.home.*
import com.coursekeeper.a.ui.components.*
import com.coursekeeper.a.ui.theme.*

@Composable
fun HomeScreen(
    state: HomeUiState,
    onCourses: () -> Unit,
    onTasks: (TaskFilter) -> Unit,
    onStatistics: () -> Unit,
    onTaskToggle: (TaskUi) -> Unit,
    onTaskDetail: (TaskUi) -> Unit,
    onCourseDetail: (CourseUi) -> Unit,
    onLogin: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.status != SyncStatus.Ready) item { StatusBanner(state.status, onLogin, onAdd) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text("上午好，同学", fontSize=25.sp, fontWeight=FontWeight.Bold)
                    Text(state.dateLabel, color=KeeperMuted, fontSize=12.sp)
                }
                Text(state.weekLabel, fontSize=11.sp, color=KeeperBlue,
                    modifier=Modifier.background(Color(0xFFEAF0FF),RoundedCornerShape(8.dp)).padding(8.dp))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().background(KeeperBlue, RoundedCornerShape(17.dp)).padding(vertical=18.dp)) {
                Metric(state.todayCourseCount,"今日课程",Modifier.weight(1f),onCourses)
                Metric(state.todayDueCount,"今日截止",Modifier.weight(1f)) { onTasks(TaskFilter.Today) }
                Metric(state.todayCompletedCount,"今日已完成",Modifier.weight(1f)) { onTasks(TaskFilter.CompletedToday) }
            }
        }
        item {
            SectionTitle("下一节课", "查看课表 →", onCourses)
            val course = state.nextCourse
            if(course == null) KeeperCard {
                EmptyState("还没有课程安排", "可以手动添加任务，或连接学校。", "连接学校", onLogin)
            } else KeeperCard(Modifier.clickable { onCourseDetail(course) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(course.time,fontSize=12.sp,color=KeeperBlue)
                    Text(state.nextCourseHint,fontSize=12.sp,color=Color(0xFF21856C))
                }
                Text(course.title,fontSize=17.sp,fontWeight=FontWeight.Bold)
                Text("${course.location} · ${course.periods}",fontSize=12.sp,color=KeeperMuted)
            }
        }
        item {
            SectionTitle("近期截止", "全部任务 →") { onTasks(TaskFilter.Pending) }
            KeeperCard {
                val pending = state.tasks.filterNot { it.completed }.take(3)
                if(pending.isEmpty()) EmptyState(if(state.tasks.isEmpty()) "从第一条任务开始" else "待办都完成了", "下方按钮可手动添加任务。")
                pending.forEachIndexed { i,task ->
                    if(i>0) HorizontalDivider()
                    TaskRow(task,onTaskToggle,onTaskDetail)
                }
            }
        }
        item {
            SectionTitle("本周概览", "查看数据 →", onStatistics)
            KeeperCard {
                Text("${state.weekCompletedCount} / ${state.weekTaskCount} 项本周任务已完成", fontWeight=FontWeight.SemiBold,fontSize=14.sp)
                LinearProgressIndicator(progress={state.weekProgress},modifier=Modifier.fillMaxWidth().height(7.dp))
                Text("本周课程 ${state.weekCourseCount} 门",fontSize=12.sp,color=KeeperMuted)
                Text(state.pressureLabel,fontSize=12.sp,color=KeeperMuted)
            }
        }
        item { Text("${state.lastSyncLabel} · 演示数据仅在内存中",fontSize=11.sp,color=KeeperMuted) }
    }
}
@Composable private fun Metric(value: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick=onClick).padding(5.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(value.toString(),fontSize=28.sp,fontWeight=FontWeight.SemiBold,color=Color.White)
        Text(label,fontSize=11.sp,color=Color.White)
    }
}
@Preview(showBackground=true,widthDp=390,heightDp=800)
@Composable private fun HomePreview() {
    CourseKeeperTheme {
        HomeScreen(DemoHomeGateway().state.value,{},{},{},{},{},{},{},{})
    }
}
@Preview(showBackground=true,widthDp=320,fontScale=1.3f,heightDp=750)
@Composable private fun EmptyHomePreview() {
    CourseKeeperTheme {
        HomeScreen(HomeUiState(dateLabel="11 月 9 日 · 星期一",weekLabel="第 10 周",status=SyncStatus.Offline),{},{},{},{},{},{},{},{})
    }
}
