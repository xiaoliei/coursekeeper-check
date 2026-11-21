package com.coursekeeper.statistics

import android.util.Log
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
 * - 8 条规则提示由 [RuleEngine] 按同一快照同一时刻求值（T07）；
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
            // 课程侧数字只取一次：统计与规则提示共用同一份，既不重复投影，也避免两次取值不一致。
            val courseStats = courseStatsSource.summary(snapshot, current)
            val statistics = StatisticsService.compute(snapshot, courseStats, current)
            _uiState.value = statistics.copy(
                notices = evaluateNotices(snapshot, courseStats, current, statistics.completionRate),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            _uiState.value = _uiState.value.copy(error = "看板加载失败，请重试")
        }
    }

    /**
     * 8 条规则提示。单独兜底：规则求值失败只丢提示，不能连累已算好的统计数字，
     * 否则一次规则异常会把整块看板打成“加载失败”并退回上一次的旧值。
     */
    private fun evaluateNotices(
        snapshot: DataSnapshot,
        courseStats: CourseStats,
        current: LocalDateTime,
        completionRate: Float?,
    ): List<RuleNotice> = try {
        // 规则 7 需要区分“学期未设置”与“真的无课程”：快照里有已配置学期（startsOn 非空）才算已设置。
        RuleEngine.evaluate(
            snapshot,
            courseStats,
            current,
            completionRate,
            snapshot.semesters.any { it.startsOn != null },
        )
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        // 兜底为空列表但必须留痕：否则线上提醒区持续消失时无从排查。
        Log.w("StatisticsViewModel", "rule evaluate failed", exception)
        emptyList()
    }
}
