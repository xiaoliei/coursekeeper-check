package com.coursekeeper

import com.coursekeeper.data.model.SyncModule
import com.coursekeeper.data.model.SyncRecord
import com.coursekeeper.data.model.SyncStatus
import com.coursekeeper.data.sync.SyncRunResult
import com.coursekeeper.data.sync.SyncSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 登录完成页的同步失败提示文案（D 复核①：同步失败不得谎报“导入完成”）。 */
class SyncFailureTextTest {

    @Test
    fun humanReadableForSingleModule() {
        val text = syncFailureText(
            listOf(SyncRecord(module = SyncModule.COURSES, status = SyncStatus.OFFLINE, errorCode = "OFFLINE")),
        )
        assertEquals("同步未完成：课表：网络不可用。稍后可在首页重试。", text)
    }

    @Test
    fun humanReadableForMultipleModules() {
        val text = syncFailureText(
            listOf(
                SyncRecord(module = SyncModule.COURSES, status = SyncStatus.ERROR, errorCode = "PARSE_ERROR"),
                SyncRecord(module = SyncModule.TASKS, status = SyncStatus.EXPIRED, errorCode = "EXPIRED"),
            ),
        )
        assertTrue(text.startsWith("同步未完成："))
        assertTrue(text.contains("课表：页面解析失败"))
        assertTrue(text.contains("作业：登录已过期"))
    }

    @Test
    fun unknownErrorCodeFallsBack() {
        val text = syncFailureText(
            listOf(SyncRecord(module = SyncModule.TASKS, status = SyncStatus.ERROR, errorCode = null)),
        )
        assertEquals("同步未完成：作业：未知原因。稍后可在首页重试。", text)
    }

    // ---- 登录后强制同步的结果判定（D 复核②：分支不再藏在 UI 的内联 lambda 里）----

    private fun finished(vararg records: SyncRecord) =
        SyncRunResult.Finished(SyncSummary(status = SyncStatus.IDLE, modules = records.toList()))

    @Test
    fun allModulesSucceededMeansNoMessage() {
        val result = finished(
            SyncRecord(module = SyncModule.COURSES, status = SyncStatus.IDLE),
            SyncRecord(module = SyncModule.TASKS, status = SyncStatus.IDLE),
        )
        assertNull(afterLoginFailureMessage(result))
    }

    @Test
    fun singleModuleFailureIsReported() {
        val result = finished(
            SyncRecord(module = SyncModule.COURSES, status = SyncStatus.OFFLINE, errorCode = "OFFLINE"),
            SyncRecord(module = SyncModule.TASKS, status = SyncStatus.IDLE),
        )
        assertEquals("同步未完成：课表：网络不可用。稍后可在首页重试。", afterLoginFailureMessage(result))
    }

    @Test
    fun partialFailureStillCountsAsFailure() {
        val result = finished(
            SyncRecord(module = SyncModule.COURSES, status = SyncStatus.IDLE),
            SyncRecord(module = SyncModule.TASKS, status = SyncStatus.EXPIRED, errorCode = "EXPIRED"),
        )
        assertEquals("同步未完成：作业：登录已过期。稍后可在首页重试。", afterLoginFailureMessage(result))
    }

    @Test
    fun inProgressIsNotSilentSuccess() {
        assertTrue(afterLoginFailureMessage(SyncRunResult.InProgress)!!.contains("还在进行中"))
    }

    @Test
    fun schoolMismatchPointsToClearableAction() {
        val message = afterLoginFailureMessage(SyncRunResult.SchoolMismatch)!!
        assertTrue(message.contains("其他学校"))
        // 退出登录只清 Cookie，解除不了不一致，提示不能再让用户去“退出登录后重连”。
        assertFalse(message.contains("退出登录"))
    }
}
