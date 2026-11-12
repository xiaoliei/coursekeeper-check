package com.coursekeeper.course

import com.coursekeeper.data.CourseDraft
import com.coursekeeper.data.WriteReceipt
import java.time.LocalDate

/**
 * 课程写侧（成员 B，第 4 步）。
 *
 * 校验、版本递增、排课整组替换、墓碑都由 E 的 Repository 权威执行；
 * 这里只提供界面对话框需要的窄接口，便于 ViewModel 单测替换成假实现。
 */
interface CourseWriter {

    suspend fun createCourse(draft: CourseDraft, requestId: String? = null): WriteReceipt

    suspend fun updateCourse(
        id: String,
        expectedVersion: Long,
        draft: CourseDraft,
        requestId: String? = null,
    ): WriteReceipt

    suspend fun deleteCourse(id: String, expectedVersion: Long, requestId: String? = null): WriteReceipt

    /**
     * 学期 + 节次是一个版本化聚合（E 的 `saveSemester`）。
     * `expectedVersion` 为 null 表示首次创建；节次沿用库中已有配置，不自行编造时刻。
     */
    suspend fun configureSemester(
        semesterId: String,
        name: String,
        startsOn: LocalDate,
        weekCount: Int,
        expectedVersion: Long? = null,
    ): WriteReceipt
}
