package com.coursekeeper.statistics

import com.coursekeeper.data.CourseKeeperRepository
import com.coursekeeper.data.model.DataSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * 看板数据入口（成员 F，W2 骨架）。
 *
 * 订阅 E 的一致性快照，用 StatisticsService 实时计算统计结果：
 * - 课程侧四个数字经 [CourseStatsSource] 消费，正式来源是 B 的 RepositoryCourseService；
 * - 数量变化或每分钟（F-01）按当前时刻重算，页面随时间自动更新（跨截止/跨日/跨周）；
 * - 单次计算失败保留上一次成功值，不因读取异常清空看板。
 */
class StatisticsViewModel(
    repository: CourseKeeperRepository,
    private val courseStatsSource: CourseStatsSource,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    // 分钟 tick 与 A 首页（RepositoryHomeGateway）、D 任务页（TaskViewModel）同一节奏，
    // 保证同一快照同一时刻首页与看板一致。
    ticks: Flow<Unit> = flow { while (true) { emit(Unit); delay(60_000) } },
    scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(StatisticsUi())
    val uiState: StateFlow<StatisticsUi> = _uiState
    private var latestSnapshot: DataSnapshot? = null

    init {
        scope.launch {
            combine(repository.snapshots, ticks) { snapshot, _ -> snapshot }.collect { snapshot ->
                latestSnapshot = snapshot
                compute(snapshot)
            }
        }
    }

    fun retry() {
        latestSnapshot?.let(::compute)
    }

    private fun compute(snapshot: DataSnapshot) {
        try {
            val current = now()
            _uiState.value = StatisticsService.compute(
                snapshot,
                courseStatsSource.summary(snapshot, current),
                current,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            _uiState.value = _uiState.value.copy(error = "看板加载失败，请重试")
        }
    }
}
