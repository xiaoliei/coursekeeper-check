package com.coursekeeper.school

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录弹层关闭时是否复位状态机（D 复核② 2026-11-17）。
 *
 * 完成页与失败页是终态：不复位的话，下次打开弹层会直接停在这两页——
 * 完成页像是刚导完，失败页则显示上一次的旧错误。
 */
class SchoolLoginSheetRuleTest {

    private val school = SchoolCatalog.default

    @Test
    fun terminalStepsResetOnDismiss() {
        assertTrue(resetsOnDismiss(LoginStep.Done(school)))
        assertTrue(resetsOnDismiss(LoginStep.Failure(school, "同步未完成：课表：网络不可用。")))
    }

    @Test
    fun inFlightStepsKeepState() {
        assertFalse(resetsOnDismiss(LoginStep.SelectSchool))
        assertFalse(resetsOnDismiss(LoginStep.WebView(school)))
        assertFalse(resetsOnDismiss(LoginStep.Feedback(school)))
        assertFalse(resetsOnDismiss(LoginStep.Importing(school)))
    }

    /** 失败页没有学校（WebView 加载失败）时同样要复位，不能因为 school 为 null 就留在错误页。 */
    @Test
    fun failureWithoutSchoolStillResets() {
        assertTrue(resetsOnDismiss(LoginStep.Failure(school = null, message = "网页加载失败")))
    }
}
