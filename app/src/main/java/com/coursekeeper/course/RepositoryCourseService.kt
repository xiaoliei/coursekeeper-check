package com.coursekeeper.course

import com.coursekeeper.data.CourseKeeperRepository
import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.statistics.CourseStats
import com.coursekeeper.statistics.CourseStatsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 课程服务（成员 B）：把 E 的 Repository 快照接到 [CourseProjector] 上。
 *
 * - 读侧全部是快照投影，不持有可变状态；
 * - 实现 F 的 [CourseStatsSource]，F 只需把 `StatisticsViewModel` 里的
 *   `IntervalCourseStatsAdapter` 换成本服务（或注入本服务的 summary），即可删除临时适配器；
 * - 写侧（新增/编辑/删除/学期配置）在第 4 步接 `CourseKeeperRepository`。
 */
class RepositoryCourseService(
    private val repository: CourseKeeperRepository,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
) : CourseService, CourseStatsSource {

    override val state: Flow<CourseState> =
        repository.snapshots.map { snapshot -> CourseProjector.project(snapshot, clock()) }

    override suspend fun stateNow(): CourseState =
        CourseProjector.project(repository.snapshot(), clock())

    override fun project(snapshot: DataSnapshot, now: LocalDateTime): CourseState =
        CourseProjector.project(snapshot, now)

    override fun timetable(snapshot: DataSnapshot, weekNo: Int): WeekTimetable =
        CourseProjector.timetable(snapshot, weekNo, clock().toLocalDate())

    override fun conflicts(snapshot: DataSnapshot, weekNo: Int): List<CourseConflict> =
        CourseProjector.conflicts(snapshot, weekNo, clock().toLocalDate())

    override fun weekOf(snapshot: DataSnapshot, date: LocalDate): Int? =
        CourseProjector.weekOf(snapshot, date)

    override fun summary(snapshot: DataSnapshot, now: LocalDateTime): CourseStats =
        CourseProjector.summary(snapshot, now)
}
