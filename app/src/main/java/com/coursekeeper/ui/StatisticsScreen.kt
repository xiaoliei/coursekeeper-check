package com.coursekeeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coursekeeper.statistics.StatisticsUi
import com.coursekeeper.ui.components.KeeperCard
import com.coursekeeper.ui.theme.KeeperMuted

/**
 * 数据看板（成员 F，W2 文字骨架）。
 *
 * 消费 StatisticsService 的计算结果，渲染文字版：周完成率、类型分布、截止分布、压力指数。
 * Compose Canvas 图表与 8 条规则提示归 W3。
 */
@Composable
fun StatisticsScreen(state: StatisticsUi?, modifier: Modifier = Modifier) {
    val ui = state ?: return
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("数据看板", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        KeeperCard {
            Text(if (ui.weekTaskCount == 0) "暂无本周任务" else "本周完成 ${ui.weekCompletedCount} / ${ui.weekTaskCount} 项",
                fontWeight = FontWeight.Bold)
            ui.completionRate?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(7.dp))
            }
            Text("按本周截止的任务统计", fontSize = 12.sp, color = KeeperMuted)
        }
        KeeperCard {
            Text("类型分布", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (ui.typeDistribution.isEmpty()) Text("暂无", fontSize = 12.sp, color = KeeperMuted)
            ui.typeDistribution.forEach { slice ->
                Text("${slice.label} × ${slice.count}", fontSize = 13.sp)
            }
        }
        KeeperCard {
            Text("截止分布", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("已逾期 ${ui.dueDist.overdue} · 本周 ${ui.dueDist.thisWeek} · 未来一周 ${ui.dueDist.next7Days}",
                fontSize = 13.sp)
        }
        KeeperCard {
            Text("压力指数", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (ui.pressureScore == null) {
                Text("暂无压力数据", fontSize = 13.sp, color = KeeperMuted)
            } else {
                Text("${ui.pressureScore} · ${ui.pressureLevel}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(ui.explanation, fontSize = 12.sp, color = KeeperMuted)
            }
        }
    }
}
