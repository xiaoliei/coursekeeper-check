package com.coursekeeper.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coursekeeper.home.*
import com.coursekeeper.ui.theme.*

@Composable fun KeeperCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp))
        .border(1.dp, Color(0xFFEBEFF6), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}
@Composable fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if(action != null) TextButton(onClick = onAction) { Text(action, fontSize = 12.sp) }
    }
}
@Composable fun EmptyState(title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(detail, color = KeeperMuted, fontSize = 12.sp)
        if(action != null) OutlinedButton(onClick = onAction) { Text(action) }
    }
}
@Composable fun StatusBanner(status: SyncStatus, onLogin: () -> Unit, onAdd: () -> Unit) {
    if(status == SyncStatus.Ready) return
    val warning = status != SyncStatus.Offline
    Row(Modifier.fillMaxWidth().background(if(warning) Color(0xFFFFF0DD) else Color(0xFFEAF0FA), RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(when(status) {
            SyncStatus.Offline -> "当前离线 · 已有数据可继续使用"
            SyncStatus.Expired -> "登录已过期 · 现有数据已保留"
            SyncStatus.Partial -> "课表已同步，任务暂未更新"
            SyncStatus.Ready -> ""
        }, fontSize = 12.sp, color = if(warning) Color(0xFF8A5515) else KeeperMuted, modifier = Modifier.weight(1f))
        when(status) {
            SyncStatus.Expired -> TextButton(onClick=onLogin) { Text("重新登录", fontSize=12.sp) }
            SyncStatus.Partial -> TextButton(onClick=onAdd) { Text("手动添加", fontSize=12.sp) }
            else -> Unit
        }
    }
}
@Composable fun TaskRow(task: TaskUi, onToggle: (TaskUi) -> Unit, onDetail: (TaskUi) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
        Checkbox(checked=task.completed, onCheckedChange={onToggle(task)}, modifier=Modifier.semantics {
            contentDescription = (if(task.completed) "恢复待办：" else "完成：") + task.title
        })
        Column(Modifier.weight(1f).clickable { onDetail(task) }.padding(vertical=12.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(task.title, fontSize=14.sp, fontWeight=FontWeight.SemiBold, color=if(task.completed) KeeperMuted else KeeperInk)
            Text("${task.type} · ${task.course.ifBlank { "未关联课程" }}", fontSize=11.sp, color=KeeperMuted)
            Text(if(task.completed) "已完成" else "${task.dueLabel} 截止", fontSize=11.sp, color=if(task.completed) Color(0xFF21856C) else Color(0xFF97591D))
        }
    }
}
fun tabIcon(name: String): ImageVector = ImageVector.Builder(name=name, defaultWidth=24.dp, defaultHeight=24.dp, viewportWidth=24f, viewportHeight=24f).apply {
    path(fill=null, stroke=androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth=1.7f) {
        when(name) {
            "首页" -> { moveTo(3f,10f);lineTo(12f,3f);lineTo(21f,10f);moveTo(5f,9f);lineTo(5f,21f);lineTo(19f,21f);lineTo(19f,9f);moveTo(9f,21f);lineTo(9f,13f);lineTo(15f,13f);lineTo(15f,21f) }
            "课表" -> { moveTo(4f,5f);lineTo(20f,5f);lineTo(20f,21f);lineTo(4f,21f);close();moveTo(8f,2f);lineTo(8f,8f);moveTo(16f,2f);lineTo(16f,8f);moveTo(4f,11f);lineTo(20f,11f) }
            "任务" -> { for(y in listOf(6f,12f,18f)) {moveTo(3f,y);lineTo(6f,y);moveTo(10f,y);lineTo(21f,y)} }
            else -> { moveTo(4f,20f);lineTo(4f,10f);moveTo(9f,20f);lineTo(9f,4f);moveTo(15f,20f);lineTo(15f,13f);moveTo(21f,20f);lineTo(21f,7f) }
        }
    }
}.build()
