package com.coursekeeper.school

import android.webkit.CookieManager
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 退出登录只清会话 Cookie；课程任务由 E 保留（计划书 §四"登录过期/退出"）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SchoolLogoutTest {

    @Test
    fun logoutClearsSchoolSessionCookies() {
        val manager = CookieManager.getInstance()
        manager.setCookie(SCHOOL_URL, "JSESSIONID=secret-session")
        manager.flush()

        SchoolLogout.clear()

        assertNull(manager.getCookie(SCHOOL_URL))
    }

    private companion object {
        const val SCHOOL_URL = "https://jwxt.example.edu.cn/index"
    }
}
