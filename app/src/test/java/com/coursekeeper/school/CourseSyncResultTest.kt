package com.coursekeeper.school

import com.coursekeeper.data.CourseDraft
import com.coursekeeper.data.sync.ImportedCourse
import com.coursekeeper.data.sync.ModuleResult
import com.coursekeeper.data.sync.SyncFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseSyncResultTest {
    private val parsed = ScheduleParse(listOf(ImportedCourse("c1", CourseDraft("课程"))), 0, null, emptyList())

    @Test fun cleanNonemptyScheduleAuthorizesMirrorCleanup() {
        assertEquals(ModuleResult.Success(parsed.courses, completeSnapshot = true), parsed.toCourseSyncResult())
    }

    @Test fun emptyOrIncompleteScheduleLeavesPreviousDataInPlace() {
        val failure = ModuleResult.Failure(SyncFailure.PARSE_ERROR)
        assertEquals(failure, parsed.copy(courses = emptyList()).toCourseSyncResult())
        assertEquals(failure, parsed.copy(skippedCourses = 1).toCourseSyncResult())
        assertEquals(failure, parsed.copy(warnings = listOf("周次部分无法解析")).toCourseSyncResult())
    }

    @Test fun skippedWeekInAnOtherwiseValidPagePreventsMirrorCleanup() {
        val html = requireNotNull(javaClass.getResource("/school/stu-timetable.html")).readText()
        val parser = StuJwPageParser()
        assertTrue(parser.parseSchedule(html).toCourseSyncResult() is ModuleResult.Success)

        val partial = parser.parseSchedule(html.replaceFirst("3-18(周)[01-02节]", "3-18,65(周)[01-02节]"))
        assertTrue(partial.courses.isNotEmpty())
        assertEquals(0, partial.skippedCourses)
        assertTrue(partial.warnings.isNotEmpty())
        assertEquals(ModuleResult.Failure(SyncFailure.PARSE_ERROR), partial.toCourseSyncResult())
    }
}
