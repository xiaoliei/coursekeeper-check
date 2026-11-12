package com.coursekeeper.course

import com.coursekeeper.data.CourseDraft
import com.coursekeeper.data.CourseKeeperRepository
import com.coursekeeper.data.WriteReceipt
import com.coursekeeper.data.model.DataSnapshot
import com.coursekeeper.data.model.PeriodRecord
import com.coursekeeper.data.model.SemesterRecord
import com.coursekeeper.statistics.CourseStats
import com.coursekeeper.statistics.CourseStatsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 课程服务（成员 B）：把 E 的 Repository 接到 [CourseProjector] 上。
 *
 * - 读侧全部是快照投影，不持有可变状态；
 * - 实现 F 的 [CourseStatsSource]，F 把 `StatisticsViewModel` 里的
 *   `IntervalCourseStatsAdapter` 换成本服务即可删除临时适配器；
 * - 写侧委托 `CourseKeeperRepository`，版本、事务、排课整组替换、墓碑都由 E 负责。
 */
class RepositoryCourseService(
    private val repository: CourseKeeperRepository,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
) : CourseService, CourseStatsSource, CourseWriter {

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

    override suspend fun createCourse(draft: CourseDraft, requestId: String?): WriteReceipt =
        repository.createCourse(draft, requestId = requestId)

    override suspend fun updateCourse(
        id: String,
        expectedVersion: Long,
        draft: CourseDraft,
        requestId: String?,
    ): WriteReceipt = repository.updateCourse(id, expectedVersion, draft, requestId = requestId)

    override suspend fun deleteCourse(id: String, expectedVersion: Long, requestId: String?): WriteReceipt =
        repository.deleteCourse(id, expectedVersion, requestId = requestId)

    override suspend fun configureSemester(
        semesterId: String,
        name: String,
        startsOn: LocalDate,
        weekCount: Int,
        periods: List<PeriodRecord>,
        expectedVersion: Long?,
    ): WriteReceipt {
        val snapshot = repository.snapshot()
        val existing = snapshot.semesters.firstOrNull { it.id == semesterId }
        val record = SemesterRecord(
            id = semesterId,
            name = name,
            startsOn = startsOn,
            weekCount = weekCount,
            version = existing?.version ?: 1,
        )
        // 节次表由界面传入；空表表示只用节次号，不编造时刻。
        return repository.saveSemester(record, periods, expectedVersion)
    }
}
