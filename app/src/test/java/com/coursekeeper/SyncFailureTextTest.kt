package com.coursekeeper

import com.coursekeeper.data.model.SyncModule
import com.coursekeeper.data.model.SyncRecord
import com.coursekeeper.data.model.SyncStatus
import org.junit.Assert.assertEquals
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
}
