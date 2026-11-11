package com.coursekeeper.statistics

import com.coursekeeper.data.CourseKeeperRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * 看板数据入口（成员 F，W2 骨架）。
 *
 * 订阅 E 的一致性快照，用 StatisticsService 实时计算统计结果：
 * - 课程侧四个数字经 [CourseStatsSource] 消费（当前为临时 [IntervalCourseStatsAdapter]，待 B 的 CourseService 替换）；
 * - 数量变化时按当前时刻重算，页面随时间自动更新；
 * - 单次计算失败保留上一次成功值，不因读取异常清空看板。
 */
class StatisticsViewModel(
    repository: CourseKeeperRepository,
    private val courseStatsSource: CourseStatsSource = IntervalCourseStatsAdapter(),
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(StatisticsUi())
    val uiState: StateFlow<StatisticsUi> = _uiState

    init {
        scope.launch {
            repository.snapshots.collect { snapshot ->
                try {
                    val current = now()
                    _uiState.value = StatisticsService.compute(
                        snapshot,
                        courseStatsSource.summary(snapshot, current),
                        current,
                    )
                } catch (_: Exception) {
                    // 读失败保留上一次成功值，不以空结果覆盖（草案 §1“失败不能以空结果覆盖”）。
                }
            }
        }
    }
}
