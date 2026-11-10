package com.coursekeeper.ui

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.coursekeeper.demo.DemoHomeGateway
import com.coursekeeper.home.*
import com.coursekeeper.ui.components.*
import com.coursekeeper.ui.theme.*

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
    LazyColumn(modifier.fillMaxSize().testTag("home_list"), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.status != SyncStatus.Ready) item { StatusBanner(state.status, onLogin, onAdd) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text(state.greeting, fontSize=25.sp, fontWeight=FontWeight.Bold)
                    Text(state.dateLabel, color=KeeperMuted, fontSize=12.sp)
                }
                Text(state.weekLabel, fontSize=11.sp, color=KeeperBlue,
                    modifier=Modifier.background(Color(0xFFEAF0FF),RoundedCornerShape(8.dp)).padding(8.dp))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().background(KeeperBlue, RoundedCornerShape(17.dp)).padding(vertical=18.dp)) {
                Metric(when(state.courseAvailability) {
                    CourseAvailability.Ready -> state.todayCourseCount.toString()
                    CourseAvailability.NoSemester -> "待设置学期"
                    CourseAvailability.NotInTerm -> "非教学周"
                    CourseAvailability.Unavailable -> "暂无数据"
                },"今日课程",Modifier.weight(1f),"home_course_count",onCourses)
                Metric(state.todayDueCount.toString(),"今日截止",Modifier.weight(1f),"home_due_count") { onTasks(TaskFilter.Today) }
                Metric(state.todayCompletedCount.toString(),"今日已完成",Modifier.weight(1f),"home_completed_count") { onTasks(TaskFilter.CompletedToday) }
            }
        }
        item {
            SectionTitle("下一节课", "查看课表 →", onCourses)
            val course = state.nextCourse
            if(course == null) KeeperCard {
                when(state.courseAvailability) {
                    CourseAvailability.NoSemester -> EmptyState("待设置学期", "请先完善学期设置，再查看课程安排。", "查看课表", onCourses)
                    CourseAvailability.NotInTerm -> EmptyState("当前不在教学周内", "可以进入课表查看其他教学周。", "查看课表", onCourses)
                    CourseAvailability.Unavailable -> EmptyState("暂无课程信息", "课程信息暂不可用，可以先管理任务。", "查看课表", onCourses)
                    CourseAvailability.Ready -> EmptyState("还没有课程安排", "可以手动添加任务，或连接学校。", "连接学校", onLogin)
                }
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
                Text(if(state.weekTaskCount==0) "暂无本周任务" else "${state.weekCompletedCount} / ${state.weekTaskCount} 项本周任务已完成", fontWeight=FontWeight.SemiBold,fontSize=14.sp)
                if(state.weekTaskCount>0) LinearProgressIndicator(progress={state.weekProgress},modifier=Modifier.fillMaxWidth().height(7.dp))
                Text(if(state.courseAvailability==CourseAvailability.Ready) "本周课程 ${state.weekCourseCount} 门" else "本周课程 暂无数据",fontSize=12.sp,color=KeeperMuted)
                Text(state.pressureLabel,fontSize=12.sp,color=KeeperMuted)
            }
        }
        item { Text("${state.lastSyncLabel} · ${if(state.isDemo) "演示数据仅在内存中" else "任务已保存在本机"}",fontSize=11.sp,color=KeeperMuted) }
    }
}
@Composable private fun Metric(value: String, label: String, modifier: Modifier, tag: String, onClick: () -> Unit) {
    Column(modifier.clickable(onClick=onClick).padding(5.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(value,fontSize=if(value.length>3) 13.sp else 28.sp,lineHeight=if(value.length>3) 20.sp else 32.sp,
            textAlign=TextAlign.Center,fontWeight=FontWeight.SemiBold,color=Color.White,
            modifier=Modifier.heightIn(min=40.dp).wrapContentHeight(Alignment.CenterVertically).testTag(tag))
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
