package com.coursekeeper.home

import com.coursekeeper.data.model.*
import com.coursekeeper.data.settings.AppSettings
import com.coursekeeper.fakedata.BoundaryFixtures
import com.coursekeeper.data.sync.SyncFailure
import com.coursekeeper.task.TaskDateFilter
import com.coursekeeper.task.TaskStatusFilter
import com.coursekeeper.task.toView
import com.coursekeeper.task.visible
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class HomeProjectionTest {
    private val now = LocalDateTime.of(2026, 11, 10, 10, 0)
    private fun task(id: String, due: LocalDateTime? = null, completed: LocalDateTime? = null) =
        TaskRecord(id, id, TaskType.HOMEWORK, dueAt = due, completedAt = completed, version = 7)
    private fun fixtures() = listOf(
        task("待定"),
        task("今日待办", now.toLocalDate().atTime(23, 59)),
        task("今日已完成", now.toLocalDate().atTime(20, 0), now.minusHours(1)),
        task("明日截止今日完成", now.plusDays(1), now.minusHours(2)),
        task("上周", LocalDateTime.of(2026,11,8,20,0), LocalDateTime.of(2026,11,8,19,0)),
        task("下周", LocalDateTime.of(2026,11,16,0,0)),
    )
    @Test fun dailyCountsMatchDateFiltersIncludingCompletedAndUndatedBoundaries() {
        val records = fixtures()
        val home = projectHome(DataSnapshot(tasks = records), AppSettings(onboardingComplete = true), now)
        val rows = records.map { it.toView(emptyList(), now) }
        assertEquals(2, home.todayDueCount)
        assertEquals(2, home.todayCompletedCount)
        assertEquals(home.todayDueCount, rows.visible(TaskStatusFilter.ALL, null, TaskDateFilter.DUE_TODAY, now).size)
        assertEquals(home.todayCompletedCount, rows.visible(TaskStatusFilter.DONE, null, TaskDateFilter.COMPLETED_TODAY, now).size)
        assertEquals("待定", home.tasks.first().id)
        assertEquals("截止待定", home.tasks.first().dueLabel)
        assertTrue(home.tasks.all { it.version == 7L })
    }
    @Test fun weekStartsOnMondayAndExcludesUndatedPreviousAndNextWeek() {
        val home = projectHome(DataSnapshot(tasks = fixtures()), AppSettings(), now)
        assertEquals(3, home.weekTaskCount)
        assertEquals(2, home.weekCompletedCount)
        assertEquals(2f/3f, home.weekProgress, 0.0001f)
    }
    @Test fun dayRolloverChangesCountersAndLabelsWithoutChangingRecords() {
        val records = listOf(task("明天", now.plusDays(1)))
        assertEquals(0, projectHome(DataSnapshot(tasks=records),AppSettings(),now).todayDueCount)
        val next = projectHome(DataSnapshot(tasks=records),AppSettings(),now.plusDays(1))
        assertEquals(1,next.todayDueCount)
        assertTrue(next.tasks.single().dueLabel.startsWith("今天"))
    }
    @Test fun missingCoursesAndPressureNeverBorrowFixtureValues() {
        val empty = projectHome(DataSnapshot(),AppSettings(),now)
        assertFalse(empty.isDemo)
        assertEquals(CourseAvailability.NoSemester,empty.courseAvailability)
        assertEquals("待设置学期",empty.weekLabel)
        assertNull(empty.nextCourse)
        assertTrue(empty.tasks.isEmpty())
        assertEquals("暂无压力数据",empty.pressureLabel)
        val configured = projectHome(DataSnapshot(semesters=listOf(SemesterRecord("s","秋季",LocalDate.of(2026,9,7),20))),AppSettings(),now)
        assertEquals(CourseAvailability.Ready,configured.courseAvailability)
        assertEquals(0,configured.todayCourseCount)
        assertEquals(0,configured.weekCourseCount)
        assertNull(configured.nextCourse)
    }
    @Test fun homeUsesCourseProjectionForTodayWeekAndNextCourse() {
        val home = projectHome(BoundaryFixtures.crossPeriodCourse(), AppSettings(), now)
        assertEquals(CourseAvailability.Ready, home.courseAvailability)
        assertEquals(1, home.todayCourseCount)
        assertEquals(1, home.weekCourseCount)
        assertEquals("数据结构", home.courses.single().title)
        assertEquals("第 1-4 节", home.courses.single().periods)
        assertEquals("数据结构", home.nextCourse?.title)
        assertEquals("今天", home.nextCourseHint)
    }
    @Test fun importedCoursesWithoutSemesterBindingAppearAfterSemesterSetup() {
        val snapshot=DataSnapshot(
            semesters=listOf(SemesterRecord("s","秋季",LocalDate.of(2026,9,7),20)),
            courses=listOf(CourseRecord("c","学校课程",source=RecordSource.SYNC,externalId="school-course")),
            schedules=listOf(ScheduleRecord("slot","c",2,1,2,ScheduleWeeks.fromRange(1,20))),
        )
        val home=projectHome(snapshot,AppSettings(onboardingComplete=true),now)
        assertEquals(CourseAvailability.Ready,home.courseAvailability)
        assertEquals(1,home.todayCourseCount)
        assertEquals(1,home.weekCourseCount)
        assertEquals("学校课程",home.nextCourse?.title)
    }
    @Test fun endedCourseKeepsItsDailyCountAndEndHint() {
        val snapshot=DataSnapshot(
            semesters=listOf(SemesterRecord("s","秋季",LocalDate.of(2026,9,7),20)),
            courses=listOf(CourseRecord("c","学校课程",semesterId="s")),
            schedules=listOf(ScheduleRecord("slot","c",2,1,1,ScheduleWeeks.fromRange(1,20))),
            periods=listOf(PeriodRecord("s",1,LocalTime.of(8,0),LocalTime.of(8,45))),
        )
        val home=projectHome(snapshot,AppSettings(onboardingComplete=true),now)
        assertEquals(1,home.todayCourseCount)
        assertEquals(1,home.courses.size)
        assertNull(home.nextCourse)
        assertEquals("今日课程已结束",home.nextCourseHint)
    }
    @Test fun syncUsesModuleSummaryAndDoesNotClaimSuccessWithoutTimestamp() {
        val empty = projectHome(DataSnapshot(),AppSettings(),now)
        assertEquals("尚未成功同步",empty.lastSyncLabel)
        val records = listOf(
            SyncRecord(SyncModule.COURSES,status=com.coursekeeper.data.model.SyncStatus.IDLE,lastSuccessAt=Instant.parse("2026-11-10T01:00:00Z")),
            SyncRecord(SyncModule.TASKS,status=com.coursekeeper.data.model.SyncStatus.ERROR),
        )
        val partial = projectHome(DataSnapshot(syncRecords=records),AppSettings(),now)
        assertEquals(com.coursekeeper.home.SyncStatus.Partial,partial.status)
        assertTrue(partial.lastSyncLabel.contains("作业 尚未成功"))
    }
    @Test fun deletedCourseAssociationRemainsVisibleAsDeleted() {
        val row = task("关联").copy(courseId="deleted")
        val home = projectHome(DataSnapshot(tasks=listOf(row)),AppSettings(),now)
        assertEquals("课程已删除",home.tasks.single().course)
    }

    @Test fun teachingWeekUsesBMondayNormalisationAndDistinguishesOutsideTerm() {
        val snapshot=DataSnapshot(semesters=listOf(
            SemesterRecord("s","秋季",LocalDate.of(2026,9,9),20),
        ))
        assertEquals("第 10 教学周",projectHome(snapshot,AppSettings(),now).weekLabel)
        val outside=projectHome(snapshot,AppSettings(),LocalDateTime.of(2027,6,1,10,0))
        assertEquals("非教学周",outside.weekLabel)
        assertEquals(CourseAvailability.NotInTerm,outside.courseAvailability)
    }

    @Test fun homePressureIncludesBPeriodLoadAndFLevelAndExplanation() {
        val base=BoundaryFixtures.crossPeriodCourse()
        val snapshot=base.copy(schedules=base.schedules.map { it.copy(endPeriod=8) })
        val home=projectHome(snapshot,AppSettings(),now)
        // F 的已定口径：8 节课程、无任务，课程负荷贡献 30 分。
        assertEquals(30,home.pressureScore)
        assertEquals("适中",home.pressureLevel)
        assertEquals("压力指数 30 · 适中",home.pressureLabel)
        assertEquals("今日课程较多",home.pressureExplanation)
        assertEquals(0,home.weekTaskCount)
    }

    @Test fun taskOnlyPressureExplainsMissingSemesterInsteadOfInventingCourseLoad() {
        val home=projectHome(DataSnapshot(tasks=listOf(task("临近任务",now.plusHours(1)))),AppSettings(),now)
        assertNotNull(home.pressureScore)
        assertTrue(home.pressureExplanation.contains("课程负荷待设置学期"))
        assertEquals("待设置学期",home.weekLabel)
        assertNull(projectHome(DataSnapshot(),AppSettings(),now).pressureScore)
    }

    @Test fun partialSyncDetailsRetainBothSuccessAndFailureAndCachedTasks() {
        val records=listOf(
            SyncRecord(SyncModule.COURSES,status=com.coursekeeper.data.model.SyncStatus.IDLE,
                lastSuccessAt=Instant.parse("2026-11-10T01:00:00Z")),
            SyncRecord(SyncModule.TASKS,status=com.coursekeeper.data.model.SyncStatus.ERROR,
                lastSuccessAt=Instant.parse("2026-11-09T01:00:00Z"),errorCode=SyncFailure.PARSE_ERROR.name),
        )
        val home=projectHome(DataSnapshot(tasks=listOf(task("缓存任务")),syncRecords=records),AppSettings(),now)
        assertEquals(SyncStatus.Partial,home.status)
        assertEquals("已同步",home.syncModules.single { it.name=="课表" }.statusLabel)
        val failed=home.syncModules.single { it.name=="作业" }
        assertEquals("同步失败",failed.statusLabel)
        assertTrue(failed.lastSuccessLabel.startsWith("最近成功："))
        assertEquals("学校页面解析失败，已有数据已保留",failed.errorMessage)
        assertEquals("缓存任务",home.tasks.single().title)
        assertFalse(home.hasExpiredSession)
    }

    @Test fun partialSyncWithExpiredModuleStillRequestsSchoolLogin() {
        val records=listOf(
            SyncRecord(SyncModule.COURSES,status=com.coursekeeper.data.model.SyncStatus.IDLE,
                lastSuccessAt=Instant.parse("2026-11-10T01:00:00Z")),
            SyncRecord(SyncModule.TASKS,status=com.coursekeeper.data.model.SyncStatus.EXPIRED,
                errorCode=SyncFailure.EXPIRED.name),
        )
        val home=projectHome(DataSnapshot(syncRecords=records),AppSettings(),now)
        assertEquals(SyncStatus.Partial,home.status)
        assertTrue(home.hasExpiredSession)
        assertEquals(SyncStatus.Expired,home.syncModules.single { it.name=="作业" }.status)
        assertEquals("尚未成功同步",home.syncModules.single { it.name=="作业" }.lastSuccessLabel)
    }

    @Test fun syncFailureReasonsAreReadableAndUnknownCodeIsNotExposed() {
        val cases=mapOf(
            SyncFailure.OFFLINE.name to "网络不可用，已有数据可继续使用",
            SyncFailure.EXPIRED.name to "请重新登录学校账号",
            SyncFailure.TIMEOUT.name to "连接超时，已有数据已保留",
            SyncFailure.PARSE_ERROR.name to "学校页面解析失败，已有数据已保留",
            SyncFailure.REMOTE_ERROR.name to "学校服务暂不可用",
            SyncFailure.VERSION_CONFLICT.name to "本地数据已被修改，本次同步未完成",
            SyncFailure.INVALID_DATA.name to "同步数据未通过校验",
            SyncFailure.INTERRUPTED.name to "上次同步已中断",
            "future-unknown-code" to "同步暂未完成，已有数据可继续使用",
        )
        cases.forEach { (code, expected) ->
            val home=projectHome(DataSnapshot(syncRecords=listOf(
                SyncRecord(SyncModule.COURSES,status=com.coursekeeper.data.model.SyncStatus.ERROR,errorCode=code),
            )),AppSettings(),now)
            assertEquals(expected,home.syncModules.single { it.name=="课表" }.errorMessage)
        }
        val empty=projectHome(DataSnapshot(),AppSettings(),now)
        assertTrue(empty.syncModules.all { it.statusLabel=="尚未成功同步" })
        assertTrue(empty.syncModules.all { it.errorMessage==null })
    }
}
